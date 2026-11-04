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
        // Brace-quoted values may contain ; and }} (N3)
        eq(JFiltraDbClient.mask("jdbc:sqlserver://h:1433;databaseName=x;password={Top;Secret13}"),
                "jdbc:sqlserver://h:1433;databaseName=x;password=****");
        eq(JFiltraDbClient.mask("x;PWD={a}}b;c};db=y"), "x;PWD=****;db=y");
        eq(JFiltraDbClient.mask("x;password={unclosed;db=y"), "x;password=****;db=y");
        eq(JFiltraDbClient.urlPasswords("jdbc:sqlserver://h;password={Top;S}}4};db=x").toString(), "[{Top;S}}4}, Top;S}4]");
        // Fatal errors and cancelled statements (N1, query timeout)
        eq(String.valueOf(JFiltraDbClient.isOutOfMemory(new java.sql.SQLException("Java heap space"))), "true");
        eq(String.valueOf(JFiltraDbClient.isOutOfMemory(new java.sql.SQLException("x", new OutOfMemoryError("y")))), "true");
        eq(String.valueOf(JFiltraDbClient.isOutOfMemory(new java.sql.SQLException("Connection refused"))), "false");
        eq(String.valueOf(JFiltraDbClient.isTimeout(new java.sql.SQLException("canceling statement due to user request", "57014"))), "true");
        eq(String.valueOf(JFiltraDbClient.isTimeout(new java.sql.SQLTimeoutException("x"))), "true");
        eq(String.valueOf(JFiltraDbClient.isTimeout(new java.sql.SQLException("syntax error", "42601"))), "false");
        // Long brace-quoted text: no StackOverflowError (A1)
        String longText = new String(new char[1000000]).replace('\0', 'x');
        eq(JFiltraDbClient.mask("bad value: \"note password={" + longText + "}\" at 1"), "bad value: \"note password=****\" at 1");
        eq(JFiltraDbClient.mask("bad value: \"note pwd={" + longText + "\""), "bad value: \"note pwd=****\"");
        eq(JFiltraDbClient.mask("PWD = {" + longText + "}} ;x"), "PWD = **** ;x");
        eq(String.valueOf(JFiltraDbClient.urlPasswords("jdbc:x://h;password={" + longText + "}").size()), "2");
        eq(JFiltraDbClient.replaceLiteral("a" + longText + "b" + longText, longText, "*"), "a*b*");
        eq(JFiltraDbClient.replaceLiteral("abcabc", "bc", "*"), "a*a*");
        // Masking that fails never hides the error class (A1 safety net)
        java.sql.SQLException broken = new java.sql.SQLException("x") {
            @Override
            public String getMessage() {
                throw new StackOverflowError();
            }
        };
        eq(JFiltraDbClient.safe(broken, java.util.Collections.<String>emptyList()),
                broken.getClass().getName() + ": (message hidden: masking failed)");
        eq(JFiltraDbClient.safe(new java.sql.SQLException("pwd=abcd; user abcd"), java.util.Arrays.asList("abcd")),
                "java.sql.SQLException: pwd=****; user ****");
        // Keywords only as whole words (A1)
        eq(JFiltraDbClient.mask("mypwd=abc;oldpassword=abc;passwords=abc"), "mypwd=abc;oldpassword=abc;passwords=abc");
        eq(JFiltraDbClient.mask("db.password=abc db_pwd=abc (passwd:abc)"), "db.password=**** db_pwd=**** (passwd:****)");
        eq(JFiltraDbClient.mask("password=  "), "password=  ");
        eq(JFiltraDbClient.mask("password={}"), "password=****");
        eq(JFiltraDbClient.mask("password={{inner}}}"), "password=****");
        eq(JFiltraDbClient.mask("Password: {Top;Secret} more"), "Password: **** more");
        eq(JFiltraDbClient.mask("PASSWORD={x};password=plain"), "PASSWORD=****;password=****");
        // Timeout hint only for real timeouts (A2)
        eq(String.valueOf(JFiltraDbClient.isTimeout(new java.sql.SQLException("relation \"cancelled_orders\" does not exist", "42P01"))), "false");
        eq(String.valueOf(JFiltraDbClient.isTimeout(new java.sql.SQLException("Statement cancelled due to client request"))), "false");
        eq(String.valueOf(JFiltraDbClient.isTimeout(new java.sql.SQLException("max_statement_time exceeded", "70100"))), "true");
        eq(String.valueOf(JFiltraDbClient.isTimeout(new RuntimeException(new java.sql.SQLException("x", "57014")))), "true");
        // Out of memory only from OutOfMemoryError or the exact message (observation)
        eq(String.valueOf(JFiltraDbClient.isOutOfMemory(new java.sql.SQLException("GC overhead limit exceeded"))), "true");
        eq(String.valueOf(JFiltraDbClient.isOutOfMemory(new java.sql.SQLException("invalid input syntax: \"Java heap space\""))), "false");
        // useCursorFetch=true or yes (observation)
        eq(String.valueOf(JFiltraDbClient.hasCursorFetch("jdbc:mysql://h/db?useCursorFetch=true")), "true");
        eq(String.valueOf(JFiltraDbClient.hasCursorFetch("jdbc:mysql://h/db?a=1&USECURSORFETCH=YES")), "true");
        eq(String.valueOf(JFiltraDbClient.hasCursorFetch("jdbc:mysql://h/db?useCursorFetch=false")), "false");
        eq(String.valueOf(JFiltraDbClient.hasCursorFetch("jdbc:mysql://h/db?useCursorFetch=yesno")), "false");
        eq(String.valueOf(JFiltraDbClient.hasCursorFetch("jdbc:mysql://h/db")), "false");
        // Lock file errors (A3)
        java.nio.file.Path lock = java.nio.file.Paths.get("spool/.export_.lock");
        eq(JFiltraDbClient.lockErrorMessage(lock, new java.nio.file.AccessDeniedException(lock.toString())),
                "Cannot create or lock spool lock file " + lock.toAbsolutePath()
                + ": permission denied. The user running the client needs write access to spool.directory.");
        // Leftover spool files of this prefix only (N2)
        eq(String.valueOf(JFiltraDbClient.isSpoolFileName(".export_20261028-193005-123.csv.tmp", "export_")), "true");
        eq(String.valueOf(JFiltraDbClient.isSpoolFileName(".export_a20261028-193005-123.csv.tmp", "export_")), "false");
        eq(String.valueOf(JFiltraDbClient.isSpoolFileName(".export_.lock", "export_")), "false");
        eq(String.valueOf(JFiltraDbClient.isSpoolFileName(".other_20261028-193005-123.jsonl.tmp", "export_")), "false");
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
