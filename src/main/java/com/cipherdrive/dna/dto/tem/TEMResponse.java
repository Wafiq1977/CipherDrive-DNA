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

/**
 * TEM (Trust Evolution Model) Snapshot Response DTO.
 *
 * Contains the complete output of a TEM computation:
 *   - Current trust score and regime
 *   - OU process parameters (theta, mu, sigma)
 *   - Trust dynamics (velocity, acceleration, TDI)
 *   - Trend and alert classification
 *   - Reversion alignment indicators
 *   - Model quality metrics (R-squared, std error)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TEMResponse {

    // ── Identifiers ──

    private Long id;
    private Long userId;

    // ── Current Trust State ──

    /** Current trust score (0-100) — the X(t) in the OU process */
    private BigDecimal currentTrust;

    /** Long-term mean trust level — the mu parameter */
    private BigDecimal mu;

    /** Current trust regime classification */
    private Regime regime;

    /** Previous regime (for transition detection) */
    private Regime previousRegime;

    /** Whether a regime transition occurred */
    private boolean regimeTransition;

    // ── OU Process Parameters ──

    /** Mean-reversion speed theta — how fast trust returns to mu */
    private BigDecimal theta;

    /** Volatility sigma — amplitude of trust fluctuations */
    private BigDecimal sigma;

    // ── Trust Dynamics ──

    /** Trust velocity: dX/dt (ICS points per hour) */
    private BigDecimal trustVelocity;

    /** Trust acceleration: d²X/dt² (ICS points per hour²) */
    private BigDecimal trustAcceleration;

    /** Trust Degradation Index: composite of velocity and acceleration */
    private BigDecimal tdiComposite;

    /** TDI velocity component weight (default 0.60) */
    private BigDecimal tdiVelocityWeight;

    /** TDI acceleration component weight (default 0.40) */
    private BigDecimal tdiAccelerationWeight;

    // ── Reversion Alignment ──

    /** Reversion alignment: OU drift strength relative to current trust */
    private BigDecimal reversionAlignment;

    /** Whether trust is currently reverting toward mean */
    private boolean isReverting;

    // ── Trend & Alert ──

    /** Trust trend direction and strength */
    private TrustTrend trustTrend;

    /** Trust alert severity level */
    private TrustAlert trustAlert;

    // ── Model Quality ──

    /** OLS R-squared: model fit quality (0-1, higher = better) */
    private BigDecimal olsRSquared;

    /** Theta standard error: estimation uncertainty */
    private BigDecimal thetaStdError;

    /** Number of observations used for parameter estimation */
    private Integer estimationWindow;

    /** Whether the OLS estimation is reliable (R-squared > 0.5) */
    private boolean estimationReliable;

    // ── Euler-Maruyama State ──

    /** EM time step in hours */
    private BigDecimal dtHours;

    /** Wiener increment dW value */
    private BigDecimal dwValue;

    /** EM iteration count */
    private Integer emIteration;

    // ── Metadata ──

    /** Regime transition count for this user */
    private Integer regimeTransitionCount;

    /** Computation time in milliseconds */
    private Integer computationTimeMs;

    /** Algorithm version */
    private String algorithmVersion;

    /** Timestamp of computation */
    private LocalDateTime computedAt;
}
