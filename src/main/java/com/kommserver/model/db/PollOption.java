package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "poll_options",
        indexes = @Index(name = "idx_poll_option_message", columnList = "message_id"),
        uniqueConstraints = @UniqueConstraint(name = "uq_poll_option_position", columnNames = {"message_id", "position"}))
public class PollOption {

    @Id
    @UuidGenerator
    @Column(name = "option_id", nullable = false, updatable = false, length = 36)
    private UUID optionId;

    @Column(name = "message_id", nullable = false, updatable = false, length = 36)
    private UUID messageId;

    @Column(name = "position", nullable = false)
    private short position;

    @Column(name = "text", nullable = false, length = 100)
    private String text;
}
