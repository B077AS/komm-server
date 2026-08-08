package com.kommserver.config;

import com.kommserver.model.dto.response.InstallationValidationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Ports chosen in the Komm client when the installation was created arrive in the
 * activation response now (see {@link InstallationValidationResponse}) rather than being
 * baked into a downloaded jar — the jar this server runs is a generic, unmodified build,
 * same reasoning as {@link com.kommserver.security.TlsMaterialService} for the hub-issued
 * certificate. Written to disk here; {@link PortEnvironmentPostProcessor} applies them at
 * the next boot — this boot has already bound to whatever was configured at startup.
 */
@Slf4j
@Component
public class PortConfigService {

    @Value("${komm.ports-file:installation-ports.properties}")
    private String portsFile;

    /** @return true if the file was (re)written — meaning a restart is needed to bind the new ports. */
    public boolean applyPorts(InstallationValidationResponse response) {
        if (response.getPort() == null) {
            return false; // hub didn't send any (e.g. talking to an older hub) — nothing to do
        }
        Properties props = new Properties();
        props.setProperty("server.port", String.valueOf(response.getPort()));
        if (response.getSignalPort() != null) props.setProperty("sfu.signal-port", String.valueOf(response.getSignalPort()));
        if (response.getTcpPort() != null) props.setProperty("sfu.tcp-port", String.valueOf(response.getTcpPort()));
        if (response.getMediaPort() != null) props.setProperty("sfu.media-port", String.valueOf(response.getMediaPort()));

        Path path = Path.of(portsFile);
        try {
            if (Files.exists(path) && props.equals(loadExisting(path))) {
                return false;
            }
            try (var out = Files.newOutputStream(path)) {
                props.store(out, "Ports chosen in the Komm client at installation creation — do not edit by hand");
            }
            log.info("Port configuration written to {}", path.toAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("Failed to write port configuration: {}", e.getMessage());
            return false;
        }
    }

    private Properties loadExisting(Path path) {
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
    }
}
