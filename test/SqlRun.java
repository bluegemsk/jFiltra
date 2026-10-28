/*
 * Small JDBC helper for test/dbclient-smoke-test.sh: runs SQL statements, prints
 * query results, and inserts the fixed test rows. Not part of jFiltra itself.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 *
 * Usage: SqlRun <url> <user> <password> <command>...
 *   exec <sql>            run a statement (errors are fatal)
 *   try <sql>             run a statement, ignore errors (e.g. DROP TABLE)
 *   query <sql>           print the rows, columns separated by |, NULL as <null>
 *   rows <table>          insert the test rows into <table>
 */
import java.math.BigDecimal;
import java.sql.*;

public class SqlRun {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(args[0], args[1].isEmpty() ? null : args[1],
                args[1].isEmpty() ? null : args[2])) {
            for (int i = 3; i < args.length; i += 2) {
                String cmd = args[i], arg = args[i + 1];
                if (cmd.equals("exec")) {
                    try (Statement st = c.createStatement()) { st.execute(arg); }
                } else if (cmd.equals("try")) {
                    try (Statement st = c.createStatement()) { st.execute(arg); } catch (SQLException ignored) { }
                } else if (cmd.equals("query")) {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(arg)) {
                        int n = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            StringBuilder sb = new StringBuilder();
                            for (int k = 1; k <= n; k++) {
                                if (k > 1) sb.append('|');
                                String v = rs.getString(k);
                                sb.append(v == null ? "<null>" : v);
                            }
                            System.out.println(sb);
                        }
                    }
                } else if (cmd.equals("rows")) {
                    insertRows(c, arg);
                } else {
                    throw new IllegalArgumentException("Unknown command " + cmd);
                }
            }
        }
    }

    // id, name, amount, created, note, sent
    static void insertRows(Connection c, String table) throws SQLException {
        Object[][] rows = {
            {1, "plain", new BigDecimal("12.50"), Timestamp.valueOf("2026-01-02 03:04:05"), "x"},
            {2, "comma, \"quote\"", new BigDecimal("-0.01"), Timestamp.valueOf("2026-12-31 23:59:59.5"), "a;b"},
            {3, "multi\nline\tand tab \\ backslash", new BigDecimal("1000000.00"), null, "\r\n"},
            {4, "\u010d\u0161\u017e \u20ac \uD83D\uDE00", null, Timestamp.valueOf("2026-06-15 12:00:00"), null},
            {5, null, new BigDecimal("0.00"), Timestamp.valueOf("2026-01-01 00:00:00"), "  spaces  "},
        };
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                + " (id, name, amount, created, note, sent) VALUES (?, ?, ?, ?, ?, 0)")) {
            for (Object[] r : rows) {
                ps.setInt(1, (Integer) r[0]);
                ps.setString(2, (String) r[1]);
                if (r[2] == null) ps.setNull(3, Types.DECIMAL); else ps.setBigDecimal(3, (BigDecimal) r[2]);
                if (r[3] == null) ps.setNull(4, Types.TIMESTAMP); else ps.setTimestamp(4, (Timestamp) r[3]);
                ps.setString(5, (String) r[4]);
                ps.executeUpdate();
            }
        }
        if (!c.getAutoCommit()) c.commit();
    }
}
