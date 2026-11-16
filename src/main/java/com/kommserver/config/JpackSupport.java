package com.kommserver.config;

import io.github.b077as.jpack.Jpack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The link to the jpack stub that starts the server in an installed copy (nothing happens when run from the IDE or with
 * {@code java -jar}).
 * <p>
 * The first start after an update is a trial: if the new version dies before it says hello, the stub puts the previous
 * version back. Saying hello once the server is up (not when the JVM starts) is what makes that check mean "the server
 * works", whatever the boot takes (embedded Postgres, the hub, the SFU).
 */
@Slf4j
@Component
public class JpackSupport {

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        sayStarted();
    }

    /** Also called before a deliberate restart (new TLS/ports after activation): that exit is not a failed version. */
    public static void sayStarted() {
        if (!Jpack.isAvailable()) {
            return;
        }
        try (Jpack jpack = Jpack.connect()) {
            log.info("Running as version {} of the installed komm-server", jpack.appVersion());
        } catch (RuntimeException e) {
            log.warn("Could not reach the jpack stub: {}", e.getMessage());
        }
    }
}
