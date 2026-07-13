package com.kommserver.controller;

import com.kommserver.model.db.Permission;
import com.kommserver.model.db.Server;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.repository.ServerRepository;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/server")
public class ServerController {

    private final SecurityUtil securityUtil;
    private final PermissionService permissionService;
    private final ServerRepository serverRepository;

    @PutMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> body) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (!permissionService.has(userId, serverId, Permission.EDIT_SERVER_INFO)) {
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: EDIT_SERVER_INFO");
            }

            Server server = serverRepository.findById(serverId)
                    .orElseThrow(() -> new RuntimeException("Server not found"));

            if (body.containsKey("defaultChannelPanelWidth")) {
                Object val = body.get("defaultChannelPanelWidth");
                server.setDefaultChannelPanelWidth(val != null ? ((Number) val).intValue() : null);
            }

            serverRepository.save(server);
            return ResponseEntity.ok().build();

        } catch (SecurityException e) {
            return ErrorResponse.of(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update server settings: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
