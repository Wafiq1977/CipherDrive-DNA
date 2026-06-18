package com.cipherdrive.dna.dto.ics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO representing the access decision result from the AccessDecisionEngine.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ACCESS DECISION FRAMEWORK
 * ══════════════════════════════════════════════════════════════════
 *
 *  The AccessDecisionEngine maps ICS score + trust level + threshold
 *  to concrete access control actions:
 *
 *  ┌─────────────┬──────────┬──────────────────────────────────┐
 *  │ Threshold   │ Decision │ Action                           │
 *  ├─────────────┼──────────┼──────────────────────────────────┤
 *  │ VERY_HIGH   │ ALLOW    │ Full access, no restrictions     │
 *  │ HIGH        │ ALLOW    │ Standard access, audit logging   │
 *  │ MODERATE    │ STEP_UP  │ Require step-up authentication   │
 *  │ LOW         │ RESTRICT │ MFA + read-only + audit trail    │
 *  │ CRITICAL    │ DENY     │ Block access, force re-auth      │
 *  └─────────────┴──────────┴──────────────────────────────────┘
 *
 *  Additional factors:
 *    - Resource sensitivity level (PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED)
 *    - Operation type (READ, WRITE, DELETE, ADMIN)
 *    - Session trust (concurrent session count, IP change)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccessDecisionResponse {

    /** The access decision */
    private AccessDecision decision;

    /** ICS score that triggered this decision */
    private double icsScore;

    /** ICS threshold classification */
    private ICSResponse.ICSThreshold threshold;

    /** Reason for the decision (human-readable) */
    private String reason;

    /** Required actions the user must perform (e.g., MFA, re-auth) */
    private List<String> requiredActions;

    /** Resource access level granted */
    private ResourceAccessLevel accessLevel;

    /** Whether the operation should be logged as a security event */
    private boolean auditRequired;

    /** Whether the session should be terminated */
    private boolean sessionTermination;

    /** User ID */
    private Long userId;

    /** Session ID */
    private Long sessionId;

    /** Timestamp of the decision */
    private java.time.LocalDateTime decisionTimestamp;

    /**
     * Access decision types.
     */
    public enum AccessDecision {
        /** Full access granted */
        ALLOW,
        /** Access granted with step-up authentication required */
        STEP_UP,
        /** Access restricted to read-only with MFA */
        RESTRICT,
        /** Access denied completely */
        DENY
    }

    /**
     * Resource access level granted based on ICS score.
     */
    public enum ResourceAccessLevel {
        /** Full read/write/admin access */
        FULL_ACCESS,
        /** Standard read/write access */
        STANDARD_ACCESS,
        /** Read-only access */
        READ_ONLY,
        /** No access */
        NO_ACCESS
    }
}
