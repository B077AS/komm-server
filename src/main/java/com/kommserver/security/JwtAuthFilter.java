package com.kommserver.security;

import com.google.gson.Gson;
import com.kommserver.model.dto.response.ErrorResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final Gson gson;
    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            Claims claims = jwtUtil.validateLocalToken(token);

            UUID userId = UUID.fromString(claims.getSubject());
            UUID serverId = UUID.fromString(claims.get("serverId", String.class));

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId,   // principal is now a UUID
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            auth.setDetails(Map.of("serverId", serverId));

            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (ExpiredJwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");

            ErrorResponse errorResponse = ErrorResponse.builder()
                    .message("Token has expired. Please login again.")
                    .error("TOKEN_EXPIRED")
                    .status(401)
                    .build();

            response.getWriter().write(gson.toJson(errorResponse));

        } catch (Exception e) {
            log.error("Unexpected error occurred: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");

            ErrorResponse errorResponse = ErrorResponse.builder()
                    .message("Invalid or malformed token")
                    .error("INVALID_TOKEN")
                    .status(401)
                    .build();

            response.getWriter().write(gson.toJson(errorResponse));
        }

        filterChain.doFilter(request, response);
    }
}