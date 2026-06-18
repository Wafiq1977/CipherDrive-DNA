package com.cipherdrive.dna.service.tem;

import com.cipherdrive.dna.entity.IdentityConfidence;
import com.cipherdrive.dna.entity.TrustEvolution;
import com.cipherdrive.dna.entity.TrustEvolution.Regime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * TEMCalculator — Core Mathematical Engine for Trust Evolution Model.
 *
 * ══════════════════════════════════════════════════════════════════
 *  MATHEMATICAL FOUNDATIONS
 * ══════════════════════════════════════════════════════════════════
 *
 * 1. ORNSTEIN-UHLENBECK PROCESS
 *    SDE:  dX = theta(mu - X)dt + sigma*dW
 *    - theta: Mean-reversion speed — how fast trust returns to mu
 *    - mu: Long-term mean trust level
 *    - sigma: Volatility of trust fluctuations
 *    - W: Standard Wiener process (Brownian motion)
 *
 * 2. EULER-MARUYAMA DISCRETIZATION
 *    X(t+dt) = X(t) + theta*(mu - X(t))*dt + sigma*sqrt(dt)*Z
 *    where Z ~ N(0,1)
 *
 * 3. DISCRETE DERIVATIVES (Moving Window)
 *    Trust Velocity:      v(t) = [ICS(t) - ICS(t-1)] / dt
 *    Trust Acceleration:  a(t) = [v(t) - v(t-1)] / dt
 *
 * 4. TRUST DEGRADATION INDEX (TDI)
 *    TDI = w_v * |velocity| + w_a * |acceleration|
 *    Default weights: w_v = 0.60, w_a = 0.40
 *
 * 5. OLS-MLE PARAMETER ESTIMATION
 *    mu  = sample mean of ICS time series
 *    theta  = OLS slope of dX vs (mu - X)
 *    sigma  = std deviation of OLS residuals
 *    R-sq = coefficient of determination
 *
 * 6. REGIME CLASSIFICATION
 *    STABLE:    Trust >= 70 and |velocity| < 2.0
 *    DRIFTING:  Trust >= 50 and |velocity| < 5.0
 *    DEGRADING: Trust < 50 and velocity < -1.0
 *    RECOVERING: velocity > 1.0
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
public class TEMCalculator {

    // ── Default Configuration ──

    private static final BigDecimal DEFAULT_TDI_VELOCITY_WEIGHT = new BigDecimal("0.60");
    private static final BigDecimal DEFAULT_TDI_ACCELERATION_WEIGHT = new BigDecimal("0.40");

    private static final double VELOCITY_STABLE_THRESHOLD = 2.0;
    private static final double VELOCITY_DRIFTING_THRESHOLD = 5.0;
    private static final double VELOCITY_RECOVERING_THRESHOLD = 1.0;
    private static final double VELOCITY_DEGRADING_THRESHOLD = -1.0;
    private static final double TRUST_HIGH_THRESHOLD = 70.0;
    private static final double TRUST_MODERATE_THRESHOLD = 50.0;

    private static final BigDecimal DEFAULT_THETA = new BigDecimal("0.100000");
    private static final BigDecimal DEFAULT_SIGMA = new BigDecimal("0.050000");
    private static final int MIN_OBSERVATIONS_FOR_OLS = 5;

    private final Random random = new Random();

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 1: TRUST VELOCITY (First Derivative)
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute trust velocity over a moving window of ICS observations.
     *
     * Uses backward difference for each consecutive pair:
     *   v(t_i) = [ICS(t_i) - ICS(t_{i-1})] / dt
     *
     * @param icsSnapshots  Sorted chronological list of ICS observations
     * @return List of velocity values (length = input - 1)
     */
    public List<BigDecimal> computeVelocitySeries(List<IdentityConfidence> icsSnapshots) {
        if (icsSnapshots == null || icsSnapshots.size() < 2) {
            return new ArrayList<>();
        }

        List<BigDecimal> velocities = new ArrayList<>();

        for (int i = 1; i < icsSnapshots.size(); i++) {
            IdentityConfidence current = icsSnapshots.get(i);
            IdentityConfidence previous = icsSnapshots.get(i - 1);

            BigDecimal deltaICS = current.getIcsScore().subtract(previous.getIcsScore());

            // dt in hours
            double deltaHours = computeDeltaHours(previous.getComputedAt(), current.getComputedAt());
            if (deltaHours <= 0.0) {
                deltaHours = 1.0 / 60.0; // Minimum 1 minute
            }

            BigDecimal velocity = deltaICS.divide(
                    BigDecimal.valueOf(deltaHours), 6, RoundingMode.HALF_UP);
            velocities.add(velocity);
        }

        log.debug("Computed {} velocity values from {} ICS observations",
                velocities.size(), icsSnapshots.size());
        return velocities;
    }

    /**
     * Compute the current (latest) trust velocity from ICS observations.
     *
     * @param icsSnapshots  Sorted chronological list
     * @return Current velocity (ICS points per hour), or 0 if insufficient data
     */
    public BigDecimal computeCurrentVelocity(List<IdentityConfidence> icsSnapshots) {
        List<BigDecimal> velocities = computeVelocitySeries(icsSnapshots);
        if (velocities.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return velocities.get(velocities.size() - 1);
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 2: TRUST ACCELERATION (Second Derivative)
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute trust acceleration over a moving window.
     *
     * Acceleration is the discrete second derivative:
     *   a(t_i) = [v(t_i) - v(t_{i-1})] / dt
     *
     * @param icsSnapshots  Sorted chronological list of ICS observations
     * @return List of acceleration values
     */
    public List<BigDecimal> computeAccelerationSeries(List<IdentityConfidence> icsSnapshots) {
        List<BigDecimal> velocities = computeVelocitySeries(icsSnapshots);
        if (velocities.size() < 2) {
            return new ArrayList<>();
        }

        List<BigDecimal> accelerations = new ArrayList<>();

        for (int i = 1; i < velocities.size(); i++) {
            BigDecimal deltaVelocity = velocities.get(i).subtract(velocities.get(i - 1));

            // Time interval between the corresponding ICS observations
            int icsIdx = i + 1;
            double deltaHours = computeDeltaHours(
                    icsSnapshots.get(icsIdx - 1).getComputedAt(),
                    icsSnapshots.get(icsIdx).getComputedAt());
            if (deltaHours <= 0.0) {
                deltaHours = 1.0 / 60.0;
            }

            BigDecimal acceleration = deltaVelocity.divide(
                    BigDecimal.valueOf(deltaHours), 6, RoundingMode.HALF_UP);
            accelerations.add(acceleration);
        }

        log.debug("Computed {} acceleration values from {} ICS observations",
                accelerations.size(), icsSnapshots.size());
        return accelerations;
    }

    /**
     * Compute the current (latest) trust acceleration.
     *
     * @param icsSnapshots  Sorted chronological list
     * @return Current acceleration (ICS points per hour squared), or 0 if insufficient data
     */
    public BigDecimal computeCurrentAcceleration(List<IdentityConfidence> icsSnapshots) {
        List<BigDecimal> accelerations = computeAccelerationSeries(icsSnapshots);
        if (accelerations.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return accelerations.get(accelerations.size() - 1);
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 3: TRUST DEGRADATION INDEX (TDI)
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the Trust Degradation Index (TDI).
     *
     * TDI is a composite metric combining the magnitude of trust velocity
     * and acceleration to quantify how rapidly trust is changing:
     *
     *   TDI = w_v * |velocity| + w_a * |acceleration|
     *
     * Default weights: w_v = 0.60 (velocity dominates), w_a = 0.40
     *
     * Interpretation:
     *   TDI < 1.0  -> Stable trust, minimal fluctuation
     *   TDI 1-3    -> Moderate drift, monitoring recommended
     *   TDI 3-5    -> Significant degradation, intervention needed
     *   TDI > 5.0  -> Critical degradation, immediate action required
     *
     * @param velocity      Current trust velocity
     * @param acceleration  Current trust acceleration
     * @return TDI composite score
     */
    public BigDecimal computeTDI(BigDecimal velocity, BigDecimal acceleration) {
        return computeTDI(velocity, acceleration,
                DEFAULT_TDI_VELOCITY_WEIGHT, DEFAULT_TDI_ACCELERATION_WEIGHT);
    }

    /**
     * Compute TDI with custom weights.
     */
    public BigDecimal computeTDI(BigDecimal velocity, BigDecimal acceleration,
                                  BigDecimal velocityWeight, BigDecimal accelerationWeight) {
        BigDecimal absVelocity = velocity.abs();
        BigDecimal absAcceleration = acceleration.abs();

        BigDecimal velocityContribution = absVelocity.multiply(velocityWeight);
        BigDecimal accelerationContribution = absAcceleration.multiply(accelerationWeight);

        return velocityContribution.add(accelerationContribution)
                .setScale(6, RoundingMode.HALF_UP);
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 4: OLS-MLE PARAMETER ESTIMATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Estimate Ornstein-Uhlenbeck parameters from ICS observations using OLS-MLE.
     *
     * The OU SDE dX = theta*(mu - X)*dt + sigma*dW can be discretized as:
     *   X(t+dt) - X(t) = theta*(mu - X(t))*dt + epsilon(t)
     *
     * This is a linear regression:
     *   Y = beta1 * X + epsilon
     * where:
     *   Y = X(t+dt) - X(t)  (ICS change)
     *   X = mu - X(t)         (distance from mean)
     *   beta1 = theta * dt    (slope gives theta after dividing by dt)
     *
     * MLE for sigma is the standard deviation of residuals.
     *
     * @param icsSnapshots  Chronological ICS observations (minimum 5)
     * @return OLSResult containing theta, mu, sigma, R-squared, theta std error
     */
    public OLSResult estimateOUParameters(List<IdentityConfidence> icsSnapshots) {
        if (icsSnapshots == null || icsSnapshots.size() < MIN_OBSERVATIONS_FOR_OLS) {
            log.warn("Insufficient observations for OLS estimation: {} < {}. Using defaults.",
                    icsSnapshots != null ? icsSnapshots.size() : 0, MIN_OBSERVATIONS_FOR_OLS);
            return OLSResult.defaultResult();
        }

        // Step 1: Compute sample mean (mu)
        double mu = icsSnapshots.stream()
                .mapToDouble(ic -> ic.getIcsScore().doubleValue())
                .average()
                .orElse(50.0);

        // Step 2: Build regression variables
        // Y_i = X(t_{i+1}) - X(t_i)  [ICS change]
        // X_i = mu - X(t_i)           [distance from mean]
        int n = icsSnapshots.size() - 1;
        double[] y = new double[n];
        double[] x = new double[n];
        double[] deltaTs = new double[n];

        for (int i = 0; i < n; i++) {
            double icsCurrent = icsSnapshots.get(i).getIcsScore().doubleValue();
            double icsNext = icsSnapshots.get(i + 1).getIcsScore().doubleValue();
            double dtHours = computeDeltaHours(
                    icsSnapshots.get(i).getComputedAt(),
                    icsSnapshots.get(i + 1).getComputedAt());

            y[i] = icsNext - icsCurrent;
            x[i] = mu - icsCurrent;
            deltaTs[i] = Math.max(dtHours, 1.0 / 60.0);
        }

        // Step 3: OLS regression — Y = beta1*X + beta0
        // We force beta0 = 0 (no drift when at mean), so: Y = beta1*X
        double sumXY = 0.0;
        double sumXX = 0.0;
        for (int i = 0; i < n; i++) {
            sumXY += x[i] * y[i];
            sumXX += x[i] * x[i];
        }

        double beta1 = (sumXX > 1e-12) ? sumXY / sumXX : 0.0;

        // Average dt for theta estimation
        double avgDt = 0.0;
        for (double dt : deltaTs) {
            avgDt += dt;
        }
        avgDt /= n;

        // theta = beta1 / dt (ensure positive for mean-reversion)
        double theta = Math.max(beta1 / avgDt, 0.001);

        // Cap theta to prevent unrealistic mean-reversion speed
        // theta > 10 means trust reverts within minutes — too aggressive
        theta = Math.min(theta, 10.0);

        // Step 4: Compute residuals and sigma (MLE)
        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            residuals[i] = y[i] - beta1 * x[i];
        }

        double sigma = computeStandardDeviation(residuals);
        sigma = Math.max(sigma, 0.001); // Floor to prevent zero volatility

        // Step 5: Compute R-squared (coefficient of determination)
        double yMean = 0.0;
        for (double yi : y) {
            yMean += yi;
        }
        yMean /= n;

        double ssTot = 0.0;
        double ssRes = 0.0;
        for (int i = 0; i < n; i++) {
            ssTot += (y[i] - yMean) * (y[i] - yMean);
            ssRes += residuals[i] * residuals[i];
        }

        double rSquared = (ssTot > 1e-12) ? 1.0 - ssRes / ssTot : 0.0;
        rSquared = Math.max(rSquared, 0.0); // R-squared can be negative for bad fits

        // Step 6: Compute theta standard error
        // SE(beta1) = sqrt(SSR / ((n-1) * SXX))
        // SE(theta) = SE(beta1) / dt
        double thetaStdError = 0.0;
        if (n > 1 && sumXX > 1e-12) {
            double seBeta1 = Math.sqrt(ssRes / ((n - 1) * sumXX));
            thetaStdError = seBeta1 / avgDt;
        }

        log.debug("OLS-MLE estimation: mu={}, theta={}, sigma={}, R-sq={}, SE(theta)={}, n={}",
                mu, theta, sigma, rSquared, thetaStdError, n);

        return OLSResult.builder()
                .mu(BigDecimal.valueOf(mu).setScale(2, RoundingMode.HALF_UP))
                .theta(BigDecimal.valueOf(theta).setScale(6, RoundingMode.HALF_UP))
                .sigma(BigDecimal.valueOf(sigma).setScale(6, RoundingMode.HALF_UP))
                .rSquared(BigDecimal.valueOf(rSquared).setScale(6, RoundingMode.HALF_UP))
                .thetaStdError(BigDecimal.valueOf(thetaStdError).setScale(6, RoundingMode.HALF_UP))
                .estimationWindow(icsSnapshots.size())
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 5: EULER-MARUYAMA SOLVER
    // ══════════════════════════════════════════════════════════════

    /**
     * Advance the OU process one step using Euler-Maruyama discretization.
     *
     * X(t+dt) = X(t) + theta*(mu - X(t))*dt + sigma*sqrt(dt)*Z
     *
     * where Z ~ N(0,1) is a standard normal random variable.
     *
     * This method:
     *   1. Computes the deterministic drift: theta*(mu - X)*dt
     *   2. Adds the stochastic diffusion: sigma*sqrt(dt)*Z
     *   3. Clamps the result to [0, 100]
     *
     * @param currentTrust  Current trust score X(t)
     * @param theta         Mean-reversion speed
     * @param mu            Long-term mean
     * @param sigma         Volatility
     * @param dtHours       Time step in hours
     * @return EulerMaruyamaStep containing new trust value and intermediate results
     */
    public EulerMaruyamaStep eulerMaruyamaStep(BigDecimal currentTrust, BigDecimal theta,
                                                 BigDecimal mu, BigDecimal sigma,
                                                 BigDecimal dtHours) {
        double X = currentTrust.doubleValue();
        double th = theta.doubleValue();
        double m = mu.doubleValue();
        double sig = sigma.doubleValue();
        double dt = dtHours.doubleValue();

        // Deterministic drift: theta*(mu - X)*dt
        double drift = th * (m - X) * dt;

        // Stochastic diffusion: sigma*sqrt(dt)*Z
        double Z = random.nextGaussian();
        double diffusion = sig * Math.sqrt(dt) * Z;

        // Euler-Maruyama update
        double Xnew = X + drift + diffusion;

        // Clamp to valid ICS range [0, 100]
        Xnew = Math.max(0.0, Math.min(100.0, Xnew));

        return EulerMaruyamaStep.builder()
                .previousTrust(BigDecimal.valueOf(X).setScale(2, RoundingMode.HALF_UP))
                .newTrust(BigDecimal.valueOf(Xnew).setScale(2, RoundingMode.HALF_UP))
                .drift(BigDecimal.valueOf(drift).setScale(6, RoundingMode.HALF_UP))
                .diffusion(BigDecimal.valueOf(diffusion).setScale(6, RoundingMode.HALF_UP))
                .dwValue(BigDecimal.valueOf(Z * Math.sqrt(dt)).setScale(8, RoundingMode.HALF_UP))
                .build();
    }

    /**
     * Multi-step Euler-Maruyama forecast.
     *
     * Projects trust forward N steps using the OU process.
     * Useful for trust trend prediction and proactive alerting.
     *
     * @param currentTrust  Starting trust score
     * @param theta         Mean-reversion speed
     * @param mu            Long-term mean
     * @param sigma         Volatility
     * @param dtHours       Time step per iteration
     * @param steps         Number of steps to forecast
     * @return List of forecasted trust values (length = steps + 1, including current)
     */
    public List<BigDecimal> forecastTrust(BigDecimal currentTrust, BigDecimal theta,
                                            BigDecimal mu, BigDecimal sigma,
                                            BigDecimal dtHours, int steps) {
        List<BigDecimal> forecast = new ArrayList<>();
        forecast.add(currentTrust);

        BigDecimal trust = currentTrust;
        for (int i = 0; i < steps; i++) {
            EulerMaruyamaStep step = eulerMaruyamaStep(trust, theta, mu, sigma, dtHours);
            trust = step.getNewTrust();
            forecast.add(trust);
        }

        log.debug("Forecasted {} steps ahead: start={}, end={}, trend={}",
                steps, currentTrust, trust,
                trust.compareTo(currentTrust) > 0 ? "RISING" :
                        trust.compareTo(currentTrust) < 0 ? "FALLING" : "FLAT");

        return forecast;
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 6: REGIME CLASSIFICATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Classify the current trust regime based on trust level and dynamics.
     *
     * Decision Logic (priority ordered):
     *
     *   1. RECOVERING: velocity > 1.0
     *      Trust is actively rising toward mean — positive signal
     *
     *   2. DEGRADING: trust < 50 AND velocity < -1.0
     *      Trust is both low and actively falling — critical alert
     *
     *   3. DRIFTING: trust >= 50 AND |velocity| >= 2.0
     *      Trust is moderate but changing — monitor closely
     *
     *   4. STABLE: trust >= 70 AND |velocity| < 2.0
     *      Normal operation, trust is high and steady
     *
     *   5. DEFAULT: DRIFTING
     *      Fallback for unclassifiable states
     *
     * @param currentTrust  Current trust score
     * @param velocity      Current trust velocity
     * @return Classified regime
     */
    public Regime classifyRegime(BigDecimal currentTrust, BigDecimal velocity) {
        double trust = currentTrust.doubleValue();
        double vel = velocity.doubleValue();
        double absVel = Math.abs(vel);

        // Priority 1: Recovering (takes precedence — positive signal)
        if (vel > VELOCITY_RECOVERING_THRESHOLD) {
            return Regime.RECOVERING;
        }

        // Priority 2: Degrading (critical — trust low and falling)
        if (trust < TRUST_MODERATE_THRESHOLD && vel < VELOCITY_DEGRADING_THRESHOLD) {
            return Regime.DEGADING;
        }

        // Priority 3: Drifting (moderate trust but changing)
        if (trust >= TRUST_MODERATE_THRESHOLD && absVel >= VELOCITY_STABLE_THRESHOLD) {
            return Regime.DRIFTING;
        }

        // Priority 4: Stable (high trust and steady)
        if (trust >= TRUST_HIGH_THRESHOLD && absVel < VELOCITY_STABLE_THRESHOLD) {
            return Regime.STABLE;
        }

        // Default: Drifting for ambiguous states
        return Regime.DRIFTING;
    }

    /**
     * Classify regime from latest ICS observations directly.
     *
     * @param icsSnapshots  Chronological ICS list
     * @return Classified regime
     */
    public Regime classifyRegimeFromICS(List<IdentityConfidence> icsSnapshots) {
        if (icsSnapshots == null || icsSnapshots.isEmpty()) {
            return Regime.STABLE;
        }

        BigDecimal currentTrust = icsSnapshots.get(icsSnapshots.size() - 1).getIcsScore();
        BigDecimal velocity = computeCurrentVelocity(icsSnapshots);

        return classifyRegime(currentTrust, velocity);
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 7: REVERSION ALIGNMENT
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute reversion alignment — measures the OU drift strength relative
     * to current trust level.
     *
     * reversion_alignment = theta * (mu - X) / max(|X|, epsilon)
     *
     * Interpretation:
     *   Positive: OU drift is pulling trust upward (toward mean from below)
     *   Negative: OU drift is pulling trust downward (toward mean from above)
     *   Near zero: Trust is at or near the long-term mean
     *
     * @param currentTrust  Current trust score X(t)
     * @param theta         Mean-reversion speed
     * @param mu            Long-term mean
     * @return Reversion alignment value
     */
    public BigDecimal computeReversionAlignment(BigDecimal currentTrust,
                                                  BigDecimal theta, BigDecimal mu) {
        double X = currentTrust.doubleValue();
        double th = theta.doubleValue();
        double m = mu.doubleValue();

        double alignment = th * (m - X) / Math.max(Math.abs(X), 1e-6);

        return BigDecimal.valueOf(alignment).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Determine if trust is currently reverting toward the long-term mean.
     *
     * A trust value is "reverting" if the OU drift direction aligns
     * with the gap between current trust and mean:
     *   - If X < mu and drift > 0 (pulling up) -> reverting
     *   - If X > mu and drift < 0 (pulling down) -> reverting
     *
     * @param currentTrust  Current trust score
     * @param theta         Mean-reversion speed
     * @param mu            Long-term mean
     * @return true if trust is reverting toward mean
     */
    public boolean isReverting(BigDecimal currentTrust, BigDecimal theta, BigDecimal mu) {
        double X = currentTrust.doubleValue();
        double th = theta.doubleValue();
        double m = mu.doubleValue();

        double drift = th * (m - X);

        // Positive drift when X < mu -> reverting upward
        // Negative drift when X > mu -> reverting downward
        return (drift > 0 && X < m) || (drift < 0 && X > m);
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 8: TRUST TREND COMPUTATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute trust trend classification based on velocity and acceleration.
     *
     * Combines the sign and magnitude of both velocity and acceleration
     * to produce a qualitative trend description:
     *
     *   RISING_STRONG:    velocity > 2 and acceleration > 0
     *   RISING:           velocity > 0
     *   STABLE:           |velocity| <= 1
     *   FALLING:          velocity < 0
     *   FALLING_STRONG:   velocity < -2 and acceleration < 0
     *
     * @param velocity      Current trust velocity
     * @param acceleration  Current trust acceleration
     * @return Trust trend enum
     */
    public TrustTrend computeTrend(BigDecimal velocity, BigDecimal acceleration) {
        double vel = velocity.doubleValue();
        double acc = acceleration.doubleValue();

        if (vel > 2.0 && acc > 0.0) {
            return TrustTrend.RISING_STRONG;
        } else if (vel > 1.0) {
            return TrustTrend.RISING;
        } else if (vel < -2.0 && acc < 0.0) {
            return TrustTrend.FALLING_STRONG;
        } else if (vel < -1.0) {
            return TrustTrend.FALLING;
        } else {
            return TrustTrend.STABLE;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  PIPELINE STEP 9: TRUST ALERT GENERATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Generate trust alert level based on current TEM state.
     *
     * Alert levels (escalating severity):
     *   NONE:      STABLE regime, TDI < 1.0
     *   INFO:      DRIFTING regime with low TDI
     *   WARNING:   DRIFTING regime with TDI > 2.0
     *   CRITICAL:  DEGRADING regime
     *   EMERGENCY: DEGRADING regime with TDI > 5.0
     *
     * @param regime  Current trust regime
     * @param tdi     Trust Degradation Index
     * @return Alert level
     */
    public TrustAlert generateAlert(Regime regime, BigDecimal tdi) {
        double tdiValue = tdi.doubleValue();

        return switch (regime) {
            case STABLE -> TrustAlert.NONE;
            case RECOVERING -> tdiValue > 3.0 ? TrustAlert.INFO : TrustAlert.NONE;
            case DRIFTING -> {
                if (tdiValue > 2.0) yield TrustAlert.WARNING;
                else yield TrustAlert.INFO;
            }
            case DEGRADING -> {
                if (tdiValue > 5.0) yield TrustAlert.EMERGENCY;
                else yield TrustAlert.CRITICAL;
            }
        };
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPER: STATISTICAL UTILITIES
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute time delta in hours between two timestamps.
     */
    private double computeDeltaHours(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return 1.0;
        Duration duration = Duration.between(from, to);
        return duration.toMillis() / (1000.0 * 3600.0);
    }

    /**
     * Compute standard deviation of an array of values (MLE — uses n, not n-1).
     */
    private double computeStandardDeviation(double[] values) {
        if (values.length == 0) return 0.0;

        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;

        double sumSquaredDiff = 0.0;
        for (double v : values) {
            sumSquaredDiff += (v - mean) * (v - mean);
        }

        // Use n (MLE) rather than n-1 (unbiased) for consistency with OU MLE
        return Math.sqrt(sumSquaredDiff / values.length);
    }

    /**
     * Compute moving average of BigDecimal values.
     *
     * @param values  Input values
     * @param window  Window size
     * @return List of averaged values
     */
    public List<BigDecimal> computeMovingAverage(List<BigDecimal> values, int window) {
        if (values == null || values.size() < window) {
            return new ArrayList<>();
        }

        List<BigDecimal> result = new ArrayList<>();
        for (int i = window - 1; i < values.size(); i++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - window + 1; j <= i; j++) {
                sum = sum.add(values.get(j));
            }
            result.add(sum.divide(BigDecimal.valueOf(window), 6, RoundingMode.HALF_UP));
        }

        return result;
    }

    // ══════════════════════════════════════════════════════════════
    //  INNER CLASSES: DATA CARRIERS
    // ══════════════════════════════════════════════════════════════

    /**
     * OLS regression result for OU parameter estimation.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OLSResult {
        private BigDecimal mu;
        private BigDecimal theta;
        private BigDecimal sigma;
        private BigDecimal rSquared;
        private BigDecimal thetaStdError;
        private int estimationWindow;

        public static OLSResult defaultResult() {
            return OLSResult.builder()
                    .mu(new BigDecimal("50.00"))
                    .theta(DEFAULT_THETA)
                    .sigma(DEFAULT_SIGMA)
                    .rSquared(BigDecimal.ZERO)
                    .thetaStdError(BigDecimal.ZERO)
                    .estimationWindow(0)
                    .build();
        }
    }

    /**
     * Euler-Maruyama solver step result.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EulerMaruyamaStep {
        private BigDecimal previousTrust;
        private BigDecimal newTrust;
        private BigDecimal drift;
        private BigDecimal diffusion;
        private BigDecimal dwValue;
    }

    /**
     * Trust trend classification.
     */
    public enum TrustTrend {
        /** Trust rising rapidly with positive acceleration */
        RISING_STRONG,
        /** Trust increasing — velocity > 0 */
        RISING,
        /** Trust steady — |velocity| <= 1 */
        STABLE,
        /** Trust decreasing — velocity < 0 */
        FALLING,
        /** Trust falling rapidly with negative acceleration */
        FALLING_STRONG
    }

    /**
     * Trust alert severity levels.
     */
    public enum TrustAlert {
        /** No alert — stable operation */
        NONE,
        /** Informational — minor drift detected */
        INFO,
        /** Warning — significant drift, monitor closely */
        WARNING,
        /** Critical — active degradation, intervention needed */
        CRITICAL,
        /** Emergency — severe degradation, immediate action required */
        EMERGENCY;

        public boolean isActionable() {
            return this == CRITICAL || this == EMERGENCY;
        }

        public boolean requiresNotification() {
            return this != NONE;
        }
    }
}
