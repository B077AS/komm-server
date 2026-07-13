package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "messages", indexes = {
        @Index(name = "sender_id", columnList = "sender_id"),
        @Index(name = "replied_to_id", columnList = "replied_to_id"),
        @Index(name = "idx_channel_timestamp", columnList = "channel_id, sent_at")
})
public class Message {

    @Id
    @UuidGenerator
    @Column(name = "message_id", nullable = false, updatable = false, length = 36)
    private UUID messageId;

    @Column(name = "sender_id", nullable = false, length = 36)
    private UUID senderId;

    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", insertable = false, updatable = false)
    private Channel channel;

    @Column(name = "server_id", nullable = false, length = 36)
    private UUID serverId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private Server server;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "has_attachments", nullable = false)
    private Boolean hasAttachments = false;

    @Column(name = "is_edited", nullable = false)
    private Boolean isEdited = false;

    @Column(name = "replied_to_id", length = 36)
    private UUID repliedToId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @OneToMany(mappedBy = "message", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<MessageReaction> reactions = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", length = 10)
    @Builder.Default
    private MessageType messageType = MessageType.TEXT;

    @Column(name = "code_language", length = 32)
    private String codeLanguage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum MessageType {
        TEXT, GIF, CODE
    }
}