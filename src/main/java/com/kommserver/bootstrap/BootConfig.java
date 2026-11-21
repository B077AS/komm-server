package com.kommserver.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * The few settings the pre-Spring activation needs, found the way Spring would find them: a {@code -D} system property,
 * then an environment variable ({@code api.url} is {@code API_URL}), then the jar's own {@code application.properties}.
 * No Spring is running yet, so there is no Environment to ask.
 */
class BootConfig {

    private static volatile Properties packaged;

    static String get(String key, String fallback) {
        String value = System.getProperty(key);
        if (isBlank(value)) {
            value = System.getenv(envName(key, false));
        }
        if (isBlank(value)) {
            value = System.getenv(envName(key, true));
        }
        if (isBlank(value)) {
            value = packaged().getProperty(key);
        }
        return isBlank(value) ? fallback : value.trim();
    }

    /** {@code komm.setup-token.file}: {@code KOMM_SETUP_TOKEN_FILE}, or Spring's own spelling {@code KOMM_SETUPTOKEN_FILE}. */
    private static String envName(String key, boolean dashesRemoved) {
        String name = dashesRemoved ? key.replace("-", "") : key;
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static Properties packaged() {
        Properties props = packaged;
        if (props == null) {
            props = new Properties();
            try (InputStream in = BootConfig.class.getResourceAsStream("/application.properties")) {
                if (in != null) {
                    props.load(in);
                }
            } catch (IOException e) {
                // the defaults at the call sites apply
            }
            packaged = props;
        }
        return props;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
