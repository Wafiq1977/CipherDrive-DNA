package com.cipherdrive.dna.controller;

import com.cipherdrive.dna.dto.tem.TEMResponse;
import com.cipherdrive.dna.dto.tem.TrustAlertResponse;
import com.cipherdrive.dna.dto.tem.TrustTrendResponse;
import com.cipherdrive.dna.entity.TrustEvolution.Regime;
import com.cipherdrive.dna.service.tem.TrustEvolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * TEMController — REST API for Trust Evolution Model.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ENDPOINTS
 * ══════════════════════════════════════════════════════════════════
 *
 *  GET  /api/tem/snapshot          — Get latest TEM snapshot
 *  POST /api/tem/compute           — Trigger on-demand TEM computation
 *  GET  /api/tem/trend             — Get comprehensive trust trend
 *  GET  /api/tem/history?limit=20  — Get TEM snapshot history
 *  GET  /api/tem/regime            — Get current regime classification
 *  GET  /api/tem/alerts?hours=1    — Get trust alerts
 *  GET  /api/tem/transitions?h=168 — Get regime transition events
 *  GET  /api/tem/requires-intervention — Check if intervention needed
 *
 *  ADMIN ENDPOINTS:
 *  GET  /api/tem/admin/distribution?h=24  — Regime distribution
 *  GET  /api/tem/admin/degrading?h=1      — Degrading users
 *  POST /api/tem/admin/recalculate/{id}   — Force recalculation
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@RestController
@RequestMapping("/api/tem")
@RequiredArgsConstructor
public class TEMController {

    private final TrustEvolutionService trustEvolutionService;

    // ══════════════════════════════════════════════════════════════
    //  USER ENDPOINTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get the latest TEM snapshot for the authenticated user.
     *
     * Returns the most recent TrustEvolution computation including:
     * current trust score, OU parameters, velocity, acceleration,
     * TDI, regime, trend, and alert level.
     */
    @GetMapping("/snapshot")
    public ResponseEntity<TEMResponse> getLatestSnapshot(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        Optional<TEMResponse> tem = trustEvolutionService.getLatestTEM(userId);

        return tem.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Trigger on-demand TEM computation for the authenticated user.
     *
     * Executes the full 9-stage TEM pipeline:
     *   Velocity -> Acceleration -> TDI -> OLS Estimation ->
     *   Euler-Maruyama -> Regime Classification -> Trend -> Alert
     *
     * Respects the computation interval cooldown (5 minutes).
     */
    @PostMapping("/compute")
    public ResponseEntity<TEMResponse> computeTEM(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        TEMResponse response = trustEvolutionService.computeAndPersistTEMSnapshot(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get comprehensive trust trend analysis.
     *
     * Includes historical trajectory with regime annotations,
     * velocity/acceleration time series, regime transitions,
     * and Euler-Maruyama forecast projection.
     */
    @GetMapping("/trend")
    public ResponseEntity<TrustTrendResponse> getTrustTrend(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        Optional<TrustTrendResponse> trend = trustEvolutionService.getTrustTrend(userId);

        return trend.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Get TEM snapshot history for the authenticated user.
     *
     * Returns the most recent snapshots in reverse chronological order.
     *
     * @param limit Maximum snapshots to return (1-100, default 20)
     */
    @GetMapping("/history")
    public ResponseEntity<List<TEMResponse>> getHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = extractUserId(userDetails);
        limit = Math.max(1, Math.min(limit, 100));
        List<TEMResponse> history = trustEvolutionService.getTEMHistory(userId, limit);
        return ResponseEntity.ok(history);
    }

    /**
     * Get current regime classification for the authenticated user.
     *
     * Returns the regime (STABLE/DRIFTING/DEGRADING/RECOVERING)
     * from the most recent TEM snapshot.
     */
    @GetMapping("/regime")
    public ResponseEntity<Regime> getCurrentRegime(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        Optional<TEMResponse> tem = trustEvolutionService.getLatestTEM(userId);

        return tem.map(t -> ResponseEntity.ok(t.getRegime()))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Get active trust alerts for the authenticated user.
     *
     * @param hours Lookback period in hours (default 1)
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<TrustAlertResponse>> getAlerts(
            @RequestParam(defaultValue = "1") int hours) {
        hours = Math.max(1, Math.min(hours, 168)); // Max 7 days
        List<TrustAlertResponse> alerts = trustEvolutionService.getTrustAlerts(hours);
        return ResponseEntity.ok(alerts);
    }

    /**
     * Get regime transition events for the authenticated user.
     *
     * Returns all regime transitions within the lookback period,
     * showing from/to regimes and the trust score at transition.
     *
     * @param h Lookback period in hours (default 168 = 7 days)
     */
    @GetMapping("/transitions")
    public ResponseEntity<List<TrustTrendResponse.RegimeTransitionEvent>> getTransitions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "168") int h) {
        Long userId = extractUserId(userDetails);
        h = Math.max(1, Math.min(h, 720)); // Max 30 days
        List<TrustTrendResponse.RegimeTransitionEvent> transitions =
                trustEvolutionService.getRegimeTransitions(userId, h);
        return ResponseEntity.ok(transitions);
    }

    /**
     * Check if the authenticated user requires security intervention.
     *
     * Returns true if the user is currently in DEGRADING regime,
     * indicating active trust degradation that requires attention.
     */
    @GetMapping("/requires-intervention")
    public ResponseEntity<Boolean> requiresIntervention(
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = extractUserId(userDetails);
        boolean requires = trustEvolutionService.requiresIntervention(userId);
        return ResponseEntity.ok(requires);
    }

    // ══════════════════════════════════════════════════════════════
    //  ADMIN ENDPOINTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get regime distribution counts across all users.
     *
     * Admin dashboard endpoint showing the distribution of trust
     * regimes across the platform for the specified time window.
     *
     * @param h Lookback period in hours (default 24)
     */
    @GetMapping("/admin/distribution")
    public ResponseEntity<String> getRegimeDistribution(
            @RequestParam(defaultValue = "24") int h) {
        h = Math.max(1, Math.min(h, 720));
        String distribution = trustEvolutionService.getRegimeDistribution(h);
        return ResponseEntity.ok(distribution);
    }

    /**
     * Get list of users currently experiencing trust degradation.
     *
     * Returns user IDs that have DEGRADING regime snapshots
     * within the specified lookback period.
     *
     * @param h Lookback period in hours (default 1)
     */
    @GetMapping("/admin/degrading")
    public ResponseEntity<List<Long>> getDegradingUsers(
            @RequestParam(defaultValue = "1") int h) {
        h = Math.max(1, Math.min(h, 168));
        List<Long> userIds = trustEvolutionService.getDegradingUserIds(h);
        return ResponseEntity.ok(userIds);
    }

    /**
     * Force TEM recalculation for a specific user.
     *
     * Admin endpoint to manually trigger TEM computation
     * bypassing the cooldown period.
     *
     * @param userId Target user ID
     */
    @PostMapping("/admin/recalculate/{userId}")
    public ResponseEntity<TEMResponse> forceRecalculation(@PathVariable Long userId) {
        TEMResponse response = trustEvolutionService.computeAndPersistTEMSnapshot(userId);
        return ResponseEntity.ok(response);
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPER
    // ══════════════════════════════════════════════════════════════

    /**
     * Extract user ID from JWT authenticated principal.
     * In this implementation, username is used as lookup key.
     */
    private Long extractUserId(UserDetails userDetails) {
        // This is a simplified extraction — in production, the JWT
        // should contain the user ID as a claim for direct extraction
        String username = userDetails.getUsername();
        // For now, the service layer handles user lookup by username
        // The actual user ID is resolved within the service
        // This controller passes a placeholder — the service resolves it
        // NOTE: In a real implementation, you'd resolve userId here
        // via UserRepository.findByUsername(username).getId()
        // For simplicity, we parse username as Long (MVP shortcut)
        try {
            return Long.parseLong(username);
        } catch (NumberFormatException e) {
            // Username is not numeric — need proper user ID resolution
            // This would be handled by injecting UserRepository here
            // or adding userId to JWT claims
            throw new IllegalStateException("Cannot resolve user ID from username: " + username);
        }
    }
}
