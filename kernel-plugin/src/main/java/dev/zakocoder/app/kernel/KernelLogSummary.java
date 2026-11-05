package dev.zakocoder.app.kernel;

/** Bounded, allow-listed error categories. Raw kernel logs must never cross the Binder boundary. */
final class KernelLogSummary {
    private static final int MAX_BYTES = 64 * 1024;

    static String read(java.io.File file) {
        if (!file.isFile()) return "unavailable";
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            long length = input.length();
            input.seek(Math.max(0, length - MAX_BYTES));
            byte[] bytes = new byte[(int) Math.min(length, MAX_BYTES)];
            input.readFully(bytes);
            return classify(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
            return "unavailable";
        }
    }

    static String classify(String text) {
        String log = text.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> result = new java.util.ArrayList<>();
        if (log.contains("received real certificate") || log.contains("processed invalid connection")) result.add("reality_auth_failed");
        if (log.contains("certificate has expired") || log.contains("certificate is not yet valid")) result.add("certificate_time_invalid");
        if (log.contains("certificate signed by unknown authority")) result.add("certificate_untrusted");
        if (log.contains("connection refused")) result.add("connection_refused");
        if (log.contains("connection reset by peer")) result.add("connection_reset");
        if (log.contains("i/o timeout") || log.contains("context deadline exceeded")) result.add("network_timeout");
        if (log.contains("no such host") || log.contains("temporary failure in name resolution")
                || (log.contains("lookup ") && (log.contains("[::1]:53") || log.contains("127.0.0.1:53")))) result.add("dns_failed");
        if (log.contains("network is unreachable") || log.contains("no route to host")) result.add("network_unreachable");
        if (log.contains("failed to load config") || log.contains("failed to build config")) result.add("config_rejected");
        return result.isEmpty() ? "none_observed" : String.join(",", result);
    }
}
