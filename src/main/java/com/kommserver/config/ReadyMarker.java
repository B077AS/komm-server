package com.kommserver.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file in the data folder that exists exactly while the server is up and activated: written once startup has
 * finished, removed when it stops (and at the very start of the next run, in case it was killed). Nothing else reads
 * it inside the server; it is for things outside the JVM that need a yes/no without knowing the port, such as the
 * container's health check ({@code test -f /data/ready}).
 */
@Slf4j
@Component
public class ReadyMarker {

    public static final String FILE = "ready";

    public static void clear() {
        try {
            Files.deleteIfExists(path());
        } catch (IOException e) {
            log.warn("Cannot remove {}: {}", path(), e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Files.writeString(path(), "");
        } catch (IOException e) {
            log.warn("Cannot write {}: {}", path(), e.getMessage());
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onClosed() {
        clear();
    }

    private static Path path() {
        return DataDir.resolve(FILE);
    }
}
