package com.cipherdrive.dna.dto.tem;

import com.cipherdrive.dna.entity.TrustEvolution.Regime;
import com.cipherdrive.dna.service.tem.TEMCalculator.TrustAlert;
import com.cipherdrive.dna.service.tem.TEMCalculator.TrustTrend;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Trust Trend Response DTO — Historical trend and forecast.
 *
 * Provides a time-series view of trust evolution including:
 *   - Historical trust trajectory with regime annotations
 *   - Velocity and acceleration time series
 *   - Euler-Maruyama forecast projection
 *   - Regime transition timeline
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrustTrendResponse {

    private Long userId;

    // ── Current State Summary ──

    /** Current trust score */
    private BigDecimal currentTrust;

    /** Current regime */
    private Regime currentRegime;

    /** Current trend */
    private TrustTrend currentTrend;

    /** Current alert level */
    private TrustAlert currentAlert;

    /** Current velocity */
    private BigDecimal currentVelocity;

    /** Current acceleration */
    private BigDecimal currentAcceleration;

    /** Current TDI */
    private BigDecimal currentTDI;

    // ── Long-term Statistics ──

    /** Long-term mean trust (mu) */
    private BigDecimal mu;

    /** Mean-reversion speed (theta) */
    private BigDecimal theta;

    /** Whether trust is currently below long-term mean */
    private boolean belowMean;

    /** Distance from long-term mean (absolute) */
    private BigDecimal distanceFromMean;

    // ── Historical Trajectory ──

    /** Historical trust scores (chronological) */
    private List<TrustDataPoint> history;

    /** Regime transition events */
    private List<RegimeTransitionEvent> regimeTransitions;

    // ── Forecast ──

    /** Euler-Maruyama forecasted trust values */
    private List<BigDecimal> forecast;

    /** Forecast time step (hours) */
    private BigDecimal forecastDtHours;

    /** Number of forecast steps */
    private Integer forecastSteps;

    // ── Trend Metadata ──

    /** Timestamp of this trend computation */
    private LocalDateTime computedAt;

    // ══════════════════════════════════════════════════════════════
    //  NESTED DTOs
    // ══════════════════════════════════════════════════════════════

    /**
     * Single data point in the trust trajectory.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrustDataPoint {
        private BigDecimal trustScore;
        private BigDecimal velocity;
        private BigDecimal acceleration;
        private BigDecimal tdi;
        private Regime regime;
        private LocalDateTime timestamp;
    }

    /**
     * Regime transition event.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegimeTransitionEvent {
        private Regime fromRegime;
        private Regime toRegime;
        private BigDecimal trustScore;
        private BigDecimal velocity;
        private LocalDateTime timestamp;
    }
}
