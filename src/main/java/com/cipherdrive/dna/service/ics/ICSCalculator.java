package com.cipherdrive.dna.service.ics;

import com.cipherdrive.dna.dto.ics.ICSResponse.ICSThreshold;
import com.cipherdrive.dna.entity.DigitalDNA;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import com.cipherdrive.dna.entity.IdentityConfidence.TrustLevel;
import com.cipherdrive.dna.entity.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ICS Calculator — Core Mathematical Engine for Identity Confidence Score.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ICS SCORE FORMULA
 * ══════════════════════════════════════════════════════════════════
 *
 *  The ICS score is a composite measure of identity confidence that
 *  combines four independent trust signals with temporal decay and
 *  a recovery mechanism:
 *
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │ Step 1: Component Scoring                                   │
 *  │   B = Behavioral Score (from DNA similarity)               │
 *  │   D = Device Trust Score (from device fingerprint)         │
 *  │   T = Temporal Score (from session timing patterns)        │
 *  │   C = Context Score (from IP/geolocation context)          │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │ Step 2: Weighted Fusion                                     │
 *  │   pre_decay = B * w_B + D * w_D + T * w_T + C * w_C      │
 *  │                                                              │
 *  │   Weights: w_B=0.35, w_D=0.20, w_T=0.25, w_C=0.20        │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │ Step 3: Temporal Decay (Exponential)                       │
 *  │   decay_factor = exp(-lambda * delta_t_hours)              │
 *  │   post_decay   = pre_decay * decay_factor                  │
 *  │                                                              │
 *  │   lambda = 0.05 (half-life ≈ 13.86 hours)                 │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │ Step 4: Recovery Mechanism                                  │
 *  │   recovery_bonus = recovery_rate * min(consecutive, 10)    │
 *  │   ics_score = clamp(post_decay + recovery_bonus, 0, 100)  │
 *  │                                                              │
 *  │   recovery_rate = 2.0 per consecutive verified action      │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │ Step 5: Threshold Classification                           │
 *  │   85-100 = VERY_HIGH   → Full access                       │
 *  │   70-84  = HIGH        → Standard access                   │
 *  │   40-69  = MODERATE    → Step-up auth required             │
 *  │   25-39  = LOW         → MFA + restricted access           │
 *  │   0-24   = CRITICAL    → Access denied                     │
 *  └─────────────────────────────────────────────────────────────┘
 *
 * ══════════════════════════════════════════════════════════════════
 *  COMPONENT SCORE DERIVATION
 * ══════════════════════════════════════════════════════════════════
 *
 *  B (Behavioral Score):
 *    Derived from DNA cosine similarity × 100.
 *    B = cosine_similarity * 100
 *    If no baseline: B = 70 (assumed moderate-high for new users)
 *    Drift regime penalty:
 *      NORMAL    → no penalty
 *      LOW_DRIFT → B -= 5
 *      HIGH_DRIFT→ B -= 15
 *      ANOMALY   → B -= 30
 *
 *  D (Device Trust Score):
 *    Derived from device fingerprint consistency.
 *    D = device_consistency * 100
 *    New device penalty: -20 per unrecognized device
 *    Bot detection: D = 0
 *
 *  T (Temporal Score):
 *    Derived from session timing patterns.
 *    T = (session_validity + login_pattern_consistency) / 2 * 100
 *    Session validity: 80 if valid, 0 if expired/revoked
 *    Login pattern: based on hour-of-day consistency with baseline
 *
 *  C (Context Score):
 *    Derived from IP address and network context.
 *    C = ip_consistency * 100
 *    Known IP: C = 90
 *    Same subnet: C = 70
 *    New subnet: C = 40
 *    Tor/VPN: C = 10
 */
@Slf4j
@Component
public class ICSCalculator {

    // ── Component Weights ──
    /** Behavioral (DNA similarity) weight — highest: captures identity uniqueness */
    private static final double W_BEHAVIORAL = 0.35;
    /** Device trust weight — strong: device fingerprint is hard to spoof */
    private static final double W_DEVICE = 0.20;
    /** Temporal pattern weight — significant: captures timing anomalies */
    private static final double W_TEMPORAL = 0.25;
    /** Context (IP/network) weight — moderate: can change legitimately */
    private static final double W_CONTEXT = 0.20;

    // ── Temporal Decay Parameters ──
    /** Decay rate lambda: controls how fast ICS degrades over time */
    private static final double DEFAULT_DECAY_LAMBDA = 0.05;
    /** Half-life in hours: ln(2)/lambda ≈ 13.86 hours */
    private static final double DECAY_HALF_LIFE_HOURS = Math.log(2) / DEFAULT_DECAY_LAMBDA;

    // ── Recovery Parameters ──
    /** Recovery rate: points gained per consecutive verified action */
    private static final double DEFAULT_RECOVERY_RATE = 2.0;
    /** Maximum consecutive verified actions counted for recovery */
    private static final int MAX_CONSECUTIVE_FOR_RECOVERY = 10;

    // ── New User Defaults ──
    /** Default behavioral score for users without baseline DNA */
    private static final double DEFAULT_BEHAVIORAL_SCORE_NO_BASELINE = 70.0;
    /** Default device score for first session */
    private static final double DEFAULT_DEVICE_SCORE_FIRST_SESSION = 80.0;
    /** Default temporal score for new users */
    private static final double DEFAULT_TEMPORAL_SCORE_NEW_USER = 75.0;
    /** Default context score for known IPs */
    private static final double DEFAULT_CONTEXT_SCORE_KNOWN_IP = 80.0;

    // ── Drift Regime Penalties (subtracted from Behavioral Score) ──
    private static final double DRIFT_PENALTY_NORMAL = 0.0;
    private static final double DRIFT_PENALTY_LOW = 5.0;
    private static final double DRIFT_PENALTY_HIGH = 15.0;
    private static final double DRIFT_PENALTY_ANOMALY = 30.0;

    // ══════════════════════════════════════════════════════════════
    //  STEP 1: COMPONENT SCORE COMPUTATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute Behavioral Score (B) from DNA similarity.
     *
     * Formula:
     *   B = cosine_similarity * 100 - drift_regime_penalty
     *
     * The behavioral score captures how closely the user's current
     * behavioral fingerprint matches their established baseline.
     * A high cosine similarity indicates the user is behaving
     * consistently with their historical pattern.
     *
     * Drift regime penalties:
     *   NORMAL    → 0  penalty (behavior matches baseline)
     *   LOW_DRIFT → 5  penalty (minor deviation)
     *   HIGH_DRIFT→ 15 penalty (significant deviation)
     *   ANOMALY   → 30 penalty (critical deviation — likely impostor)
     *
     * @param cosineSimilarity DNA cosine similarity [0, 1]
     * @param driftRegime      current drift regime classification
     * @param hasBaseline      whether the user has an established baseline
     * @return behavioral score [0, 100]
     */
    public double computeBehavioralScore(double cosineSimilarity,
                                          DriftRegime driftRegime,
                                          boolean hasBaseline) {
        double baseScore;

        if (!hasBaseline) {
            // No baseline → assume moderate-high behavioral score
            // (no evidence of anomaly, but no confirmation either)
            baseScore = DEFAULT_BEHAVIORAL_SCORE_NO_BASELINE;
            log.debug("No baseline DNA — using default behavioral score: {}", baseScore);
        } else {
            // Scale cosine similarity to [0, 100]
            baseScore = cosineSimilarity * 100.0;
        }

        // Apply drift regime penalty
        double penalty = switch (driftRegime) {
            case NORMAL -> DRIFT_PENALTY_NORMAL;
            case LOW_DRIFT -> DRIFT_PENALTY_LOW;
            case HIGH_DRIFT -> DRIFT_PENALTY_HIGH;
            case ANOMALY -> DRIFT_PENALTY_ANOMALY;
        };

        double behavioralScore = clamp(baseScore - penalty, 0.0, 100.0);
        log.debug("Behavioral score: base={}, penalty={}, regime={}, result={}",
                baseScore, penalty, driftRegime, behavioralScore);
        return behavioralScore;
    }

    /**
     * Compute Device Trust Score (D) from device fingerprint and session data.
     *
     * Formula:
     *   D = device_consistency * 100 - new_device_penalty - bot_penalty
     *
     * Factors:
     *   1. Device consistency from DNA vector (how often user uses same device)
     *   2. New device penalty: -20 if device fingerprint is unrecognized
     *   3. Bot detection: D = 0 if user-agent indicates bot/crawler
     *   4. Device fingerprint match with session: +10 bonus if matching
     *
     * @param deviceConsistency  DNA device consistency dimension [0, 1]
     * @param isRecognizedDevice whether the device fingerprint is known
     * @param isBot              whether the user-agent indicates bot/crawler
     * @param isFirstSession     whether this is the user's first session
     * @return device trust score [0, 100]
     */
    public double computeDeviceScore(double deviceConsistency,
                                      boolean isRecognizedDevice,
                                      boolean isBot,
                                      boolean isFirstSession) {
        if (isBot) {
            log.warn("Bot detected — device trust score set to 0");
            return 0.0;
        }

        if (isFirstSession) {
            return DEFAULT_DEVICE_SCORE_FIRST_SESSION;
        }

        // Base score from device consistency
        double baseScore = deviceConsistency * 100.0;

        // Penalty for unrecognized device
        if (!isRecognizedDevice) {
            baseScore -= 20.0;
            log.debug("Unrecognized device — applying -20 penalty");
        }

        // Bonus for recognized device
        if (isRecognizedDevice) {
            baseScore = Math.min(baseScore + 10.0, 100.0);
        }

        return clamp(baseScore, 0.0, 100.0);
    }

    /**
     * Compute Temporal Score (T) from session timing patterns.
     *
     * Formula:
     *   T = (session_validity * 0.5 + login_pattern_score * 0.5)
     *
     * Session validity:
     *   - Valid, active session → 80
     *   - Expired but not revoked → 40
     *   - Revoked → 0
     *
     * Login pattern score:
     *   - Current hour matches user's typical login hours → 90
     *   - Within ±2 hours of typical pattern → 70
     *   - Outside typical pattern → 40
     *   - First session → 75 (no pattern data)
     *
     * @param session           current session
     * @param isTypicalLoginHour whether current hour matches user's typical pattern
     * @param isFirstSession     whether this is the user's first session
     * @return temporal score [0, 100]
     */
    public double computeTemporalScore(Session session,
                                        boolean isTypicalLoginHour,
                                        boolean isFirstSession) {
        if (isFirstSession) {
            return DEFAULT_TEMPORAL_SCORE_NEW_USER;
        }

        // Session validity component
        double sessionValidity;
        if (session == null) {
            sessionValidity = 30.0; // No session → low score
        } else if (session.isValid()) {
            sessionValidity = 80.0;
        } else if (!session.getIsRevoked()) {
            sessionValidity = 40.0; // Expired but not revoked
        } else {
            sessionValidity = 0.0;  // Revoked
        }

        // Login pattern component
        double loginPatternScore;
        if (isTypicalLoginHour) {
            loginPatternScore = 90.0;
        } else {
            loginPatternScore = 40.0; // Unusual login hour
        }

        double temporalScore = sessionValidity * 0.5 + loginPatternScore * 0.5;
        return clamp(temporalScore, 0.0, 100.0);
    }

    /**
     * Compute Context Score (C) from IP address and network context.
     *
     * Formula:
     *   C = ip_trust_level * 100
     *
     * IP Trust Levels:
     *   - Known IP (exact match)      → 95
     *   - Same subnet (partial match) → 70
     *   - New subnet (no match)       → 40
     *   - Known VPN/Tor exit node     → 10
     *   - Internal/loopback           → 100
     *
     * @param currentIp      current client IP address
     * @param previousIp     IP from previous session (for comparison)
     * @param isVpnOrTor     whether IP is a known VPN/Tor exit node
     * @param isFirstSession whether this is the user's first session
     * @return context score [0, 100]
     */
    public double computeContextScore(String currentIp,
                                       String previousIp,
                                       boolean isVpnOrTor,
                                       boolean isFirstSession) {
        if (isFirstSession) {
            return DEFAULT_CONTEXT_SCORE_KNOWN_IP;
        }

        if (isVpnOrTor) {
            log.warn("VPN/Tor detected — context score penalized: ip={}", currentIp);
            return 10.0;
        }

        if (currentIp == null || currentIp.isBlank()) {
            return 30.0; // No IP info → low-moderate
        }

        // Internal/loopback addresses
        if (currentIp.equals("127.0.0.1") || currentIp.equals("0:0:0:0:0:0:0:1")
                || currentIp.startsWith("192.168.") || currentIp.startsWith("10.")
                || currentIp.startsWith("172.16.")) {
            return 100.0;
        }

        // Compare with previous IP
        if (previousIp != null && !previousIp.isBlank()) {
            if (currentIp.equals(previousIp)) {
                return 95.0; // Same IP as last session
            }

            // Check same subnet (first 3 octets for IPv4)
            if (isSameSubnet(currentIp, previousIp)) {
                return 70.0; // Same subnet
            }
        }

        // New IP, new subnet
        return 40.0;
    }

    // ══════════════════════════════════════════════════════════════
    //  STEP 2: WEIGHTED FUSION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the pre-decay ICS score from component scores.
     *
     * Formula:
     *   pre_decay = B * 0.35 + D * 0.20 + T * 0.25 + C * 0.20
     *
     * The weights reflect the relative importance of each trust signal:
     *   - Behavioral (0.35): Highest — directly measures identity match
     *   - Temporal (0.25): Significant — captures timing anomalies
     *   - Device (0.20): Important — device fingerprint is hard to spoof
     *   - Context (0.20): Moderate — IP can change legitimately
     *
     * @param behavioralScore B component [0, 100]
     * @param deviceScore     D component [0, 100]
     * @param temporalScore   T component [0, 100]
     * @param contextScore    C component [0, 100]
     * @return pre-decay ICS score [0, 100]
     */
    public double computePreDecayScore(double behavioralScore,
                                         double deviceScore,
                                         double temporalScore,
                                         double contextScore) {
        double preDecay = behavioralScore * W_BEHAVIORAL
                + deviceScore * W_DEVICE
                + temporalScore * W_TEMPORAL
                + contextScore * W_CONTEXT;

        return clamp(preDecay, 0.0, 100.0);
    }

    // ══════════════════════════════════════════════════════════════
    //  STEP 3: TEMPORAL DECAY
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the temporal decay factor.
     *
     * Formula:
     *   decay_factor = exp(-lambda * delta_t_hours)
     *
     * This models the natural degradation of identity confidence
     * over time. As more time passes since the last verified action,
     * the less confident we are that the current user is the same
     * person who authenticated.
     *
     * Parameters:
     *   lambda = 0.05 → half-life ≈ 13.86 hours
     *   After 1 hour:   decay = 0.951  (4.9% reduction)
     *   After 6 hours:  decay = 0.741  (25.9% reduction)
     *   After 12 hours: decay = 0.549  (45.1% reduction)
     *   After 24 hours: decay = 0.301  (69.9% reduction)
     *   After 48 hours: decay = 0.091  (90.9% reduction)
     *
     * @param deltaTHours hours since last verified event
     * @param lambda      decay rate (default: 0.05)
     * @return decay factor [0, 1]
     */
    public double computeDecayFactor(double deltaTHours, double lambda) {
        if (deltaTHours <= 0) {
            return 1.0; // No time passed → no decay
        }
        return Math.exp(-lambda * deltaTHours);
    }

    /**
     * Apply temporal decay to pre-decay score.
     *
     * Formula:
     *   post_decay = pre_decay * decay_factor
     *
     * @param preDecayScore pre-decay ICS score
     * @param decayFactor   computed decay factor
     * @return post-decay ICS score
     */
    public double applyTemporalDecay(double preDecayScore, double decayFactor) {
        return clamp(preDecayScore * decayFactor, 0.0, 100.0);
    }

    // ══════════════════════════════════════════════════════════════
    //  STEP 4: RECOVERY MECHANISM
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the recovery bonus from consecutive verified actions.
     *
     * Formula:
     *   recovery_bonus = recovery_rate * min(consecutive_verified, MAX_CONSECUTIVE)
     *
     * This mechanism allows users who have been temporarily flagged
     * to recover their ICS score through consecutive verified actions.
     * Each verified action (successful login, file access, etc.) adds
     * to the consecutive counter, boosting the ICS score.
     *
     * Parameters:
     *   recovery_rate = 2.0 points per verified action
     *   MAX_CONSECUTIVE = 10 → max recovery bonus = 20.0 points
     *
     * This ensures that:
     *   - A single verified action adds 2.0 points
     *   - 5 consecutive actions add 10.0 points
     *   - 10+ consecutive actions add 20.0 points (maximum)
     *   - The recovery is gradual, not instant
     *
     * @param consecutiveVerified number of consecutive verified actions
     * @param recoveryRate        recovery rate per action (default: 2.0)
     * @return recovery bonus [0, 20]
     */
    public double computeRecoveryBonus(int consecutiveVerified, double recoveryRate) {
        int cappedConsecutive = Math.min(consecutiveVerified, MAX_CONSECUTIVE_FOR_RECOVERY);
        return recoveryRate * cappedConsecutive;
    }

    // ══════════════════════════════════════════════════════════════
    //  STEP 5: FINAL ICS SCORE + CLASSIFICATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the final ICS score with all components.
     *
     * Full Formula:
     *   pre_decay  = B * 0.35 + D * 0.20 + T * 0.25 + C * 0.20
     *   decay      = exp(-lambda * delta_t_hours)
     *   post_decay = pre_decay * decay
     *   ics_score  = clamp(post_decay + recovery_bonus, 0, 100)
     *
     * @param behavioralScore    B component [0, 100]
     * @param deviceScore        D component [0, 100]
     * @param temporalScore      T component [0, 100]
     * @param contextScore       C component [0, 100]
     * @param deltaTHours        hours since last verified event
     * @param consecutiveVerified consecutive verified actions
     * @return final ICS score [0, 100]
     */
    public double computeICSScore(double behavioralScore,
                                   double deviceScore,
                                   double temporalScore,
                                   double contextScore,
                                   double deltaTHours,
                                   int consecutiveVerified) {
        // Step 2: Weighted fusion
        double preDecay = computePreDecayScore(behavioralScore, deviceScore,
                temporalScore, contextScore);

        // Step 3: Temporal decay
        double decayFactor = computeDecayFactor(deltaTHours, DEFAULT_DECAY_LAMBDA);
        double postDecay = applyTemporalDecay(preDecay, decayFactor);

        // Step 4: Recovery
        double recoveryBonus = computeRecoveryBonus(consecutiveVerified, DEFAULT_RECOVERY_RATE);

        // Step 5: Final score
        double icsScore = clamp(postDecay + recoveryBonus, 0.0, 100.0);

        log.debug("ICS computation: preDecay={:.2f}, decay={:.4f}, postDecay={:.2f}, " +
                        "recovery={:.2f}, final={:.2f}",
                preDecay, decayFactor, postDecay, recoveryBonus, icsScore);

        return icsScore;
    }

    /**
     * Classify ICS score into threshold category.
     *
     * ┌─────────────┬───────────┬─────────────────────────────────┐
     * │ Threshold   │ Score     │ Access Level                    │
     * ├─────────────┼───────────┼─────────────────────────────────┤
     * │ VERY_HIGH   │ 85 - 100  │ Full access, no restrictions    │
     * │ HIGH        │ 70 - 84   │ Standard access, monitor        │
     * │ MODERATE    │ 40 - 69   │ Step-up authentication required │
     * │ LOW         │ 25 - 39   │ MFA + restricted access         │
     * │ CRITICAL    │ 0 - 24    │ Access denied, re-auth required │
     * └─────────────┴───────────┴─────────────────────────────────┘
     *
     * @param icsScore computed ICS score [0, 100]
     * @return threshold classification
     */
    public ICSThreshold classifyThreshold(double icsScore) {
        return ICSThreshold.fromScore(icsScore);
    }

    /**
     * Classify ICS score into TrustLevel (entity-level classification).
     * Maps the 5-level threshold to the 4-level entity TrustLevel.
     *
     * Mapping:
     *   VERY_HIGH + HIGH → TRUSTED    (ICS >= 70)
     *   MODERATE         → ACCEPTED   (ICS >= 40)
     *   LOW              → CHALLENGED (ICS >= 25)
     *   CRITICAL         → REJECTED   (ICS < 25)
     *
     * @param icsScore computed ICS score
     * @return TrustLevel enum
     */
    public TrustLevel classifyTrustLevel(double icsScore) {
        return TrustLevel.fromScore(BigDecimal.valueOf(icsScore).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Check if the ICS score is below the operational threshold.
     * Below-threshold means the user requires additional verification.
     *
     * @param icsScore computed ICS score
     * @return true if ICS < 40 (below MODERATE threshold)
     */
    public boolean isBelowThreshold(double icsScore) {
        return icsScore < 40.0;
    }

    /**
     * Check if a security challenge should be triggered.
     *
     * @param icsScore computed ICS score
     * @return true if ICS < 70 (below HIGH threshold)
     */
    public boolean shouldTriggerChallenge(double icsScore) {
        return icsScore < 70.0;
    }

    // ══════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ══════════════════════════════════════════════════════════════

    /**
     * Check if two IPv4 addresses are in the same /24 subnet.
     */
    private boolean isSameSubnet(String ip1, String ip2) {
        if (ip1 == null || ip2 == null) return false;

        // Handle IPv6 (simple comparison for now)
        if (ip1.contains(":") || ip2.contains(":")) {
            // For IPv6, compare first 4 hextets (prefix /64)
            String[] parts1 = ip1.split(":");
            String[] parts2 = ip2.split(":");
            if (parts1.length >= 4 && parts2.length >= 4) {
                return parts1[0].equals(parts2[0]) && parts1[1].equals(parts2[1])
                        && parts1[2].equals(parts2[2]) && parts1[3].equals(parts2[3]);
            }
            return false;
        }

        // IPv4: compare first 3 octets (/24 subnet)
        String[] octets1 = ip1.split("\\.");
        String[] octets2 = ip2.split("\\.");

        if (octets1.length >= 3 && octets2.length >= 3) {
            return octets1[0].equals(octets2[0])
                    && octets1[1].equals(octets2[1])
                    && octets1[2].equals(octets2[2]);
        }

        return false;
    }

    /**
     * Clamp a value to [min, max] range.
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
