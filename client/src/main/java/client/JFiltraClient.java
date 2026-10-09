/**
* ## License
* This project is licensed under the PolyForm Noncommercial License 1.0.0.
* See the LICENSE file or https://polyformproject.org/licenses/noncommercial/1.0.0
*
* Required Notice: Copyright 2026 Vladimir Rumanko - BLUEGEM (https://github.com/bluegemsk/jFiltra)
*/

package client;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JFiltraClient - A client application that monitors directories for files,
 * processes them (compresses and encrypts), and sends them to a remote server.
 * The client runs on a scheduled interval and removes files after successful transfer.
 * Files are streamed in small chunks, so memory use doesn't depend on file size,
 * and no temporary files are written.
 */
public class JFiltraClient {
    // Initialize logger for application logging
    private static final Logger logger = LogManager.getLogger(JFiltraClient.class);
    
    // Configuration and operational fields
    private Properties config;                  // Stores configuration properties
    private List<String> sourceDirs;            // Directories to monitor for files
    private String serverIp;                    // Remote server IP address
    private int serverPort;                     // Remote server port
    private String clientLabel;                 // Unique identifier for this client
    private String encryptionKey;               // Key used for file encryption
    private ScheduledExecutorService scheduler; // Scheduler for periodic directory polling
    private int pollingIntervalSeconds;         // Time between directory scans
    private int socketTimeoutSeconds;           // Max wait for the server's response
    private int stablePolls;                    // Scans a file must stay unchanged before it is sent
    private String ignorePatterns;              // File name patterns that are never sent
    private List<PathMatcher> ignoreMatchers;   // Compiled ignore patterns
    
    // Max wait when connecting to the server
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    
    // Files that are never sent unless ignore.patterns says otherwise: hidden files (the
    // server rejects them), and common names for files still being written or locked
    private static final String DEFAULT_IGNORE_PATTERNS = ".*,*.part,*.tmp,*.crdownload,~$*";
    
    // Size, modification time and number of unchanged scans for each file seen in the
    // previous scan. Only accessed from the single scheduler thread.
    private Map<Path, FileState> previousStates = new HashMap<>();
    
    // Files the server rejected for a reason that retrying can't fix, with their size and
    // modification time at that moment. They are skipped until they change.
    // Only accessed from the single scheduler thread.
    private final Map<Path, String> rejectedFiles = new HashMap<>();
    
    // Server replies that mean the same file will always be rejected
    private static final Set<String> PERMANENT_REJECTIONS = new HashSet<>(Arrays.asList(
            "ERROR: File too large", "ERROR: Invalid file name"));
    
    // Shortest accepted encryption key; shorter keys could be guessed offline
    private static final int MIN_KEY_LENGTH = 32;
    
    /**
     * A file's size and modification time, and how many scans in a row it has been unchanged.
     */
    private static class FileState {
        final String snapshot;
        final int unchangedScans;
        
        FileState(String snapshot, int unchangedScans) {
            this.snapshot = snapshot;
            this.unchangedScans = unchangedScans;
        }
    }

    /**
     * Constructor - Initializes the client with configuration from the specified file
     * 
     * @param configPath Path to the configuration file
     */
    public JFiltraClient(String configPath) {
        try {
            // Load configuration
            config = loadProperties(configPath);
            
            // Parse configuration
            // Trim each entry and skip empty ones, so "dir1, dir2" works as expected
            sourceDirs = new ArrayList<>();
            for (String dir : requiredProperty("source.directories").split(",")) {
                if (!dir.trim().isEmpty()) {
                    sourceDirs.add(dir.trim());
                }
            }
            if (sourceDirs.isEmpty()) {
                throw new IllegalArgumentException("Setting source.directories contains no directories");
            }
            serverIp = requiredProperty("server.host");
            serverPort = intProperty("server.port", null, 1, 65535);
            clientLabel = requiredProperty("client.label");
            // Not trimmed: spaces may be part of the key, and it must match the server's entry exactly
            encryptionKey = config.getProperty("encryption.key");
            if (encryptionKey == null || encryptionKey.isEmpty()) {
                throw new IllegalArgumentException("Missing required setting: encryption.key");
            }
            checkKeyStrength(clientLabel, encryptionKey);
            
            // Get polling interval with default of 10 seconds if not specified
            pollingIntervalSeconds = intProperty("polling.interval.seconds", 10, 1, 86400);
            
            // Max wait for the server's response; the server needs time to decrypt,
            // decompress and store large files before it replies (default 120 seconds)
            socketTimeoutSeconds = intProperty("socket.timeout.seconds", 120, 1, 86400);
            
            // Scans a file must stay unchanged before it is sent (default 2)
            stablePolls = intProperty("stable.polls", 2, 1, 1000);
            
            // File name patterns that are never sent (an empty value sends everything)
            ignorePatterns = config.getProperty("ignore.patterns", DEFAULT_IGNORE_PATTERNS).trim();
            ignoreMatchers = new ArrayList<>();
            for (String pattern : ignorePatterns.split(",")) {
                if (!pattern.trim().isEmpty()) {
                    ignoreMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim()));
                }
            }

            // print environment info
            printEnvironmentInfo();
        } catch (IOException | IllegalArgumentException e) {
            // Log a clear message and exit if configuration is missing or invalid
            logger.error("Error loading configuration: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Loads a properties file as UTF-8, so keys and paths can contain any characters.
     * A UTF-8 byte order mark (added by some Windows editors) is ignored, and
     * escapes such as backslash-u013e keep working.
     * 
     * @param path Path to the properties file
     * @return The loaded properties
     * @throws IOException If the file cannot be read or is not valid UTF-8
     */
    private static Properties loadProperties(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
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
    
    /**
     * Checks that the encryption key is not the shipped placeholder and long enough
     * not to be guessed offline.
     * 
     * @param label The client label, for the error message
     * @param key The encryption key
     * @throws IllegalArgumentException If the key is a placeholder or too short
     */
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
    
    /**
     * Reads a required setting.
     * 
     * @param name The setting name
     * @return The trimmed value
     * @throws IllegalArgumentException If the setting is missing or empty
     */
    private String requiredProperty(String name) {
        String value = config.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required setting: " + name);
        }
        return value.trim();
    }
    
    /**
     * Reads a whole-number setting and checks its range.
     * 
     * @param name The setting name
     * @param defaultValue Value used when the setting is missing, or null if it is required
     * @param min Smallest allowed value
     * @param max Largest allowed value
     * @return The setting's value
     * @throws IllegalArgumentException If the setting is missing, not a number or out of range
     */
    private int intProperty(String name, Integer defaultValue, int min, int max) {
        String value = config.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Missing required setting: " + name);
            }
            return defaultValue;
        }
        
        String error = "Setting " + name + " must be a whole number from " + min + " to " + max
                + ", but is: " + value.trim();
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(error);
        }
        return parsed;
    }

    /**
     * Starts the file monitoring and transfer service
     * Initializes a scheduler to periodically check directories for files
     */
    public void start() {
       
        // Schedule file polling using the configured interval
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::pollDirectories, 0, pollingIntervalSeconds, TimeUnit.SECONDS);
       
    }

    /**
     * Scans all configured source directories for files to process
     * This method runs on the scheduled interval
     */
    private void pollDirectories() {
        // Never let an error escape: the scheduler would silently stop polling for good
        try {
            String timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logger.info(timeStamp + " - Scanning directories for files to send...");
            
            Map<Path, FileState> currentStates = new HashMap<>();
           
            for (String dirPath : sourceDirs) {
                try {
                    Path dir = Paths.get(dirPath);
                    if (!Files.exists(dir)) {
                        logger.warn("Directory does not exist: " + dirPath);
                       
                        continue;
                    }
                    
                    // Close the directory listing after use, otherwise every scan leaks a file handle.
                    // Symbolic links are skipped: following them would send (and then delete the
                    // link to) any file the client can read, such as files outside the source directory.
                    List<Path> files = new ArrayList<>();
                    try (Stream<Path> entries = Files.list(dir)) {
                        entries.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                               .filter(file -> !isIgnored(file))
                               .forEach(files::add);
                    }
                    
                    for (Path file : files) {
                        processIfStable(file, currentStates);
                    }
                } catch (Exception e) {
                    logger.error("Error polling directory " + dirPath + ": " + e.getMessage(), e);
                   
                }
            }
            
            previousStates = currentStates;
            
            // Forget rejected files that are gone
            rejectedFiles.keySet().retainAll(currentStates.keySet());
        } catch (Throwable t) {
            logger.error("Unexpected error while polling directories: " + t, t);
        }
    }

    /**
     * Checks whether a file matches one of the ignore patterns.
     * 
     * @param file The file to check
     * @return true if the file must not be sent
     */
    private boolean isIgnored(Path file) {
        Path fileName = file.getFileName();
        for (PathMatcher matcher : ignoreMatchers) {
            if (matcher.matches(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends a file only once its size and modification time have stayed unchanged for
     * stable.polls scans in a row, so files that are still being written or copied are
     * not sent.
     * 
     * @param file The file to check
     * @param currentStates File states collected during the current scan
     */
    private void processIfStable(Path file, Map<Path, FileState> currentStates) {
        try {
            String snapshot = fileSnapshot(file);
            FileState previous = previousStates.get(file);
            int unchangedScans = (previous != null && previous.snapshot.equals(snapshot))
                    ? previous.unchangedScans + 1 : 0;
            currentStates.put(file, new FileState(snapshot, unchangedScans));
            
            // Rejected before for a reason retrying can't fix, and unchanged since
            if (snapshot.equals(rejectedFiles.get(file))) {
                return;
            }
            
            if (unchangedScans >= stablePolls) {
                processFile(file, snapshot);
            } else {
                logger.debug("Waiting for file to stop changing: " + file.getFileName());
            }
        } catch (NoSuchFileException e) {
            // File was removed between listing and reading its attributes
        } catch (IOException e) {
            logger.error("Error reading file attributes " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the file's size and last modification time, used to detect changes.
     * 
     * @param file The file to inspect
     * @return A string combining size and modification time
     * @throws IOException If the attributes cannot be read
     */
    private String fileSnapshot(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.size() + ":" + attrs.lastModifiedTime().toMillis();
    }

    /**
     * Processes a single file: compresses, encrypts, and sends it to the server
     * Deletes the original file after successful transfer
     * 
     * @param filePath Path to the file to be processed
     * @param snapshot The file's size and modification time when it was found stable
     */
    private void processFile(Path filePath, String snapshot) {
        String fileName = filePath.getFileName().toString();
        logger.info("Processing file: " + fileName);
        
        try {
            // First pass: hash the file and measure its compressed, encrypted size, which
            // the server needs before the data. Nothing is stored, so no temp files are needed.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream sizeCounter = new CountingOutputStream(new DiscardingOutputStream());
            writeEncrypted(filePath, sizeCounter, digest);
            String fileHash = toHex(digest.digest());
            
            // Second pass: compress, encrypt and send the file straight to the server
            String response = sendFileToServer(filePath, fileHash, sizeCounter.count, fileName);
            
            // Don't resend a file the server will always reject; it is tried again once it changes
            if (PERMANENT_REJECTIONS.contains(response)) {
                rejectedFiles.put(filePath, snapshot);
                logger.error("File " + fileName + " will not be sent again until it changes");
            }
            
            if ("SUCCESS".equals(response)) {
                
                // File sent: test3.txt (Duration: 0.016 seconds)
                logger.info("File sent: " + fileName + " (Hash: " + fileHash + ")");
                logger.info("File sent: " + fileName + " (Size: " + filePath.toFile().length() + " bytes)");
                
                // Delete original file after successful transfer, unless it changed while
                // being sent - deleting it then would lose the new content
                if (snapshot.equals(fileSnapshot(filePath))) {
                    Files.delete(filePath);
                } else {
                    logger.warn("File changed while being sent and was not deleted: " + fileName);
                }
              
            }
        } catch (Exception | OutOfMemoryError e) {
            // OutOfMemoryError is caught too, so one file cannot stop the others from being processed
            logger.error("Error processing file " + fileName + ": " + e, e);
           
        }
    }

    /**
     * Compresses (GZIP) and encrypts (AES) a file in small chunks and writes the result
     * to the given stream. Memory use doesn't depend on the file size.
     * 
     * @param filePath The file to read
     * @param out Where to write the compressed, encrypted data; it is not closed
     * @param digest If not null, updated with the file's original content for hashing
     * @throws IOException If reading or writing fails
     * @throws GeneralSecurityException If the cipher cannot be set up
     */
    private void writeEncrypted(Path filePath, OutputStream out, MessageDigest digest)
            throws IOException, GeneralSecurityException {
        // Generate a 32-byte (256-bit) key using SHA-256
        MessageDigest keyDigest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = keyDigest.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        // NOFOLLOW_LINKS: if the file was replaced by a symbolic link after the scan, don't read the link's target
        InputStream fileIn = Files.newInputStream(filePath, LinkOption.NOFOLLOW_LINKS);
        try (InputStream in = (digest != null) ? new DigestInputStream(fileIn, digest) : fileIn;
             GZIPOutputStream gzos = new GZIPOutputStream(new CipherOutputStream(new NonClosingOutputStream(out), cipher), 8192)) {
            
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                gzos.write(buffer, 0, len);
            }
        }
    }

    /**
     * Converts bytes to a lowercase hexadecimal string.
     * 
     * @param bytes The bytes to convert
     * @return The hexadecimal string
     */
    private static String toHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Sends a file to the remote server over a socket connection
     * Includes metadata like client label, original filename, and file hash
     * 
     * @param filePath The file to send; it is compressed and encrypted while sending
     * @param fileHash The hash of the original file for integrity verification
     * @param encryptedSize Size of the compressed, encrypted data, measured in the first pass
     * @param originalFileName The name of the original file
     * @return The server's reply ("SUCCESS" or "ERROR: ..."), or null if no reply was received
     */
    private String sendFileToServer(Path filePath, String fileHash, long encryptedSize, String originalFileName) {
        long startTime = System.currentTimeMillis();
        
        try (Socket socket = new Socket()) {
            // Use timeouts so an unreachable or unresponsive server can't block the client forever
            socket.connect(new InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(socketTimeoutSeconds * 1000);
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
            
            // Send client label
            dos.writeUTF(clientLabel);
            
            // Send original file name
            dos.writeUTF(originalFileName);
            
            // Send file hash
            dos.writeUTF(fileHash);
            
            // Send file size
            dos.writeLong(encryptedSize);
            
            // Send file data, compressed and encrypted on the fly
            CountingOutputStream sentCounter = new CountingOutputStream(dos);
            writeEncrypted(filePath, sentCounter, null);
            dos.flush();
            
            // The file changed between the two passes, so the server can't accept it.
            // Dropping the connection makes the server discard it; it is retried on the next poll.
            if (sentCounter.count != encryptedSize) {
                logger.warn("File changed while being sent, will retry: " + originalFileName);
                return null;
            }
            
            // Check response
            try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
                String response = dis.readUTF();
                
                long endTime = System.currentTimeMillis();
                double duration = (endTime - startTime) / 1000.0;
                
                if ("SUCCESS".equals(response)) {
                    String message = "File sent: " + originalFileName + " (Duration: " + duration + " seconds)";
                    logger.info(message);
                    return response;
                }
                
                logger.error("Server rejected file " + originalFileName + ": " + response);
                return response;
            }
        } catch (IOException | GeneralSecurityException e) {
            logger.error("Error sending file " + originalFileName + " to server: " + e, e);
            return null;
        }
    }

    /**
     * Returns a short, non-reversible fingerprint of the encryption key for logging.
     * The same key gives the same fingerprint on server and client, so mismatched keys
     * can be spotted in the logs without revealing any part of the key.
     * 
     * @param key The encryption key
     * @return The first 8 hex characters of the key's SHA-256 hash
     */
    private String keyFingerprint(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    /**
     * Logs detailed information about the client environment and configuration
     * Useful for debugging and audit purposes
     */
    private void printEnvironmentInfo() {
        logger.info("=========================================");
        logger.info("=== Client jFiltra Started ==============");
        logger.info("=========================================");
        logger.info("Client configuration:");
        logger.info("-----------------------------------------");
        logger.info("Client Label: " + clientLabel);
        logger.info("Server IP: " + serverIp);
        logger.info("Server Port: " + serverPort);
        logger.info("Encryption Key Fingerprint: " + keyFingerprint(encryptionKey));
        logger.info("Source Directories: " + String.join(", ", sourceDirs));
        logger.info("Polling Interval: " + pollingIntervalSeconds + " seconds");
        logger.info("Socket Timeout: " + socketTimeoutSeconds + " seconds");
        logger.info("Stable Polls: " + stablePolls);
        logger.info("Ignore Patterns: " + (ignorePatterns.isEmpty() ? "(none)" : ignorePatterns));
        logger.info("-----------------------------------------");
        try {
            
            // Get and log the server's IP address for connection information
            String clientIP = java.net.InetAddress.getLocalHost().getHostAddress();
            logger.info("Client is running on IP: " + clientIP);

        } catch (IOException e) {
            logger.error("Error starting client: " + e.getMessage(), e);
            }
        
    }

    /**
     * Stops the file monitoring service
     * Shuts down the scheduler cleanly
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        logger.info("JFiltraClient stopped");
       
    }

    /**
     * Main entry point for the application
     * Initializes and starts the client with the provided configuration file
     * 
     * @param args Command line arguments (expects config file path)
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Error: Missing configuration file path!");
            System.out.println("Usage: java JFiltraClient <config-file-path>");
            System.exit(1);
        }
        
        String configPath = args[0];
        JFiltraClient client = new JFiltraClient(configPath);

        client.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
    }

    /**
     * Counts the bytes written through it.
     */
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

    /**
     * Passes writes through, but doesn't close the underlying stream when closed,
     * so the socket stays open for the server's reply.
     */
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

    /**
     * Discards everything written to it (Java 8 has no built-in equivalent).
     */
    private static class DiscardingOutputStream extends OutputStream {
        @Override
        public void write(int b) {
        }
        
        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
