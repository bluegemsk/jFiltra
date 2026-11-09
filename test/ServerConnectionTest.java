/* PolyForm Noncommercial License 1.0.0. Regression checks for accept recovery and disconnect logs. */
package server;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerConnectionTest {
    private static class FlakySocket extends ServerSocket {
        final ServerSocket delegate;
        volatile int attempts;
        FlakySocket(ServerSocket delegate) throws IOException { this.delegate = delegate; }
        @Override public Socket accept() throws IOException {
            if (++attempts == 1) throw new IOException("injected transient accept failure");
            return delegate.accept();
        }
        @Override public void close() throws IOException { delegate.close(); super.close(); }
    }
    public static void main(String[] args) throws Exception {
        JFiltraServer server = new JFiltraServer(args[0]);
        Thread thread = new Thread(server::start);
        thread.start();
        Field field = JFiltraServer.class.getDeclaredField("serverSocket");
        field.setAccessible(true);
        long until = System.currentTimeMillis() + 5000;
        ServerSocket original;
        while ((original = (ServerSocket) field.get(server)) == null) {
            if (System.currentTimeMillis() > until) throw new AssertionError("Server did not start");
            Thread.sleep(10);
        }
        FlakySocket flaky = new FlakySocket(original);
        field.set(server, flaky);
        int port = original.getLocalPort();
        try {
            // Release an accept already blocked on the original socket; then inject one failure.
            try (Socket probe = new Socket("127.0.0.1", port)) { }
            until = System.currentTimeMillis() + 5000;
            while (flaky.attempts < 2) {
                if (System.currentTimeMillis() > until) throw new AssertionError("accept did not recover");
                Thread.sleep(10);
            }
            try (Socket partial = new Socket("127.0.0.1", port)) {
                partial.getOutputStream().write(0); // Incomplete UTF header: EOF, not a health probe.
                partial.shutdownOutput();
            }
            try (Socket timeout = new Socket("127.0.0.1", port)) {
                timeout.getOutputStream().write(0);
                timeout.setSoTimeout(4000);
                if (!"ERROR: Transfer failed".equals(new java.io.DataInputStream(timeout.getInputStream()).readUTF()))
                    throw new AssertionError("Expected existing transfer-failed reply on timeout");
            }
            try (Socket reset = new Socket("127.0.0.1", port)) {
                reset.getOutputStream().write(0);
                Thread.sleep(100);
                reset.setSoLinger(true, 0);
            }
            Thread.sleep(200);
        } finally {
            server.stop();
            thread.join(5000);
        }
        if (thread.isAlive()) throw new AssertionError("shutdown did not stop accept loop");
        System.out.println("ServerConnectionTest: all passed");
    }
}
