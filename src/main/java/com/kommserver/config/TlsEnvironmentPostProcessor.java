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

/**
 * Enables HTTPS on the embedded Tomcat whenever the hub-issued certificate and the
 * installation's private key are present on disk (both PEM). Registered via
 * META-INF/spring.factories.
 * <p>
 * On a fresh installation the certificate does not exist yet — the JAR boots plain
 * HTTP, activates against the hub (which signs the CSR), persists the certificate,
 * and serves TLS from the next boot onward. App clients trust the hub CA, so no
 * domain name or third-party certificate is needed.
 */
public class TlsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        // After config-data processing so application*.properties values are visible
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // An operator who configured SSL explicitly (e.g. their own reverse-proxied
        // setup or custom keystore) wins over the automatic hub-CA material.
        if (environment.containsProperty("server.ssl.enabled")
                || environment.containsProperty("server.ssl.certificate")
                || environment.containsProperty("server.ssl.key-store")) {
            return;
        }

        Path cert = Path.of(environment.getProperty("komm.tls.cert-file", "keys/tls-cert.pem"));
        Path key = Path.of(environment.getProperty("jwt.keys.private", "keys/komm-private.pem"));
        if (!Files.isReadable(cert) || !Files.isReadable(key)) {
            return; // not activated yet — plain HTTP until the certificate exists
        }

        Map<String, Object> props = new HashMap<>();
        props.put("server.ssl.enabled", "true");
        props.put("server.ssl.certificate", cert.toUri().toString());
        props.put("server.ssl.certificate-private-key", key.toUri().toString());
        environment.getPropertySources().addFirst(new MapPropertySource("komm-tls", props));
    }
}
