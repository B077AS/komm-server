package com.kommserver.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
 @RequiredArgsConstructor
public class SecurityUtil {

    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof UUID userId) {
            return userId;
        }

        return null;
    }

    public UUID getCurrentServerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object details = authentication.getDetails();

        if (details instanceof Map<?, ?> detailsMap) {
            Object serverId = detailsMap.get("serverId");
            if (serverId instanceof UUID uuid) {
                return uuid;
            }
        }

        return null;
    }
}