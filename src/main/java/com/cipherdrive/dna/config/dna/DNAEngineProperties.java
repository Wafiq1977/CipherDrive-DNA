package com.cipherdrive.dna.config.dna;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration Properties for the Digital DNA Engine.
 *
 * Maps to: cipherdrive.dna.* in application.yml
 *
 * These properties control:
 *   - Observation window for behavioral event aggregation
 *   - Drift regime classification thresholds
 *   - Weight fusion alpha parameter
 *   - Normalization bounds for each dimension
 *   - Scheduled task cron expressions
 *   - Data retention period
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cipherdrive.dna")
public class DNAEngineProperties {

    /** Time window in days for behavioral event aggregation (default: 7) */
    private int observationWindowDays = 7;

    /** Minimum minutes between DNA recomputations per user (default: 5) */
    private int computationIntervalMinutes = 5;

    /** Drift regime classification thresholds */
    private DriftThresholds driftThresholds = new DriftThresholds();

    /** Alpha parameter for composite weight fusion (default: 0.5) */
    private double compositeAlpha = 0.5;

    /** Normalization bounds for each dimension */
    private Normalization normalization = new Normalization();

    /** Scheduled task cron expressions */
    private Scheduler scheduler = new Scheduler();

    /** DNA profile retention period in days (default: 90) */
    private int retentionDays = 90;

    // ── Nested Classes ──

    @Data
    public static class DriftThresholds {
        /** Below this = NORMAL regime (default: 0.15) */
        private double normal = 0.15;
        /** Below this = LOW_DRIFT regime (default: 0.30) */
        private double low = 0.30;
        /** Below this = HIGH_DRIFT regime, above = ANOMALY (default: 0.50) */
        private double high = 0.50;
    }

    @Data
    public static class Normalization {
        /** Max expected logins per day for frequency normalization (default: 10) */
        private double maxLoginPerDay = 10.0;
        /** Max session duration in minutes for normalization (default: 480 = 8 hours) */
        private double maxSessionDurationMin = 480.0;
        /** Max file operations per day for frequency normalization (default: 50) */
        private double maxFileOpsPerDay = 50.0;
        /** Max file size in bytes for file size normalization (default: 100 MB) */
        private double maxFileSizeBytes = 100.0 * 1024 * 1024;
    }

    @Data
    public static class Scheduler {
        /** DNA recalculation cron expression (default: every 5 minutes) */
        private String recalculationCron = "0 */5 * * * *";
        /** Drift monitoring cron expression (default: every 1 minute) */
        private String monitoringCron = "0 */1 * * * *";
        /** Baseline recalibration cron expression (default: daily at 02:00) */
        private String baselineCron = "0 0 2 * * *";
        /** Stale profile cleanup cron expression (default: daily at 03:00) */
        private String cleanupCron = "0 0 3 * * *";
    }
}
