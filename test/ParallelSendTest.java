/*
 * Sends many files with the same name to a jFiltra server at the same time and checks
 * what the server stored, for one on.name.conflict mode. Run by test/smoke-test.sh.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 *
 * Usage: ParallelSendTest <port> <label> <key> <storageDir> <mode> <count>
 *        ParallelSendTest <port> <label> <key> <storageDir> expect <announcedSize|0> <reply>
 * Prints one line and exits with 0 if the stored result is correct for the mode.
 */
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;

public class ParallelSendTest {
    static final int BLOCK = 100000;
    static final String NAME = "parallel.csv";

    static byte[] content(int i) {
        byte[] b = new byte[BLOCK];
        Arrays.fill(b, (byte) ('A' + i));
        return b;
    }

    static String send(int port, String label, String key, byte[] data) throws Exception {
        return send(port, label, key, data, null);
    }

    static String send(int port, String label, String key, byte[] data, Long announcedSize) throws Exception {
        byte[] k = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"));
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(new CipherOutputStream(enc, cipher))) {
            gz.write(data);
        }
        StringBuilder hash = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) {
            hash.append(String.format("%02x", x));
        }
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(60000);
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            dos.writeUTF(label);
            dos.writeUTF(NAME);
            dos.writeUTF(hash.toString());
            dos.writeLong(announcedSize != null ? announcedSize : enc.size());
            if (announcedSize == null) {
                enc.writeTo(dos);
            }
            dos.flush();
            return new DataInputStream(s.getInputStream()).readUTF();
        }
    }

    public static void main(String[] args) throws Exception {
        final int port = Integer.parseInt(args[0]);
        final String label = args[1], key = args[2];
        Path dir = Paths.get(args[3]);
        String mode = args[4];
        int count = Integer.parseInt(args[5]);

        // Single uploads that must get a given reply: <mode> is "expect", args[6] the reply,
        // count the announced size (0 = real size)
        if (mode.equals("expect")) {
            String reply = send(port, label, key, content(0), count == 0 ? null : (long) count);
            if (!reply.equals(args[6])) fail("server replied " + reply + ", expected " + args[6]);
            System.out.println("ParallelSendTest: got " + reply);
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(count);
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final byte[] data = content(i);
            results.add(pool.submit(() -> { go.await(); return send(port, label, key, data); }));
        }
        go.countDown();
        for (Future<String> f : results) {
            String r = f.get();
            if (!"SUCCESS".equals(r)) {
                fail("server replied " + r);
            }
        }
        pool.shutdown();

        Set<String> expected = new HashSet<>();
        for (int i = 0; i < count; i++) {
            expected.add(new String(content(i), StandardCharsets.US_ASCII));
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) files.add(p);
        }
        for (Path p : files) {
            if (p.getFileName().toString().endsWith(".part")) fail("temporary file left: " + p);
        }
        if (mode.equals("rename")) {
            Set<String> names = new HashSet<>(), contents = new HashSet<>();
            for (Path p : files) {
                names.add(p.getFileName().toString());
                contents.add(new String(Files.readAllBytes(p), StandardCharsets.US_ASCII));
            }
            Set<String> wantNames = new HashSet<>(Collections.singleton(NAME));
            for (int i = 1; i < count; i++) wantNames.add("parallel_" + i + ".csv");
            if (!names.equals(wantNames)) fail("names " + new TreeSet<>(names));
            if (!contents.equals(expected)) fail("contents lost or duplicated");
        } else if (mode.equals("overwrite")) {
            if (files.size() != 1) fail("expected one file, found " + files.size());
            String c = new String(Files.readAllBytes(files.get(0)), StandardCharsets.US_ASCII);
            if (!expected.contains(c)) fail("stored content is not one complete upload");
        } else if (mode.equals("append")) {
            if (files.size() != 1) fail("expected one file, found " + files.size());
            byte[] all = Files.readAllBytes(files.get(0));
            if (all.length != count * BLOCK) fail("size " + all.length + ", expected " + count * BLOCK);
            Set<String> blocks = new HashSet<>();
            for (int i = 0; i < count; i++) {
                blocks.add(new String(all, i * BLOCK, BLOCK, StandardCharsets.US_ASCII));
            }
            if (!blocks.equals(expected)) fail("uploads interleaved or missing");
        } else {
            fail("unknown mode " + mode);
        }
        System.out.println("ParallelSendTest " + mode + ": " + count + " parallel uploads OK");
    }

    static void fail(String message) {
        System.out.println("ParallelSendTest: FAIL " + message);
        System.exit(1);
    }
}
