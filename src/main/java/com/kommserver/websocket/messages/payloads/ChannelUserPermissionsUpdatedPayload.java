package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Sent directly to the affected user when their per-user channel override changes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelUserPermissionsUpdatedPayload {
    private UUID channelId;
    private List<String> allowPermissions;
    private List<String> denyPermissions;
}
