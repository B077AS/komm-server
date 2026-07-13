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

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "message_reactions", indexes = {
        @Index(name = "idx_reaction_message", columnList = "message_id"),
        @Index(name = "idx_reaction_user", columnList = "user_id")
})
public class MessageReaction {

    @EmbeddedId
    private MessageReactionId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("messageId")
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    private Message message;

    @CreationTimestamp
    @Column(name = "reacted_at", updatable = false)
    private LocalDateTime reactedAt;

    @Data
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageReactionId implements Serializable {

        @Column(name = "message_id", nullable = false, updatable = false, length = 36)
        private UUID messageId;

        @Column(name = "user_id", nullable = false, updatable = false, length = 36)
        private UUID userId;

        @Column(name = "emoji", nullable = false, updatable = false, length = 64)
        private String emoji;
    }
}