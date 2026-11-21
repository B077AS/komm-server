package com.kommserver.launcher.commands;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Palette;
import com.kommserver.launcher.Platform;
import com.kommserver.launcher.Terminal;
import com.kommserver.launcher.service.ServiceController;
import com.kommserver.launcher.service.ServiceStatus;
import com.kommserver.launcher.tray.Tray;
import io.github.b077as.jpack.Jpack;
import io.github.b077as.jpack.UpdateCheck;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Updates the whole installation (server, this tool, the stub) through jpack, in one command:
 *
 * <ol>
 *   <li>Asks the update address for a newer release, and downloads it with a progress bar. The server keeps running.</li>
 *   <li>Stops the service: files are only swapped while no program of the installation runs.</li>
 *   <li>Exits, asking the stub to start this command again. Before it does, the stub swaps the new files in, so the second
 *       run is already the new version of this tool. A marker file tells it that it is finishing an update.</li>
 *   <li>The second run starts the service and waits for the server. The server's first start is the release's trial: if
 *       it dies at once, the service's stub puts the previous version back; this command then says so.</li>
 * </ol>
 */
@CommandLine.Command(name = "update", description = "Download and install a newer komm-server, then restart it")
public class UpdateCommand implements Callable<Integer> {

    /** Written by the first run, read and removed by the second. Not a jpack part, so swaps leave it alone. */
    static final String MARKER = "kommserver-update.properties";

    @CommandLine.Option(names = {"-y", "--yes"}, description = "Do not ask before restarting a running server")
    boolean yes;

    @CommandLine.Option(names = "--check", description = "Only say whether there is a newer version")
    boolean checkOnly;

    @Override
    public Integer call() {
        Install install = Install.current();
        if (!Jpack.isAvailable()) {
            System.out.println(Palette.danger("Updates need the installed `kommserver` command (it runs through jpack)."));
            return 1;
        }
        Path marker = install.getHome().resolve(MARKER);
        try (Jpack jpack = Jpack.connect()) {
            if (Files.exists(marker)) {
                return finish(install, marker);
            }
            return download(install, jpack, marker);
        }
    }

    private int download(Install install, Jpack jpack, Path marker) {
        String current = install.versionOnDisk();
        UpdateCheck update;
        try {
            update = jpack.check().get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            System.out.println(Palette.danger("Cannot look for updates: ") + e.getCause().getMessage());
            return 1;
        } catch (TimeoutException | InterruptedException e) {
            System.out.println(Palette.danger("Cannot look for updates: no answer within a minute."));
            return 1;
        }
        if (!update.available()) {
            System.out.println(Palette.success("✓") + " komm-server " + Palette.cyan(current) + " is the newest version");
            return 0;
        }
        System.out.println(Palette.accent("komm-server ") + Palette.cyan(current) + " → " + Palette.cyan(update.version())
                + Palette.muted("  (" + Terminal.size(update.downloadSize()) + " to download)"));
        if (checkOnly) {
            return 0;
        }
        if (!jpack.canUpdate()) {
            System.out.println(Palette.danger("The installation folder cannot be written to: ")
                    + (Platform.isWindows() ? "run this from a Command Prompt opened as Administrator." : "run it as root: sudo kommserver update"));
            return 1;
        }

        ServiceController service = ServiceController.create();
        ServiceStatus status = service.status();
        if (status.isRunning() && !yes && !Terminal.confirm("komm-server is running: it restarts once the update is downloaded. Go on?")) {
            System.out.println(Palette.muted("Nothing downloaded."));
            return 0;
        }

        long[] shown = {-1};
        try {
            jpack.download((received, total) -> {
                // About a hundred redraws for the whole download, not one per network chunk.
                long step = total > 0 ? Math.max(total / 100, 8192) : 262144;
                if (received == total || received - shown[0] >= step) {
                    shown[0] = received;
                    Terminal.progress(received, total);
                }
            }).get(30, TimeUnit.MINUTES);
            System.out.println();
        } catch (ExecutionException e) {
            System.out.println();
            System.out.println(Palette.danger("Download failed: ") + e.getCause().getMessage());
            return 1;
        } catch (TimeoutException | InterruptedException e) {
            System.out.println();
            System.out.println(Palette.danger("Download did not finish within 30 minutes."));
            return 1;
        }
        System.out.println(Palette.success("✓") + " downloaded " + Palette.cyan(update.version()));

        boolean restart = status.isRunning();
        if (restart) {
            System.out.println(Palette.muted("Stopping komm-server..."));
            try {
                service.stop();
            } catch (RuntimeException e) {
                System.out.println(Palette.danger("Cannot stop the server: ") + e.getMessage());
                System.out.println(Palette.muted("The update is downloaded; it is installed the next time komm-server starts."));
                return 1;
            }
        }
        boolean tray = Platform.isWindows() && Tray.stopRunning(); // it holds the tool's jar open, which blocks the swap

        Properties props = new Properties();
        props.setProperty("version", update.version());
        props.setProperty("from", current);
        props.setProperty("restart", String.valueOf(restart));
        props.setProperty("tray", String.valueOf(tray));
        try (OutputStream out = Files.newOutputStream(marker)) {
            props.store(out, "kommserver update in progress: the next `kommserver update` finishes it");
        } catch (IOException e) {
            System.out.println(Palette.danger("Cannot write " + marker + ": ") + e.getMessage());
            return 1;
        }
        System.out.println(Palette.muted("Installing " + update.version() + "..."));
        jpack.restartAfterExit(); // the stub swaps the files in once this exits, then runs the new `kommserver update`
        return 0;
    }

    /** The second run: the new files are in (or could not be swapped in), the server is stopped. */
    private int finish(Install install, Path marker) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            props.load(in);
        } catch (IOException e) {
            // an unreadable marker: finish as well as we can
        }
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            System.out.println(Palette.danger("Cannot remove " + marker + ": ") + e.getMessage());
        }
        String wanted = props.getProperty("version", "?");
        boolean restart = Boolean.parseBoolean(props.getProperty("restart", "false"));
        boolean installed = wanted.equals(install.getVersion());
        if (installed) {
            System.out.println(Palette.success("✓") + " installed " + Palette.cyan(wanted));
        } else {
            System.out.println(Palette.danger("●") + " " + wanted + " is downloaded but could not be put in place yet "
                    + "(something still had komm-server's files open). It is installed the next time komm-server starts.");
        }
        if (Boolean.parseBoolean(props.getProperty("tray", "false"))) {
            Tray.launch(install);
        }
        if (!restart) {
            return installed ? 0 : 1;
        }

        System.out.println(Palette.muted("Starting komm-server..."));
        try {
            ServiceController.create().start();
        } catch (RuntimeException e) {
            System.out.println(Palette.danger("Cannot start the server: ") + e.getMessage());
            return 1;
        }
        Terminal.Wait result = StartCommand.waitUntilUp(install);
        if (result == Terminal.Wait.STOPPED && installed) {
            // The service's stub puts the previous version back when the new one dies at its first start. It can only
            // do that once this command has ended (files are not swapped while it runs), so end now and let it.
            System.out.println(Palette.danger("●") + " " + wanted + " did not start. komm-server goes back to "
                    + Palette.cyan(props.getProperty("from", "the previous version")) + " on its own in a few seconds:"
                    + " check with `kommserver status`, and see `kommserver logs` for what went wrong.");
            return 1;
        }
        return result == Terminal.Wait.UP && installed ? 0 : 1;
    }
}
