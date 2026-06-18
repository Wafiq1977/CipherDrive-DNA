package com.cipherdrive.dna.service.dna;

import com.cipherdrive.dna.dto.dna.DNADriftResponse;
import com.cipherdrive.dna.dto.dna.DNAProfileResponse;
import com.cipherdrive.dna.dto.dna.DNAProfileResponse.WeightSet;
import com.cipherdrive.dna.dto.dna.DNAVectorResponse;
import com.cipherdrive.dna.entity.*;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import com.cipherdrive.dna.repository.BehaviorLogRepository;
import com.cipherdrive.dna.repository.DigitalDNARepository;
import com.cipherdrive.dna.repository.FileRepository;
import com.cipherdrive.dna.repository.SessionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Digital DNA Service — Orchestration Layer for CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  7-STAGE DNA ENGINE PIPELINE
 * ══════════════════════════════════════════════════════════════════
 *
 *  Stage 1: EVENT INGESTION
 *    → Load raw BehaviorLog events for the user in the observation window
 *    → Data source: behavior_logs table (highest volume)
 *
 *  Stage 2: DIMENSION EXTRACTION
 *    → Extract 8 behavioral features from raw events
 *    → Login Frequency, Login Time Pattern, Session Duration,
 *      Upload/Download/Delete Frequency, Avg File Size, Device Consistency
 *
 *  Stage 3: NORMALIZATION
 *    → Scale each dimension to [0, 1] using domain-specific bounds
 *    → Enables cross-dimensional comparability
 *
 *  Stage 4: AHP WEIGHTING (Subjective)
 *    → Apply expert-judged Analytic Hierarchy Process weights
 *    → Device Consistency: 0.30, Login Time Pattern: 0.18, etc.
 *
 *  Stage 5: ENTROPY WEIGHTING (Objective)
 *    → Compute Shannon entropy weights from historical profiles
 *    → Dimensions with higher variance get more weight
 *
 *  Stage 6: COMPOSITE FUSION
 *    → w_final = alpha * w_ahp + (1 - alpha) * w_entropy
 *    → Default alpha = 0.5 (balanced subjective + objective)
 *
 *  Stage 7: SIMILARITY & DRIFT COMPUTATION
 *    → Weighted cosine similarity: cos(A, B) with composite weights
 *    → Drift score: 1 - cosine_similarity
 *    → Regime classification: NORMAL / LOW_DRIFT / HIGH_DRIFT / ANOMALY
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalDNAService {

    private final DigitalDNACalculator calculator;
    private final DigitalDNARepository digitalDNARepository;
    private final BehaviorLogRepository behaviorLogRepository;
    private final SessionRepository sessionRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** Default observation window for DNA computation (days) */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** Default computation window in milliseconds (5 minutes) */
    private static final int DEFAULT_COMPUTATION_WINDOW_MS = 300000;

    /** Algorithm version identifier */
    private static final String ALGORITHM_VERSION = "2.0";

    // ══════════════════════════════════════════════════════════════
    //  FULL PIPELINE: Compute & Persist DNA Profile
    // ══════════════════════════════════════════════════════════════

    /**
     * Execute the full 7-stage DNA Engine pipeline for a user.
     *
     * This is the PRIMARY entry point called by:
     *   - DigitalDNAScheduler (periodic recalculation)
     *   - BehaviorLogInterceptor (event-triggered incremental update)
     *   - DigitalDNAController (on-demand computation)
     *
     * @param userId user whose DNA profile to compute
     * @return computed and persisted DNA profile response
     */
    @Transactional
    public DNAProfileResponse computeAndPersistDNAProfile(Long userId) {
        long startTime = System.currentTimeMillis();

        log.info("Starting DNA Engine pipeline for userId={}", userId);

        // ── Get active session ──
        Session activeSession = getActiveSession(userId);

        // ── Stage 1: EVENT INGESTION ──
        LocalDateTime windowStart = LocalDateTime.now().minusDays(DEFAULT_WINDOW_DAYS);
        List<BehaviorLog> events = behaviorLogRepository.findByUserIdSince(userId, windowStart);

        if (events.isEmpty()) {
            log.warn("No behavioral events found for userId={} in last {} days. " +
                    "Creating minimal DNA profile.", userId, DEFAULT_WINDOW_DAYS);
        }

        // Load sessions and files for dimension computation
        List<Session> sessions = sessionRepository.findByUserIdAndExpiresAtAfter(
                userId, LocalDateTime.now().minusDays(DEFAULT_WINDOW_DAYS));
        List<FileEntity> files = fileRepository.findActiveByUserId(userId);

        // ── Stage 2-3: DIMENSION EXTRACTION + NORMALIZATION ──
        DNAVectorResponse currentVector = calculator.computeDNAVector(
                events, sessions, files, DEFAULT_WINDOW_DAYS);

        // ── Stage 4: AHP SUBJECTIVE WEIGHTS ──
        WeightSet ahpWeights = calculator.getAHPWeights();

        // ── Stage 5: ENTROPY OBJECTIVE WEIGHTS ──
        List<DNAVectorResponse> historicalVectors = loadHistoricalVectors(userId);
        WeightSet entropyWeights = calculator.computeEntropyWeights(historicalVectors);

        // ── Stage 6: COMPOSITE FUSION ──
        WeightSet compositeWeights = calculator.computeCompositeWeights(
                ahpWeights, entropyWeights, 0.5);

        // ── Stage 7: SIMILARITY & DRIFT ──
        double cosineSimilarity = 1.0; // Default: perfect match (no baseline yet)
        double driftScore = 0.0;
        double euclideanDistance = 0.0;
        DNAVectorResponse dimensionDrift = null;
        DigitalDNA baselineProfile = null;

        Optional<DigitalDNA> baselineOpt = digitalDNARepository.findBaselineByUserId(userId);
        if (baselineOpt.isPresent()) {
            baselineProfile = baselineOpt.get();
            DNAVectorResponse baselineVector = parseVectorFromJson(
                    baselineProfile.getCombinedVector());

            cosineSimilarity = calculator.computeCosineSimilarity(
                    currentVector, baselineVector, compositeWeights);
            driftScore = 1.0 - cosineSimilarity;
            euclideanDistance = calculator.computeEuclideanDistance(
                    currentVector, baselineVector, compositeWeights);
            dimensionDrift = calculator.computeDimensionDrift(currentVector, baselineVector);
        }

        DriftRegime driftRegime = calculator.classifyDriftRegime(driftScore);

        // ── Persist DigitalDNA Entity ──
        User user = userRepository.getReferenceById(userId);
        DigitalDNA dnaEntity = buildDNAEntity(
                user, activeSession, currentVector, ahpWeights, entropyWeights,
                compositeWeights, cosineSimilarity, driftScore, driftRegime,
                baselineProfile, events.size());

        digitalDNARepository.save(dnaEntity);

        long computationTime = System.currentTimeMillis() - startTime;
        log.info("DNA Engine pipeline completed: userId={}, regime={}, similarity={}, " +
                        "drift={}, samples={}, time={}ms",
                userId, driftRegime, cosineSimilarity, driftScore,
                events.size(), computationTime);

        // ── Build Response ──
        return DNAProfileResponse.builder()
                .id(dnaEntity.getId())
                .userId(userId)
                .sessionId(activeSession != null ? activeSession.getId() : null)
                .baselineProfileId(baselineProfile != null ? baselineProfile.getId() : null)
                .dnaVector(currentVector)
                .cosineSimilarity(cosineSimilarity)
                .driftScore(driftScore)
                .driftRegime(driftRegime)
                .ahpWeights(ahpWeights)
                .entropyWeights(entropyWeights)
                .compositeWeights(compositeWeights)
                .sampleCount(events.size())
                .algorithmVersion(ALGORITHM_VERSION)
                .computedAt(dnaEntity.getComputedAt())
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    //  DRIFT ANALYSIS (for Controller API)
    // ══════════════════════════════════════════════════════════════

    /**
     * Get the latest drift analysis for a user.
     *
     * @param userId user ID
     * @return drift analysis response, or empty if no profile exists
     */
    @Transactional(readOnly = true)
    public Optional<DNADriftResponse> getLatestDriftAnalysis(Long userId) {
        Optional<DigitalDNA> latestOpt = digitalDNARepository.findLatestByUserId(userId);
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }

        DigitalDNA latest = latestOpt.get();
        DNAVectorResponse currentVector = parseVectorFromJson(latest.getCombinedVector());

        DNAVectorResponse dimensionDrift = null;
        Long baselineId = null;

        if (latest.getBaselineProfile() != null) {
            baselineId = latest.getBaselineProfile().getId();
            DNAVectorResponse baselineVector = parseVectorFromJson(
                    latest.getBaselineProfile().getCombinedVector());
            dimensionDrift = calculator.computeDimensionDrift(currentVector, baselineVector);
        }

        return Optional.of(DNADriftResponse.builder()
                .cosineSimilarity(latest.getCosineSimilarity().doubleValue())
                .driftScore(latest.getDriftScore().doubleValue())
                .euclideanDistance(computeEuclideanFromEntity(latest))
                .driftRegime(latest.getDriftRegime())
                .dimensionDrift(dimensionDrift)
                .userId(userId)
                .sessionId(latest.getSession().getId())
                .baselineProfileId(baselineId)
                .currentProfileId(latest.getId())
                .sampleCount(latest.getSampleCount())
                .build());
    }

    // ══════════════════════════════════════════════════════════════
    //  DNA PROFILE RETRIEVAL
    // ══════════════════════════════════════════════════════════════

    /**
     * Get the latest DNA profile for a user.
     */
    @Transactional(readOnly = true)
    public Optional<DNAProfileResponse> getLatestProfile(Long userId) {
        return digitalDNARepository.findLatestByUserId(userId)
                .map(this::toProfileResponse);
    }

    /**
     * Get DNA profile history for a user (drift trend analysis).
     */
    @Transactional(readOnly = true)
    public List<DNAProfileResponse> getProfileHistory(Long userId, int limit) {
        return digitalDNARepository.findRecentByUserId(userId, limit).stream()
                .map(this::toProfileResponse)
                .toList();
    }

    /**
     * Get all anomalous profiles (HIGH_DRIFT + ANOMALY) — security alert feed.
     */
    @Transactional(readOnly = true)
    public List<DNAProfileResponse> getAnomalousProfiles(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return digitalDNARepository.findByDriftRegimesSince(
                        List.of(DriftRegime.HIGH_DRIFT, DriftRegime.ANOMALY), since).stream()
                .map(this::toProfileResponse)
                .toList();
    }

    /**
     * Check if a user has a baseline DNA profile established.
     */
    @Transactional(readOnly = true)
    public boolean hasBaseline(Long userId) {
        return digitalDNARepository.findBaselineByUserId(userId).isPresent();
    }

    /**
     * Get DNA profile count for a user — indicates how many computations done.
     */
    @Transactional(readOnly = true)
    public long getProfileCount(Long userId) {
        return digitalDNARepository.countByUserId(userId);
    }

    // ══════════════════════════════════════════════════════════════
    //  REAL-TIME DRIFT CHECK
    // ══════════════════════════════════════════════════════════════

    /**
     * Perform a lightweight real-time drift check without persisting.
     * Used by the security filter chain for continuous verification.
     *
     * @param userId user to check
     * @return current drift regime classification
     */
    @Transactional(readOnly = true)
    public DriftRegime checkCurrentDriftRegime(Long userId) {
        Optional<DigitalDNA> latestOpt = digitalDNARepository.findLatestByUserId(userId);
        if (latestOpt.isEmpty()) {
            return DriftRegime.NORMAL; // No profile → assume normal
        }
        return latestOpt.get().getDriftRegime();
    }

    /**
     * Check if a security challenge should be triggered for a user.
     * Called before sensitive operations (file delete, account changes).
     */
    @Transactional(readOnly = true)
    public boolean shouldTriggerChallenge(Long userId) {
        DriftRegime regime = checkCurrentDriftRegime(userId);
        return regime == DriftRegime.HIGH_DRIFT || regime == DriftRegime.ANOMALY;
    }

    // ══════════════════════════════════════════════════════════════
    //  INCREMENTAL DNA UPDATE (Event-Triggered)
    // ══════════════════════════════════════════════════════════════

    /**
     * Trigger an incremental DNA recalculation after a significant event.
     *
     * Unlike the full pipeline, this only recomputes if the last computation
     * is older than the computation window (5 minutes by default).
     *
     * @param userId user who triggered the event
     */
    @Transactional
    public void triggerIncrementalUpdate(Long userId) {
        Optional<DigitalDNA> latestOpt = digitalDNARepository.findLatestByUserId(userId);

        if (latestOpt.isPresent()) {
            DigitalDNA latest = latestOpt.get();
            long minutesSinceLastComputation = java.time.Duration.between(
                    latest.getComputedAt(), LocalDateTime.now()).toMinutes();

            // Only recompute if last computation was > 5 minutes ago
            if (minutesSinceLastComputation < 5) {
                log.debug("Skipping incremental DNA update: last computation was {} minutes ago " +
                        "(threshold: 5 min)", minutesSinceLastComputation);
                return;
            }
        }

        // No recent computation → run full pipeline
        log.info("Triggering incremental DNA update for userId={}", userId);
        try {
            computeAndPersistDNAProfile(userId);
        } catch (Exception e) {
            log.error("Incremental DNA update failed for userId={}: {}", userId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get the most recent active session for a user.
     */
    private Session getActiveSession(Long userId) {
        List<Session> activeSessions = sessionRepository.findByUserIdAndIsRevokedFalseAndExpiresAtAfter(
                userId, LocalDateTime.now());
        if (activeSessions.isEmpty()) {
            // Fallback: use any session
            List<Session> allSessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
            return allSessions.isEmpty() ? null : allSessions.get(0);
        }
        return activeSessions.get(0);
    }

    /**
     * Load historical DNA vectors for entropy weight computation.
     */
    private List<DNAVectorResponse> loadHistoricalVectors(Long userId) {
        List<DigitalDNA> recentProfiles = digitalDNARepository.findRecentByUserId(userId, 30);

        List<DNAVectorResponse> vectors = new ArrayList<>();
        for (DigitalDNA profile : recentProfiles) {
            try {
                DNAVectorResponse vector = parseVectorFromJson(profile.getCombinedVector());
                if (vector != null) {
                    vectors.add(vector);
                }
            } catch (Exception e) {
                log.warn("Failed to parse historical DNA vector for profileId={}: {}",
                        profile.getId(), e.getMessage());
            }
        }

        // Include current computation if we have enough history
        return vectors;
    }

    /**
     * Parse a DNAVectorResponse from JSON string stored in the database.
     */
    private DNAVectorResponse parseVectorFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, DNAVectorResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse DNA vector JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serialize a DNAVectorResponse to JSON for database storage.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Build a DigitalDNA JPA entity from computed data.
     */
    private DigitalDNA buildDNAEntity(User user, Session session, DNAVectorResponse vector,
                                      WeightSet ahpWeights, WeightSet entropyWeights,
                                      WeightSet compositeWeights, double cosineSimilarity,
                                      double driftScore, DriftRegime driftRegime,
                                      DigitalDNA baselineProfile, int sampleCount) {
        return DigitalDNA.builder()
                .user(user)
                .session(session != null ? session : getAnySession(user.getId()))
                .computationWindowMs(DEFAULT_COMPUTATION_WINDOW_MS)
                // Store each dimension vector as JSON
                .keystrokeVector("{\"note\":\"not_applicable_for_server_side\"}")
                .mouseVector("{\"note\":\"not_applicable_for_server_side\"}")
                .navigationVector("{\"note\":\"not_applicable_for_server_side\"}")
                .fileOpVector(toJson(DNAVectorResponse.builder()
                        .uploadFrequency(vector.getUploadFrequency())
                        .downloadFrequency(vector.getDownloadFrequency())
                        .deleteFrequency(vector.getDeleteFrequency())
                        .avgFileSize(vector.getAvgFileSize())
                        .build()))
                .temporalVector(toJson(DNAVectorResponse.builder()
                        .loginFrequency(vector.getLoginFrequency())
                        .loginTimePattern(vector.getLoginTimePattern())
                        .sessionDuration(vector.getSessionDuration())
                        .build()))
                .deviceVector(toJson(DNAVectorResponse.builder()
                        .deviceConsistency(vector.getDeviceConsistency())
                        .build()))
                .contextVector("{\"note\":\"requires_client_sdk\"}")
                // Combined 8-dimensional vector
                .combinedVector(toJson(vector))
                // Composite scores
                .cosineSimilarity(toBigDecimal(cosineSimilarity, 8))
                .driftScore(toBigDecimal(driftScore, 8))
                .driftRegime(driftRegime)
                // Weight configuration
                .ahpWeights(toJson(ahpWeights))
                .entropyWeights(toJson(entropyWeights))
                .compositeWeights(toJson(compositeWeights))
                // Baseline reference
                .baselineProfile(baselineProfile)
                // Metadata
                .sampleCount(sampleCount)
                .algorithmVersion(ALGORITHM_VERSION)
                .build();
    }

    /**
     * Fallback to get any session for a user.
     */
    private Session getAnySession(Long userId) {
        List<Session> sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    /**
     * Convert a DigitalDNA entity to a DNAProfileResponse DTO.
     */
    private DNAProfileResponse toProfileResponse(DigitalDNA entity) {
        DNAVectorResponse vector = parseVectorFromJson(entity.getCombinedVector());
        WeightSet ahpWeights = parseWeightSet(entity.getAhpWeights());
        WeightSet entropyWeights = parseWeightSet(entity.getEntropyWeights());
        WeightSet compositeWeights = parseWeightSet(entity.getCompositeWeights());

        return DNAProfileResponse.builder()
                .id(entity.getId())
                .userId(entity.getUser().getId())
                .sessionId(entity.getSession().getId())
                .baselineProfileId(entity.getBaselineProfile() != null
                        ? entity.getBaselineProfile().getId() : null)
                .dnaVector(vector)
                .cosineSimilarity(entity.getCosineSimilarity().doubleValue())
                .driftScore(entity.getDriftScore().doubleValue())
                .driftRegime(entity.getDriftRegime())
                .ahpWeights(ahpWeights)
                .entropyWeights(entropyWeights)
                .compositeWeights(compositeWeights)
                .sampleCount(entity.getSampleCount())
                .algorithmVersion(entity.getAlgorithmVersion())
                .computedAt(entity.getComputedAt())
                .build();
    }

    /**
     * Parse a WeightSet from JSON string.
     */
    private WeightSet parseWeightSet(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WeightSet.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse weight set JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Compute Euclidean distance from a persisted entity.
     */
    private double computeEuclideanFromEntity(DigitalDNA entity) {
        DNAVectorResponse current = parseVectorFromJson(entity.getCombinedVector());
        if (current == null || entity.getBaselineProfile() == null) {
            return 0.0;
        }
        DNAVectorResponse baseline = parseVectorFromJson(
                entity.getBaselineProfile().getCombinedVector());
        if (baseline == null) {
            return 0.0;
        }
        WeightSet weights = parseWeightSet(entity.getCompositeWeights());
        if (weights == null) {
            weights = calculator.getAHPWeights(); // Fallback
        }
        return calculator.computeEuclideanDistance(current, baseline, weights);
    }

    /**
     * Convert double to BigDecimal with specified scale.
     */
    private BigDecimal toBigDecimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
