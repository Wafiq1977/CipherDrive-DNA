package com.cipherdrive.dna.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * JWT Service for CipherDrive-DNA.
 *
 * Security Architecture:
 * - Access Token: short-lived (15 min), carries claims for authorization
 * - Refresh Token: long-lived (7 days), used only for renewal
 * - JTI (JWT ID): unique per token, SHA-256 hashed before DB storage
 * - Token Revocation: checked against sessions table on every request
 *
 * IMPORTANT: The raw JWT is NEVER stored in the database.
 * Only the SHA-256 hash of the jti claim is stored for revocation checking.
 */
@Slf4j
@Service
public class JwtService {

    @Value("${cipherdrive.security.jwt.secret-key}")
    private String secretKey;

    @Value("${cipherdrive.security.jwt.access-token-expiration:900000}")
    private long accessTokenExpiration; // 15 minutes default

    @Value("${cipherdrive.security.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration; // 7 days default

    @Value("${cipherdrive.security.jwt.issuer:cipherdrive-dna}")
    private String issuer;

    // ── Token Generation ──

    /**
     * Generate access token with user claims.
     */
    public String generateAccessToken(Long userId, String username, String role) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(accessTokenExpiration);

        return Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .claim("type", "ACCESS")
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Generate refresh token with minimal claims.
     */
    public String generateRefreshToken(Long userId, String username) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(refreshTokenExpiration);

        return Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .claim("username", username)
                .claim("type", "REFRESH")
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    // ── Token Validation ──

    /**
     * Validate token signature and expiration.
     * Does NOT check revocation — that's done in JwtAuthenticationFilter.
     */
    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.warn("JWT is null or empty");
            return false;
        }
    }

    /**
     * Check if the token is expired.
     */
    public boolean isTokenExpired(String token) {
        try {
            return extractExpiration(token).before(new Date());
        } catch (JwtException e) {
            return true;
        }
    }

    // ── Claim Extraction ──

    /**
     * Extract user ID (subject) from token.
     */
    public Long extractUserId(String token) {
        String subject = parseToken(token).getPayload().getSubject();
        return Long.parseLong(subject);
    }

    /**
     * Extract username claim from token.
     */
    public String extractUsername(String token) {
        return parseToken(token).getPayload().get("username", String.class);
    }

    /**
     * Extract role claim from token.
     */
    public String extractRole(String token) {
        return parseToken(token).getPayload().get("role", String.class);
    }

    /**
     * Extract token type (ACCESS or REFRESH).
     */
    public String extractTokenType(String token) {
        return parseToken(token).getPayload().get("type", String.class);
    }

    /**
     * Extract JTI (JWT ID) — used for revocation check.
     */
    public String extractJti(String token) {
        return parseToken(token).getPayload().getId();
    }

    /**
     * Extract expiration date from token.
     */
    public Date extractExpiration(String token) {
        return parseToken(token).getPayload().getExpiration();
    }

    /**
     * Convert token expiration to LocalDateTime for DB storage.
     */
    public LocalDateTime extractExpirationLocalDateTime(String token) {
        return extractExpiration(token).toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    // ── Hashing (for secure DB storage) ──

    /**
     * Hash the JTI claim with SHA-256 for database storage.
     * This prevents token theft via database compromise —
     * even if the DB is leaked, the raw JTI cannot be recovered.
     *
     * @param jti raw JWT ID from token
     * @return SHA-256 hex digest of the JTI
     */
    public String hashJti(String jti) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(jti.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Hash a full token for session lookup.
     * Alternative to JTI-based hashing when the full token is the session identifier.
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Configuration Accessors ──

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    // ── Internal ──

    private Jws<Claims> parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
