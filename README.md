# jFiltra

jFiltra is a lightweight Java client/server tool for moving files securely from
watched directories to a central server.

- The **client** polls one or more source directories. For each file it computes a
  SHA-256 hash, compresses the file with GZIP, encrypts it with AES using a
  client-specific key, and sends it to the server. After the server confirms
  receipt, the client deletes the original file.
- The **server** accepts connections from multiple clients. It looks up the
  client's key, decrypts and decompresses the file, checks the SHA-256 hash, and
  stores the file in that client's configured storage directory.

## Requirements

- Java 8 or newer, with `java` on your `PATH` (JDK for compiling, JRE for running)
- Bash (to run the scripts)
- [Apache Log4j 2](https://logging.apache.org/log4j/2.x/) 2.26.1, placed in both `server/lib/` and `client/lib/`:
  - [`log4j-api-2.26.1.jar`](https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.26.1/log4j-api-2.26.1.jar)
  - [`log4j-core-2.26.1.jar`](https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.26.1/log4j-core-2.26.1.jar)

From the project root:

```bash
for d in server/lib client/lib; do
  mkdir -p "$d"
  for a in log4j-api log4j-core; do
    curl -fsSL -o "$d/$a-2.26.1.jar" "https://repo1.maven.org/maven2/org/apache/logging/log4j/$a/2.26.1/$a-2.26.1.jar"
  done
done
```

## Project layout

```
server/
  config/                 server.properties, client_keys.properties,
                          client_paths.properties, log4j2.properties
  script/                 compile.sh, start-server.sh
  src/main/java/server/   JFiltraServer.java
client/
  config/                 client.properties, log4j2.properties
  script/                 compile.sh, start-client.sh
  src/main/java/client/   JFiltraClient.java
```

These folders are created when you set up, build and run jFiltra. They are not
committed to git:

- `lib/` holds the Log4j 2 JARs and the built `jfiltra-server.jar` or `jfiltra-client.jar`.
- `script/bin/` holds the compiled classes.
- `logs/` holds the log files.

## Configuration

### Server

`server/config/server.properties`

| Property              | Required | Description                                                |
|-----------------------|----------|------------------------------------------------------------|
| `server.port`         | Yes      | Port the server listens on (shipped value: `9000`)         |
| `client.keys.path`    | No       | File that maps each client label to its key                |
| `client.paths.config` | No       | File that maps each client label to its storage directory  |
| `socket.timeout.seconds` | No    | Seconds to wait for data from a client before closing the connection (default `30`) |
| `header.timeout.seconds` | No    | Seconds a client has to send the transfer header: label, file name, hash and size (default `5`) |
| `transfer.timeout.seconds` | No  | Maximum total seconds for one transfer, including processing (default `600`); raise it for large files or slow networks |
| `max.connections`     | No       | Maximum transfers handled at the same time (default `10`)  |
| `max.connections.per.ip` | No    | Maximum simultaneous connections from one IP address (default `4`) |
| `max.file.size.mb`    | No       | Largest file accepted, both as sent and after decompression (default `512`) |

Relative paths are resolved from the `server/` folder, because the start script
runs the server from there. Leaving out `client.keys.path` means no keys are
loaded, so the server rejects every client.

Both server and client check their settings at startup. A missing required
setting or an invalid value stops the program with a message naming the
setting, for example `Missing required setting: server.port`.

Save all `.properties` files as UTF-8, so keys and paths can contain any
characters, for example `client1=/data/účtovníctvo`. A file that isn't valid
UTF-8 stops the program with a message saying so. Escapes such as `\u013e`
also still work.

`server/config/client_keys.properties` has one line per client, in the form
`<client.label>=<encryption key>`. A client whose label is not listed is rejected.

`server/config/client_paths.properties` has one line per client, in the form
`<client.label>=<storage directory>`. Files from a client without an entry are
stored in `server/incoming/`. The storage directory is created if it doesn't exist.

### Client

`client/config/client.properties`

| Property                   | Required | Description                                                    |
|----------------------------|----------|----------------------------------------------------------------|
| `client.label`             | Yes      | This client's ID; must match an entry on the server            |
| `source.directories`       | Yes      | Comma-separated list of directories to watch (spaces around commas are ignored) |
| `server.host`              | Yes      | Server hostname or IP                                          |
| `server.port`              | Yes      | Server port                                                    |
| `encryption.key`           | Yes      | This client's key; must match the server's entry for this label |
| `polling.interval.seconds` | No       | How often to scan the directories (default `10`; shipped value: `3`) |
| `socket.timeout.seconds`   | No       | Seconds to wait for the server's response before retrying later (default `120`) |
| `stable.polls`             | No       | Number of scans a file must stay unchanged before it is sent (default `2`) |
| `ignore.patterns`          | No       | Comma-separated file name patterns that are never sent (default `.*,*.part,*.tmp,*.crdownload,~$*`; empty sends everything) |

> **Change the keys before use.** The shipped configuration contains placeholder
> keys (`CHANGE_ME_...`). Generate your own secret for each client, for example
> with `openssl rand -base64 24`, and put the same value in the server's
> `client_keys.properties` and the client's `client.properties`.

## Build

Download the Log4j 2 JARs first (see [Requirements](#requirements)). Then run each
compile script from its own `script/` directory:

```bash
cd server/script
./compile.sh   # builds server/lib/jfiltra-server.jar
```

```bash
cd client/script
./compile.sh   # builds client/lib/jfiltra-client.jar
```

## Run

Start the server first, then one or more clients. Run each start script from its
own `script/` directory, because the scripts locate the rest of the project
relative to it:

```bash
cd server/script
./start-server.sh
```

```bash
cd client/script
./start-client.sh
```

Both run in the foreground. Press `Ctrl+C` to stop them.

To try it locally with the default configuration:

```bash
mkdir -p /tmp/jfiltra/source1 /tmp/jfiltra/source2
echo "hello jFiltra" > /tmp/jfiltra/source1/test.txt
# A few seconds later the file appears in /tmp/jfiltra/received/client1/
# and is removed from /tmp/jfiltra/source1/
```

## How transfers behave

- The client sends only the files directly inside each source directory.
  Subdirectories are not scanned.
- A file is sent only once its size and modification time have stayed unchanged
  for `stable.polls` scans in a row, so files that are still being written or
  copied are not sent half finished. With the default of 2, a new file is sent
  after two to three polling intervals.
- A program that writes part of a file, pauses for longer than that, and then
  continues can't be detected this way. The client would send the first part and
  delete the file. Programs that write into a source directory should therefore
  write to a temporary name and rename the file when it is complete. Files ending
  in `.part` or `.tmp` are ignored by default for exactly this purpose.
- Files matching `ignore.patterns` are never sent. By default these are hidden
  files (starting with `.`), files ending in `.part`, `.tmp` or `.crdownload`, and
  Office lock files (starting with `~$`).
- If a file changes while it is being sent, the client keeps it instead of
  deleting it, so the new content is not lost.
- The client deletes a file only after the server replies with success. In all
  other cases the file stays where it is and is retried on the next poll. That
  includes:
  - the server is unreachable;
  - the client's label or key is unknown to the server;
  - the hash check fails;
  - the server rejects the file name;
  - a file with the same name already exists in the storage directory.
- When the server rejects a file, the client logs the reason, for example
  `Server rejected file report.pdf: ERROR: File already exists`. The server log
  has the details.
- Existing files on the server are never overwritten, even when two transfers of
  the same name arrive at the same time.
- If a file with the same name **and the same content** is already stored, the
  transfer counts as successful and the client deletes its copy. This happens,
  for example, when the client missed the server's reply and sends the file again.
- If a file with the same name but **different content** is already stored, the
  client keeps retrying and logging an error until that file is moved or renamed.
- The server rejects file names that:
  - contain a path (such as `../` or `/`);
  - start with `.` (hidden files);
  - contain control characters;
  - are longer than 255 bytes;
  - are reserved on Windows (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, also with an extension, such as `con.txt`);
  - end with a dot or a space.
- The server first writes each file under a hidden temporary name
  (`.jfiltra-<random>.part`) in the storage directory, then publishes it under
  its real name in one atomic step. Other programs watching the storage directory
  therefore never see a half-written file. If the server is stopped in the middle
  of a write, the leftover `.part` file is deleted the next time the server starts.
- Files are processed in small chunks, so memory use stays the same whatever the
  file size, and there is no size limit apart from `max.file.size.mb`.
- The client writes no temporary files. It reads each file twice: once to
  compute its hash and the size of the data to send, and once while sending.
  The server writes only the `.part` file in the storage directory.
- If a file changes between the client's two reads, the client drops the
  transfer and sends the file again on a later poll.

## Logging

Logs are written to `server/logs/server_jfiltra.log` and
`client/logs/client_jfiltra.log`, and also printed to the console. Each log file
rolls over at 10MB and keeps 10 backups. You can change this in
`server/config/log4j2.properties` and `client/config/log4j2.properties`.

Encryption keys are never written to the logs. Instead, server and client log a
fingerprint of each key: the first 8 hex characters of its SHA-256 hash. The same
key gives the same fingerprint on both sides, so you can compare them in the logs
to find a mismatched key.

## Security notes

jFiltra is suitable for trusted networks. Before using it anywhere more exposed,
keep the following in mind:

- Files are encrypted with Java's default `AES` cipher mode (ECB) using a key
  derived from SHA-256 of the configured secret. Transfers are not authenticated
  beyond the hash check, and the connection itself does not use TLS.
- The server accepts only plain file names from clients. It rejects any name
  containing a path (such as `../` or `/`), so a client cannot write outside its
  storage directory.
- Client labels are not secret. The server rejects unknown labels before storing
  anything. With a known label but the wrong key, decryption fails and the data
  is discarded.
- Limits stop a single connection from holding the server for long:
  - The header must arrive within `header.timeout.seconds`.
  - The whole transfer must finish within `transfer.timeout.seconds`.
  - One IP address can have at most `max.connections.per.ip` connections at a time.
  - Files can be at most `max.file.size.mb`.
- Several hosts together can still keep all `max.connections` workers busy
  within these limits. Restrict access to the port with a firewall.

## License

jFiltra is source-available under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).
You may use, modify and share it for any noncommercial purpose. Commercial use is
not permitted under this license. See [LICENSE](LICENSE) for the full terms.

Required Notice: Copyright 2026 Vladimir Rumanko - BLUEGEM (https://github.com/bluegemsk/jFiltra)
