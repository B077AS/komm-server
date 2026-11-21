package com.kommserver.launcher.commands;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Palette;
import com.kommserver.launcher.Platform;
import com.kommserver.launcher.Terminal;
import com.kommserver.launcher.service.ServiceController;
import com.kommserver.launcher.service.ServiceStatus;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "status", description = "Show whether komm-server is installed and running, and where its files are")
public class StatusCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        Install install = Install.current();
        ServiceStatus status = ServiceController.create().status();
        System.out.println(Palette.header());

        if (!status.isInstalled()) {
            System.out.println(Palette.badge(false, "service not installed"));
        } else {
            System.out.println(Palette.badge(status.isRunning(), status.isRunning() ? "running" : "stopped (" + status.getDetail() + ")"));
        }
        int port = install.serverPort();
        if (status.isRunning()) {
            boolean open = Terminal.portOpen(port);
            System.out.println(Palette.muted("port      ") + port + "  " + (open ? Palette.success("accepting connections") : Palette.danger("not answering (yet)")));
        }
        System.out.println(Palette.muted("version   ") + Palette.cyan(install.versionOnDisk()));
        System.out.println(Palette.muted("java      ") + Runtime.version() + Palette.muted("  " + System.getProperty("java.home")));
        System.out.println(Palette.muted("installed ") + install.getHome());
        System.out.println(Palette.muted("data      ") + install.getDataDir()
                + (install.activated() ? "" : Palette.danger("  (not activated yet)")));

        printNextSteps(status);
        return 0;
    }

    // status is what people run when they do not know what to do next, so it answers that.
    private void printNextSteps(ServiceStatus status) {
        System.out.println();
        System.out.println(Palette.muted("─".repeat(28)));
        if (!status.isInstalled()) {
            line("kommserver install-service", "registers the OS service (asks for the verification code once)");
        } else if (status.isRunning()) {
            line("kommserver update", "looks for a newer komm-server and installs it");
            line("kommserver logs -f", "follows the server log");
            line("kommserver restart", "restarts the server");
            line("kommserver stop", "stops the server");
        } else {
            line("kommserver start", "starts the server");
            line("kommserver logs", "shows the end of the server log");
            line("kommserver update", "looks for a newer komm-server and installs it");
        }
        System.out.println(Palette.muted("kommserver --help             every command"));
        if (Platform.isWindows()) {
            System.out.println(Palette.muted("(start/stop/update/install-service need a Command Prompt opened as Administrator)"));
        } else {
            System.out.println(Palette.muted("(start/stop/update/install-service need root: sudo kommserver ...)"));
        }
    }

    private static void line(String command, String what) {
        System.out.println(Palette.accent(String.format("%-29s", command)) + " " + Palette.muted(what));
    }
}
