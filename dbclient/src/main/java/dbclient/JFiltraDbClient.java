/**
* ## License
* This project is licensed under the PolyForm Noncommercial License 1.0.0.
* See the LICENSE file or https://polyformproject.org/licenses/noncommercial/1.0.0
*
* Required Notice: Copyright 2026 Vladimir Rumanko - BLUEGEM (https://github.com/bluegemsk/jFiltra)
*/

package dbclient;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * jFiltra database client: runs a SELECT, writes the rows to a file (CSV, TSV or
 * JSON Lines), sends the file to a jFiltra server with the same protocol as the
 * file client, and after the server's SUCCESS runs an optional post-transfer SQL
 * statement and commits. Select, transfer and post-transfer SQL run in one
 * transaction; any failure before the commit rolls it back.
 */
public class JFiltraDbClient {

    private static final String VERSION = "1.1.1";

    private static final Logger logger = LogManager.getLogger(JFiltraDbClient.class);

    // Exit codes, the same as the file client's run.mode=once
    static final int EXIT_SENT = 0;
    static final int EXIT_CONFIG_ERROR = 1;
    static final int EXIT_NOTHING_TO_SEND = 2;
    static final int EXIT_TRANSFER_FAILED = 3;

    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    private static final int MIN_KEY_LENGTH = 32;
    private static final long MAX_RETRY_MILLIS = 10 * 60 * 1000L;

    // Oracle's own type codes (oracle.jdbc.OracleTypes), not in java.sql.Types
    static final int ORACLE_TIMESTAMPTZ = -101;
    static final int ORACLE_TIMESTAMPLTZ = -102;
    static final int ORACLE_BINARY_FLOAT = 100;
    static final int ORACLE_BINARY_DOUBLE = 101;

    // The server's file name rules (see JFiltraServer): Windows device names are refused
    private static final Pattern WINDOWS_RESERVED_NAME =
            Pattern.compile("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?", Pattern.CASE_INSENSITIVE);
    // Longest file.prefix in bytes: the name gets a timestamp, an extension, and in the
    // spool directory a leading dot and ".tmp"; all must fit in 255 bytes
    static final int MAX_PREFIX_BYTES = 200;
    // A JSON number (anything else, e.g. NaN or Infinity, is written as a string)
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?");
    private static final String SERVER_FILE_TOO_LARGE = "ERROR: File too large";

    // Server connection (as in client.properties)
    private String clientLabel;
    private String serverIp;
    private int serverPort;
    private String encryptionKey;
    private int socketTimeoutSeconds;

    // Database
    private String jdbcUrl;
    private String dbUser;
    private String dbPassword;
    private final List<String> secrets = new ArrayList<>(); // Masked in every logged line
    private Integer isolation;              // Transaction isolation, null = driver default
    private String isolationName;
    private String selectSql;
    private List<String> postTransferSql;   // Statements run after SUCCESS, in order
    private int fetchSize;                  // Rows fetched at a time, 0 = driver default
    private int queryTimeoutSeconds;        // Statement timeout, 0 = none

    // Behaviour and output
    private boolean runOnce;
    private int pollingIntervalSeconds;
    private String fileFormat;      // csv, tsv or jsonl
    private boolean fileHeader;
    private char csvDelimiter;
    private String filePrefix;
    private Path spoolDir;

    private Connection connection;
    private String lastFileName = "";
    private String lastFileHash;            // SHA-256 of the last file sent
    private volatile boolean stopping = false;
    private volatile int exitCode = -1;     // Set by main when the run ended
    private final Object sleepLock = new Object();

    /** Result of one cycle. */
    enum Outcome { SENT, NO_ROWS, TRANSFER_FAILED, TOO_LARGE, POST_SQL_FAILED }

    public JFiltraDbClient(String configPath) {
        try {
            checkUtf8FileNames();
            Properties config = loadProperties(configPath);
            warnIfReadableByOthers(Paths.get(configPath));

            clientLabel = required(config, "client.label");
            serverIp = required(config, "server.host");
            serverPort = intProperty(config, "server.port", null, 1, 65535);
            encryptionKey = config.getProperty("encryption.key");
            if (encryptionKey == null || encryptionKey.isEmpty()) {
                throw new IllegalArgumentException("Missing required setting: encryption.key");
            }
            checkKeyStrength(clientLabel, encryptionKey);
            socketTimeoutSeconds = intProperty(config, "socket.timeout.seconds", 120, 1, 86400);

            jdbcUrl = required(config, "db.url");
            dbUser = config.getProperty("db.user", "").trim();
            // The password may come from an environment variable instead of this file
            String passwordEnv = config.getProperty("db.password.env", "").trim();
            if (!passwordEnv.isEmpty()) {
                dbPassword = System.getenv(passwordEnv);
                if (dbPassword == null) {
                    throw new IllegalArgumentException("Environment variable " + passwordEnv
                            + " (setting db.password.env) is not set");
                }
            } else {
                // Not trimmed: spaces may be part of the password
                dbPassword = config.getProperty("db.password", "");
            }
            secrets.add(dbPassword);
            secrets.addAll(urlPasswords(jdbcUrl));
            isolationName = config.getProperty("db.isolation", "").trim().toLowerCase(Locale.ROOT);
            isolation = parseIsolation(isolationName);
            selectSql = required(config, "select.sql");
            postTransferSql = parsePostTransferSql(config);
            fetchSize = intProperty(config, "db.fetch.size", 1000, 0, 1000000);
            queryTimeoutSeconds = intProperty(config, "db.query.timeout.seconds", 0, 0, 86400);

            String mode = config.getProperty("run.mode", "loop").trim().toLowerCase(Locale.ROOT);
            if (!mode.isEmpty() && !mode.equals("loop") && !mode.equals("once")) {
                throw new IllegalArgumentException("Setting run.mode must be loop or once, but is: "
                        + config.getProperty("run.mode").trim());
            }
            runOnce = mode.equals("once");
            pollingIntervalSeconds = intProperty(config, "polling.interval.seconds", 10, 1, 86400);

            fileFormat = config.getProperty("file.format", "csv").trim().toLowerCase(Locale.ROOT);
            if (fileFormat.isEmpty()) {
                fileFormat = "csv";
            }
            if (!fileFormat.equals("csv") && !fileFormat.equals("tsv") && !fileFormat.equals("jsonl")) {
                throw new IllegalArgumentException("Setting file.format must be csv, tsv or jsonl, but is: "
                        + config.getProperty("file.format").trim());
            }
            String header = config.getProperty("file.header", "true").trim().toLowerCase(Locale.ROOT);
            if (!header.equals("true") && !header.equals("false") && !header.isEmpty()) {
                throw new IllegalArgumentException("Setting file.header must be true or false, but is: "
                        + config.getProperty("file.header").trim());
            }
            fileHeader = !header.equals("false");
            csvDelimiter = parseDelimiter(config.getProperty("csv.delimiter", ","));
            filePrefix = config.getProperty("file.prefix", "export_").trim();
            checkPrefix(filePrefix);
            spoolDir = Paths.get(config.getProperty("spool.directory", "spool").trim());
            if (Files.exists(spoolDir) && !Files.isDirectory(spoolDir)) {
                throw new IllegalArgumentException("Setting spool.directory is not a directory: " + spoolDir.toAbsolutePath());
            }
            Files.createDirectories(spoolDir);
            deleteLeftoverSpoolFiles();

            printConfiguration();
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Error loading configuration: " + safe(e.getMessage()));
            System.exit(EXIT_CONFIG_ERROR);
        }
    }

    // ---------------------------------------------------------------- configuration

    /**
     * Reads post.transfer.sql, then post.transfer.sql.2, .3, ... up to the first one
     * that is missing. Empty values are skipped.
     */
    static List<String> parsePostTransferSql(Properties config) {
        List<String> statements = new ArrayList<>();
        String first = config.getProperty("post.transfer.sql", "").trim();
        if (!first.isEmpty()) {
            statements.add(first);
        }
        for (int i = 2; config.getProperty("post.transfer.sql." + i) != null; i++) {
            String sql = config.getProperty("post.transfer.sql." + i).trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    /**
     * Parses db.isolation: read_committed, repeatable_read or serializable; empty
     * means the driver's default.
     */
    static Integer parseIsolation(String value) {
        switch (value) {
            case "": return null;
            case "read_committed": return Connection.TRANSACTION_READ_COMMITTED;
            case "repeatable_read": return Connection.TRANSACTION_REPEATABLE_READ;
            case "serializable": return Connection.TRANSACTION_SERIALIZABLE;
            default:
                throw new IllegalArgumentException("Setting db.isolation must be read_committed, repeatable_read or serializable, but is: " + value);
        }
    }

    private static String required(Properties config, String name) {
        String value = config.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required setting: " + name);
        }
        return value.trim();
    }

    private static int intProperty(Properties config, String name, Integer defaultValue, int min, int max) {
        String value = config.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Missing required setting: " + name);
            }
            return defaultValue;
        }
        String error = "Setting " + name + " must be a whole number from " + min + " to " + max
                + ", but is: " + value.trim();
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(error);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    /**
     * Parses csv.delimiter: one character, or tab (the word, \\t or a tab) or space (the
     * word, \\s or a single space). Spaces around other values are ignored. Not allowed:
     * the quote character, line breaks, letters and digits (e.g. "\\s" written with one
     * backslash, which the .properties format reads as "s").
     */
    static char parseDelimiter(String value) {
        String v = value.trim();
        if (v.isEmpty() && value.length() == 1) {
            return value.charAt(0); // A single space or tab
        }
        if (v.equalsIgnoreCase("tab") || v.equals("\\t")) {
            return '\t';
        }
        if (v.equalsIgnoreCase("space") || v.equals("\\s")) {
            return ' ';
        }
        if (v.length() != 1 || v.charAt(0) == '"' || Character.isLetterOrDigit(v.charAt(0))) {
            throw new IllegalArgumentException("Setting csv.delimiter must be one character (not \", a letter, a digit"
                    + " or a line break), tab or space, but is: '" + value + "'");
        }
        return v.charAt(0);
    }

    /**
     * The prefix becomes part of a file name: no paths, no control characters, and the
     * resulting name must pass the server's file name rules.
     */
    static void checkPrefix(String prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c) || ":*?\"<>|".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Setting file.prefix must be a plain file name part, but is: " + prefix);
            }
        }
        if (prefix.startsWith(".")) {
            throw new IllegalArgumentException("Setting file.prefix must not start with a dot (the server rejects hidden files)");
        }
        if (prefix.getBytes(StandardCharsets.UTF_8).length > MAX_PREFIX_BYTES) {
            throw new IllegalArgumentException("Setting file.prefix is too long (at most " + MAX_PREFIX_BYTES + " bytes)");
        }
        if (WINDOWS_RESERVED_NAME.matcher(prefix + "20261028-193005-123.csv").matches()) {
            throw new IllegalArgumentException("Setting file.prefix gives file names the server rejects"
                    + " (Windows device names such as CON, NUL, COM1), but is: " + prefix);
        }
    }

    /**
     * Deletes temporary files of this client left in the spool directory by a run that
     * was killed. They may contain exported data.
     */
    private void deleteLeftoverSpoolFiles() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(spoolDir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.startsWith("." + filePrefix) && name.endsWith(".tmp") && Files.isRegularFile(file)) {
                    Files.delete(file);
                    logger.warn("Deleted spool file left by an earlier run: " + file);
                }
            }
        }
    }

    /** Creates an empty file only its owner can read (POSIX file systems). */
    private static void createPrivateFile(Path file) throws IOException {
        Files.deleteIfExists(file);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
        }
    }

    /**
     * Hides passwords in text that is logged: user:password@ in URLs, Oracle's
     * user/password@, and password=... values.
     */
    static String mask(String text) {
        if (text == null) {
            return null;
        }
        String s = text.replaceAll("(//[^/@\\s:]*:)[^/\\s]*@", "$1****@");
        s = s.replaceAll("(?i)(jdbc:oracle:\\w+:[^/@\\s:]+/)[^@\\s]*@", "$1****@");
        s = s.replaceAll("(?i)(password|passwd|pwd)(\\s*[=:]\\s*)[^&;,)\\s'\"]+", "$1$2****");
        return s;
    }

    /** The passwords written in a JDBC URL (the forms {@link #mask(String)} knows). */
    static List<String> urlPasswords(String url) {
        List<String> found = new ArrayList<>();
        for (String regex : new String[] {"//[^/@\\s:]*:([^/\\s]*)@", "(?i)jdbc:oracle:\\w+:[^/@\\s:]+/([^@\\s]*)@",
                "(?i)(?:password|passwd|pwd)\\s*[=:]\\s*([^&;,)\\s'\"]+)"}) {
            java.util.regex.Matcher m = Pattern.compile(regex).matcher(url);
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    /**
     * {@link #mask(String)}, and the configured password and the passwords in db.url
     * wherever they appear (some drivers quote parts of the URL in their messages).
     */
    private String safe(Object text) {
        String s = mask(String.valueOf(text));
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                s = s.replace(secret, "****");
            }
        }
        return s;
    }

    private static void warnIfReadableByOthers(Path config) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(config);
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                logger.warn("Configuration file " + config.toAbsolutePath() + " is readable by all users,"
                        + " but contains the encryption key and maybe the database password. Run: chmod 600 " + config);
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system (e.g. Windows): nothing to check
        }
    }

    // ---------------------------------------------------------------- running

    /** run.mode=once: one cycle, then the exit code. */
    public int runOnce() {
        Outcome outcome = runCycle();
        closeConnection();
        switch (outcome) {
            case SENT: return EXIT_SENT;
            case NO_ROWS: return EXIT_NOTHING_TO_SEND;
            default: return EXIT_TRANSFER_FAILED;
        }
    }

    /**
     * run.mode=loop: runs cycles until stopped, with polling.interval.seconds between
     * them. After a failed transfer it waits longer each time (up to 10 minutes). After
     * a failed post-transfer SQL it stops with exit code 3, because continuing would
     * send the same rows again.
     */
    public int runLoop() {
        long retryDelay = 0;
        String previousHash = null;
        while (!stopping) {
            Outcome outcome = runCycle();
            if (outcome == Outcome.POST_SQL_FAILED) {
                logger.error("JFiltraDbClient stops so the same rows are not sent again and again."
                        + " Fix post.transfer.sql or the data, check the delivered file, then start the client again.");
                closeConnection();
                return EXIT_TRANSFER_FAILED;
            }
            long wait;
            if (outcome == Outcome.TRANSFER_FAILED) {
                retryDelay = (retryDelay == 0) ? pollingIntervalSeconds * 1000L : Math.min(retryDelay * 2, MAX_RETRY_MILLIS);
                wait = retryDelay;
                logger.warn("Will try again in " + (wait / 1000) + " s");
            } else if (outcome == Outcome.TOO_LARGE) {
                // Sending the same rows again soon can't help
                retryDelay = MAX_RETRY_MILLIS;
                wait = retryDelay;
                logger.error("The file is larger than the server's max.file.size.mb. Limit the rows in select.sql"
                        + " (LIMIT / FETCH FIRST n ROWS ONLY). Will try again in " + (wait / 1000) + " s");
            } else {
                retryDelay = 0;
                wait = pollingIntervalSeconds * 1000L;
            }
            if (outcome == Outcome.SENT) {
                if (lastFileHash != null && lastFileHash.equals(previousHash)) {
                    logger.warn("The file has the same content as the previous one. post.transfer.sql probably"
                            + " doesn't mark or remove the sent rows, so they are sent again every "
                            + pollingIntervalSeconds + " s. Check post.transfer.sql and select.sql.");
                }
                previousHash = lastFileHash;
            } else if (outcome == Outcome.NO_ROWS) {
                previousHash = null;
            }
            sleep(wait);
        }
        closeConnection();
        return EXIT_SENT;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        synchronized (sleepLock) {
            long until = System.currentTimeMillis() + millis;
            while (!stopping && System.currentTimeMillis() < until) {
                try {
                    sleepLock.wait(Math.max(1, until - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Asks the loop to stop after the current cycle. */
    public void stop() {
        stopping = true;
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
    }

    /**
     * One cycle: select, write the file, send it, run the post-transfer SQL, commit.
     * Nothing is committed unless the server replied SUCCESS and the post-transfer
     * SQL succeeded.
     */
    Outcome runCycle() {
        Path spoolFile = null;
        String fileName = null;
        boolean delivered = false;
        try {
            Connection conn = connection();
            int rows;
            try (Statement st = createStatement(conn); ResultSet rs = st.executeQuery(selectSql)) {
                if (!rs.next()) {
                    conn.rollback(); // End the transaction (releases locks of SELECT ... FOR UPDATE)
                    logger.debug("No rows to send");
                    return Outcome.NO_ROWS;
                }
                fileName = nextFileName();
                spoolFile = spoolDir.resolve("." + fileName + ".tmp");
                createPrivateFile(spoolFile);
                try (Writer out = new BufferedWriter(new OutputStreamWriter(
                        Files.newOutputStream(spoolFile), StandardCharsets.UTF_8))) {
                    rows = writeRows(rs, out, fileFormat, fileHeader, csvDelimiter);
                }
            }
            logger.info("Selected " + rows + " rows into " + fileName + " (" + Files.size(spoolFile) + " bytes)");

            String response = sendFile(spoolFile, fileName);
            if (!"SUCCESS".equals(response)) {
                rollbackQuietly();
                return SERVER_FILE_TOO_LARGE.equals(response) ? Outcome.TOO_LARGE : Outcome.TRANSFER_FAILED;
            }
            delivered = true;
            logger.info("File sent: " + fileName + " (" + rows + " rows)");

            for (int i = 0; i < postTransferSql.size(); i++) {
                try (Statement st = createStatement(conn)) {
                    st.execute(postTransferSql.get(i));
                    int count = st.getUpdateCount();
                    logger.info("post.transfer.sql" + (i == 0 ? "" : "." + (i + 1)) + " done"
                            + (count >= 0 ? " (" + count + " rows)" : ""));
                }
            }
            conn.commit();
            return Outcome.SENT;
        } catch (Exception e) {
            rollbackQuietly();
            if (delivered) {
                logger.error("File " + fileName + " WAS DELIVERED, but post.transfer.sql or the commit failed: " + safe(e)
                        + ". The database was rolled back, so the same rows would be selected and sent again.");
                closeConnection();
                return Outcome.POST_SQL_FAILED;
            }
            logger.error("Error in database cycle (nothing sent, database rolled back): " + safe(e));
            closeConnection(); // Reconnect next time, in case the connection is broken
            return Outcome.TRANSFER_FAILED;
        } finally {
            if (spoolFile != null) {
                try {
                    Files.deleteIfExists(spoolFile);
                } catch (IOException e) {
                    logger.warn("Could not delete spool file " + spoolFile + ": " + safe(e));
                }
            }
        }
    }

    /** A statement with db.fetch.size and db.query.timeout.seconds applied. */
    private Statement createStatement(Connection conn) throws SQLException {
        Statement st = conn.createStatement();
        if (fetchSize > 0) {
            st.setFetchSize(fetchSize);
        }
        if (queryTimeoutSeconds > 0) {
            st.setQueryTimeout(queryTimeoutSeconds);
        }
        return st;
    }

    private Connection connection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(5)) {
                    return connection;
                }
            } catch (SQLException e) {
                // Reconnect below
            }
            closeConnection();
        }
        connection = DriverManager.getConnection(jdbcUrl, dbUser.isEmpty() ? null : dbUser,
                dbUser.isEmpty() && dbPassword.isEmpty() ? null : dbPassword);
        connection.setAutoCommit(false);
        if (isolation != null) {
            connection.setTransactionIsolation(isolation);
        }
        return connection;
    }

    private void rollbackQuietly() {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException e) {
                logger.warn("Rollback failed: " + safe(e));
            }
        }
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Nothing more to do
            }
            connection = null;
        }
    }

    /**
     * A new file name: prefix + date and time with milliseconds + extension, e.g.
     * export_20261028-193005-123.csv. Never the same name twice in one run.
     */
    private String nextFileName() throws InterruptedException {
        String name;
        while (true) {
            name = filePrefix + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date())
                    + "." + fileFormat;
            if (!name.equals(lastFileName)) {
                break;
            }
            Thread.sleep(1);
        }
        lastFileName = name;
        return name;
    }

    // ---------------------------------------------------------------- writing rows

    /** A result column and how to read it. */
    static final class Column {
        final int index;
        final String label;
        final int type;
        final String typeName;      // Lower case, e.g. "timestamptz"
        final int precision;
        Boolean dateAndTime;        // TIMESTAMP read as LocalDate + LocalTime: null = not known yet

        Column(ResultSetMetaData md, int index) throws SQLException {
            this.index = index;
            this.label = md.getColumnLabel(index);
            this.type = md.getColumnType(index);
            String name = md.getColumnTypeName(index);
            this.typeName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            int p;
            try {
                p = md.getPrecision(index);
            } catch (SQLException | RuntimeException e) {
                p = 0;
            }
            this.precision = p;
        }
    }

    /**
     * Writes all rows of a result set (positioned on its first row) in the given format.
     *
     * @return The number of rows written
     */
    static int writeRows(ResultSet rs, Writer out, String format, boolean header, char delimiter)
            throws SQLException, IOException {
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        Column[] cols = new Column[columns];
        String[] names = new String[columns];
        for (int i = 0; i < columns; i++) {
            cols[i] = new Column(md, i + 1);
            names[i] = cols[i].label;
        }
        if (format.equals("jsonl")) {
            checkUniqueNames(names);
        }
        char sep = format.equals("tsv") ? '\t' : delimiter;
        if (header && !format.equals("jsonl")) {
            for (int i = 0; i < columns; i++) {
                if (i > 0) out.write(sep);
                out.write(format.equals("tsv") ? tsvEscape(names[i]) : csvQuote(names[i], sep));
            }
            out.write('\n');
        }
        int rows = 0;
        do {
            if (format.equals("jsonl")) {
                out.write('{');
                for (int i = 0; i < columns; i++) {
                    if (i > 0) out.write(',');
                    out.write(jsonString(names[i]));
                    out.write(':');
                    out.write(jsonValue(cols[i], value(rs, cols[i], rows + 1)));
                }
                out.write("}\n");
            } else {
                for (int i = 0; i < columns; i++) {
                    if (i > 0) out.write(sep);
                    String value = value(rs, cols[i], rows + 1);
                    if (value == null) {
                        continue; // NULL: empty field
                    }
                    if (format.equals("tsv")) {
                        out.write(tsvEscape(value));
                    } else {
                        // An empty string is "" so it differs from NULL
                        out.write(value.isEmpty() ? "\"\"" : csvQuote(value, sep));
                    }
                }
                out.write('\n');
            }
            rows++;
        } while (rs.next());
        return rows;
    }

    /** JSON keys must be unique: two columns with the same name need aliases. */
    static void checkUniqueNames(String[] names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException("select.sql returns the column name '" + name
                        + "' more than once, but JSON Lines needs unique names. Give the columns different names with AS");
            }
        }
    }

    /**
     * A column value as text, or null for SQL NULL. If the value can't be converted to
     * the documented form, the driver's text form is used; if even that fails, the
     * error names the row and the column.
     */
    private static String value(ResultSet rs, Column c, int row) throws SQLException {
        try {
            return textValue(rs, c);
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            try {
                return rs.getString(c.index);
            } catch (SQLException | RuntimeException e2) {
                throw new SQLException("Row " + row + ", column " + c.label + " (" + c.typeName
                        + "): the value can't be read: " + e2.getMessage(), e2);
            }
        }
    }

    /**
     * A column value in the documented form, or null for SQL NULL. Dates and times in
     * ISO 8601 (without the JVM's time zone), numbers without exponent, binary data in
     * Base64, CLOBs as their full text.
     */
    static String textValue(ResultSet rs, Column c) throws SQLException {
        int col = c.index;
        switch (c.type) {
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case ORACLE_TIMESTAMPTZ:
            case ORACLE_TIMESTAMPLTZ:
                return offsetDateTime(rs, col);
            case Types.TIMESTAMP: {
                if (c.typeName.equals("timestamptz")) {
                    return offsetDateTime(rs, col); // PostgreSQL reports timestamptz as TIMESTAMP
                }
                LocalDateTime t = localDateTime(rs, c);
                if (t == null) {
                    return null;
                }
                return isInfinite(t.getYear()) ? rs.getString(col) : t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            case Types.DATE: {
                LocalDate d = rs.getObject(col, LocalDate.class);
                if (d == null) {
                    return null;
                }
                return isInfinite(d.getYear()) ? rs.getString(col) : d.toString();
            }
            case Types.TIME_WITH_TIMEZONE:
                return offsetTime(rs, col);
            case Types.TIME:
                if (c.typeName.equals("timetz")) {
                    return offsetTime(rs, col); // PostgreSQL reports timetz as TIME
                }
                // The driver's text: exact, and MySQL's TIME can be a duration such as 838:59:59
                return trimFraction(rs.getString(col));
            case Types.NUMERIC:
            case Types.DECIMAL: {
                BigDecimal n = rs.getBigDecimal(col); // NaN, Infinity: fails, written as text
                return n == null ? null : n.toPlainString();
            }
            case Types.BIT:
                if (c.precision > 1) {
                    return bitValue(rs, c);
                }
                // BIT(1): a boolean
            case Types.BOOLEAN: {
                boolean b = rs.getBoolean(col);
                return rs.wasNull() ? null : Boolean.toString(b);
            }
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB: {
                byte[] bytes = rs.getBytes(col);
                return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
            }
            default:
                // Text, CLOB, integers, floats and anything else: the driver's text form
                return rs.getString(col);
        }
    }

    private static String offsetDateTime(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        if (t == null) {
            return null;
        }
        return isInfinite(t.getYear()) ? rs.getString(col) : t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String offsetTime(ResultSet rs, int col) throws SQLException {
        OffsetTime t = rs.getObject(col, OffsetTime.class);
        return t == null ? null : t.format(DateTimeFormatter.ISO_OFFSET_TIME);
    }

    /**
     * A timestamp without time zone, never converted through the JVM's time zone (which
     * would shift times in a daylight saving gap). Read as date plus time where the
     * driver supports it (the MariaDB driver's LocalDateTime is shifted in a gap),
     * otherwise as LocalDateTime.
     */
    private static LocalDateTime localDateTime(ResultSet rs, Column c) throws SQLException {
        if (c.dateAndTime != Boolean.FALSE) {
            try {
                LocalDate d = rs.getObject(c.index, LocalDate.class);
                if (d == null) {
                    return null;
                }
                LocalTime t = rs.getObject(c.index, LocalTime.class);
                if (t != null && !isInfinite(d.getYear())) {
                    c.dateAndTime = Boolean.TRUE;
                    return LocalDateTime.of(d, t);
                }
            } catch (SQLException | RuntimeException | AbstractMethodError e) {
                // Not supported by this driver (e.g. PostgreSQL): use LocalDateTime
            }
            if (c.dateAndTime == null) {
                c.dateAndTime = Boolean.FALSE;
            }
        }
        return rs.getObject(c.index, LocalDateTime.class);
    }

    /** PostgreSQL's infinity / -infinity arrive as the largest / smallest Java date. */
    private static boolean isInfinite(int year) {
        return Math.abs(year) >= 999999999;
    }

    /** "19:30:05.500000" -> "19:30:05.5", "19:30:05.000" -> "19:30:05", like the other times. */
    static String trimFraction(String time) {
        if (time == null || !time.matches("-?[0-9]+:[0-9]{2}:[0-9]{2}\\.[0-9]+")) {
            return time;
        }
        String t = time.replaceAll("0+$", "");
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    /**
     * BIT(n) with n > 1 as a string of 0 and 1 with n digits, e.g. 10101010. PostgreSQL
     * returns that text itself; MySQL and MariaDB return the bytes.
     */
    private static String bitValue(ResultSet rs, Column c) throws SQLException {
        String text = rs.getString(c.index);
        if (text == null) {
            return null;
        }
        if (text.length() == c.precision && text.matches("[01]+")) {
            return text;
        }
        return bitString(rs.getBytes(c.index), c.precision);
    }

    /** The last {@code bits} bits of the bytes (big-endian) as 0 and 1. */
    static String bitString(byte[] bytes, int bits) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 8);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                sb.append((b >> i & 1) == 1 ? '1' : '0');
            }
        }
        while (sb.length() < bits) {
            sb.insert(0, '0');
        }
        return sb.substring(sb.length() - Math.min(bits, sb.length()));
    }

    /** A value as JSON: numbers and booleans as such, everything else as a string. */
    static String jsonValue(Column c, String value) {
        if (value == null) {
            return "null";
        }
        switch (c.type) {
            case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
            case Types.NUMERIC: case Types.DECIMAL:
            case Types.REAL: case Types.FLOAT: case Types.DOUBLE:
            case ORACLE_BINARY_FLOAT: case ORACLE_BINARY_DOUBLE:
                // NaN and Infinity are not valid JSON numbers
                return JSON_NUMBER.matcher(value).matches() ? value : jsonString(value);
            case Types.BIT: case Types.BOOLEAN:
                return value.equals("true") || value.equals("false") ? value : jsonString(value);
            default:
                return jsonString(value);
        }
    }

    /** CSV field (RFC 4180): quoted if it contains the delimiter, a quote or a line break. */
    static String csvQuote(String value, char delimiter) {
        if (value.indexOf(delimiter) < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** TSV field: tab, line breaks and backslash as \t \n \r \\ (as in PostgreSQL's text format). */
    static String tsvEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- transfer (same protocol as the file client)

    /**
     * Sends a file to the server exactly like the file client: label, file name,
     * SHA-256 of the content, size of the compressed and encrypted data, then the data.
     *
     * @return The server's reply ("SUCCESS" or "ERROR: ..."), or null if there was none
     */
    private String sendFile(Path file, String fileName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream sizeCounter = new CountingOutputStream(new DiscardingOutputStream());
            writeEncrypted(file, sizeCounter, digest);
            String fileHash = toHex(digest.digest());
            lastFileHash = fileHash;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MILLIS);
                socket.setSoTimeout(socketTimeoutSeconds * 1000);
                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                try {
                    dos.writeUTF(clientLabel);
                    dos.writeUTF(fileName);
                    dos.writeUTF(fileHash);
                    dos.writeLong(sizeCounter.count);
                    writeEncrypted(file, dos, null);
                    dos.flush();
                } catch (IOException writeError) {
                    // The server may have rejected the file early and closed the connection
                    try {
                        socket.setSoTimeout(2000);
                        String reply = new DataInputStream(socket.getInputStream()).readUTF();
                        if (reply.startsWith("ERROR: ")) {
                            logger.error("Server rejected file " + fileName + ": " + reply);
                            return reply;
                        }
                    } catch (IOException noReply) {
                        // No reply available
                    }
                    logger.warn("Could not send " + fileName + ": connection lost while sending (" + safe(writeError) + ")");
                    return null;
                }
                String response = new DataInputStream(socket.getInputStream()).readUTF();
                if (!"SUCCESS".equals(response)) {
                    logger.error("Server rejected file " + fileName + ": " + response);
                }
                return response;
            }
        } catch (IOException e) {
            logger.warn("Could not send " + fileName + ": server not reachable or connection lost (" + safe(e) + ")");
            return null;
        } catch (GeneralSecurityException e) {
            logger.error("Error sending file " + fileName + ": " + safe(e));
            return null;
        }
    }

    private void writeEncrypted(Path file, OutputStream out, MessageDigest digest)
            throws IOException, GeneralSecurityException {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"));
        try (InputStream fileIn = Files.newInputStream(file);
             GZIPOutputStream gzos = new FastGZIPOutputStream(new CipherOutputStream(new NonClosingOutputStream(out), cipher))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fileIn.read(buffer)) != -1) {
                if (digest != null) {
                    digest.update(buffer, 0, len);
                }
                gzos.write(buffer, 0, len);
            }
        }
    }

    // ---------------------------------------------------------------- helpers copied from the file client

    static void checkUtf8FileNames() {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        String enc = System.getProperty("sun.jnu.encoding", "");
        if (!enc.equalsIgnoreCase("UTF-8") && !enc.equalsIgnoreCase("UTF8")) {
            throw new IllegalArgumentException("File names need a UTF-8 locale, but the system uses " + enc
                    + ". Set e.g. LANG=C.UTF-8 or LANG=en_US.UTF-8 (see README, Java)");
        }
    }

    private static Properties loadProperties(String path) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Paths.get(path));
        } catch (NoSuchFileException e) {
            throw new IOException("Configuration file not found: " + Paths.get(path).toAbsolutePath());
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(path + " is not valid UTF-8; save it with UTF-8 encoding");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Properties props = new Properties();
        props.load(new StringReader(text));
        return props;
    }

    private static void checkKeyStrength(String label, String key) {
        String hint = " Generate a key with: openssl rand -base64 32";
        if (key.trim().startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("Encryption key for " + label
                    + " is still the CHANGE_ME placeholder." + hint);
        }
        int length = key.codePointCount(0, key.length());
        if (length < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("Encryption key for " + label + " is too short ("
                    + length + " characters, at least " + MIN_KEY_LENGTH + " required)." + hint);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void printConfiguration() {
        String fingerprint;
        try {
            fingerprint = toHex(MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception e) {
            fingerprint = "(unavailable)";
        }
        logger.info("=========================================");
        logger.info("=== DB Client jFiltra Started ===========");
        logger.info("=========================================");
        logger.info("Version: " + VERSION);
        logger.info("Client Label: " + clientLabel);
        logger.info("Server: " + serverIp + ":" + serverPort);
        logger.info("Encryption Key Fingerprint: " + fingerprint);
        // The URL is shown without its parameters, which may contain a password
        logger.info("Database URL: " + safe(jdbcUrl.replaceAll("[?;].*$", "")) + ", User: " + (dbUser.isEmpty() ? "(none)" : dbUser));
        logger.info("Fetch Size: " + (fetchSize == 0 ? "driver default" : fetchSize)
                + ", Query Timeout: " + (queryTimeoutSeconds == 0 ? "none" : queryTimeoutSeconds + " seconds"));
        logger.info("Transaction Isolation: " + (isolationName.isEmpty() ? "driver default" : isolationName));
        logger.info("Run Mode: " + (runOnce ? "once" : "loop"));
        logger.info("Polling Interval: " + pollingIntervalSeconds + " seconds");
        logger.info("File Format: " + fileFormat + (fileFormat.equals("jsonl") ? "" : ", Header: " + fileHeader)
                + (fileFormat.equals("csv") ? ", Delimiter: " + (csvDelimiter == '\t' ? "tab" : "'" + csvDelimiter + "'") : ""));
        logger.info("File Prefix: " + filePrefix);
        logger.info("Post-transfer SQL: " + (postTransferSql.isEmpty() ? "(none)" : postTransferSql.size() + " statement(s)"));
        logger.info("-----------------------------------------");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Error: Missing configuration file path!");
            System.out.println("Usage: java dbclient.JFiltraDbClient <config-file-path>");
            System.exit(EXIT_CONFIG_ERROR);
        }
        final JFiltraDbClient client = new JFiltraDbClient(args[0]);
        // On Ctrl+C or systemctl stop, finish the current cycle first (both run modes), so
        // a delivered file is followed by its post-transfer SQL and commit. Then exit with
        // the run's exit code (0 for a normal stop of the loop).
        final Thread mainThread = Thread.currentThread();
        Thread hook = new Thread(() -> {
            client.stop();
            try {
                mainThread.join(60000);
            } catch (InterruptedException ignored) {
                // Exit anyway
            }
            int code = client.exitCode;
            if (mainThread.isAlive() || code < 0) {
                logger.warn("JFiltraDbClient stopped before the cycle finished (the database rolls it back)");
                code = EXIT_TRANSFER_FAILED;
            }
            logger.info("JFiltraDbClient stopped" + (client.runOnce ? ", exit code " + code : ""));
            LogManager.shutdown();
            Runtime.getRuntime().halt(code);
        });
        Runtime.getRuntime().addShutdownHook(hook);
        int code;
        try {
            code = client.runOnce ? client.runOnce() : client.runLoop();
        } catch (Throwable t) {
            // E.g. OutOfMemoryError: log it (not only on stderr) and don't report a configuration error
            client.closeConnection();
            logger.error("JFiltraDbClient stops after an unexpected error (nothing committed): " + client.safe(t));
            code = EXIT_TRANSFER_FAILED;
        }
        client.exitCode = code;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            return; // Stopped by a signal: the hook exits with this code
        }
        if (client.runOnce) {
            logger.info("JFiltraDbClient finished one-time run, exit code " + code);
        }
        System.exit(code);
    }

    // ---------------------------------------------------------------- streams (as in the file client)

    private static class FastGZIPOutputStream extends GZIPOutputStream {
        FastGZIPOutputStream(OutputStream out) throws IOException {
            super(out, 8192);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private static class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private static class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static class DiscardingOutputStream extends OutputStream {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
