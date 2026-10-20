/*
 * Unit tests for the server's on.name.conflict parsing and numbered names.
 * Run by test/smoke-test.sh.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 */
package server;

public class NameConflictTest {
    private static int failed = 0;

    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) {
            System.out.println("  FAIL  expected '" + expected + "' but got '" + actual + "'");
            failed++;
        }
    }

    private static void invalid(String value) {
        try {
            JFiltraServer.parseOnNameConflict(value);
            System.out.println("  FAIL  '" + value + "' should be refused");
            failed++;
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        eq(JFiltraServer.parseOnNameConflict(null), "rename");
        eq(JFiltraServer.parseOnNameConflict("  "), "rename");
        eq(JFiltraServer.parseOnNameConflict("Rename"), "rename");
        eq(JFiltraServer.parseOnNameConflict("OVERWRITE"), "overwrite");
        eq(JFiltraServer.parseOnNameConflict(" Append "), "append");
        eq(JFiltraServer.parseOnNameConflict("reject"), "reject");
        invalid("replace");
        invalid("overwrite,append");
        eq(JFiltraServer.numberedName("report.csv", 1), "report_1.csv");
        eq(JFiltraServer.numberedName("report.csv", 12), "report_12.csv");
        eq(JFiltraServer.numberedName("archive.tar.gz", 2), "archive.tar_2.gz");
        eq(JFiltraServer.numberedName("README", 3), "README_3");
        eq(JFiltraServer.numberedName(".profile", 1), ".profile_1");
        eq(JFiltraServer.numberedName("name.", 1), "name_1.");
        // Very long names are shortened (whole characters) so the result fits in 255 bytes
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 251; i++) longName.append('x');
        String n255 = longName + ".csv";
        String r = JFiltraServer.numberedName(n255, 12);
        if (r.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 255 || !r.endsWith("_12.csv")) {
            System.out.println("  FAIL  long name: " + r.length());
            failed++;
        }
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 127; i++) wide.append('\u010d');   // 254 bytes
        r = JFiltraServer.numberedName(wide.toString(), 3);
        if (r.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255 || !r.endsWith("\u010d_3")) {
            System.out.println("  FAIL  long multi-byte name: " + r);
            failed++;
        }
        System.out.println(failed == 0 ? "NameConflictTest: all passed" : "NameConflictTest: " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
