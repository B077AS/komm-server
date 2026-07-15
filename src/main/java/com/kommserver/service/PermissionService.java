package com.kommserver.service;

import com.kommserver.model.db.*;
import com.kommserver.model.dto.request.CustomRoleCreateRequest;
import com.kommserver.model.dto.request.CustomRoleUpdateRequest;
import com.kommserver.model.dto.summary.CustomRoleSummary;
import com.kommserver.model.dto.summary.ServerMemberSummary;
import com.kommserver.model.dto.summary.ServerPermissionsSummary;
import com.kommserver.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final ServerMemberRepository serverMemberRepository;
    private final ServerRolePermissionRepository rolePermissionRepository;
    private final ServerCustomRoleRepository customRoleRepository;
    private final ServerMemberCustomRoleRepository memberCustomRoleRepository;
    private final ChannelRolePermissionRepository channelRolePermissionRepository;
    private final ChannelCustomRolePermissionRepository channelCustomRolePermissionRepository;
    private final ChannelUserPermissionRepository channelUserPermissionRepository;

    // Self-reference so that @Cacheable on effectiveChannelPermissions is honoured
    // when called from within this class (Spring proxy is bypassed on plain this.x() calls).
    @Lazy
    @Autowired
    private PermissionService self;

    // ── Startup migration ─────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migratePermissions() {
        List<ServerRolePermission> all = rolePermissionRepository.findAll();
        int updated = 0;
        for (ServerRolePermission srp : all) {
            List<String> defaults = Permission.defaultPermissionsFor(srp.getRole());
            List<String> stored = srp.getPermissions() != null ? new ArrayList<>(srp.getPermissions()) : new ArrayList<>();
            List<String> missing = defaults.stream().filter(p -> !stored.contains(p)).collect(Collectors.toList());
            if (!missing.isEmpty()) {
                stored.addAll(missing);
                srp.setPermissions(stored);
                rolePermissionRepository.save(srp);
                log.info("Added {} new permission(s) to role {} in serverId={}: {}", missing.size(), srp.getRole(), srp.getServerId(), missing);
                updated++;
            }
        }
        log.debug("Permission migration complete: {}/{} rows updated", updated, all.size());
    }

    // ── Seeding ───────────────────────────────────────────────────────────────

    @Transactional
    public void seedDefaults(UUID serverId) {
        for (ServerMember.Role role : ServerMember.Role.values()) {
            ServerRolePermission srp = ServerRolePermission.builder()
                    .serverId(serverId)
                    .role(role)
                    .permissions(Permission.defaultPermissionsFor(role))
                    .build();
            rolePermissionRepository.save(srp);
        }
        log.debug("Seeded default permissions for serverId={}", serverId);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public ServerPermissionsSummary getServerPermissions(UUID serverId, UUID userId) {
        Map<String, List<String>> rolePerms = getServerRolePermissions(serverId);

        List<CustomRoleSummary> customRoles = customRoleRepository
                .findByServerIdOrderByPosition(serverId).stream()
                .map(CustomRoleSummary::from)
                .collect(Collectors.toList());

        List<UUID> myCustomRoleIds = memberCustomRoleRepository
                .findByUserIdAndServerId(userId, serverId).stream()
                .map(ServerMemberCustomRole::getRoleId)
                .collect(Collectors.toList());

        return ServerPermissionsSummary.builder()
                .serverId(serverId)
                .rolePermissions(rolePerms)
                .customRoles(customRoles)
                .myCustomRoleIds(myCustomRoleIds)
                .build();
    }

    public Map<String, List<String>> getServerRolePermissions(UUID serverId) {
        Map<String, List<String>> rolePerms = rolePermissionRepository.findByServerId(serverId).stream()
                .collect(Collectors.toMap(
                        r -> r.getRole().name(),
                        r -> r.getPermissions() != null ? r.getPermissions() : Permission.defaultPermissionsFor(r.getRole())));
        for (ServerMember.Role role : ServerMember.Role.values()) {
            rolePerms.putIfAbsent(role.name(), Permission.defaultPermissionsFor(role));
        }
        return rolePerms;
    }

    // ── Server members ────────────────────────────────────────────────────────

    public List<ServerMemberSummary> getServerMembers(UUID serverId) {
        return serverMemberRepository.findByServerId(serverId).stream()
                .map(m -> {
                    List<UUID> customRoleIds = memberCustomRoleRepository
                            .findByUserIdAndServerId(m.getUserId(), serverId).stream()
                            .map(ServerMemberCustomRole::getRoleId)
                            .collect(Collectors.toList());
                    return new ServerMemberSummary(m.getUserId(), m.getRole().name(), customRoleIds);
                })
                .collect(Collectors.toList());
    }

    public ServerMemberSummary getMember(UUID serverId, UUID targetUserId) {
        ServerMember member = serverMemberRepository.findByServerIdAndUserId(serverId, targetUserId)
                .orElse(null);
        if (member == null) return null;
        List<UUID> customRoleIds = memberCustomRoleRepository
                .findByUserIdAndServerId(targetUserId, serverId).stream()
                .map(ServerMemberCustomRole::getRoleId)
                .collect(Collectors.toList());
        return new ServerMemberSummary(targetUserId, member.getRole().name(), customRoleIds);
    }

    // ── Base role management ──────────────────────────────────────────────────

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public void changeBaseRole(UUID serverId, UUID userId, UUID targetUserId, ServerMember.Role targetRole) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);

        if (targetRole == ServerMember.Role.OWNER) {
            throw new IllegalArgumentException("Cannot assign the OWNER role");
        }

        int callerRank = roleRank(getMemberRole(userId, serverId));
        if (roleRank(targetRole) >= callerRank) {
            throw new SecurityException("Cannot assign a role of equal or higher rank than your own");
        }

        ServerMember member = serverMemberRepository.findByServerIdAndUserId(serverId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User is not a member of this server"));

        if (member.getRole() == ServerMember.Role.OWNER) {
            throw new SecurityException("Cannot change the server owner's role");
        }

        if (roleRank(member.getRole()) >= callerRank) {
            throw new SecurityException("Cannot change the role of a member with equal or higher rank than your own");
        }

        member.setRole(targetRole);
        serverMemberRepository.save(member);
        log.info("Changed base role of userId={} to {} in serverId={}", targetUserId, targetRole, serverId);
    }

    // ── Base role permissions ─────────────────────────────────────────────────

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public void updateRolePermission(UUID serverId, UUID userId, ServerMember.Role role, List<String> permissions) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);

        ServerMember.Role callerRole = getMemberRole(userId, serverId);
        if (callerRole != ServerMember.Role.OWNER) {
            if (roleRank(role) >= roleRank(callerRole)) {
                throw new SecurityException("Cannot edit permissions of a role equal to or higher than your own");
            }
        }

        ServerRolePermission srp = rolePermissionRepository
                .findByServerIdAndRole(serverId, role)
                .orElse(ServerRolePermission.builder().serverId(serverId).role(role).build());
        srp.setPermissions(sanitize(permissions));
        rolePermissionRepository.save(srp);
        log.info("Updated {} permissions for serverId={}", role, serverId);
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public void resetRoleToDefault(UUID serverId, UUID userId, ServerMember.Role role) {
        updateRolePermission(serverId, userId, role, Permission.defaultPermissionsFor(role));
    }

    // ── Custom roles ──────────────────────────────────────────────────────────

    @Transactional
    public CustomRoleSummary createCustomRole(UUID serverId, UUID userId, CustomRoleCreateRequest req) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);
        requireWithinCallerBasePerms(serverId, userId, req.getPermissions());

        int nextPosition = customRoleRepository.findByServerIdOrderByPosition(serverId).size();

        ServerCustomRole entity = ServerCustomRole.builder()
                .roleId(UUID.randomUUID())
                .serverId(serverId)
                .roleName(req.getRoleName())
                .color(req.getColor())
                .permissions(sanitize(req.getPermissions()))
                .position(nextPosition)
                .build();
        entity = customRoleRepository.save(entity);

        log.info("Created custom role '{}' for serverId={}", req.getRoleName(), serverId);
        return CustomRoleSummary.from(entity);
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public CustomRoleSummary updateCustomRole(UUID serverId, UUID userId, UUID roleId, CustomRoleUpdateRequest req) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);
        requireWithinCallerBasePerms(serverId, userId, req.getPermissions());

        ServerCustomRole entity = customRoleRepository.findById(roleId)
                .filter(r -> r.getServerId().equals(serverId))
                .orElseThrow(() -> new IllegalArgumentException("Custom role not found"));

        entity.setRoleName(req.getRoleName());
        entity.setColor(req.getColor());
        entity.setPermissions(sanitize(req.getPermissions()));
        entity = customRoleRepository.save(entity);

        log.info("Updated custom role {} for serverId={}", roleId, serverId);
        return CustomRoleSummary.from(entity);
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public void deleteCustomRole(UUID serverId, UUID userId, UUID roleId) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);

        ServerCustomRole entity = customRoleRepository.findById(roleId)
                .filter(r -> r.getServerId().equals(serverId))
                .orElseThrow(() -> new IllegalArgumentException("Custom role not found"));

        requireWithinCallerBasePerms(serverId, userId, entity.getPermissions());

        memberCustomRoleRepository.deleteByRoleId(roleId);
        channelCustomRolePermissionRepository.deleteByCustomRoleId(roleId);
        customRoleRepository.delete(entity);

        log.info("Deleted custom role {} from serverId={}", roleId, serverId);
    }

    // ── Role assignment ───────────────────────────────────────────────────────

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public void assignCustomRole(UUID serverId, UUID userId, UUID targetUserId, UUID roleId) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);

        ServerCustomRole role = customRoleRepository.findById(roleId)
                .filter(r -> r.getServerId().equals(serverId))
                .orElseThrow(() -> new IllegalArgumentException("Custom role not found"));

        // Roles with EDIT_SERVER_PERMS can only be assigned by OWNER
        if (role.getPermissions() != null
                && role.getPermissions().contains(Permission.EDIT_SERVER_PERMS.name())) {
            if (getMemberRole(userId, serverId) != ServerMember.Role.OWNER) {
                throw new SecurityException("Only the server owner can assign roles with EDIT_SERVER_PERMS");
            }
        }

        // Cannot assign a role whose permissions exceed the assigner's own effective permissions
        if (role.getPermissions() != null && !role.getPermissions().isEmpty()) {
            Set<String> callerEffective = effectiveServerPermissions(userId, serverId);
            List<String> overflowing = role.getPermissions().stream()
                    .filter(p -> !callerEffective.contains(p))
                    .collect(Collectors.toList());
            if (!overflowing.isEmpty()) {
                throw new SecurityException("Cannot assign a role containing permissions you don't have: " + overflowing);
            }
        }

        ServerMemberCustomRole.ServerMemberCustomRoleId id =
                new ServerMemberCustomRole.ServerMemberCustomRoleId(targetUserId, serverId, roleId);
        if (!memberCustomRoleRepository.existsById(id)) {
            memberCustomRoleRepository.save(ServerMemberCustomRole.builder()
                    .userId(targetUserId).serverId(serverId).roleId(roleId).build());
        }
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    @Transactional
    public void removeCustomRole(UUID serverId, UUID userId, UUID targetUserId, UUID roleId) {
        requirePermission(userId, serverId, Permission.EDIT_SERVER_PERMS);

        ServerCustomRole role = customRoleRepository.findById(roleId)
                .filter(r -> r.getServerId().equals(serverId))
                .orElseThrow(() -> new IllegalArgumentException("Custom role not found"));

        if (role.getPermissions() != null
                && role.getPermissions().contains(Permission.EDIT_SERVER_PERMS.name())) {
            if (getMemberRole(userId, serverId) != ServerMember.Role.OWNER) {
                throw new SecurityException("Only the server owner can remove roles with EDIT_SERVER_PERMS");
            }
        }

        ServerMemberCustomRole.ServerMemberCustomRoleId id =
                new ServerMemberCustomRole.ServerMemberCustomRoleId(targetUserId, serverId, roleId);
        memberCustomRoleRepository.deleteById(id);
    }

    // ── Effective permission computation ──────────────────────────────────────

    public Set<String> effectiveServerPermissions(UUID userId, UUID serverId) {
        ServerMember member = serverMemberRepository.findByServerIdAndUserId(serverId, userId)
                .orElse(null);
        return effectiveServerPermissions(member, serverId);
    }

    private Set<String> effectiveServerPermissions(ServerMember member, UUID serverId) {
        if (member == null) return Collections.emptySet();
        if (member.getRole() == ServerMember.Role.OWNER) return new HashSet<>(Permission.allNames());

        Set<String> effective = new HashSet<>(rolePermissionRepository
                .findByServerIdAndRole(serverId, member.getRole())
                .map(srp -> srp.getPermissions() != null
                        ? srp.getPermissions()
                        : Permission.defaultPermissionsFor(member.getRole()))
                .orElseGet(() -> Permission.defaultPermissionsFor(member.getRole())));

        List<UUID> customRoleIds = memberCustomRoleRepository
                .findByUserIdAndServerId(member.getUserId(), serverId).stream()
                .map(ServerMemberCustomRole::getRoleId)
                .collect(Collectors.toList());

        if (!customRoleIds.isEmpty()) {
            customRoleRepository.findByServerIdAndRoleIdIn(serverId, customRoleIds)
                    .forEach(cr -> { if (cr.getPermissions() != null) effective.addAll(cr.getPermissions()); });
        }
        return effective;
    }

    @Cacheable(value = "channelPerms", key = "#userId + ':' + #channelId")
    public Set<String> effectiveChannelPermissions(UUID userId, UUID serverId, UUID channelId) {
        ServerMember member = serverMemberRepository.findByServerIdAndUserId(serverId, userId)
                .orElse(null);
        if (member == null) return Collections.emptySet();
        if (member.getRole() == ServerMember.Role.OWNER) return new HashSet<>(Permission.allNames());

        Set<String> effective = effectiveServerPermissions(member, serverId);
        // VIEW_CHANNEL is on by default for every role; channel-level overrides can deny it
        effective.add(Permission.VIEW_CHANNEL.name());

        // Base role-level channel override
        ChannelRolePermission chPerm = channelRolePermissionRepository
                .findByChannelIdAndRole(channelId, member.getRole()).orElse(null);
        if (chPerm != null) {
            if (chPerm.getDenyPermissions() != null) effective.removeAll(chPerm.getDenyPermissions());
            if (chPerm.getAllowPermissions() != null) effective.addAll(chPerm.getAllowPermissions());
        }

        // Custom role-level channel overrides
        List<UUID> customRoleIds = memberCustomRoleRepository
                .findByUserIdAndServerId(userId, member.getServerId())
                .stream().map(ServerMemberCustomRole::getRoleId).toList();
        if (!customRoleIds.isEmpty()) {
            for (ChannelCustomRolePermission crPerm :
                    channelCustomRolePermissionRepository.findByChannelId(channelId)) {
                if (customRoleIds.contains(crPerm.getCustomRoleId())) {
                    if (crPerm.getDenyPermissions() != null) effective.removeAll(crPerm.getDenyPermissions());
                    if (crPerm.getAllowPermissions() != null) effective.addAll(crPerm.getAllowPermissions());
                }
            }
        }

        // User-level channel override (highest priority)
        ChannelUserPermission userPerm = channelUserPermissionRepository
                .findByChannelIdAndUserId(channelId, userId).orElse(null);
        if (userPerm != null) {
            if (userPerm.getDenyPermissions() != null) effective.removeAll(userPerm.getDenyPermissions());
            if (userPerm.getAllowPermissions() != null) effective.addAll(userPerm.getAllowPermissions());
        }

        return effective;
    }

    public boolean has(UUID userId, UUID serverId, Permission permission) {
        return effectiveServerPermissions(userId, serverId).contains(permission.name());
    }

    public boolean hasInChannel(UUID userId, UUID serverId, UUID channelId, Permission permission) {
        return self.effectiveChannelPermissions(userId, serverId, channelId).contains(permission.name());
    }

    // ── Channel role permission management ────────────────────────────────────

    public List<ChannelRolePermission> getChannelPermissions(UUID channelId) {
        return channelRolePermissionRepository.findByChannelId(channelId);
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    public ChannelRolePermission upsertChannelRolePermission(UUID channelId,
                                                              ServerMember.Role role,
                                                              List<String> allowPermissions,
                                                              List<String> denyPermissions) {
        ChannelRolePermission.ChannelRolePermissionId id =
                new ChannelRolePermission.ChannelRolePermissionId(channelId, role);
        ChannelRolePermission entity = channelRolePermissionRepository.findById(id)
                .orElse(ChannelRolePermission.builder().channelId(channelId).role(role).build());
        entity.setAllowPermissions(sanitize(allowPermissions));
        entity.setDenyPermissions(sanitize(denyPermissions));
        return channelRolePermissionRepository.save(entity);
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    public void deleteChannelRolePermission(UUID channelId, ServerMember.Role role) {
        channelRolePermissionRepository.deleteById(
                new ChannelRolePermission.ChannelRolePermissionId(channelId, role));
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    public void upsertChannelCustomRolePermission(UUID channelId, UUID customRoleId,
                                                   List<String> allowPermissions,
                                                   List<String> denyPermissions) {
        ChannelCustomRolePermission.ChannelCustomRolePermissionId id =
                new ChannelCustomRolePermission.ChannelCustomRolePermissionId(channelId, customRoleId);
        ChannelCustomRolePermission entity = channelCustomRolePermissionRepository.findById(id)
                .orElse(ChannelCustomRolePermission.builder()
                        .channelId(channelId).customRoleId(customRoleId).build());
        entity.setAllowPermissions(sanitize(allowPermissions));
        entity.setDenyPermissions(sanitize(denyPermissions));
        channelCustomRolePermissionRepository.save(entity);
    }

    @CacheEvict(value = "channelPerms", allEntries = true)
    public void deleteChannelCustomRolePermission(UUID channelId, UUID customRoleId) {
        channelCustomRolePermissionRepository.deleteById(
                new ChannelCustomRolePermission.ChannelCustomRolePermissionId(channelId, customRoleId));
    }

    public List<ChannelCustomRolePermission> getChannelCustomRolePermissions(UUID channelId) {
        return channelCustomRolePermissionRepository.findByChannelId(channelId);
    }

    @Transactional
    @CacheEvict(value = "channelPerms", allEntries = true)
    public void deleteChannelPermissions(UUID channelId) {
        channelRolePermissionRepository.deleteByChannelId(channelId);
        channelCustomRolePermissionRepository.deleteByChannelId(channelId);
        channelUserPermissionRepository.deleteByChannelId(channelId);
    }

    // ── Channel user permission management ───────────────────────────────────

    public List<ChannelUserPermission> getChannelUserPermissions(UUID channelId) {
        return channelUserPermissionRepository.findByChannelId(channelId);
    }

    public Optional<ChannelUserPermission> getChannelUserPermission(UUID channelId, UUID userId) {
        return channelUserPermissionRepository.findByChannelIdAndUserId(channelId, userId);
    }

    @CacheEvict(value = "channelPerms", key = "#userId + ':' + #channelId")
    public ChannelUserPermission upsertChannelUserPermission(UUID channelId, UUID userId,
                                                              List<String> allowPermissions,
                                                              List<String> denyPermissions) {
        ChannelUserPermission.ChannelUserPermissionId id =
                new ChannelUserPermission.ChannelUserPermissionId(channelId, userId);
        ChannelUserPermission entity = channelUserPermissionRepository.findById(id)
                .orElse(ChannelUserPermission.builder().channelId(channelId).userId(userId).build());
        entity.setAllowPermissions(sanitize(allowPermissions));
        entity.setDenyPermissions(sanitize(denyPermissions));
        return channelUserPermissionRepository.save(entity);
    }

    @CacheEvict(value = "channelPerms", key = "#userId + ':' + #channelId")
    public void deleteChannelUserPermission(UUID channelId, UUID userId) {
        channelUserPermissionRepository.deleteById(
                new ChannelUserPermission.ChannelUserPermissionId(channelId, userId));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public void requireAllowWithinEffectivePerms(UUID serverId, UUID userId, List<String> allowPermissions) {
        if (allowPermissions == null || allowPermissions.isEmpty()) return;
        Set<String> effective = effectiveServerPermissions(userId, serverId);
        List<String> overflowing = allowPermissions.stream()
                .filter(p -> !effective.contains(p))
                .collect(Collectors.toList());
        if (!overflowing.isEmpty()) {
            throw new SecurityException("Cannot grant permissions you don't have: " + overflowing);
        }
    }

    private void requireWithinCallerBasePerms(UUID serverId, UUID userId, List<String> requestedPerms) {
        if (requestedPerms == null || requestedPerms.isEmpty()) return;
        ServerMember.Role callerRole = getMemberRole(userId, serverId);
        if (callerRole == ServerMember.Role.OWNER) return;
        List<String> callerBasePerms = rolePermissionRepository
                .findByServerIdAndRole(serverId, callerRole)
                .map(p -> p.getPermissions() != null ? p.getPermissions() : Permission.defaultPermissionsFor(callerRole))
                .orElseGet(() -> Permission.defaultPermissionsFor(callerRole));
        List<String> overflowing = requestedPerms.stream()
                .filter(p -> !callerBasePerms.contains(p))
                .collect(Collectors.toList());
        if (!overflowing.isEmpty()) {
            throw new SecurityException("Cannot grant permissions your own base role does not have: " + overflowing);
        }
    }

    private void requirePermission(UUID userId, UUID serverId, Permission permission) {
        if (!has(userId, serverId, permission)) {
            throw new SecurityException("Missing permission: " + permission.name());
        }
    }

    public ServerMember.Role getMemberRole(UUID userId, UUID serverId) {
        return serverMemberRepository.findByServerIdAndUserId(serverId, userId)
                .map(ServerMember::getRole)
                .orElseThrow(() -> new IllegalArgumentException("User is not a member of this server"));
    }

    private int roleRank(ServerMember.Role role) {
        return switch (role) {
            case MEMBER -> 0;
            case MODERATOR -> 1;
            case ADMIN -> 2;
            case OWNER -> 3;
        };
    }

    private List<String> sanitize(List<String> permissions) {
        if (permissions == null) return new ArrayList<>();
        Set<String> valid = new HashSet<>(Permission.allNames());
        return permissions.stream().filter(valid::contains).collect(Collectors.toList());
    }
}
