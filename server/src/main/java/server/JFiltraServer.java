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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * - Secure key logging (masking)
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
    
    // Limits that protect the server from stalled or oversized transfers
    private int socketTimeoutSeconds;  // Max wait for data from a client before giving up
    private long maxFileSizeBytes;     // Max size of a transferred file (sent and uncompressed)
    
    // Thread pool for handling multiple client connections
    private ExecutorService executor;
    
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
            config = new Properties();
            config.load(new FileInputStream(configPath));
            
            // Extract server port from configuration
            port = Integer.parseInt(config.getProperty("server.port"));
            
            // Read timeout for client connections (default 30 seconds)
            socketTimeoutSeconds = Integer.parseInt(config.getProperty("socket.timeout.seconds", "30").trim());
            
            // Maximum accepted file size (default 512 MB)
            maxFileSizeBytes = Long.parseLong(config.getProperty("max.file.size.mb", "512").trim()) * 1024 * 1024;
            
            // Load client encryption keys from separate file for better security
            clientKeys = new Properties();
            String clientKeysPath = config.getProperty("client.keys.path");
            if (clientKeysPath != null) {
                clientKeys.load(new FileInputStream(clientKeysPath));
            }
            
            // Load client storage path mappings from configuration file
            clientPaths = new Properties();
            String clientPathsConfig = config.getProperty("client.paths.config");
            if (clientPathsConfig != null) {
                clientPaths.load(new FileInputStream(clientPathsConfig));
            }
            
            // Log server startup and configuration details
            logger.info("=========================================");
            logger.info("=== Server jFiltra Started ==============");
            logger.info("=========================================");
            logger.info("Server configuration:");
            logger.info("-----------------------------------------");
            logger.info("Socket Timeout: " + socketTimeoutSeconds + " seconds");
            logger.info("Max File Size: " + (maxFileSizeBytes / (1024 * 1024)) + " MB");

            // Log storage paths for all configured clients
            for (String key : clientPaths.stringPropertyNames()) {
                String path = clientPaths.getProperty(key);
                logger.info("Client: " + key + ", Storage Path: " + path);
            }
            
            // Log masked encryption keys for security audit purposes
            for (String key : clientKeys.stringPropertyNames()) {
                String encryptionKey = clientKeys.getProperty(key);
                String maskedKey = maskEncryptionKey(encryptionKey);
                logger.info("Client: " + key + ", Encryption Key: " + maskedKey);
            }
            logger.info("-----------------------------------------");
           
        } catch (IOException e) {
            // Log error and exit if configuration cannot be loaded
            logger.error("Error loading configuration: " + e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Masks the encryption key for secure logging purposes.
     * Shows only first 4 and last 4 characters, with the middle replaced by "..."
     * 
     * @param key The encryption key to mask
     * @return A masked version of the key for secure logging
     */
    private String maskEncryptionKey(String key) {
        if (key == null || key.length() <= 8) {
            return "***masked***";
        }
        // Show only first 4 and last 4 characters for security
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /**
     * Starts the server and begins listening for client connections.
     * Uses a thread pool to handle multiple clients simultaneously.
     */
    public void start() {
        // Initialize server state
        running = true;
        
        // Create thread pool for handling multiple client connections
        executor = Executors.newFixedThreadPool(10);
        
        try {
            // Start server socket on configured port
            serverSocket = new ServerSocket(port);
            
            // Log the server's IP address for connection information
            logger.info("Server is running on IP: " + getLocalIpAddress() + " and port " + port);
            
            // Main server loop - accept connections and process in separate threads
            while (running) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
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
        
        try {
            // Log client connection with IP address for audit purposes
            logger.info("Client connected: " + clientSocket.getInetAddress().getHostAddress());
            
            // Don't let a stalled or idle connection hold a worker thread forever
            clientSocket.setSoTimeout(socketTimeoutSeconds * 1000);
            
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
                // Security error - file name contains path components (e.g. "../")
                logger.error("Rejected unsafe file name: " + originalFileName + " from client: " + clientLabel);
                dos.writeUTF("ERROR: Invalid file name");
                return;
            }

            // Never overwrite an existing file
            if (destinationFile.exists()) {
                logger.error("File already exists: " + destinationFile + " (client: " + clientLabel + ")");
                dos.writeUTF("ERROR: File already exists");
                return;
            }
            
            // Write to a hidden temporary file in the storage directory, then rename it into
            // place, so other programs never see a partially written file
            partFile = storageDir.toPath().resolve(".jfiltra-" + UUID.randomUUID() + ".part");
            Files.copy(uncompressedFile.toPath(), partFile);
            moveIntoPlace(partFile, destinationFile.toPath());
            
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
        byte[] keyBytes = digest.digest(encryptionKey.getBytes());
        
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
        
        // Read file contents
        byte[] fileBytes = Files.readAllBytes(filePath);
        
        // Calculate hash
        byte[] hashBytes = digest.digest(fileBytes);
        
        // Convert hash bytes to hexadecimal string
        StringBuilder hexString = new StringBuilder();
        for (byte hashByte : hashBytes) {
            String hex = Integer.toHexString(0xff & hashByte);
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