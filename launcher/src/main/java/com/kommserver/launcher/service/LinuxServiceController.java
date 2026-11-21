package com.kommserver.launcher.service;

import com.kommserver.launcher.Install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A systemd unit that runs the installation's jpack stub. systemd sends SIGTERM to every process of the unit on stop, so
 * the server shuts down cleanly (embedded Postgres included) even though the stub is the main process.
 */
public class LinuxServiceController implements ServiceController {

    static final Path UNIT_FILE = Path.of("/etc/systemd/system/" + NAME + ".service");

    @Override
    public void install(Install install) {
        // JAVA_HOME pins the Java this tool runs on (the one the stub found when the service was installed), so a second
        // Java installed later, or a changed default, does not silently switch the server's Java.
        String unit = """
                # Written by `kommserver install-service`. Run it again rather than editing this file.
                [Unit]
                Description=komm-server (self-hosted komm community server)
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                ExecStart=%s
                WorkingDirectory=%s
                Environment=KOMM_DATA_DIR=%s
                Environment=JAVA_HOME=%s
                Restart=always
                RestartSec=2
                SuccessExitStatus=143
                TimeoutStopSec=60
                StandardOutput=journal
                StandardError=journal

                [Install]
                WantedBy=multi-user.target
                """.formatted(install.getStub(), install.getDataDir(), install.getDataDir(), System.getProperty("java.home"));
        try {
            Files.createDirectories(install.getDataDir());
            Files.writeString(UNIT_FILE, unit);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + UNIT_FILE + " (" + e.getMessage() + ") (" + privilegeHint("install-service") + ")");
        }
        Processes.require(Processes.run("systemctl", "daemon-reload"), "systemctl daemon-reload", privilegeHint("install-service"));
        Processes.require(Processes.run("systemctl", "enable", NAME), "systemctl enable", privilegeHint("install-service"));
    }

    @Override
    public void uninstall() {
        Processes.run("systemctl", "stop", NAME);
        Processes.run("systemctl", "disable", NAME);
        try {
            Files.deleteIfExists(UNIT_FILE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot remove " + UNIT_FILE + " (" + privilegeHint("uninstall-service") + ")");
        }
        Processes.run("systemctl", "daemon-reload");
    }

    @Override
    public void start() {
        Processes.require(Processes.run("systemctl", "start", NAME), "systemctl start", privilegeHint("start"));
    }

    @Override
    public void stop() {
        Processes.require(Processes.run("systemctl", "stop", NAME), "systemctl stop", privilegeHint("stop"));
    }

    @Override
    public ServiceStatus status() {
        if (!Files.exists(UNIT_FILE)) {
            return ServiceStatus.notInstalled();
        }
        String state = Processes.run("systemctl", "is-active", NAME).output().trim();
        return ServiceStatus.builder().installed(true).running(state.equals("active")).detail(state).build();
    }

    @Override
    public String privilegeHint(String command) {
        return "this needs root: sudo kommserver " + command;
    }
}
