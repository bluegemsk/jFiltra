/*
 * Unit tests for the client's file.order setting. Run by test/smoke-test.sh.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 */
package client;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileOrderTest {
    private static int failed = 0;

    private static JFiltraClient.Candidate c(String name, long size, long modified) {
        return new JFiltraClient.Candidate(Paths.get("/outbox", name), size, modified);
    }

    // b: smallest, c: oldest, a: largest and newest; d ties with b on size and c on time
    private static final List<JFiltraClient.Candidate> FILES = Arrays.asList(
            c("a.txt", 300, 3000), c("b.txt", 100, 2000), c("c.txt", 200, 1000), c("d.txt", 100, 1000));

    private static void order(String spec, String expected) {
        List<JFiltraClient.Candidate> list = new ArrayList<>(FILES);
        list.sort(JFiltraClient.parseFileOrder(spec));
        StringBuilder sb = new StringBuilder();
        for (JFiltraClient.Candidate x : list) sb.append(x.name.charAt(0));
        if (!sb.toString().equals(expected)) {
            System.out.println("  FAIL  file.order=" + spec + ": expected " + expected + " but got " + sb);
            failed++;
        }
    }

    private static void invalid(String spec) {
        try {
            JFiltraClient.parseFileOrder(spec);
            System.out.println("  FAIL  file.order=" + spec + " should be refused");
            failed++;
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        order("time", "cdba");      // oldest first, ties by name
        order("!time", "abcd");     // newest first, ties still by name A-Z
        order("size", "bdca");      // smallest first
        order("!size", "acbd");     // largest first
        order("name", "abcd");
        order("!name", "dcba");
        order("TIME", "cdba");
        order(" ! Size ", "acbd");
        invalid("date");
        invalid("!!time");
        invalid("time,name");
        invalid("!");
        System.out.println(failed == 0 ? "FileOrderTest: all passed" : "FileOrderTest: " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
