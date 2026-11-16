package com.kommserver.launcher;

public final class Platform {

    private Platform() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
