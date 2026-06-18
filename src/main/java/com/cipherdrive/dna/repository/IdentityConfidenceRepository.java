package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.IdentityConfidence;
import com.cipherdrive.dna.entity.IdentityConfidence.TrustLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository for IdentityConfidence — ICS (Identity Confidence Score) snapshots.
 *
 * Query patterns optimized for:
 *   1. Latest ICS retrieval (real-time access decisions)
 *   2. ICS history (trust trend analysis for TEM)
 *   3. Threshold monitoring (below-threshold user detection)
 *   4. Challenge-triggered user identification (security alert feed)
 */
@Repository
public interface IdentityConfidenceRepository extends JpaRepository<IdentityConfidence, Long> {

    /**
     * Get the LATEST ICS snapshot for a user.
     * PRIMARY query for real-time access decisions.
     * Uses index: ix_ics_user_computed
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.user.id = :userId " +
            "ORDER BY ic.computedAt DESC LIMIT 1")
    Optional<IdentityConfidence> findLatestByUserId(@Param("userId") Long userId);

    /**
     * Get the LATEST ICS snapshot for a session.
     * Used by: session-scoped access decisions.
     * Uses index: ix_ics_session_id
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.session.id = :sessionId " +
            "ORDER BY ic.computedAt DESC LIMIT 1")
    Optional<IdentityConfidence> findLatestBySessionId(@Param("sessionId") Long sessionId);

    /**
     * Get ICS history for a user — trust trend visualization.
     * Used by: TEM (Trust Evolution Model) velocity computation.
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.user.id = :userId " +
            "ORDER BY ic.computedAt DESC LIMIT :limit")
    List<IdentityConfidence> findRecentByUserId(@Param("userId") Long userId,
                                                 @Param("limit") int limit);

    /**
     * Get ICS snapshots for a user within a time window — TEM parameter estimation.
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.user.id = :userId " +
            "AND ic.computedAt BETWEEN :start AND :end ORDER BY ic.computedAt ASC")
    List<IdentityConfidence> findByUserIdBetween(@Param("userId") Long userId,
                                                   @Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    /**
     * Find all users below the ICS threshold — security monitoring.
     * Uses index: ix_ics_below_threshold
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.isBelowThreshold = true " +
            "AND ic.computedAt > :since ORDER BY ic.icsScore ASC")
    List<IdentityConfidence> findBelowThresholdSince(@Param("since") LocalDateTime since);

    /**
     * Find all users with challenge triggered — security alert feed.
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.challengeTriggered = true " +
            "AND ic.computedAt > :since ORDER BY ic.computedAt DESC")
    List<IdentityConfidence> findChallengedSince(@Param("since") LocalDateTime since);

    /**
     * Count ICS snapshots by trust level — admin dashboard analytics.
     * Uses index: ix_ics_trust_level
     */
    @Query("SELECT COUNT(ic) FROM IdentityConfidence ic WHERE ic.trustLevel = :level " +
            "AND ic.computedAt > :since")
    long countByTrustLevelSince(@Param("level") TrustLevel level,
                                 @Param("since") LocalDateTime since);

    /**
     * Get average ICS score for a user in a time window.
     * Used by: TEM (Trust Evolution Model) mean estimation.
     */
    @Query("SELECT AVG(ic.icsScore) FROM IdentityConfidence ic WHERE ic.user.id = :userId " +
            "AND ic.computedAt BETWEEN :start AND :end")
    Optional<Double> avgScoreByUserIdBetween(@Param("userId") Long userId,
                                               @Param("start") LocalDateTime start,
                                               @Param("end") LocalDateTime end);

    /**
     * Get ICS snapshots by DNA profile — per-DNA ICS correlation.
     * Uses index: ix_ics_dna_profile
     */
    @Query("SELECT ic FROM IdentityConfidence ic WHERE ic.dnaProfile.id = :dnaProfileId " +
            "ORDER BY ic.computedAt DESC")
    List<IdentityConfidence> findByDnaProfileId(@Param("dnaProfileId") Long dnaProfileId);

    /**
     * Count total ICS snapshots for a user — computation history depth.
     */
    @Query("SELECT COUNT(ic) FROM IdentityConfidence ic WHERE ic.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Get consecutive_verified count for a user — recovery mechanism state.
     */
    @Query("SELECT ic.consecutiveVerified FROM IdentityConfidence ic " +
            "WHERE ic.user.id = :userId ORDER BY ic.computedAt DESC LIMIT 1")
    Optional<Integer> findLatestConsecutiveVerified(@Param("userId") Long userId);
}
