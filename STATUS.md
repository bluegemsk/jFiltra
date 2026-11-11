# jFiltra – Status

Test status and change history of jFiltra. Product documentation: [README.md](README.md).

1.1.12 (November 2026) addresses cosmetic finding M1 from the 1.1.11 product test.
No known open bugs remain in the tested scenarios:

- Bundled suites pass on Temurin 21.0.12.1 and Temurin 1.8.0_504: the file client and server smoke test
  (274 checks), the DB client smoke test against PostgreSQL
  (131 checks) and MariaDB (122 with the MariaDB driver, 125 with MySQL Connector/J,
  and 122 with Connector/J cursor fetching), and the Java version script test
  (48 cases, included in each file smoke run). Permission-based checks run as a
  non-root user; Linux write failures are exercised with `ulimit -f`, including
  new-file cleanup, buffered close failures and append rollback.
- Earlier product test on Java 8: 300 files from two source directories with `after.send=move`,
  port probes and a key file change during the run. All files arrived intact, with
  no warnings or errors.
- Changes in 1.1.12:
  - Once-mode cleanup failures after delivery are reported on one ERROR line with
    the full manual-cleanup advice, conditional append warning and operating-system
    reason. There is no separate WARN advice; loop-mode logging is unchanged.
- Changes in 1.1.11:
  - Writable source and archive directories must also have execute/search permission.
    A `sent/` directory without execute permission blocks the source folder before
    upload, preventing repeated append deliveries in once mode.
  - After a delivered file cannot be removed or moved, once mode directs manual
    cleanup before another run, with a conditional warning about append duplicates.
    The client does not know the server's conflict mode; ordinary failed-transfer
    retry messages and loop-mode cleanup retries are unchanged.
- Changes in 1.1.10:
  - Unreadable stored files are treated as different content: rename, overwrite and
    reject still work, with one warning and no stack trace; append fails cleanly.
  - Source directories need read and execute/search permission. Unavailable folders
    are logged once in loop mode, with recovery logged when accessible again;
    once mode exits 3 if no source directory is accessible.
  - Storage write failures (including disk full, quota and OS file-size limits) are
    logged on one contextual line, including buffered flush/close failures.
    Failed uploads are cleaned up and failed appends are rolled back.
  - A directory at `sent/<fileName>` blocks only that file, not its neighbours.
- Changes in 1.1.9:
  - A file that grows during hashing is removed or archived after successful delivery
    if unchanged since the hash pass, avoiding duplicate append deliveries.
  - Storage filesystem errors are logged on one line, without stack traces.
  - An archive target that is a directory is detected before uploading.
  - A one-time run with no readable source directory exits 3, with clearer path errors.
  - Invalid mapping edits are logged once per file version; recovery is still logged once.
- Changes in 1.1.8:
  - The server keeps running when accepting a connection fails, and logs ordinary
    network events on one line without stack traces.
  - The server reloads the key and path files without a restart.
  - A wrong key is refused even for a file that is already stored.
  - In `once` mode, one stuck file or directory no longer blocks the others.
  - With `after.send`, the client checks before sending that it will be able to
    delete or move the file.
  - Clearer errors for `filemask` and `ignore.patterns`.
  - The scripts accept Java versions with a suffix (`25-ea`, `17-internal`).
  - The DB client hides passwords in logs more reliably.

Not tested yet: Windows (`.bat` scripts), macOS, network shares, and MySQL and Oracle servers.
