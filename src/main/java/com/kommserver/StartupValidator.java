package com.kommserver;

import com.kommserver.bootstrap.PreBootActivation;
import com.kommserver.config.DataDir;
import com.kommserver.model.db.Installation;
import com.kommserver.model.dto.response.InstallationValidationResponse;
import com.kommserver.repository.InstallationRepository;
import com.kommserver.security.JwtUtil;
import com.kommserver.security.TlsMaterialService;
import com.kommserver.sfu.SfuLauncher;
import com.kommserver.websocket.HubConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator implements ApplicationRunner {

    private final HubConnector hubConnector;
    private final InstallationRepository installationRepository;
    private final JwtUtil jwtUtil;
    private final SfuLauncher sfuLauncher;
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

        boolean restartNeeded = false;

        if (installations.isEmpty()) {
            // A new installation was activated with the hub before this context started (PreBootActivation), so the
            // certificate and the ports were already in place when Tomcat was configured. All that is left is to
            // record who we are.
            InstallationValidationResponse activation = PreBootActivation.readPending();
            if (activation == null) {
                throw new IllegalStateException(
                        "This server is not activated and was given no verification code. Put the code from the "
                                + "Komm client in " + DataDir.resolve(setupTokenFile).toAbsolutePath()
                                + " (`kommserver install-service` does that) or set "
                                + PreBootActivation.SETUP_TOKEN_ENV + ", then start it again.");
            }
            restartNeeded = recordActivation(activation);
            installations = installationRepository.findAll();
        } else {
            // Nothing left to import (an earlier boot recorded it, then stopped before removing the file).
            PreBootActivation.clearPending();
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
        restartNeeded |= tlsMaterialService.ensureCertificateFile(installation.getCertificate());
        if (restartNeeded) {
            restartToApplyConfiguration();
            return;
        }
        log.info("Serving mode: {}", tlsMaterialService.isServingTls() ? "HTTPS/WSS (hub-CA TLS)" : "plain HTTP/WS");

        hubConnector.connect();
        sfuLauncher.start();   // ← start SFU after normal boot
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Turns the hub's answer into the installation record.
     *
     * @return true when the certificate or the ports had to be put back on disk now, which only happens if they were
     *         lost between the activation and this boot: this boot did not have them, so it has to restart once
     */
    private boolean recordActivation(InstallationValidationResponse activation) {
        installationRepository.save(Installation.builder()
                .installationId(activation.getInstallationId())
                .installationName(activation.getInstallationName())
                .certificate(activation.getCertificate())
                .hubPublicKey(activation.getHubPublicKey())
                .ownerId(activation.getOwnerId())
                .publicAddress(activation.getPublicAddress())
                .build());
        jwtUtil.setHubPublicKeyFromPem(activation.getHubPublicKey());

        boolean certWritten = tlsMaterialService.ensureCertificateFile(activation.getCertificate());
        boolean portsWritten = PreBootActivation.writePorts(activation);
        PreBootActivation.clearPending();
        log.info("Installation '{}' recorded", activation.getInstallationName());
        return certWritten || portsWritten;
    }

    // Non-zero and distinctive on purpose: systemd's Restart=always restarts on any
    // exit code, but WinSW's <onfailure> only fires on a non-zero one — this needs to
    // trigger a restart under both, so a plain System.exit(0) wouldn't reliably work
    // on Windows. Only reached now when the TLS certificate or the ports have to be
    // put on disk after the fact (an upgrade from a pre-TLS build, or files lost after
    // activation); a normal activation no longer restarts.
    private static final int RESTART_REQUESTED_EXIT_CODE = 75;

    private void restartToApplyConfiguration() {
        log.info("Restarting to apply the updated TLS/port configuration...");
        com.kommserver.config.JpackSupport.sayStarted(); // a planned restart, not a version that fails to start
        System.exit(RESTART_REQUESTED_EXIT_CODE);
    }
}
