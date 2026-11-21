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
            Install.current().clearActivationStatus();
            ServiceController.create().start();
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Start failed: ") + e.getMessage());
            return 1;
        }
        return waitUntilUp(Install.current()) == com.kommserver.launcher.Terminal.Wait.UP ? 0 : 1;
    }

    /** Shared by start, restart and update: waits for the server's port, and says what to do if it never opens. */
    static Terminal.Wait waitUntilUp(Install install) {
        ServiceController service = ServiceController.create();
        System.out.print(Palette.muted("Waiting for komm-server on port " + install.serverPort() + " "));
        // Long enough for embedded Postgres, the hub handshake and the SFU. The port is looked up again every second: a
        // new installation learns it from the hub while it activates. A server whose verification code the hub
        // rejected serves nothing and waits, so that is its own outcome instead of a two-minute timeout.
        Terminal.Wait result = Terminal.waitForPort(install::serverPort, 120, () -> service.status().isRunning(), () -> {
            Install.ActivationStatus status = install.activationStatus();
            return status != null && status.rejected();
        });
        switch (result) {
            case UP -> System.out.println(Palette.success("●") + " komm-server " + Palette.cyan(install.versionOnDisk()) + " is running");
            case STOPPED -> System.out.println(Palette.danger("●") + " komm-server stopped while starting. See `kommserver logs`.");
            case BLOCKED -> printRejected(install.activationStatus());
            case TIMED_OUT -> {
                System.out.println(Palette.danger("●") + " komm-server did not answer within 2 minutes. See `kommserver logs`.");
                Install.ActivationStatus status = install.activationStatus();
                if (status != null && status.getMessage() != null) {
                    System.out.println(Palette.muted("  Still activating: ") + status.getMessage());
                }
            }
        }
        return result;
    }

    private static void printRejected(Install.ActivationStatus status) {
        System.out.println(Palette.danger("●") + " komm-server is running but is not activated: the hub did not accept the verification code.");
        if (status != null && status.getMessage() != null) {
            System.out.println(Palette.muted("  ") + status.getMessage());
        }
        System.out.println("  Copy the code from the Komm client again, then run: "
                + Palette.cyan("kommserver install-service --code <code>"));
        System.out.println(Palette.muted("  The server picks the new code up by itself; no restart is needed."));
    }
}
