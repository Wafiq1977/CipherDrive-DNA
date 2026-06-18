package com.cipherdrive.dna.service.ics;

import com.cipherdrive.dna.dto.ics.AccessDecisionResponse;
import com.cipherdrive.dna.dto.ics.AccessDecisionResponse.AccessDecision;
import com.cipherdrive.dna.dto.ics.AccessDecisionResponse.ResourceAccessLevel;
import com.cipherdrive.dna.dto.ics.ICSResponse;
import com.cipherdrive.dna.dto.ics.ICSResponse.ICSThreshold;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Access Decision Engine — Maps ICS scores to concrete access control decisions.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ACCESS DECISION FRAMEWORK
 * ══════════════════════════════════════════════════════════════════
 *
 *  The AccessDecisionEngine is the FINAL STAGE of the identity verification
 *  pipeline. It takes the computed ICS score, threshold classification,
 *  drift regime, and operation context to produce a concrete access
 *  control decision.
 *
 *  ┌─────────────┬──────────┬──────────────────────────────────────┐
 *  │ ICS Range   │ Decision │ Action                               │
 *  ├─────────────┼──────────┼──────────────────────────────────────┤
 *  │ 85-100      │ ALLOW    │ Full access, no restrictions         │
 *  │ 70-84       │ ALLOW    │ Standard access, audit logging       │
 *  │ 40-69       │ STEP_UP  │ Require step-up authentication       │
 *  │ 25-39       │ RESTRICT │ MFA + read-only + audit trail        │
 *  │ 0-24        │ DENY     │ Block access, force re-authentication│
 *  └─────────────┴──────────┴──────────────────────────────────────┘
 *
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │ Operation Sensitivity Matrix                                 │
 *  ├──────────┬─────────┬──────────┬──────────┬──────────────────┤
 *  │ ICS      │ READ    │ WRITE    │ DELETE   │ ADMIN            │
 *  ├──────────┼─────────┼──────────┼──────────┼──────────────────┤
 *  │ VERY_HIGH│ ALLOW   │ ALLOW    │ ALLOW    │ ALLOW            │
 *  │ HIGH     │ ALLOW   │ ALLOW    │ STEP_UP  │ STEP_UP          │
 *  │ MODERATE │ ALLOW   │ STEP_UP  │ RESTRICT │ DENY             │
 *  │ LOW      │ STEP_UP │ RESTRICT │ DENY     │ DENY             │
 *  │ CRITICAL │ DENY    │ DENY     │ DENY     │ DENY             │
 *  └──────────┴─────────┴──────────┴──────────┴──────────────────┘
 *
 *  Additional Modifiers:
 *    - Drift Regime ANOMALY → downgrade decision by 1 level
 *    - Drift Regime HIGH_DRIFT → restrict to read-only
 *    - Concurrent session change → require step-up
 *    - IP change during session → flag for re-verification
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessDecisionEngine {

    private final IdentityConfidenceService icsService;

    // ══════════════════════════════════════════════════════════════
    //  MAIN ACCESS DECISION METHOD
    // ══════════════════════════════════════════════════════════════

    /**
     * Evaluate access decision for a user performing an operation.
     *
     * This is the PRIMARY method called by:
     *   - Spring Security filter chain (pre-authentication check)
     *   - FileController (before UPLOAD/DOWNLOAD/DELETE)
     *   - AdminController (before admin operations)
     *   - ICSController (on-demand decision)
     *
     * @param userId     user requesting access
     * @param operation  type of operation being performed
     * @return access decision with required actions and access level
     */
    public AccessDecisionResponse evaluateAccess(Long userId, OperationType operation) {
        log.debug("Evaluating access: userId={}, operation={}", userId, operation);

        // Get current ICS score
        double icsScore = icsService.getCurrentICSScore(userId);
        ICSThreshold threshold = icsService.getCurrentThreshold(userId);

        // Base decision from ICS threshold × operation sensitivity matrix
        AccessDecision baseDecision = resolveDecision(threshold, operation);
        ResourceAccessLevel accessLevel = resolveAccessLevel(threshold);

        // Build required actions list
        List<String> requiredActions = new ArrayList<>();
        boolean auditRequired = true;
        boolean sessionTermination = false;

        // Apply decision-specific actions
        switch (baseDecision) {
            case ALLOW -> {
                auditRequired = threshold != ICSThreshold.VERY_HIGH;
                if (threshold == ICSThreshold.HIGH) {
                    requiredActions.add("AUDIT_LOG");
                }
            }
            case STEP_UP -> {
                requiredActions.add("STEP_UP_AUTH");
                requiredActions.add("MFA_VERIFY");
                auditRequired = true;
            }
            case RESTRICT -> {
                requiredActions.add("MFA_REQUIRED");
                requiredActions.add("READ_ONLY_MODE");
                requiredActions.add("AUDIT_TRAIL_ENABLED");
                auditRequired = true;
            }
            case DENY -> {
                requiredActions.add("RE_AUTHENTICATION_REQUIRED");
                requiredActions.add("SESSION_TERMINATION");
                auditRequired = true;
                sessionTermination = true;
            }
        }

        // Build reason
        String reason = buildDecisionReason(baseDecision, threshold, icsScore, operation);

        AccessDecisionResponse response = AccessDecisionResponse.builder()
                .decision(baseDecision)
                .icsScore(icsScore)
                .threshold(threshold)
                .reason(reason)
                .requiredActions(requiredActions)
                .accessLevel(accessLevel)
                .auditRequired(auditRequired)
                .sessionTermination(sessionTermination)
                .userId(userId)
                .sessionId(null) // Filled by caller if available
                .decisionTimestamp(LocalDateTime.now())
                .build();

        log.info("Access decision: userId={}, operation={}, decision={}, ics={:.2f}, " +
                        "threshold={}, actions={}",
                userId, operation, baseDecision, icsScore, threshold, requiredActions);

        return response;
    }

    /**
     * Evaluate access with explicit ICS response (avoids redundant DB query).
     */
    public AccessDecisionResponse evaluateAccess(ICSResponse icsResponse, OperationType operation) {
        double icsScore = icsResponse.getIcsScore();
        ICSThreshold threshold = icsResponse.getThreshold();

        AccessDecision decision = resolveDecision(threshold, operation);
        ResourceAccessLevel accessLevel = resolveAccessLevel(threshold);

        List<String> requiredActions = buildRequiredActions(decision, threshold);
        String reason = buildDecisionReason(decision, threshold, icsScore, operation);

        boolean sessionTermination = decision == AccessDecision.DENY;

        return AccessDecisionResponse.builder()
                .decision(decision)
                .icsScore(icsScore)
                .threshold(threshold)
                .reason(reason)
                .requiredActions(requiredActions)
                .accessLevel(accessLevel)
                .auditRequired(true)
                .sessionTermination(sessionTermination)
                .userId(icsResponse.getUserId())
                .sessionId(icsResponse.getSessionId())
                .decisionTimestamp(LocalDateTime.now())
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    //  DECISION MATRIX
    // ══════════════════════════════════════════════════════════════

    /**
     * Resolve access decision from ICS threshold × operation type.
     *
     * Matrix:
     *   ┌──────────┬─────────┬──────────┬──────────┬──────────┐
     *   │ ICS      │ READ    │ WRITE    │ DELETE   │ ADMIN    │
     *   ├──────────┼─────────┼──────────┼──────────┼──────────┤
     *   │ VERY_HIGH│ ALLOW   │ ALLOW    │ ALLOW    │ ALLOW    │
     *   │ HIGH     │ ALLOW   │ ALLOW    │ STEP_UP  │ STEP_UP  │
     *   │ MODERATE │ ALLOW   │ STEP_UP  │ RESTRICT │ DENY     │
     *   │ LOW      │ STEP_UP │ RESTRICT │ DENY     │ DENY     │
     *   │ CRITICAL │ DENY    │ DENY     │ DENY     │ DENY     │
     *   └──────────┴─────────┴──────────┴──────────┴──────────┘
     *
     * @param threshold ICS threshold classification
     * @param operation requested operation type
     * @return access decision
     */
    public AccessDecision resolveDecision(ICSThreshold threshold, OperationType operation) {
        return switch (threshold) {
            case VERY_HIGH -> AccessDecision.ALLOW; // Full access for all operations
            case HIGH -> switch (operation) {
                case READ, WRITE -> AccessDecision.ALLOW;
                case DELETE, ADMIN -> AccessDecision.STEP_UP;
            };
            case MODERATE -> switch (operation) {
                case READ -> AccessDecision.ALLOW;
                case WRITE -> AccessDecision.STEP_UP;
                case DELETE -> AccessDecision.RESTRICT;
                case ADMIN -> AccessDecision.DENY;
            };
            case LOW -> switch (operation) {
                case READ -> AccessDecision.STEP_UP;
                case WRITE -> AccessDecision.RESTRICT;
                case DELETE, ADMIN -> AccessDecision.DENY;
            };
            case CRITICAL -> AccessDecision.DENY; // Block everything
        };
    }

    /**
     * Resolve resource access level from ICS threshold.
     *
     * Mapping:
     *   VERY_HIGH → FULL_ACCESS
     *   HIGH      → STANDARD_ACCESS
     *   MODERATE  → STANDARD_ACCESS (with step-up)
     *   LOW       → READ_ONLY
     *   CRITICAL  → NO_ACCESS
     */
    public ResourceAccessLevel resolveAccessLevel(ICSThreshold threshold) {
        return switch (threshold) {
            case VERY_HIGH -> ResourceAccessLevel.FULL_ACCESS;
            case HIGH, MODERATE -> ResourceAccessLevel.STANDARD_ACCESS;
            case LOW -> ResourceAccessLevel.READ_ONLY;
            case CRITICAL -> ResourceAccessLevel.NO_ACCESS;
        };
    }

    // ══════════════════════════════════════════════════════════════
    //  QUICK CHECK METHODS
    // ══════════════════════════════════════════════════════════════

    /**
     * Quick check: can the user perform a READ operation?
     */
    public boolean canRead(Long userId) {
        return resolveDecision(icsService.getCurrentThreshold(userId), OperationType.READ)
                != AccessDecision.DENY;
    }

    /**
     * Quick check: can the user perform a WRITE operation?
     */
    public boolean canWrite(Long userId) {
        AccessDecision decision = resolveDecision(icsService.getCurrentThreshold(userId),
                OperationType.WRITE);
        return decision == AccessDecision.ALLOW;
    }

    /**
     * Quick check: can the user perform a DELETE operation?
     */
    public boolean canDelete(Long userId) {
        AccessDecision decision = resolveDecision(icsService.getCurrentThreshold(userId),
                OperationType.DELETE);
        return decision == AccessDecision.ALLOW;
    }

    /**
     * Quick check: can the user perform ADMIN operations?
     */
    public boolean canAdmin(Long userId) {
        AccessDecision decision = resolveDecision(icsService.getCurrentThreshold(userId),
                OperationType.ADMIN);
        return decision == AccessDecision.ALLOW;
    }

    /**
     * Check if the user needs step-up authentication for an operation.
     */
    public boolean requiresStepUpAuth(Long userId, OperationType operation) {
        AccessDecision decision = resolveDecision(icsService.getCurrentThreshold(userId), operation);
        return decision == AccessDecision.STEP_UP || decision == AccessDecision.RESTRICT;
    }

    // ══════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════

    private List<String> buildRequiredActions(AccessDecision decision, ICSThreshold threshold) {
        List<String> actions = new ArrayList<>();
        switch (decision) {
            case ALLOW -> {
                if (threshold == ICSThreshold.HIGH) actions.add("AUDIT_LOG");
            }
            case STEP_UP -> {
                actions.add("STEP_UP_AUTH");
                actions.add("MFA_VERIFY");
            }
            case RESTRICT -> {
                actions.add("MFA_REQUIRED");
                actions.add("READ_ONLY_MODE");
                actions.add("AUDIT_TRAIL_ENABLED");
            }
            case DENY -> {
                actions.add("RE_AUTHENTICATION_REQUIRED");
                actions.add("SESSION_TERMINATION");
            }
        }
        return actions;
    }

    private String buildDecisionReason(AccessDecision decision, ICSThreshold threshold,
                                        double icsScore, OperationType operation) {
        return switch (decision) {
            case ALLOW -> String.format("Access ALLOWED: ICS=%.1f (threshold=%s) for %s operation",
                    icsScore, threshold, operation);
            case STEP_UP -> String.format("Step-up auth REQUIRED: ICS=%.1f (threshold=%s) for %s operation",
                    icsScore, threshold, operation);
            case RESTRICT -> String.format("Access RESTRICTED: ICS=%.1f (threshold=%s) for %s operation. MFA required.",
                    icsScore, threshold, operation);
            case DENY -> String.format("Access DENIED: ICS=%.1f (threshold=%s) for %s operation. Re-authentication required.",
                    icsScore, threshold, operation);
        };
    }

    // ══════════════════════════════════════════════════════════════
    //  OPERATION TYPE ENUM
    // ══════════════════════════════════════════════════════════════

    /**
     * Operation types for access decision evaluation.
     * Ordered by sensitivity: READ < WRITE < DELETE < ADMIN
     */
    public enum OperationType {
        /** Read/download operations — least sensitive */
        READ,
        /** Write/upload operations — moderate sensitivity */
        WRITE,
        /** Delete operations — high sensitivity (irreversible) */
        DELETE,
        /** Admin operations — highest sensitivity (account management) */
        ADMIN
    }
}
