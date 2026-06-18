package com.cipherdrive.dna.service.dna;

import com.cipherdrive.dna.entity.User;
import com.cipherdrive.dna.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Digital DNA Scheduler — Periodic Recalculation Engine for CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  SCHEDULED TASKS
 * ══════════════════════════════════════════════════════════════════
 *
 *  1. DNA RECALCULATION (every 5 minutes)
 *     → Recompute DNA profiles for users with recent activity
 *     → Only processes users who had events in the last observation window
 *     → Skips users whose latest computation is less than 5 minutes old
 *
 *  2. DRIFT REGIME CHECK (every 1 minute)
 *     → Scan for HIGH_DRIFT and ANOMALY regimes
 *     → Log security alerts for anomalous behavioral patterns
 *     → Feed into ICS engine for trust level adjustment
 *
 *  3. BASELINE RECALIBRATION (daily at 02:00)
 *     → Re-evaluate baseline DNA profiles for long-term users
 *     → Only recalibrate if user has >= 30 days of consistent NORMAL regime
 *     → Ensures baseline evolves with gradual behavior changes
 *
 *  4. STALE PROFILE CLEANUP (daily at 03:00)
 *     → Remove DNA profiles older than retention period (90 days)
 *     → Preserves baseline profiles from cleanup
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigitalDNAScheduler {

    private final DigitalDNAService digitalDNAService;
    private final UserRepository userRepository;

    /** Minimum minutes between DNA recomputations for the same user */
    private static final int RECOMPUTE_INTERVAL_MINUTES = 5;

    /** Retention period for DNA profiles in days */
    private static final int PROFILE_RETENTION_DAYS = 90;

    // ══════════════════════════════════════════════════════════════
    //  TASK 1: PERIODIC DNA RECALCULATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Recompute DNA profiles for active users.
     *
     * Schedule: Every 5 minutes
     * Cron: 0 */5 * * * *
     *
     * Strategy:
     *   - Fetch all users who logged in within the last 24 hours
     *   - For each user, call DigitalDNAService.computeAndPersistDNAProfile()
     *   - The service internally checks if recomputation is needed
     *     (skips if last computation was < 5 minutes ago)
     *
     * Performance:
     *   - Batch processing: processes users sequentially
     *   - Error isolation: one user's failure doesn't block others
     *   - Rate limiting: inherent 5-minute cooldown per user
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void recalculateDNAProfiles() {
        log.info("[DNA-SCHEDULER] Starting periodic DNA recalculation...");

        LocalDateTime activeSince = LocalDateTime.now().minusHours(24);
        List<User> activeUsers = userRepository.findByLastLoginAtAfter(activeSince);

        if (activeUsers.isEmpty()) {
            log.debug("[DNA-SCHEDULER] No active users found. Skipping.");
            return;
        }

        log.info("[DNA-SCHEDULER] Found {} active users for DNA recalculation", activeUsers.size());

        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        for (User user : activeUsers) {
            try {
                // The service internally checks if recomputation is needed
                digitalDNAService.computeAndPersistDNAProfile(user.getId());
                successCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("[DNA-SCHEDULER] Failed to recompute DNA for userId={}: {}",
                        user.getId(), e.getMessage());
            }
        }

        log.info("[DNA-SCHEDULER] Recalculation complete: processed={}, success={}, errors={}",
                activeUsers.size(), successCount, errorCount);
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 2: DRIFT REGIME SECURITY MONITORING
    // ══════════════════════════════════════════════════════════════

    /**
     * Monitor for HIGH_DRIFT and ANOMALY regimes.
     *
     * Schedule: Every 1 minute
     * Cron: 0 */1 * * * *
     *
     * This is the security alert feed:
     *   - Scans all DNA profiles computed in the last hour
     *   - Logs HIGH_DRIFT and ANOMALY regimes as security events
     *   - In production: would trigger push notifications, SIEM integration,
     *     and ICS recalculation
     */
    @Scheduled(cron = "0 */1 * * * *")
    public void monitorDriftRegimes() {
        var anomalousProfiles = digitalDNAService.getAnomalousProfiles(1);

        if (!anomalousProfiles.isEmpty()) {
            for (var profile : anomalousProfiles) {
                log.warn("[DNA-SECURITY] Drift anomaly detected: userId={}, regime={}, " +
                                "driftScore={}, similarity={}, profileId={}",
                        profile.getUserId(), profile.getDriftRegime(),
                        profile.getDriftScore(), profile.getCosineSimilarity(),
                        profile.getId());
            }
            log.info("[DNA-SECURITY] Total anomalous profiles in last hour: {}",
                    anomalousProfiles.size());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 3: BASELINE RECALIBRATION (DAILY)
    // ══════════════════════════════════════════════════════════════

    /**
     * Daily baseline recalibration for stable users.
     *
     * Schedule: Daily at 02:00 AM server time
     * Cron: 0 0 2 * * *
     *
     * Strategy:
     *   - For users with consistent NORMAL regime over 30+ days:
     *     → The latest profile becomes the new baseline
     *     → This allows the baseline to evolve gradually
     *   - Baseline recalibration is CRITICAL for:
     *     → Accommodating legitimate behavior changes (new job, new device)
     *     → Preventing false positives from stale baselines
     *     → Maintaining drift detection accuracy over time
     *
     * Note: Baseline recalibration is a future enhancement.
     *       Current implementation logs the intent only.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void recalibrateBaselines() {
        log.info("[DNA-SCHEDULER] Starting daily baseline recalibration...");

        // TODO: Implement baseline recalibration logic:
        // 1. Find users with 30+ consecutive NORMAL regime profiles
        // 2. For each qualifying user:
        //    a. Set the latest profile as the new baseline
        //    b. Update baseline_dna_id reference
        //    c. Log the recalibration event
        // 3. This ensures baselines evolve with legitimate behavior changes

        log.info("[DNA-SCHEDULER] Baseline recalibration check complete (no-op for MVP)");
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 4: STALE PROFILE CLEANUP (DAILY)
    // ══════════════════════════════════════════════════════════════

    /**
     * Remove stale DNA profiles beyond retention period.
     *
     * Schedule: Daily at 03:00 AM server time
     * Cron: 0 0 3 * * *
     *
     * Strategy:
     *   - Delete profiles older than 90 days
     *   - ALWAYS preserve baseline profiles (baseline_dna_id IS NULL)
     *   - Preserves at least 1 profile per user for continuity
     *   - Reduces database storage for the highest-volume table
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupStaleProfiles() {
        log.info("[DNA-SCHEDULER] Starting stale profile cleanup (retention={} days)...",
                PROFILE_RETENTION_DAYS);

        // TODO: Implement cleanup logic:
        // 1. DELETE FROM digital_dna WHERE computed_at < cutoff
        //    AND baseline_dna_id IS NOT NULL  -- preserve baselines
        //    AND id NOT IN (SELECT baseline_dna_id FROM digital_dna WHERE baseline_dna_id IS NOT NULL)
        // 2. Log count of deleted profiles

        log.info("[DNA-SCHEDULER] Stale profile cleanup complete (no-op for MVP)");
    }

    // ══════════════════════════════════════════════════════════════
    //  MANUAL TRIGGER (for testing and admin API)
    // ══════════════════════════════════════════════════════════════

    /**
     * Manually trigger DNA recalculation for a specific user.
     * Called by admin API endpoint or test harness.
     *
     * @param userId user to recalculate
     */
    public void triggerManualRecalculation(Long userId) {
        log.info("[DNA-SCHEDULER] Manual DNA recalculation triggered for userId={}", userId);
        try {
            digitalDNAService.computeAndPersistDNAProfile(userId);
            log.info("[DNA-SCHEDULER] Manual recalculation completed for userId={}", userId);
        } catch (Exception e) {
            log.error("[DNA-SCHEDULER] Manual recalculation failed for userId={}: {}",
                    userId, e.getMessage());
        }
    }
}
