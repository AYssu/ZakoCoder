package dev.zakocoder.app.kernel;

/**
 * Plugin-side process keeper for the optional kernel plugin.
 *
 * Security/lifecycle contract:
 * - every AIDL method is reachable only through the signature-level service permission; methods
 *   also verify the caller permission while the Binder transaction is active;
 * - exactly one Xray process; start replaces the previous one, stop kills it and deletes the
 *   plaintext config from the plugin's private cache;
 * - the executable is the packaged `libzakocoder_xray.so` from this APK's nativeLibraryDir (the app
 *   cannot execute files from writable data on API 29+); the child gets SSL_CERT_DIR so TLS nodes
 *   can use Android system CAs;
 * - readiness is a real SOCKS5 RFC 1929 handshake with the per-start random credential;
 * - failures return a stable category, never the config, credentials or raw log lines.
 */
public final class KernelService extends android.app.Service {
    private static final String PERMISSION = "dev.zakocoder.app.permission.KERNEL";
    private static final long READY_TIMEOUT_MS = 10_000L;
    private static final long READY_POLL_MS = 100L;
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int HANDSHAKE_TIMEOUT_MS = 800;
    private static final String SYSTEM_CA_DIR = "/system/etc/security/cacerts";

    private final Object guard = new Object();
    private Process process;
    private java.io.File configFile;
    private int runningPort;
    private boolean failed;
    private long processGeneration;

    private final IKernelService.Stub binder = new IKernelService.Stub() {
        @Override
        public String start(String configJson, int port, String socksUser, String socksPass,
                            String nodeId, String nodeName) {
            return KernelService.this.start(configJson, port, socksUser, socksPass, nodeId);
        }

        @Override
        public void stop() {
            if (checkCallingPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            stopLocked();
        }

        @Override
        public String status() {
            if (checkCallingPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "{\"state\":\"failed\",\"port\":0,\"contractVersion\":1}";
            }
            final Process snapshot;
            final int port;
            final boolean wasFailed;
            final long generation;
            synchronized (guard) {
                snapshot = process;
                port = runningPort;
                wasFailed = failed;
                generation = processGeneration;
            }
            boolean alive = snapshot != null && snapshot.isAlive();
            String state = alive ? "running" : snapshot != null || wasFailed ? "failed" : "stopped";
            Integer exitCode = null;
            if (snapshot != null && !alive) {
                try { exitCode = snapshot.exitValue(); } catch (IllegalThreadStateException ignored) { }
            }
            String summary = KernelLogSummary.read(new java.io.File(getCacheDir(), "kernel/kernel.log"));
            synchronized (guard) {
                // A log can be replaced by a concurrent start. Never attribute it to the old process.
                if (generation != processGeneration) summary = "snapshot_changed";
            }
            return "{\"state\":\"" + state + "\",\"port\":" + (alive ? port : 0)
                    + ",\"contractVersion\":1,\"diagnosticVersion\":2,\"processAlive\":" + alive
                    + ",\"exitCode\":" + (exitCode == null ? "null" : exitCode)
                    + ",\"errorCategories\":\"" + summary + "\"}";
        }

        @Override
        public int contractVersion() {
            return 1;
        }
    };

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        // Do not checkCallingPermission here: onBind runs as the system caller. The service
        // permission is enforced by the framework before bind; AIDL methods re-check per call.
        return binder;
    }

    private String start(String configJson, int port, String user, String pass, String nodeId) {
        if (checkCallingPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "{\"ok\":false,\"port\":0,\"error\":\"denied\"}";
        }
        if (configJson == null || configJson.length() < 16 || configJson.length() > 512 * 1024
                || port < 1 || port > 65535 || user == null || pass == null) {
            return "{\"ok\":false,\"port\":0,\"error\":\"invalid\"}";
        }
        synchronized (guard) {
            stopLocked();
            failed = false;
            java.io.File executable = new java.io.File(getApplicationInfo().nativeLibraryDir,
                    "libzakocoder_xray.so");
            if (!executable.isFile()) {
                failed = true;
                return "{\"ok\":false,\"port\":0,\"error\":\"binary_missing\"}";
            }
            try {
                java.io.File directory = new java.io.File(getCacheDir(), "kernel");
                if (!directory.exists() && !directory.mkdirs()) {
                    failed = true;
                    return "{\"ok\":false,\"port\":0,\"error\":\"io\"}";
                }
                cleanupStale(directory);
                String safeId = String.valueOf(nodeId).replaceAll("[^A-Za-z0-9_-]", "").substring(
                        0, Math.min(40, String.valueOf(nodeId).replaceAll("[^A-Za-z0-9_-]", "").length()));
                if (safeId.isEmpty()) safeId = "node";
                java.io.File target = new java.io.File(directory, "config-" + safeId + ".json");
                java.nio.file.Files.write(target.toPath(), configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                configFile = target;
                ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "run", "-c", target.getAbsolutePath());
                builder.directory(directory);
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File(directory, "kernel.log")));
                builder.environment().put("SSL_CERT_DIR", SYSTEM_CA_DIR);
                Process started = builder.start();
                process = started;
                runningPort = port;
                long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (!started.isAlive()) {
                        stopLocked();
                        return "{\"ok\":false,\"port\":0,\"error\":\"start_failed\"}";
                    }
                    if (socksReady(port, user, pass)) {
                        return "{\"ok\":true,\"port\":" + port + ",\"error\":\"\"}";
                    }
                    try {
                        Thread.sleep(READY_POLL_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                stopLocked();
                return "{\"ok\":false,\"port\":0,\"error\":\"timeout\"}";
            } catch (Exception error) {
                stopLocked();
                return "{\"ok\":false,\"port\":0,\"error\":\"start_failed\"}";
            }
        }
    }

    private void stopLocked() {
        synchronized (guard) {
            processGeneration++;
            Process current = process;
            process = null;
            runningPort = 0;
            if (configFile != null) {
                //noinspection ResultOfMethodCallIgnored
                configFile.delete();
                configFile = null;
            }
            if (current != null) {
                try {
                    current.destroy();
                    if (!current.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) current.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    current.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void cleanupStale(java.io.File directory) {
        java.io.File[] files = directory.listFiles();
        if (files == null) return;
        for (java.io.File file : files) {
            if (file.getName().startsWith("config-") || file.getName().equals("kernel.log")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** Only the expected authenticated SOCKS listener counts as locally ready. */
    static boolean socksReady(int port, String user, String pass) {
        byte[] username = user.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] password = pass.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (username.length == 0 || username.length > 255 || password.length == 0 || password.length > 255) return false;
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            java.io.DataInputStream input = new java.io.DataInputStream(socket.getInputStream());
            java.io.OutputStream output = socket.getOutputStream();
            output.write(new byte[] {5, 1, 2});
            output.flush();
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) return false;
            output.write(1);
            output.write(username.length);
            output.write(username);
            output.write(password.length);
            output.write(password);
            output.flush();
            return input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0;
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        stopLocked();
        super.onDestroy();
    }
}
