#!/bin/bash
#
# jFiltra smoke test
#
# Builds server and client, runs them in a temporary directory and checks the main
# behaviours end to end. Needs Java and the Log4j 2 JARs in server/lib and client/lib
# (see README). Nothing outside the temporary directory is changed, apart from the
# build output in server/ and client/.
#
# Usage: test/smoke-test.sh            (port 19555, or set JFILTRA_TEST_PORT)
# Exit code: 0 if all checks pass, 1 otherwise.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${JFILTRA_TEST_PORT:-19555}"
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

# Copy built applications and configs into the temporary directory
for side in server client client2; do
  src="${side%2}"
  mkdir -p "$WORK/$side"
  cp -R "$ROOT/$src/lib" "$ROOT/$src/config" "$WORK/$side/"
done
mkdir -p "$WORK/src1" "$WORK/src2" "$WORK/received"

KEY1="$(random_key)"
KEY2="$(random_key)"
WRONG_KEY="$(random_key)"

cat > "$WORK/server/config/server.properties" <<EOF
server.port=$PORT
client.keys.path=config/client_keys.properties
client.paths.config=config/client_paths.properties
max.file.size.mb=1
EOF
printf 'client1=%s\nclient2=%s\n' "$KEY1" "$KEY2" > "$WORK/server/config/client_keys.properties"
printf 'client1=%s\nclient2=%s\n' "$WORK/received/client1" "$WORK/received/client2" > "$WORK/server/config/client_paths.properties"

write_client_config() {
  cat > "$1/config/client.properties" <<EOF
client.label=$2
source.directories=$3
server.host=localhost
server.port=$PORT
encryption.key=$4
polling.interval.seconds=1
stable.polls=1
EOF
}
write_client_config "$WORK/client" client1 "$WORK/src1" "$KEY1"
write_client_config "$WORK/client2" client2 "$WORK/src2" "$WRONG_KEY"

start() {
  local dir="$1" class="$2" config="$3" jar="$4"
  (cd "$dir" && exec java -Dlog4j2.configurationFile="$dir/config/log4j2.properties" \
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
(cd "$WORK/server-weak" && java -cp "lib/*" server.JFiltraServer config/server.properties > console.log 2>&1)
weak_exit=$?
check "placeholder key refused at startup" [ "$weak_exit" -eq 1 ]
check "placeholder error message names the problem" grep -q "CHANGE_ME placeholder" "$WORK/server-weak/console.log"

# Files present before the clients start
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

echo "Transfers"
wait_for 20 [ -f "$R1/normal.txt" ]
check "normal file delivered with the same content" cmp -s "$R1/normal.txt" <(echo "hello jFiltra")
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

echo "Duplicates"
echo "same content" > "$WORK/src1/same.txt"
wait_for 15 [ ! -e "$WORK/src1/same.txt" ]
echo "same content" > "$WORK/src1/same.txt"
check "resent identical file accepted and deleted" wait_for 15 [ ! -e "$WORK/src1/same.txt" ]
check "server logged the identical content" grep -q "identical content: same.txt" "$SERVER_LOG"
echo "different content" > "$WORK/src1/normal.txt"
check "same name with different content rejected" wait_for 15 grep -q "Server rejected file normal.txt: ERROR: File already exists" "$CLIENT_LOG"
check "stored file not overwritten" cmp -s "$R1/normal.txt" <(echo "hello jFiltra")
check "rejected file kept in source" [ -f "$WORK/src1/normal.txt" ]

echo "Cleanliness"
check "no .part files left in storage" [ -z "$(find "$WORK/received" -name '.jfiltra-*.part')" ]
check "no unexpected errors in server log" [ "$(grep -c 'Error handling client' "$SERVER_LOG")" -eq 0 ]

echo
echo "Passed: $PASSED, failed: $FAILED"
[ "$FAILED" -eq 0 ]
