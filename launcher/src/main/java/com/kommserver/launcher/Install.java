package com.kommserver.launcher;

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
public record Install(Path home, Path stub, Path dataDir, String version) {

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
        return new Install(homeDir, stub, data, version == null ? "unknown" : version);
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
}
