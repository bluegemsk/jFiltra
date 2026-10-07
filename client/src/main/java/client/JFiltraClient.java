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

package client;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JFiltraClient - A client application that monitors directories for files,
 * processes them (compresses and encrypts), and sends them to a remote server.
 * The client runs on a scheduled interval and removes files after successful transfer.
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
    
    // Max wait when connecting to the server
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    
    // Size and modification time of each file seen in the previous scan. A file is only
    // sent once it is unchanged between two scans, so files still being written are skipped.
    // Only accessed from the single scheduler thread.
    private Map<Path, String> previousSnapshots = new HashMap<>();

    /**
     * Constructor - Initializes the client with configuration from the specified file
     * 
     * @param configPath Path to the configuration file
     */
    public JFiltraClient(String configPath) {
        try {
            // Load configuration
            config = new Properties();
            config.load(new FileInputStream(configPath));
            
            // Parse configuration
            // Trim each entry and skip empty ones, so "dir1, dir2" works as expected
            sourceDirs = new ArrayList<>();
            for (String dir : config.getProperty("source.directories").split(",")) {
                if (!dir.trim().isEmpty()) {
                    sourceDirs.add(dir.trim());
                }
            }
            serverIp = config.getProperty("server.host");
            serverPort = Integer.parseInt(config.getProperty("server.port"));
            clientLabel = config.getProperty("client.label");
            encryptionKey = config.getProperty("encryption.key");
            
            // Get polling interval with default of 10 seconds if not specified
            String pollingIntervalStr = config.getProperty("polling.interval.seconds");
            pollingIntervalSeconds = (pollingIntervalStr != null) ? 
                                     Integer.parseInt(pollingIntervalStr) : 10;
            
            // Max wait for the server's response; the server needs time to decrypt,
            // decompress and store large files before it replies (default 120 seconds)
            socketTimeoutSeconds = Integer.parseInt(config.getProperty("socket.timeout.seconds", "120").trim());

            // print environment info
            printEnvironmentInfo();
        } catch (IOException e) {
            logger.error("Error loading configuration: " + e.getMessage(), e);
            System.exit(1);
        }
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
            
            Map<Path, String> currentSnapshots = new HashMap<>();
           
            for (String dirPath : sourceDirs) {
                try {
                    Path dir = Paths.get(dirPath);
                    if (!Files.exists(dir)) {
                        logger.warn("Directory does not exist: " + dirPath);
                       
                        continue;
                    }
                    
                    // Close the directory listing after use, otherwise every scan leaks a file handle
                    List<Path> files = new ArrayList<>();
                    try (Stream<Path> entries = Files.list(dir)) {
                        entries.filter(Files::isRegularFile).forEach(files::add);
                    }
                    
                    for (Path file : files) {
                        processIfStable(file, currentSnapshots);
                    }
                } catch (Exception e) {
                    logger.error("Error polling directory " + dirPath + ": " + e.getMessage(), e);
                   
                }
            }
            
            previousSnapshots = currentSnapshots;
        } catch (Throwable t) {
            logger.error("Unexpected error while polling directories: " + t, t);
        }
    }

    /**
     * Sends a file only if its size and modification time are unchanged since the
     * previous scan, so files that are still being written or copied are not sent.
     * 
     * @param file The file to check
     * @param currentSnapshots Snapshots collected during the current scan
     */
    private void processIfStable(Path file, Map<Path, String> currentSnapshots) {
        try {
            String snapshot = fileSnapshot(file);
            currentSnapshots.put(file, snapshot);
            
            if (snapshot.equals(previousSnapshots.get(file))) {
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
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
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
       
        File compressedFile = null;
        File encryptedFile = null;
        
        try {
            // Calculate file hash
            String fileHash = calculateFileHash(filePath);
            
            // Compress the file
            compressedFile = compressFile(filePath.toFile());
            
            // Encrypt the file
            encryptedFile = encryptFile(compressedFile);
            
            // Send the file to server
            boolean sent = sendFileToServer(encryptedFile, fileHash, fileName);
            
            if (sent) {
                
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
            // OutOfMemoryError is caught too (e.g. a file too large for the heap), so one
            // file cannot stop the others from being processed
            logger.error("Error processing file " + fileName + ": " + e, e);
           
        } finally {
            // Clean up temporary files on success and on every error path
            if (compressedFile != null) compressedFile.delete();
            if (encryptedFile != null) encryptedFile.delete();
        }
    }

    /**
     * Calculates a SHA-256 hash of the file contents
     * Used for file integrity verification
     * 
     * @param filePath Path to the file to hash
     * @return Hexadecimal string representation of the file hash
     * @throws Exception If hashing fails
     */
    private String calculateFileHash(Path filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(filePath);
        byte[] hashBytes = digest.digest(fileBytes);
        
        StringBuilder hexString = new StringBuilder();
        for (byte hashByte : hashBytes) {
            String hex = Integer.toHexString(0xff & hashByte);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        
        return hexString.toString();
    }

    /**
     * Compresses a file using GZIP compression
     * 
     * @param inputFile The file to compress
     * @return A temporary file containing the compressed data
     * @throws IOException If compression fails
     */
    private File compressFile(File inputFile) throws IOException {
        File compressedFile = File.createTempFile("compressed_", ".gz");
        
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(compressedFile);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, len);
            }
        }
        
        return compressedFile;
    }

    /**
     * Encrypts a file using AES encryption with the configured key
     * 
     * @param inputFile The file to encrypt
     * @return A temporary file containing the encrypted data
     * @throws Exception If encryption fails
     */
    private File encryptFile(File inputFile) throws Exception {
        File encryptedFile = File.createTempFile("encrypted_", ".tmp");
        
        // Generate a 32-byte (256-bit) key using SHA-256
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(encryptionKey.getBytes());
        
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(encryptedFile)) {
            
            byte[] inputBytes = new byte[(int) inputFile.length()];
            fis.read(inputBytes);
            
            byte[] outputBytes = cipher.doFinal(inputBytes);
            fos.write(outputBytes);
        }
        
        return encryptedFile;
    }

    /**
     * Sends a file to the remote server over a socket connection
     * Includes metadata like client label, original filename, and file hash
     * 
     * @param file The file to send (already compressed and encrypted)
     * @param fileHash The hash of the original file for integrity verification
     * @param originalFileName The name of the original file
     * @return true if the server confirmed successful receipt, false otherwise
     */
    private boolean sendFileToServer(File file, String fileHash, String originalFileName) {
        long startTime = System.currentTimeMillis();
        
        try (Socket socket = new Socket()) {
            // Use timeouts so an unreachable or unresponsive server can't block the client forever
            socket.connect(new InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(socketTimeoutSeconds * 1000);
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            
            // Send client label
            dos.writeUTF(clientLabel);
            
            // Send original file name
            dos.writeUTF(originalFileName);
            
            // Send file hash
            dos.writeUTF(fileHash);
            
            // Send file size
            dos.writeLong(file.length());
            
            // Send file data
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                }
            }
            
            // Check response
            try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
                String response = dis.readUTF();
                
                long endTime = System.currentTimeMillis();
                double duration = (endTime - startTime) / 1000.0;
                
                if ("SUCCESS".equals(response)) {
                    String message = "File sent: " + originalFileName + " (Duration: " + duration + " seconds)";
                    logger.info(message);
                    return true;
                }
                
                logger.error("Server rejected file " + originalFileName + ": " + response);
                return false;
            }
        } catch (IOException e) {
            logger.error("Error sending file " + originalFileName + " to server: " + e, e);
            return false;
        }
    }

    /**
     * Masks the encryption key for secure logging
     * Shows only first and last 4 characters
     * 
     * @param key The encryption key to mask
     * @return A masked version of the key
     */
    private String maskEncryptionKey(String key) {
        if (key == null || key.length() <= 8) {
            return "***masked***";
        }
        // Show only first 4 and last 4 characters for security
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /**
     * Logs detailed information about the client environment and configuration
     * Useful for debugging and audit purposes
     */
    private void printEnvironmentInfo() {
        String maskedKey = maskEncryptionKey(encryptionKey);
        logger.info("=========================================");
        logger.info("=== Client jFiltra Started ==============");
        logger.info("=========================================");
        logger.info("Client configuration:");
        logger.info("-----------------------------------------");
        logger.info("Client Label: " + clientLabel);
        logger.info("Server IP: " + serverIp);
        logger.info("Server Port: " + serverPort);
        logger.info("Encryption Key: " + maskedKey);
        logger.info("Source Directories: " + String.join(", ", sourceDirs));
        logger.info("Polling Interval: " + pollingIntervalSeconds + " seconds");
        logger.info("Socket Timeout: " + socketTimeoutSeconds + " seconds");
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
} 