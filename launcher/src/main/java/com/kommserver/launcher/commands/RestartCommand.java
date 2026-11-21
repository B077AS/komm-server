package com.kommserver.launcher.commands;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceController;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "restart", description = "Restart the komm-server service and wait until it answers")
public class RestartCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try {
            Install.current().clearActivationStatus();
            ServiceController.create().restart();
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Restart failed: ") + e.getMessage());
            return 1;
        }
        return StartCommand.waitUntilUp(Install.current()) == com.kommserver.launcher.Terminal.Wait.UP ? 0 : 1;
    }
}
