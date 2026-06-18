package com.cipherdrive.dna.service.ics;

import com.cipherdrive.dna.dto.ics.ICSResponse;
import com.cipherdrive.dna.dto.ics.ICSResponse.ICSThreshold;
import com.cipherdrive.dna.entity.*;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import com.cipherdrive.dna.entity.IdentityConfidence.TrustLevel;
import com.cipherdrive.dna.repository.BehaviorLogRepository;
import com.cipherdrive.dna.repository.DigitalDNARepository;
import com.cipherdrive.dna.repository.IdentityConfidenceRepository;
import com.cipherdrive.dna.repository.SessionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import com.cipherdrive.dna.service.dna.DigitalDNACalculator;
import com.cipherdrive.dna.dto.dna.DNAVectorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Identity Confidence Service — ICS Engine Orchestration Layer.
 *
 * ══════════════════════════════════════════════════════════════════
 *  ICS ENGINE PIPELINE
 * ══════════════════════════════════════════════════════════════════
 *
 *  The ICS Engine computes and persists Identity Confidence Scores
 *  by integrating signals from:
 *    1. Digital DNA Engine (behavioral similarity + drift)
 *    2. Device fingerprint (session device data)
 *    3. Temporal patterns (login timing, session validity)
 *    4. Context signals (IP address, network context)
 *
 *  Full Pipeline:
 *    Step 1: Load latest DNA profile → extract similarity & drift
 *    Step 2: Compute Behavioral Score (B) from DNA similarity
 *    Step 3: Compute Device Trust Score (D) from fingerprint
 *    Step 4: Compute Temporal Score (T) from session timing
 *    Step 5: Compute Context Score (C) from IP/network
 *    Step 6: Weighted Fusion → pre_decay = B*0.35 + D*0.20 + T*0.25 + C*0.20
 *    Step 7: Temporal Decay → post_decay = pre_decay * exp(-lambda * dt)
 *    Step 8: Recovery → ics = post_decay + recovery_rate * consecutive
 *    Step 9: Classification → VERY_HIGH / HIGH / MODERATE / LOW / CRITICAL
 *    Step 10: Persist IdentityConfidence entity
 *
 * ══════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityConfidenceService {

    private final ICSCalculator icsCalculator;
    private final DigitalDNACalculator dnaCalculator;
    private final IdentityConfidenceRepository icsRepository;
    private final DigitalDNARepository digitalDNARepository;
    private final SessionRepository sessionRepository;
    private final BehaviorLogRepository behaviorLogRepository;
    private final UserRepository userRepository;

    /** Algorithm version identifier */
    private static final String ALGORITHM_VERSION = "2.0";

    // ══════════════════════════════════════════════════════════════
    //  FULL ICS COMPUTATION PIPELINE
    // ══════════════════════════════════════════════════════════════

    /**
     * Execute the full ICS computation pipeline for a user.
     *
     * This is the PRIMARY entry point called by:
     *   - DigitalDNAScheduler (after DNA recalculation)
     *   - ICS Controller (on-demand computation)
     *   - BehaviorLogInterceptor (event-triggered incremental update)
     *
     * @param userId user whose ICS to compute
     * @return computed and persisted ICS response
     */
    @Transactional
    public ICSResponse computeAndPersistICS(Long userId) {
        long startTime = System.currentTimeMillis();
        log.info("Starting ICS Engine pipeline for userId={}", userId);

        // ── Get active session ──
        Session activeSession = getActiveSession(userId);

        // ── Get latest DNA profile ──
        Optional<DigitalDNA> dnaOpt = digitalDNARepository.findLatestByUserId(userId);
        boolean hasBaseline = digitalDNARepository.findBaselineByUserId(userId).isPresent();

        // ── Step 1: Extract DNA signals ──
        double cosineSimilarity = 1.0;
        DriftRegime driftRegime = DriftRegime.NORMAL;
        double deviceConsistency = 1.0;
        Long dnaProfileId = null;
        DigitalDNA dnaProfile = null;

        if (dnaOpt.isPresent()) {
            dnaProfile = dnaOpt.get();
            dnaProfileId = dnaProfile.getId();
            cosineSimilarity = dnaProfile.getCosineSimilarity().doubleValue();
            driftRegime = dnaProfile.getDriftRegime();

            // Extract device consistency from DNA vector
            DNAVectorResponse vector = parseDNAVector(dnaProfile.getCombinedVector());
            if (vector != null) {
                deviceConsistency = vector.getDeviceConsistency();
            }
        }

        // ── Step 2: Compute Behavioral Score (B) ──
        double behavioralScore = icsCalculator.computeBehavioralScore(
                cosineSimilarity, driftRegime, hasBaseline);

        // ── Step 3: Compute Device Trust Score (D) ──
        boolean isRecognizedDevice = isRecognizedDevice(activeSession, userId);
        boolean isBot = isBotUserAgent(activeSession);
        boolean isFirstSession = isFirstSession(userId);
        double deviceScore = icsCalculator.computeDeviceScore(
                deviceConsistency, isRecognizedDevice, isBot, isFirstSession);

        // ── Step 4: Compute Temporal Score (T) ──
        boolean isTypicalLoginHour = isTypicalLoginHour(userId);
        double temporalScore = icsCalculator.computeTemporalScore(
                activeSession, isTypicalLoginHour, isFirstSession);

        // ── Step 5: Compute Context Score (C) ──
        String currentIp = activeSession != null ? activeSession.getIpAddress() : null;
        String previousIp = getPreviousSessionIp(userId);
        boolean isVpnOrTor = isVpnOrTorIp(currentIp);
        double contextScore = icsCalculator.computeContextScore(
                currentIp, previousIp, isVpnOrTor, isFirstSession);

        // ── Step 6: Weighted Fusion ──
        double preDecayScore = icsCalculator.computePreDecayScore(
                behavioralScore, deviceScore, temporalScore, contextScore);

        // ── Step 7: Temporal Decay ──
        double deltaTHours = computeHoursSinceLastEvent(userId);
        double decayLambda = 0.05;
        double decayFactor = icsCalculator.computeDecayFactor(deltaTHours, decayLambda);
        double postDecayScore = icsCalculator.applyTemporalDecay(preDecayScore, decayFactor);

        // ── Step 8: Recovery Mechanism ──
        int consecutiveVerified = getConsecutiveVerified(userId);
        double recoveryRate = 2.0;
        double recoveryBonus = icsCalculator.computeRecoveryBonus(consecutiveVerified, recoveryRate);

        // ── Step 9: Final ICS Score ──
        double icsScore = clamp(postDecayScore + recoveryBonus, 0.0, 100.0);

        // Classification
        ICSThreshold threshold = icsCalculator.classifyThreshold(icsScore);
        TrustLevel trustLevel = icsCalculator.classifyTrustLevel(icsScore);
        boolean belowThreshold = icsCalculator.isBelowThreshold(icsScore);
        boolean challengeTriggered = icsCalculator.shouldTriggerChallenge(icsScore);

        // Update consecutive verified counter
        if (icsScore >= 70.0) {
            consecutiveVerified = Math.min(consecutiveVerified + 1, 10);
        } else {
            consecutiveVerified = 0; // Reset on low ICS
        }

        long computationTime = System.currentTimeMillis() - startTime;

        // ── Step 10: Persist IdentityConfidence Entity ──
        User user = userRepository.getReferenceById(userId);
        Session persistSession = activeSession != null ? activeSession : getAnySession(userId);
        DigitalDNA persistDna = dnaProfile;
        if (persistDna == null) {
            // Create a minimal DNA reference if none exists
            persistDna = digitalDNARepository.findLatestByUserId(userId).orElse(null);
        }

        IdentityConfidence icsEntity = IdentityConfidence.builder()
                .user(user)
                .session(persistSession)
                .dnaProfile(persistDna)
                .icsScore(toBD(icsScore, 2))
                .behavioralScore(toBD(behavioralScore, 2))
                .deviceScore(toBD(deviceScore, 2))
                .temporalScore(toBD(temporalScore, 2))
                .contextScore(toBD(contextScore, 2))
                .decayFactor(toBD(decayFactor, 6))
                .decayLambda(toBD(decayLambda, 4))
                .timeSinceLastEvent(toBD(deltaTHours, 2))
                .preDecayScore(toBD(preDecayScore, 2))
                .postDecayScore(toBD(postDecayScore, 2))
                .recoveryRate(toBD(recoveryRate, 4))
                .consecutiveVerified(consecutiveVerified)
                .trustLevel(trustLevel)
                .isBelowThreshold(belowThreshold)
                .challengeTriggered(challengeTriggered)
                .computationTimeMs((int) computationTime)
                .algorithmVersion(ALGORITHM_VERSION)
                .build();

        icsRepository.save(icsEntity);

        log.info("ICS Engine pipeline completed: userId={}, ics={:.2f}, threshold={}, " +
                        "trust={}, B={:.2f}, D={:.2f}, T={:.2f}, C={:.2f}, time={}ms",
                userId, icsScore, threshold, trustLevel, behavioralScore,
                deviceScore, temporalScore, contextScore, computationTime);

        // ── Build Response ──
        return ICSResponse.builder()
                .id(icsEntity.getId())
                .userId(userId)
                .sessionId(persistSession != null ? persistSession.getId() : null)
                .dnaProfileId(dnaProfileId)
                .icsScore(icsScore)
                .behavioralScore(behavioralScore)
                .deviceScore(deviceScore)
                .temporalScore(temporalScore)
                .contextScore(contextScore)
                .decayFactor(decayFactor)
                .decayLambda(decayLambda)
                .timeSinceLastEventHours(deltaTHours)
                .preDecayScore(preDecayScore)
                .postDecayScore(postDecayScore)
                .recoveryRate(recoveryRate)
                .consecutiveVerified(consecutiveVerified)
                .recoveryBonus(recoveryBonus)
                .trustLevel(trustLevel)
                .threshold(threshold)
                .belowThreshold(belowThreshold)
                .challengeTriggered(challengeTriggered)
                .computationTimeMs((int) computationTime)
                .algorithmVersion(ALGORITHM_VERSION)
                .computedAt(icsEntity.getComputedAt())
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    //  ICS RETRIEVAL
    // ══════════════════════════════════════════════════════════════

    /**
     * Get the latest ICS score for a user.
     */
    @Transactional(readOnly = true)
    public Optional<ICSResponse> getLatestICS(Long userId) {
        return icsRepository.findLatestByUserId(userId)
                .map(this::toResponse);
    }

    /**
     * Get ICS history for a user.
     */
    @Transactional(readOnly = true)
    public List<ICSResponse> getICSHistory(Long userId, int limit) {
        return icsRepository.findRecentByUserId(userId, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get all users below the ICS threshold — security monitoring.
     */
    @Transactional(readOnly = true)
    public List<ICSResponse> getBelowThresholdUsers(int hours) {
        return icsRepository.findBelowThresholdSince(
                        LocalDateTime.now().minusHours(hours)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get all users with challenges triggered — security alert feed.
     */
    @Transactional(readOnly = true)
    public List<ICSResponse> getChallengedUsers(int hours) {
        return icsRepository.findChallengedSince(
                        LocalDateTime.now().minusHours(hours)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Quick check: is the user's ICS score above the given threshold?
     */
    @Transactional(readOnly = true)
    public boolean isUserAboveThreshold(Long userId, double thresholdScore) {
        return icsRepository.findLatestByUserId(userId)
                .map(ic -> ic.getIcsScore().doubleValue() >= thresholdScore)
                .orElse(false);
    }

    /**
     * Get current ICS score for a user (lightweight).
     */
    @Transactional(readOnly = true)
    public double getCurrentICSScore(Long userId) {
        return icsRepository.findLatestByUserId(userId)
                .map(ic -> ic.getIcsScore().doubleValue())
                .orElse(50.0); // Default moderate score if no computation yet
    }

    /**
     * Get current ICS threshold for a user.
     */
    @Transactional(readOnly = true)
    public ICSThreshold getCurrentThreshold(Long userId) {
        return icsRepository.findLatestByUserId(userId)
                .map(ic -> ICSThreshold.fromScore(ic.getIcsScore().doubleValue()))
                .orElse(ICSThreshold.MODERATE); // Default moderate if no computation yet
    }

    // ══════════════════════════════════════════════════════════════
    //  INCREMENTAL ICS UPDATE
    // ══════════════════════════════════════════════════════════════

    /**
     * Trigger incremental ICS recomputation after a significant event.
     * Rate-limited: only recomputes if last computation was > 5 minutes ago.
     */
    @Transactional
    public void triggerIncrementalUpdate(Long userId) {
        Optional<IdentityConfidence> latestOpt = icsRepository.findLatestByUserId(userId);

        if (latestOpt.isPresent()) {
            long minutesSince = Duration.between(
                    latestOpt.get().getComputedAt(), LocalDateTime.now()).toMinutes();
            if (minutesSince < 5) {
                log.debug("Skipping incremental ICS update: last computation was {} min ago", minutesSince);
                return;
            }
        }

        log.info("Triggering incremental ICS update for userId={}", userId);
        try {
            computeAndPersistICS(userId);
        } catch (Exception e) {
            log.error("Incremental ICS update failed for userId={}: {}", userId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════

    private Session getActiveSession(Long userId) {
        List<Session> active = sessionRepository.findByUserIdAndIsRevokedFalseAndExpiresAtAfter(
                userId, LocalDateTime.now());
        if (active.isEmpty()) {
            List<Session> all = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
            return all.isEmpty() ? null : all.get(0);
        }
        return active.get(0);
    }

    private Session getAnySession(Long userId) {
        List<Session> all = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    private DNAVectorResponse parseDNAVector(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, DNAVectorResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isRecognizedDevice(Session session, Long userId) {
        if (session == null || session.getDeviceFingerprint() == null) return true; // Assume recognized
        // Check if this device fingerprint has been seen before
        List<Session> recentSessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (recentSessions.size() <= 1) return true; // First or only session
        String currentFingerprint = session.getDeviceFingerprint();
        return recentSessions.stream()
                .filter(s -> !s.getId().equals(session.getId()))
                .anyMatch(s -> currentFingerprint.equals(s.getDeviceFingerprint()));
    }

    private boolean isBotUserAgent(Session session) {
        if (session == null || session.getUserAgent() == null) return false;
        String ua = session.getUserAgent().toLowerCase();
        return ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")
                || ua.contains("scraper") || ua.contains("headless");
    }

    private boolean isFirstSession(Long userId) {
        long sessionCount = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).size();
        return sessionCount <= 1;
    }

    private boolean isTypicalLoginHour(Long userId) {
        // Get the user's login hour distribution from behavior logs
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<BehaviorLog> loginEvents = behaviorLogRepository.findByUserIdAndTypeSince(
                userId, BehaviorLog.EventType.TEMPORAL, since);

        if (loginEvents.size() < 3) return true; // Not enough data → assume typical

        int currentHour = LocalDateTime.now().getHour();
        // Check if current hour is within the user's top 4 most frequent login hours
        int[] hourCounts = new int[24];
        for (BehaviorLog event : loginEvents) {
            if ("LOGIN".equals(event.getEventSubtype())) {
                int hour = event.getEventTimestamp().getHour();
                hourCounts[hour]++;
            }
        }

        // Find the top 4 most frequent hours
        int threshold = 0;
        java.util.PriorityQueue<Integer> topHours = new java.util.PriorityQueue<>();
        for (int count : hourCounts) {
            topHours.offer(count);
            if (topHours.size() > 4) topHours.poll();
        }
        while (!topHours.isEmpty()) threshold = topHours.poll();

        return hourCounts[currentHour] >= threshold;
    }

    private String getPreviousSessionIp(Long userId) {
        List<Session> sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (sessions.size() < 2) return null;
        // Return IP from second-most-recent session
        return sessions.get(1).getIpAddress();
    }

    private boolean isVpnOrTorIp(String ip) {
        if (ip == null) return false;
        // Basic VPN/Tor detection — in production, use a GeoIP database
        return false;
    }

    private double computeHoursSinceLastEvent(Long userId) {
        LocalDateTime lastEvent = behaviorLogRepository.findLastEventTimestamp(userId);
        if (lastEvent == null) return 24.0; // No events → assume 24 hours (significant decay)
        return Duration.between(lastEvent, LocalDateTime.now()).toMinutes() / 60.0;
    }

    private int getConsecutiveVerified(Long userId) {
        return icsRepository.findLatestConsecutiveVerified(userId).orElse(0);
    }

    private ICSResponse toResponse(IdentityConfidence entity) {
        return ICSResponse.builder()
                .id(entity.getId())
                .userId(entity.getUser().getId())
                .sessionId(entity.getSession().getId())
                .dnaProfileId(entity.getDnaProfile().getId())
                .icsScore(entity.getIcsScore().doubleValue())
                .behavioralScore(entity.getBehavioralScore().doubleValue())
                .deviceScore(entity.getDeviceScore().doubleValue())
                .temporalScore(entity.getTemporalScore().doubleValue())
                .contextScore(entity.getContextScore().doubleValue())
                .decayFactor(entity.getDecayFactor().doubleValue())
                .decayLambda(entity.getDecayLambda().doubleValue())
                .timeSinceLastEventHours(entity.getTimeSinceLastEvent().doubleValue())
                .preDecayScore(entity.getPreDecayScore().doubleValue())
                .postDecayScore(entity.getPostDecayScore().doubleValue())
                .recoveryRate(entity.getRecoveryRate().doubleValue())
                .consecutiveVerified(entity.getConsecutiveVerified())
                .recoveryBonus(icsCalculator.computeRecoveryBonus(
                        entity.getConsecutiveVerified(), entity.getRecoveryRate().doubleValue()))
                .trustLevel(entity.getTrustLevel())
                .threshold(ICSThreshold.fromScore(entity.getIcsScore().doubleValue()))
                .belowThreshold(entity.getIsBelowThreshold())
                .challengeTriggered(entity.getChallengeTriggered())
                .computationTimeMs(entity.getComputationTimeMs())
                .algorithmVersion(entity.getAlgorithmVersion())
                .computedAt(entity.getComputedAt())
                .build();
    }

    private BigDecimal toBD(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
