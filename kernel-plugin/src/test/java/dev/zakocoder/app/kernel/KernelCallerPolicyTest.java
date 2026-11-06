package dev.zakocoder.app.kernel;

import org.junit.Test;
import static org.junit.Assert.*;

public class KernelCallerPolicyTest {
    @Test public void fullInternalServiceAllowsOwnUidWithoutPermission() {
        assertTrue(KernelCallerPolicy.allowed(1234, 1234, null, false));
    }
    @Test public void internalServiceNeverAcceptsOtherUidWithoutDeclaredPermission() {
        assertFalse(KernelCallerPolicy.allowed(1235, 1234, null, true));
        assertFalse(KernelCallerPolicy.allowed(1235, 1234, "", true));
    }
    @Test public void liteExportedServiceRequiresGrantedSignaturePermission() {
        assertFalse(KernelCallerPolicy.allowed(1235, 1234, "dev.zakocoder.app.permission.KERNEL", false));
        assertTrue(KernelCallerPolicy.allowed(1235, 1234, "dev.zakocoder.app.permission.KERNEL", true));
    }
}
