package com.kommserver.sfu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Extracts the platform-appropriate LiveKit binary from the JAR's resources,
 * writes a minimal config YAML, and manages the process lifecycle.
 * <p>
 * Call {@link #start()} once after authentication has completed.
 * The JVM shutdown hook ensures the child process is always killed cleanly.
 * <p>
 * Binary resources expected in src/main/resources/sfu/:
 * livekit_1.10.0_linux_amd64.tar.gz   (contains: livekit-server)
 * livekit_1.10.0_windows_amd64.zip    (contains: livekit-server.exe)
 */
@Slf4j
@Component
public class SfuLauncher {

    @Value("${sfu.signal-port}")
    private int signalPort;

    @Value("${sfu.media-port}")
    private int mediaPort;

    @Value("${sfu.tcp-port}")
    private int tcpPort;

    private static final String LINUX_RESOURCE = "/sfu/livekit_1.10.0_linux_amd64.tar.gz";
    private static final String WINDOWS_RESOURCE = "/sfu/livekit_1.10.0_windows_amd64.zip";

    private static final Path WORK_DIR = Path.of("sfu");

    private volatile Process livekitProcess;
    private volatile String apiKey;
    private volatile String apiSecret;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract, configure, and launch LiveKit. Safe to call exactly once.
     * Registers a JVM shutdown hook to kill the child process cleanly.
     */
    public synchronized void start() {
        if (livekitProcess != null && livekitProcess.isAlive()) {
            log.warn("[SFU] Already running (pid {})", livekitProcess.pid());
            return;
        }

        try {
            Files.createDirectories(WORK_DIR);

            // Kill any leftover process from a previous JVM run (e.g. crashed without cleanup)
            killStaleProcess();

            // Generate a fresh random key pair on every boot — no need to persist,
            // tokens are minted on demand by LiveKitTokenService using the same values.
            apiKey = "kommkey-" + randomHex(8);
            apiSecret = randomBase64(32);  // LiveKit requires >= 32 chars

            Path binaryPath = extractBinary();
            Path configPath = writeConfig();

            ProcessBuilder pb = new ProcessBuilder(
                    binaryPath.toAbsolutePath().toString(),
                    "--config", configPath.toAbsolutePath().toString()
            );
            pb.directory(WORK_DIR.toFile());
            pb.redirectErrorStream(true);   // merge stderr into stdout
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            livekitProcess = pb.start();
            log.info("[SFU] LiveKit started — pid={} signal=:{} udp=:{} tcp=:{}",
                    livekitProcess.pid(), signalPort, mediaPort, tcpPort);

            // Pipe LiveKit stdout → our logger in a daemon thread
            Thread logThread = new Thread(this::pipeOutput, "livekit-log");
            logThread.setDaemon(true);
            logThread.start();

            // Kill LiveKit when the JVM exits
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "livekit-shutdown"));

        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException("[SFU] Failed to start LiveKit: " + e.getMessage(), e);
        }
    }

    /**
     * Terminate the LiveKit child process. Idempotent.
     */
    public synchronized void stop() {
        Process p = livekitProcess;
        if (p == null || !p.isAlive()) return;
        log.info("[SFU] Stopping LiveKit (pid {})…", p.pid());
        p.destroy();
        try {
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        livekitProcess = null;
        log.info("[SFU] LiveKit stopped.");
    }

    public boolean isRunning() {
        return livekitProcess != null && livekitProcess.isAlive();
    }

    /**
     * API key to pass to {@link LiveKitTokenService}.
     */
    public String getApiKey() {
        ensureStarted();
        return apiKey;
    }

    /**
     * API secret to pass to {@link LiveKitTokenService}.
     */
    public String getApiSecret() {
        ensureStarted();
        return apiSecret;
    }

    /**
     * The WebSocket URL clients should connect to for media.
     */
    public String getSignalUrl() {
        return "ws://localhost:" + signalPort;
    }

    // ── Binary extraction ─────────────────────────────────────────────────────

    private Path extractBinary() throws IOException, InterruptedException {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exeName = isWindows ? "livekit-server.exe" : "livekit-server";
        Path dest = WORK_DIR.resolve(exeName);

        if (Files.exists(dest)) {
            log.debug("[SFU] Binary already present at {}", dest.toAbsolutePath());
            return dest;
        }

        String resource = isWindows ? WINDOWS_RESOURCE : LINUX_RESOURCE;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new FileNotFoundException("Resource not found in JAR: " + resource);

            if (isWindows) {
                extractFromZip(in, exeName, dest);
            } else {
                extractFromTarGz(in, exeName, dest);
                dest.toFile().setExecutable(true, false);
            }
        }

        log.debug("[SFU] Binary extracted to {}", dest.toAbsolutePath());
        return dest;
    }

    private void killStaleProcess() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exeName = isWindows ? "livekit-server.exe" : "livekit-server";
        try {
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("taskkill", "/F", "/IM", exeName)
                    : new ProcessBuilder("pkill", "-f", exeName);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            log.debug("[SFU] Killed stale {} process (if any)", exeName);
        } catch (Exception ignored) { }
    }

    private void extractFromZip(InputStream zipStream, String entryName, Path dest)
            throws IOException {
        // Write zip to a temp file first — ZipInputStream needs mark/reset or a seekable source
        Path tmp = Files.createTempFile(WORK_DIR, "livekit-", ".zip");
        try {
            Files.copy(zipStream, tmp, StandardCopyOption.REPLACE_EXISTING);
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmp.toFile())) {
                java.util.zip.ZipEntry entry = zf.getEntry(entryName);
                if (entry == null) throw new IOException("'" + entryName + "' not found inside zip");
                try (InputStream es = zf.getInputStream(entry)) {
                    Files.copy(es, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void extractFromTarGz(InputStream tgzStream, String entryName, Path dest)
            throws IOException {
        // Use the JDK's built-in gzip + manual tar parsing to avoid extra dependencies
        try (InputStream gz = new java.util.zip.GZIPInputStream(tgzStream);
             DataInputStream tar = new DataInputStream(new BufferedInputStream(gz))) {

            byte[] header = new byte[512];
            while (tar.read(header) == 512) {
                String name = readTarName(header);
                long size = readTarSize(header);

                if (size < 0) break;  // end-of-archive block

                if (name.equals(entryName) || name.endsWith("/" + entryName)) {
                    byte[] data = tar.readNBytes((int) size);
                    Files.write(dest, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return;
                }
                // Skip this entry (round up to 512-byte block boundary)
                long skip = roundUp512(size);
                tar.skipNBytes(skip);
            }
            throw new IOException("'" + entryName + "' not found inside tar.gz");
        }
    }

    private static String readTarName(byte[] header) {
        int end = 0;
        while (end < 100 && header[end] != 0) end++;
        return new String(header, 0, end).trim();
    }

    private static long readTarSize(byte[] header) {
        String oct = new String(header, 124, 12).trim();
        if (oct.isEmpty()) return -1;
        try {
            return Long.parseLong(oct, 8);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long roundUp512(long n) {
        return ((n + 511) / 512) * 512;
    }

    // ── Config generation ─────────────────────────────────────────────────────

    private Path writeConfig() throws IOException {
        /*
          active_level: 50
          min_percentile: 30
        */

        Path configPath = WORK_DIR.resolve("livekit.yaml");
        String yaml = """
        port: %d
        log_level: info
        keys:
          %s: "%s"
        rtc:
          udp_port: %d
          tcp_port: %d
          use_external_ip: false
          congestion_control:
            enabled: true
            # Never pause video tracks under perceived congestion. With non-simulcast
            # publishers (our Java client) the allocator has no lower layer to fall
            # back to, so "pause" is its only lever — which viewers experience as the
            # stream freezing every few seconds while the SFU probes and resumes.
            allow_pause: false
        audio:
          update_interval: 50
        """.formatted(
                signalPort,
                apiKey, apiSecret,
                mediaPort, tcpPort
        );
        Files.writeString(configPath, yaml, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.debug("[SFU] Config written to {}", configPath.toAbsolutePath());
        return configPath;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void pipeOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(livekitProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[LiveKit] {}", line);
            }
        } catch (IOException e) {
            if (livekitProcess != null && livekitProcess.isAlive()) {
                log.warn("[SFU] Log pipe error: {}", e.getMessage());
            }
        }
    }

    private void ensureStarted() {
        if (apiKey == null) throw new IllegalStateException("[SFU] LiveKit has not been started yet");
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String randomBase64(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}