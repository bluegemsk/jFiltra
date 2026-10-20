/*
 * Unit tests for the client's filemask matcher. Run by test/smoke-test.sh.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 */
package client;

import java.util.Arrays;

public class FileMaskTest {
    private static int failed = 0;

    private static void match(String mask, String name, boolean expected) {
        boolean actual = JFiltraClient.globMatches(mask, name);
        if (actual != expected) {
            System.out.println("  FAIL  '" + mask + "' vs '" + name + "': expected " + expected);
            failed++;
        }
    }

    private static void invalid(String value) {
        try {
            JFiltraClient.parseFileMasks(value);
            System.out.println("  FAIL  mask '" + value + "' should be refused");
            failed++;
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        match("*", "anything.txt", true);
        match("*", "", true);
        match("*.csv", "data.csv", true);
        match("*.csv", "DATA.CSV", true);
        match("*.csv", "data.csv.bak", false);
        match("*.csv", "csv", false);
        match("report_????.txt", "report_2026.txt", true);
        match("report_????.txt", "report_26.txt", false);
        match("a*b*c", "aXXbYYc", true);
        match("a*b*c", "aXXbYY", false);
        match("*.*", "noextension", false);
        match("in[1].txt", "in[1].txt", true);
        match("in[1].txt", "in1.txt", false);
        match("ž*.TXT", "Žltý.txt", true);
        match("**a", "bba", true);
        match("?", "", false);
        match("x?.txt", "x\uD83D\uDE00.txt", true);   // ? matches a whole emoji
        match("x??.txt", "x\uD83D\uDE00.txt", false);
        // A pathological mask must not take exponential time
        char[] a = new char[5000];
        Arrays.fill(a, 'a');
        String longName = new String(a);
        long start = System.nanoTime();
        match("*a*a*a*a*a*a*a*a*a*a*b", longName, false);
        if (System.nanoTime() - start > 2_000_000_000L) {
            System.out.println("  FAIL  pathological mask too slow");
            failed++;
        }
        if (!JFiltraClient.parseFileMasks("*.csv; *.txt ,data_*").equals(Arrays.asList("*.csv", "*.txt", "data_*"))) {
            System.out.println("  FAIL  list parsing");
            failed++;
        }
        if (!JFiltraClient.parseFileMasks(" ").equals(Arrays.asList("*"))) {
            System.out.println("  FAIL  empty value means *");
            failed++;
        }
        invalid("dir/*.csv");
        invalid("*.csv,..\\x");
        invalid("a\tb");
        System.out.println(failed == 0 ? "FileMaskTest: all passed" : "FileMaskTest: " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
