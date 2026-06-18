package com.cipherdrive.dna.config.tem;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * TEM (Trust Evolution Model) Engine Configuration Properties.
 *
 * Binds to: cipherdrive.tem.* in application.yml
 *
 * Configuration hierarchy:
 *   cipherdrive.tem
 *     ├── observation-window-days      — ICS lookback window for OU estimation
 *     ├── computation-interval-minutes — Minimum interval between TEM recomputations
 *     ├── moving-window-size           — Moving window for velocity/acceleration smoothing
 *     ├── tdi-weights                  — TDI component weights
 *     │   ├── velocity                 — Weight for |velocity| in TDI (default: 0.60)
 *     │   └── acceleration             — Weight for |acceleration| in TDI (default: 0.40)
 *     ├── regime-thresholds            — Regime classification thresholds
 *     │   ├── velocity-stable          — Max |velocity| for STABLE (default: 2.0)
 *     │   ├── velocity-drifting        — Max |velocity| for DRIFTING (default: 5.0)
 *     │   ├── velocity-recovering      — Min velocity for RECOVERING (default: 1.0)
 *     │   ├── velocity-degrading       — Max velocity for DEGRADING (default: -1.0)
 *     │   ├── trust-high               — Min trust for STABLE (default: 70.0)
 *     │   └── trust-moderate           — Min trust for DRIFTING (default: 50.0)
 *     ├── euler-maruyama               — Euler-Maruyama solver parameters
 *     │   ├── dt-hours                 — Default time step (hours)
 *     │   └── forecast-steps           — Number of forecast steps
 *     ├── ols-estimation               — OLS-MLE parameter estimation
 *     │   ├── min-observations         — Minimum ICS observations for OLS (default: 5)
 *     │   ├── max-theta                — Maximum allowed theta (default: 10.0)
 *     │   └── min-theta                — Minimum allowed theta (default: 0.001)
 *     ├── scheduler                    — Scheduled task cron expressions
 *     │   ├── recalculation-cron       — TEM recalculation schedule
 *     │   ├── monitoring-cron          — Regime monitoring schedule
 *     │   ├── parameter-estimation-cron— OU re-estimation schedule
 *     │   └── cleanup-cron             — Stale snapshot cleanup schedule
 *     └── retention-days               — Snapshot retention period
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cipherdrive.tem")
public class TEMEngineProperties {

    /** ICS lookback window for OU parameter estimation (days) */
    private int observationWindowDays = 14;

    /** Minimum interval between TEM recomputations per user (minutes) */
    private int computationIntervalMinutes = 5;

    /** Moving window size for velocity/acceleration smoothing */
    private int movingWindowSize = 5;

    /** TDI component weights */
    private TDIWeights tdiWeights = new TDIWeights();

    /** Regime classification thresholds */
    private RegimeThresholds regimeThresholds = new RegimeThresholds();

    /** Euler-Maruyama solver parameters */
    private EulerMaruyama eulerMaruyama = new EulerMaruyama();

    /** OLS-MLE parameter estimation */
    private OLSEstimation olsEstimation = new OLSEstimation();

    /** Scheduled task cron expressions */
    private Scheduler scheduler = new Scheduler();

    /** Snapshot retention period (days) */
    private int retentionDays = 90;

    @Data
    public static class TDIWeights {
        /** Weight for |velocity| in TDI computation */
        private double velocity = 0.60;
        /** Weight for |acceleration| in TDI computation */
        private double acceleration = 0.40;
    }

    @Data
    public static class RegimeThresholds {
        /** Maximum |velocity| for STABLE regime */
        private double velocityStable = 2.0;
        /** Maximum |velocity| for DRIFTING regime */
        private double velocityDrifting = 5.0;
        /** Minimum velocity for RECOVERING regime */
        private double velocityRecovering = 1.0;
        /** Maximum velocity for DEGRADING regime (negative = falling) */
        private double velocityDegrading = -1.0;
        /** Minimum trust score for STABLE regime */
        private double trustHigh = 70.0;
        /** Minimum trust score for DRIFTING regime */
        private double trustModerate = 50.0;
    }

    @Data
    public static class EulerMaruyama {
        /** Default time step for EM solver (hours) */
        private double dtHours = 1.0;
        /** Number of steps for trust forecast */
        private int forecastSteps = 6;
    }

    @Data
    public static class OLSEstimation {
        /** Minimum ICS observations required for OLS regression */
        private int minObservations = 5;
        /** Maximum allowed theta (mean-reversion speed) */
        private double maxTheta = 10.0;
        /** Minimum allowed theta (prevent zero mean-reversion) */
        private double minTheta = 0.001;
    }

    @Data
    public static class Scheduler {
        /** TEM recalculation: every 5 minutes */
        private String recalculationCron = "0 */5 * * * *";
        /** Regime monitoring: every 2 minutes */
        private String monitoringCron = "0 */2 * * * *";
        /** OU parameter re-estimation: daily at 02:30 */
        private String parameterEstimationCron = "0 30 2 * * *";
        /** Stale snapshot cleanup: daily at 03:30 */
        private String cleanupCron = "0 30 3 * * *";
    }
}
