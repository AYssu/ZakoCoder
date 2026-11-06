package dev.zakocoder.app.kernel;

/** Explicit same-UID access; cross-UID access always requires the service's declared permission. */
public final class KernelCallerPolicy {
    private KernelCallerPolicy() { }
    static boolean allowed(int callerUid, int serviceUid, String permission, boolean granted) {
        return callerUid == serviceUid || permission != null && !permission.isEmpty() && granted;
    }
}
