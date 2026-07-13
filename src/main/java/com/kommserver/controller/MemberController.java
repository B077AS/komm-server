package com.kommserver.controller;

import com.google.gson.Gson;
import com.kommserver.model.db.Permission;
import com.kommserver.model.db.ServerMember;
import com.kommserver.model.dto.request.PokeRequest;
import com.kommserver.model.dto.request.UpdatePokePreferenceRequest;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.model.dto.response.PokePreferenceResponse;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.security.SecurityUtil;
import com.kommserver.service.PermissionService;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.UserPokedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members")
public class MemberController {

    private final SecurityUtil securityUtil;
    private final PermissionService permissionService;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final Gson gson;

    @PostMapping("/poke")
    public ResponseEntity<?> pokeUser(@RequestBody PokeRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID senderId = securityUtil.getCurrentUserId();

            if (serverId == null || senderId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Invalid session");
            }

            if (req.getTargetUserId() == null) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "targetUserId is required");
            }

            if (senderId.equals(req.getTargetUserId())) {
                return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Cannot poke yourself");
            }

            if (!permissionService.has(senderId, serverId, Permission.POKE_USERS)) {
                log.warn("POKE: userId={} missing POKE_USERS on serverId={}", senderId, serverId);
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Missing permission: POKE_USERS");
            }

            ServerMember receiverMember = serverMemberRepository
                    .findByServerIdAndUserId(serverId, req.getTargetUserId())
                    .orElse(null);

            if (receiverMember == null) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Target user is not a member of this server");
            }

            if (!receiverMember.isPokesEnabled()) {
                log.debug("POKE: targetUserId={} has pokes disabled on serverId={}", req.getTargetUserId(), serverId);
                return ErrorResponse.of(HttpStatus.FORBIDDEN, "Target user has disabled pokes");
            }

            String message = req.getMessage() != null ? req.getMessage().trim() : "";
            if (message.length() > 100) {
                message = message.substring(0, 100);
            }

            String wsMsg = gson.toJson(WsMessage.builder()
                    .type(WsMessageType.USER_POKED)
                    .payload(gson.toJsonTree(UserPokedPayload.builder()
                            .senderUserId(senderId)
                            .message(message)
                            .build()).getAsJsonObject())
                    .build());

            clientMessageSender.sendToUser(serverId, req.getTargetUserId(), wsMsg);
            log.info("POKE: userId={} poked targetUserId={} on serverId={}", senderId, req.getTargetUserId(), serverId);

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Failed to send poke: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @GetMapping("/me/poke-preference")
    public ResponseEntity<?> getMyPokePreference() {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (serverId == null || userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Invalid session");
            }

            ServerMember member = serverMemberRepository
                    .findByServerIdAndUserId(serverId, userId)
                    .orElse(null);

            if (member == null) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Member not found");
            }

            return ResponseEntity.ok(PokePreferenceResponse.builder()
                    .enabled(member.isPokesEnabled())
                    .build());

        } catch (Exception e) {
            log.error("Failed to get poke preference: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    @PutMapping("/me/poke-preference")
    public ResponseEntity<?> updateMyPokePreference(@RequestBody UpdatePokePreferenceRequest req) {
        try {
            UUID serverId = securityUtil.getCurrentServerId();
            UUID userId = securityUtil.getCurrentUserId();

            if (serverId == null || userId == null) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Invalid session");
            }

            ServerMember member = serverMemberRepository
                    .findByServerIdAndUserId(serverId, userId)
                    .orElse(null);

            if (member == null) {
                return ErrorResponse.of(HttpStatus.NOT_FOUND, "Member not found");
            }

            member.setPokesEnabled(req.isEnabled());
            serverMemberRepository.save(member);

            WebrtcRoomsManager.ChannelRef currentRoom = webrtcRoomsManager.getCurrentRoomInServer(serverId, userId);
            if (currentRoom != null) {
                webrtcRoomsManager.updatePokesEnabled(serverId, currentRoom.channelId(), userId, req.isEnabled());
            }

            log.debug("POKE_PREF: userId={} set pokesEnabled={} on serverId={}", userId, req.isEnabled(), serverId);
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Failed to update poke preference: {}", e.getMessage(), e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }
}
