package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Poll settings for a {@link Message} of type {@code POLL} (1:1, keyed by the message id). The
 * question is the message's {@code content}; options and votes live in {@link PollOption} /
 * {@link PollVote}. Like {@link MessageAttachment}, rows reference the message by plain id (no
 * FK) and are deleted explicitly wherever messages are deleted.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "polls", indexes = {
        @Index(name = "idx_poll_open_closes_at", columnList = "closed_at, closes_at")
})
public class Poll {

    @Id
    @Column(name = "message_id", nullable = false, updatable = false, length = 36)
    private UUID messageId;

    @Column(name = "allow_multiple", nullable = false)
    private boolean allowMultiple;

    @Column(name = "anonymous", nullable = false)
    private boolean anonymous;

    /** When the poll stops accepting votes; null = open until closed by hand. */
    @Column(name = "closes_at")
    private LocalDateTime closesAt;

    /** Set once the poll is closed (by hand or by {@code PollService.closeExpiredPolls}). */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public boolean isClosedAt(LocalDateTime now) {
        return closedAt != null || (closesAt != null && !now.isBefore(closesAt));
    }
}
