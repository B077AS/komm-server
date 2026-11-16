package com.kommserver.launcher.tray;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.service.ServiceController;
import com.kommserver.launcher.service.ServiceStatus;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The Windows-only resident piece. Deliberately NOT the Windows Service itself — a service
 * runs in Session 0 and can never draw a tray icon (Session 0 isolation). This is started
 * separately, at user login, by a Startup-folder shortcut the installer creates; it only
 * ever talks to the actual service (komm-server-service.exe / sc.exe) via ServiceController,
 * the same class the CLI subcommands use.
 */
public final class TrayApp {

    private TrayApp() {}

    public static void run() {
        if (!SystemTray.isSupported()) {
            System.out.println("System tray not supported on this desktop.");
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();

            MenuItem statusItem = new MenuItem("Status: checking...");
            statusItem.setEnabled(false);
            menu.add(statusItem);
            menu.addSeparator();

            MenuItem start = new MenuItem("Start");
            menu.add(start);

            MenuItem stop = new MenuItem("Stop");
            menu.add(stop);

            MenuItem restart = new MenuItem("Restart");
            menu.add(restart);

            menu.addSeparator();

            MenuItem update = new MenuItem("Check for updates");
            menu.add(update);

            MenuItem openCli = new MenuItem("Open console");
            menu.add(openCli);

            menu.addSeparator();

            MenuItem quit = new MenuItem("Quit");
            menu.add(quit);

            TrayIcon trayIcon = new TrayIcon(renderIcon(), "Komm Server", menu);
            trayIcon.setImageAutoSize(true);
            tray.add(trayIcon);

            // Listeners attached after trayIcon exists — Start/Stop/Restart need it to
            // report back what actually happened, since none of ServiceController's
            // methods report success/failure on their own (they're thin sc.exe wrappers
            // that stay silent either way) — which is exactly why "Stop doesn't do
            // anything" was previously indistinguishable from "Stop worked but nothing
            // told me." Runs off the EDT since these block on an external process.
            start.addActionListener(a -> runServiceAction(trayIcon, "Start",
                    () -> ServiceController.create().start(), true));
            stop.addActionListener(a -> runServiceAction(trayIcon, "Stop",
                    () -> ServiceController.create().stop(), false));
            restart.addActionListener(a -> runServiceAction(trayIcon, "Restart",
                    () -> ServiceController.create().restart(), true));
            update.addActionListener(a -> openConsole("update"));
            openCli.addActionListener(a -> openConsole("status"));
            // AWT's TrayIcon "action event" fires on double-click on Windows — without
            // this, double-clicking the icon did nothing at all, since only the
            // right-click popup menu had anything wired to it.
            trayIcon.addActionListener(a -> openConsole("status"));
            quit.addActionListener(a -> {
                tray.remove(trayIcon);
                System.exit(0);
            });

            refreshStatus(statusItem, trayIcon);
            Timer refresher = new Timer("tray-status-refresh", true);
            refresher.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    refreshStatus(statusItem, trayIcon);
                }
            }, 5000, 5000);

            // The real bug: every other command is meant to run once and exit, so
            // Launcher.main() calls System.exit(exitCode) right after run() returns —
            // which it does here too, immediately, since setting up the icon and timer
            // above is non-blocking. That killed the JVM a fraction of a second after
            // the tray icon appeared, every single time, with no exception and no clue
            // why. Block here forever instead; Quit's own System.exit(0) is the only
            // way this process ends.
            Object parkForever = new Object();
            synchronized (parkForever) {
                parkForever.wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Failed to start tray icon: " + e.getMessage());
        }
    }

    private static void runServiceAction(TrayIcon trayIcon, String label, Runnable action, boolean expectRunning) {
        new Thread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                trayIcon.displayMessage("Komm Server", label + " failed: " + e.getMessage(), TrayIcon.MessageType.ERROR);
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ServiceStatus status = ServiceController.create().status();
            if (status.running() == expectRunning) {
                trayIcon.displayMessage("Komm Server",
                        label + " succeeded — " + (status.running() ? "running" : "stopped") + ".",
                        TrayIcon.MessageType.INFO);
            } else {
                trayIcon.displayMessage("Komm Server",
                        label + " may not have worked — still " + (status.running() ? "running" : "stopped")
                                + ". Try Command Prompt as Administrator: kommserver " + label.toLowerCase(),
                        TrayIcon.MessageType.WARNING);
            }
        }, "tray-service-action").start();
    }

    private static void refreshStatus(MenuItem statusItem, TrayIcon trayIcon) {
        ServiceStatus status = ServiceController.create().status();
        String label = !status.installed() ? "Not installed" : status.running() ? "Running" : "Stopped";
        statusItem.setLabel("Status: " + label);
        trayIcon.setToolTip("Komm Server — " + label);
    }

    private static void openConsole(String command) {
        try {
            // Two separate problems, not one. (1) The absolute path for the *first*
            // command: the installer adds {app} to the system PATH, but the tray is a
            // long-lived process whose own environment was frozen at launch — nothing
            // broadcast afterward can ever retroactively refresh an already-running
            // process's copy of it, so relying on PATH here always resolves to nothing
            // ("is not recognized..."). (2) A window this spawns *inherits* that same
            // frozen PATH from the tray, its parent — so even after fixing the first
            // command, typing bare "kommserver" again yourself inside that same window
            // hit the exact same problem one level down. Fixed by extending PATH for
            // just this spawned session (set PATH=...;{app}) before running anything,
            // so both the scripted command and anything typed afterward resolve.
            String installDir = Install.current().home().toString();
            String cmdPath = Install.current().home().resolve("kommserver.cmd").toString();
            String innerCommand = "set \"PATH=%PATH%;" + installDir + "\" && \"" + cmdPath + "\" " + command;
            new ProcessBuilder("cmd", "/c", "start", "cmd", "/k", innerCommand).start();
        } catch (Exception e) {
            System.err.println("Failed to open console: " + e.getMessage());
        }
    }

    /** Same source image as the client/client-launcher icon — see src/main/resources/icon.png. */
    private static Image renderIcon() {
        return Toolkit.getDefaultToolkit().getImage(TrayApp.class.getResource("/icon.png"));
    }
}
