package com.kommserver.service;

import com.kommserver.model.db.Installation;
import com.kommserver.model.dto.request.LoginInstallationRequest;
import com.kommserver.model.dto.response.AuthResponse;
import com.kommserver.repository.InstallationRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.repository.ServerRepository;
import com.kommserver.security.JwtUtil;
import com.kommserver.security.UsedTicketStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtUtil jwtUtil;
    private final UsedTicketStore usedTicketStore;
    private final InstallationRepository installationRepository;
    private final ServerRepository serverRepository;
    private final ServerMemberRepository serverMemberRepository;

    @Value("${jwt.access-token.expiration}")
    private int accessTokenExpiration;

    public AuthResponse login(LoginInstallationRequest request) {
        if (request.getTicket() == null || request.getTicket().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket is required");
        }

        Claims claims = parseHubTicket(request.getTicket());

        // 1. Token type
        String type = claims.get("type", String.class);
        if (!"installation_ticket".equals(type)) {
            log.warn("Login rejected: wrong token type '{}'", type);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid ticket type");
        }

        // 2. Replay prevention
        String jti = claims.get("jti", String.class);
        if (jti == null || !usedTicketStore.markUsed(jti)) {
            log.warn("Login rejected: ticket already used or missing jti");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ticket already used");
        }

        // 3. Resolve own installation ID from DB
        UUID ownInstallationId = resolveOwnInstallationId();

        // 4. installationId claim must match our own
        String claimedInstallationId = claims.get("installationId", String.class);
        if (claimedInstallationId == null || !ownInstallationId.toString().equals(claimedInstallationId)) {
            log.warn("Login rejected: installationId mismatch — ticket={} own={}",
                    claimedInstallationId, ownInstallationId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ticket not issued for this installation");
        }

        // 5. serverId must exist in this installation's DB
        String rawServerId = claims.get("serverId", String.class);
        if (rawServerId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ticket serverId missing");
        }
        UUID serverId = UUID.fromString(rawServerId);
        if (!serverRepository.existsByServerId(serverId)) {
            log.warn("Login rejected: serverId={} not found in this installation", serverId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Server not found in this installation");
        }

        // 6. userId
        String ticketUserId = claims.get("userId", String.class);
        if (ticketUserId == null || ticketUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ticket userId missing");
        }
        UUID userId = UUID.fromString(ticketUserId);

        // 7. User must be a member of the server locally
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            log.warn("Login rejected: userId={} is not a member of serverId={}", userId, serverId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not a member of this server");
        }

        log.info("Hub ticket valid for userId={} serverId={}", userId, serverId);
        return buildAuthResponse(userId, claims.getSubject(), serverId);
    }

    public AuthResponse refresh(String token) {
        Claims claims = jwtUtil.validateLocalToken(token);

        if (JwtUtil.TokenType.valueOf(claims.get("type", String.class).toUpperCase())
                != JwtUtil.TokenType.REFRESH) {
            throw new IllegalArgumentException("Provided token is not a refresh token");
        }

        UUID userId = UUID.fromString(claims.get("userId", String.class));
        UUID serverId = UUID.fromString(claims.get("serverId", String.class));

        // Re-validate membership on every refresh — catches kicks/bans
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            log.warn("Refresh rejected: userId={} is no longer a member of serverId={}", userId, serverId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is no longer a member of this server");
        }

        log.info("Token refreshed for userId={} serverId={}", userId, serverId);
        return buildAuthResponse(userId, claims.getSubject(), serverId);
    }

    private UUID resolveOwnInstallationId() {
        List<Installation> installations = installationRepository.findAll();

        if (installations.size() > 1) {
            throw new IllegalStateException(
                    "Data integrity error: found " + installations.size() +
                            " installation records, expected at most 1.");
        }

        if (installations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Installation not registered with hub yet");
        }

        return installations.get(0).getInstallationId();
    }

    private Claims parseHubTicket(String ticket) {
        try {
            return jwtUtil.validateHubToken(ticket);
        } catch (IllegalStateException e) {
            log.warn("Login rejected: hub key not available — {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Installation not registered with hub yet");
        } catch (JwtException e) {
            log.warn("Login rejected: invalid hub ticket — {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired ticket");
        }
    }

    private AuthResponse buildAuthResponse(UUID userId, String email, UUID serverId) {
        return AuthResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(userId, email, serverId))
                .refreshToken(jwtUtil.generateRefreshToken(userId, email, serverId))
                .tokenType("Bearer")
                .expiresIn(accessTokenExpiration)
                .build();
    }
}