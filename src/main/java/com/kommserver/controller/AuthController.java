package com.kommserver.controller;

import com.kommserver.model.dto.request.LoginInstallationRequest;
import com.kommserver.model.dto.response.AuthResponse;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.service.AuthService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginInstallationRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        log.debug("Token refresh attempt");
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header. Expected Bearer token.");
            }
            return ResponseEntity.ok(authService.refresh(authHeader.substring(7)));
        } catch (IllegalArgumentException e) {
            return ErrorResponse.of(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (ExpiredJwtException e) {
            log.warn("Refresh token has expired");
            return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Refresh token has expired. Please login again.");
        } catch (JwtException e) {
            log.warn("Invalid refresh token: {}", e.getMessage());
            return ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Invalid refresh token: " + e.getMessage());
        } catch (Exception e) {
            log.error("Token refresh error: {}", e.getMessage());
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}