package com.kommserver.launcher;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.NoSuchElementException;
import java.util.Scanner;

/** Small terminal helpers shared by the commands. */
public final class Terminal {

    private static final Scanner IN = new Scanner(System.in);

    private Terminal() {
    }

    /** Asks a yes/no question; no answer (no terminal) counts as no. */
    public static boolean confirm(String question) {
        System.out.print(question + " [y/N] ");
        System.out.flush();
        try {
            return IN.nextLine().trim().equalsIgnoreCase("y");
        } catch (NoSuchElementException e) {
            System.out.println();
            return false;
        }
    }

    /** One line of input, or null when there is no terminal to read from. */
    public static String ask(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            return IN.nextLine().trim();
        } catch (NoSuchElementException e) {
            System.out.println();
            return null;
        }
    }

    /**
     * Redraws one progress line. \r works the same in Linux terminals and Windows consoles; the trailing spaces wipe what a
     * longer earlier line left behind.
     */
    public static void progress(long received, long total) {
        String line;
        if (total > 0) {
            int percent = (int) Math.min(100, received * 100 / total);
            int width = 24;
            int filled = width * percent / 100;
            line = Palette.accent("[" + "█".repeat(filled) + "░".repeat(width - filled) + "]") + " " + Palette.cyan(percent + "%")
                    + Palette.muted(" (" + size(received) + " / " + size(total) + ")");
        } else {
            line = Palette.muted("Downloading... " + size(received));
        }
        System.out.print("\r" + line + "          ");
        System.out.flush();
    }

    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        return kb < 1024 ? String.format("%.0f KB", kb) : String.format("%.1f MB", kb / 1024.0);
    }

    public static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** How a wait for the server ended. */
    public enum Wait { UP, STOPPED, BLOCKED, TIMED_OUT }

    /**
     * Waits until the server accepts connections, printing a dot per second. Ends early when {@code alive} says the
     * service is no longer running (the server died: the service manager is about to restart it), or when
     * {@code blocked} says the server is up but cannot go on without the user (the hub rejected its verification code).
     * The port is asked again every second: a new installation only learns it from the hub while it activates.
     */
    public static Wait waitForPort(java.util.function.IntSupplier port, int seconds,
                                   java.util.function.BooleanSupplier alive, java.util.function.BooleanSupplier blocked) {
        int down = 0;
        for (int i = 0; i < seconds; i++) {
            if (portOpen(port.getAsInt())) {
                System.out.println();
                return Wait.UP;
            }
            if (blocked.getAsBoolean()) {
                System.out.println();
                return Wait.BLOCKED;
            }
            // Twice in a row: a single "not running" can be the moment the service manager is still starting it.
            down = alive.getAsBoolean() ? 0 : down + 1;
            if (down >= 2) {
                System.out.println();
                return Wait.STOPPED;
            }
            System.out.print(Palette.muted("."));
            System.out.flush();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Wait.TIMED_OUT;
            }
        }
        System.out.println();
        return Wait.TIMED_OUT;
    }
}
