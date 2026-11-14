package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/** One row per (poll, option, user). {@code message_id} leads the key so a poll's votes are one indexed range. */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "poll_votes", indexes = {
        @Index(name = "idx_poll_vote_user", columnList = "message_id, user_id")
})
public class PollVote {

    @EmbeddedId
    private PollVoteId id;

    @CreationTimestamp
    @Column(name = "voted_at", updatable = false)
    private LocalDateTime votedAt;

    @Data
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PollVoteId implements Serializable {

        @Column(name = "message_id", nullable = false, updatable = false, length = 36)
        private UUID messageId;

        @Column(name = "option_id", nullable = false, updatable = false, length = 36)
        private UUID optionId;

        @Column(name = "user_id", nullable = false, updatable = false, length = 36)
        private UUID userId;
    }
}
