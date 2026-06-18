package com.cipherdrive.dna.service;

import com.cipherdrive.dna.dto.AuthRequest;
import com.cipherdrive.dna.dto.AuthResponse;
import com.cipherdrive.dna.dto.RegisterRequest;
import com.cipherdrive.dna.dto.behavior.BehaviorEvent;
import com.cipherdrive.dna.dto.behavior.BehaviorEvent.SecurityEventType;
import com.cipherdrive.dna.entity.Role;
import com.cipherdrive.dna.entity.Session;
import com.cipherdrive.dna.entity.User;
import com.cipherdrive.dna.exception.AuthenticationException;
import com.cipherdrive.dna.repository.RoleRepository;
import com.cipherdrive.dna.repository.SessionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import com.cipherdrive.dna.security.JwtService;
import com.cipherdrive.dna.service.behavioral.BehaviorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Authentication Service for CipherDrive-DNA.
 *
 * Implements the complete authentication lifecycle:
 *   1. REGISTER: Create account → hash password → assign ROLE_USER
 *   2. LOGIN: Validate credentials → generate JWT pair → create session
 *   3. LOGOUT: Revoke session → invalidate JWT via sessions table
 *   4. REFRESH: Validate refresh token → generate new access token
 *
 * Security Features:
 *   - Argon2id-compatible password hashing (via Spring Security PasswordEncoder)
 *   - Account lockout after N failed attempts (configurable)
 *   - Auto-unlock after lockout period expires
 *   - JWT token stored as SHA-256 hash in DB (prevents token theft via DB leak)
 *   - Concurrent session limiting
 *   - Refresh token rotation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final BehaviorLogService behaviorLogService;

    @Value("${cipherdrive.security.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${cipherdrive.security.lock-duration-minutes:30}")
    private int lockDurationMinutes;

    @Value("${cipherdrive.security.max-concurrent-sessions:3}")
    private int maxConcurrentSessions;

    // ── REGISTER ──

    /**
     * Register a new user account.
     *
     * Steps:
     *   1. Validate username & email uniqueness
     *   2. Hash password with BCrypt (strength 12)
     *   3. Assign ROLE_USER by default
     *   4. Persist user entity
     */
    @Transactional
    public AuthResponse register(RegisterRequest request, String clientIp) {
        log.info("Registration attempt: username={}, ip={}", request.getUsername(), clientIp);

        // Check uniqueness
        if (userRepository.existsByUsername(request.getUsername())) {
            throw AuthenticationException.usernameAlreadyExists(request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw AuthenticationException.emailAlreadyExists(request.getEmail());
        }

        // Find default role
        Role userRole = roleRepository.findByRoleName("ROLE_USER")
                .orElseThrow(() -> AuthenticationException.roleNotFound("ROLE_USER"));

        // Create user
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(userRole)
                .isEnabled(true)
                .isLocked(false)
                .failedLoginCount((short) 0)
                .build();

        user = userRepository.save(user);

        log.info("User registered successfully: id={}, username={}", user.getId(), user.getUsername());

        // Generate tokens and create session
        return createSessionAndTokens(user, clientIp, null);
    }

    // ── LOGIN ──

    /**
     * Authenticate user and issue JWT tokens.
     *
     * Steps:
     *   1. Check if account is locked → auto-unlock if expired
     *   2. Validate password against BCrypt hash
     *   3. Record successful login (reset failed count, update last_login)
     *   4. Enforce concurrent session limit
     *   5. Generate JWT access + refresh token pair
     *   6. Create session record with SHA-256 hashed JTI
     */
    @Transactional
    public AuthResponse login(AuthRequest request, String clientIp, String userAgent) {
        log.info("Login attempt: username={}, ip={}", request.getUsername(), clientIp);

        // Find user with role
        User user = userRepository.findByUsernameWithRole(request.getUsername())
                .orElseThrow(AuthenticationException::invalidCredentials);

        // Check if account is enabled
        if (!user.getIsActive()) {
            throw AuthenticationException.accountDisabled();
        }

        // Auto-unlock if lockout period expired
        user.isLockExpired();

        // Check if account is locked
        if (user.getIsLocked()) {
            log.warn("Login attempt on locked account: username={}, lockedUntil={}",
                    user.getUsername(), user.getLockedUntil());
            throw AuthenticationException.accountLocked();
        }

        // Validate password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.recordFailedLogin(maxFailedAttempts, lockDurationMinutes);
            userRepository.save(user);

            log.warn("Failed login attempt: username={}, attempts={}/{}",
                    user.getUsername(), user.getFailedLoginCount(), maxFailedAttempts);

            if (user.getIsLocked()) {
                throw AuthenticationException.accountLocked();
            }
            throw AuthenticationException.invalidCredentials();
        }

        // Record successful login
        user.recordSuccessfulLogin(clientIp);
        userRepository.save(user);

        // Generate tokens and create session
        return createSessionAndTokens(user, clientIp, userAgent);
    }

    // ── LOGOUT ──

    /**
     * Logout user by revoking the current session.
     *
     * The JWT itself remains valid until expiration, but the session
     * record is marked as revoked. On subsequent requests, the
     * JwtAuthenticationFilter will detect the revoked session and
     * reject the token.
     */
    @Transactional
    public void logout(Long sessionId, String reason) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && !session.getIsRevoked()) {
            session.revoke(Session.RevokeReason.valueOf(reason));
            sessionRepository.save(session);
            log.info("Session revoked: sessionId={}, reason={}", sessionId, reason);
        }
    }

    /**
     * Logout all sessions for a user (force logout across all devices).
     */
    @Transactional
    public void logoutAll(Long userId) {
        sessionRepository.revokeAllByUserId(userId);
        log.info("All sessions revoked for userId={}", userId);
    }

    // ── REFRESH ──

    /**
     * Refresh access token using a valid refresh token.
     *
     * Steps:
     *   1. Validate refresh token signature + expiration
     *   2. Verify token type is REFRESH
     *   3. Find user
     *   4. Generate new access token
     *   5. Optionally rotate refresh token
     */
    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        log.debug("Refresh token attempt");

        // Validate refresh token
        if (!jwtService.isTokenValid(refreshToken)) {
            throw AuthenticationException.invalidRefreshToken();
        }

        // Verify token type
        String tokenType = jwtService.extractTokenType(refreshToken);
        if (!"REFRESH".equals(tokenType)) {
            log.warn("Non-REFRESH token used in refresh endpoint. Type: {}", tokenType);
            throw AuthenticationException.invalidRefreshToken();
        }

        // Find user
        Long userId = jwtService.extractUserId(refreshToken);
        User user = userRepository.findByUsernameWithRole(jwtService.extractUsername(refreshToken))
                .orElseThrow(AuthenticationException::invalidRefreshToken);

        if (!user.getIsActive() || user.getIsLocked()) {
            throw AuthenticationException.invalidRefreshToken();
        }

        // Generate new access token
        String newAccessToken = jwtService.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole().getRoleName());

        // Rotate refresh token
        String newRefreshToken = jwtService.generateRefreshToken(
                user.getId(), user.getUsername());

        log.info("Token refreshed for userId={}", userId);

        return AuthResponse.of(
                newAccessToken,
                newRefreshToken,
                jwtService.getAccessTokenExpiration(),
                user.getUsername(),
                user.getRole().getRoleName()
        );
    }

    // ── Internal Helpers ──

    /**
     * Create a new session and generate JWT token pair.
     */
    private AuthResponse createSessionAndTokens(User user, String clientIp, String userAgent) {
        // Enforce concurrent session limit
        var activeSessions = sessionRepository.findActiveSessionsByUser(user.getId(), LocalDateTime.now());
        if (activeSessions.size() >= maxConcurrentSessions) {
            // Revoke the oldest session to make room
            Session oldest = activeSessions.get(activeSessions.size() - 1);
            oldest.revoke(Session.RevokeReason.LOGOUT);
            sessionRepository.save(oldest);
            log.info("Revoked oldest session for userId={}, sessionId={}", user.getId(), oldest.getId());
        }

        // Generate JWT tokens
        String accessToken = jwtService.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole().getRoleName());
        String refreshToken = jwtService.generateRefreshToken(
                user.getId(), user.getUsername());

        // Create session record with SHA-256 hashed JTI
        String jti = jwtService.extractJti(accessToken);
        String tokenHash = jwtService.hashJti(jti);
        String refreshTokenJti = jwtService.extractJti(refreshToken);
        String refreshTokenHash = jwtService.hashJti(refreshTokenJti);

        Session session = Session.builder()
                .user(user)
                .sessionToken(tokenHash)
                .refreshToken(refreshTokenHash)
                .ipAddress(clientIp)
                .userAgent(truncateUserAgent(userAgent))
                .expiresAt(jwtService.extractExpirationLocalDateTime(accessToken))
                .build();

        sessionRepository.save(session);

        log.info("Session created: userId={}, sessionId={}", user.getId(), session.getId());

        // Log LOGIN behavioral event (async)
        BehaviorEvent loginEvent = BehaviorEvent.builder()
                .userId(user.getId())
                .sessionId(session.getId())
                .ipAddress(clientIp)
                .userAgent(userAgent)
                .deviceType(behaviorLogService.classifyDevice(userAgent))
                .eventType(SecurityEventType.LOGIN)
                .eventMetadata("{\"username\":\"" + user.getUsername() + "\"}")
                .build();
        behaviorLogService.logSecurityEvent(loginEvent);

        return AuthResponse.of(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpiration(),
                user.getUsername(),
                user.getRole().getRoleName()
        );
    }

    private String truncateUserAgent(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
    }
}
