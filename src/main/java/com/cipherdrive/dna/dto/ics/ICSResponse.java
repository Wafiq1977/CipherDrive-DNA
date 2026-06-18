package com.cipherdrive.dna.dto.ics;

import com.cipherdrive.dna.entity.IdentityConfidence.TrustLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing the complete ICS (Identity Confidence Score) computation result.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ICS SCORE ARCHITECTURE
 * ══════════════════════════════════════════════════════════════════
 *
 *  ICS = f(Behavioral, Device, Temporal, Context) with temporal decay + recovery
 *
 *  Composition:
 *    pre_decay  = B * w_B + D * w_D + T * w_T + C * w_C
 *    decay      = exp(-lambda * delta_t_hours)
 *    post_decay = pre_decay * decay
 *    ics_score  = post_decay + recovery_rate * min(consecutive_verified, 10)
 *
 *  Threshold Classification (DNA-TRUST V2 Extended):
 *    85-100 = VERY_HIGH  → Full access, no restrictions
 *    70-84  = HIGH       → Standard access, monitor
 *    40-69  = MODERATE   → Limited access, step-up auth
 *    25-39  = LOW        → Restricted access, MFA required
 *    0-24   = CRITICAL   → Block access, force re-authentication
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ICSResponse {

    private Long id;
    private Long userId;
    private Long sessionId;
    private Long dnaProfileId;

    // ── ICS Score (0-100) ──
    private double icsScore;

    // ── Component Scores (0-100 each) ──
    private double behavioralScore;
    private double deviceScore;
    private double temporalScore;
    private double contextScore;

    // ── Temporal Decay ──
    private double decayFactor;
    private double decayLambda;
    private double timeSinceLastEventHours;
    private double preDecayScore;
    private double postDecayScore;

    // ── Recovery Mechanism ──
    private double recoveryRate;
    private int consecutiveVerified;
    private double recoveryBonus;

    // ── Classification ──
    private TrustLevel trustLevel;
    private ICSThreshold threshold;
    private boolean belowThreshold;
    private boolean challengeTriggered;

    // ── Metadata ──
    private int computationTimeMs;
    private String algorithmVersion;
    private LocalDateTime computedAt;

    /**
     * ICS Threshold Classification — extended from DNA-TRUST V2 design.
     *
     * The user's request specifies 5 levels:
     *   VERY_HIGH: 85-100
     *   HIGH:      70-84
     *   MODERATE:  40-69
     *   LOW:       25-39
     *   CRITICAL:  0-24
     */
    public enum ICSThreshold {
        /** ICS 85-100: Full unrestricted access */
        VERY_HIGH(85, 100),
        /** ICS 70-84: Standard access with monitoring */
        HIGH(70, 84),
        /** ICS 40-69: Limited access, step-up authentication */
        MODERATE(40, 69),
        /** ICS 25-39: Restricted access, MFA required */
        LOW(25, 39),
        /** ICS 0-24: Block access, force re-authentication */
        CRITICAL(0, 24);

        private final int min;
        private final int max;

        ICSThreshold(int min, int max) {
            this.min = min;
            this.max = max;
        }

        /**
         * Classify ICS score into threshold category.
         */
        public static ICSThreshold fromScore(double icsScore) {
            if (icsScore >= 85) return VERY_HIGH;
            if (icsScore >= 70) return HIGH;
            if (icsScore >= 40) return MODERATE;
            if (icsScore >= 25) return LOW;
            return CRITICAL;
        }
    }
}
