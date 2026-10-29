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
        // csv.delimiter: words, escapes, spaces around the value (L7)
        eq(String.valueOf(JFiltraDbClient.parseDelimiter("\t")), "\t");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter("\\t")), "\t");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter("space")), " ");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter("\\s")), " ");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter("; ")), ";");
        eq(String.valueOf(JFiltraDbClient.parseDelimiter(" | ")), "|");
        invalid(() -> JFiltraDbClient.parseDelimiter("s"), "delimiter s (\\s with one backslash)");
        invalid(() -> JFiltraDbClient.parseDelimiter("1"), "delimiter 1");
        invalid(() -> JFiltraDbClient.parseDelimiter("  "), "delimiter of two spaces");
        // file.prefix: the server's file name rules (L5)
        JFiltraDbClient.checkPrefix("nul_");
        JFiltraDbClient.checkPrefix("con");
        invalid(() -> JFiltraDbClient.checkPrefix("nul."), "prefix nul.");
        invalid(() -> JFiltraDbClient.checkPrefix("COM1."), "prefix COM1.");
        invalid(() -> JFiltraDbClient.checkPrefix(new String(new char[201]).replace('\0', 'x')), "prefix of 201 characters");
        JFiltraDbClient.checkPrefix(new String(new char[200]).replace('\0', 'x'));
        // Duplicate column names for jsonl (L6)
        JFiltraDbClient.checkUniqueNames(new String[] {"id", "ID", "name"});
        invalid(() -> JFiltraDbClient.checkUniqueNames(new String[] {"id", "name", "id"}), "duplicate column id");
        // BIT(n) as bits (H2)
        eq(JFiltraDbClient.bitString(new byte[] {(byte) 0xAA}, 8), "10101010");
        eq(JFiltraDbClient.bitString(new byte[] {0x0A, 0x01}, 12), "101000000001");
        eq(JFiltraDbClient.bitString(new byte[] {0x05}, 3), "101");
        eq(JFiltraDbClient.bitString(new byte[] {0x01}, 10), "0000000001");
        // TIME fractions (L1)
        eq(JFiltraDbClient.trimFraction("19:30:05.123456"), "19:30:05.123456");
        eq(JFiltraDbClient.trimFraction("19:30:05.500000"), "19:30:05.5");
        eq(JFiltraDbClient.trimFraction("19:30:05.000"), "19:30:05");
        eq(JFiltraDbClient.trimFraction("838:59:59"), "838:59:59");
        eq(JFiltraDbClient.trimFraction("10:00:00"), "10:00:00");
        // Passwords masked in logged text (M4)
        eq(JFiltraDbClient.mask("jdbc:mysql://user:SECRET@host:3306/db"), "jdbc:mysql://user:****@host:3306/db");
        eq(JFiltraDbClient.mask("jdbc:mysql://user:p@ss@host/db"), "jdbc:mysql://user:****@host/db");
        eq(JFiltraDbClient.mask("jdbc:oracle:thin:scott/SECRET@//host:1521/ORCL"), "jdbc:oracle:thin:scott/****@//host:1521/ORCL");
        eq(JFiltraDbClient.mask("No suitable driver found for jdbc:x://h/db?user=a&password=SECRET&ssl=1"),
                "No suitable driver found for jdbc:x://h/db?user=a&password=****&ssl=1");
        eq(JFiltraDbClient.mask("jdbc:sqlserver://h;user=a;Password=SECRET;db=x"), "jdbc:sqlserver://h;user=a;Password=****;db=x");
        eq(JFiltraDbClient.mask("jdbc:postgresql://host:5432/db"), "jdbc:postgresql://host:5432/db");
        eq(JFiltraDbClient.mask("jdbc:oracle:thin:@//host:1521/ORCL"), "jdbc:oracle:thin:@//host:1521/ORCL");
        eq(JFiltraDbClient.mask("FATAL: password authentication failed for user \"jf\""),
                "FATAL: password authentication failed for user \"jf\"");
        eq(JFiltraDbClient.urlPasswords("jdbc:mysql://u:S1@h/db?password=S2").toString(), "[S1, S2]");
        eq(JFiltraDbClient.urlPasswords("jdbc:oracle:thin:scott/S3@//h:1521/X").toString(), "[S3]");
        eq(JFiltraDbClient.urlPasswords("jdbc:postgresql://h:5432/db").toString(), "[]");
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
