package com.kommserver.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one folder everything komm-server writes lives in: {@code keys/}, {@code komm-postgres-data/}, {@code uploads/},
 * {@code logs/}, {@code sfu/}, the embedded Postgres binaries ({@code pg-runtime/}), {@code setup-token.txt} and
 * {@code installation-ports.properties}.
 * <p>
 * Chosen by, first match wins: the {@code KOMM_DATA_DIR} environment variable (a service or container that keeps the
 * data somewhere else), the {@code komm.data-dir} system property (an install made with jpack passes
 * {@code <install folder>/data}), else {@code data} in the working directory (a run from the IDE: the project's
 * {@code data/} folder).
 * <p>
 * Paths stored in the database (attachments, soundboard sounds) stay relative, as they always were, and are resolved
 * against this folder whenever a file is touched, so the data folder can be moved as a whole.
 */
public class DataDir {

    public static final String PROPERTY = "komm.data-dir";
    public static final String ENV = "KOMM_DATA_DIR";

    private static volatile Path root;

    /** Settles the folder, creates it, and publishes it as the {@code komm.data-dir} system property (the log config reads it). */
    public static Path init() {
        Path dir = root();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("The data folder " + dir + " cannot be created", e);
        }
        System.setProperty(PROPERTY, dir.toString());
        return dir;
    }

    public static Path root() {
        Path dir = root;
        if (dir == null) {
            synchronized (DataDir.class) {
                if (root == null) {
                    root = locate(System.getenv(ENV), System.getProperty(PROPERTY));
                }
                dir = root;
            }
        }
        return dir;
    }

    static Path locate(String fromEnv, String fromProperty) {
        String chosen = notBlank(fromEnv) ? fromEnv : notBlank(fromProperty) ? fromProperty : "data";
        return Path.of(chosen.trim()).toAbsolutePath().normalize();
    }

    /** A path inside the data folder; an absolute path is returned as it is. */
    public static Path resolve(String path) {
        return root().resolve(path);
    }

    public static Path resolve(Path path) {
        return root().resolve(path);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
