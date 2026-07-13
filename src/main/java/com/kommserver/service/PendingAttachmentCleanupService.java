package com.kommserver.service;

import com.kommserver.model.db.PendingChannelAttachment;
import com.kommserver.repository.PendingChannelAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PendingAttachmentCleanupService {

    private final PendingChannelAttachmentRepository pendingAttachmentRepository;

    @Value("${komm.attachments.pending-ttl-minutes:30}")
    private int ttlMinutes;

    @Scheduled(fixedDelayString = "${komm.attachments.cleanup-interval-ms:300000}")
    public void cleanUpStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(ttlMinutes);
        List<PendingChannelAttachment> stale = pendingAttachmentRepository.findByUploadedAtBefore(cutoff);
        if (stale.isEmpty()) return;

        int deleted = 0;
        for (PendingChannelAttachment pending : stale) {
            try {
                if (pending.getFilePath() != null) {
                    Files.deleteIfExists(Paths.get(pending.getFilePath()));
                }
                pendingAttachmentRepository.delete(pending);
                deleted++;
            } catch (IOException e) {
                log.warn("Failed to delete stale pending attachment file {}: {}", pending.getFilePath(), e.getMessage());
            }
        }
        log.info("Cleaned up {} stale pending channel attachment(s) older than {} minutes", deleted, ttlMinutes);
    }
}
