package dev.zakocoder.app.kernel;

import org.junit.Test;
import static org.junit.Assert.*;

public class KernelLogSummaryTest {
    @Test public void categoriesNeverIncludeRawEndpointOrCredentials() {
        assertEquals("reality_auth_failed,connection_reset", KernelLogSummary.classify(
                "REALITY: received real certificate (potential MITM or redirection)\n"
                + "secret-token@example.com:443 connection reset by peer"));
    }

    @Test public void ordinaryOutputDoesNotClaimConnectionSuccess() {
        assertEquals("none_observed", KernelLogSummary.classify("Xray started\naccepted tcp:example.com:443"));
        assertEquals("network_timeout,dns_failed", KernelLogSummary.classify("i/o timeout; lookup: no such host"));
    }

    @Test public void androidGoLoopbackResolverFailureIsClassifiedAsDns() {
        assertEquals("connection_refused,dns_failed", KernelLogSummary.classify(
                "lookup node.example on [::1]:53: read udp [::1]:40000->[::1]:53: read: connection refused"));
    }

    @Test public void onlyBoundedTailIsRead() throws Exception {
        java.io.File file = java.io.File.createTempFile("kernel-summary", ".log");
        try {
            java.nio.file.Files.write(file.toPath(), ("received real certificate\n" + "x".repeat(70_000)
                    + "\nconnection refused").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("connection_refused", KernelLogSummary.read(file));
        } finally { file.delete(); }
        assertEquals("unavailable", KernelLogSummary.read(file));
    }
}
