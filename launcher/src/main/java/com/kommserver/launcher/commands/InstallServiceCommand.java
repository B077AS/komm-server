package com.kommserver.launcher.commands;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Palette;
import com.kommserver.launcher.Terminal;
import com.kommserver.launcher.service.ServiceController;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "install-service",
        description = "Register komm-server as an OS service (systemd on Linux, a Windows Service on Windows); safe to run again")
public class InstallServiceCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--code", description = "The verification code from the Komm client (asked for when missing)")
    String code;

    @Override
    public Integer call() {
        Install install = Install.current();
        // A server that is up and waiting for a code the hub rejected: it picks a new one up by itself.
        Install.ActivationStatus waiting = install.activationStatus();
        boolean replacingRejectedCode = waiting != null && waiting.rejected();
        if (!saveSetupToken(install)) {
            return 1;
        }
        try {
            ServiceController.create().install(install);
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Install failed: ") + e.getMessage());
            return 1;
        }
        System.out.println(Palette.success("✓") + " service installed: it starts at boot and runs " + install.getStub());
        System.out.println(Palette.muted("  data folder ") + install.getDataDir());
        System.out.println(Palette.muted("  Java        ") + System.getProperty("java.home"));
        if (replacingRejectedCode && code != null && !code.isBlank()) {
            System.out.println(Palette.muted("The running server picks the new code up by itself."));
            return StartCommand.waitUntilUp(install) == Terminal.Wait.UP ? 0 : 1;
        }
        System.out.println(Palette.muted("`kommserver start` brings it up."));
        return 0;
    }

    /**
     * A new installation has no identity yet: the server activates with a one-time setup token (the verification code
     * shown in the Komm client) that it reads from {@code setup-token.txt} and deletes once used. The hub's certificate
     * on disk means it already activated.
     */
    private boolean saveSetupToken(Install install) {
        if (install.activated()) {
            return true;
        }
        // A code that is already saved is kept, unless a new one is given with --code: that is how a code the hub
        // rejected gets replaced (the server reads the file again by itself, no restart needed).
        boolean replacing = code != null && !code.isBlank();
        if (!replacing && Files.exists(install.setupTokenFile())) {
            return true;
        }
        String value = code;
        if (value == null || value.isBlank()) {
            System.out.println(Palette.accent("New installation: it needs the verification code from the Komm client."));
            value = Terminal.ask("Verification code: ");
            if (value == null) {
                System.out.println(Palette.danger("No terminal to ask in: pass it as `kommserver install-service --code <code>`."));
                return false;
            }
        }
        if (value.isBlank()) {
            System.out.println(Palette.danger("No code entered."));
            return false;
        }
        try {
            Files.createDirectories(install.getDataDir());
            Files.writeString(install.setupTokenFile(), value.trim());
            install.clearActivationStatus(); // so that the old rejection is not mistaken for the answer to this code
            System.out.println(Palette.success("✓") + " verification code saved");
            return true;
        } catch (IOException e) {
            System.out.println(Palette.danger("Cannot save the verification code: ") + e.getMessage());
            return false;
        }
    }
}
