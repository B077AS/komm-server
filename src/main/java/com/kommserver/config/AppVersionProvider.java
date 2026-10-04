package com.kommserver.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the version Maven stamped into {@code version.properties} at build time (see pom.xml
 * resource filtering) so {@link com.kommserver.websocket.HubConnector} can report it to the hub
 * on every connect, the same way {@link com.kommserver.security.TlsMaterialService} reports
 * TLS state - lets outages be correlated with a specific release.
 */
@Slf4j
@Component
public class AppVersionProvider {

    private final String version;

    public AppVersionProvider() {
        this.version = loadVersion();
    }

    public String getVersion() {
        return version;
    }

    private String loadVersion() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("version.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            return v != null && !v.isBlank() ? v : "unknown";
        } catch (IOException e) {
            log.warn("Failed to read version.properties: {}", e.getMessage());
            return "unknown";
        }
    }
}
