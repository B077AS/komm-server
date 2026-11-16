package com.kommserver.launcher.service;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Platform;

/**
 * komm-server as an OS service: systemd on Linux, a Windows Service (WinSW) on Windows. The service runs the jpack stub
 * of the installation, which starts the server; this tool only asks the OS's own service manager, which is what gives
 * the server restart-on-crash and start-at-boot.
 */
public interface ServiceController {

    String NAME = "komm-server";

    static ServiceController create() {
        return Platform.isWindows() ? new WindowsServiceController() : new LinuxServiceController();
    }

    void install(Install install);

    void uninstall();

    void start();

    void stop();

    ServiceStatus status();

    /** Who may run start/stop/install, for error messages ("sudo kommserver ..." / "an Administrator prompt"). */
    String privilegeHint(String command);

    default void restart() {
        if (status().running()) {
            stop();
        }
        start();
    }
}
