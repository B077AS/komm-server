package com.kommserver.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the on-disk TLS material derived from the hub-issued certificate.
 * <p>
 * The installation's private key already lives in {@code keys/} (see {@link JwtUtil});
 * the hub-signed certificate is stored in the DB at activation time. Embedded Tomcat,
 * however, needs both as files at boot — {@link com.kommserver.config.TlsEnvironmentPostProcessor}
 * turns on {@code server.ssl.*} when the certificate PEM exists on disk. This service
 * keeps that file in sync with the DB copy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TlsMaterialService {

    private final Environment environment;

    @Value("${komm.tls.cert-file:keys/tls-cert.pem}")
    private String certFile;

    /** Whether this boot is actually serving HTTPS/WSS (decided at startup). */
    public boolean isServingTls() {
        return environment.getProperty("server.ssl.enabled", Boolean.class, false);
    }

    /**
     * Write the hub-issued certificate PEM to disk if missing or outdated.
     *
     * @return true if the file was (re)written — meaning a restart is needed
     *         before Tomcat picks it up.
     */
    public boolean ensureCertificateFile(String certificatePem) {
        if (certificatePem == null || certificatePem.isBlank()) return false;
        try {
            Path path = Path.of(certFile);
            if (Files.exists(path) && certificatePem.equals(Files.readString(path))) {
                return false;
            }
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, certificatePem);
            log.info("TLS certificate written to {}", path.toAbsolutePath());
            return true;
        } catch (Exception e) {
            log.error("Failed to write TLS certificate file: {}", e.getMessage());
            return false;
        }
    }
}
