package com.kommserver.launcher;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where this komm-server installation is and what it holds. Everything is in one folder:
 *
 * <pre>
 * &lt;install&gt;/
 *   komm-server(.exe)          the jpack stub: starts the server (the service runs it) and this tool
 *   jpack.json                 what is installed (version, parts)
 *   app/komm-server-X.jar      the server
 *   launcher/                  this tool
 *   data/                      everything the server writes: keys/, komm-postgres-data/, uploads/, logs/, ...
 * </pre>
 *
 * The data folder can live elsewhere: {@code KOMM_DATA_DIR} (set by the {@code kommserver} wrapper and the service).
 */
@Getter
@Builder
@ToString
public class Install {

    /** The install folder. */
    private final Path home;
    /** The jpack stub that starts the server (and this tool). */
    private final Path stub;
    /** Everything the server writes. */
    private final Path dataDir;
    /** The version the stub started us as; {@link #versionOnDisk()} is the one in {@code jpack.json} right now. */
    private final String version;

    /** The installation this tool was started from, by the jpack stub. */
    public static Install current() {
        String home = System.getenv("JPACK_HOME");
        Path homeDir = home != null && !home.isBlank() ? Path.of(home).toAbsolutePath().normalize() : homeFromJarLocation();
        if (homeDir == null) {
            throw new IllegalStateException("kommserver has to be started through the installed komm-server "
                    + "(the 'kommserver' command), not with java -jar.");
        }
        String exe = System.getenv("JPACK_EXE");
        Path stub = exe != null && !exe.isBlank() ? Path.of(exe) : homeDir.resolve(Platform.isWindows() ? "komm-server.exe" : "komm-server");
        String dataEnv = System.getenv("KOMM_DATA_DIR");
        Path data = dataEnv != null && !dataEnv.isBlank() ? Path.of(dataEnv.trim()).toAbsolutePath().normalize() : homeDir.resolve("data");
        String version = System.getenv("JPACK_VERSION");
        return Install.builder()
                .home(homeDir)
                .stub(stub)
                .dataDir(data)
                .version(version == null ? "unknown" : version)
                .build();
    }

    /**
     * Started without the stub (the Windows tray, from a Startup shortcut that runs {@code javaw -jar}): this jar is
     * {@code <install>/launcher/komm-server-launcher.jar}, so the install folder is two levels up.
     */
    private static Path homeFromJarLocation() {
        try {
            Path jar = Path.of(Install.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = jar.getParent() == null ? null : jar.getParent().getParent();
            return candidate != null && Files.exists(candidate.resolve("jpack.json")) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The version in {@code jpack.json} right now (it changes when an update is swapped in or rolled back). */
    public String versionOnDisk() {
        try {
            Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(home.resolve("jpack.json")));
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** The HTTPS port the server listens on: the one the hub assigned at activation, else the default. */
    public int serverPort() {
        Path ports = dataDir.resolve("installation-ports.properties");
        if (Files.isReadable(ports)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(ports)) {
                props.load(in);
                return Integer.parseInt(props.getProperty("server.port", "8090").trim());
            } catch (IOException | NumberFormatException e) {
                // fall through to the default
            }
        }
        return 8090;
    }

    /** Activated once the hub's certificate is on disk. */
    public boolean activated() {
        return Files.exists(dataDir.resolve("keys").resolve("tls-cert.pem"));
    }

    public Path setupTokenFile() {
        return dataDir.resolve("setup-token.txt");
    }

    public Path serverLog() {
        return dataDir.resolve("logs").resolve("komm.log");
    }

    /**
     * What a server that cannot finish its activation says in {@code activation.status}: the hub rejected the verification
     * code ({@code rejected}), or could not be reached ({@code unreachable}). {@code code} is the hub's machine-readable
     * reason, when it sent one.
     */
    @Getter
    @Builder
    @ToString
    public static class ActivationStatus {

        private final String state;
        private final String code;
        private final String message;

        public boolean rejected() {
            return "rejected".equals(state);
        }
    }

    /** A waiting server rewrites its status every few seconds; one that was not touched for this long is a dead server's. */
    private static final long STATUS_FRESH_MS = 20_000;

    /** What the running server says about its activation right now, or null when it is not waiting (or the file is stale). */
    public ActivationStatus activationStatus() {
        Path file = dataDir.resolve("activation.status");
        if (!Files.isReadable(file)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            long updated = Long.parseLong(props.getProperty("updated", "0").trim());
            if (System.currentTimeMillis() - updated > STATUS_FRESH_MS) {
                return null;
            }
            return ActivationStatus.builder()
                    .state(props.getProperty("state", ""))
                    .code(props.getProperty("code"))
                    .message(props.getProperty("message"))
                    .build();
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Forgets what an earlier run said, before starting the server, so that only its own word counts. */
    public void clearActivationStatus() {
        try {
            Files.deleteIfExists(dataDir.resolve("activation.status"));
        } catch (IOException e) {
            // best effort: a stale file is also ignored once it is older than STATUS_FRESH_MS
        }
    }
}
