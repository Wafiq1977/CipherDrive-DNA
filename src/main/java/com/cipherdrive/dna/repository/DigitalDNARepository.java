package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.DigitalDNA;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository for DigitalDNA — computed behavioral profile snapshots.
 *
 * Query patterns optimized for:
 *   1. Baseline retrieval (most stable profile for a user)
 *   2. Latest profile retrieval (current DNA snapshot)
 *   3. Drift regime analytics (security monitoring dashboard)
 *   4. Temporal sequence retrieval (drift trend analysis)
 */
@Repository
public interface DigitalDNARepository extends JpaRepository<DigitalDNA, Long> {

    /**
     * Get the LATEST DNA profile for a user.
     * Used by: DNA drift computation, ICS engine, real-time monitoring.
     * Uses index: ix_dna_user_computed
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.user.id = :userId " +
            "ORDER BY d.computedAt DESC LIMIT 1")
    Optional<DigitalDNA> findLatestByUserId(@Param("userId") Long userId);

    /**
     * Get the BASELINE (first established) DNA profile for a user.
     * The baseline represents the "known good" behavioral fingerprint.
     * Used by: cosine similarity & drift score computation.
     * Uses index: ix_dna_user_computed
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.user.id = :userId " +
            "AND d.baselineProfile IS NULL ORDER BY d.computedAt ASC LIMIT 1")
    Optional<DigitalDNA> findBaselineByUserId(@Param("userId") Long userId);

    /**
     * Get DNA profiles explicitly marked as baseline via self-reference.
     * Uses index: ix_dna_baseline
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.baselineProfile.id = :baselineId " +
            "ORDER BY d.computedAt DESC")
    List<DigitalDNA> findByBaselineId(@Param("baselineId") Long baselineId);

    /**
     * Get DNA profiles for a user within a time window — drift trend analysis.
     * Used by: TEM (Trust Evolution Model) drift trajectory computation.
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.user.id = :userId " +
            "AND d.computedAt BETWEEN :start AND :end ORDER BY d.computedAt ASC")
    List<DigitalDNA> findByUserIdBetween(@Param("userId") Long userId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /**
     * Get recent N DNA profiles for a user — drift trend visualization.
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.user.id = :userId " +
            "ORDER BY d.computedAt DESC LIMIT :limit")
    List<DigitalDNA> findRecentByUserId(@Param("userId") Long userId,
                                         @Param("limit") int limit);

    /**
     * Count profiles by drift regime — security monitoring dashboard.
     * Uses index: ix_dna_drift_regime
     */
    @Query("SELECT COUNT(d) FROM DigitalDNA d WHERE d.driftRegime = :regime " +
            "AND d.computedAt > :since")
    long countByDriftRegimeSince(@Param("regime") DriftRegime regime,
                                  @Param("since") LocalDateTime since);

    /**
     * Get all profiles with HIGH_DRIFT or ANOMALY regime — security alert feed.
     * Uses index: ix_dna_drift_regime
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.driftRegime IN :regimes " +
            "AND d.computedAt > :since ORDER BY d.computedAt DESC")
    List<DigitalDNA> findByDriftRegimesSince(@Param("regimes") List<DriftRegime> regimes,
                                              @Param("since") LocalDateTime since);

    /**
     * Get DNA profile by session ID — session-scoped ICS computation.
     * Uses index: ix_dna_session_id
     */
    @Query("SELECT d FROM DigitalDNA d WHERE d.session.id = :sessionId " +
            "ORDER BY d.computedAt DESC LIMIT 1")
    Optional<DigitalDNA> findLatestBySessionId(@Param("sessionId") Long sessionId);

    /**
     * Count total DNA profiles for a user — profile history depth.
     */
    @Query("SELECT COUNT(d) FROM DigitalDNA d WHERE d.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Get average cosine similarity for a user in a time window.
     * Used by: TEM stability assessment.
     */
    @Query("SELECT AVG(d.cosineSimilarity) FROM DigitalDNA d WHERE d.user.id = :userId " +
            "AND d.computedAt BETWEEN :start AND :end")
    Optional<Double> avgCosineSimilarityByUserIdBetween(@Param("userId") Long userId,
                                                         @Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end);

    /**
     * Delete DNA profiles older than retention period — data lifecycle management.
     */
    @Query("DELETE FROM DigitalDNA d WHERE d.computedAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
