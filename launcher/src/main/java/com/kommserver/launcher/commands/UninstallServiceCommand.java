package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceController;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "uninstall-service", description = "Stop komm-server and remove the OS service (files and data stay)")
public class UninstallServiceCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try {
            ServiceController.create().uninstall();
            System.out.println(Palette.success("✓") + " service removed (the installation and its data are untouched)");
            return 0;
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Uninstall failed: ") + e.getMessage());
            return 1;
        }
    }
}
