package com.cipherdrive.dna.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Core user identity for CipherDrive-DNA platform.
 * Maps to: users table
 * Password hashing: Argon2id (m=65536, t=3, p=4)
 */
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uq_users_email", columnNames = "email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"role", "sessions", "files", "behaviorLogs", "digitalDnaProfiles", "identityConfidenceScores", "trustEvolutionSnapshots"})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "username", nullable = false, length = 64, unique = true)
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,63}$", message = "Username must start with a letter, contain only alphanumeric and underscore, 3-64 chars")
    private String username;

    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // ── RBAC ──

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false, foreignKey = @ForeignKey(name = "fk_users_role_id"))
    private Role role;

    // ── Account State ──

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    @Column(name = "is_locked", nullable = false)
    @Builder.Default
    private Boolean isLocked = false;

    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private Short failedLoginCount = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    // ── Login Tracking ──

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    // ── Timestamps ──

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Relationships ──

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Session> sessions = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<FileEntity> files = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BehaviorLog> behaviorLogs = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DigitalDNA> digitalDnaProfiles = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<IdentityConfidence> identityConfidenceScores = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<TrustEvolution> trustEvolutionSnapshots = new ArrayList<>();

    // ── Helper Methods ──

    /**
     * Increment failed login count and lock account if threshold exceeded.
     * @param maxAttempts maximum allowed consecutive failures
     * @param lockDurationMinutes lockout duration in minutes
     */
    public void recordFailedLogin(int maxAttempts, int lockDurationMinutes) {
        this.failedLoginCount = (short) Math.min(this.failedLoginCount + 1, maxAttempts);
        if (this.failedLoginCount >= maxAttempts) {
            this.isLocked = true;
            this.lockedUntil = LocalDateTime.now().plusMinutes(lockDurationMinutes);
        }
    }

    /**
     * Reset failed login count and unlock account on successful authentication.
     */
    public void recordSuccessfulLogin(String ipAddress) {
        this.failedLoginCount = 0;
        this.isLocked = false;
        this.lockedUntil = null;
        this.lastLoginAt = LocalDateTime.now();
        this.lastLoginIp = ipAddress;
    }

    /**
     * Check if the account lockout period has expired and auto-unlock.
     */
    public boolean isLockExpired() {
        if (this.isLocked && this.lockedUntil != null && LocalDateTime.now().isAfter(this.lockedUntil)) {
            this.isLocked = false;
            this.failedLoginCount = 0;
            this.lockedUntil = null;
            return true;
        }
        return false;
    }
}
