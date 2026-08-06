package com.kommserver.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Applies the ports chosen on the hub dashboard at installation creation (written to disk
 * by {@link PortConfigService} at activation time) — same "takes effect after a restart"
 * shape as {@link TlsEnvironmentPostProcessor}, registered alongside it in spring.factories.
 */
public class PortEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path path = Path.of(environment.getProperty("komm.ports-file", "installation-ports.properties"));
        if (!Files.isReadable(path)) {
            return;
        }

        Properties fileProps = new Properties();
        try (var in = Files.newInputStream(path)) {
            fileProps.load(in);
        } catch (Exception e) {
            return;
        }
        if (fileProps.isEmpty()) {
            return;
        }

        Map<String, Object> props = new HashMap<>();
        fileProps.forEach((k, v) -> props.put((String) k, v));
        environment.getPropertySources().addFirst(new MapPropertySource("komm-ports", props));
    }
}
