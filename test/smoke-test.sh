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
if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/client/lib/*" -d "$WORK/unit" "$ROOT/test/FileMaskTest.java" 2>/dev/null; then
  check "filemask matcher unit tests" "$JAVA" -cp "$WORK/unit:$ROOT/client/lib/*" client.FileMaskTest
else
  check "filemask matcher unit tests compile" false
fi
if "$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/server/lib/*" -d "$WORK/unit" "$ROOT/test/NameConflictTest.java" "$ROOT/test/ParallelSendTest.java" 2>/dev/null; then
  check "on.name.conflict unit tests" "$JAVA" -cp "$WORK/unit:$ROOT/server/lib/*" server.NameConflictTest
else
  check "on.name.conflict unit tests compile" false
fi

echo "Build checks"
check "server JAR runs on Java 8 (class version 52)" [ "$(class_version "$ROOT/server/lib/jfiltra-server.jar")" = "52" ]
check "client JAR runs on Java 8 (class version 52)" [ "$(class_version "$ROOT/client/lib/jfiltra-client.jar")" = "52" ]

# Copy built applications and configs into the temporary directory
for side in server server-rename client client2 client3 client4; do
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
printf 'client1=%s\nclient2=%s\nclient4=%s\n' "$KEY1" "$KEY2" "$KEY4" > "$WORK/server/config/client_keys.properties"
printf 'client1=%s\nclient2=%s\nclient4=%s\n' "$WORK/received/client1" "$WORK/received/client2" "$WORK/received/client4" > "$WORK/server/config/client_paths.properties"
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
write_client_config "$WORK/client" client1 "$WORK/src1" "$KEY1" "$PORT"
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
check "non-matching files left in source" [ -f "$WORK/src4/notes.txt" ] && [ -f "$WORK/src4/report_001.txt" ]
check "client logs the configured mask" grep -q "File Mask: \*.csv; report_??.TXT" "$WORK/client4/logs/client_jfiltra.log"
check "default mask sends everything (no filemask in client1 config)" grep -q "File Mask: \*$" "$CLIENT_LOG"

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

echo "Server down"
kill "${PIDS[0]}" 2>/dev/null
wait "${PIDS[0]}" 2>/dev/null
PIDS[0]=""
echo "while the server is down" > "$WORK/src1/later.txt"
check "unreachable server reported in one line" wait_for 10 grep -q "Could not send later.txt: server not reachable" "$CLIENT_LOG"
check "client waits before trying the server again" grep -q "Will try the server again in" "$CLIENT_LOG"
check "no stack traces in the client log" [ "$(grep -c '^[[:space:]]*at ' "$CLIENT_LOG")" -eq 0 ]
check "file kept while the server is down" [ -f "$WORK/src1/later.txt" ]

echo "Cleanliness"
check "no .part files left in storage" [ -z "$(find "$WORK/received" -name '.jfiltra-*.part')" ]
check "no unexpected errors in server log" [ "$(grep -c 'Error handling client' "$SERVER_LOG")" -eq 0 ]

echo
echo "Passed: $PASSED, failed: $FAILED"
[ "$FAILED" -eq 0 ]
