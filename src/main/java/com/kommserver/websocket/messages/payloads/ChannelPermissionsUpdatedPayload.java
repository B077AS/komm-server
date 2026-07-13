package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelPermissionsUpdatedPayload {
    private UUID channelId;
    /** role name → {allowPermissions, denyPermissions} */
    private Map<String, RoleOverride> roleOverrides;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleOverride {
        private List<String> allowPermissions;
        private List<String> denyPermissions;
    }
}
