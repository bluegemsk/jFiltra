/*
 * Unit tests for the DB client's output formatting and settings. Run by
 * test/dbclient-smoke-test.sh.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 */
package dbclient;

import java.util.Properties;

public class DbFormatTest {
    private static int failed = 0;

    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) {
            System.out.println("  FAIL  expected [" + expected + "] but got [" + actual + "]");
            failed++;
        }
    }

    private static void invalid(Runnable r, String what) {
        try {
            r.run();
            System.out.println("  FAIL  " + what + " should be refused");
            failed++;
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public static void main(String[] args) {
        eq(JFiltraDbClient.csvQuote("plain", ','), "plain");
        eq(JFiltraDbClient.csvQuote("a,b", ','), "\"a,b\"");
        eq(JFiltraDbClient.csvQuote("a,b", ';'), "a,b");
        eq(JFiltraDbClient.csvQuote("say \"hi\"", ','), "\"say \"\"hi\"\"\"");
        eq(JFiltraDbClient.csvQuote("two\nlines", ','), "\"two\nlines\"");
        eq(JFiltraDbClient.csvQuote("cr\r", ','), "\"cr\r\"");
        eq(JFiltraDbClient.tsvEscape("a\tb\nc\rd\\e"), "a\\tb\\nc\\rd\\\\e");
        eq(JFiltraDbClient.jsonString("q\"b\\n\n\u0001\u2028"), "\"q\\\"b\\\\n\\n\\u0001\\u2028\"");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter(";")), ";");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter("TAB")), "\t");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter(" ")), " ");
        invalid(() -> JFiltraDbClient.parseDelimiter("\""), "delimiter \"");
        invalid(() -> JFiltraDbClient.parseDelimiter(";;"), "delimiter ;;");
        invalid(() -> JFiltraDbClient.parseDelimiter(""), "empty delimiter");
        JFiltraDbClient.checkPrefix("export_");
        JFiltraDbClient.checkPrefix("");
        invalid(() -> JFiltraDbClient.checkPrefix("../x"), "prefix ../x");
        invalid(() -> JFiltraDbClient.checkPrefix(".hidden"), "prefix .hidden");
        invalid(() -> JFiltraDbClient.checkPrefix("a:b"), "prefix a:b");
        Properties p = new Properties();
        p.setProperty("post.transfer.sql", "A");
        p.setProperty("post.transfer.sql.2", "B");
        p.setProperty("post.transfer.sql.3", " ");
        p.setProperty("post.transfer.sql.4", "D");
        p.setProperty("post.transfer.sql.6", "not read: .5 is missing");
        eq(JFiltraDbClient.parsePostTransferSql(p).toString(), "[A, B, D]");
        System.out.println(failed == 0 ? "DbFormatTest: all passed" : "DbFormatTest: " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
