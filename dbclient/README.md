# jFiltra DB client

The DB client sends the result of an SQL query to a jFiltra server. It runs a
`SELECT`, writes the rows to a file (CSV, TSV or JSON Lines), sends the file
with the same protocol as the [file client](../README.md), and after the server
confirms it, runs your optional post-transfer SQL (for example to mark the rows
as sent) and commits.

The server needs no change: the DB client is just another client with its own
label and key in `client_keys.properties`. Files arrive in the storage directory
like any other file.

## Contents

- [Requirements](#requirements)
- [Setup](#setup)
- [Configuration](#configuration)
- [How a cycle works](#how-a-cycle-works)
- [Consistency between select and post-transfer SQL](#consistency-between-select-and-post-transfer-sql)
- [When something fails](#when-something-fails)
- [Output formats](#output-formats)
- [Exit codes](#exit-codes)
- [Testing](#testing)

## Requirements

- Java 8 or newer, as for the file client (see [Java](../README.md#java) and
  [Locale](../README.md#locale))
- The Log4j 2 JARs, as for the file client
- The JDBC driver of your database. jFiltra doesn't include any driver.

Tested with:

| Database             | Driver                         | Result |
|----------------------|--------------------------------|--------|
| PostgreSQL 17.6      | `postgresql-42.7.8.jar`        | all smoke checks pass, Java 8 and 21 |
| MariaDB 11.4.13      | `mariadb-java-client-3.5.6.jar`| all smoke checks pass, Java 8 and 21 |
| MariaDB 11.4.13      | `mysql-connector-j-9.4.0.jar`  | all smoke checks pass, Java 8 and 21 |
| MySQL 8.4.7          | `mysql-connector-j-9.4.0.jar`  | all smoke checks of 1.1.0 pass, Java 8 and 21 (1.1.1 not run on MySQL itself) |
| Oracle               | `ojdbc8` / `ojdbc11`           | **not tested yet** (no Oracle database was available); the Oracle examples below follow Oracle's documented SQL |

## Setup

```
dbclient/
  config/   dbclient.properties, log4j2.properties
  lib/      jfiltra-dbclient.jar, Log4j JARs, your JDBC driver JAR
  script/   compile.sh/.bat, start-dbclient.sh/.bat
  src/main/java/dbclient/JFiltraDbClient.java
```

1. Copy `log4j-api-2.26.1.jar` and `log4j-core-2.26.1.jar` into `dbclient/lib`
   (the same files as in `client/lib`).
2. Put your JDBC driver JAR into `dbclient/lib`, for example:
   - PostgreSQL: [postgresql](https://repo1.maven.org/maven2/org/postgresql/postgresql/)
   - MySQL: [mysql-connector-j](https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/)
   - MariaDB: [mariadb-java-client](https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/)
   - Oracle: [ojdbc8](https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/)
     (Java 8) or `ojdbc11` (Java 11 and newer)

   Every JAR in `dbclient/lib` is on the classpath.
3. Build: `dbclient/script/compile.sh` (Windows: `dbclient\script\compile.bat`).
4. On the server, add a label and key for the DB client to
   `client_keys.properties` (and a storage path to `client_paths.properties`).
5. Edit `dbclient/config/dbclient.properties`, then `chmod 600` it.
6. Start: `dbclient/script/start-dbclient.sh` (Windows: `start-dbclient.bat`).
   For a systemd service, follow the file client's
   [example](../README.md#running-as-a-service) with `start-dbclient.sh`.

## Configuration

`dbclient/config/dbclient.properties`:

| Property | Required | Description |
|----------|----------|-------------|
| `client.label` | Yes | This client's ID; must match an entry on the server |
| `server.host`, `server.port` | Yes | jFiltra server |
| `encryption.key` | Yes | This client's key (at least 32 characters), as in the server's `client_keys.properties` |
| `socket.timeout.seconds` | No | Seconds to wait for the server's reply (default `120`) |
| `db.url` | Yes | JDBC URL, e.g. `jdbc:postgresql://host:5432/db`, `jdbc:mysql://host:3306/db`, `jdbc:mariadb://host:3306/db`, `jdbc:oracle:thin:@//host:1521/SERVICE` |
| `db.user` | No | Database user |
| `db.password` | No | Database password |
| `db.fetch.size` | No | Rows the driver fetches at a time (default `1000`; `0` = the driver's default). Keeps large results out of memory, see [How a cycle works](#how-a-cycle-works) |
| `db.query.timeout.seconds` | No | Longest time for each statement (select and post-transfer SQL), e.g. while waiting for a lock (default `0` = no limit). A statement that takes longer fails, and the cycle is rolled back |
| `db.isolation` | No | Transaction isolation: `read_committed`, `repeatable_read` or `serializable`; empty = the database's default. See [Consistency](#consistency-between-select-and-post-transfer-sql) |
| `db.password.env` | No | Name of an environment variable holding the password; used instead of `db.password`. The client doesn't start if the variable is not set |
| `select.sql` | Yes | One `SELECT`. All its rows go into one file. No trailing semicolon |
| `post.transfer.sql` | No | One statement run after the server confirmed the file. Empty = none |
| `post.transfer.sql.2`, `.3`, ... | No | More statements, run in order after `post.transfer.sql`, up to the first missing number |
| `run.mode` | No | `loop` (default): run continuously; `once`: one cycle, then exit |
| `polling.interval.seconds` | No | Seconds between cycles (default `10`), also after a cycle that sent rows |
| `file.format` | No | `csv` (default), `tsv` or `jsonl` |
| `file.header` | No | `true` (default) or `false`: first line with the column names (csv and tsv) |
| `csv.delimiter` | No | CSV field delimiter: one character (default `,`), `tab` or `space` (also `\\t` and `\\s`, see the backslash note below). Spaces around the value are ignored. Not `"`, a line break, a letter or a digit |
| `file.prefix` | No | Start of the file name (default `export_`). At most 200 bytes; no `/ \ : * ? " < > \|`, control characters or leading dot; must not make a name the server refuses, e.g. `nul.` or `COM1.` (Windows device names) |
| `spool.directory` | No | Where the file is written before it is sent (default `spool`); it is deleted afterwards. Use one spool directory per DB client |

The values of `run.mode`, `file.format` and `file.header` are not
case-sensitive. An invalid value stops the client with exit code `1` and a clear
message.

**Statements are run exactly as written.** The client doesn't bind parameters
or change your SQL. The post-transfer SQL decides what "sent" means: delete the
rows, set a flag, or move them to another table. It's up to whoever configures
the client.

**Backslashes must be doubled.** The `.properties` format reads `\` as an escape
character, so `\d` becomes `d` and `\\` becomes `\`, without any error. Write
every backslash your SQL needs twice:

```properties
# PostgreSQL regex ~ '\d+'
select.sql=SELECT id, code FROM export_queue WHERE code ~ '\\d+' AND sent = 0
# MySQL LIKE with the escape character \ : LIKE 'a\_b'
select.sql=SELECT id FROM export_queue WHERE name LIKE 'a\\_b' AND sent = 0
```

A long statement can continue on the next line when the line ends with a single
`\` (that backslash is not part of the SQL).

**File names** are the prefix, the date and time with milliseconds, and the
extension of the format, for example `export_20261028-193005-123.csv`. One client
never uses the same name twice. With the server's default
`on.name.conflict=rename`, a name clash with another client still loses nothing.

**Secrets.** The configuration contains the encryption key and maybe the
database password. The client logs a warning if the file is readable by all
users. Keep it at `chmod 600`, or put the password into an environment variable
(`db.password.env`), for example with `Environment=` in a systemd unit. The log
shows the database URL without its parameters. Passwords in a URL
(`user:password@`, Oracle's `user/password@`, `password=...`) and the passwords
from `db.password`, `db.password.env` and `db.url` (4 characters or longer) are
replaced by `****` in every logged line, including database error messages.

## How a cycle works

Each cycle is one database transaction (autocommit off):

1. Run `select.sql`. No rows: end the transaction, and wait
   `polling.interval.seconds` (or exit with `2` in `once` mode).
2. Write all rows to a temporary file in `spool.directory`, readable only by the
   client's user (`600`).
3. Send the file to the server, exactly like the file client does.
4. Only if the server replies `SUCCESS`: run `post.transfer.sql` (and `.2`, `.3`,
   ...).
5. Commit.

If anything fails before the commit, the transaction is rolled back, so the
database is as it was before the select. The temporary file is always deleted;
if the client was killed, the next start deletes the client's leftover
temporary files.

In `loop` mode the next cycle starts `polling.interval.seconds` later, also after
rows were sent. If `post.transfer.sql` is empty or doesn't mark the sent rows,
the same rows are sent again every interval; the log warns when a file has the
same content as the previous one. To send a large backlog faster, use a larger
`LIMIT` or a shorter interval.

Keep each result small enough for the server's `max.file.size.mb`, for example
with `LIMIT` / `FETCH FIRST n ROWS ONLY`. If the server replies `File too
large`, `once` exits with `3`, and `loop` tries again only after 10 minutes
(sending the same rows sooner can't help): reduce the rows.

The rows are written to a file, and the driver fetches `db.fetch.size` rows at a
time, so memory use doesn't grow with the result size:

- PostgreSQL (pgjdbc), MariaDB driver, Oracle: `db.fetch.size` is enough.
- MySQL Connector/J reads the whole result into memory unless the URL has
  `useCursorFetch=true`, e.g. `jdbc:mysql://host:3306/db?useCursorFetch=true`.

If the client runs out of memory anyway (or hits any other unexpected error), it
logs `JFiltraDbClient stops after an unexpected error`, nothing is committed, and
it exits with `3`.

The transaction stays open while the file is sent. If `select.sql` locks rows
(`FOR UPDATE`), they stay locked until the commit.

## Consistency between select and post-transfer SQL

**This is the configurer's responsibility.** The post-transfer SQL runs after the
file was sent, in the same transaction as the select. If other programs insert
or change rows in between, a post-transfer SQL that repeats the select's
condition (`WHERE sent = 0`) can also mark or delete rows that were **not** in
the file. They would then never be sent.

Two tools make it exact:

- `FOR UPDATE` in `select.sql` locks the selected rows until the commit, so
  nobody can change or delete them in between.
- `db.isolation` keeps new rows out of the post-transfer SQL where the database
  needs it:

| Database | Settings | Why it is exact |
|----------|----------|-----------------|
| PostgreSQL | `db.isolation=repeatable_read` and `FOR UPDATE` | All statements of the transaction see the data as of the select; rows inserted later are invisible to the post-transfer SQL |
| MySQL / MariaDB (InnoDB) | default isolation (`REPEATABLE READ`) and `FOR UPDATE` | `FOR UPDATE` also locks the gaps of the scanned range, so matching rows can't be inserted until the commit (inserts wait). Index the condition column, or the whole table is locked |
| Oracle | `db.isolation=serializable` and `FOR UPDATE` | Like PostgreSQL's repeatable read: the transaction sees the data as of its start (not tested yet) |

The smoke test checks this: a row inserted while the file is being sent stays
unsent with these settings (PostgreSQL, MySQL, MariaDB). With PostgreSQL's
default `read_committed` the same row is marked as sent without having been sent.

Without these, keep the default and make sure nothing else writes matching
rows while the client runs, or use a condition new rows can't match.

PostgreSQL, mark rows as sent:

```properties
db.isolation=repeatable_read
select.sql=SELECT id, name, amount FROM export_queue WHERE sent = false ORDER BY id FOR UPDATE
post.transfer.sql=UPDATE export_queue SET sent = true, sent_at = now() WHERE sent = false
```

MySQL / MariaDB, at most 10000 rows per file:

```properties
select.sql=SELECT id, name, amount FROM export_queue WHERE sent = 0 ORDER BY id LIMIT 10000 FOR UPDATE
post.transfer.sql=UPDATE export_queue SET sent = 1 WHERE sent = 0 ORDER BY id LIMIT 10000
```

Oracle (not tested yet), delete the sent rows:

```properties
db.isolation=serializable
select.sql=SELECT id, name, amount FROM export_queue ORDER BY id FOR UPDATE
post.transfer.sql=DELETE FROM export_queue
```

Move rows into an archive table (two statements, one transaction). Use the
isolation of the table above for your database; on PostgreSQL without
`db.isolation=repeatable_read`, a row inserted while the file is sent would be
archived and deleted without ever being sent:

```properties
# PostgreSQL: repeatable_read; Oracle: serializable; MySQL/MariaDB: leave empty
db.isolation=repeatable_read
select.sql=SELECT id, name, amount FROM export_queue WHERE sent = 0 ORDER BY id FOR UPDATE
post.transfer.sql=INSERT INTO export_archive (id, name, amount) SELECT id, name, amount FROM export_queue WHERE sent = 0
post.transfer.sql.2=DELETE FROM export_queue WHERE sent = 0
```

Notes:

- In Oracle, `FOR UPDATE` can't be combined with `ORDER BY ... FETCH FIRST`;
  limit the rows with a condition instead. A PL/SQL block (`BEGIN ... END;`) can
  be one `post.transfer.sql`.
- Run one DB client per table or queue; two clients selecting the same rows
  would wait for each other's locks.
- MySQL: an `UPDATE ... LIMIT` in the post-transfer SQL must use the same
  `ORDER BY` and `LIMIT` as the select.

## When something fails

| What fails | Database | File | What the client does |
|------------|----------|------|---------------------|
| Select (SQL error, connection lost) | rolled back | not sent | logs the error, reconnects next time; `loop`: retries after the polling interval, then twice as long each time, up to 10 minutes; `once`: exit `3` |
| Server unreachable or rejects the file | rolled back, unchanged | not stored | as above |
| Server replies `File too large` | rolled back, unchanged | not stored | `once`: exit `3`; `loop`: tries again after 10 minutes |
| Connection lost after the file was sent, before the server's reply (e.g. `socket.timeout.seconds` too short) | rolled back, unchanged | **may be stored** | as for an unreachable server; the same rows are sent again later under a new file name, so the receiver may get them twice |
| Post-transfer SQL or commit fails | rolled back, unchanged | **stored on the server** | logs `ERROR ... WAS DELIVERED, but post.transfer.sql or the commit failed`; `once`: exit `3`; `loop`: **stops with exit `3`** |
| Unexpected error (e.g. out of memory) | not committed | not stored, or stored if it happened after the reply | logs `stops after an unexpected error`; exit `3` |

**Possible duplicates.** If the server's reply is lost, the client can't know
that the file was stored, so it sends the rows again. Whatever reads the files
should cope with a row arriving twice, for example by its key. Set
`socket.timeout.seconds` long enough for the server to store large files.

**Why the loop stops after a failed post-transfer SQL:** the file is already on
the server, but the database still shows the rows as not sent. Continuing would
send the same rows again on every cycle. The client stops instead, so a person
can check the delivered file, fix the post-transfer SQL or the data, and start
the client again. When it starts again, the same rows are selected and sent
again, unless you marked them by hand.

If the client runs as a systemd service with `Restart=on-failure`, systemd starts
it again after `RestartSec`, and the same rows are sent again each time. Use
`Restart=no` for the DB client, or a long `RestartSec`, and watch the log.
Example unit (`/etc/systemd/system/jfiltra-dbclient.service`):

```ini
[Unit]
Description=jFiltra DB client
After=network-online.target

[Service]
User=jfiltra
Environment=LANG=C.UTF-8
Environment=JFILTRA_DB_PASSWORD=secret
ExecStart=/opt/jfiltra/dbclient/script/start-dbclient.sh
Restart=no
# The client exits with 0 when stopped; 143 (SIGTERM) only if it is killed while stopping
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

If the client is stopped (Ctrl+C, `systemctl stop`) during a cycle, in `loop` and
`once` mode, it finishes the cycle first (up to 60 seconds), so a delivered file
is followed by its post-transfer SQL and commit, and exits with the cycle's exit
code (`loop`: `0`). If the process is killed, the database rolls the open
transaction back; a file delivered just before is then sent again later.

## Output formats

All files are UTF-8, one row per line, lines end with `\n`.

| Value | Format |
|-------|--------|
| `csv` | Fields separated by `csv.delimiter`. A field with the delimiter, a `"` or a line break is quoted, and `"` inside becomes `""` (RFC 4180). NULL is an empty field; an empty string is `""` |
| `tsv` | Fields separated by tab. Tab, line breaks and `\` inside values are written as `\t`, `\n`, `\r` and `\\` (as in PostgreSQL's text format). NULL and empty strings are both empty |
| `jsonl` | One JSON object per row, with the column names as keys: `{"id":1,"name":"a"}`. NULL is `null`; integers, decimals, floating-point numbers and booleans are JSON numbers and booleans (`NaN` and `Infinity` are strings); everything else is a string. Two columns with the same name are refused (the cycle fails with a message): use `AS` aliases. `file.header` has no effect |

Values:

- **Dates and times** in ISO 8601: `2026-10-28`, `19:30:05`,
  `2026-10-28T19:30:05` (with fractions of a second only when they aren't zero,
  e.g. `2026-10-28T19:30:05.5`, `19:30:05.123456`).
  - Without a time zone (`TIMESTAMP`, `DATETIME`, `DATE`, `TIME`): exactly as
    stored, whatever the client's time zone, also in a daylight saving gap.
  - With a time zone, with its offset: `2026-10-28T19:30:05+01:00`,
    `19:30:05+02:00`. PostgreSQL's `timestamptz` stores only the instant, and
    its driver returns it in UTC: `2026-10-28T18:30:05Z`.
  - Oracle `DATE` includes a time and is written as a timestamp.
  - PostgreSQL `infinity` / `-infinity` as these words.
  - MySQL/MariaDB `TIME` values outside a day (e.g. `838:59:59`) as the database
    writes them.
  - MySQL/MariaDB `YEAR` is reported by the drivers as a date (`2026-01-01`).
    Add `yearIsDateType=false` to the URL to get `2026`.
  - MySQL/MariaDB zero dates (`0000-00-00`): the MariaDB driver returns NULL (an
    empty field); MySQL Connector/J by default `0000-00-00` as text. Choose with
    the URL option `zeroDateTimeBehavior` (`CONVERT_TO_NULL` or `ROUND`).
- **Numbers** as the database has them: decimals with all their digits and
  never in exponent form (`12.50`, `1000000.00`); floating-point values in the
  driver's text form, which differs between drivers (`1e+300`, `1.0E300`,
  `1.5e-07`). PostgreSQL `NaN` / `Infinity` as these words.
- **Booleans** as `true` / `false`. MySQL/MariaDB `TINYINT(1)` columns are
  reported as booleans by the drivers. `BIT(1)` is a boolean; `BIT(n)` with
  `n` > 1 is written as `n` digits, e.g. `10101010`.
- **Other values** that can't be converted to these forms are written in the
  driver's text form. If even that fails, the cycle fails with an error naming the
  row and the column.
- **Binary data** (`BLOB`, `BYTEA`, `VARBINARY`, `RAW`) in Base64.
- **Long text** (`CLOB`, `TEXT`) in full.
- **Column names** as the database reports them; Oracle reports unquoted names in
  upper case. Use `AS` aliases in `select.sql` to choose them.
- **Oracle** `TIMESTAMP WITH (LOCAL) TIME ZONE` with offset, `BINARY_FLOAT` /
  `BINARY_DOUBLE` as numbers (not tested yet).

## Exit codes

The same as the file client's `run.mode=once`:

| Code | Meaning |
|------|---------|
| `0` | `once`: the rows were sent and committed. `loop`: stopped normally (Ctrl+C, SIGTERM, `systemctl stop`) |
| `1` | Configuration error (missing or invalid setting, missing password variable, missing config file, no UTF-8 locale) |
| `2` | `once`: the select returned no rows |
| `3` | `once`: the select, the transfer, the post-transfer SQL or the commit failed. `loop`: stopped after a failed post-transfer SQL or commit. Both: unexpected error such as out of memory |

A missing JDBC driver shows up as `No suitable driver found for jdbc:...` and,
like any database error, as exit code `3` in `once` mode.

## Testing

`test/dbclient-smoke-test.sh` builds the server and the DB client and runs them
against a real database. It creates and drops the tables `jf_test` and
`jf_archive`, so use a test database:

```bash
JFILTRA_DB_URL=jdbc:postgresql://localhost:5432/test JFILTRA_DB_USER=test \
JFILTRA_DB_PASSWORD=secret JFILTRA_DB_DRIVER=/path/postgresql-42.7.8.jar \
test/dbclient-smoke-test.sh
```

For MySQL and MariaDB add `JFILTRA_DB_TYPES="DECIMAL(12,2) DATETIME(3) TEXT"`
and use a database with the `utf8mb4` character set (MariaDB 11.4 creates
`latin1` databases by default: `CREATE DATABASE test CHARACTER SET utf8mb4`),
for Oracle `JFILTRA_DB_TYPES="NUMBER(12,2) TIMESTAMP(3) CLOB"`. Without
`JFILTRA_DB_URL` the test is skipped.

It checks every format with and without header (including quotes, delimiters,
line breaks, tabs, backslashes, Unicode and emoji, NULL and empty strings,
decimals and timestamps), an empty select, a row inserted while a file is being
sent (with the isolation settings above), post-transfer SQL that deletes, sets a
flag or moves rows, the exit codes of `once` mode, that a failed or rejected
transfer leaves the database unchanged, that a failed post-transfer SQL rolls
back and stops the loop after one file, the loop picking up new rows and
stopping on SIGTERM with exit code `0`, the pause between cycles without an
effective post-transfer SQL, finishing a one-time run on SIGTERM, value types
(`BIT(n)`, time zones, a daylight saving gap, `TIME` fractions, `NaN`, `infinity`),
a result of 1,000,000 rows with a 40 MB heap (and, on PostgreSQL, the logged
exit `3` when it runs out of memory without a fetch size), passwords masked in
the log, leftover spool files, and configuration errors.
