/*
 * Test JDBC driver for the DB client smoke test (N1): jdbc:hang: URLs give a
 * connection whose select fails like MySQL Connector/J out of memory
 * (SQLException "Java heap space") and whose rollback() and close() never return.
 * Runs the DB client with this driver registered.
 * This project is licensed under the PolyForm Noncommercial License 1.0.0.
 */
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

public class HangDriver implements Driver {
    public static void main(String[] args) throws Exception {
        DriverManager.registerDriver(new HangDriver());
        dbclient.JFiltraDbClient.main(args);
    }

    private static Object blockForever() {
        while (true) {
            try {
                Thread.sleep(60000);
            } catch (InterruptedException ignored) {
                // keep blocking, like a read without timeout
            }
        }
    }

    @Override
    public Connection connect(String url, Properties info) {
        if (!acceptsURL(url)) {
            return null;
        }
        final Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Statement.class}, (proxy, method, a) -> {
                    switch (method.getName()) {
                        case "executeQuery": throw new SQLException("Java heap space");
                        case "close": case "setFetchSize": case "setQueryTimeout": return null;
                        default: throw new SQLFeatureNotSupportedException(method.getName());
                    }
                });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, a) -> {
                    switch (method.getName()) {
                        case "createStatement": return statement;
                        case "rollback": case "close": return blockForever();
                        case "isValid": return Boolean.TRUE;
                        case "abort": System.out.println("HangDriver: abort called"); return null;
                        case "setAutoCommit": case "setTransactionIsolation": return null;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == a[0];
                        case "toString": return "HangConnection";
                        default: throw new SQLFeatureNotSupportedException(method.getName());
                    }
                });
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:hang:"); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
}
