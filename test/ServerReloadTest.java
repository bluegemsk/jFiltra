/* PolyForm Noncommercial License 1.0.0. Mapping reload races and last-good snapshots. */
package server;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Properties;

public class ServerReloadTest {
    private static void write(Path path, String text, FileTime time) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(path, time);
    }
    private static Properties[] reload(JFiltraServer server) throws Exception {
        Method method = JFiltraServer.class.getDeclaredMethod("clientConfiguration");
        method.setAccessible(true);
        return (Properties[]) method.invoke(server);
    }
    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    public static void main(String[] args) throws Exception {
        Path work = Files.createDirectories(Paths.get(args[0]));
        Path keys = work.resolve("keys.properties"), paths = work.resolve("paths.properties");
        Path config = work.resolve("server.properties");
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 10000);
        String a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        write(keys, "test=" + a + "\n", old);
        write(paths, "test=/first\n", old);
        Files.write(config, ("server.port=19591\nclient.keys.path=" + keys + "\nclient.paths.config=" + paths + "\n").getBytes(StandardCharsets.UTF_8));
        JFiltraServer server = new JFiltraServer(config.toString());
        Properties[] initial = reload(server);
        if (reload(server)[0] != initial[0]) throw new AssertionError("settled mapping should not reload");
        // Size alone changes while the recorded mtime remains the same.
        write(keys, "test=" + a + "x\n", old);
        write(paths, "test=/longer-path\n", old);
        Properties[] updated = reload(server);
        eq(updated[0].getProperty("test"), a + "x");
        eq(updated[1].getProperty("test"), "/longer-path");
        // Two writes share both the recent mtime and byte length.
        FileTime recent = FileTime.fromMillis(System.currentTimeMillis());
        write(keys, "test=" + a + "\n", recent);
        write(paths, "test=/first\n", recent);
        reload(server);
        write(keys, "test=" + b + "\n", recent);
        write(paths, "test=/other\n", recent);
        updated = reload(server);
        eq(updated[0].getProperty("test"), b);
        eq(updated[1].getProperty("test"), "/other");
        // Editor's truncate-then-write update: do not cache the empty first step forever.
        recent = FileTime.fromMillis(System.currentTimeMillis());
        write(keys, "", recent); write(paths, "", recent); reload(server);
        write(keys, "test=" + b + "\n", recent);
        write(paths, "test=/final\n", recent);
        updated = reload(server);
        eq(updated[0].getProperty("test"), b); eq(updated[1].getProperty("test"), "/final");
        write(keys, "test=SecretLeak_X\n", FileTime.fromMillis(System.currentTimeMillis()));
        eq(reload(server)[0].getProperty("test"), b);
        // Once old/settled, identical metadata must stop triggering reloads.
        write(keys, "test=" + b + "\n", old); write(paths, "test=/final\n", old);
        updated = reload(server);
        if (reload(server)[0] != updated[0] || reload(server)[1] != updated[1])
            throw new AssertionError("settled mappings must be cached");
        System.out.println("UNCHANGED_RELOAD_BEGIN");
        recent = FileTime.fromMillis(System.currentTimeMillis());
        write(keys, "test=" + b + "\n", recent); write(paths, "test=/final\n", recent);
        for (int i = 0; i < 5; i++) reload(server);
        System.out.println("UNCHANGED_RELOAD_END");
        // Future metadata may trigger one read, but never the unsettled retry window.
        System.out.println("FUTURE_RELOAD_BEGIN");
        FileTime future = FileTime.fromMillis(System.currentTimeMillis() + 3600000);
        write(keys, "test=" + a + "\n", future); write(paths, "test=/future\n", future);
        updated = reload(server);
        for (int i = 0; i < 5; i++) {
            Properties[] again = reload(server);
            if (again[0] != updated[0] || again[1] != updated[1])
                throw new AssertionError("future metadata must be settled after reload");
        }
        eq(updated[0].getProperty("test"), a); eq(updated[1].getProperty("test"), "/future");
        System.out.println("FUTURE_RELOAD_END");
        System.out.println("FUTURE_STARTUP_BEGIN");
        JFiltraServer futureServer = new JFiltraServer(config.toString());
        updated = reload(futureServer);
        for (int i = 0; i < 5; i++) {
            Properties[] again = reload(futureServer);
            if (again[0] != updated[0] || again[1] != updated[1])
                throw new AssertionError("future startup metadata must be settled");
        }
        System.out.println("FUTURE_STARTUP_END");
        // A revert can restore the exact last-good metadata as well as its contents.
        // Error state must force a successful retry and exactly one recovery message.
        System.out.println("KEYS_RECOVERY_BEGIN");
        FileTime badTime = FileTime.fromMillis(future.toMillis() + 1);
        write(keys, "test=short\n", badTime);
        for (int i = 0; i < 3; i++) eq(reload(server)[0].getProperty("test"), a);
        write(keys, "test=" + a + "\n", future);
        for (int i = 0; i < 10; i++) eq(reload(server)[0].getProperty("test"), a);
        System.out.println("KEYS_RECOVERY_END");
        System.out.println("PATHS_RECOVERY_BEGIN");
        write(paths, "test=" + (char) 92 + "uZZZZ\n", badTime);
        for (int i = 0; i < 3; i++) eq(reload(server)[1].getProperty("test"), "/future");
        write(paths, "test=/future\n", future);
        for (int i = 0; i < 10; i++) eq(reload(server)[1].getProperty("test"), "/future");
        System.out.println("PATHS_RECOVERY_END");
        System.out.println("ServerReloadTest: all passed");
    }
}
