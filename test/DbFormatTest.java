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
        // Unquoted value runs to & or whitespace (; stays in the value, C1b); brace-quote for SQL Server
        eq(JFiltraDbClient.mask("jdbc:sqlserver://h;user=a;Password=SECRET;db=x"), "jdbc:sqlserver://h;user=a;Password=****");
        eq(JFiltraDbClient.mask("jdbc:sqlserver://h;user=a;Password={SECRET};db=x"), "jdbc:sqlserver://h;user=a;Password=****;db=x");
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
        eq(JFiltraDbClient.mask("x;password={unclosed;db=y"), "x;password=****");
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
        eq(JFiltraDbClient.mask("bad value: \"note pwd={" + longText + "\""), "bad value: \"note pwd=****"); // Unclosed: up to & or whitespace (C1b)
        eq(JFiltraDbClient.mask("PWD = {" + longText + "}} ;x"), "PWD = **** ;x"); // Unclosed: space ends the value (C1c)
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
                "java.sql.SQLException: pwd=**** user ****");
        // Any key that ends with password, passwd or pwd (B1)
        for (String key : new String[] {"sslpassword", "trustStorePassword", "keyStorePassword",
                "trustCertificateKeyStorePassword", "clientCertificateKeyStorePassword", "keyPassword",
                "db_password", "oracle.jdbc.password", "SSLPASSWORD", "mypwd", "oldpassword", "dbpasswd", "PWD"}) {
            eq(JFiltraDbClient.mask("No suitable driver found for jdbc:x://h/db?user=a&" + key + "=Sec9ret&ssl=1"),
                    "No suitable driver found for jdbc:x://h/db?user=a&" + key + "=****&ssl=1");
            eq(JFiltraDbClient.urlPasswords("jdbc:x://h/db?user=a&" + key + "=Sec9ret&ssl=1").toString(), "[Sec9ret]");
        }
        eq(JFiltraDbClient.mask("passwords=abc;pwdx=abc;password_file=abc"), "passwords=abc;pwdx=abc;password_file=abc");
        // Quoted values (observation)
        eq(JFiltraDbClient.mask("password=\"quoted secret\" x"), "password=**** x");
        eq(JFiltraDbClient.mask("password='quoted secret' x"), "password=**** x");
        eq(JFiltraDbClient.mask("password=\"unclosed secret"), "password=\"**** secret");
        eq(JFiltraDbClient.mask("password=\"unclosed secret&x=1"), "password=\"**** secret&x=1");
        // Unquoted values run up to & or whitespace; ) only in (key=...) (C1b/C1c)
        eq(JFiltraDbClient.mask("jdbc:mysql://address=(host=127.0.0.1)(port=53306)(user=pwt)(password=Ab,cd9XyZ)/test"),
                "jdbc:mysql://address=(host=127.0.0.1)(port=53306)(user=pwt)(password=****)/test");
        eq(JFiltraDbClient.urlPasswords("jdbc:mysql://address=(host=h)(user=pwt)(password=Ab,cd9XyZ)/test").toString(), "[Ab,cd9XyZ]");
        for (String pw : new String[] {"Ab,cd9", "Ab'cd9", "Ab\"cd9", "Ab;cd9XyZ", "Rt)yu1Op"}) {
            eq(JFiltraDbClient.mask("jdbc:x://h/db?user=a&password=" + pw + "&ssl=1"), "jdbc:x://h/db?user=a&password=****&ssl=1");
            eq(JFiltraDbClient.urlPasswords("jdbc:x://h/db?password=" + pw + "&ssl=1").toString(), "[" + pw + "]");
        }
        // ) ends the value only in (key=...) form; passwords with ) belong in URL parameters
        for (String pw : new String[] {"Ab,cd9", "Ab'cd9", "Ab\"cd9", "Ab;cd9"}) {
            eq(JFiltraDbClient.mask("(user=a)(password=" + pw + ")/db"), "(user=a)(password=****)/db");
        }
        eq(JFiltraDbClient.mask("(user=a)(password=Rt)yu1Op)/db"), "(user=a)(password=****)yu1Op)/db");
        // JDBC URL secrets include spaces; diagnostic masking still ends at whitespace.
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h;password=Ab,c d;db=x").toString(), "[Ab,c d;db=x]");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h/db?password=Ab,c%20d&ssl=1").toString(), "[Ab,c%20d]");
        eq(JFiltraDbClient.mask("password=\"quoted secret\";db=x"), "password=****;db=x");
        eq(JFiltraDbClient.mask("password='quoted secret';db=x"), "password=****;db=x");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h?password=two words&x=1").toString(), "[two words]");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://(password=two words)/db").toString(), "[two words]");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h;password=\"quoted secret\";db=x").toString(), "[\"quoted secret\", quoted secret]");
        eq(JFiltraDbClient.safe("Driver rejected 'two words'", JFiltraDbClient.urlPasswords("jdbc:x://h?password=two words&x=1")),
                "Driver rejected '****'");
        eq(JFiltraDbClient.safe("Driver rejected 'quoted secret'", JFiltraDbClient.urlPasswords("jdbc:x://h;password=\"quoted secret\";db=x")),
                "Driver rejected '****'");
        eq(JFiltraDbClient.safe("Driver error: password=two words&x=1", JFiltraDbClient.urlPasswords("jdbc:x://h?password=two words&x=1")),
                "Driver error: password=****&x=1");
        eq(JFiltraDbClient.safe("Driver error: password=two words&x=1", java.util.Arrays.asList("two ", "two words")),
                "Driver error: password=****&x=1");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h?password=ab)cdef&x=1").toString(), "[ab)cdef]");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h?password=ab)cd ef&x=1").toString(), "[ab)cd ef]");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://(password=ab)cdef)/db").toString(), "[ab]");
        eq(JFiltraDbClient.safe("Driver rejected ab)cdef", JFiltraDbClient.urlPasswords("jdbc:x://h?password=ab)cdef&x=1")), "Driver rejected ****");
        // Partial quote then more text: whole value masked (C1b)
        eq(JFiltraDbClient.mask("jdbc:x://h/db?password=\"Wq8\"Er5&ssl=1"), "jdbc:x://h/db?password=****&ssl=1");
        eq(JFiltraDbClient.mask("jdbc:x://h/db?password='Fg6'Hj7&ssl=1"), "jdbc:x://h/db?password=****&ssl=1");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h/db?password=\"Wq8\"Er5&ssl=1").toString(), "[\"Wq8\"Er5]");
        // Multi-line / diagnostic text after password: kept (C1c)
        eq(JFiltraDbClient.mask("Wrong password: check db.user and db.password in config"),
                "Wrong password: **** db.user and db.password in config");
        eq(JFiltraDbClient.mask("ERROR: password=abc\nDetail: something useful"),
                "ERROR: password=****\nDetail: something useful");
        eq(JFiltraDbClient.mask("/etc/passwd: No such file"), "/etc/passwd: **** such file");
        eq(JFiltraDbClient.mask("password='' x"), "password=**** x");
        eq(JFiltraDbClient.urlPasswords("jdbc:x://h/db?password='a b'&ssl=1").toString(), "['a b', a b]");
        // Linear time for many unclosed braces (B2)
        StringBuilder many = new StringBuilder();
        for (int k = 0; k < 100000; k++) {
            many.append("password={&"); // & ends an unclosed value (C1b)
        }
        long t0 = System.nanoTime();
        String maskedMany = JFiltraDbClient.mask(many.toString());
        long ms = (System.nanoTime() - t0) / 1000000;
        System.out.println("100,000 unclosed password={ masked in " + ms + " ms");
        eq(String.valueOf(ms < 1000), "true");
        eq(maskedMany.substring(0, 22), "password=****&password");
        eq(maskedMany.length() + "", String.valueOf(100000 * "password=****&".length()));
        eq(JFiltraDbClient.mask("a password={x b password={y} c"), "a password=**** c");
        eq(JFiltraDbClient.mask("password={x}} pwd={y}"), "password=****");
        eq(JFiltraDbClient.mask("password={x}}} pwd={y}"), "password=**** pwd=****");
        eq(JFiltraDbClient.mask("db.password=abc;db_pwd=abc&(passwd:abc)"), "db.password=****&(passwd:****)");
        eq(JFiltraDbClient.mask("db.password=abc&db_pwd=abc&(passwd:abc)"), "db.password=****&db_pwd=****&(passwd:****)");
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
        eq(JFiltraDbClient.lockErrorMessage(lock, new java.nio.file.NoSuchFileException(lock.toString())),
                "Cannot create or lock spool lock file " + lock.toAbsolutePath()
                + ": it does not exist or is not a regular file.");
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
