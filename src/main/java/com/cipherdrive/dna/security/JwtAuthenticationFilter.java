package com.cipherdrive.dna.security;

import com.cipherdrive.dna.entity.Session;
import com.cipherdrive.dna.repository.SessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * JWT Authentication Filter for CipherDrive-DNA.
 *
 * Request Processing Pipeline:
 *   1. Extract Authorization header from incoming request
 *   2. Parse and validate JWT signature + expiration
 *   3. Verify token type is ACCESS (not REFRESH)
 *   4. Check token revocation against sessions table
 *   5. Extract user claims and populate SecurityContext
 *
 * Security Notes:
 *   - Runs ONCE per request (OncePerRequestFilter)
 *   - Rejects expired or revoked tokens immediately
 *   - Refresh tokens cannot be used for API access
 *   - Revocation check hits DB on every request (acceptable for MVP)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionRepository sessionRepository;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        // ── Step 1: Extract Bearer token ──
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(BEARER_PREFIX.length());

        // ── Step 2: Validate JWT signature + expiration ──
        if (!jwtService.isTokenValid(jwt)) {
            log.warn("Invalid JWT token received from IP: {}", request.getRemoteAddr());
            filterChain.doFilter(request, response);
            return;
        }

        // ── Step 3: Verify token type is ACCESS ──
        String tokenType = jwtService.extractTokenType(jwt);
        if (!"ACCESS".equals(tokenType)) {
            log.warn("Non-ACCESS token used for API access. Type: {}, IP: {}",
                    tokenType, request.getRemoteAddr());
            filterChain.doFilter(request, response);
            return;
        }

        // ── Step 4: Check token revocation in sessions table ──
        String jti = jwtService.extractJti(jwt);
        String tokenHash = jwtService.hashJti(jti);

        var validSession = sessionRepository.findValidSession(tokenHash, LocalDateTime.now());
        if (validSession.isEmpty()) {
            log.warn("Revoked or expired session. JTI hash: {}, IP: {}",
                    tokenHash.substring(0, 8) + "...", request.getRemoteAddr());
            filterChain.doFilter(request, response);
            return;
        }

        // ── Step 5: Populate SecurityContext ──
        Long userId = jwtService.extractUserId(jwt);
        String username = jwtService.extractUsername(jwt);
        String role = jwtService.extractRole(jwt);

        var authorities = Collections.singletonList(
                new SimpleGrantedAuthority(role)
        );

        var authentication = new UsernamePasswordAuthenticationToken(
                username,
                null,
                authorities
        );

        // Attach user ID and session ID as details for downstream use
        authentication.setDetails(new CipherDriveAuthDetails(
                userId,
                username,
                validSession.get().getId(),
                request.getRemoteAddr()
        ));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Authenticated user: {} (role: {}, session: {})",
                username, role, validSession.get().getId());

        filterChain.doFilter(request, response);
    }

    /**
     * Skip JWT filter for public endpoints.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/register")
                || path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/refresh")
                || path.startsWith("/public/")
                || path.equals("/favicon.ico");
    }

    /**
     * Custom authentication details carrying CipherDrive-DNA context.
     * Available in controllers via: SecurityContextHolder.getContext().getAuthentication().getDetails()
     */
    public record CipherDriveAuthDetails(
            Long userId,
            String username,
            Long sessionId,
            String clientIp
    ) {}
}
