package com.kommserver.launcher.commands;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Palette;
import com.kommserver.launcher.Terminal;
import com.kommserver.launcher.service.ServiceController;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "start", description = "Start the komm-server service and wait until it answers")
public class StartCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try {
            ServiceController.create().start();
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Start failed: ") + e.getMessage());
            return 1;
        }
        return waitUntilUp(Install.current()) == com.kommserver.launcher.Terminal.Wait.UP ? 0 : 1;
    }

    /** Shared by start, restart and update: waits for the server's port, and says what to do if it never opens. */
    static Terminal.Wait waitUntilUp(Install install) {
        int port = install.serverPort();
        ServiceController service = ServiceController.create();
        System.out.print(Palette.muted("Waiting for komm-server on port " + port + " "));
        // Long enough for embedded Postgres, the hub handshake and the SFU.
        Terminal.Wait result = Terminal.waitForPort(port, 120, () -> service.status().running());
        switch (result) {
            case UP -> System.out.println(Palette.success("●") + " komm-server " + Palette.cyan(install.versionOnDisk()) + " is running");
            case STOPPED -> System.out.println(Palette.danger("●") + " komm-server stopped while starting. See `kommserver logs`.");
            case TIMED_OUT -> System.out.println(Palette.danger("●") + " komm-server did not answer within 2 minutes. See `kommserver logs`.");
        }
        return result;
    }
}
