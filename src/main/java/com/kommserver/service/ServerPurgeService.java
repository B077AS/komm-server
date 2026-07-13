package com.kommserver.service;

import com.kommserver.model.db.Server;
import com.kommserver.repository.ServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Background worker that purges servers flagged for deletion. Runs on a fixed interval and once on
 * startup, so a restart resumes any deletion that did not finish (the {@code pending_deletion} flag
 * is persisted). Each server is purged in its own transaction so one failure does not block others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerPurgeService {

    private final ServerRepository serverRepository;
    private final ServerDeletionService serverDeletionService;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeOnStartup() {
        purgePending();
    }

    @Scheduled(fixedDelayString = "${komm.server-purge.interval-ms:30000}",
               initialDelayString = "${komm.server-purge.interval-ms:30000}")
    public void scheduledPurge() {
        purgePending();
    }

    private void purgePending() {
        List<Server> pending = serverRepository.findByPendingDeletionTrue();
        if (pending.isEmpty()) return;
        log.info("Found {} server(s) pending deletion", pending.size());
        for (Server server : pending) {
            try {
                serverDeletionService.purgeServer(server.getServerId());
            } catch (Exception e) {
                log.error("Failed to purge serverId={} — will retry next cycle: {}",
                        server.getServerId(), e.getMessage(), e);
            }
        }
    }
}
