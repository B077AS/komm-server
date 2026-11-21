package com.kommserver.launcher.tray;

import com.kommserver.launcher.Install;

import java.io.IOException;

/**
 * The tray process from outside: Windows locks the jar a running program uses, and the tray runs from this tool's jar
 * for as long as someone is signed in. An update stops it before the files are swapped and starts it again after.
 */
public final class Tray {

    private static final String MATCH = "*komm-server-launcher.jar*tray*";

    private Tray() {
    }

    /** Stops running tray processes; true when there was one. */
    public static boolean stopRunning() {
        // ProcessHandle cannot see other processes' command lines on Windows; CIM can.
        String script = "$p = Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" | Where-Object { $_.CommandLine -like '" + MATCH + "' }; "
                + "if ($p) { $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; exit 0 } else { exit 1 }";
        try {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean stopped = process.waitFor() == 0;
            if (stopped) {
                Thread.sleep(500); // the jar's handle goes with the process, a moment after it ends
            }
            return stopped;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Starts the tray on its own, the way the Startup shortcut does. */
    public static void launch(Install install) {
        try {
            new ProcessBuilder("javaw", "-jar", install.getHome().resolve("launcher").resolve("komm-server-launcher.jar").toString(), "tray")
                    .directory(install.getHome().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            System.out.println("The tray icon could not be started again: " + e.getMessage());
        }
    }
}
