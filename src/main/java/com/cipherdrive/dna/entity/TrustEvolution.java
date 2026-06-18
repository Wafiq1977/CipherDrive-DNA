package com.cipherdrive.dna.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * TEM (Trust Evolution Model) snapshots — Ornstein-Uhlenbeck process state.
 * Maps to: trust_evolution table
 *
 * Mathematical Model:
 *   OU SDE:  dX = theta * (mu - X) * dt + sigma * dW
 *   Solver:  Euler-Maruyama discretization
 *   TDI:     0.6 * |velocity| + 0.4 * |acceleration|
 *   Regimes: STABLE | DRIFTING | DEGRADING | RECOVERING
 *
 * Parameter Estimation:
 *   theta: OLS-based MLE from historical ICS observations
 *   mu:    Sample mean of ICS time series
 *   sigma: Sample standard deviation of ICS residuals
 */
@Entity
@Table(name = "trust_evolution", indexes = {
        @Index(name = "ix_tem_user_computed", columnList = "user_id, computed_at DESC"),
        @Index(name = "ix_tem_regime", columnList = "regime, computed_at DESC"),
        @Index(name = "ix_tem_mu", columnList = "user_id, mu, computed_at DESC"),
        @Index(name = "ix_tem_regime_transition", columnList = "user_id, regime, previous_regime"),
        @Index(name = "ix_tem_current_trust", columnList = "current_trust")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user"})
public class TrustEvolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_tem_user_id"))
    private User user;

    // ── Ornstein-Uhlenbeck Parameters ──

    @Column(name = "theta", nullable = false, precision = 10, scale = 6)
    private BigDecimal theta;

    @Column(name = "mu", nullable = false, precision = 5, scale = 2)
    private BigDecimal mu;

    @Column(name = "sigma", nullable = false, precision = 10, scale = 6)
    private BigDecimal sigma;

    @Column(name = "current_trust", nullable = false, precision = 5, scale = 2)
    private BigDecimal currentTrust;

    // ── Euler-Maruyama Solver State ──

    @Column(name = "dt_hours", nullable = false, precision = 8, scale = 4)
    private BigDecimal dtHours;

    @Column(name = "dw_value", precision = 10, scale = 8)
    private BigDecimal dwValue;

    @Column(name = "em_iteration", nullable = false)
    @Builder.Default
    private Integer emIteration = 0;

    // ── Trust Velocity & Acceleration ──

    @Column(name = "trust_velocity", nullable = false, precision = 10, scale = 6)
    private BigDecimal trustVelocity;

    @Column(name = "trust_acceleration", nullable = false, precision = 10, scale = 6)
    private BigDecimal trustAcceleration;

    // ── Trust Degradation Index (TDI) ──

    @Column(name = "tdi_composite", nullable = false, precision = 10, scale = 6)
    private BigDecimal tdiComposite;

    @Column(name = "tdi_velocity_weight", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal tdiVelocityWeight = new BigDecimal("0.60");

    @Column(name = "tdi_acceleration_weight", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal tdiAccelerationWeight = new BigDecimal("0.40");

    // ── Reversion Alignment ──

    @Column(name = "reversion_alignment", nullable = false, precision = 10, scale = 6)
    private BigDecimal reversionAlignment;

    @Column(name = "is_reverting", nullable = false)
    @Builder.Default
    private Boolean isReverting = false;

    // ── Regime Classification ──

    @Column(name = "regime", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Regime regime;

    @Column(name = "previous_regime", length = 16)
    @Enumerated(EnumType.STRING)
    private Regime previousRegime;

    @Column(name = "regime_transition_count", nullable = false)
    @Builder.Default
    private Integer regimeTransitionCount = 0;

    // ── OLS-MLE Parameter Estimation ──

    @Column(name = "estimation_window", nullable = false)
    private Integer estimationWindow;

    @Column(name = "ols_r_squared", precision = 8, scale = 6)
    private BigDecimal olsRSquared;

    @Column(name = "theta_std_error", precision = 10, scale = 6)
    private BigDecimal thetaStdError;

    // ── Metadata ──

    @Column(name = "computation_time_ms", nullable = false)
    private Integer computationTimeMs;

    @Column(name = "algorithm_version", nullable = false, length = 16)
    @Builder.Default
    private String algorithmVersion = "2.0";

    @CreationTimestamp
    @Column(name = "computed_at", nullable = false, updatable = false)
    private LocalDateTime computedAt;

    // ── Enum ──

    public enum Regime {
        /** Trust >= 70 and |velocity| < 2.0: Normal operation */
        STABLE,
        /** Trust >= 50 and |velocity| < 5.0: Monitor for changes */
        DRIFTING,
        /** Trust < 50 and velocity < -1.0: Active trust degradation */
        DEGRADING,
        /** Velocity > 1.0: Trust recovering toward mean */
        RECOVERING
    }

    // ── Helper Methods ──

    /**
     * Detect if a regime transition occurred.
     */
    public boolean hasRegimeTransition() {
        return previousRegime != null && regime != previousRegime;
    }

    /**
     * Calculate the OU drift term: theta * (mu - currentTrust).
     * This represents the deterministic pull toward the long-term mean.
     */
    public BigDecimal calculateDrift() {
        return theta.multiply(mu.subtract(currentTrust));
    }

    /**
     * Check if trust is converging toward the long-term mean.
     */
    public boolean isConverging() {
        BigDecimal drift = calculateDrift();
        return drift.compareTo(BigDecimal.ZERO) > 0 && currentTrust.compareTo(mu) < 0
                || drift.compareTo(BigDecimal.ZERO) < 0 && currentTrust.compareTo(mu) > 0;
    }

    /**
     * Check if the parameter estimation quality is acceptable.
     * R-squared > 0.5 indicates a reasonable fit.
     */
    public boolean isEstimationReliable() {
        return olsRSquared != null && olsRSquared.doubleValue() > 0.5;
    }
}
