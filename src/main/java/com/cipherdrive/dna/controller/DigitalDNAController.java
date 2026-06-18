package com.cipherdrive.dna.controller;

import com.cipherdrive.dna.dto.dna.DNADriftResponse;
import com.cipherdrive.dna.dto.dna.DNAProfileResponse;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import com.cipherdrive.dna.service.dna.DigitalDNAScheduler;
import com.cipherdrive.dna.service.dna.DigitalDNAService;
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
 * REST Controller for Digital DNA Engine — CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  API ENDPOINTS
 * ══════════════════════════════════════════════════════════════════
 *
 *  ┌──────────────────────────────────────────────────────────────────┐
 *  │ Method │ Endpoint                          │ Auth   │ Role      │
 *  ├────────┼───────────────────────────────────┼────────┼───────────┤
 *  │ GET    │ /api/dna/profile                  │ JWT    │ USER+     │
 *  │ GET    │ /api/dna/drift                    │ JWT    │ USER+     │
 *  │ GET    │ /api/dna/history?limit=10         │ JWT    │ USER+     │
 *  │ POST   │ /api/dna/compute                  │ JWT    │ USER+     │
 *  │ GET    │ /api/dna/regime                   │ JWT    │ USER+     │
 *  │ GET    │ /api/dna/baseline-exists          │ JWT    │ USER+     │
 *  │ GET    │ /api/dna/admin/anomalies?hours=24 │ JWT    │ ADMIN     │
 *  │ POST   │ /api/dna/admin/recompute/{userId} │ JWT    │ ADMIN     │
 *  └──────────────────────────────────────────────────────────────────┘
 */
@Slf4j
@RestController
@RequestMapping("/api/dna")
@RequiredArgsConstructor
public class DigitalDNAController {

    private final DigitalDNAService digitalDNAService;
    private final DigitalDNAScheduler digitalDNAScheduler;

    // ── USER ENDPOINTS ──

    /**
     * Get the current user's latest DNA profile.
     *
     * GET /api/dna/profile
     *
     * Returns the most recently computed 8-dimensional behavioral vector
     * with cosine similarity, drift score, and weight configuration.
     */
    @GetMapping("/profile")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<DNAProfileResponse> getMyProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        return digitalDNAService.getLatestProfile(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Get the current user's drift analysis.
     *
     * GET /api/dna/drift
     *
     * Returns detailed drift analysis including:
     *   - Cosine similarity to baseline
     *   - Drift score and regime classification
     *   - Per-dimension drift breakdown
     *   - Euclidean distance
     */
    @GetMapping("/drift")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<DNADriftResponse> getMyDrift(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        return digitalDNAService.getLatestDriftAnalysis(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Get the current user's DNA profile history (drift trend).
     *
     * GET /api/dna/history?limit=10
     *
     * Returns recent DNA profiles for trend visualization.
     * Default limit: 10 profiles, max: 100.
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<DNAProfileResponse>> getMyHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "10") int limit) {
        Long userId = extractUserId(userDetails);
        limit = Math.min(Math.max(limit, 1), 100); // Clamp to [1, 100]
        List<DNAProfileResponse> history = digitalDNAService.getProfileHistory(userId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * Trigger on-demand DNA profile computation for the current user.
     *
     * POST /api/dna/compute
     *
     * Executes the full 7-stage DNA Engine pipeline:
     *   Event Ingestion → Dimension Extraction → Normalization
     *   → AHP Weighting → Entropy Weighting → Composite Fusion
     *   → Similarity & Drift Computation
     *
     * Note: Rate-limited to 1 computation per 5 minutes per user.
     */
    @PostMapping("/compute")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<DNAProfileResponse> computeMyProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        DNAProfileResponse profile = digitalDNAService.computeAndPersistDNAProfile(userId);
        return ResponseEntity.ok(profile);
    }

    /**
     * Get the current user's drift regime classification.
     *
     * GET /api/dna/regime
     *
     * Lightweight endpoint for real-time security checks.
     * Returns: { "regime": "NORMAL" | "LOW_DRIFT" | "HIGH_DRIFT" | "ANOMALY" }
     */
    @GetMapping("/regime")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> getMyRegime(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        DriftRegime regime = digitalDNAService.checkCurrentDriftRegime(userId);
        return ResponseEntity.ok(Map.of("regime", regime.name()));
    }

    /**
     * Check if the current user has an established baseline DNA profile.
     *
     * GET /api/dna/baseline-exists
     *
     * Returns: { "hasBaseline": true | false }
     * A baseline is required for drift computation.
     * The first DNA computation automatically establishes the baseline.
     */
    @GetMapping("/baseline-exists")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Boolean>> checkBaseline(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        boolean hasBaseline = digitalDNAService.hasBaseline(userId);
        return ResponseEntity.ok(Map.of("hasBaseline", hasBaseline));
    }

    // ── ADMIN ENDPOINTS ──

    /**
     * Get all anomalous DNA profiles (HIGH_DRIFT + ANOMALY).
     *
     * GET /api/dna/admin/anomalies?hours=24
     *
     * Admin-only security monitoring endpoint.
     * Returns profiles with concerning drift in the specified time window.
     */
    @GetMapping("/admin/anomalies")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<DNAProfileResponse>> getAnomalies(
            @RequestParam(defaultValue = "24") int hours) {
        hours = Math.min(Math.max(hours, 1), 168); // Clamp to [1, 168] (1 hour to 1 week)
        List<DNAProfileResponse> anomalies = digitalDNAService.getAnomalousProfiles(hours);
        return ResponseEntity.ok(anomalies);
    }

    /**
     * Admin-triggered DNA recomputation for a specific user.
     *
     * POST /api/dna/admin/recompute/{userId}
     *
     * Forces DNA profile recomputation regardless of cooldown period.
     * Used for: troubleshooting, post-security-incident re-evaluation.
     */
    @PostMapping("/admin/recompute/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> adminRecompute(@PathVariable Long userId) {
        log.info("Admin-triggered DNA recomputation for userId={}", userId);
        digitalDNAScheduler.triggerManualRecalculation(userId);
        return ResponseEntity.ok(Map.of(
                "status", "RECOMPUTATION_TRIGGERED",
                "userId", userId.toString()
        ));
    }

    // ── HELPER ──

    /**
     * Extract user ID from Spring Security UserDetails.
     * Assumes the username is the user's ID (stored as string).
     * Override this based on your custom UserDetails implementation.
     */
    private Long extractUserId(UserDetails userDetails) {
        try {
            return Long.parseLong(userDetails.getUsername());
        } catch (NumberFormatException e) {
            // Fallback: username might be the actual username string
            // In production, use a custom UserDetails with userId field
            log.warn("Cannot parse userId from username: {}", userDetails.getUsername());
            throw new IllegalArgumentException("Invalid user identifier in security context");
        }
    }
}
