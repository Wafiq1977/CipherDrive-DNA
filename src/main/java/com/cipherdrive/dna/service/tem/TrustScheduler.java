package com.cipherdrive.dna.service.tem;

import com.cipherdrive.dna.entity.User;
import com.cipherdrive.dna.repository.TrustEvolutionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TrustScheduler — Periodic Trust Evolution Model Tasks for CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  SCHEDULED TASKS
 * ══════════════════════════════════════════════════════════════════
 *
 *  1. TEM RECALCULATION (every 5 minutes)
 *     -> Recompute TEM snapshots for users with recent ICS changes
 *     -> Only processes users active in the last 24 hours
 *     -> Respects 5-minute cooldown per user
 *
 *  2. REGIME TRANSITION MONITORING (every 2 minutes)
 *     -> Scan for DEGRADING and EMERGENCY alert-level regimes
 *     -> Log security alerts for anomalous trust trajectories
 *     -> Feed into ICS engine for proactive trust adjustment
 *
 *  3. OU PARAMETER RE-ESTIMATION (daily at 02:30)
 *     -> Re-estimate OU parameters (theta, mu, sigma) for all active users
 *     -> Uses full observation window for more accurate estimation
 *     -> Compares new parameters with previous to detect model drift
 *
 *  4. STALE SNAPSHOT CLEANUP (daily at 03:30)
 *     -> Remove TEM snapshots older than retention period (90 days)
 *     -> Preserves the most recent snapshot per user for continuity
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrustScheduler {

    private final TrustEvolutionService trustEvolutionService;
    private final TrustEvolutionRepository trustEvolutionRepository;
    private final UserRepository userRepository;

    /** Retention period for TEM snapshots in days */
    private static final int SNAPSHOT_RETENTION_DAYS = 90;

    // ══════════════════════════════════════════════════════════════
    //  TASK 1: PERIODIC TEM RECALCULATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Recompute TEM snapshots for active users.
     *
     * Schedule: Every 5 minutes
     * Cron: 0 */5 * * * *
     *
     * Strategy:
     *   - Fetch all users who logged in within the last 24 hours
     *   - For each user, call TrustEvolutionService.computeAndPersistTEMSnapshot()
     *   - The service internally checks if recomputation is needed
     *     (skips if last computation was < 5 minutes ago via cooldown)
     *
     * Performance:
     *   - Sequential processing with error isolation
     *   - One user's failure does not block others
     *   - Cooldown prevents redundant computation
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void recalculateTEMSnapshots() {
        log.info("[TEM-SCHEDULER] Starting periodic TEM recalculation...");

        LocalDateTime activeSince = LocalDateTime.now().minusHours(24);
        List<User> activeUsers = userRepository.findByLastLoginAtAfter(activeSince);

        if (activeUsers.isEmpty()) {
            log.debug("[TEM-SCHEDULER] No active users found. Skipping.");
            return;
        }

        log.info("[TEM-SCHEDULER] Found {} active users for TEM recalculation", activeUsers.size());

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        for (User user : activeUsers) {
            try {
                boolean updated = trustEvolutionService.triggerIncrementalUpdate(user.getId());
                if (updated) {
                    successCount++;
                } else {
                    skipCount++;
                }
            } catch (Exception e) {
                errorCount++;
                log.error("[TEM-SCHEDULER] Failed to recompute TEM for userId={}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("[TEM-SCHEDULER] Recalculation complete: processed={}, success={}, skipped={}, errors={}",
                activeUsers.size(), successCount, skipCount, errorCount);
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 2: REGIME TRANSITION SECURITY MONITORING
    // ══════════════════════════════════════════════════════════════

    /**
     * Monitor for DEGRADING regimes and high TDI scores.
     *
     * Schedule: Every 2 minutes
     * Cron: 0 */2 * * * *
     *
     * This is the security alert feed:
     *   - Scans all TEM snapshots computed in the last hour
     *   - Logs DEGRADING regimes as security events
     *   - In production: would trigger push notifications, SIEM integration,
     *     and ICS recalculation
     *
     * Alert Escalation:
     *   - DEGRADING regime with TDI > 5.0 -> EMERGENCY
     *   - DEGRADING regime with TDI <= 5.0 -> CRITICAL
     *   - DRIFTING regime with TDI > 2.0 -> WARNING
     */
    @Scheduled(cron = "0 */2 * * * *")
    public void monitorRegimeTransitions() {
        // Get all active trust alerts
        var alerts = trustEvolutionService.getTrustAlerts(1);

        if (!alerts.isEmpty()) {
            for (var alert : alerts) {
                if (alert.isRequiresIntervention()) {
                    log.warn("[TEM-SECURITY] Trust alert: userId={}, level={}, trust={}, " +
                                    "velocity={}, TDI={}, regime={}, action={}",
                            alert.getUserId(), alert.getAlertLevel(),
                            alert.getCurrentTrust(), alert.getTrustVelocity(),
                            alert.getTdiComposite(), alert.getRegime(),
                            alert.getRecommendedAction());
                } else {
                    log.info("[TEM-SECURITY] Trust notification: userId={}, level={}, trust={}",
                            alert.getUserId(), alert.getAlertLevel(), alert.getCurrentTrust());
                }
            }
            log.info("[TEM-SECURITY] Total trust alerts in last hour: {}", alerts.size());
        }

        // Log regime distribution for monitoring
        String distribution = trustEvolutionService.getRegimeDistribution(1);
        log.debug("[TEM-SECURITY] Regime distribution (1h): {}", distribution);
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 3: OU PARAMETER RE-ESTIMATION (DAILY)
    // ══════════════════════════════════════════════════════════════

    /**
     * Daily OU parameter re-estimation for active users.
     *
     * Schedule: Daily at 02:30 AM server time
     * Cron: 0 30 2 * * *
     *
     * Strategy:
     *   - For all active users, re-estimate OU parameters using
     *     the full observation window (14 days default)
     *   - Compare new parameters with previous estimation:
     *     -> If |theta_new - theta_old| / theta_old > 0.5: log warning
     *     -> If R-squared < 0.3: log poor model fit
     *   - This ensures OU parameters stay calibrated as user
     *     behavior evolves over time
     *
     * Why re-estimation matters:
     *   - User behavior is non-stationary (new devices, schedule changes)
     *   - Stale theta/mu/sigma leads to inaccurate trust forecasts
     *   - Regular re-estimation keeps the model responsive
     */
    @Scheduled(cron = "0 30 2 * * *")
    public void reestimateOUParameters() {
        log.info("[TEM-SCHEDULER] Starting daily OU parameter re-estimation...");

        LocalDateTime activeSince = LocalDateTime.now().minusDays(30);
        List<User> activeUsers = userRepository.findByLastLoginAtAfter(activeSince);

        if (activeUsers.isEmpty()) {
            log.debug("[TEM-SCHEDULER] No active users for OU re-estimation.");
            return;
        }

        int reestimated = 0;
        int errors = 0;

        for (User user : activeUsers) {
            try {
                trustEvolutionService.computeAndPersistTEMSnapshot(user.getId());
                reestimated++;
            } catch (Exception e) {
                errors++;
                log.debug("[TEM-SCHEDULER] OU re-estimation skipped for userId={}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("[TEM-SCHEDULER] OU re-estimation complete: reestimated={}, errors={}",
                reestimated, errors);
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 4: STALE SNAPSHOT CLEANUP (DAILY)
    // ══════════════════════════════════════════════════════════════

    /**
     * Remove stale TEM snapshots beyond retention period.
     *
     * Schedule: Daily at 03:30 AM server time
     * Cron: 0 30 3 * * *
     *
     * Strategy:
     *   - Delete snapshots older than 90 days
     *   - Always preserve at least the most recent snapshot per user
     *   - Reduces database storage for the trust_evolution table
     *
     * Note: In production, consider archival instead of deletion
     * for forensic analysis and long-term trust evolution studies.
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanupStaleSnapshots() {
        log.info("[TEM-SCHEDULER] Starting stale snapshot cleanup (retention={} days)...",
                SNAPSHOT_RETENTION_DAYS);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(SNAPSHOT_RETENTION_DAYS);

        try {
            trustEvolutionRepository.deleteOlderThan(cutoff);
            log.info("[TEM-SCHEDULER] Stale snapshot cleanup complete.");
        } catch (Exception e) {
            log.error("[TEM-SCHEDULER] Snapshot cleanup failed: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MANUAL TRIGGER (for testing and admin API)
    // ══════════════════════════════════════════════════════════════

    /**
     * Manually trigger TEM recalculation for a specific user.
     * Called by admin API endpoint or test harness.
     *
     * @param userId user to recalculate
     */
    public void triggerManualRecalculation(Long userId) {
        log.info("[TEM-SCHEDULER] Manual TEM recalculation triggered for userId={}", userId);
        try {
            trustEvolutionService.computeAndPersistTEMSnapshot(userId);
            log.info("[TEM-SCHEDULER] Manual recalculation completed for userId={}", userId);
        } catch (Exception e) {
            log.error("[TEM-SCHEDULER] Manual recalculation failed for userId={}: {}",
                    userId, e.getMessage());
        }
    }
}
