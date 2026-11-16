package com.kommserver.launcher;

import com.kommserver.launcher.commands.InstallServiceCommand;
import com.kommserver.launcher.commands.LogsCommand;
import com.kommserver.launcher.commands.RestartCommand;
import com.kommserver.launcher.commands.StartCommand;
import com.kommserver.launcher.commands.StatusCommand;
import com.kommserver.launcher.commands.StopCommand;
import com.kommserver.launcher.commands.TrayCommand;
import com.kommserver.launcher.commands.UninstallServiceCommand;
import com.kommserver.launcher.commands.UpdateCommand;
import org.fusesource.jansi.AnsiConsole;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@CommandLine.Command(
        name = "kommserver",
        description = "Run, update and look after a self-hosted komm-server.",
        mixinStandardHelpOptions = true,
        versionProvider = Launcher.VersionProvider.class,
        subcommands = {
                StatusCommand.class,
                StartCommand.class,
                StopCommand.class,
                RestartCommand.class,
                UpdateCommand.class,
                LogsCommand.class,
                InstallServiceCommand.class,
                UninstallServiceCommand.class,
                TrayCommand.class
        }
)
public class Launcher implements Runnable {

    @Override
    public void run() {
        // A bare `kommserver` shows what `kommserver status` shows.
        new StatusCommand().call();
    }

    public static void main(String[] args) {
        AnsiConsole.systemInstall();
        // A plain cmd.exe uses a legacy codepage that mangles the box and bullet glyphs; AnsiConsole replaces System.out,
        // so this has to wrap its streams (kommserver.cmd also switches the console to UTF-8).
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new CommandLine(new Launcher()).execute(args);
        } catch (Throwable t) {
            // picocli catches Exceptions only; an Error (AWT in the tray, say) would otherwise vanish with exit code 0.
            System.err.println("Fatal: " + t);
            t.printStackTrace();
            code = 1;
        } finally {
            AnsiConsole.systemUninstall();
        }
        System.exit(code);
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = System.getenv("JPACK_VERSION");
            return new String[]{"komm-server " + (version == null ? "(not installed: started outside jpack)" : version)};
        }
    }
}
