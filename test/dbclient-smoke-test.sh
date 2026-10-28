#!/bin/bash
#
# jFiltra DB client smoke test
#
# Builds server and DB client, runs them against a real database in a temporary
# directory and checks the main behaviours end to end. Creates and drops the tables
# jf_test and jf_archive in the given database.
#
# Usage: JFILTRA_DB_URL=... JFILTRA_DB_DRIVER=/path/driver.jar test/dbclient-smoke-test.sh
#   JFILTRA_DB_URL       JDBC URL of a test database (required, else the test is skipped)
#   JFILTRA_DB_USER      database user (default: none)
#   JFILTRA_DB_PASSWORD  database password (passed to the DB client via db.password.env)
#   JFILTRA_DB_DRIVER    JDBC driver JAR (required)
#   JFILTRA_DB_TYPES     column types for: decimal timestamp text, default for PostgreSQL
#                        "DECIMAL(12,2) TIMESTAMP(3) TEXT"; MySQL/MariaDB: "DECIMAL(12,2)
#                        DATETIME(3) TEXT"; Oracle: "NUMBER(12,2) TIMESTAMP(3) CLOB"
#   JFILTRA_TEST_PORT    server port (default 19655)
#   JFILTRA_TEST_JAVA    java command (default: java)
# Exit code: 0 if all checks pass (or skipped), 1 otherwise.

set -u

if [ -z "${JFILTRA_DB_URL:-}" ] || [ -z "${JFILTRA_DB_DRIVER:-}" ]; then
  echo "Skipped: set JFILTRA_DB_URL and JFILTRA_DB_DRIVER to run the DB client smoke test"
  exit 0
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JFILTRA_DB_DRIVER="$(cd "$(dirname "$JFILTRA_DB_DRIVER")" && pwd)/$(basename "$JFILTRA_DB_DRIVER")"
PORT="${JFILTRA_TEST_PORT:-19655}"
JAVA="${JFILTRA_TEST_JAVA:-java}"
DB_USER="${JFILTRA_DB_USER:-}"
export JFILTRA_DB_PASSWORD="${JFILTRA_DB_PASSWORD:-}"
read -r T_DEC T_TS T_TEXT <<< "${JFILTRA_DB_TYPES:-DECIMAL(12,2) TIMESTAMP(3) TEXT}"
WORK="$(mktemp -d)"
PIDS=()
PASSED=0
FAILED=0

if [ "$(locale charmap 2>/dev/null)" != "UTF-8" ]; then
  for loc in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
    if locale -a 2>/dev/null | grep -qx "$loc"; then export LC_ALL="$loc"; break; fi
  done
fi

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

wait_for() {
  local seconds="$1"
  shift
  for _ in $(seq 1 $((seconds * 4))); do
    "$@" && return 0
    sleep 0.25
  done
  return 1
}

echo "Building..."
"$ROOT/server/script/compile.sh" >/dev/null || { echo "Server build failed"; exit 1; }
cp "$ROOT"/client/lib/log4j-*.jar "$ROOT/dbclient/lib/" 2>/dev/null
"$ROOT/dbclient/script/compile.sh" >/dev/null || { echo "DB client build failed"; exit 1; }
JAVAC="javac"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && JAVAC="$JAVA_HOME/bin/javac"
mkdir -p "$WORK/unit"
"$JAVAC" -nowarn -source 8 -target 8 -cp "$ROOT/dbclient/lib/*" -d "$WORK/unit" \
    "$ROOT/test/DbFormatTest.java" "$ROOT/test/SqlRun.java" 2>/dev/null || { echo "Test build failed"; exit 1; }

echo "Unit tests"
check "DB client formatting unit tests" "$JAVA" -cp "$WORK/unit:$ROOT/dbclient/lib/*" dbclient.DbFormatTest

sql() {
  "$JAVA" -cp "$WORK/unit:$JFILTRA_DB_DRIVER" SqlRun "$JFILTRA_DB_URL" "$DB_USER" "$JFILTRA_DB_PASSWORD" "$@"
}
reset_table() {
  sql try "DROP TABLE jf_test" try "DROP TABLE jf_archive" \
      exec "CREATE TABLE jf_test (id INTEGER PRIMARY KEY, name VARCHAR(100), amount $T_DEC, created $T_TS, note $T_TEXT, sent INTEGER)" \
      exec "CREATE TABLE jf_archive (id INTEGER PRIMARY KEY, name VARCHAR(100))" \
      rows jf_test
}
sent_flags() { sql query "SELECT id, sent FROM jf_test ORDER BY id" | tr '\n' ' '; }

# Server
KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
mkdir -p "$WORK/server" "$WORK/received"
cp -R "$ROOT/server/lib" "$ROOT/server/config" "$WORK/server/"
cat > "$WORK/server/config/server.properties" <<CONF
server.port=$PORT
server.bind.address=127.0.0.1
client.keys.path=config/client_keys.properties
client.paths.config=config/client_paths.properties
CONF
printf 'dbclient1=%s\n' "$KEY" > "$WORK/server/config/client_keys.properties"
printf 'dbclient1=%s\n' "$WORK/received" > "$WORK/server/config/client_paths.properties"
(cd "$WORK/server" && exec "$JAVA" -Dlog4j2.configurationFile="$WORK/server/config/log4j2.properties" \
    -cp "lib/*" server.JFiltraServer config/server.properties > console.log 2>&1) &
PIDS+=("$!")
SERVER_LOG="$WORK/server/logs/server_jfiltra.log"
wait_for 15 grep -q "Server is running" "$SERVER_LOG" 2>/dev/null || { echo "Server did not start"; exit 1; }

# DB client
D="$WORK/dbclient"
mkdir -p "$D/lib"
cp -R "$ROOT/dbclient/config" "$D/"
cp "$ROOT"/dbclient/lib/*.jar "$JFILTRA_DB_DRIVER" "$D/lib/"
DB_LOG="$D/logs/dbclient_jfiltra.log"
# write_config <format> <header> <post-sql> [extra lines...]
write_config() {
  {
    echo "client.label=dbclient1"
    echo "server.host=127.0.0.1"
    echo "server.port=$PORT"
    echo "encryption.key=$KEY"
    echo "db.url=$JFILTRA_DB_URL"
    echo "db.user=$DB_USER"
    echo "db.password.env=JFILTRA_DB_PASSWORD"
    echo "select.sql=SELECT id, name, amount, created, note FROM jf_test WHERE sent = 0 ORDER BY id"
    echo "post.transfer.sql=$3"
    echo "run.mode=once"
    echo "polling.interval.seconds=1"
    echo "file.format=$1"
    echo "file.header=$2"
    shift 3
    for line in "$@"; do echo "$line"; done
  } > "$D/config/dbclient.properties"
  chmod 600 "$D/config/dbclient.properties"
}
run_db() {
  (cd "$D" && "$JAVA" -Dlog4j2.configurationFile="$D/config/log4j2.properties" -cp "lib/*" \
      dbclient.JFiltraDbClient config/dbclient.properties > once.log 2>&1)
  echo $?
}
received() { ls "$WORK/received" | grep -v '^\.' ; }
received_count() { received | wc -l | tr -d ' '; }
received_is() { [ "$(received_count)" = "$1" ]; }
flags_are() { [ "$(sent_flags)" = "$1" ]; }
received_file() { echo "$WORK/received/$(received | head -1)"; }
clear_received() { rm -f "$WORK/received"/*; }

# Expected content of the test rows (see SqlRun.insertRows); $1 = delimiter
expected_csv() {
  local d="$1"
  printf '1%splain%s12.50%s2026-01-02T03:04:05%sx\n' "$d" "$d" "$d" "$d"
  printf '2%s"comma, ""quote"""%s-0.01%s2026-12-31T23:59:59.5%s' "$d" "$d" "$d" "$d"
  if [ "$d" = ";" ]; then printf '"a;b"\n'; else printf 'a;b\n'; fi
  printf '3%s"multi\nline\tand tab \\ backslash"%s1000000.00%s%s"\r\n"\n' "$d" "$d" "$d" "$d"
  printf '4%s\xc4\x8d\xc5\xa1\xc5\xbe \xe2\x82\xac \xf0\x9f\x98\x80%s%s2026-06-15T12:00:00%s\n' "$d" "$d" "$d" "$d"
  printf '5%s%s0.00%s2026-01-01T00:00:00%s  spaces  \n' "$d" "$d" "$d" "$d"
}
expected_tsv() {
  printf '1\tplain\t12.50\t2026-01-02T03:04:05\tx\n'
  printf '2\tcomma, "quote"\t-0.01\t2026-12-31T23:59:59.5\ta;b\n'
  printf '3\tmulti\\nline\\tand tab \\\\ backslash\t1000000.00\t\t\\r\\n\n'
  printf '4\t\xc4\x8d\xc5\xa1\xc5\xbe \xe2\x82\xac \xf0\x9f\x98\x80\t\t2026-06-15T12:00:00\t\n'
  printf '5\t\t0.00\t2026-01-01T00:00:00\t  spaces  \n'
}
expected_jsonl() {
  printf '{"%s":1,"%s":"plain","%s":12.50,"%s":"2026-01-02T03:04:05","%s":"x"}\n' $COLS
  printf '{"%s":2,"%s":"comma, \\"quote\\"","%s":-0.01,"%s":"2026-12-31T23:59:59.5","%s":"a;b"}\n' $COLS
  printf '{"%s":3,"%s":"multi\\nline\\tand tab \\\\ backslash","%s":1000000.00,"%s":null,"%s":"\\r\\n"}\n' $COLS
  printf '{"%s":4,"%s":"\xc4\x8d\xc5\xa1\xc5\xbe \xe2\x82\xac \xf0\x9f\x98\x80","%s":null,"%s":"2026-06-15T12:00:00","%s":null}\n' $COLS
  printf '{"%s":5,"%s":null,"%s":0.00,"%s":"2026-01-01T00:00:00","%s":"  spaces  "}\n' $COLS
}

echo "Database: $JFILTRA_DB_URL"
reset_table || { echo "Could not set up the test table"; exit 1; }
# Column labels as the database reports them (Oracle: upper case)
COLS="id name amount created note"
if echo "$JFILTRA_DB_URL" | grep -q '^jdbc:oracle'; then COLS="ID NAME AMOUNT CREATED NOTE"; fi
HEAD_TSV="$(echo $COLS | tr ' ' '\t')"

echo "Formats"
for case in "csv true ," "csv false ;" "tsv true" "tsv false" "jsonl true"; do
  read -r fmt hdr delim <<< "$case"
  reset_table
  clear_received
  extra=()
  [ -n "${delim:-}" ] && extra=("csv.delimiter=$delim")
  write_config "$fmt" "$hdr" "UPDATE jf_test SET sent = 1 WHERE sent = 0" "${extra[@]}"
  code="$(run_db)"
  case "$fmt" in
    csv) { [ "$hdr" = true ] && echo "$(echo $COLS | tr ' ' "$delim")"; expected_csv "$delim"; } > "$WORK/expected" ;;
    tsv) { [ "$hdr" = true ] && echo "$HEAD_TSV"; expected_tsv; } > "$WORK/expected" ;;
    jsonl) expected_jsonl > "$WORK/expected" ;;
  esac
  check "$fmt header=$hdr ${delim:+delimiter '$delim' }: exit 0, one file" [ "$code:$(received_count)" = "0:1" ]
  check "$fmt header=$hdr: file name export_<timestamp>.$fmt" test -n "$(received | grep -E "^export_[0-9]{8}-[0-9]{6}-[0-9]{3}\.$fmt$")"
  if ! check "$fmt header=$hdr: content received intact" cmp -s "$WORK/expected" "$(received_file)"; then
    diff <(od -c "$WORK/expected") <(od -c "$(received_file)") | head -10
  fi
  check "$fmt header=$hdr: post.transfer.sql flagged all rows" [ "$(sent_flags)" = "1|1 2|1 3|1 4|1 5|1 " ]
done
check "no spool files left" [ -z "$(ls -A "$D/spool")" ]

echo "Empty select"
clear_received
code="$(run_db)"
check "no rows: exit 2, no file" [ "$code:$(received_count)" = "2:0" ]

echo "post.transfer.sql: delete and move"
reset_table; clear_received
write_config csv true "DELETE FROM jf_test WHERE sent = 0"
code="$(run_db)"
check "delete: exit 0 and table empty" [ "$code:$(sql query "SELECT COUNT(*) FROM jf_test")" = "0:0" ]
reset_table; clear_received
write_config csv true "INSERT INTO jf_archive (id, name) SELECT id, name FROM jf_test WHERE sent = 0" \
  "post.transfer.sql.2=DELETE FROM jf_test WHERE sent = 0"
code="$(run_db)"
check "move (two statements): exit 0, rows moved" \
  [ "$code:$(sql query "SELECT COUNT(*) FROM jf_test"):$(sql query "SELECT COUNT(*) FROM jf_archive")" = "0:0:5" ]

echo "Failures leave the database unchanged"
reset_table; clear_received
write_config csv true "UPDATE jf_test SET sent = 1 WHERE sent = 0"
sed -i "s/^server.port=.*/server.port=$((PORT + 7))/" "$D/config/dbclient.properties"
code="$(run_db)"
check "server unreachable: exit 3, nothing received" [ "$code:$(received_count)" = "3:0" ]
check "server unreachable: rows unchanged" [ "$(sent_flags)" = "1|0 2|0 3|0 4|0 5|0 " ]
write_config csv true "UPDATE jf_test SET sent = 1 WHERE sent = 0"
sed -i "s|^encryption.key=.*|encryption.key=$(head -c 32 /dev/urandom | base64 | tr -d '\n')|" "$D/config/dbclient.properties"
code="$(run_db)"
check "server rejects (wrong key): exit 3, rows unchanged" [ "$code:$(sent_flags)" = "3:1|0 2|0 3|0 4|0 5|0 " ]
check "rejection logged" grep -q "Server rejected file export_.*Decryption failed" "$DB_LOG"
write_config csv true "UPDATE jf_test SET sent = 1 WHERE sent = 0" \
  "post.transfer.sql.2=UPDATE no_such_table SET x = 1"
code="$(run_db)"
check "post.transfer.sql fails: exit 3, file delivered once" [ "$code:$(received_count)" = "3:1" ]
check "post.transfer.sql fails: first statement rolled back too" [ "$(sent_flags)" = "1|0 2|0 3|0 4|0 5|0 " ]
check "post.transfer.sql failure logged as delivered" grep -q "WAS DELIVERED, but post.transfer.sql or the commit failed" "$DB_LOG"
clear_received
sed -i 's/^run.mode=.*/run.mode=loop/' "$D/config/dbclient.properties"
code="$(run_db)"
check "loop: post.transfer.sql failure stops the client with exit 3 after one file" [ "$code:$(received_count)" = "3:1" ]
check "loop: stop explained in the log" grep -q "stops so the same rows are not sent again" "$DB_LOG"

echo "Rows inserted while a file is being sent"
# The post-transfer SQL waits 3 s; meanwhile another session inserts a matching row.
# With the recommended settings that row must stay unsent (see README, Consistency).
case "$JFILTRA_DB_URL" in
  jdbc:postgresql*) SLEEP_SQL="SELECT pg_sleep(3)"; ISO="repeatable_read" ;;
  jdbc:oracle*) SLEEP_SQL="BEGIN DBMS_SESSION.SLEEP(3); END;"; ISO="serializable" ;;
  *) SLEEP_SQL="SELECT SLEEP(3)"; ISO="" ;;
esac
reset_table; clear_received
write_config csv true "$SLEEP_SQL" "db.isolation=$ISO" \
  "select.sql=SELECT id, name, amount, created, note FROM jf_test WHERE sent = 0 ORDER BY id FOR UPDATE" \
  "post.transfer.sql.2=UPDATE jf_test SET sent = 1 WHERE sent = 0"
( sleep 1.5; sql exec "INSERT INTO jf_test (id, name, sent) VALUES (7, 'inserted meanwhile', 0)" ) &
INSERT_PID=$!
code="$(run_db)"
wait "$INSERT_PID"
check "concurrent insert: exit 0, the five selected rows flagged, the new row not" \
  [ "$code:$(sent_flags)" = "0:1|1 2|1 3|1 4|1 5|1 7|0 " ]
check "concurrent insert: new row not in the file" test -z "$(grep 'inserted meanwhile' "$(received_file)")"
write_config csv true "" "db.isolation=chaos"
check "invalid db.isolation: exit 1" [ "$(run_db)" = 1 ]

echo "Loop"
reset_table; clear_received
write_config jsonl true "UPDATE jf_test SET sent = 1 WHERE sent = 0" "run.mode=loop"
sed -i '/^run.mode=once/d' "$D/config/dbclient.properties"
(cd "$D" && exec "$JAVA" -Dlog4j2.configurationFile="$D/config/log4j2.properties" -cp "lib/*" \
    dbclient.JFiltraDbClient config/dbclient.properties > loop.log 2>&1) &
LOOP_PID=$!
PIDS+=("$LOOP_PID")
check "loop: first batch sent" wait_for 20 received_is 1
sql exec "INSERT INTO jf_test (id, name, sent) VALUES (6, 'later', 0)"
check "loop: new row sent in a second file" wait_for 20 received_is 2
check "loop: second file has only the new row" grep -q '"later"' "$WORK/received/$(received | sort | tail -1)"
check "loop: all rows flagged" wait_for 5 flags_are "1|1 2|1 3|1 4|1 5|1 6|1 "
kill "$LOOP_PID"
check "loop: stops on SIGTERM" wait_for 15 sh -c "! kill -0 $LOOP_PID 2>/dev/null"
check "loop: stop logged" grep -q "JFiltraDbClient stopped" "$DB_LOG"

echo "Configuration"
write_config xml true ""
check "invalid file.format: exit 1" [ "$(run_db)" = 1 ]
check "invalid file.format message" grep -q "file.format must be csv, tsv or jsonl" "$D/once.log"
write_config csv true "" "db.password.env=JFILTRA_NO_SUCH_VARIABLE"
check "missing password variable: exit 1" [ "$(run_db)" = 1 ]
write_config csv true ""
chmod 644 "$D/config/dbclient.properties"
reset_table; run_db >/dev/null
check "world-readable configuration warned" grep -q "is readable by all users" "$D/once.log"
check "no stack traces in the DB client log" [ "$(grep -c '^[[:space:]]*at ' "$DB_LOG")" -eq 0 ]

sql try "DROP TABLE jf_test" try "DROP TABLE jf_archive"
echo
echo "Passed: $PASSED, failed: $FAILED"
[ "$FAILED" -eq 0 ]
