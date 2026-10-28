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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.Deflater;
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
    // jFiltra release version, shown at startup
    private static final String VERSION = "1.1.0";
    
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
    private String fileMask;                    // File name masks of files to send
    private List<String> fileMasks;             // Parsed file masks
    private boolean runOnce;                    // run.mode=once: send one file and exit
    private String fileOrder;                   // file.order: time, size or name, ! reverses
    private Comparator<Candidate> fileComparator; // Order in which files are sent
    private boolean lastFileSent;               // Whether the last processFile call succeeded
    private boolean lastPermanentRejection;     // Whether the server last rejected a file for good

    // Files delivered whose deletion failed, with their snapshot: only the delete is retried,
    // never the transfer (a resend would append or store the file again)
    private final Map<Path, String> deliveredFiles = new HashMap<>();

    // Source directories that could not be read, so the error is logged only once
    private final Set<String> failingDirs = new HashSet<>();

    // Exit codes of run.mode=once (1 is also used for configuration errors in both modes)
    static final int EXIT_SENT = 0;
    static final int EXIT_CONFIG_ERROR = 1;
    static final int EXIT_NOTHING_TO_SEND = 2;
    static final int EXIT_TRANSFER_FAILED = 3;
    
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
    
    // Longest wait before retrying a file the server rejected (e.g. "File already exists"),
    // and before trying an unreachable server again
    private static final long MAX_REJECTION_RETRY_MILLIS = 10 * 60 * 1000L;
    private static final long MAX_NETWORK_RETRY_MILLIS = 60 * 1000L;
    
    // Returned by sendFileToServer when the file changed between the two passes
    private static final String CHANGED_DURING_SEND = "(file changed while being sent)";

    // sendFileToServer result when the connection broke while sending and no reply came:
    // retried for this file only, so one problem file can't block the others
    private static final String BROKEN_DURING_SEND = "(connection lost while sending)";
    
    // Files the server rejected for a reason that may be fixed later (e.g. a wrong key or
    // a name conflict), with when to try again. Retrying waits longer after each rejection,
    // so a file isn't re-sent in full on every poll. Only accessed from the scheduler thread.
    private final Map<Path, Retry> retries = new HashMap<>();
    
    // After a network error, no file is sent before this time (milliseconds), so an
    // unreachable server isn't tried with every file on every poll
    private long serverRetryAt = 0;
    private long serverRetryDelay = 0;
    
    // Source directories reported as missing, so the warning is logged only once
    private final Set<String> missingDirs = new HashSet<>();
    
    /**
     * When to retry a file the server rejected, and the size and modification time it had then.
     */
    private static class Retry {
        final String snapshot;
        final long delayMillis;
        final long nextAttemptMillis;
        
        Retry(String snapshot, long delayMillis, long nextAttemptMillis) {
            this.snapshot = snapshot;
            this.delayMillis = delayMillis;
            this.nextAttemptMillis = nextAttemptMillis;
        }
    }
    
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
            // File names are decoded with the system locale; without UTF-8, non-ASCII names break
            checkUtf8FileNames();
            
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
                    // Lower case on both sides: not case-sensitive on any system, like filemask
                    ignoreMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim().toLowerCase(Locale.ROOT)));
                }
            }

            // File name masks of files to send (default * sends every file)
            fileMask = config.getProperty("filemask", "*").trim();
            fileMasks = parseFileMasks(fileMask);
            if (fileMask.isEmpty()) {
                fileMask = "*";
            }

            // Run continuously (default) or send one file and exit
            String mode = config.getProperty("run.mode", "loop").trim().toLowerCase(Locale.ROOT);
            if (!mode.isEmpty() && !mode.equals("loop") && !mode.equals("once")) {
                throw new IllegalArgumentException("Setting run.mode must be loop or once, but is: "
                        + config.getProperty("run.mode").trim());
            }
            runOnce = mode.equals("once");

            // Order in which files are sent (default time: oldest first)
            fileOrder = config.getProperty("file.order", "time").trim();
            if (fileOrder.isEmpty()) {
                fileOrder = "time";
            }
            fileComparator = parseFileOrder(fileOrder);

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
            logger.debug("Scanning directories for files to send");
            
            Map<Path, FileState> currentStates = new HashMap<>();
            List<Candidate> candidates = new ArrayList<>();
           
            for (String dirPath : sourceDirs) {
                try {
                    Path dir = Paths.get(dirPath);
                    if (!Files.isDirectory(dir)) {
                        // Warn once, not on every poll
                        if (missingDirs.add(dirPath)) {
                            logger.warn("Directory does not exist: " + dirPath + " (checked again on every poll)");
                        }
                        continue;
                    }
                    if (missingDirs.remove(dirPath)) {
                        logger.info("Directory is available again: " + dirPath);
                    }
                    
                    // Close the directory listing after use, otherwise every scan leaks a file handle.
                    // Symbolic links are skipped: following them would send (and then delete the
                    // link to) any file the client can read, such as files outside the source directory.
                    candidates.addAll(listCandidates(dir));
                    if (failingDirs.remove(dirPath)) {
                        logger.info("Directory is readable again: " + dirPath);
                    }
                } catch (Exception e) {
                    // Log once, not on every poll
                    if (failingDirs.add(dirPath)) {
                        logger.error("Cannot read directory " + dirPath + ": " + e + " (checked again on every poll)");
                    }
                }
            }
            
            // Send in the order of file.order, across all source directories
            candidates.sort(fileComparator);
            for (Candidate candidate : candidates) {
                processIfStable(candidate.path, currentStates);
            }
            
            previousStates = currentStates;
            
            // Forget rejected files that are gone
            rejectedFiles.keySet().retainAll(currentStates.keySet());
            retries.keySet().retainAll(currentStates.keySet());
            deliveredFiles.keySet().retainAll(currentStates.keySet());
        } catch (Throwable t) {
            logger.error("Unexpected error while polling directories: " + t, t);
        }
    }

    /**
     * Refuses to run when Java decodes file names with a non-UTF-8 charset (e.g. the
     * POSIX/C locale): non-ASCII names would be sent mangled and the source deleted.
     * Windows uses UTF-16 file names and is not affected.
     *
     * @throws IllegalArgumentException If the file name encoding is not UTF-8
     */
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

    /**
     * A file found in a source directory, with the attributes used to order files.
     */
    static class Candidate {
        final Path path;
        final String name;
        final long size;
        final long modified;

        Candidate(Path path, long size, long modified) {
            this.path = path;
            this.name = path.getFileName().toString();
            this.size = size;
            this.modified = modified;
        }
    }

    /**
     * Lists the files in a directory that may be sent: regular files (no symbolic links)
     * that match filemask and no ignore pattern.
     *
     * @param dir The source directory
     * @return The files with their size and modification time
     * @throws IOException If the directory cannot be listed
     */
    private List<Candidate> listCandidates(Path dir) throws IOException {
        // Close the directory listing after use, otherwise every scan leaks a file handle.
        // Symbolic links are skipped: following them would send (and then delete the
        // link to) any file the client can read, such as files outside the source directory.
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                   .filter(file -> !isIgnored(file))
                   .filter(this::matchesFileMask)
                   .forEach(files::add);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Path file : files) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                candidates.add(new Candidate(file, attrs.size(), attrs.lastModifiedTime().toMillis()));
            } catch (NoSuchFileException e) {
                // Removed meanwhile
            }
        }
        return candidates;
    }

    /**
     * Parses file.order: time (oldest first), size (smallest first) or name (A to Z by
     * character code), ignoring case; a leading ! reverses the order. Files that are
     * equal by the chosen key are ordered by name, A to Z, so the order is always the same.
     *
     * @param value The setting's value
     * @return The comparator
     * @throws IllegalArgumentException If the value is not valid
     */
    static Comparator<Candidate> parseFileOrder(String value) {
        String key = value.trim();
        boolean reverse = key.startsWith("!");
        if (reverse) {
            key = key.substring(1).trim();
        }
        Comparator<Candidate> byName = (a, b) -> a.name.compareTo(b.name);
        Comparator<Candidate> order;
        switch (key.toLowerCase(Locale.ROOT)) {
            case "time":
                order = (a, b) -> Long.compare(a.modified, b.modified);
                break;
            case "size":
                order = (a, b) -> Long.compare(a.size, b.size);
                break;
            case "name":
                order = byName;
                break;
            default:
                throw new IllegalArgumentException("Setting file.order must be time, size or name, optionally with a leading ! to reverse, but is: " + value.trim());
        }
        if (reverse) {
            order = order.reversed();
        }
        return order.thenComparing(byName);
    }

    /**
     * run.mode=once: scans the source directories once, sends the first file in
     * file.order and returns. A file that changes during a short check (1 second) is
     * still being written and is skipped. There is one attempt, no retries.
     *
     * @return The exit code: EXIT_SENT, EXIT_NOTHING_TO_SEND or EXIT_TRANSFER_FAILED
     */
    public int runOnce() {
        try {
            List<Candidate> candidates = new ArrayList<>();
            for (String dirPath : sourceDirs) {
                Path dir = Paths.get(dirPath);
                if (!Files.isDirectory(dir)) {
                    logger.warn("Directory does not exist: " + dirPath);
                    continue;
                }
                candidates.addAll(listCandidates(dir));
            }
            candidates.sort(fileComparator);
            if (candidates.isEmpty()) {
                logger.info("No file to send");
                return EXIT_NOTHING_TO_SEND;
            }
            
            // Files still being written change within a second; skip them
            Thread.sleep(1000);
            for (Candidate candidate : candidates) {
                String snapshot;
                try {
                    snapshot = fileSnapshot(candidate.path);
                } catch (NoSuchFileException e) {
                    continue;
                }
                if (!snapshot.equals(candidate.size + ":" + candidate.modified)) {
                    logger.info("Skipping file still being written: " + candidate.name);
                    continue;
                }
                logger.info("Sending one file (file.order=" + fileOrder + "): " + candidate.name);
                lastFileSent = false;
                lastPermanentRejection = false;
                processFile(candidate.path, snapshot);
                if (lastPermanentRejection) {
                    // Would be rejected on every run: try the next file instead
                    continue;
                }
                return lastFileSent ? EXIT_SENT : EXIT_TRANSFER_FAILED;
            }
            logger.info("No file to send (no file can be sent now: still being written or always rejected)");
            return EXIT_NOTHING_TO_SEND;
        } catch (Exception e) {
            logger.error("Error in one-time run: " + e, e);
            return EXIT_TRANSFER_FAILED;
        }
    }

    /**
     * Checks whether a file matches the filemask setting. Files that don't match stay
     * in the source directory untouched.
     *
     * @param file The file to check
     * @return true if the file may be sent
     */
    private boolean matchesFileMask(Path file) {
        String name = file.getFileName().toString();
        for (String mask : fileMasks) {
            if (globMatches(mask, name)) {
                return true;
            }
        }
        logger.debug("Skipping file not matching filemask: " + name);
        return false;
    }

    /**
     * Parses the filemask setting: masks separated by commas or semicolons, spaces
     * around them ignored. An empty value means every file.
     *
     * @param value The setting's value
     * @return The masks, never empty
     * @throws IllegalArgumentException if a mask contains a path separator or a control character
     */
    static List<String> parseFileMasks(String value) {
        List<String> masks = new ArrayList<>();
        for (String mask : value.split("[,;]")) {
            mask = mask.trim();
            if (mask.isEmpty()) {
                continue;
            }
            for (int i = 0; i < mask.length(); i++) {
                char c = mask.charAt(i);
                if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                    throw new IllegalArgumentException("Invalid filemask '" + mask
                            + "': masks match file names only, use * and ? without paths");
                }
            }
            masks.add(mask);
        }
        if (masks.isEmpty()) {
            masks.add("*");
        }
        return masks;
    }

    /**
     * Matches a file name against a mask with the wildcards * (any characters, also
     * none) and ? (exactly one character). All other characters match themselves,
     * ignoring case. Runs in linear time for any input (no regular expressions).
     *
     * @param mask The mask, for example *.csv or report_????.txt
     * @param name The file name
     * @return true if the name matches the mask
     */
    static boolean globMatches(String maskText, String nameText) {
        // Work on whole characters (code points), so ? also matches e.g. an emoji
        int[] mask = maskText.codePoints().toArray();
        int[] name = nameText.codePoints().toArray();
        int m = 0, n = 0, starM = -1, starN = 0;
        while (n < name.length) {
            if (m < mask.length && mask[m] == '*') {
                starM = m++;
                starN = n;
            } else if (m < mask.length && (mask[m] == '?' || sameIgnoreCase(mask[m], name[n]))) {
                m++;
                n++;
            } else if (starM >= 0) {
                m = starM + 1;
                n = ++starN;
            } else {
                return false;
            }
        }
        while (m < mask.length && mask[m] == '*') {
            m++;
        }
        return m == mask.length;
    }

    private static boolean sameIgnoreCase(int a, int b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b)
                || Character.toUpperCase(a) == Character.toUpperCase(b);
    }

    /**
     * Checks whether a file matches one of the ignore patterns.
     * 
     * @param file The file to check
     * @return true if the file must not be sent
     */
    private boolean isIgnored(Path file) {
        Path fileName = Paths.get(file.getFileName().toString().toLowerCase(Locale.ROOT));
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
            
            if (unchangedScans < stablePolls) {
                logger.debug("Waiting for file to stop changing: " + file.getFileName());
                return;
            }
            
            // Rejected before: wait until its retry time, unless the file has changed since
            Retry retry = retries.get(file);
            if (retry != null) {
                if (!retry.snapshot.equals(snapshot)) {
                    retries.remove(file);
                } else if (System.currentTimeMillis() < retry.nextAttemptMillis) {
                    return;
                }
            }
            
            // Delivered before but not deleted: only retry the delete, never send it again
            String delivered = deliveredFiles.get(file);
            if (delivered != null) {
                if (!delivered.equals(snapshot)) {
                    deliveredFiles.remove(file); // Changed since: the new content is sent
                } else {
                    deleteDelivered(file, snapshot);
                    return;
                }
            }
            
            // Server unreachable recently: wait (scanning goes on, so file changes are still tracked)
            if (System.currentTimeMillis() < serverRetryAt) {
                return;
            }
            
            processFile(file, snapshot);
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
        logger.debug("Processing file: " + fileName);
        long startTime = System.currentTimeMillis();
        
        try {
            // First pass: hash the file and measure its compressed, encrypted size, which
            // the server needs before the data. Nothing is stored, so no temp files are needed.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream sizeCounter = new CountingOutputStream(new DiscardingOutputStream());
            writeEncrypted(filePath, sizeCounter, digest);
            String fileHash = toHex(digest.digest());
            
            // Second pass: compress, encrypt and send the file straight to the server
            String response = sendFileToServer(filePath, fileHash, sizeCounter.count, fileName);
            
            if (CHANGED_DURING_SEND.equals(response)) {
                // Already logged; the changed file is sent again once it is stable
                return;
            }
            if (response == null) {
                // No reply: the server is unreachable
                scheduleServerRetry();
                return;
            }
            if (BROKEN_DURING_SEND.equals(response)) {
                // The server was reached but the connection broke: retry this file only
                scheduleRetry(filePath, snapshot, fileName);
                return;
            }
            
            // The server answered, so it is reachable again
            serverRetryAt = 0;
            serverRetryDelay = 0;
            
            if ("SUCCESS".equals(response)) {
                double seconds = (System.currentTimeMillis() - startTime) / 1000.0;
                String size = snapshot.substring(0, snapshot.indexOf(':'));
                logger.info(String.format("File sent: %s (%s bytes, %.2f s, SHA-256 %s)", fileName, size, seconds, fileHash));
                
                // Delete original file after successful transfer, unless it changed while
                // being sent - deleting it then would lose the new content
                if (!snapshot.equals(fileSnapshot(filePath))) {
                    logger.warn("File changed while being sent and was not deleted: " + fileName);
                    return;
                }
                retries.remove(filePath);
                lastFileSent = deleteDelivered(filePath, snapshot);
            } else if (PERMANENT_REJECTIONS.contains(response)) {
                lastPermanentRejection = true;
                // Don't resend a file the server will always reject; it is tried again once it changes
                rejectedFiles.put(filePath, snapshot);
                logger.error("File " + fileName + " will not be sent again until it changes");
            } else {
                // Rejected for a reason that may be fixed later (e.g. a wrong key or a name conflict)
                scheduleRetry(filePath, snapshot, fileName);
            }
        } catch (IOException e) {
            // Problems reading the file (e.g. no permission): one line, retried with growing delays
            logger.error("Error reading file " + fileName + ": " + e);
            scheduleRetry(filePath, snapshot, fileName);
        } catch (Exception | OutOfMemoryError e) {
            // OutOfMemoryError is caught too, so one file cannot stop the others from being processed
            logger.error("Error processing file " + fileName + ": " + e, e);
           
        }
    }

    /**
     * Deletes a file the server has stored. If that fails, the file is remembered as
     * delivered, so only the delete is retried later (with growing delays), never the transfer.
     *
     * @param filePath The delivered file
     * @param snapshot The file's size and modification time when it was sent
     * @return true if the file was deleted
     */
    private boolean deleteDelivered(Path filePath, String snapshot) {
        String fileName = filePath.getFileName().toString();
        try {
            Files.delete(filePath);
            if (deliveredFiles.remove(filePath) != null) {
                logger.info("Deleted delivered file: " + fileName);
            }
            retries.remove(filePath);
            return true;
        } catch (NoSuchFileException e) {
            deliveredFiles.remove(filePath);
            return true;
        } catch (IOException e) {
            logger.error("File " + fileName + " was delivered but can't be deleted (it is not sent again): " + e);
            deliveredFiles.put(filePath, snapshot);
            scheduleRetry(filePath, snapshot, fileName);
            return false;
        }
    }

    /**
     * Schedules the next attempt to send a file the server rejected. The delay starts at
     * the polling interval and doubles after each rejection, up to 10 minutes.
     * 
     * @param filePath The rejected file
     * @param snapshot The file's size and modification time; a change cancels the delay
     * @param fileName The file name, for logging
     */
    private void scheduleRetry(Path filePath, String snapshot, String fileName) {
        Retry previous = retries.get(filePath);
        long delay = (previous == null) ? pollingIntervalSeconds * 1000L : previous.delayMillis * 2;
        delay = Math.min(delay, MAX_REJECTION_RETRY_MILLIS);
        retries.put(filePath, new Retry(snapshot, delay, System.currentTimeMillis() + delay));
        logger.warn("Will retry " + fileName + " in " + (delay / 1000) + " s");
    }

    /**
     * Pauses all transfers after a network error. The pause starts at the polling
     * interval and doubles after each failed attempt, up to 1 minute.
     */
    private void scheduleServerRetry() {
        long delay = (serverRetryDelay == 0) ? pollingIntervalSeconds * 1000L : serverRetryDelay * 2;
        serverRetryDelay = Math.min(delay, MAX_NETWORK_RETRY_MILLIS);
        serverRetryAt = System.currentTimeMillis() + serverRetryDelay;
        logger.warn("Will try the server again in " + (serverRetryDelay / 1000) + " s");
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
             GZIPOutputStream gzos = new FastGZIPOutputStream(new CipherOutputStream(new NonClosingOutputStream(out), cipher))) {
            
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
     * @return The server's reply ("SUCCESS" or "ERROR: ..."), CHANGED_DURING_SEND, or null
     *         if no reply was received (network problem)
     */
    private String sendFileToServer(Path filePath, String fileHash, long encryptedSize, String originalFileName) {
        try (Socket socket = new Socket()) {
            // Use timeouts so an unreachable or unresponsive server can't block the client forever
            socket.connect(new InetSocketAddress(serverIp, serverPort), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(socketTimeoutSeconds * 1000);
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
            
            CountingOutputStream sentCounter;
            try {
                // Send client label
                dos.writeUTF(clientLabel);
            
                // Send original file name
                dos.writeUTF(originalFileName);
            
                // Send file hash
                dos.writeUTF(fileHash);
            
                // Send file size
                dos.writeLong(encryptedSize);
            
                // Send file data, compressed and encrypted on the fly
                sentCounter = new CountingOutputStream(dos);
                writeEncrypted(filePath, sentCounter, null);
                dos.flush();
            
            } catch (IOException writeError) {
                // The connection broke while sending. The server may have rejected the file
                // early (e.g. too large) and closed the connection: read its reply if it came.
                try {
                    socket.setSoTimeout(2000);
                    String reply = new DataInputStream(socket.getInputStream()).readUTF();
                    if (reply.startsWith("ERROR: ")) {
                        logger.error("Server rejected file " + originalFileName + ": " + reply);
                        return reply;
                    }
                } catch (IOException noReply) {
                    // No reply available
                }
                logger.warn("Could not send " + originalFileName + ": connection lost while sending (" + writeError + ")");
                return BROKEN_DURING_SEND;
            }
            
            // The file changed between the two passes, so the server can't accept it.
            // Dropping the connection makes the server discard it; it is retried on the next poll.
            if (sentCounter.count != encryptedSize) {
                logger.warn("File changed while being sent, will retry: " + originalFileName);
                return CHANGED_DURING_SEND;
            }
            
            // Check response
            try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
                String response = dis.readUTF();
                if (!"SUCCESS".equals(response)) {
                    logger.error("Server rejected file " + originalFileName + ": " + response);
                }
                return response;
            }
        } catch (IOException e) {
            // Routine network problem (server down, connection refused or lost, server busy): one line
            logger.warn("Could not send " + originalFileName + ": server not reachable or connection lost (" + e + ")");
            return null;
        } catch (GeneralSecurityException e) {
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
        logger.info("Version: " + VERSION);
        logger.info("Client Label: " + clientLabel);
        logger.info("Server IP: " + serverIp);
        logger.info("Server Port: " + serverPort);
        logger.info("Encryption Key Fingerprint: " + keyFingerprint(encryptionKey));
        logger.info("Source Directories: " + String.join(", ", sourceDirs));
        logger.info("Polling Interval: " + pollingIntervalSeconds + " seconds");
        logger.info("Socket Timeout: " + socketTimeoutSeconds + " seconds");
        logger.info("Stable Polls: " + stablePolls);
        logger.info("Run Mode: " + (runOnce ? "once" : "loop"));
        logger.info("File Order: " + fileOrder);
        logger.info("File Mask: " + fileMask);
        logger.info("Ignore Patterns: " + (ignorePatterns.isEmpty() ? "(none)" : ignorePatterns));
        logger.info("-----------------------------------------");
        try {
            
            // Get and log the server's IP address for connection information
            String clientIP = java.net.InetAddress.getLocalHost().getHostAddress();
            logger.info("Client is running on IP: " + clientIP);

        } catch (IOException e) {
            logger.warn("Could not determine local IP address: " + e.getMessage());
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

        if (client.runOnce) {
            int exitCode = client.runOnce();
            logger.info("JFiltraClient finished one-time run, exit code " + exitCode);
            System.exit(exitCode);
        }

        client.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
    }

    /**
     * GZIP output using the fastest compression level. The format is the same as with
     * the default level, so any jFiltra server can read it. Compressing text and other
     * compressible data is about 2-3 times faster, at the cost of slightly larger output.
     */
    private static class FastGZIPOutputStream extends GZIPOutputStream {
        FastGZIPOutputStream(OutputStream out) throws IOException {
            super(out, 8192);
            def.setLevel(Deflater.BEST_SPEED);
        }
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
