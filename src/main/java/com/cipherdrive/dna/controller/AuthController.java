package com.cipherdrive.dna.controller;

import com.cipherdrive.dna.dto.AuthRequest;
import com.cipherdrive.dna.dto.AuthResponse;
import com.cipherdrive.dna.dto.RegisterRequest;
import com.cipherdrive.dna.security.JwtAuthenticationFilter.CipherDriveAuthDetails;
import com.cipherdrive.dna.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication REST Controller for CipherDrive-DNA.
 *
 * Endpoints:
 *   POST /api/auth/register  — Create new account
 *   POST /api/auth/login     — Authenticate and get JWT tokens
 *   POST /api/auth/logout    — Revoke current session
 *   POST /api/auth/logout-all — Revoke all sessions
 *   POST /api/auth/refresh   — Refresh access token
 *   GET  /api/auth/me        — Get current user info
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authService;

    // ── REGISTER ──

    /**
     * Register a new user account.
     *
     * Request: { username, email, password }
     * Response: { accessToken, refreshToken, tokenType, expiresIn, username, role }
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = extractClientIp(httpRequest);
        AuthResponse response = authService.register(request, clientIp);

        log.info("User registered: username={}, ip={}", request.getUsername(), clientIp);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── LOGIN ──

    /**
     * Authenticate user and issue JWT token pair.
     *
     * Request: { username, password }
     * Response: { accessToken, refreshToken, tokenType, expiresIn, username, role }
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody AuthRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AuthResponse response = authService.login(request, clientIp, userAgent);

        log.info("User logged in: username={}, ip={}", request.getUsername(), clientIp);
        return ResponseEntity.ok(response);
    }

    // ── LOGOUT ──

    /**
     * Logout current session — revoke the JWT via sessions table.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest httpRequest) {
        CipherDriveAuthDetails details = getAuthDetails();
        if (details != null) {
            authService.logout(details.sessionId(), "LOGOUT");
            log.info("User logged out: username={}, sessionId={}", details.username(), details.sessionId());
        }
        return ResponseEntity.ok(Map.of(
                "message", "Logged out successfully",
                "status", "OK"
        ));
    }

    /**
     * Logout from all devices — revoke all sessions for current user.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<Map<String, String>> logoutAll() {
        CipherDriveAuthDetails details = getAuthDetails();
        if (details != null) {
            authService.logoutAll(details.userId());
            log.info("User logged out from all devices: username={}", details.username());
        }
        return ResponseEntity.ok(Map.of(
                "message", "Logged out from all devices",
                "status", "OK"
        ));
    }

    // ── REFRESH ──

    /**
     * Refresh access token using refresh token.
     *
     * Request: { refreshToken: "..." }
     * Response: { accessToken, refreshToken, tokenType, expiresIn, username, role }
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(
            @RequestBody Map<String, String> body) {

        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        AuthResponse response = authService.refreshToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    // ── CURRENT USER ──

    /**
     * Get current authenticated user information.
     * Uses SecurityContext populated by JwtAuthenticationFilter.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {

        CipherDriveAuthDetails details = getAuthDetails();

        Map<String, Object> userInfo = Map.of(
                "username", userDetails.getUsername(),
                "authorities", userDetails.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .toList(),
                "userId", details != null ? details.userId() : "N/A",
                "sessionId", details != null ? details.sessionId() : "N/A",
                "accountNonExpired", userDetails.isAccountNonExpired(),
                "accountNonLocked", userDetails.isAccountNonLocked(),
                "credentialsNonExpired", userDetails.isCredentialsNonExpired(),
                "enabled", userDetails.isEnabled()
        );

        return ResponseEntity.ok(userInfo);
    }

    // ── Internal Helpers ──

    private CipherDriveAuthDetails getAuthDetails() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof CipherDriveAuthDetails details) {
            return details;
        }
        return null;
    }

    /**
     * Extract real client IP, accounting for reverse proxy headers.
     */
    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For may contain multiple IPs — take the first
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
