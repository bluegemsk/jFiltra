/* PolyForm Noncommercial License 1.0.0. Deterministic growth during the hash pass. */
package client;

import server.JFiltraServer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class HashGrowthTest {
    private static Provider original;
    private static Path growingFile;
    private static final AtomicBoolean grown = new AtomicBoolean();
    private static final byte[] SUFFIX = "grown".getBytes(StandardCharsets.UTF_8);

    // Test-only provider delegates SHA-256 unchanged, appending after the first data read.
    // Short key/fingerprint updates do not trigger growth. No production hooks or timing races.
    public static class GrowingDigest extends MessageDigestSpi {
        private final MessageDigest delegate;
        public GrowingDigest() throws NoSuchAlgorithmException {
            delegate = MessageDigest.getInstance("SHA-256", original);
        }
        protected void engineUpdate(byte input) { delegate.update(input); }
        protected void engineUpdate(byte[] input, int offset, int length) {
            delegate.update(input, offset, length);
            if (length >= 8192 && growingFile != null && grown.compareAndSet(false, true)) {
                try {
                    Files.write(growingFile, SUFFIX, StandardOpenOption.APPEND);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        protected byte[] engineDigest() { return delegate.digest(); }
        protected void engineReset() { delegate.reset(); }
        protected int engineGetDigestLength() { return 32; }
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createDirectories(Paths.get(args[0]));
        int port = Integer.parseInt(args[1]);
        String key = "12345678901234567890123456789012";
        Path keys = work.resolve("keys.properties"), paths = work.resolve("paths.properties");
        Files.write(keys, ("delete=" + key + "\nmove=" + key + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(paths, ("delete=" + work.resolve("received-delete") + "\nmove="
                + work.resolve("received-move") + "\n").getBytes(StandardCharsets.UTF_8));
        Path serverConfig = work.resolve("server.properties");
        Files.write(serverConfig, ("server.port=" + port + "\non.name.conflict=append\nclient.keys.path="
                + keys + "\nclient.paths.config=" + paths + "\n").getBytes(StandardCharsets.UTF_8));
        original = MessageDigest.getInstance("SHA-256").getProvider();
        Provider provider = new Provider("HashGrowthTest", 1.0, "Test-only deterministic file append") {};
        provider.put("MessageDigest.SHA-256", GrowingDigest.class.getName());
        Security.insertProviderAt(provider, 1);
        JFiltraServer server = new JFiltraServer(serverConfig.toString());
        Thread thread = new Thread(server::start);
        thread.start();
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (true) {
                try (Socket ready = new Socket("127.0.0.1", port)) { break; }
                catch (Exception e) {
                    if (System.currentTimeMillis() >= deadline) throw e;
                    Thread.sleep(20);
                }
            }
            for (String action : new String[] { "delete", "move" }) {
                Path source = Files.createDirectories(work.resolve("source-" + action));
                Path file = source.resolve("grow.bin");
                byte[] initial = new byte[32768];
                new Random(19).nextBytes(initial);
                Files.write(file, initial);
                Path config = work.resolve("client-" + action + ".properties");
                Files.write(config, ("client.label=" + action + "\nsource.directories=" + source
                        + "\nserver.host=127.0.0.1\nserver.port=" + port + "\nencryption.key=" + key
                        + "\nfilemask=*\nrun.mode=once\nafter.send=" + action + "\n").getBytes(StandardCharsets.UTF_8));
                JFiltraClient client = new JFiltraClient(config.toString());
                growingFile = file;
                grown.set(false);
                try {
                    if (client.runOnce() != 0 || !grown.get() || Files.exists(file))
                        throw new AssertionError(action + ": file grown during hash must be sent and removed");
                    if (client.runOnce() != 2) throw new AssertionError(action + ": no file to resend");
                    byte[] expected = Arrays.copyOf(initial, initial.length + SUFFIX.length);
                    System.arraycopy(SUFFIX, 0, expected, initial.length, SUFFIX.length);
                    Path stored = work.resolve("received-" + action).resolve("grow.bin");
                    if (!Arrays.equals(expected, Files.readAllBytes(stored)))
                        throw new AssertionError(action + ": append storage must contain one complete copy");
                    if (action.equals("move") && !Arrays.equals(expected, Files.readAllBytes(source.resolve("sent/grow.bin"))))
                        throw new AssertionError("grown file must be archived intact");
                } finally {
                    growingFile = null;
                    client.stop();
                }
            }
        } finally {
            server.stop();
            thread.join(5000);
            Security.removeProvider(provider.getName());
        }
        System.out.println("HashGrowthTest: all passed");
    }
}
