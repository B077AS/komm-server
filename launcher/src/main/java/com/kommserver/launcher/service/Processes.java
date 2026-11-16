package com.kommserver.launcher.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs a system command and collects what it printed. */
final class Processes {

    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private Processes() {
    }

    static Result run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return new Result(process.waitFor(), output);
        } catch (IOException e) {
            return new Result(127, command[0] + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "interrupted");
        }
    }

    static void require(Result result, String what, String hint) {
        if (!result.ok()) {
            String output = result.output().trim();
            throw new IllegalStateException(what + " failed" + (output.isEmpty() ? "" : ": " + output) + (hint == null ? "" : " (" + hint + ")"));
        }
    }
}
