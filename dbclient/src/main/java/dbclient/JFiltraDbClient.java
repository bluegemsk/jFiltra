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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    private static final String VERSION = "1.1.0";

    private static final Logger logger = LogManager.getLogger(JFiltraDbClient.class);

    // Exit codes, the same as the file client's run.mode=once
    static final int EXIT_SENT = 0;
    static final int EXIT_CONFIG_ERROR = 1;
    static final int EXIT_NOTHING_TO_SEND = 2;
    static final int EXIT_TRANSFER_FAILED = 3;

    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    private static final int MIN_KEY_LENGTH = 32;
    private static final long MAX_RETRY_MILLIS = 10 * 60 * 1000L;

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
    private Integer isolation;              // Transaction isolation, null = driver default
    private String isolationName;
    private String selectSql;
    private List<String> postTransferSql;   // Statements run after SUCCESS, in order

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
    private volatile boolean stopping = false;
    private final Object sleepLock = new Object();

    /** Result of one cycle. */
    enum Outcome { SENT, NO_ROWS, TRANSFER_FAILED, POST_SQL_FAILED }

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
            isolationName = config.getProperty("db.isolation", "").trim().toLowerCase(Locale.ROOT);
            isolation = parseIsolation(isolationName);
            selectSql = required(config, "select.sql");
            postTransferSql = parsePostTransferSql(config);

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
            Files.createDirectories(spoolDir);

            printConfiguration();
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Error loading configuration: " + e.getMessage());
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
     * Parses csv.delimiter: one character, or the word "tab". Not allowed: the quote
     * character and line breaks, which would make the file unreadable.
     */
    static char parseDelimiter(String value) {
        if (value.equalsIgnoreCase("tab")) {
            return '\t';
        }
        // Not trimmed, so a single space can be the delimiter
        if (value.length() != 1 || value.charAt(0) == '"' || value.charAt(0) == '\r' || value.charAt(0) == '\n') {
            throw new IllegalArgumentException("Setting csv.delimiter must be one character (not \" or a line break) or tab, but is: '" + value + "'");
        }
        return value.charAt(0);
    }

    /** The prefix becomes part of a file name: no paths, no control characters. */
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
     * run.mode=loop: runs cycles until stopped. After a failed transfer it waits
     * longer each time (up to 10 minutes). After a failed post-transfer SQL it stops
     * with exit code 3, because continuing would send the same rows again.
     */
    public int runLoop() {
        long retryDelay = 0;
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
            } else {
                retryDelay = 0;
                // Rows were sent: look for more right away; no rows: wait for the next poll
                wait = (outcome == Outcome.SENT) ? 0 : pollingIntervalSeconds * 1000L;
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
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(selectSql)) {
                if (!rs.next()) {
                    conn.rollback(); // End the transaction (releases locks of SELECT ... FOR UPDATE)
                    logger.debug("No rows to send");
                    return Outcome.NO_ROWS;
                }
                fileName = nextFileName();
                spoolFile = spoolDir.resolve("." + fileName + ".tmp");
                try (Writer out = new BufferedWriter(new OutputStreamWriter(
                        Files.newOutputStream(spoolFile), StandardCharsets.UTF_8))) {
                    rows = writeRows(rs, out, fileFormat, fileHeader, csvDelimiter);
                }
            }
            logger.info("Selected " + rows + " rows into " + fileName + " (" + Files.size(spoolFile) + " bytes)");

            String response = sendFile(spoolFile, fileName);
            if (!"SUCCESS".equals(response)) {
                rollbackQuietly();
                return Outcome.TRANSFER_FAILED;
            }
            delivered = true;
            logger.info("File sent: " + fileName + " (" + rows + " rows)");

            for (int i = 0; i < postTransferSql.size(); i++) {
                try (Statement st = conn.createStatement()) {
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
                logger.error("File " + fileName + " WAS DELIVERED, but post.transfer.sql or the commit failed: " + e
                        + ". The database was rolled back, so the same rows would be selected and sent again.");
                closeConnection();
                return Outcome.POST_SQL_FAILED;
            }
            logger.error("Error in database cycle (nothing sent, database rolled back): " + e);
            closeConnection(); // Reconnect next time, in case the connection is broken
            return Outcome.TRANSFER_FAILED;
        } finally {
            if (spoolFile != null) {
                try {
                    Files.deleteIfExists(spoolFile);
                } catch (IOException e) {
                    logger.warn("Could not delete spool file " + spoolFile + ": " + e);
                }
            }
        }
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
                logger.warn("Rollback failed: " + e);
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

    /**
     * Writes all rows of a result set (positioned on its first row) in the given format.
     *
     * @return The number of rows written
     */
    static int writeRows(ResultSet rs, Writer out, String format, boolean header, char delimiter)
            throws SQLException, IOException {
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        String[] names = new String[columns];
        for (int i = 0; i < columns; i++) {
            names[i] = md.getColumnLabel(i + 1);
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
                    out.write(jsonValue(rs, i + 1, md.getColumnType(i + 1)));
                }
                out.write("}\n");
            } else {
                for (int i = 0; i < columns; i++) {
                    if (i > 0) out.write(sep);
                    String value = textValue(rs, i + 1, md.getColumnType(i + 1));
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

    /**
     * A column value as text, or null for SQL NULL. Dates and times in ISO 8601,
     * numbers without exponent, binary data in Base64, CLOBs as their full text.
     */
    static String textValue(ResultSet rs, int col, int type) throws SQLException {
        switch (type) {
            case Types.DATE: {
                java.sql.Date d = rs.getDate(col);
                return d == null ? null : d.toLocalDate().toString();
            }
            case Types.TIME: {
                Time t = rs.getTime(col);
                return t == null ? null : t.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
            }
            case Types.TIMESTAMP: {
                Timestamp t = rs.getTimestamp(col);
                return t == null ? null : t.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            case Types.TIMESTAMP_WITH_TIMEZONE: {
                try {
                    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
                    return t == null ? null : t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (SQLException | AbstractMethodError e) {
                    return rs.getString(col);
                }
            }
            case Types.NUMERIC:
            case Types.DECIMAL: {
                BigDecimal n = rs.getBigDecimal(col);
                return n == null ? null : n.toPlainString();
            }
            case Types.BIT:
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

    private static String jsonValue(ResultSet rs, int col, int type) throws SQLException {
        String value = textValue(rs, col, type);
        if (value == null) {
            return "null";
        }
        switch (type) {
            case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
            case Types.NUMERIC: case Types.DECIMAL:
                return value;
            case Types.REAL: case Types.FLOAT: case Types.DOUBLE:
                // NaN and Infinity are not valid JSON numbers
                return value.matches("-?[0-9.]+([eE][-+]?[0-9]+)?") ? value : jsonString(value);
            case Types.BIT: case Types.BOOLEAN:
                return value;
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
                    logger.warn("Could not send " + fileName + ": connection lost while sending (" + writeError + ")");
                    return null;
                }
                String response = new DataInputStream(socket.getInputStream()).readUTF();
                if (!"SUCCESS".equals(response)) {
                    logger.error("Server rejected file " + fileName + ": " + response);
                }
                return response;
            }
        } catch (IOException e) {
            logger.warn("Could not send " + fileName + ": server not reachable or connection lost (" + e + ")");
            return null;
        } catch (GeneralSecurityException e) {
            logger.error("Error sending file " + fileName + ": " + e, e);
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
        logger.info("Database URL: " + jdbcUrl.replaceAll("[?;].*$", "") + ", User: " + (dbUser.isEmpty() ? "(none)" : dbUser));
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
        if (client.runOnce) {
            int code = client.runOnce();
            logger.info("JFiltraDbClient finished one-time run, exit code " + code);
            System.exit(code);
        }
        // Stop after the current cycle on Ctrl+C or systemctl stop, so a delivered file
        // is followed by its post-transfer SQL and commit
        final Thread mainThread = Thread.currentThread();
        Thread hook = new Thread(() -> {
            client.stop();
            try {
                mainThread.join(60000);
            } catch (InterruptedException ignored) {
                // Exit anyway
            }
            logger.info("JFiltraDbClient stopped");
        });
        Runtime.getRuntime().addShutdownHook(hook);
        int code = client.runLoop();
        if (code != EXIT_SENT) {
            Runtime.getRuntime().removeShutdownHook(hook);
            System.exit(code);
        }
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
