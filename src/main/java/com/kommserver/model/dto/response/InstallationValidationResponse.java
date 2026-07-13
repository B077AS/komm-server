package com.kommserver.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstallationValidationResponse {
    private String certificate;
    private String hubPublicKey;
    private String installationName;
    private UUID installationId;
    private UUID ownerId;
}