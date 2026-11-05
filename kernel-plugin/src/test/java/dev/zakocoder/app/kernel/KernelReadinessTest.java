package dev.zakocoder.app.kernel;

import org.junit.Test;
import static org.junit.Assert.*;

public class KernelReadinessTest {
    private boolean probe(int reply) throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2000);
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<?> remote = worker.submit(() -> {
                    try (java.net.Socket client = server.accept()) {
                        client.setSoTimeout(2000);
                        if (reply == -1) return null;
                        java.io.DataInputStream input = new java.io.DataInputStream(client.getInputStream());
                        assertEquals(5, input.readUnsignedByte());
                        assertEquals(1, input.readUnsignedByte());
                        assertEquals(2, input.readUnsignedByte());
                        client.getOutputStream().write(new byte[] {5, 2});
                        assertEquals(1, input.readUnsignedByte());
                        byte[] user = new byte[input.readUnsignedByte()]; input.readFully(user);
                        byte[] pass = new byte[input.readUnsignedByte()]; input.readFully(pass);
                        assertEquals("fixture-user", new String(user, java.nio.charset.StandardCharsets.UTF_8));
                        assertEquals("fixture-pass", new String(pass, java.nio.charset.StandardCharsets.UTF_8));
                        client.getOutputStream().write(new byte[] {1, (byte) reply});
                    }
                    return null;
                });
                boolean ready = KernelService.socksReady(server.getLocalPort(), "fixture-user", "fixture-pass");
                remote.get(3, java.util.concurrent.TimeUnit.SECONDS);
                return ready;
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void authenticatesTheExpectedSocksListener() throws Exception { assertTrue(probe(0)); }
    @Test public void rejectsAuthenticationFailure() throws Exception { assertFalse(probe(1)); }
    @Test public void tcpConnectionAloneIsNotReady() throws Exception { assertFalse(probe(-1)); }
}
