# jFiltra

jFiltra is a lightweight Java client/server tool for moving files from watched
directories to a central server, reliably and with as little setup as possible.

- The **client** polls one or more source directories. For each file it computes a
  SHA-256 hash, compresses the file with GZIP, scrambles it with AES using a
  client-specific key, and sends it to the server. After the server confirms
  receipt, the client deletes the original file.
- The **server** accepts connections from multiple clients. It looks up the
  client's key, decrypts and decompresses the file, checks the SHA-256 hash, and
  stores the file in that client's configured storage directory.

jFiltra does not encrypt the connection. Use it on a trusted network, or through
a VPN or SSH tunnel; see [Security notes](#security-notes).

## Requirements

- Java 8 or newer, with `java` on your `PATH` (JDK for compiling, JRE for running).
  The compile scripts always build JARs that run on Java 8, whatever JDK you build with.
- Bash on Linux and macOS, or the Command Prompt on Windows (to run the scripts)
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
  script/                 compile.sh/.bat, start-server.sh/.bat
  src/main/java/server/   JFiltraServer.java
client/
  config/                 client.properties, log4j2.properties
  script/                 compile.sh/.bat, start-client.sh/.bat
  src/main/java/client/   JFiltraClient.java
test/
  smoke-test.sh           end-to-end test (see Testing)
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
| `server.bind.address` | No       | Network address to listen on (default: all interfaces). `127.0.0.1` accepts only connections from the same machine, for example through an SSH tunnel |
| `client.keys.path`    | No       | File that maps each client label to its key                |
| `client.paths.config` | No       | File that maps each client label to its storage directory  |
| `socket.timeout.seconds` | No    | Seconds to wait for data from a client before closing the connection (default `30`) |
| `header.timeout.seconds` | No    | Seconds a client has to send the transfer header: label, file name, hash and size (default `5`) |
| `transfer.timeout.seconds` | No  | Maximum total seconds for one transfer, including processing (default `600`); raise it for large files or slow networks |
| `max.connections`     | No       | Maximum transfers handled at the same time (default `10`)  |
| `max.connections.per.ip` | No    | Maximum simultaneous connections from one IP address (default `4`); raise it when many clients share one IP address, for example on one host or behind NAT |
| `max.file.size.mb`    | No       | Largest file accepted, both as sent and after decompression (default `512`) |
| `on.name.conflict`    | No       | What to do when a file with the same name but different content is already stored: `reject` (default) or `rename`; see [Name conflicts](#name-conflicts) |

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

### Keys

Each client needs its own key, at least 32 characters long. Put the same key in
the server's `client_keys.properties` and in the client's `client.properties`.
Server and client refuse to start with the shipped `CHANGE_ME` placeholder or
with a shorter key.

Generate a key with:

```bash
openssl rand -base64 32
```

On Windows, `openssl` comes with Git for Windows. In PowerShell 7 you can also use
`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`.

> **Don't commit real keys.** Once you have set real keys,
> `server/config/client_keys.properties` and `client/config/client.properties`
> contain secrets. Don't commit them to a public repository, or a fork of this one.

## Build

Download the Log4j 2 JARs first (see [Requirements](#requirements)). Then run the
compile scripts; they work from any directory:

```bash
server/script/compile.sh   # builds server/lib/jfiltra-server.jar
client/script/compile.sh   # builds client/lib/jfiltra-client.jar
```

On Windows, run `server\script\compile.bat` and `client\script\compile.bat`.

The JARs run on Java 8 or newer, even when built with a newer JDK.

## Run

Start the server first, then one or more clients. The start scripts work from
any directory:

```bash
server/script/start-server.sh
```

```bash
client/script/start-client.sh
```

On Windows, run `server\script\start-server.bat` and `client\script\start-client.bat`.

Both run in the foreground. Press `Ctrl+C` to stop them.

To try it locally with the default configuration:

1. Generate a key (see [Keys](#keys)) and replace the `CHANGE_ME` placeholder
   for `client1` with it in both `server/config/client_keys.properties` and
   `client/config/client.properties`.
2. Start the server and the client as shown above.
3. Drop a file into a source directory:

   ```bash
   mkdir -p /tmp/jfiltra/source1 /tmp/jfiltra/source2
   echo "hello jFiltra" > /tmp/jfiltra/source1/test.txt
   # A few seconds later the file appears in /tmp/jfiltra/received/client1/
   # and is removed from /tmp/jfiltra/source1/
   ```

### Running as a service

On Linux, systemd can start jFiltra at boot and restart it after a failure. For
example, for the server in `/opt/jfiltra`, create
`/etc/systemd/system/jfiltra-server.service`:

```ini
[Unit]
Description=jFiltra server
After=network-online.target

[Service]
User=jfiltra
ExecStart=/opt/jfiltra/server/script/start-server.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Then run `sudo systemctl enable --now jfiltra-server`. The client works the same
way with `start-client.sh`. Both exit with code `1` when they can't start, for
example because the port is in use, so systemd reports the failure.

On Windows, a service wrapper such as [NSSM](https://nssm.cc/) can run
`start-server.bat` or `start-client.bat` as a Windows service.

## How transfers behave

- The client sends only the files directly inside each source directory.
  Subdirectories are not scanned. To watch a subdirectory, add it to
  `source.directories` too.
- Symbolic links are never sent or deleted. Following them could send any file
  the client can read, including files outside the source directory.
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
  other cases the file stays where it is and is retried (see [Retries](#retries)).
- When the server rejects a file, the client logs the reason, for example
  `Server rejected file report.pdf: ERROR: File already exists`. The server log
  has the details. A wrong key is reported as `ERROR: Decryption failed`.
- Existing files on the server are never overwritten, even when two transfers of
  the same name arrive at the same time.
- If a file with the same name **and the same content** is already stored, the
  transfer counts as successful and the client deletes its copy. This happens,
  for example, when the client missed the server's reply and sends the file again.
- If a file with the same name but **different content** is already stored, see
  [Name conflicts](#name-conflicts).
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
- The client writes no temporary files. It reads each file twice: once to
  compute its hash and the size of the data to send, and once while sending.
  The server writes only the `.part` file in the storage directory.
- If a file changes between the client's two reads, the client drops the
  transfer and sends the file again on a later poll.

### Retries

The client never gives up on a file, but it waits longer between attempts when
retrying doesn't help right away, so a large file isn't sent again in full on
every poll:

- **Server unreachable** (server down, connection refused or lost, server busy):
  the client pauses all transfers. The pause starts at the polling interval and
  doubles after each failed attempt, up to 1 minute. Each attempt is logged in
  one line.
- **Rejected for a reason a person can fix** (`File already exists`, `Decryption
  failed`, `No encryption key found`, a failed hash check), or delivered but the
  client can't delete it (no permission): the file is retried after the polling
  interval, then after twice as long each time, up to 10 minutes. Fixing the
  problem, for example the key on the server, needs no restart; the file is sent
  at its next attempt. If the file itself changes, it is sent again right away.
- **Rejected for a reason retrying can't fix** (`File too large`, `Invalid file
  name`): the file is not sent again until it changes. The client logs this once.

### Name conflicts

`on.name.conflict` on the server decides what happens when a client sends a file
whose name is already taken by a file with **different content**:

- `reject` (default): the stored file is kept, and the new file stays on the
  client. The client retries with growing delays (see [Retries](#retries)) until
  someone moves or renames the stored file.
- `rename`: the new file is stored under a new name with the date and time
  added, for example `export_20261012-150012.csv`, and the client deletes its
  copy. This suits files that arrive under the same name again and again, such as
  a daily `export.csv`. In the rare case that the client misses the server's
  reply, it sends the file again, and it is then stored a second time under
  another new name.

Files with the same name **and the same content** are never stored twice in
either mode.

### Large files

Files are processed in small chunks, so memory use stays the same whatever the
file size, and there is no size limit apart from `max.file.size.mb`. For files
over 512 MB, change these settings:

| Where  | Setting                    | Why |
|--------|----------------------------|-----|
| Server | `max.file.size.mb`         | Raise it above your largest file |
| Server | `transfer.timeout.seconds` | Raise it so the slowest expected transfer fits (default 600 seconds) |
| Client | `socket.timeout.seconds`   | Raise it on slow links |

The size limit applies to the data as sent too. Compression can't shrink data
that is already compressed (such as ZIP files, videos or photos) and makes it
slightly larger, so such a file just under the limit can be rejected.

As a rough guide, tests on fast hardware reached about 18 to 30 MB/s end to end
for data that doesn't compress, and much more for text, which compresses well.
On a real network, the network speed usually sets the limit.

## Logging

Logs are written to `server/logs/server_jfiltra.log` and
`client/logs/client_jfiltra.log`, and also printed to the console. Each log file
rolls over at 10MB and keeps 10 backups. You can change this in
`server/config/log4j2.properties` and `client/config/log4j2.properties`.

Both log their version and settings at startup. During operation, the client logs
one line per sent file, for example
`File sent: report.pdf (12345 bytes, 0.40 s, SHA-256 ab12...)`. A missing source
directory is logged once, and again when it is available. Network problems are
logged in one line each, without a stack trace.

Encryption keys are never written to the logs. Instead, server and client log a
fingerprint of each key: the first 8 hex characters of its SHA-256 hash. The same
key gives the same fingerprint on both sides, so you can compare them in the logs
to find a mismatched key.

Control characters in client-supplied file names and labels are logged as `?`,
so they can't forge extra log lines.

## Security notes

jFiltra is built for simple, reliable file exchange on trusted networks. It is
**not** a secure file transfer product:

- The connection is not encrypted (no TLS). Client labels, file names, file
  sizes and hashes travel in plain text.
- File content is scrambled with AES in ECB mode. That keeps it from being read
  at a glance, but it is not strong encryption: identical files produce
  identical data on the network, and patterns in the content can show through.
- Transfers are not protected against replay. Someone who records a transfer can
  send it to the server again, also under a different file name, without
  knowing the key.

To send files over an untrusted network, such as the internet, run jFiltra
through a VPN or an SSH tunnel. For an SSH tunnel:

1. On the server, set `server.bind.address=127.0.0.1`, so the jFiltra port can't
   be reached from the network at all.
2. On the client machine, open the tunnel:

   ```bash
   ssh -N -L 9000:localhost:9000 user@server-host
   ```

3. Set `server.host=localhost` in `client.properties`.

The SSH connection then encrypts and authenticates everything between client and
server.

Protection that jFiltra does provide:

- Keys must be at least 32 characters (see [Keys](#keys)), so they can't be
  guessed.
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

## Testing

`test/smoke-test.sh` builds server and client, runs them in a temporary
directory, and checks the main behaviours: delivery, skipped files and symbolic
links, oversized files, wrong keys, duplicates, retries, name conflicts, an
unreachable server, startup errors, and that the JARs run on Java 8. It needs
Java and the Log4j 2 JARs in `server/lib` and `client/lib`:

```bash
test/smoke-test.sh
```

It prints `PASS` or `FAIL` for each check and exits with code `0` when all pass.
To run server and client with a different Java, for example Java 8, set
`JFILTRA_TEST_JAVA=/path/to/java`.

The same test runs on GitHub on every push: on Java 8, 11, 17 and 21, and once
with JARs built on Java 21 and run on Java 8.

## Roadmap

Ideas for later versions, kept out of 1.0 to keep jFiltra simple:

- Scanning subdirectories of the source directories
- Moving sent files to a `sent/` directory instead of deleting them

## Support this project

jFiltra is free. If it helps you, consider sending a small crypto donation:

- **SOL**: `DL5sEEG6z666vyety2FdDZtTF1pMtMAnjKXSdZTYg34K`
- **BNB**: `0xC08f5CC86610e400bb3c12Fe8a085514F7e786E0`

## License

jFiltra is source-available under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).
You may use, modify and share it for any noncommercial purpose. Commercial use is
not permitted under this license. See [LICENSE](LICENSE) for the full terms.

Required Notice: Copyright 2026 Vladimir Rumanko - BLUEGEM (https://github.com/bluegemsk/jFiltra)
