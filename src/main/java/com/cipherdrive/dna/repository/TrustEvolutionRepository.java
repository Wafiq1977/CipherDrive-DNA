package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.TrustEvolution;
import com.cipherdrive.dna.entity.TrustEvolution.Regime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository for TrustEvolution — TEM (Trust Evolution Model) snapshots.
 *
 * Query patterns optimized for:
 *   1. Latest TEM snapshot retrieval (real-time trust decisions)
 *   2. Historical TEM trajectory (trust trend analysis)
 *   3. Regime transition detection (security alerting)
 *   4. OU parameter analytics (model quality assessment)
 *   5. Cross-user regime monitoring (admin dashboard)
 */
@Repository
public interface TrustEvolutionRepository extends JpaRepository<TrustEvolution, Long> {

    /**
     * Get the LATEST TEM snapshot for a user.
     * Used by: real-time trust decisions, trust trend display, ICS integration.
     * Uses index: ix_tem_user_computed
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.user.id = :userId " +
            "ORDER BY t.computedAt DESC LIMIT 1")
    Optional<TrustEvolution> findLatestByUserId(@Param("userId") Long userId);

    /**
     * Get recent N TEM snapshots for a user — trust trajectory visualization.
     * Used by: trend charts, velocity/acceleration time series.
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.user.id = :userId " +
            "ORDER BY t.computedAt DESC LIMIT :limit")
    List<TrustEvolution> findRecentByUserId(@Param("userId") Long userId,
                                             @Param("limit") int limit);

    /**
     * Get TEM snapshots for a user within a time window.
     * Used by: OU parameter re-estimation, historical trend analysis.
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.user.id = :userId " +
            "AND t.computedAt BETWEEN :start AND :end ORDER BY t.computedAt ASC")
    List<TrustEvolution> findByUserIdBetween(@Param("userId") Long userId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * Find all regime transitions for a user — detect unstable trust patterns.
     * A transition occurs when current regime differs from previous regime.
     * Uses index: ix_tem_regime_transition
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.user.id = :userId " +
            "AND t.previousRegime IS NOT NULL AND t.regime <> t.previousRegime " +
            "AND t.computedAt > :since ORDER BY t.computedAt DESC")
    List<TrustEvolution> findRegimeTransitionsByUserIdSince(@Param("userId") Long userId,
                                                             @Param("since") LocalDateTime since);

    /**
     * Get all snapshots in a specific regime — security alerting.
     * Used by: admin dashboard, security monitoring feed.
     * Uses index: ix_tem_regime
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.regime = :regime " +
            "AND t.computedAt > :since ORDER BY t.computedAt DESC")
    List<TrustEvolution> findByRegimeSince(@Param("regime") Regime regime,
                                            @Param("since") LocalDateTime since);

    /**
     * Get all DEGRADING snapshots — critical security alerts.
     * Used by: real-time security monitoring.
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.regime = 'DEGRADING' " +
            "AND t.computedAt > :since ORDER BY t.computedAt DESC")
    List<TrustEvolution> findDegradingSince(@Param("since") LocalDateTime since);

    /**
     * Get all snapshots where TDI exceeds threshold — degradation alerts.
     * Used by: proactive alerting before regime transition.
     */
    @Query("SELECT t FROM TrustEvolution t WHERE t.tdiComposite > :tdiThreshold " +
            "AND t.computedAt > :since ORDER BY t.tdiComposite DESC")
    List<TrustEvolution> findHighTDISince(@Param("tdiThreshold") double tdiThreshold,
                                           @Param("since") LocalDateTime since);

    /**
     * Count snapshots by regime — admin analytics dashboard.
     * Uses index: ix_tem_regime
     */
    @Query("SELECT COUNT(t) FROM TrustEvolution t WHERE t.regime = :regime " +
            "AND t.computedAt > :since")
    long countByRegimeSince(@Param("regime") Regime regime,
                             @Param("since") LocalDateTime since);

    /**
     * Count total TEM snapshots for a user — computation history depth.
     */
    @Query("SELECT COUNT(t) FROM TrustEvolution t WHERE t.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Get average theta for a user — mean-reversion speed analytics.
     * Used by: model quality monitoring.
     */
    @Query("SELECT AVG(t.theta) FROM TrustEvolution t WHERE t.user.id = :userId " +
            "AND t.computedAt BETWEEN :start AND :end")
    Optional<Double> avgThetaByUserIdBetween(@Param("userId") Long userId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * Get average R-squared for a user — model fit quality assessment.
     * Used by: OLS model reliability monitoring.
     */
    @Query("SELECT AVG(t.olsRSquared) FROM TrustEvolution t WHERE t.user.id = :userId " +
            "AND t.computedAt BETWEEN :start AND :end")
    Optional<Double> avgRSquaredByUserIdBetween(@Param("userId") Long userId,
                                                  @Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * Find users with consecutive DEGRADING snapshots — persistent trust loss.
     * Used by: security operations center (SOC) alerting.
     */
    @Query("SELECT DISTINCT t.user.id FROM TrustEvolution t WHERE t.regime = 'DEGRADING' " +
            "AND t.computedAt > :since")
    List<Long> findUserIdsWithDegradingSince(@Param("since") LocalDateTime since);

    /**
     * Delete stale TEM snapshots older than retention period.
     * Used by: scheduled cleanup job.
     */
    @Query("DELETE FROM TrustEvolution t WHERE t.computedAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
