package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceController;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "stop", description = "Stop the komm-server service")
public class StopCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        try {
            ServiceController.create().stop();
            System.out.println(Palette.muted("●") + " komm-server stopped");
            return 0;
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Stop failed: ") + e.getMessage());
            return 1;
        }
    }
}
