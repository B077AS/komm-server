package com.kommserver.launcher;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Terminal colors mirrored from komm-hub's own site.css palette
 * (--accent, --cyan, --success, --danger, --fg-muted), so the CLI on both
 * platforms feels like the same product as the website and the client.
 */
public final class Palette {

    private Palette() {}

    public static String accent(String text) {
        return ansi().fgRgb(0x95, 0x80, 0xff).a(text).reset().toString();
    }

    public static String cyan(String text) {
        return ansi().fgRgb(0x30, 0xd6, 0xe8).a(text).reset().toString();
    }

    public static String success(String text) {
        return ansi().fgRgb(0x8a, 0xff, 0x80).a(text).reset().toString();
    }

    public static String danger(String text) {
        return ansi().fgRgb(0xff, 0x95, 0x80).a(text).reset().toString();
    }

    public static String muted(String text) {
        return ansi().fgRgb(0x7e, 0x7f, 0x86).a(text).reset().toString();
    }

    public static String bold(String text) {
        return ansi().bold().a(text).reset().toString();
    }

    public static String badge(boolean ok, String label) {
        return (ok ? success("●") : danger("●")) + " " + label;
    }

    public static String header() {
        return bold(accent("komm")) + bold(muted("server")) + "\n" + muted("─".repeat(28));
    }
}
