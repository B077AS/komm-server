package com.kommserver.websocket.messages.payloads;

import com.google.gson.JsonElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermProxyResponsePayload {
    private String correlationId;
    private boolean success;
    private String error;
    private JsonElement data;
}
