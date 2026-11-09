#!/bin/bash
#
# jFiltra smoke test
#
# Builds server and client, runs them in a temporary directory and checks the main
# behaviours end to end. Needs Java and the Log4j 2 JARs in server/lib and client/lib
# (see README). Nothing outside the temporary directory is changed, apart from the
# build output in server/ and client/.
#
# Usage: test/smoke-test.sh
#   JFILTRA_TEST_PORT   first of two ports to use (default 19555)
#   JFILTRA_TEST_JAVA   java command that runs server and client (default: java),
#                       e.g. a Java 8 runtime to check JARs built with a newer JDK
# Exit code: 0 if all checks pass, 1 otherwise.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${JFILTRA_TEST_PORT:-19555}"
RENAME_PORT=$((PORT + 1))
JAVA="${JFILTRA_TEST_JAVA:-java}"
WORK="$(mktemp -d)"
PIDS=()
PASSED=0
FAILED=0

cleanup() {
  chmod -R u+rwx "$WORK" 2>/dev/null
  for pid in "${PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
  done
  wait 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

check() {
  local description="$1"
  shift
  if "$@"; then
    echo "  PASS  $description"
    PASSED=$((PASSED + 1))
  else
    echo "  FAIL  $description"
    FAILED=$((FAILED + 1))
  fi
}

# Waits up to $1 seconds for the command in the remaining arguments to succeed
wait_for() {
  local seconds="$1"
  shift
  for _ in $(seq 1 $((seconds * 4))); do
    "$@" && return 0
    sleep 0.25
  done
  return 1
}

count() {
  grep -c -- "$1" "$2" 2>/dev/null || true
}

random_key() {
  head -c 32 /dev/urandom | base64 | tr -d '\n'
}

# Server and client need a UTF-8 locale (same selection as the start scripts)
if [ "$(locale charmap 2>/dev/null)" != "UTF-8" ]; then
  for loc in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
    if locale -a 2>/dev/null | grep -qx "$loc"; then
      export LC_ALL="$loc"
      break
    fi
  done
fi

echo "Building..."
"$ROOT/server/script/compile.sh" >/dev/null || { echo "Server build failed"; exit 1; }
"$ROOT/client/script/compile.sh" >/dev/null || { echo "Client build failed"; exit 1; }

# Major class file version of the first class in a JAR (52 = Java 8)
class_version() {
  local jar="$1" class
  class="$(unzip -Z1 "$jar" | grep '\.class$' | head -1)"
  unzip -p "$jar" "$class" | od -An -tu1 -j7 -N1 | tr -d ' '
}

echo "Unit tests"
mkdir -p "$WORK/unit"
JAVAC="javac"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && JAVAC="$JAVA_HOME/bin/javac"
if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/client/lib/*" -d "$WORK/unit" "$ROOT/test/FileMaskTest.java" "$ROOT/test/FileOrderTest.java" 2>/dev/null; then
  check "filemask matcher unit tests" "$JAVA" -cp "$WORK/unit:$ROOT/client/lib/*" client.FileMaskTest
  check "file.order unit tests" "$JAVA" -cp "$WORK/unit:$ROOT/client/lib/*" client.FileOrderTest
else
  check "filemask matcher unit tests compile" false
fi
if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/server/lib/*" -d "$WORK/unit" "$ROOT/test/NameConflictTest.java" "$ROOT/test/ParallelSendTest.java" 2>/dev/null; then
  check "on.name.conflict unit tests" "$JAVA" -cp "$WORK/unit:$ROOT/server/lib/*" server.NameConflictTest
else
  check "on.name.conflict unit tests compile" false
fi

check "Java version scripts (48 normal, EA, build-suffix and invalid cases)" bash "$ROOT/test/java-version-test.sh"

# Isolated server with a fault-injecting ServerSocket, no OS resource exhaustion needed.
if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/server/lib/*" -d "$WORK/unit" "$ROOT/test/ServerConnectionTest.java" 2>/dev/null; then
  printf 'server.port=%s\nsocket.timeout.seconds=1\n' "$((PORT + 8))" > "$WORK/connection.properties"
  (cd "$WORK" && "$JAVA" -Dlog4j2.configurationFile="$ROOT/server/config/log4j2.properties" -cp "$WORK/unit:$ROOT/server/lib/*" server.ServerConnectionTest "$WORK/connection.properties" > connection.log 2>&1)
  check "accept failure recovers and shutdown terminates" [ "$?" = 0 ]
  check "accept error is one line, not a startup failure" grep -q 'Error accepting connection:.*injected transient accept failure' "$WORK/connection.log"
  check "EOF and timeout logged without stack traces" bash -c '! grep -Eq "Error handling client|Error starting server|^[[:space:]]+at " "$1"' _ "$WORK/connection.log"
  check "connect-and-close is not logged at info level" [ "$(count 'Client connected:' "$WORK/connection.log")" = 3 ]
else
  check "connection regression tests compile" false
fi

if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/client/lib/*" -d "$WORK/unit" "$ROOT/test/AfterSendTest.java" 2>/dev/null; then
  mkdir -p "$WORK/after-send-test"
  "$JAVA" -Dlog4j2.configurationFile="$ROOT/client/config/log4j2.properties" -cp "$WORK/unit:$ROOT/client/lib/*" client.AfterSendTest "$WORK/after-send-test" > "$WORK/after-send-test.log" 2>&1
  check "cleanup permission races: delete/move, once stops and loop retries cleanup only" [ "$?" = 0 ]
else
  check "cleanup permission-race tests compile" false
fi
if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/server/lib/*" -d "$WORK/unit" "$ROOT/test/ServerReloadTest.java" 2>/dev/null; then
  "$JAVA" -Dlog4j2.configurationFile="$ROOT/server/config/log4j2.properties" -cp "$WORK/unit:$ROOT/server/lib/*" server.ServerReloadTest "$WORK/reload-test" > "$WORK/reload-test.log" 2>&1
  check "reload detects size and unsettled/two-step writes" [ "$?" = 0 ]
  check "reload error names the label and reason" grep -q 'Encryption key for test is too short' "$WORK/reload-test.log"
  check "reload error does not expose the key value" bash -c '! grep -q SecretLeak_X "$1"' _ "$WORK/reload-test.log"
else
  check "reload regression tests compile" false
fi

echo "Build checks"
check "server JAR runs on Java 8 (class version 52)" [ "$(class_version "$ROOT/server/lib/jfiltra-server.jar")" = "52" ]
check "client JAR runs on Java 8 (class version 52)" [ "$(class_version "$ROOT/client/lib/jfiltra-client.jar")" = "52" ]

# Copy built applications and configs into the temporary directory
for side in server server-rename client client2 client3 client4 client5; do
  src="${side%%[0-9-]*}"
  src="${src%-rename}"
  mkdir -p "$WORK/$side"
  cp -R "$ROOT/$src/lib" "$ROOT/$src/config" "$WORK/$side/"
done
mkdir -p "$WORK/src1" "$WORK/src2" "$WORK/src3" "$WORK/src4" "$WORK/received/client3"

KEY1="$(random_key)"
KEY2="$(random_key)"
KEY3="$(random_key)"
KEY4="$(random_key)"
KEY5="$(random_key)"
WRONG_KEY="$(random_key)"

write_server_config() {
  cat > "$1/config/server.properties" <<EOF
server.port=$2
server.bind.address=127.0.0.1
client.keys.path=config/client_keys.properties
client.paths.config=config/client_paths.properties
max.file.size.mb=1
max.connections=25
max.connections.per.ip=25
EOF
  [ -n "$3" ] && echo "on.name.conflict=$3" >> "$1/config/server.properties"
}
write_server_config "$WORK/server" "$PORT" reject
printf 'client1=%s\nclient2=%s\nclient4=%s\nclient5=%s\n' "$KEY1" "$KEY2" "$KEY4" "$KEY5" > "$WORK/server/config/client_keys.properties"
printf 'client1=%s\nclient2=%s\nclient4=%s\nclient5=%s\n' "$WORK/received/client1" "$WORK/received/client2" "$WORK/received/client4" "$WORK/received/client5 " > "$WORK/server/config/client_paths.properties"
write_server_config "$WORK/server-rename" "$RENAME_PORT" rename
printf 'client3=%s\n' "$KEY3" > "$WORK/server-rename/config/client_keys.properties"
printf 'client3=%s\n' "$WORK/received/client3" > "$WORK/server-rename/config/client_paths.properties"

write_client_config() {
  cat > "$1/config/client.properties" <<EOF
client.label=$2
source.directories=$3
server.host=127.0.0.1
server.port=$5
encryption.key=$4
polling.interval.seconds=1
stable.polls=1
EOF
}
write_client_config "$WORK/client" client1 "$WORK/src1,$WORK/locked" "$KEY1" "$PORT"
mkdir -p "$WORK/locked" && chmod 000 "$WORK/locked"
write_client_config "$WORK/client2" client2 "$WORK/src2" "$WRONG_KEY" "$PORT"
write_client_config "$WORK/client3" client3 "$WORK/src3" "$KEY3" "$RENAME_PORT"
write_client_config "$WORK/client4" client4 "$WORK/src4" "$KEY4" "$PORT"
echo 'filemask=*.csv; report_??.TXT' >> "$WORK/client4/config/client.properties"

start() {
  local dir="$1" class="$2" config="$3" jar="$4"
  (cd "$dir" && exec "$JAVA" -Dlog4j2.configurationFile="$dir/config/log4j2.properties" \
      -cp "$dir/lib/$jar:$dir/lib/log4j-api-2.26.1.jar:$dir/lib/log4j-core-2.26.1.jar" \
      "$class" "$dir/config/$config" > "$dir/console.log" 2>&1) &
  PIDS+=("$!")
}

SERVER_LOG="$WORK/server/logs/server_jfiltra.log"
CLIENT_LOG="$WORK/client/logs/client_jfiltra.log"
CLIENT2_LOG="$WORK/client2/logs/client_jfiltra.log"
R1="$WORK/received/client1"

echo "Startup checks"
(cd "$WORK/client" && env LC_ALL=C LANG=C "$JAVA" -cp "lib/*" client.JFiltraClient config/client.properties > locale.log 2>&1)
check "client refuses to start without a UTF-8 locale" [ "$?" -eq 1 ]
check "locale error message names the fix" grep -q "File names need a UTF-8 locale" "$WORK/client/locale.log"
(cd "$WORK/server" && env LC_ALL=C LANG=C "$JAVA" -cp "lib/*" server.JFiltraServer config/server.properties > locale.log 2>&1)
check "server refuses to start without a UTF-8 locale" [ "$?" -eq 1 ]
cp -R "$WORK/server" "$WORK/server-weak"
printf 'client1=CHANGE_ME_client1_secret_key_000\n' > "$WORK/server-weak/config/client_keys.properties"
(cd "$WORK/server-weak" && "$JAVA" -cp "lib/*" server.JFiltraServer config/server.properties > console.log 2>&1)
weak_exit=$?
check "placeholder key refused at startup" [ "$weak_exit" -eq 1 ]
check "placeholder error message names the problem" grep -q "CHANGE_ME placeholder" "$WORK/server-weak/console.log"
(cd "$WORK/client" && "$JAVA" -cp "lib/*" client.JFiltraClient config/missing.properties > missing.log 2>&1)
missing_exit=$?
check "missing config file refused at startup" [ "$missing_exit" -eq 1 ]
check "missing config message shows the full path" grep -q "Configuration file not found: /.*/client/config/missing.properties" "$WORK/client/missing.log"

cp -R "$WORK/client4" "$WORK/client-badmask"
echo 'filemask=sub/*.csv' >> "$WORK/client-badmask/config/client.properties"
(cd "$WORK/client-badmask" && "$JAVA" -cp "lib/*" client.JFiltraClient config/client.properties > console.log 2>&1)
badmask_exit=$?
check "invalid filemask refused at startup" [ "$badmask_exit" -eq 1 ]
check "invalid filemask message names the mask" grep -q "Invalid filemask 'sub/\*.csv'" "$WORK/client-badmask/console.log"

# Raw backslashes must be caught before Properties.load silently removes them.
for mask in 'sub\*.csv' 'sub\\*.csv'; do
  cp -R "$WORK/client4" "$WORK/client-rawmask"
  printf 'filemask=%s\n' "$mask" >> "$WORK/client-rawmask/config/client.properties"
  (cd "$WORK/client-rawmask" && "$JAVA" -cp "lib/*" client.JFiltraClient config/client.properties > console.log 2>&1)
  check "raw backslash filemask rejected ($mask)" [ "$?" = 1 ]
  check "raw backslash error names filemask" grep -q 'Setting filemask must not contain a backslash' "$WORK/client-rawmask/console.log"
  rm -rf "$WORK/client-rawmask"
done
for pattern in 'foo\*.tmp' 'foo\\*.tmp'; do
  cp -R "$WORK/client4" "$WORK/client-rawignore"
  printf 'ignore.patterns=%s\n' "$pattern" >> "$WORK/client-rawignore/config/client.properties"
  (cd "$WORK/client-rawignore" && "$JAVA" -cp "lib/*" client.JFiltraClient config/client.properties > console.log 2>&1)
  check "raw ignore.patterns backslash rejected ($pattern)" [ "$?" = 1 ]
  check "raw ignore.patterns error names setting" grep -q 'Setting ignore.patterns must not contain a backslash' "$WORK/client-rawignore/console.log"
  rm -rf "$WORK/client-rawignore"
done
for pattern in '[abc' '{a,b}'; do
  cp -R "$WORK/client4" "$WORK/client-badignore"
  printf 'ignore.patterns=%s\n' "$pattern" >> "$WORK/client-badignore/config/client.properties"
  (cd "$WORK/client-badignore" && "$JAVA" -cp "lib/*" client.JFiltraClient config/client.properties > console.log 2>&1)
  check "invalid ignore.patterns rejected ($pattern)" [ "$?" = 1 ]
  check "invalid ignore.patterns message names setting and pattern" grep -q 'Setting ignore.patterns has an invalid pattern' "$WORK/client-badignore/console.log"
  rm -rf "$WORK/client-badignore"
done

# Files present before the clients start
echo "a,b" > "$WORK/src4/data.csv"
echo "upper" > "$WORK/src4/UPPER.CSV"
echo "report" > "$WORK/src4/report_01.txt"
echo "not matching" > "$WORK/src4/notes.txt"
echo "not matching" > "$WORK/src4/report_001.txt"
echo "hello jFiltra" > "$WORK/src1/normal.txt"
echo "secret outside the source directory" > "$WORK/secret.txt"
ln -s "$WORK/secret.txt" "$WORK/src1/link.txt"
echo "draft" > "$WORK/src1/draft.tmp"
echo "draft" > "$WORK/src1/DRAFT.TMP"
# Older than everything else, so it is first in file.order=time: must not block other files
head -c 41943040 /dev/urandom > "$WORK/src1/huge.bin" && touch -d '2026-01-01 00:00' "$WORK/src1/huge.bin"
echo "hidden" > "$WORK/src1/.hidden"
head -c 2097152 /dev/urandom > "$WORK/src1/big.bin"
echo "sent with the wrong key" > "$WORK/src2/wrongkey.txt"

start "$WORK/server" server.JFiltraServer server.properties jfiltra-server.jar
wait_for 15 grep -q "Server is running" "$SERVER_LOG" 2>/dev/null || { echo "Server did not start"; cat "$WORK/server/console.log"; exit 1; }
start "$WORK/client" client.JFiltraClient client.properties jfiltra-client.jar
start "$WORK/client2" client.JFiltraClient client.properties jfiltra-client.jar
start "$WORK/client4" client.JFiltraClient client.properties jfiltra-client.jar

(cd "$WORK/server" && "$JAVA" -cp "lib/*" server.JFiltraServer config/server.properties > port-in-use.log 2>&1)
in_use_exit=$?
check "second server on a used port exits with code 1" [ "$in_use_exit" -eq 1 ]
check "server listens on the configured bind address only" grep -q "listening on 127.0.0.1" "$SERVER_LOG"

echo "Transfers"
wait_for 20 [ -f "$R1/normal.txt" ]
check "normal file delivered with the same content" cmp -s "$R1/normal.txt" <(echo "hello jFiltra")
sent_once() { [ "$(count 'File sent: normal.txt' "$CLIENT_LOG")" -eq 1 ]; }
check "one log line per sent file" wait_for 5 sent_once
check "source file deleted after delivery" wait_for 5 [ ! -e "$WORK/src1/normal.txt" ]

# Give the clients several polls, so repeated attempts would show up in the logs
sleep 5
check "symbolic link not sent" [ ! -e "$R1/link.txt" ]
check "symbolic link left in place" [ -L "$WORK/src1/link.txt" ]
check "*.tmp file not sent" [ ! -e "$R1/draft.tmp" ]
check "ignore.patterns not case-sensitive (DRAFT.TMP not sent)" [ -f "$WORK/src1/DRAFT.TMP" ]
check "huge file rejected without blocking other files" test ! -e "$R1/huge.bin" -a -f "$WORK/src1/huge.bin"
check "huge file did not pause transfers as 'server unreachable'" [ "$(count 'Will try the server again' "$CLIENT_LOG")" -eq 0 ]
check "huge file rejected right away" wait_for 15 grep -q "huge.bin from client client1: size 4" "$SERVER_LOG"
check "unreadable source directory logged once" [ "$(count 'Cannot read directory' "$CLIENT_LOG")" -eq 1 ]
check "hidden file not sent" [ ! -e "$R1/.hidden" ]
check "oversized file not stored" [ ! -e "$R1/big.bin" ]
check "oversized file kept in source" [ -f "$WORK/src1/big.bin" ]
check "oversized file rejected with 'File too large'" grep -q "Server rejected file big.bin: ERROR: File too large" "$CLIENT_LOG"
check "oversized file sent only once" [ "$(count 'Rejected file big.bin' "$SERVER_LOG")" -eq 1 ]
check "wrong key rejected with 'Decryption failed'" grep -q "Server rejected file wrongkey.txt: ERROR: Decryption failed" "$CLIENT2_LOG"
check "wrong-key file not stored" [ ! -e "$WORK/received/client2/wrongkey.txt" ]
check "wrong-key file kept in source" [ -f "$WORK/src2/wrongkey.txt" ]

echo "File mask"
R4="$WORK/received/client4"
check "file matching the mask delivered" wait_for 15 [ -f "$R4/data.csv" ]
check "mask matches ignoring case" wait_for 10 [ -f "$R4/UPPER.CSV" ]
check "second mask after ';' applies" wait_for 10 [ -f "$R4/report_01.txt" ]
check "matching files deleted from source" wait_for 5 [ ! -e "$WORK/src4/data.csv" ]
check "non-matching file not sent" [ ! -e "$R4/notes.txt" ]
check "'?' matches exactly one character" [ ! -e "$R4/report_001.txt" ]
check "non-matching files left in source" test -f "$WORK/src4/notes.txt" -a -f "$WORK/src4/report_001.txt"
check "client logs the configured mask" grep -q "File Mask: \*.csv; report_??.TXT" "$WORK/client4/logs/client_jfiltra.log"
check "default mask sends everything (no filemask in client1 config)" grep -q "File Mask: \*$" "$CLIENT_LOG"

echo "One-time run"
check "loop is the default run mode" grep -q "Run Mode: loop" "$CLIENT_LOG"
check "time is the default file order" grep -q "File Order: time" "$CLIENT_LOG"
C5="$WORK/client5"
write_client_config "$C5" client5 "$WORK/src5" "$KEY5" "$PORT"
echo "run.mode=Once" >> "$C5/config/client.properties"
run_once() {
  # $1: file.order; prints the exit code
  sed -i '/^file.order=/d' "$C5/config/client.properties"
  [ -n "$1" ] && echo "file.order=$1" >> "$C5/config/client.properties"
  # Same Log4j config as the loop clients so WARN lines (e.g. reserved "sent") are kept
  (cd "$C5" && "$JAVA" -Dlog4j2.configurationFile="$C5/config/log4j2.properties"       -cp "lib/*" client.JFiltraClient config/client.properties > once.log 2>&1)
  echo $?
}
# a: largest and newest, b: smallest, c: oldest
make_outbox() {
  rm -rf "$WORK/src5" && mkdir -p "$WORK/src5"
  head -c 300 /dev/zero | tr '\0' a > "$WORK/src5/a.txt"; touch -d '2026-01-03 10:00' "$WORK/src5/a.txt"
  head -c 100 /dev/zero | tr '\0' b > "$WORK/src5/b.txt"; touch -d '2026-01-02 10:00' "$WORK/src5/b.txt"
  head -c 200 /dev/zero | tr '\0' c > "$WORK/src5/c.txt"; touch -d '2026-01-01 10:00' "$WORK/src5/c.txt"
}
# The one file sent: the only one missing from the outbox
sent_file() { for f in a b c; do [ -e "$WORK/src5/$f.txt" ] || printf '%s' "$f"; done; }
for case in ":c" "time:c" "!time:a" "size:b" "!size:a" "name:a" "!Name:c"; do
  order="${case%%:*}"; want="${case##*:}"
  make_outbox
  code="$(run_once "$order")"
  check "once, file.order=${order:-(default)}: exit 0 and only $want.txt sent" [ "$code:$(sent_file)" = "0:$want" ]
done
check "storage path with trailing space is trimmed" [ ! -e "$WORK/received/client5 " ]
make_outbox
head -c 2097152 /dev/zero > "$WORK/src5/z.bin"
code="$(run_once '!size')"
check "once: permanently rejected first file skipped, next file sent" [ "$code:$(sent_file)" = "0:a" ]
check "sent file delivered with the same content" cmp -s "$WORK/received/client5/a.txt" <(head -c 300 /dev/zero | tr '\0' a)
rm -rf "$WORK/src5" && mkdir -p "$WORK/src5"
code="$(run_once name)"
check "once with an empty outbox exits with 2" [ "$code" = 2 ]
make_outbox
sed -i "s/^server.port=.*/server.port=$((PORT + 9))/" "$C5/config/client.properties"
code="$(run_once name)"
check "once with an unreachable server exits with 3" [ "$code" = 3 ]
check "file kept after a failed one-time run" [ -f "$WORK/src5/a.txt" ]
sed -i "s/^server.port=.*/server.port=$PORT/" "$C5/config/client.properties"
code="$(run_once date)"
check "invalid file.order exits with 1" [ "$code" = 1 ]
check "invalid file.order message names the values" grep -q "file.order must be time, size or name" "$C5/once.log"
sed -i 's/^run.mode=.*/run.mode=twice/' "$C5/config/client.properties"
code="$(run_once name)"
check "invalid run.mode exits with 1" [ "$code" = 1 ]
check "invalid run.mode message names the values" grep -q "run.mode must be loop or once, but is: twice" "$C5/once.log"
sed -i 's/^run.mode=.*/run.mode=once/' "$C5/config/client.properties"

echo "1.1.8 per-file failure, conflict validation and mapping reloads"
rm -rf "$WORK/src5" "$WORK/received/client5/a.txt" "$WORK/received/client5/b.txt" && mkdir -p "$WORK/src5" "$WORK/received/client5/a.txt"
echo blocked > "$WORK/src5/a.txt"; echo next > "$WORK/src5/b.txt"
code="$(run_once name)"
check "once: retryable first-file rejection does not block next file" [ "$code" = 0 -a -f "$WORK/src5/a.txt" -a ! -e "$WORK/src5/b.txt" ]
code="$(run_once name)"
check "once: all attempted files fail: exit 3" [ "$code" = 3 ]
check "once: retry message says next run" grep -q 'Will retry a.txt on the next run' "$C5/once.log"
check "once: no timed retry is promised" bash -c '! grep -Eq "Will retry .* in [0-9]+ s|Will try the server again in" "$1"' _ "$C5/once.log"
rm -rf "$WORK/received/client5/a.txt"
cp "$WORK/src5/a.txt" "$WORK/received/client5/a.txt"
sed -i "s|^encryption.key=.*|encryption.key=$WRONG_KEY|" "$C5/config/client.properties"
code="$(run_once name)"
check "identical conflict with wrong key: exit 3, not SUCCESS" [ "$code" = 3 -a -f "$WORK/src5/a.txt" ]
check "identical conflict with wrong key reports Decryption failed" grep -q 'ERROR: Decryption failed' "$C5/once.log"
echo different > "$WORK/src5/a.txt"
code="$(run_once name)"
check "reject conflict with wrong key reports Decryption failed" grep -q 'ERROR: Decryption failed' "$C5/once.log"
sed -i "s|^encryption.key=.*|encryption.key=$KEY5|" "$C5/config/client.properties"
rm -f "$WORK/src5/a.txt"
# Rotate one key, then corrupt the file: the new valid key must stay active.
cp "$WORK/server/config/client_keys.properties" "$WORK/keys.saved"
KEY5_NEW="$(random_key)"
sed -i "s|^client5=.*|client5=$KEY5_NEW|" "$WORK/server/config/client_keys.properties"
sed -i "s|^encryption.key=.*|encryption.key=$KEY5_NEW|" "$C5/config/client.properties"
echo rotated > "$WORK/src5/rotated.txt"
code="$(run_once name)"
check "key reload works without restart" [ "$code" = 0 -a -f "$WORK/received/client5/rotated.txt" ]
printf 'client5=\\uZZZZ\n' > "$WORK/server/config/client_keys.properties"
echo retained > "$WORK/src5/retained.txt"
code="$(run_once name)"
check "malformed key reload keeps last good key" [ "$code" = 0 -a -f "$WORK/received/client5/retained.txt" ]
check "malformed key reload logged" grep -q 'Cannot reload client.keys.path; keeping last good configuration' "$SERVER_LOG"
cp "$WORK/keys.saved" "$WORK/server/config/client_keys.properties"
sed -i "s|^encryption.key=.*|encryption.key=$KEY5|" "$C5/config/client.properties"
cp "$WORK/server/config/client_paths.properties" "$WORK/paths.saved"
sed -i "s|^client5=.*|client5=$WORK/reloaded5|" "$WORK/server/config/client_paths.properties"
echo redirected > "$WORK/src5/redirected.txt"
code="$(run_once name)"
check "path reload works without restart" [ "$code" = 0 -a -f "$WORK/reloaded5/redirected.txt" ]
printf 'client5=\\uZZZZ\n' > "$WORK/server/config/client_paths.properties"
echo retained > "$WORK/src5/path-retained.txt"
code="$(run_once name)"
check "malformed path reload keeps last good path" [ "$code" = 0 -a -f "$WORK/reloaded5/path-retained.txt" ]
check "malformed path reload logged" grep -q 'Cannot reload client.paths.config; keeping last good configuration' "$SERVER_LOG"
cp "$WORK/paths.saved" "$WORK/server/config/client_paths.properties"

echo "after.send=move"
check "delete is the default after.send" grep -q "After Send: delete" "$CLIENT_LOG"
echo "after.send=Move " >> "$C5/config/client.properties"
rm -rf "$WORK/src5" && mkdir -p "$WORK/src5" && echo "moved content" > "$WORK/src5/m1.txt"
code="$(run_once name)"
check "move: exit 0 and file gone from source" [ "$code" = 0 -a ! -e "$WORK/src5/m1.txt" ]
check "move: file in sent/ with the same content" cmp -s "$WORK/src5/sent/m1.txt" <(echo "moved content")
check "move: file delivered to the server" cmp -s "$WORK/received/client5/m1.txt" <(echo "moved content")
code="$(run_once name)"
check "move: files in sent/ not sent again (empty outbox exits 2)" [ "$code" = 2 ]
rm -f "$WORK/received/client5/m1.txt"; echo "newer content" > "$WORK/src5/m1.txt"
code="$(run_once name)"
check "move: same name in sent/ replaced" [ "$code" = 0 ] && cmp -s "$WORK/src5/sent/m1.txt" <(echo "newer content")
# D1: a file named sent is never sent (reserved for the archive folder)
rm -rf "$WORK/src5" "$WORK/received/client5/sent" && mkdir -p "$WORK/src5"
echo "blocker" > "$WORK/src5/sent"
echo "other" > "$WORK/src5/other.txt"
code="$(run_once name)"
check "move: file named sent is not delivered" [ -f "$WORK/src5/sent" -a ! -e "$WORK/received/client5/sent" ]
check "move: reserved sent name is logged once" grep -q 'Skipping file named "sent".*reserved for the sent/ directory' "$C5/once.log" "$C5/logs/client_jfiltra.log"
# The pre-check prevents delivery while sent (the file) blocks the archive directory.
check "move: other file stays while sent file blocks the archive dir" [ -f "$WORK/src5/other.txt" ]
check "move: blocking sent file names the problem for the other file" \
  grep -q "Cannot send other.txt: not a writable directory: .*sent" "$C5/once.log" "$C5/logs/client_jfiltra.log"

# With filemask only m2 is a candidate; the same pre-check still prevents delivery.
rm -rf "$WORK/src5" && mkdir -p "$WORK/src5" && echo x > "$WORK/src5/sent" && echo y > "$WORK/src5/m2.txt"
echo "filemask=m*.txt" >> "$C5/config/client.properties"
code="$(run_once name)"
check "move: 'sent' file in the way keeps the file and names the problem" \
  [ -f "$WORK/src5/m2.txt" ] && grep -q "Cannot send m2.txt: not a writable directory: .*sent" "$C5/once.log" "$C5/logs/client_jfiltra.log"
sed -i '/^filemask=/d' "$C5/config/client.properties"

# D2: sent as a symbolic link to a directory
rm -rf "$WORK/src5" "$WORK/archive5" && mkdir -p "$WORK/src5" "$WORK/archive5"
ln -s "$WORK/archive5" "$WORK/src5/sent"
echo "linked content" > "$WORK/src5/m3.txt"
code="$(run_once name)"
check "move: symlink sent/ to a directory: exit 0 and source cleared" [ "$code" = 0 -a ! -e "$WORK/src5/m3.txt" ]
check "move: file landed in the symlink target" cmp -s "$WORK/archive5/m3.txt" <(echo "linked content")
check "move: symlink itself remains" [ -L "$WORK/src5/sent" ]

sed -i 's/^after.send=.*/after.send=archive/' "$C5/config/client.properties"
code="$(run_once name)"
check "invalid after.send exits with 1" [ "$code" = 1 ]
check "invalid after.send message names the values" grep -q "after.send must be delete or move, but is: archive" "$C5/once.log"

echo "Duplicates"
echo "same content" > "$WORK/src1/same.txt"
wait_for 15 [ ! -e "$WORK/src1/same.txt" ]
echo "same content" > "$WORK/src1/same.txt"
check "resent identical file accepted and deleted" wait_for 15 [ ! -e "$WORK/src1/same.txt" ]
check "server logged the identical content" grep -q "identical content: same.txt" "$SERVER_LOG"
echo "different content" > "$WORK/src1/normal.txt"
check "same name with different content rejected" wait_for 15 grep -q "Server rejected file normal.txt: ERROR: File already exists" "$CLIENT_LOG"
# With 1 s polls, retries come after 1, 2 and 4 s: at most 4 attempts in 9 s instead of 9
sleep 9
check "rejected file retried with growing delays" [ "$(count 'Server rejected file normal.txt' "$CLIENT_LOG")" -le 4 ]
check "retry delay logged" grep -q "Will retry normal.txt in" "$CLIENT_LOG"
check "stored file not overwritten" cmp -s "$R1/normal.txt" <(echo "hello jFiltra")
check "rejected file kept in source" [ -f "$WORK/src1/normal.txt" ]

echo "Name conflict: rename"
mkdir -p "$WORK/received/client3"
echo "yesterday's export" > "$WORK/received/client3/export.csv"
echo "today's export" > "$WORK/src3/export.csv"
start "$WORK/server-rename" server.JFiltraServer server.properties jfiltra-server.jar
wait_for 15 grep -q "Server is running" "$WORK/server-rename/logs/server_jfiltra.log" 2>/dev/null
start "$WORK/client3" client.JFiltraClient client.properties jfiltra-client.jar
renamed_file() { ls "$WORK/received/client3" | grep '^export_1\.csv$' | head -1; }
has_renamed_file() { [ -n "$(renamed_file)" ]; }
check "same name with different content stored under a new name" wait_for 15 has_renamed_file
check "renamed file has the new content" cmp -s "$WORK/received/client3/$(renamed_file)" <(echo "today's export")
check "existing file left unchanged" cmp -s "$WORK/received/client3/export.csv" <(echo "yesterday's export")
check "source deleted after storing under a new name" wait_for 5 [ ! -e "$WORK/src3/export.csv" ]

echo "Name conflict: invalid value, default and parallel uploads"
MODE_PORT=$((PORT + 2))
KEYP="$(random_key)"
mkdir -p "$WORK/server-mode"
cp -R "$ROOT/server/lib" "$ROOT/server/config" "$WORK/server-mode/"
printf 'parallel=%s\n' "$KEYP" > "$WORK/server-mode/config/client_keys.properties"
write_server_config "$WORK/server-mode" "$MODE_PORT" Replace
(cd "$WORK/server-mode" && "$JAVA" -cp "lib/*" server.JFiltraServer config/server.properties > invalid.log 2>&1)
invalid_exit=$?
check "invalid on.name.conflict refused at startup" [ "$invalid_exit" -eq 1 ]
check "invalid on.name.conflict message lists the values" grep -q "on.name.conflict must be rename, overwrite, append or reject, but is: Replace" "$WORK/server-mode/invalid.log"
MODE_LOG="$WORK/server-mode/logs/server_jfiltra.log"
for mode in "" OVERWRITE Append; do
  expect="$(echo "${mode:-rename}" | tr 'A-Z' 'a-z')"
  rm -rf "$WORK/received/parallel" "$WORK/server-mode/logs"
  mkdir -p "$WORK/received/parallel"
  printf 'parallel=%s\n' "$WORK/received/parallel" > "$WORK/server-mode/config/client_paths.properties"
  write_server_config "$WORK/server-mode" "$MODE_PORT" "$mode"
  start "$WORK/server-mode" server.JFiltraServer server.properties jfiltra-server.jar
  wait_for 15 grep -q "Server is running" "$MODE_LOG" 2>/dev/null
  if [ -z "$mode" ]; then
    check "default on.name.conflict is rename" grep -q "On Name Conflict: rename" "$MODE_LOG"
  else
    check "on.name.conflict=$mode accepted ignoring case" grep -q "On Name Conflict: $expect" "$MODE_LOG"
  fi
  check "$expect: 20 parallel uploads of one name" "$JAVA" -cp "$WORK/unit" ParallelSendTest "$MODE_PORT" parallel "$KEYP" "$WORK/received/parallel" "$expect" 20
  last=$((${#PIDS[@]} - 1))
  kill "${PIDS[$last]}" 2>/dev/null
  wait "${PIDS[$last]}" 2>/dev/null
  PIDS[$last]=""
done
rm -rf "$WORK/received/parallel" && mkdir -p "$WORK/received/parallel/parallel.csv"
write_server_config "$WORK/server-mode" "$MODE_PORT" overwrite
rm -rf "$WORK/server-mode/logs"
start "$WORK/server-mode" server.JFiltraServer server.properties jfiltra-server.jar
wait_for 15 grep -q "Server is running" "$MODE_LOG" 2>/dev/null
check "overwrite onto a directory: 'File already exists' (retried later)" "$JAVA" -cp "$WORK/unit" ParallelSendTest "$MODE_PORT" parallel "$KEYP" x expect 0 "ERROR: File already exists"
check "directory name reported clearly" grep -q "a directory or other non-file has this name" "$MODE_LOG"
check "negative size rejected" "$JAVA" -cp "$WORK/unit" ParallelSendTest "$MODE_PORT" parallel "$KEYP" x expect -5 "ERROR: File too large"
check "negative size logged as invalid" grep -q "invalid size -5" "$MODE_LOG"

echo "Append with a source file that can't be deleted"
rm -rf "$WORK/received/parallel" && mkdir -p "$WORK/received/parallel" "$WORK/src6"
printf 'parallel=%s\n' "$WORK/received/parallel" > "$WORK/server-mode/config/client_paths.properties"
kill "${PIDS[$((${#PIDS[@]} - 1))]}" 2>/dev/null; wait "${PIDS[$((${#PIDS[@]} - 1))]}" 2>/dev/null
rm -rf "$WORK/server-mode/logs"
write_server_config "$WORK/server-mode" "$MODE_PORT" append
start "$WORK/server-mode" server.JFiltraServer server.properties jfiltra-server.jar
wait_for 15 grep -q "Server is running" "$MODE_LOG" 2>/dev/null
mkdir -p "$WORK/client6" && cp -R "$ROOT/client/lib" "$ROOT/client/config" "$WORK/client6/"
write_client_config "$WORK/client6" parallel "$WORK/src6" "$KEYP" "$MODE_PORT"
# Three separate once runs must not append even once while cleanup is impossible.
for action in delete move; do
  rm -rf "$WORK/src6" && mkdir -p "$WORK/src6"
  echo "$action content" > "$WORK/src6/$action.txt"
  echo "must not send next" > "$WORK/src6/z-$action.txt"
  if [ "$action" = move ]; then mkdir "$WORK/src6/sent"; locked="$WORK/src6/sent"; else locked="$WORK/src6"; fi
  chmod 555 "$locked"
  write_client_config "$WORK/client6" parallel "$WORK/src6" "$KEYP" "$MODE_PORT"
  printf 'run.mode=once\nafter.send=%s\nfile.order=name\n' "$action" >> "$WORK/client6/config/client.properties"
  for attempt in 1 2 3; do
    (cd "$WORK/client6" && "$JAVA" -Dlog4j2.configurationFile="$WORK/client6/config/log4j2.properties" -cp 'lib/*' client.JFiltraClient config/client.properties > "once-$action-$attempt.log" 2>&1)
    check "$action: once run $attempt exits 3 without upload" [ "$?" = 3 -a ! -e "$WORK/received/parallel/$action.txt" -a ! -e "$WORK/received/parallel/z-$action.txt" ]
    check "$action: once run $attempt names blocked directory in one ERROR" [ "$(count "ERROR.*Cannot send .*not a writable directory: $locked" "$WORK/client6/once-$action-$attempt.log")" = 1 ]
  done
  chmod 755 "$locked"
  (cd "$WORK/client6" && "$JAVA" -cp 'lib/*' client.JFiltraClient config/client.properties > "restored-$action.log" 2>&1)
  check "$action: once succeeds after permissions restored" [ "$?" = 0 ]
  check "$action: appended exactly once across four runs" cmp -s "$WORK/received/parallel/$action.txt" <(echo "$action content")
done
# Missing sent/: the source must allow its creation.
rm -rf "$WORK/src6" && mkdir "$WORK/src6"; echo missing > "$WORK/src6/missing.txt"; chmod 555 "$WORK/src6"
(cd "$WORK/client6" && "$JAVA" -cp 'lib/*' client.JFiltraClient config/client.properties > missing-sent.log 2>&1)
check "move: missing sent and read-only source exits 3 without upload" [ "$?" = 3 -a ! -e "$WORK/received/parallel/missing.txt" ]
chmod 755 "$WORK/src6"; rm "$WORK/src6/missing.txt"
write_client_config "$WORK/client6" parallel "$WORK/src6" "$KEYP" "$MODE_PORT"
echo "line" > "$WORK/src6/log.txt" && chmod 555 "$WORK/src6"
start "$WORK/client6" client.JFiltraClient client.properties jfiltra-client.jar
wait_for 15 grep -q "Cannot send log.txt: not a writable directory:" "$WORK/client6/logs/client_jfiltra.log"
sleep 8
check "unwritable source is not uploaded" [ ! -e "$WORK/received/parallel/log.txt" ]
check "unwritable source reported before sending" grep -q "Cannot send log.txt: not a writable directory:" "$WORK/client6/logs/client_jfiltra.log"
chmod 755 "$WORK/src6"
check "file deleted once the directory is writable" wait_for 20 [ ! -e "$WORK/src6/log.txt" ]
check "still appended only once" cmp -s "$WORK/received/parallel/log.txt" <(echo "line")

echo "Server down"
LINES_BEFORE="$(wc -l < "$CLIENT_LOG" | tr -d ' ')"
kill "${PIDS[0]}" 2>/dev/null
wait "${PIDS[0]}" 2>/dev/null
PIDS[0]=""
echo "while the server is down" > "$WORK/src1/later.txt"
# The client may still be retrying normal.txt from the step before (waits up to 16 s)
# and then tries it first: accept the line for either file
check "unreachable server reported in one line" \
  wait_for 40 sh -c "tail -n +$((LINES_BEFORE + 1)) '$CLIENT_LOG' | grep -Eq 'Could not send (later|normal)\.txt: server not reachable'"
check "client waits before trying the server again" grep -q "Will try the server again in" "$CLIENT_LOG"
check "no stack traces in the client log" [ "$(grep -c '^[[:space:]]*at ' "$CLIENT_LOG")" -eq 0 ]
check "file kept while the server is down" [ -f "$WORK/src1/later.txt" ]

echo "Cleanliness"
check "no .part files left in storage" [ -z "$(find "$WORK/received" -name '.jfiltra-*.part')" ]
check "no unexpected errors in server log" [ "$(grep -c 'Error handling client' "$SERVER_LOG")" -eq 0 ]

echo
echo "Passed: $PASSED, failed: $FAILED"
[ "$FAILED" -eq 0 ]
