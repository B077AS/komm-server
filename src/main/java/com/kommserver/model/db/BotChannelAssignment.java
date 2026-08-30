package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** Many-to-many join: which channels a bot is active in. */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "bot_channel_assignments",
        indexes = @Index(name = "idx_bca_channel", columnList = "channel_id"))
@IdClass(BotChannelAssignment.BotChannelAssignmentId.class)
public class BotChannelAssignment {

    @Id
    @Column(name = "bot_id", nullable = false, length = 36)
    private UUID botId;

    @Id
    @Column(name = "channel_id", nullable = false, length = 36)
    private UUID channelId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BotChannelAssignmentId implements Serializable {
        private UUID botId;
        private UUID channelId;
    }
}
