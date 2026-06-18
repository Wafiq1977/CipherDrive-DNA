package com.cipherdrive.dna.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT session tracking and revocation management.
 * Maps to: sessions table
 *
 * IMPORTANT: session_token stores SHA-256 hash of JWT jti claim, NOT the JWT itself.
 * This prevents token theft via database compromise.
 */
@Entity
@Table(name = "sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_sessions_session_token", columnNames = "session_token")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "behaviorLogs", "digitalDnaProfiles", "identityConfidenceScores"})
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_sessions_user_id"))
    private User user;

    // ── Token Data (hashed, never store raw JWT) ──

    @Column(name = "session_token", nullable = false, length = 255, unique = true)
    private String sessionToken;

    @Column(name = "refresh_token", length = 255)
    private String refreshToken;

    // ── Client Fingerprinting ──

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    // ── Revocation ──

    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    private Boolean isRevoked = false;

    @Column(name = "revoke_reason", length = 64)
    @Enumerated(EnumType.STRING)
    private RevokeReason revokeReason;

    // ── TTL ──

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // ── Timestamps ──

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Relationships ──

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BehaviorLog> behaviorLogs = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DigitalDNA> digitalDnaProfiles = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<IdentityConfidence> identityConfidenceScores = new ArrayList<>();

    // ── Enum ──

    public enum RevokeReason {
        LOGOUT,
        SECURITY_EVENT,
        ADMIN,
        EXPIRED
    }

    // ── Helper Methods ──

    /**
     * Check if this session has expired.
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if this session is valid (not revoked and not expired).
     */
    public boolean isValid() {
        return !isRevoked && !isExpired();
    }

    /**
     * Revoke this session with a reason.
     */
    public void revoke(RevokeReason reason) {
        this.isRevoked = true;
        this.revokeReason = reason;
    }
}
