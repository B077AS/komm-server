package com.kommserver.websocket;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class ReconnectScheduler {

    @Setter
    private Runnable connectAction;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    public void scheduleReconnect() {
        if (reconnectScheduled.compareAndSet(false, true)) {
            log.info("Reconnecting in 15s...");
            scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                connectAction.run();
            }, 15, TimeUnit.SECONDS);
        }
    }

    public void resetFlag() {
        reconnectScheduled.set(false);
    }
}