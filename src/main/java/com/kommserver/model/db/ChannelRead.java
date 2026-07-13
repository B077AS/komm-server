package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(ChannelRead.PK.class)
@Table(name = "channel_reads", indexes = {
        @Index(name = "idx_channel_reads_user", columnList = "user_id")
})
public class ChannelRead {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Id
    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @Column(name = "last_read_at", nullable = false)
    private LocalDateTime lastReadAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private UUID userId;
        private UUID channelId;
    }
}
