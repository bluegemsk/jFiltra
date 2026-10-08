/**
* ## License
* This project is licensed under the PolyForm Noncommercial License 1.0.0.
* See the LICENSE file or https://polyformproject.org/licenses/noncommercial/1.0.0
*
* Required Notice: Copyright 2026 Vladimir Rumanko - BLUEGEM (https://github.com/bluegemsk/jFiltra)
*
* ## Support This Project  
* If this code helps you, consider sending a small crypto donation:  
* - **SOL**: `DL5sEEG6z666vyety2FdDZtTF1pMtMAnjKXSdZTYg34K` 
* - **BNB**: `0xC08f5CC86610e400bb3c12Fe8a085514F7e786E0` 
*/

package server;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JFiltraServer class
 * 
 * This server receives encrypted and compressed files from clients, processes them
 * (decrypts, uncompresses), verifies file integrity via hash validation, and stores
 * them in designated client-specific storage locations.
 * 
 * Security features:
 * - Client-specific encryption keys
 * - File hash verification
 * - Secure key logging (fingerprint only)
 */
public class JFiltraServer {
    // Logger for application logging
    private static final Logger logger = LogManager.getLogger(JFiltraServer.class);
    
    // Server configuration properties
    private Properties config;
    
    // Server network configuration
    private int port;
    private ServerSocket serverSocket;
    private boolean running;
    
    // Limits that protect the server from stalled, slow or oversized transfers
    private int socketTimeoutSeconds;    // Max wait for data from a client before giving up
    private int headerTimeoutSeconds;    // Max time for a client to send the transfer header
    private int transferTimeoutSeconds;  // Max total time for one transfer, including processing
    private int maxConnections;          // Max transfers handled at the same time
    private int maxConnectionsPerIp;     // Max simultaneous connections from one IP address
    private long maxFileSizeBytes;       // Max size of a transferred file (sent and uncompressed)
    
    // Thread pool for handling multiple client connections
    private ExecutorService executor;
    
    // Closes connections that exceed their time limit (daemon thread, never blocks shutdown)
    private ScheduledExecutorService watchdog;
    
    // Number of open connections per client IP address
    private final Map<InetAddress, Integer> connectionsPerIp = new HashMap<>();
    
    // Guards the fallback publish path on file systems without hard links
    private final Object publishLock = new Object();
    
    // Windows reserved device names; rejected so stored files stay usable on Windows
    private static final Pattern WINDOWS_RESERVED_NAME =
            Pattern.compile("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?", Pattern.CASE_INSENSITIVE);
    
    // Longest accepted file name in bytes (common file system limit)
    private static final int MAX_FILE_NAME_BYTES = 255;
    
    // Client-specific configuration
    private Properties clientKeys;    // Maps client IDs to their encryption keys
    private Properties clientPaths;   // Maps client IDs to their storage directories

    /**
     * Constructor - Initializes the server with configuration from the specified path.
     * 
     * @param configPath Path to the server configuration file
     */
    public JFiltraServer(String configPath) {
        try {
            // Load main configuration file
            config = loadProperties(configPath);
            
            // Server port (required)
            port = intProperty(config, "server.port", null, 1, 65535);
            
            // Read timeout for client connections (default 30 seconds)
            socketTimeoutSeconds = intProperty(config, "socket.timeout.seconds", 30, 1, 86400);
            
            // Time allowed for sending the transfer header (default 5 seconds)
            headerTimeoutSeconds = intProperty(config, "header.timeout.seconds", 5, 1, 3600);
            
            // Total time allowed for one transfer, including processing (default 600 seconds)
            transferTimeoutSeconds = intProperty(config, "transfer.timeout.seconds", 600, 1, 86400);
            
            // Simultaneous transfers in total (default 10) and per client IP address (default 4)
            maxConnections = intProperty(config, "max.connections", 10, 1, 1000);
            maxConnectionsPerIp = intProperty(config, "max.connections.per.ip", 4, 1, 1000);
            
            // Maximum accepted file size (default 512 MB). Files are processed in memory,
            // so the size can't exceed 2047 MB.
            maxFileSizeBytes = intProperty(config, "max.file.size.mb", 512, 1, 2047) * 1024L * 1024L;
            
            // Load client encryption keys from separate file for better security
            String clientKeysPath = config.getProperty("client.keys.path");
            clientKeys = (clientKeysPath != null) ? loadProperties(clientKeysPath.trim()) : new Properties();
            
            // Load client storage path mappings from configuration file
            String clientPathsConfig = config.getProperty("client.paths.config");
            clientPaths = (clientPathsConfig != null) ? loadProperties(clientPathsConfig.trim()) : new Properties();
            
            // Log server startup and configuration details
            logger.info("=========================================");
            logger.info("=== Server jFiltra Started ==============");
            logger.info("=========================================");
            logger.info("Server configuration:");
            logger.info("-----------------------------------------");
            logger.info("Socket Timeout: " + socketTimeoutSeconds + " seconds");
            logger.info("Header Timeout: " + headerTimeoutSeconds + " seconds");
            logger.info("Transfer Timeout: " + transferTimeoutSeconds + " seconds");
            logger.info("Max Connections: " + maxConnections + " (per IP: " + maxConnectionsPerIp + ")");
            logger.info("Max File Size: " + (maxFileSizeBytes / (1024 * 1024)) + " MB");

            // Log storage paths for all configured clients
            for (String key : clientPaths.stringPropertyNames()) {
                String path = clientPaths.getProperty(key);
                logger.info("Client: " + key + ", Storage Path: " + path);
            }
            
            // Log key fingerprints (never the keys) so they can be compared with the clients' logs
            for (String key : clientKeys.stringPropertyNames()) {
                String encryptionKey = clientKeys.getProperty(key);
                logger.info("Client: " + key + ", Encryption Key Fingerprint: " + keyFingerprint(encryptionKey));
            }
            logger.info("-----------------------------------------");
           
        } catch (IOException | IllegalArgumentException e) {
            // Log a clear message and exit if configuration is missing or invalid
            logger.error("Error loading configuration: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * Loads a properties file, closing it afterwards.
     * 
     * @param path Path to the properties file
     * @return The loaded properties
     * @throws IOException If the file cannot be read
     */
    private static Properties loadProperties(String path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            props.load(in);
        }
        return props;
    }
    
    /**
     * Reads a whole-number setting and checks its range.
     * 
     * @param props The properties to read from
     * @param name The setting name
     * @param defaultValue Value used when the setting is missing, or null if it is required
     * @param min Smallest allowed value
     * @param max Largest allowed value
     * @return The setting's value
     * @throws IllegalArgumentException If the setting is missing, not a number or out of range
     */
    private static int intProperty(Properties props, String name, Integer defaultValue, int min, int max) {
        String value = props.getProperty(name);
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
     * Returns a short, non-reversible fingerprint of an encryption key for logging.
     * The same key gives the same fingerprint on server and client, so mismatched keys
     * can be spotted in the logs without revealing any part of the key.
     * 
     * @param key The encryption key
     * @return The first 8 hex characters of the key's SHA-256 hash
     */
    private static String keyFingerprint(String key) {
        if (key == null) {
            return "(none)";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception e) {
            return "(unavailable)";
        }
    }

    /**
     * Starts the server and begins listening for client connections.
     * Uses a thread pool to handle multiple clients simultaneously.
     */
    public void start() {
        // Initialize server state
        running = true;
        
        // Create thread pool for handling multiple client connections
        executor = Executors.newFixedThreadPool(maxConnections);
        
        // Create the watchdog that enforces per-connection time limits
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jfiltra-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        
        // Remove temporary files left behind if the server was stopped in the middle of a transfer
        cleanUpPartFiles();
        
        try {
            // Start server socket on configured port
            serverSocket = new ServerSocket(port);
            
            // Log the server's IP address for connection information
            logger.info("Server is running on IP: " + getLocalIpAddress() + " and port " + port);
            
            // Main server loop - accept connections and process in separate threads
            while (running) {
                Socket clientSocket = serverSocket.accept();
                InetAddress clientAddress = clientSocket.getInetAddress();
                
                // Limit connections per IP address, so a single host can't occupy every worker
                if (!acquireConnectionSlot(clientAddress)) {
                    logger.warn("Too many connections from " + clientAddress.getHostAddress() + ", connection refused");
                    closeQuietly(clientSocket);
                    continue;
                }
                
                executor.submit(() -> {
                    try {
                        handleClient(clientSocket);
                    } finally {
                        releaseConnectionSlot(clientAddress);
                    }
                });
            }
        } catch (IOException e) {
            // Only log as error if the exception wasn't caused by manual server shutdown
            if (running) {
                logger.error("Error starting server: " + e.getMessage(), e);
            }
        } finally {
            // Let worker threads finish and exit, so the JVM doesn't stay alive without
            // accepting connections if the server loop ends unexpectedly
            executor.shutdown();
        }
    }

    /**
     * Reserves a connection slot for a client IP address.
     * 
     * @param address The client's IP address
     * @return true if the connection may proceed, false if the address has too many connections
     */
    private synchronized boolean acquireConnectionSlot(InetAddress address) {
        int open = connectionsPerIp.getOrDefault(address, 0);
        if (open >= maxConnectionsPerIp) {
            return false;
        }
        connectionsPerIp.put(address, open + 1);
        return true;
    }
    
    /**
     * Releases a connection slot reserved with acquireConnectionSlot.
     * 
     * @param address The client's IP address
     */
    private synchronized void releaseConnectionSlot(InetAddress address) {
        int open = connectionsPerIp.getOrDefault(address, 1) - 1;
        if (open <= 0) {
            connectionsPerIp.remove(address);
        } else {
            connectionsPerIp.put(address, open);
        }
    }
    
    /**
     * Schedules a connection to be closed when its time limit runs out. Closing the
     * socket interrupts any blocked read, so a client that sends data very slowly
     * can't hold a worker thread longer than the limit.
     * 
     * @param socket The client connection
     * @param seconds The time limit
     * @param limitName Name of the limit, for the log message
     * @return The scheduled task; cancel it when the connection finishes in time
     */
    private ScheduledFuture<?> scheduleClose(Socket socket, int seconds, String limitName) {
        return watchdog.schedule(() -> {
            if (!socket.isClosed()) {
                logger.warn("Closing connection from " + socket.getInetAddress().getHostAddress()
                        + ": " + limitName + " of " + seconds + " seconds exceeded");
                closeQuietly(socket);
            }
        }, seconds, TimeUnit.SECONDS);
    }
    
    /**
     * Closes a socket, ignoring errors.
     * 
     * @param socket The socket to close
     */
    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing more to do
        }
    }
    
    /**
     * Deletes leftover ".jfiltra-*.part" files from all storage directories. They remain
     * when the server is stopped while writing a file, and are never completed later.
     */
    private void cleanUpPartFiles() {
        Set<String> storageDirs = new LinkedHashSet<>();
        for (String key : clientPaths.stringPropertyNames()) {
            storageDirs.add(clientPaths.getProperty(key).trim());
        }
        storageDirs.add("incoming");
        
        for (String dirPath : storageDirs) {
            Path dir = Paths.get(dirPath);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> partFiles = Files.newDirectoryStream(dir, ".jfiltra-*.part")) {
                for (Path partFile : partFiles) {
                    Files.deleteIfExists(partFile);
                    logger.info("Deleted leftover temporary file: " + partFile);
                }
            } catch (IOException e) {
                logger.warn("Could not clean up temporary files in " + dir + ": " + e.getMessage());
            }
        }
    }

    /**
     * Returns the local IP address for logging. A failed lookup (e.g. a hostname that
     * does not resolve) must not stop the server, so it falls back to "unknown".
     * 
     * @return The local IP address, or "unknown" if it cannot be determined
     */
    private String getLocalIpAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            logger.warn("Could not determine local IP address: " + e.getMessage());
            return "unknown";
        }
    }

    /**
     * Handles an individual client connection.
     * Processes file transfers including:
     * - Reading metadata (client ID, filename, file hash, file size)
     * - Receiving encrypted data
     * - Decrypting and uncompressing data
     * - Verifying file integrity via hash
     * - Storing the file in client-specific directory
     * 
     * @param clientSocket The socket for the client connection
     */
    private void handleClient(Socket clientSocket) {
        // Temporary files for this transfer; always deleted in the finally block
        File encryptedFile = null;
        File decryptedFile = null;
        File uncompressedFile = null;
        Path partFile = null;
        DataOutputStream dos = null;
        ScheduledFuture<?> deadline = null;
        
        try {
            // Log client connection with IP address for audit purposes
            logger.info("Client connected: " + clientSocket.getInetAddress().getHostAddress());
            
            // Don't let a stalled or idle connection hold a worker thread forever
            clientSocket.setSoTimeout(socketTimeoutSeconds * 1000);
            
            // The header must arrive quickly; this also stops clients that send it byte by byte
            deadline = scheduleClose(clientSocket, headerTimeoutSeconds, "header time limit");
            
            // Initialize data streams for communication with client
            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
            dos = new DataOutputStream(clientSocket.getOutputStream());
            
            // Read client identification label (used to determine encryption key and storage path)
            String clientLabel = dis.readUTF();
            logger.info("Client label: " + clientLabel);
            
            // Read original file name (will be preserved when storing)
            String originalFileName = dis.readUTF();
           
            // Read file hash (for integrity verification)
            String fileHash = dis.readUTF();
            
            // Read file size (for progress tracking and verification)
            long fileSize = dis.readLong();
            
            logger.info("Receiving file: " + originalFileName + " from client: " + clientLabel);
            
            // Header received - from now on, limit the total time of the transfer
            deadline.cancel(false);
            deadline = scheduleClose(clientSocket, transferTimeoutSeconds, "transfer time limit");
            
            // Start timing the file processing for performance logging
            long startTime = System.currentTimeMillis();
            
            // Reject invalid or oversized transfers before receiving any data
            if (fileSize < 0 || fileSize > maxFileSizeBytes) {
                logger.error("Rejected file " + originalFileName + " from client " + clientLabel
                        + ": size " + fileSize + " bytes exceeds limit of " + maxFileSizeBytes + " bytes");
                dos.writeUTF("ERROR: File too large");
                return;
            }
            
            // Look up encryption key for this client before writing anything to disk
            String encryptionKey = clientKeys.getProperty(clientLabel);
            if (encryptionKey == null) {
                // Security error - client not authorized or missing key
                logger.error("No encryption key found for client: " + clientLabel);
                // Discard the upload without storing it, so the client can read the response
                receiveBytes(dis, null, fileSize);
                dos.writeUTF("ERROR: No encryption key found");
                return;
            }
            
            // Create temporary file to store the encrypted data
            encryptedFile = File.createTempFile("encrypted_", ".enc");
            
            // Read encrypted file data from client
            long missingBytes;
            try (FileOutputStream fos = new FileOutputStream(encryptedFile)) {
                missingBytes = receiveBytes(dis, fos, fileSize);
            }
            if (missingBytes > 0) {
                // Client disconnected before sending the whole file
                logger.error("Incomplete transfer of file " + originalFileName + " from client " + clientLabel
                        + ": " + missingBytes + " bytes missing");
                return;
            }
            
            // Decrypt the file using client-specific encryption key
            decryptedFile = decryptFile(encryptedFile, encryptionKey);
            
            // Uncompress the file (clients send GZIP compressed data)
            uncompressedFile = uncompressFile(decryptedFile);
            
            // Calculate hash of processed file for integrity verification
            String calculatedHash = calculateFileHash(uncompressedFile.toPath());
            
            // Verify file integrity by comparing hashes
            if (!calculatedHash.equals(fileHash)) {
                // Data integrity error - file corrupted during transfer
                logger.error("Hash verification failed for file: " + originalFileName);
                dos.writeUTF("ERROR: Hash verification failed");
                return;
            }
            
            // Determine storage location for this client
            String clientStoragePath = clientPaths.getProperty(clientLabel);
            if (clientStoragePath == null) {
                clientStoragePath = "incoming"; // Default storage directory
            }
            
            // Ensure storage directory exists
            File storageDir = new File(clientStoragePath);
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }
            
            // Resolve destination inside the storage directory, rejecting names that could escape it
            File destinationFile = resolveSafeDestination(storageDir, originalFileName);
            if (destinationFile == null) {
                // File name contains path components (e.g. "../") or is not allowed
                logger.error("Rejected invalid file name: " + originalFileName + " from client: " + clientLabel);
                dos.writeUTF("ERROR: Invalid file name");
                return;
            }

            // Never overwrite an existing file
            if (destinationFile.exists()) {
                respondToExistingFile(dos, destinationFile, fileHash, originalFileName, clientLabel);
                return;
            }
            
            // Write to a hidden temporary file in the storage directory first, so other
            // programs never see a partially written file
            partFile = storageDir.toPath().resolve(".jfiltra-" + UUID.randomUUID() + ".part");
            Files.copy(uncompressedFile.toPath(), partFile);
            
            // Publish under the real name; fails if another transfer stored the name meanwhile
            if (!publishFile(partFile, destinationFile.toPath())) {
                respondToExistingFile(dos, destinationFile, fileHash, originalFileName, clientLabel);
                return;
            }
            
            // Calculate processing duration for performance logging
            long endTime = System.currentTimeMillis();
            double duration = (endTime - startTime) / 1000.0;

            long fileSizeReceived = uncompressedFile.length();

            // Log successful file transfer with performance metrics
            String logMessage = String.format(
                "File transfer completed: %s, Client: %s, Size: %d bytes, Hash: %s, Duration: %.2f seconds",
                originalFileName, clientLabel, fileSizeReceived, fileHash, duration
            );
            logger.info(logMessage);
            
            // Notify client of successful transfer
            dos.writeUTF("SUCCESS");
            
        } catch (Exception e) {
            // Log any errors during client handling
            logger.error("Error handling client: " + e.getMessage(), e);
            
            // Tell the client the transfer failed, if the connection is still usable.
            // Details stay in the server log, since the client is not authenticated.
            if (dos != null) {
                try {
                    dos.writeUTF("ERROR: Transfer failed");
                } catch (IOException ignored) {
                    // Connection already broken - nothing more to report
                }
            }
        } finally {
            // The connection is finished - stop its time limit
            if (deadline != null) {
                deadline.cancel(false);
            }
            
            // Clean up temporary files on success and on every error path
            deleteTempFile(encryptedFile);
            deleteTempFile(decryptedFile);
            deleteTempFile(uncompressedFile);
            if (partFile != null) {
                deleteTempFile(partFile.toFile());
            }
            
            // Ensure client socket is closed even if an exception occurs
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.error("Error closing client socket: " + e.getMessage());
            }
        }
    }

    /**
     * Reads the given number of bytes from the client.
     * 
     * @param dis The stream to read from
     * @param out Where to write the bytes, or null to discard them
     * @param byteCount Number of bytes to read
     * @return Number of bytes not received because the client closed the connection early
     * @throws IOException If reading fails or times out
     */
    private long receiveBytes(DataInputStream dis, OutputStream out, long byteCount) throws IOException {
        byte[] buffer = new byte[4096];
        long remaining = byteCount;
        int bytesRead;
        
        // Read data in chunks until all bytes received
        while (remaining > 0 && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
            if (out != null) {
                out.write(buffer, 0, bytesRead);
            }
            remaining -= bytesRead;
        }
        return remaining;
    }

    /**
     * Answers a client whose file name is already taken in the storage directory.
     * If the stored file has the same content (e.g. the client missed the server's
     * earlier reply and sent the file again), the transfer counts as successful, so
     * the client can delete its copy instead of retrying forever.
     * 
     * @param dos The stream to answer the client on
     * @param existingFile The file already stored under this name
     * @param fileHash The SHA-256 hash of the file the client sent
     * @param fileName The file name, for logging
     * @param clientLabel The client's label, for logging
     * @throws Exception If the existing file cannot be read or the answer cannot be sent
     */
    private void respondToExistingFile(DataOutputStream dos, File existingFile, String fileHash,
                                       String fileName, String clientLabel) throws Exception {
        if (existingFile.isFile() && calculateFileHash(existingFile.toPath()).equals(fileHash)) {
            logger.info("File already stored with identical content: " + fileName + " (client: " + clientLabel + ")");
            dos.writeUTF("SUCCESS");
        } else {
            logger.error("File already exists with different content: " + existingFile + " (client: " + clientLabel + ")");
            dos.writeUTF("ERROR: File already exists");
        }
    }

    /**
     * Publishes a fully written temporary file under its final name, never overwriting
     * an existing file. Creating a hard link is atomic and fails if the name is taken,
     * so two transfers of the same name at the same time can't overwrite each other.
     * 
     * @param partFile The fully written temporary file in the storage directory
     * @param target The final file name
     * @return true if the file was published, false if the name is already taken
     * @throws IOException If the file cannot be published
     */
    private boolean publishFile(Path partFile, Path target) throws IOException {
        try {
            Files.createLink(target, partFile);
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (UnsupportedOperationException | IOException e) {
            // File system without hard links (e.g. FAT or some network shares): rename instead,
            // guarded by a lock so this server's own transfers still can't overwrite each other
            logger.debug("Hard link not supported in " + target.getParent() + ", using rename: " + e);
            synchronized (publishLock) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                moveIntoPlace(partFile, target);
            }
            return true;
        }
        
        // Published via the hard link - remove the temporary name
        Files.delete(partFile);
        return true;
    }

    /**
     * Renames a fully written file to its final name. Both are in the same directory,
     * so the rename is atomic on normal file systems.
     * 
     * @param source The fully written temporary file
     * @param target The final destination
     * @throws IOException If the file cannot be moved
     */
    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // File system without atomic rename - fall back to a regular move
            Files.move(source, target);
        }
    }

    /**
     * Deletes a temporary file if it exists, logging a warning if it cannot be removed.
     * 
     * @param file The temporary file to delete (may be null)
     */
    private void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logger.warn("Could not delete temporary file: " + file.getAbsolutePath());
        }
    }

    /**
     * Resolves the destination file for a client-supplied file name, ensuring it
     * stays directly inside the storage directory (prevents path traversal).
     *
     * @param storageDir The client's storage directory
     * @param fileName The file name sent by the client
     * @return The destination file, or null if the file name is unsafe
     */
    private File resolveSafeDestination(File storageDir, String fileName) {
        // Accept only a plain file name - no separators, relative segments or NUL bytes
        if (fileName == null || fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.equals(".") || fileName.equals("..")
                || fileName.indexOf('\0') >= 0) {
            return null;
        }
        
        // No hidden files: this also reserves the ".jfiltra-" prefix for the server's temporary files
        if (fileName.startsWith(".")) {
            return null;
        }
        
        // No control characters (they could also forge extra lines in the logs)
        for (int i = 0; i < fileName.length(); i++) {
            if (Character.isISOControl(fileName.charAt(i))) {
                return null;
            }
        }
        
        // Keep within the usual file system limit for name length
        if (fileName.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_NAME_BYTES) {
            return null;
        }
        
        // Names Windows can't handle (CON, NUL, COM1..., or ending in a dot or space), so
        // stored files can be copied to Windows systems later
        if (WINDOWS_RESERVED_NAME.matcher(fileName).matches()
                || fileName.endsWith(".") || fileName.endsWith(" ")) {
            return null;
        }

        // Double-check the resolved path is directly inside the storage directory
        Path baseDir = storageDir.toPath().toAbsolutePath().normalize();
        Path target = baseDir.resolve(fileName).normalize();
        if (!baseDir.equals(target.getParent())) {
            return null;
        }
        return target.toFile();
    }

    /**
     * Decrypts a file using AES encryption with the provided key.
     * 
     * @param encryptedFile The file containing encrypted data
     * @param encryptionKey The key to use for decryption
     * @return A temporary file containing the decrypted data
     * @throws Exception If decryption fails
     */
    private File decryptFile(File encryptedFile, String encryptionKey) throws Exception {
        // Create temporary file for decrypted data
        File decryptedFile = File.createTempFile("decrypted_", ".tmp");
        
        // Generate a 32-byte (256-bit) key using SHA-256 hash of the provided key
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        
        // Initialize AES cipher for decryption
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        
        // Perform decryption operation
        try (FileInputStream fis = new FileInputStream(encryptedFile);
             FileOutputStream fos = new FileOutputStream(decryptedFile)) {
            
            // Read all encrypted bytes
            byte[] inputBytes = new byte[(int) encryptedFile.length()];
            fis.read(inputBytes);
            
            // Decrypt and write to output file
            byte[] outputBytes = cipher.doFinal(inputBytes);
            fos.write(outputBytes);
        } catch (Exception e) {
            // Don't leave the temporary file behind if decryption fails (e.g. wrong key)
            deleteTempFile(decryptedFile);
            throw e;
        }
        
        logger.info("File decrypted successfully");
        return decryptedFile;
    }

    /**
     * Uncompresses a GZIP compressed file.
     * 
     * @param compressedFile The file containing GZIP compressed data
     * @return A temporary file containing the uncompressed data
     * @throws IOException If decompression fails
     */
    private File uncompressFile(File compressedFile) throws IOException {
        // Create temporary file for uncompressed data
        File uncompressedFile = File.createTempFile("uncompressed_", ".bin");
        
        // Set up GZIP input stream for decompression
        try (FileInputStream fis = new FileInputStream(compressedFile);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(uncompressedFile)) {
            
            // Decompress data in chunks, stopping if the output exceeds the size limit
            byte[] buffer = new byte[1024];
            int len;
            long totalBytes = 0;
            while ((len = gzis.read(buffer)) != -1) {
                totalBytes += len;
                if (totalBytes > maxFileSizeBytes) {
                    throw new IOException("Uncompressed size exceeds limit of " + maxFileSizeBytes + " bytes");
                }
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            // Don't leave the temporary file behind if the data is not valid GZIP
            deleteTempFile(uncompressedFile);
            throw e;
        }
        
        logger.info("File uncompressed successfully");
        return uncompressedFile;
    }

    /**
     * Calculates SHA-256 hash of a file for integrity verification.
     * 
     * @param filePath Path to the file to hash
     * @return Hex string representation of the SHA-256 hash
     * @throws Exception If hash calculation fails
     */
    private String calculateFileHash(Path filePath) throws Exception {
        // Create SHA-256 digest for hash calculation
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        // Read the file in chunks, so even large files don't need to fit in memory
        try (InputStream in = new FileInputStream(filePath.toFile())) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
        }
        
        return toHex(digest.digest());
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
     * Stops the server gracefully, closing connections and resources.
     */
    public void stop() {
        // Set running flag to false to stop the main server loop
        running = false;
        
        // Close server socket to stop accepting new connections
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error("Error closing server socket: " + e.getMessage());
            }
        }
        
        // Shutdown thread pool gracefully
        if (executor != null) {
            executor.shutdown();
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        
        logger.info("JFiltraServer stopped");
    }

    /**
     * Main method to start the server from command line.
     * 
     * @param args Command-line arguments, expects config file path
     */
    public static void main(String[] args) {
        // Validate command-line arguments
        if (args.length < 1) {
            System.out.println("Error: Missing configuration directory path!");
            System.out.println("Usage: java JFiltraServer <config-directory-path>");
            System.exit(1);
        }

        // Initialize server with config file
        String configPath = args[0];
        JFiltraServer server = new JFiltraServer(configPath);

        // Add shutdown hook to gracefully stop server when JVM exits
        // (registered before start(), which blocks until the server stops)
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        // Start the server
        server.start();
    }
} 