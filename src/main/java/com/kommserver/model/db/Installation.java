package com.kommserver.model.db;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "installations")
public class Installation {

    @Id
    @Column(name = "installation_id", nullable = false, updatable = false, length = 36)
    private UUID installationId;

    @Column(name = "installation_name", nullable = false)
    private String installationName;

    @Column(name = "certificate", columnDefinition = "TEXT")
    private String certificate;

    @Column(name = "hub_public_key", columnDefinition = "TEXT")
    private String hubPublicKey;

    @Column(name = "owner_id", nullable = false, length = 36)
    private UUID ownerId;
}
