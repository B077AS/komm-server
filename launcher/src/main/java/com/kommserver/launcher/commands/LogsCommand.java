package com.kommserver.launcher.commands;

import com.kommserver.launcher.Install;
import com.kommserver.launcher.Palette;
import picocli.CommandLine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;

/**
 * The server's own log file ({@code <data>/logs/komm.log}), the same on Linux, Windows and in a container. On Linux the
 * service's output also goes to the journal: {@code journalctl -u komm-server}.
 */
@CommandLine.Command(name = "logs", description = "Show the end of the server log")
public class LogsCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-n", "--lines"}, description = "How many lines (default ${DEFAULT-VALUE})", defaultValue = "50")
    int lines;

    @CommandLine.Option(names = {"-f", "--follow"}, description = "Keep printing new lines (Ctrl+C to stop)")
    boolean follow;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path log = Install.current().serverLog();
        if (!Files.exists(log)) {
            System.out.println(Palette.muted("No log yet at " + log));
            return 0;
        }
        long position = printLast(log, lines);
        while (follow) {
            Thread.sleep(500);
            long size = Files.size(log);
            if (size < position) {
                position = 0; // rolled over to a new file
            }
            if (size > position) {
                try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                    file.seek(position);
                    byte[] bytes = new byte[(int) (size - position)];
                    file.readFully(bytes);
                    System.out.print(new String(bytes, StandardCharsets.UTF_8));
                    System.out.flush();
                }
                position = size;
            }
        }
        return 0;
    }

    /** Prints the last lines without reading a big log into memory; returns where the file ended. */
    private static long printLast(Path log, int count) throws IOException {
        Deque<String> last = new ArrayDeque<>(count);
        try (var reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null && count > 0; line = reader.readLine()) {
                if (last.size() == count) {
                    last.removeFirst();
                }
                last.addLast(line);
            }
        }
        last.forEach(System.out::println);
        return Files.size(log);
    }
}
