/* PolyForm Noncommercial License 1.0.0. Inject a permission change after upload, before SUCCESS. */
package client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.atomic.*;

public class AfterSendTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void run(Path work, String action, boolean once) throws Exception {
        Path dir = Files.createDirectories(work.resolve(action + (once ? "-once" : "-loop")));
        Path source = Files.createDirectory(dir.resolve("source"));
        Path next = Files.createDirectory(dir.resolve("next"));
        Path first = source.resolve("a.txt");
        Files.write(first, "first".getBytes(StandardCharsets.UTF_8));
        Files.write(next.resolve("z.txt"), "next".getBytes(StandardCharsets.UTF_8));
        Path locked = action.equals("move") ? Files.createDirectory(source.resolve("sent")) : source;
        String snapshot = Files.size(first) + ":" + Files.getLastModifiedTime(first).toMillis();
        AtomicInteger uploads = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerSocket listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        Thread receiver = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        in.readUTF(); in.readUTF(); in.readUTF();
                        long left = in.readLong();
                        byte[] buffer = new byte[8192];
                        while (left > 0) {
                            int read = in.read(buffer, 0, (int) Math.min(left, buffer.length));
                            if (read < 0) throw new EOFException();
                            left -= read;
                        }
                        uploads.incrementAndGet();
                        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        out.writeUTF("SUCCESS"); out.flush();
                    }
                }
            } catch (SocketException e) {
                if (!listener.isClosed()) failure.set(e);
            } catch (Throwable e) { failure.set(e); }
        });
        receiver.start();
        Path config = dir.resolve("client.properties");
        String text = "client.label=test\nsource.directories=" + source + "," + next
                + "\nserver.host=127.0.0.1\nserver.port=" + listener.getLocalPort()
                + "\nencryption.key=12345678901234567890123456789012\nafter.send=" + action
                + "\nfile.order=name\nrun.mode=" + (once ? "once" : "loop") + "\n";
        Files.write(config, text.getBytes(StandardCharsets.UTF_8));
        try {
            JFiltraClient client = new JFiltraClient(config.toString());
            if (once) {
                check(client.runOnce() == 3, "cleanup failure must exit 3");
                check(Files.exists(next.resolve("z.txt")), "once must not send the next file");
            } else {
                Method process = JFiltraClient.class.getDeclaredMethod("processFile", Path.class, String.class);
                process.setAccessible(true);
                process.invoke(client, first, snapshot);
                Field delivered = JFiltraClient.class.getDeclaredField("deliveredFiles");
                delivered.setAccessible(true);
                check(((Map<?, ?>) delivered.get(client)).containsKey(first), "loop must remember delivery");
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
                Method finish = JFiltraClient.class.getDeclaredMethod("finishDelivered", Path.class, String.class);
                finish.setAccessible(true);
                check((Boolean) finish.invoke(client, first, snapshot), "loop cleanup retry must succeed");
                check(!Files.exists(first), "cleanup retry must remove original");
                check(((Map<?, ?>) delivered.get(client)).isEmpty(), "delivery memory cleared after cleanup");
            }
            check(uploads.get() == 1, "must upload exactly once: " + action + "/" + once);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
            listener.close(); receiver.join(6000);
        }
        check(!receiver.isAlive(), "receiver must stop");
        check(failure.get() == null, "receiver failed: " + failure.get());
    }
    public static void main(String[] args) throws Exception {
        for (String action : new String[] {"delete", "move"}) {
            run(Paths.get(args[0]), action, true);
            run(Paths.get(args[0]), action, false);
        }
        System.out.println("AfterSendTest: 4 permission-race cases passed");
    }
}
