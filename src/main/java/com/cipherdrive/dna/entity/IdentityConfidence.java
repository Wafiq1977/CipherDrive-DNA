package com.cipherdrive.dna.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ICS (Identity Confidence Score) snapshots with temporal decay.
 * Maps to: identity_confidence table
 *
 * ICS Computation:
 *   pre_decay = B*0.35 + D*0.20 + T*0.25 + C*0.20
 *   decay_factor = exp(-lambda * delta_t_hours)
 *   post_decay = pre_decay * decay_factor
 *   ics_score = post_decay + recovery_rate * min(consecutive_verified, 10)
 *
 * Trust Level Classification:
 *   TRUSTED    >= 80
 *   ACCEPTED   >= 60
 *   CHALLENGED >= 40
 *   REJECTED   < 40
 */
@Entity
@Table(name = "identity_confidence", indexes = {
        @Index(name = "ix_ics_user_computed", columnList = "user_id, computed_at DESC"),
        @Index(name = "ix_ics_session_id", columnList = "session_id"),
        @Index(name = "ix_ics_trust_level", columnList = "trust_level, computed_at DESC"),
        @Index(name = "ix_ics_below_threshold", columnList = "is_below_threshold, user_id, computed_at DESC"),
        @Index(name = "ix_ics_dna_profile", columnList = "dna_profile_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "session", "dnaProfile"})
public class IdentityConfidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ics_user_id"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ics_session_id"))
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dna_profile_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ics_dna_profile_id"))
    private DigitalDNA dnaProfile;

    // ── ICS Score Components ──

    @Column(name = "ics_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal icsScore;

    @Column(name = "behavioral_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal behavioralScore;

    @Column(name = "device_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal deviceScore;

    @Column(name = "temporal_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal temporalScore;

    @Column(name = "context_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal contextScore;

    // ── Temporal Decay ──

    @Column(name = "decay_factor", nullable = false, precision = 8, scale = 6)
    private BigDecimal decayFactor;

    @Column(name = "decay_lambda", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal decayLambda = new BigDecimal("0.0500");

    @Column(name = "time_since_last_event", nullable = false, precision = 12, scale = 2)
    private BigDecimal timeSinceLastEvent;

    @Column(name = "pre_decay_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal preDecayScore;

    @Column(name = "post_decay_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal postDecayScore;

    // ── Recovery Mechanism ──

    @Column(name = "recovery_rate", nullable = false, precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal recoveryRate = new BigDecimal("0.0200");

    @Column(name = "consecutive_verified", nullable = false)
    @Builder.Default
    private Integer consecutiveVerified = 0;

    // ── Thresholds & Classification ──

    @Column(name = "trust_level", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TrustLevel trustLevel;

    @Column(name = "is_below_threshold", nullable = false)
    @Builder.Default
    private Boolean isBelowThreshold = false;

    @Column(name = "challenge_triggered", nullable = false)
    @Builder.Default
    private Boolean challengeTriggered = false;

    // ── Metadata ──

    @Column(name = "computation_time_ms", nullable = false)
    private Integer computationTimeMs;

    @Column(name = "algorithm_version", nullable = false, length = 16)
    @Builder.Default
    private String algorithmVersion = "2.0";

    @CreationTimestamp
    @Column(name = "computed_at", nullable = false, updatable = false)
    private LocalDateTime computedAt;

    // ── Enum ──

    public enum TrustLevel {
        /** ICS >= 80: Full access, no additional verification needed */
        TRUSTED,
        /** ICS >= 60: Standard access, monitor for changes */
        ACCEPTED,
        /** ICS >= 40: Limited access, trigger MFA/challenge */
        CHALLENGED,
        /** ICS < 40: Block access, force re-authentication */
        REJECTED;

        /**
         * Classify trust level from ICS score.
         */
        public static TrustLevel fromScore(BigDecimal icsScore) {
            double score = icsScore.doubleValue();
            if (score >= 80.0) return TRUSTED;
            if (score >= 60.0) return ACCEPTED;
            if (score >= 40.0) return CHALLENGED;
            return REJECTED;
        }
    }

    // ── Helper Methods ──

    /**
     * Check if MFA/challenge should be triggered based on trust level.
     */
    public boolean requiresChallenge() {
        return trustLevel == TrustLevel.CHALLENGED || trustLevel == TrustLevel.REJECTED;
    }

    /**
     * Check if access should be blocked entirely.
     */
    public boolean shouldBlockAccess() {
        return trustLevel == TrustLevel.REJECTED;
    }
}
