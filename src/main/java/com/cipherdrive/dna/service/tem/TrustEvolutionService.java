package com.cipherdrive.dna.service.tem;

import com.cipherdrive.dna.config.tem.TEMEngineProperties;
import com.cipherdrive.dna.dto.tem.TEMResponse;
import com.cipherdrive.dna.dto.tem.TrustAlertResponse;
import com.cipherdrive.dna.dto.tem.TrustTrendResponse;
import com.cipherdrive.dna.dto.tem.TrustTrendResponse.RegimeTransitionEvent;
import com.cipherdrive.dna.dto.tem.TrustTrendResponse.TrustDataPoint;
import com.cipherdrive.dna.entity.IdentityConfidence;
import com.cipherdrive.dna.entity.TrustEvolution;
import com.cipherdrive.dna.entity.TrustEvolution.Regime;
import com.cipherdrive.dna.entity.User;
import com.cipherdrive.dna.exception.TEMEngineException;
import com.cipherdrive.dna.exception.TEMEngineException.ErrorCode;
import com.cipherdrive.dna.repository.IdentityConfidenceRepository;
import com.cipherdrive.dna.repository.TrustEvolutionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import com.cipherdrive.dna.service.tem.TEMCalculator.TrustAlert;
import com.cipherdrive.dna.service.tem.TEMCalculator.TrustTrend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TrustEvolutionService — TEM Orchestration Layer for CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  TEM COMPUTATION PIPELINE (9 stages)
 * ══════════════════════════════════════════════════════════════════
 *
 *  Stage 1: DATA LOADING
 *    → Load historical ICS observations for the user
 *    → Minimum 2 observations required; 5+ for OLS estimation
 *
 *  Stage 2: VELOCITY COMPUTATION
 *    → Compute trust velocity series via discrete first derivative
 *    → v(t) = [ICS(t) - ICS(t-1)] / dt
 *
 *  Stage 3: ACCELERATION COMPUTATION
 *    → Compute trust acceleration series via discrete second derivative
 *    → a(t) = [v(t) - v(t-1)] / dt
 *
 *  Stage 4: TDI COMPUTATION
 *    → Compute Trust Degradation Index: TDI = w_v·|v| + w_a·|a|
 *    → Default weights: w_v = 0.60, w_a = 0.40
 *
 *  Stage 5: OU PARAMETER ESTIMATION (OLS-MLE)
 *    → Estimate mu (mean), theta (reversion speed), sigma (volatility)
 *    → Compute R-squared and theta standard error
 *
 *  Stage 6: EULER-MARUYAMA STEP
 *    → Advance OU process one step: X(t+dt) = X + θ(μ−X)dt + σ√dt·Z
 *
 *  Stage 7: REGIME CLASSIFICATION
 *    → Classify into STABLE / DRIFTING / DEGRADING / RECOVERING
 *    → Detect regime transitions
 *
 *  Stage 8: REVERSION ALIGNMENT
 *    → Compute OU drift direction relative to current trust
 *    → Determine if trust is reverting toward mean
 *
 *  Stage 9: TREND & ALERT GENERATION
 *    → Compute qualitative trust trend
 *    → Generate trust alert severity level
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrustEvolutionService {

    private final TEMCalculator calculator;
    private final TrustEvolutionRepository trustEvolutionRepository;
    private final IdentityConfidenceRepository identityConfidenceRepository;
    private final UserRepository userRepository;
    private final TEMEngineProperties properties;

    private static final String ALGORITHM_VERSION = "2.0";

    // ══════════════════════════════════════════════════════════════
    //  MAIN PIPELINE: COMPUTE AND PERSIST TEM SNAPSHOT
    // ══════════════════════════════════════════════════════════════

    /**
     * Execute the full TEM computation pipeline and persist the result.
     *
     * This is the primary entry point for TEM computation, called by:
     *   - TrustScheduler (periodic recomputation)
     *   - TEMController (on-demand computation)
     *   - Event-driven triggers (after ICS score changes)
     *
     * @param userId  User ID to compute TEM for
     * @return TEMResponse containing all computation results
     * @throws TEMEngineException if insufficient data or computation failure
     */
    @Transactional
    public TEMResponse computeAndPersistTEMSnapshot(Long userId) {
        long startTime = System.currentTimeMillis();
        log.info("[TEM] Starting TEM pipeline for userId={}", userId);

        // ── Stage 1: Load historical ICS observations ──
        LocalDateTime windowStart = LocalDateTime.now()
                .minusDays(properties.getObservationWindowDays());
        List<IdentityConfidence> icsSnapshots = identityConfidenceRepository
                .findByUserIdBetween(userId, windowStart, LocalDateTime.now());

        if (icsSnapshots.size() < 2) {
            throw new TEMEngineException(ErrorCode.INSUFFICIENT_DATA,
                    "Insufficient ICS observations for TEM: userId=" + userId
                            + ", count=" + icsSnapshots.size() + ", minimum=2");
        }

        log.debug("[TEM] Stage 1: Loaded {} ICS observations for userId={}",
                icsSnapshots.size(), userId);

        // ── Stage 2: Compute trust velocity ──
        BigDecimal velocity = calculator.computeCurrentVelocity(icsSnapshots);
        log.debug("[TEM] Stage 2: Velocity={} for userId={}", velocity, userId);

        // ── Stage 3: Compute trust acceleration ──
        BigDecimal acceleration = calculator.computeCurrentAcceleration(icsSnapshots);
        log.debug("[TEM] Stage 3: Acceleration={} for userId={}", acceleration, userId);

        // ── Stage 4: Compute TDI ──
        BigDecimal tdiVelocityWeight = BigDecimal.valueOf(properties.getTdiWeights().getVelocity());
        BigDecimal tdiAccelerationWeight = BigDecimal.valueOf(properties.getTdiWeights().getAcceleration());
        BigDecimal tdiComposite = calculator.computeTDI(velocity, acceleration,
                tdiVelocityWeight, tdiAccelerationWeight);
        log.debug("[TEM] Stage 4: TDI={} for userId={}", tdiComposite, userId);

        // ── Stage 5: OU Parameter Estimation ──
        TEMCalculator.OLSResult olsResult = calculator.estimateOUParameters(icsSnapshots);
        log.debug("[TEM] Stage 5: OLS mu={}, theta={}, sigma={}, R²={} for userId={}",
                olsResult.getMu(), olsResult.getTheta(), olsResult.getSigma(),
                olsResult.getRSquared(), userId);

        // ── Stage 6: Euler-Maruyama Step ──
        BigDecimal currentTrust = icsSnapshots.get(icsSnapshots.size() - 1).getIcsScore();
        BigDecimal dtHours = BigDecimal.valueOf(properties.getEulerMaruyama().getDtHours());

        TEMCalculator.EulerMaruyamaStep emStep = calculator.eulerMaruyamaStep(
                currentTrust, olsResult.getTheta(), olsResult.getMu(),
                olsResult.getSigma(), dtHours);
        log.debug("[TEM] Stage 6: EM step: X={} -> X'={}, drift={}, diffusion={}",
                currentTrust, emStep.getNewTrust(), emStep.getDrift(), emStep.getDiffusion());

        // ── Stage 7: Regime Classification ──
        Regime regime = calculator.classifyRegime(currentTrust, velocity);
        Regime previousRegime = null;
        int regimeTransitionCount = 0;

        Optional<TrustEvolution> previousTEM = trustEvolutionRepository.findLatestByUserId(userId);
        if (previousTEM.isPresent()) {
            previousRegime = previousTEM.get().getRegime();
            regimeTransitionCount = previousTEM.get().getRegimeTransitionCount();
            if (regime != previousRegime) {
                regimeTransitionCount++;
            }
        }

        boolean regimeTransition = previousRegime != null && regime != previousRegime;
        log.debug("[TEM] Stage 7: Regime={}, previous={}, transition={}, userId={}",
                regime, previousRegime, regimeTransition, userId);

        // ── Stage 8: Reversion Alignment ──
        BigDecimal reversionAlignment = calculator.computeReversionAlignment(
                currentTrust, olsResult.getTheta(), olsResult.getMu());
        boolean isReverting = calculator.isReverting(
                currentTrust, olsResult.getTheta(), olsResult.getMu());
        log.debug("[TEM] Stage 8: Reversion alignment={}, isReverting={}, userId={}",
                reversionAlignment, isReverting, userId);

        // ── Stage 9: Trend & Alert Generation ──
        TrustTrend trend = calculator.computeTrend(velocity, acceleration);
        TrustAlert alert = calculator.generateAlert(regime, tdiComposite);
        log.debug("[TEM] Stage 9: Trend={}, Alert={}, userId={}", trend, alert, userId);

        // ── Build and persist TrustEvolution entity ──
        User user = userRepository.getReferenceById(userId);
        long computationTime = System.currentTimeMillis() - startTime;

        int emIteration = previousTEM
                .map(t -> t.getEmIteration() + 1)
                .orElse(0);

        TrustEvolution temEntity = TrustEvolution.builder()
                .user(user)
                .theta(olsResult.getTheta())
                .mu(olsResult.getMu())
                .sigma(olsResult.getSigma())
                .currentTrust(currentTrust)
                .dtHours(dtHours)
                .dwValue(emStep.getDwValue())
                .emIteration(emIteration)
                .trustVelocity(velocity)
                .trustAcceleration(acceleration)
                .tdiComposite(tdiComposite)
                .tdiVelocityWeight(tdiVelocityWeight)
                .tdiAccelerationWeight(tdiAccelerationWeight)
                .reversionAlignment(reversionAlignment)
                .isReverting(isReverting)
                .regime(regime)
                .previousRegime(previousRegime)
                .regimeTransitionCount(regimeTransitionCount)
                .estimationWindow(olsResult.getEstimationWindow())
                .olsRSquared(olsResult.getRSquared())
                .thetaStdError(olsResult.getThetaStdError())
                .computationTimeMs((int) computationTime)
                .algorithmVersion(ALGORITHM_VERSION)
                .build();

        trustEvolutionRepository.save(temEntity);

        log.info("[TEM] Pipeline complete: userId={}, trust={}, regime={}, velocity={}, " +
                        "acceleration={}, TDI={}, trend={}, alert={}, time={}ms",
                userId, currentTrust, regime, velocity, acceleration,
                tdiComposite, trend, alert, computationTime);

        // ── Build response DTO ──
        return buildTEMResponse(temEntity, trend, alert, regimeTransition);
    }

    // ══════════════════════════════════════════════════════════════
    //  QUERY OPERATIONS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get the latest TEM snapshot for a user.
     *
     * @param userId  User ID
     * @return Latest TEMResponse, or empty if no snapshot exists
     */
    @Transactional(readOnly = true)
    public Optional<TEMResponse> getLatestTEM(Long userId) {
        return trustEvolutionRepository.findLatestByUserId(userId)
                .map(tem -> {
                    TrustTrend trend = calculator.computeTrend(
                            tem.getTrustVelocity(), tem.getTrustAcceleration());
                    TrustAlert alert = calculator.generateAlert(tem.getRegime(), tem.getTdiComposite());
                    boolean transition = tem.hasRegimeTransition();
                    return buildTEMResponse(tem, trend, alert, transition);
                });
    }

    /**
     * Get TEM snapshot history for a user.
     *
     * @param userId  User ID
     * @param limit   Maximum number of snapshots to return
     * @return List of TEMResponse DTOs (most recent first)
     */
    @Transactional(readOnly = true)
    public List<TEMResponse> getTEMHistory(Long userId, int limit) {
        List<TrustEvolution> snapshots = trustEvolutionRepository.findRecentByUserId(userId, limit);
        return snapshots.stream()
                .map(tem -> {
                    TrustTrend trend = calculator.computeTrend(
                            tem.getTrustVelocity(), tem.getTrustAcceleration());
                    TrustAlert alert = calculator.generateAlert(tem.getRegime(), tem.getTdiComposite());
                    return buildTEMResponse(tem, trend, alert, tem.hasRegimeTransition());
                })
                .collect(Collectors.toList());
    }

    /**
     * Get regime transition events for a user.
     *
     * @param userId  User ID
     * @param hours   Lookback period in hours
     * @return List of regime transition events
     */
    @Transactional(readOnly = true)
    public List<RegimeTransitionEvent> getRegimeTransitions(Long userId, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<TrustEvolution> transitions = trustEvolutionRepository
                .findRegimeTransitionsByUserIdSince(userId, since);

        return transitions.stream()
                .map(tem -> RegimeTransitionEvent.builder()
                        .fromRegime(tem.getPreviousRegime())
                        .toRegime(tem.getRegime())
                        .trustScore(tem.getCurrentTrust())
                        .velocity(tem.getTrustVelocity())
                        .timestamp(tem.getComputedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════
    //  TRUST TREND ANALYSIS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get comprehensive trust trend analysis for a user.
     *
     * Includes historical trajectory, regime transitions,
     * and Euler-Maruyama forecast.
     *
     * @param userId  User ID
     * @return TrustTrendResponse with full trend data
     */
    @Transactional(readOnly = true)
    public Optional<TrustTrendResponse> getTrustTrend(Long userId) {
        Optional<TrustEvolution> latestOpt = trustEvolutionRepository.findLatestByUserId(userId);
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }

        TrustEvolution latest = latestOpt.get();

        // Load historical data points (last 50 TEM snapshots)
        List<TrustEvolution> history = trustEvolutionRepository
                .findRecentByUserId(userId, 50);

        // Build trajectory data points
        List<TrustDataPoint> dataPoints = new ArrayList<>();
        for (TrustEvolution tem : history) {
            TrustTrend trend = calculator.computeTrend(
                    tem.getTrustVelocity(), tem.getTrustAcceleration());
            dataPoints.add(TrustDataPoint.builder()
                    .trustScore(tem.getCurrentTrust())
                    .velocity(tem.getTrustVelocity())
                    .acceleration(tem.getTrustAcceleration())
                    .tdi(tem.getTdiComposite())
                    .regime(tem.getRegime())
                    .timestamp(tem.getComputedAt())
                    .build());
        }

        // Get regime transitions (last 7 days)
        List<RegimeTransitionEvent> transitions =
                getRegimeTransitions(userId, 168); // 7 days

        // Compute Euler-Maruyama forecast
        List<BigDecimal> forecast = calculator.forecastTrust(
                latest.getCurrentTrust(), latest.getTheta(), latest.getMu(),
                latest.getSigma(),
                BigDecimal.valueOf(properties.getEulerMaruyama().getDtHours()),
                properties.getEulerMaruyama().getForecastSteps());

        // Compute distance from mean
        BigDecimal distanceFromMean = latest.getCurrentTrust()
                .subtract(latest.getMu()).abs();

        TrustTrend currentTrend = calculator.computeTrend(
                latest.getTrustVelocity(), latest.getTrustAcceleration());
        TrustAlert currentAlert = calculator.generateAlert(
                latest.getRegime(), latest.getTdiComposite());

        return Optional.of(TrustTrendResponse.builder()
                .userId(userId)
                .currentTrust(latest.getCurrentTrust())
                .currentRegime(latest.getRegime())
                .currentTrend(currentTrend)
                .currentAlert(currentAlert)
                .currentVelocity(latest.getTrustVelocity())
                .currentAcceleration(latest.getTrustAcceleration())
                .currentTDI(latest.getTdiComposite())
                .mu(latest.getMu())
                .theta(latest.getTheta())
                .belowMean(latest.getCurrentTrust().compareTo(latest.getMu()) < 0)
                .distanceFromMean(distanceFromMean)
                .history(dataPoints)
                .regimeTransitions(transitions)
                .forecast(forecast)
                .forecastDtHours(BigDecimal.valueOf(properties.getEulerMaruyama().getDtHours()))
                .forecastSteps(properties.getEulerMaruyama().getForecastSteps())
                .computedAt(LocalDateTime.now())
                .build());
    }

    // ══════════════════════════════════════════════════════════════
    //  ALERT OPERATIONS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get active trust alerts across all users.
     *
     * Returns alerts for users currently in DEGRADING regime
     * or with high TDI scores.
     *
     * @param hours  Lookback period in hours
     * @return List of TrustAlertResponse DTOs
     */
    @Transactional(readOnly = true)
    public List<TrustAlertResponse> getTrustAlerts(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        // Get DEGRADING regime snapshots (critical alerts)
        List<TrustEvolution> degrading = trustEvolutionRepository.findDegradingSince(since);

        // Get high TDI snapshots (proactive alerts)
        List<TrustEvolution> highTDI = trustEvolutionRepository.findHighTDISince(3.0, since);

        // Combine and deduplicate
        List<TrustAlertResponse> alerts = new ArrayList<>();

        for (TrustEvolution tem : degrading) {
            TrustAlert alert = TrustAlert.EMERGENCY;
            if (tem.getTdiComposite().doubleValue() <= 5.0) {
                alert = TrustAlert.CRITICAL;
            }
            alerts.add(buildAlertResponse(tem, alert));
        }

        for (TrustEvolution tem : highTDI) {
            // Skip if already included from degrading list
            boolean alreadyIncluded = alerts.stream()
                    .anyMatch(a -> a.getUserId().equals(tem.getUser().getId())
                            && a.getTimestamp().equals(tem.getComputedAt()));
            if (!alreadyIncluded) {
                TrustAlert alert = calculator.generateAlert(tem.getRegime(), tem.getTdiComposite());
                if (alert.requiresNotification()) {
                    alerts.add(buildAlertResponse(tem, alert));
                }
            }
        }

        return alerts;
    }

    /**
     * Get users currently experiencing trust degradation.
     *
     * @param hours  Lookback period in hours
     * @return List of user IDs with DEGRADING regime
     */
    @Transactional(readOnly = true)
    public List<Long> getDegradingUserIds(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return trustEvolutionRepository.findUserIdsWithDegradingSince(since);
    }

    // ══════════════════════════════════════════════════════════════
    //  INCREMENTAL UPDATE
    // ══════════════════════════════════════════════════════════════

    /**
     * Trigger an incremental TEM update for a user.
     *
     * Respects the computation interval cooldown to prevent
     * excessive recomputation. Called after ICS score changes.
     *
     * @param userId  User ID
     * @return true if update was performed, false if rate-limited
     */
    @Transactional
    public boolean triggerIncrementalUpdate(Long userId) {
        Optional<TrustEvolution> latest = trustEvolutionRepository.findLatestByUserId(userId);

        if (latest.isPresent()) {
            LocalDateTime lastComputed = latest.get().getComputedAt();
            int cooldownMinutes = properties.getComputationIntervalMinutes();
            LocalDateTime earliest = lastComputed.plusMinutes(cooldownMinutes);

            if (LocalDateTime.now().isBefore(earliest)) {
                log.debug("[TEM] Rate-limited: userId={}, lastComputed={}, cooldown={}min",
                        userId, lastComputed, cooldownMinutes);
                return false;
            }
        }

        try {
            computeAndPersistTEMSnapshot(userId);
            return true;
        } catch (TEMEngineException e) {
            if (e.getErrorCode() == ErrorCode.INSUFFICIENT_DATA) {
                log.debug("[TEM] Skipping incremental update — insufficient data: userId={}", userId);
                return false;
            }
            throw e;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  REGIME ANALYTICS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get regime distribution counts for admin dashboard.
     *
     * @param hours  Lookback period in hours
     * @return Map of regime -> count
     */
    @Transactional(readOnly = true)
    public String getRegimeDistribution(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        long stable = trustEvolutionRepository.countByRegimeSince(Regime.STABLE, since);
        long drifting = trustEvolutionRepository.countByRegimeSince(Regime.DRIFTING, since);
        long degrading = trustEvolutionRepository.countByRegimeSince(Regime.DEGADING, since);
        long recovering = trustEvolutionRepository.countByRegimeSince(Regime.RECOVERING, since);

        return String.format("STABLE=%d, DRIFTING=%d, DEGRADING=%d, RECOVERING=%d",
                stable, drifting, degrading, recovering);
    }

    /**
     * Check if a user's current trust regime requires intervention.
     *
     * @param userId  User ID
     * @return true if user is in DEGRADING regime
     */
    @Transactional(readOnly = true)
    public boolean requiresIntervention(Long userId) {
        return trustEvolutionRepository.findLatestByUserId(userId)
                .map(tem -> tem.getRegime() == Regime.DEGADING)
                .orElse(false);
    }

    /**
     * Get total TEM snapshot count for a user.
     */
    @Transactional(readOnly = true)
    public long getSnapshotCount(Long userId) {
        return trustEvolutionRepository.countByUserId(userId);
    }

    // ══════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Build TEMResponse DTO from TrustEvolution entity.
     */
    private TEMResponse buildTEMResponse(TrustEvolution tem, TrustTrend trend,
                                           TrustAlert alert, boolean regimeTransition) {
        return TEMResponse.builder()
                .id(tem.getId())
                .userId(tem.getUser().getId())
                .currentTrust(tem.getCurrentTrust())
                .mu(tem.getMu())
                .regime(tem.getRegime())
                .previousRegime(tem.getPreviousRegime())
                .regimeTransition(regimeTransition)
                .theta(tem.getTheta())
                .sigma(tem.getSigma())
                .trustVelocity(tem.getTrustVelocity())
                .trustAcceleration(tem.getTrustAcceleration())
                .tdiComposite(tem.getTdiComposite())
                .tdiVelocityWeight(tem.getTdiVelocityWeight())
                .tdiAccelerationWeight(tem.getTdiAccelerationWeight())
                .reversionAlignment(tem.getReversionAlignment())
                .isReverting(tem.getIsReverting())
                .trustTrend(trend)
                .trustAlert(alert)
                .olsRSquared(tem.getOlsRSquared())
                .thetaStdError(tem.getThetaStdError())
                .estimationWindow(tem.getEstimationWindow())
                .estimationReliable(tem.isEstimationReliable())
                .dtHours(tem.getDtHours())
                .dwValue(tem.getDwValue())
                .emIteration(tem.getEmIteration())
                .regimeTransitionCount(tem.getRegimeTransitionCount())
                .computationTimeMs(tem.getComputationTimeMs())
                .algorithmVersion(tem.getAlgorithmVersion())
                .computedAt(tem.getComputedAt())
                .build();
    }

    /**
     * Build TrustAlertResponse from TrustEvolution entity.
     */
    private TrustAlertResponse buildAlertResponse(TrustEvolution tem, TrustAlert alert) {
        return TrustAlertResponse.builder()
                .alertLevel(alert)
                .userId(tem.getUser().getId())
                .currentTrust(tem.getCurrentTrust())
                .regime(tem.getRegime())
                .trustVelocity(tem.getTrustVelocity())
                .trustAcceleration(tem.getTrustAcceleration())
                .tdiComposite(tem.getTdiComposite())
                .message(String.format("Trust %s: score=%.1f, velocity=%.2f, TDI=%.2f",
                        alert.name(), tem.getCurrentTrust().doubleValue(),
                        tem.getTrustVelocity().doubleValue(),
                        tem.getTdiComposite().doubleValue()))
                .recommendedAction(TrustAlertResponse.getRecommendedAction(alert))
                .requiresIntervention(alert.isActionable())
                .timestamp(tem.getComputedAt())
                .build();
    }
}
