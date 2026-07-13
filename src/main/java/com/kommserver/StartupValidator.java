package com.kommserver;

import com.kommserver.model.db.Installation;
import com.kommserver.repository.InstallationRepository;
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

import java.io.InputStream;
import java.util.List;
import java.util.jar.Manifest;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator implements ApplicationRunner {

    private final HubConnector hubConnector;
    private final InstallationRepository installationRepository;
    private final ActivationService activationService;
    private final SfuLauncher sfuLauncher;   // ← new

    @Value("${server.port}")
    private String serverPort;

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
            String setupToken = readSetupTokenFromManifest();
            if (activationService.activateInstallation(setupToken)) {
                hubConnector.connect();
                sfuLauncher.start();  // ← start SFU after fresh activation
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

        hubConnector.connect();
        sfuLauncher.start();   // ← start SFU after normal boot
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String readSetupTokenFromManifest() {
        try (InputStream is = getClass().getResourceAsStream("/META-INF/kommserver.mf")) {
            if (is == null)
                throw new IllegalStateException("kommserver.mf not found in classpath");
            Manifest manifest = new Manifest(is);
            String token = manifest.getMainAttributes().getValue("Kommserver-Setup-Token");
            if (token == null)
                throw new IllegalStateException("Setup token not found in manifest");
            return token;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}