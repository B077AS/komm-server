package com.kommserver.controller;

import com.google.gson.Gson;
import com.kommserver.model.db.ChannelRolePermission;
import com.kommserver.model.db.ChannelUserPermission;
import com.kommserver.model.db.Permission;
import com.kommserver.model.db.ServerMember;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.model.dto.summary.ChannelSummary;
import com.kommserver.model.dto.summary.ServerMemberSummary;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.ChannelService;
import com.kommserver.service.ChannelVisibilityService;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelCreatedPayload;
import com.kommserver.websocket.messages.payloads.ChannelDeletedPayload;
import com.kommserver.websocket.messages.payloads.ChannelPermissionsUpdatedPayload;
import com.kommserver.websocket.messages.payloads.ChannelUserPermissionsUpdatedPayload;
import com.kommserver.websocket.messages.payloads.CustomRoleMemberPayload;
import com.kommserver.websocket.messages.payloads.MemberRoleUpdatedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/permissions")
public class PermissionController {

    private final SecurityUtil securityUtil;
    private final PermissionService permissionService;
    private final ChannelService channelService;
    private final ChannelVisibilityService channelVisibilityService;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final com.kommserver.websocket.senders.HubMessageSender hubMessageSender;
    private final Gson gson;

    // ── Server permissions summary ────────────────────────────────────────────

    @GetMapping("/server")
    public ResponseEntity<?> getServerPermissions() {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            return ResponseEntity.ok(permissionService.getServerPermissions(serverId, userId));
        } catch (Exception e) {
            log.error("Failed to get server permissions: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Server members ────────────────────────────────────────────────────────

    @GetMapping("/server/members/{targetUserId}")
    public ResponseEntity<?> getServerMember(@PathVariable UUID targetUserId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Not a member of this server");
            }
            ServerMemberSummary member = permissionService.getMember(serverId, targetUserId);
            if (member == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Member not found");
            return ResponseEntity.ok(member);
        } catch (Exception e) {
            log.error("Failed to get server member: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/server/members/{targetUserId}/role")
    public ResponseEntity<?> changeBaseRole(@PathVariable UUID targetUserId,
                                             @RequestBody ChangeBaseRoleRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            ServerMember.Role targetRole = ServerMember.Role.valueOf(req.getRole().toUpperCase());
            Map<UUID, Boolean> before = snapshotUserViewAllChannels(serverId, targetUserId);
            permissionService.changeBaseRole(serverId, userId, targetUserId, targetRole);
            Map<UUID, Boolean> after = snapshotUserViewAllChannels(serverId, targetUserId);
            broadcastMemberRoleUpdated(serverId, targetUserId, targetRole);
            applyUserViewChanges(serverId, targetUserId, before, after);
            hubMessageSender.send(WsMessageType.MEMBER_ROLE_UPDATED,
                    MemberRoleUpdatedPayload.builder()
                            .serverId(serverId)
                            .userId(targetUserId)
                            .newRole(targetRole.name())
                            .build());
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to change base role: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Custom role assignment ────────────────────────────────────────────────

    @PostMapping("/server/custom-roles/{roleId}/members/{targetUserId}")
    public ResponseEntity<?> assignCustomRole(@PathVariable UUID roleId,
                                               @PathVariable UUID targetUserId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            Map<UUID, Boolean> before = snapshotUserViewAllChannels(serverId, targetUserId);
            permissionService.assignCustomRole(serverId, userId, targetUserId, roleId);
            Map<UUID, Boolean> after = snapshotUserViewAllChannels(serverId, targetUserId);
            broadcastCustomRoleMemberAssigned(serverId, roleId, targetUserId);
            applyUserViewChanges(serverId, targetUserId, before, after);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to assign custom role: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/server/custom-roles/{roleId}/members/{targetUserId}")
    public ResponseEntity<?> removeCustomRole(@PathVariable UUID roleId,
                                               @PathVariable UUID targetUserId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            Map<UUID, Boolean> before = snapshotUserViewAllChannels(serverId, targetUserId);
            permissionService.removeCustomRole(serverId, userId, targetUserId, roleId);
            Map<UUID, Boolean> after = snapshotUserViewAllChannels(serverId, targetUserId);
            broadcastCustomRoleMemberRemoved(serverId, roleId, targetUserId);
            applyUserViewChanges(serverId, targetUserId, before, after);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to remove custom role: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Channel permissions ───────────────────────────────────────────────────

    @GetMapping("/channels/{channelId}")
    public ResponseEntity<?> getChannelPermissions(@PathVariable UUID channelId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Not a member of this server");
            }
            Map<String, ChannelPermissionsUpdatedPayload.RoleOverride> overrides = new java.util.HashMap<>();
            permissionService.getChannelPermissions(channelId).forEach(p ->
                    overrides.put(p.getRole().name(), new ChannelPermissionsUpdatedPayload.RoleOverride(
                            p.getAllowPermissions(), p.getDenyPermissions())));
            permissionService.getChannelCustomRolePermissions(channelId).forEach(p ->
                    overrides.put(p.getCustomRoleId().toString(), new ChannelPermissionsUpdatedPayload.RoleOverride(
                            p.getAllowPermissions(), p.getDenyPermissions())));
            return ResponseEntity.ok(
                    ChannelPermissionsUpdatedPayload.builder()
                            .channelId(channelId)
                            .roleOverrides(overrides)
                            .build());
        } catch (Exception e) {
            log.error("Failed to get channel permissions: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/channels/{channelId}/roles/{role}")
    public ResponseEntity<?> updateChannelRolePermission(
            @PathVariable UUID channelId,
            @PathVariable String role,
            @RequestBody ChannelRolePermissionRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            ServerMember.Role roleEnum = ServerMember.Role.valueOf(role.toUpperCase());
            permissionService.requireAllowWithinEffectivePerms(serverId, userId, req.getAllowPermissions());
            Map<UUID, Boolean> before = snapshotViewChannel(serverId, channelId);
            permissionService.upsertChannelRolePermission(
                    channelId, roleEnum, req.getAllowPermissions(), req.getDenyPermissions());
            Map<UUID, Boolean> after = snapshotViewChannel(serverId, channelId);
            broadcastChannelPermissionsUpdated(serverId, channelId);
            applyViewChannelChanges(serverId, channelId, before, after);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Unknown role: " + role);
        } catch (Exception e) {
            log.error("Failed to update channel role permission: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/channels/{channelId}/roles/{role}")
    public ResponseEntity<?> deleteChannelRolePermission(
            @PathVariable UUID channelId, @PathVariable String role) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            ServerMember.Role roleEnum = ServerMember.Role.valueOf(role.toUpperCase());
            Map<UUID, Boolean> before = snapshotViewChannel(serverId, channelId);
            permissionService.deleteChannelRolePermission(channelId, roleEnum);
            Map<UUID, Boolean> after = snapshotViewChannel(serverId, channelId);
            broadcastChannelPermissionsUpdated(serverId, channelId);
            applyViewChannelChanges(serverId, channelId, before, after);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Unknown role: " + role);
        } catch (Exception e) {
            log.error("Failed to delete channel role permission: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/channels/{channelId}/custom-roles/{customRoleId}")
    public ResponseEntity<?> updateChannelCustomRolePermission(
            @PathVariable UUID channelId,
            @PathVariable UUID customRoleId,
            @RequestBody ChannelRolePermissionRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            permissionService.requireAllowWithinEffectivePerms(serverId, userId, req.getAllowPermissions());
            Map<UUID, Boolean> before = snapshotViewChannel(serverId, channelId);
            permissionService.upsertChannelCustomRolePermission(
                    channelId, customRoleId, req.getAllowPermissions(), req.getDenyPermissions());
            Map<UUID, Boolean> after = snapshotViewChannel(serverId, channelId);
            broadcastChannelPermissionsUpdated(serverId, channelId);
            applyViewChannelChanges(serverId, channelId, before, after);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update channel custom role permission: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/channels/{channelId}/custom-roles/{customRoleId}")
    public ResponseEntity<?> deleteChannelCustomRolePermission(
            @PathVariable UUID channelId,
            @PathVariable UUID customRoleId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            Map<UUID, Boolean> before = snapshotViewChannel(serverId, channelId);
            permissionService.deleteChannelCustomRolePermission(channelId, customRoleId);
            Map<UUID, Boolean> after = snapshotViewChannel(serverId, channelId);
            broadcastChannelPermissionsUpdated(serverId, channelId);
            applyViewChannelChanges(serverId, channelId, before, after);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete channel custom role permission: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/channels/{channelId}/users")
    public ResponseEntity<?> getChannelUserPermissions(@PathVariable UUID channelId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            List<ChannelUserPermission> perms = permissionService.getChannelUserPermissions(channelId);
            return ResponseEntity.ok(perms);
        } catch (Exception e) {
            log.error("Failed to get channel user permissions: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/channels/{channelId}/users/{targetUserId}")
    public ResponseEntity<?> upsertChannelUserPermission(
            @PathVariable UUID channelId,
            @PathVariable UUID targetUserId,
            @RequestBody ChannelRolePermissionRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            permissionService.requireAllowWithinEffectivePerms(serverId, userId, req.getAllowPermissions());
            boolean hadView = permissionService.hasInChannel(targetUserId, serverId, channelId, Permission.VIEW_CHANNEL);
            permissionService.upsertChannelUserPermission(
                    channelId, targetUserId, req.getAllowPermissions(), req.getDenyPermissions());
            boolean hasView = permissionService.hasInChannel(targetUserId, serverId, channelId, Permission.VIEW_CHANNEL);
            sendChannelUserPermissionsUpdated(serverId, channelId, targetUserId,
                    req.getAllowPermissions(), req.getDenyPermissions());
            sendUserViewChannelChange(serverId, channelId, targetUserId, hadView, hasView);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to upsert channel user permission: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/channels/{channelId}/users/{targetUserId}")
    public ResponseEntity<?> deleteChannelUserPermission(
            @PathVariable UUID channelId,
            @PathVariable UUID targetUserId) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();
            if (!permissionService.has(userId, serverId, Permission.EDIT_CHANNEL_PERMS)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_CHANNEL_PERMS");
            }
            boolean hadView = permissionService.hasInChannel(targetUserId, serverId, channelId, Permission.VIEW_CHANNEL);
            permissionService.deleteChannelUserPermission(channelId, targetUserId);
            boolean hasView = permissionService.hasInChannel(targetUserId, serverId, channelId, Permission.VIEW_CHANNEL);
            sendChannelUserPermissionsUpdated(serverId, channelId, targetUserId, List.of(), List.of());
            sendUserViewChannelChange(serverId, channelId, targetUserId, hadView, hasView);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete channel user permission: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private void broadcastMemberRoleUpdated(UUID serverId, UUID targetUserId, ServerMember.Role newRole) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.MEMBER_ROLE_UPDATED)
                .payload(MemberRoleUpdatedPayload.builder()
                        .serverId(serverId)
                        .userId(targetUserId)
                        .newRole(newRole.name())
                        .build())
                .build();
        clientMessageSender.sendToUser(serverId, targetUserId, gson.toJson(msg));
    }

    private void broadcastCustomRoleMemberAssigned(UUID serverId, UUID roleId, UUID targetUserId) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_MEMBER_ASSIGNED)
                .payload(CustomRoleMemberPayload.builder()
                        .serverId(serverId).roleId(roleId).targetUserId(targetUserId).build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }

    private void broadcastCustomRoleMemberRemoved(UUID serverId, UUID roleId, UUID targetUserId) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CUSTOM_ROLE_MEMBER_REMOVED)
                .payload(CustomRoleMemberPayload.builder()
                        .serverId(serverId).roleId(roleId).targetUserId(targetUserId).build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }

    private Map<UUID, Boolean> snapshotViewChannel(UUID serverId, UUID channelId) {
        return channelVisibilityService.snapshotViewChannel(serverId, channelId);
    }

    private void applyViewChannelChanges(UUID serverId, UUID channelId,
                                          Map<UUID, Boolean> before, Map<UUID, Boolean> after) {
        channelVisibilityService.applyViewChannelChanges(serverId, channelId, before, after);
    }

    private Map<UUID, Boolean> snapshotUserViewAllChannels(UUID serverId, UUID targetUserId) {
        return channelVisibilityService.snapshotUserViewAllChannels(serverId, targetUserId);
    }

    private void applyUserViewChanges(UUID serverId, UUID targetUserId,
                                       Map<UUID, Boolean> before, Map<UUID, Boolean> after) {
        channelVisibilityService.applyUserViewChanges(serverId, targetUserId, before, after);
    }

    private void sendUserViewChannelChange(UUID serverId, UUID channelId, UUID userId,
                                            boolean hadView, boolean hasView) {
        channelVisibilityService.sendUserViewChannelChange(serverId, channelId, userId, hadView, hasView);
    }

    private void broadcastChannelPermissionsUpdated(UUID serverId, UUID channelId) {
        // Include both base-role and custom-role overrides (keyed by role name / roleId), matching
        // the GET endpoint format — clients replace their whole override map with this payload.
        Map<String, ChannelPermissionsUpdatedPayload.RoleOverride> overrides = new HashMap<>();
        permissionService.getChannelPermissions(channelId).forEach(p ->
                overrides.put(p.getRole().name(), new ChannelPermissionsUpdatedPayload.RoleOverride(
                        p.getAllowPermissions(), p.getDenyPermissions())));
        permissionService.getChannelCustomRolePermissions(channelId).forEach(p ->
                overrides.put(p.getCustomRoleId().toString(), new ChannelPermissionsUpdatedPayload.RoleOverride(
                        p.getAllowPermissions(), p.getDenyPermissions())));
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CHANNEL_PERMISSIONS_UPDATED)
                .payload(ChannelPermissionsUpdatedPayload.builder()
                        .channelId(channelId).roleOverrides(overrides).build())
                .build();
        clientMessageSender.broadcastToServer(serverId, gson.toJson(msg));
    }

    private void sendChannelUserPermissionsUpdated(UUID serverId, UUID channelId, UUID targetUserId,
                                                    List<String> allowPermissions,
                                                    List<String> denyPermissions) {
        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CHANNEL_USER_PERMISSIONS_UPDATED)
                .payload(ChannelUserPermissionsUpdatedPayload.builder()
                        .channelId(channelId)
                        .allowPermissions(allowPermissions)
                        .denyPermissions(denyPermissions)
                        .build())
                .build();
        clientMessageSender.sendToUser(serverId, targetUserId, gson.toJson(msg));
    }

    // ── Inner request DTOs ────────────────────────────────────────────────────

    public static class ChangeBaseRoleRequest {
        private String role;
        public String getRole() { return role; }
    }

    public static class ChannelRolePermissionRequest {
        private List<String> allowPermissions;
        private List<String> denyPermissions;
        public List<String> getAllowPermissions() { return allowPermissions != null ? allowPermissions : new ArrayList<>(); }
        public List<String> getDenyPermissions()  { return denyPermissions  != null ? denyPermissions  : new ArrayList<>(); }
    }
}
