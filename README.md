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
| `max.file.size.mb`    | No       | Largest file accepted, both as sent and after decompression (default `512`) |

Relative paths are resolved from the `server/` folder, because the start script
runs the server from there. Leaving out `client.keys.path` means no keys are
loaded, so the server rejects every client.

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
- A file is sent only once its size and modification time are unchanged between
  two scans, so files that are still being written or copied are not sent half
  finished. A new file is therefore sent after one to two polling intervals.
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
- Existing files on the server are never overwritten. While a file with the same
  name is already there, the client keeps retrying and logging an error until
  that file is moved or renamed.
- The server first writes each file under a hidden temporary name
  (`.jfiltra-<random>.part`) in the storage directory, then renames it to its
  real name. Other programs watching the storage directory therefore never see a
  half-written file. If the server is stopped in the middle of a write, a
  `.part` file may be left behind and can be deleted.
- Each file is held in memory while it is encrypted and decrypted. On the server,
  `max.file.size.mb` sets the largest accepted file. Give the server a Java heap
  of well over twice that size. No file can be larger than 2GB. On the client, a
  file too large for its heap is logged as an error on each poll and left in
  place, and the other files are still sent.

## Logging

Logs are written to `server/logs/server_jfiltra.log` and
`client/logs/client_jfiltra.log`, and also printed to the console. Each log file
rolls over at 10MB and keeps 10 backups. You can change this in
`server/config/log4j2.properties` and `client/config/log4j2.properties`.

Encryption keys are never written to the logs in full. Only the first and last
4 characters are shown.

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
- The read timeout and `max.file.size.mb` limit how long a connection can stay
  open and how much data it can send. Anyone who can reach the port can still
  keep the server's 10 worker threads busy for that long. Restrict access to the
  port with a firewall.

## License

jFiltra is source-available under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).
You may use, modify and share it for any noncommercial purpose. Commercial use is
not permitted under this license. See [LICENSE](LICENSE) for the full terms.

Required Notice: Copyright 2026 Vladimir Rumanko - BLUEGEM (https://github.com/bluegemsk/jFiltra)
