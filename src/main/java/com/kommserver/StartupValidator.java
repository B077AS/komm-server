package com.kommserver;

import com.kommserver.model.db.Installation;
import com.kommserver.repository.InstallationRepository;
import com.kommserver.security.TlsMaterialService;
import com.kommserver.service.ActivationService;
import com.kommserver.sfu.SfuLauncher;
import com.kommserver.websocket.HubConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator implements ApplicationRunner {

    private final HubConnector hubConnector;
    private final InstallationRepository installationRepository;
    private final ActivationService activationService;
    private final SfuLauncher sfuLauncher;   // ← new
    private final TlsMaterialService tlsMaterialService;

    @Value("${server.port}")
    private String serverPort;

    @Value("${komm.setup-token.file:setup-token.txt}")
    private String setupTokenFile;

    @Override
    public void run(@NonNull ApplicationArguments args) {
        List<Installation> installations = installationRepository.findAll();

        if (installations.size() > 1) {
            throw new IllegalStateException(
                    "Data integrity error: found " + installations.size() +
                            " installation records, expected at most 1.");
        }

        if (installations.isEmpty()) {
            log.info("No installation found, triggering setup flow...");
            String setupToken = readSetupTokenFromFile();
            if (activationService.activateInstallation(setupToken)) {
                deleteSetupTokenFile();
                // TLS material and any hub-chosen ports were just written to disk by
                // activateInstallation() — this boot already bound to plain HTTP and
                // the default ports before either existed, so it can't pick them up
                // itself. Restart now rather than serve this boot in the wrong mode:
                // that's the whole point of running under a supervised OS service
                // (Restart=always / WinSW onfailure) instead of a bare `java -jar`.
                restartToApplyConfiguration();
            }
            return;
        }

        // Exactly 1 record — validate it
        Installation installation = installations.get(0);
        if (installation.getInstallationName() == null ||
                installation.getInstallationName().isBlank()) {
            throw new IllegalStateException(
                    "Corrupt installation record: some fields are missing. " +
                            "Either all fields must be present or no record should exist.");
        }

        log.debug("Installation check passed: [{}] on port {}",
                installation.getInstallationName(), serverPort);

        // Upgrades from pre-TLS builds: the certificate exists only in the DB — put it
        // on disk so the next boot serves HTTPS (see TlsEnvironmentPostProcessor).
        if (tlsMaterialService.ensureCertificateFile(installation.getCertificate())) {
            restartToApplyConfiguration();
            return;
        }
        log.info("Serving mode: {}", tlsMaterialService.isServingTls() ? "HTTPS/WSS (hub-CA TLS)" : "plain HTTP/WS");

        hubConnector.connect();
        sfuLauncher.start();   // ← start SFU after normal boot
    }

    // ─────────────────────────────────────────────────────────────────────────

    // Non-zero and distinctive on purpose: systemd's Restart=always restarts on any
    // exit code, but WinSW's <onfailure> only fires on a non-zero one — this needs to
    // trigger a restart under both, so a plain System.exit(0) wouldn't reliably work
    // on Windows.
    private static final int RESTART_REQUESTED_EXIT_CODE = 75;

    private void restartToApplyConfiguration() {
        log.info("Restarting to apply the updated TLS/port configuration...");
        System.exit(RESTART_REQUESTED_EXIT_CODE);
    }

    // The server jar is now a generic, unmodified build (see komm-server-launcher) — it
    // no longer carries a per-installation setup token in its own manifest. The launcher's
    // `kommserver install-service` prompts for the verification code shown on the hub
    // dashboard and writes it here before the service is ever started.
    private String readSetupTokenFromFile() {
        Path path = Path.of(setupTokenFile);
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                    "No setup token found at " + path.toAbsolutePath() + ". If you're using " +
                            "komm-server-launcher, run `kommserver install-service` and paste the " +
                            "verification code.");
        }
        try {
            String token = Files.readString(path).trim();
            if (token.isEmpty()) {
                throw new IllegalStateException("Setup token file " + path.toAbsolutePath() + " is empty");
            }
            return token;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read setup token file: " + path.toAbsolutePath(), e);
        }
    }

    private void deleteSetupTokenFile() {
        try {
            Files.deleteIfExists(Path.of(setupTokenFile));
        } catch (IOException e) {
            log.warn("Failed to delete setup token file after activation: {}", e.getMessage());
        }
    }
}