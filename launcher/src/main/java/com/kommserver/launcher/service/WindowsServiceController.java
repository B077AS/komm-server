package com.kommserver.launcher.service;

import com.kommserver.launcher.Install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Windows Service through WinSW ({@code komm-server-service.exe}, installed next to the stub by the installer), which
 * runs the installation's jpack stub. The server entry is a console program ({@code java.exe}), and the stub hands it
 * WinSW's console, so WinSW's stop (a Ctrl+C) reaches the JVM and its shutdown hooks run (embedded Postgres included).
 */
public class WindowsServiceController implements ServiceController {

    private static final String SERVICE_ID = "KommServer";

    private Path wrapper(Install install) {
        return install.home().resolve("komm-server-service.exe");
    }

    @Override
    public void install(Install install) {
        Path exe = wrapper(install);
        if (!Files.exists(exe)) {
            throw new IllegalStateException(exe + " is missing: install komm-server again, the installer puts it there.");
        }
        String xml = """
                <!-- Written by `kommserver install-service`. Run it again rather than editing this file. -->
                <service>
                  <id>%s</id>
                  <name>Komm Server</name>
                  <description>Self-hosted komm community server</description>
                  <executable>%s</executable>
                  <workingdirectory>%s</workingdirectory>
                  <env name="KOMM_DATA_DIR" value="%s"/>
                  <env name="JAVA_HOME" value="%s"/>
                  <logpath>%s</logpath>
                  <log mode="roll-by-size">
                    <sizeThreshold>10240</sizeThreshold>
                    <keepFiles>8</keepFiles>
                  </log>
                  <onfailure action="restart" delay="2 sec"/>
                  <stoptimeout>30 sec</stoptimeout>
                  <startmode>Automatic</startmode>
                </service>
                """.formatted(SERVICE_ID, install.stub(), install.dataDir(), install.dataDir(), System.getProperty("java.home"),
                install.dataDir().resolve("logs"));
        try {
            Files.createDirectories(install.dataDir().resolve("logs"));
            Files.writeString(install.home().resolve("komm-server-service.xml"), xml);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the service configuration (" + privilegeHint("install-service") + "): " + e.getMessage());
        }
        if (status().installed()) {
            return; // WinSW reads the XML at every start: rewriting it was the whole update
        }
        Processes.require(Processes.run(exe.toString(), "install"), "Registering the service", privilegeHint("install-service"));
        // By default only Administrators may start/stop a service; this lets any signed-in user do it (the tray runs
        // unelevated). SYSTEM and Administrators keep full control.
        Processes.run("sc", "sdset", SERVICE_ID, "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)"
                + "(A;;CCLCSWRPWPLOCRRC;;;AU)(A;;CCLCSWLOCRRC;;;PU)");
    }

    @Override
    public void uninstall() {
        Install install = Install.current();
        Processes.run("sc", "stop", SERVICE_ID);
        waitFor("STOPPED", 60);
        Processes.run(wrapper(install).toString(), "uninstall");
    }

    @Override
    public void start() {
        Processes.Result result = Processes.run("sc", "start", SERVICE_ID);
        if (!result.ok() && !result.output().contains("1056")) { // 1056: already running
            Processes.require(result, "Starting the service", privilegeHint("start"));
        }
    }

    @Override
    public void stop() {
        Processes.Result result = Processes.run("sc", "stop", SERVICE_ID);
        if (!result.ok() && !result.output().contains("1062")) { // 1062: not started
            Processes.require(result, "Stopping the service", privilegeHint("stop"));
        }
        // sc only asks; an update must not swap files before the server has really exited.
        if (!waitFor("STOPPED", 60)) {
            throw new IllegalStateException("The service did not stop within 60 seconds");
        }
    }

    private boolean waitFor(String state, int seconds) {
        for (int i = 0; i < seconds * 2; i++) {
            ServiceStatus status = status();
            if (!status.installed() || status.detail().contains(state)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public ServiceStatus status() {
        Processes.Result query = Processes.run("sc", "query", SERVICE_ID);
        if (query.output().contains("1060")) { // the service does not exist
            return ServiceStatus.notInstalled();
        }
        String state = query.output().lines().filter(l -> l.contains("STATE")).findFirst().map(String::trim).orElse("unknown");
        return new ServiceStatus(true, state.contains("RUNNING"), state);
    }

    @Override
    public String privilegeHint(String command) {
        return "run `kommserver " + command + "` from a Command Prompt opened as Administrator";
    }
}
