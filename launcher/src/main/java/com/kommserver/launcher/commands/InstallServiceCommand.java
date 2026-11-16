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
        if (!saveSetupToken(install)) {
            return 1;
        }
        try {
            ServiceController.create().install(install);
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Install failed: ") + e.getMessage());
            return 1;
        }
        System.out.println(Palette.success("✓") + " service installed: it starts at boot and runs " + install.stub());
        System.out.println(Palette.muted("  data folder ") + install.dataDir());
        System.out.println(Palette.muted("  Java        ") + System.getProperty("java.home"));
        System.out.println(Palette.muted("`kommserver start` brings it up."));
        return 0;
    }

    /**
     * A new installation has no identity yet: the server activates with a one-time setup token (the verification code
     * shown in the Komm client) that it reads from {@code setup-token.txt} and deletes once used. The hub's certificate
     * on disk means it already activated.
     */
    private boolean saveSetupToken(Install install) {
        if (install.activated() || Files.exists(install.setupTokenFile())) {
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
            Files.createDirectories(install.dataDir());
            Files.writeString(install.setupTokenFile(), value.trim());
            System.out.println(Palette.success("✓") + " verification code saved");
            return true;
        } catch (IOException e) {
            System.out.println(Palette.danger("Cannot save the verification code: ") + e.getMessage());
            return false;
        }
    }
}
