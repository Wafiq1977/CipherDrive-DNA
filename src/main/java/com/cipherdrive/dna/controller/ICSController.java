package com.cipherdrive.dna.controller;

import com.cipherdrive.dna.dto.ics.AccessDecisionResponse;
import com.cipherdrive.dna.dto.ics.ICSResponse;
import com.cipherdrive.dna.dto.ics.ICSResponse.ICSThreshold;
import com.cipherdrive.dna.service.ics.AccessDecisionEngine;
import com.cipherdrive.dna.service.ics.AccessDecisionEngine.OperationType;
import com.cipherdrive.dna.service.ics.IdentityConfidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for ICS (Identity Confidence Score) Engine — CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  API ENDPOINTS
 * ══════════════════════════════════════════════════════════════════
 *
 *  ┌────────┬───────────────────────────────────────┬────────┬─────────┐
 *  │ Method │ Endpoint                              │ Auth   │ Role    │
 *  ├────────┼───────────────────────────────────────┼────────┼─────────┤
 *  │ GET    │ /api/ics/score                        │ JWT    │ USER+   │
 *  │ POST   │ /api/ics/compute                      │ JWT    │ USER+   │
 *  │ GET    │ /api/ics/threshold                    │ JWT    │ USER+   │
 *  │ GET    │ /api/ics/history?limit=10             │ JWT    │ USER+   │
 *  │ POST   │ /api/ics/access-decision?op=WRITE     │ JWT    │ USER+   │
 *  │ GET    │ /api/ics/can-read                     │ JWT    │ USER+   │
 *  │ GET    │ /api/ics/can-write                    │ JWT    │ USER+   │
 *  │ GET    │ /api/ics/can-delete                   │ JWT    │ USER+   │
 *  │ GET    │ /api/ics/admin/below-threshold?h=24   │ JWT    │ ADMIN   │
 *  │ GET    │ /api/ics/admin/challenged?h=24        │ JWT    │ ADMIN   │
 *  └────────┴───────────────────────────────────────┴────────┴─────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/ics")
@RequiredArgsConstructor
public class ICSController {

    private final IdentityConfidenceService icsService;
    private final AccessDecisionEngine accessDecisionEngine;

    // ── USER ENDPOINTS ──

    /**
     * GET /api/ics/score
     * Get the current user's latest ICS score.
     */
    @GetMapping("/score")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ICSResponse> getMyScore(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        return icsService.getLatestICS(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * POST /api/ics/compute
     * Trigger on-demand ICS computation.
     */
    @PostMapping("/compute")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ICSResponse> computeMyICS(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        ICSResponse response = icsService.computeAndPersistICS(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/ics/threshold
     * Get the current user's ICS threshold classification (lightweight).
     */
    @GetMapping("/threshold")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> getMyThreshold(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        ICSThreshold threshold = icsService.getCurrentThreshold(userId);
        double score = icsService.getCurrentICSScore(userId);
        return ResponseEntity.ok(Map.of(
                "threshold", threshold.name(),
                "icsScore", score,
                "minScore", threshold.min,
                "maxScore", threshold.max
        ));
    }

    /**
     * GET /api/ics/history?limit=10
     * Get ICS score history for trend analysis.
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<ICSResponse>> getMyHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "10") int limit) {
        Long userId = extractUserId(userDetails);
        limit = Math.min(Math.max(limit, 1), 100);
        List<ICSResponse> history = icsService.getICSHistory(userId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * POST /api/ics/access-decision?op=WRITE
     * Evaluate access decision for a specific operation.
     */
    @PostMapping("/access-decision")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<AccessDecisionResponse> evaluateAccess(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "READ") String op) {
        Long userId = extractUserId(userDetails);
        OperationType operation = parseOperationType(op);
        AccessDecisionResponse decision = accessDecisionEngine.evaluateAccess(userId, operation);
        return ResponseEntity.ok(decision);
    }

    /**
     * GET /api/ics/can-read
     * Quick check if user can perform READ operations.
     */
    @GetMapping("/can-read")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Boolean>> canRead(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        return ResponseEntity.ok(Map.of("allowed", accessDecisionEngine.canRead(userId)));
    }

    /**
     * GET /api/ics/can-write
     * Quick check if user can perform WRITE operations.
     */
    @GetMapping("/can-write")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Boolean>> canWrite(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        return ResponseEntity.ok(Map.of("allowed", accessDecisionEngine.canWrite(userId)));
    }

    /**
     * GET /api/ics/can-delete
     * Quick check if user can perform DELETE operations.
     */
    @GetMapping("/can-delete")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Boolean>> canDelete(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        return ResponseEntity.ok(Map.of("allowed", accessDecisionEngine.canDelete(userId)));
    }

    // ── ADMIN ENDPOINTS ──

    /**
     * GET /api/ics/admin/below-threshold?h=24
     * Get all users below the ICS threshold — security monitoring.
     */
    @GetMapping("/admin/below-threshold")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<ICSResponse>> getBelowThresholdUsers(
            @RequestParam(defaultValue = "24") int h) {
        h = Math.min(Math.max(h, 1), 168);
        List<ICSResponse> users = icsService.getBelowThresholdUsers(h);
        return ResponseEntity.ok(users);
    }

    /**
     * GET /api/ics/admin/challenged?h=24
     * Get all users with security challenges triggered.
     */
    @GetMapping("/admin/challenged")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<ICSResponse>> getChallengedUsers(
            @RequestParam(defaultValue = "24") int h) {
        h = Math.min(Math.max(h, 1), 168);
        List<ICSResponse> users = icsService.getChallengedUsers(h);
        return ResponseEntity.ok(users);
    }

    // ── HELPERS ──

    private Long extractUserId(UserDetails userDetails) {
        try {
            return Long.parseLong(userDetails.getUsername());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid user identifier in security context");
        }
    }

    private OperationType parseOperationType(String op) {
        try {
            return OperationType.valueOf(op.toUpperCase());
        } catch (IllegalArgumentException e) {
            return OperationType.READ; // Default to least sensitive
        }
    }
}
