package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.BehaviorLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Repository for BehaviorLog — the HIGHEST VOLUME table in CipherDrive-DNA.
 *
 * Query patterns are optimized for the Digital DNA Engine's 7-stage pipeline:
 *   1. Fetch user events sorted by time (per-user time-series extraction)
 *   2. Fetch events by DNA dimension type (per-dimension vector extraction)
 *   3. Fetch events by session (session-scoped ICS computation)
 *   4. Analytics queries (cross-user, cross-type aggregation)
 */
@Repository
public interface BehaviorLogRepository extends JpaRepository<BehaviorLog, Long> {

    /**
     * Fetch recent events for a user sorted by timestamp DESC.
     * PRIMARY query for DNA extraction pipeline.
     * Uses index: ix_behavior_user_time
     */
    @Query("SELECT bl FROM BehaviorLog bl WHERE bl.user.id = :userId " +
            "AND bl.eventTimestamp > :since ORDER BY bl.eventTimestamp DESC")
    List<BehaviorLog> findByUserIdSince(@Param("userId") Long userId,
                                         @Param("since") LocalDateTime since);

    /**
     * Fetch recent events for a user with limit.
     * Used by DNA engine to get the latest N events for vector computation.
     */
    @Query("SELECT bl FROM BehaviorLog bl WHERE bl.user.id = :userId " +
            "ORDER BY bl.eventTimestamp DESC LIMIT :limit")
    List<BehaviorLog> findRecentByUserId(@Param("userId") Long userId,
                                          @Param("limit") int limit);

    /**
     * Fetch events by user and event type (DNA dimension).
     * Used by per-dimension DNA extraction.
     * Uses index: ix_behavior_user_type
     */
    @Query("SELECT bl FROM BehaviorLog bl WHERE bl.user.id = :userId " +
            "AND bl.eventType = :eventType AND bl.eventTimestamp > :since " +
            "ORDER BY bl.eventTimestamp DESC")
    List<BehaviorLog> findByUserIdAndTypeSince(@Param("userId") Long userId,
                                                @Param("eventType") BehaviorLog.EventType eventType,
                                                @Param("since") LocalDateTime since);

    /**
     * Fetch events by session ID.
     * Used by ICS computation at session end.
     * Uses index: ix_behavior_session_id
     */
    @Query("SELECT bl FROM BehaviorLog bl WHERE bl.session.id = :sessionId " +
            "ORDER BY bl.eventTimestamp DESC")
    List<BehaviorLog> findBySessionId(@Param("sessionId") Long sessionId);

    /**
     * Count events by type for a user in a time window.
     * Used for temporal pattern extraction (hour/day distribution).
     */
    @Query("SELECT COUNT(bl) FROM BehaviorLog bl WHERE bl.user.id = :userId " +
            "AND bl.eventType = :eventType AND bl.eventTimestamp BETWEEN :start AND :end")
    long countByUserIdAndTypeBetween(@Param("userId") Long userId,
                                      @Param("eventType") BehaviorLog.EventType eventType,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end);

    /**
     * Count total events for a user in a time window.
     * Used for activity level estimation.
     */
    @Query("SELECT COUNT(bl) FROM BehaviorLog bl WHERE bl.user.id = :userId " +
            "AND bl.eventTimestamp BETWEEN :start AND :end")
    long countByUserIdBetween(@Param("userId") Long userId,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    /**
     * Get distinct event types for a user in a time window.
     * Used to determine which DNA dimensions have data available.
     */
    @Query("SELECT DISTINCT bl.eventType FROM BehaviorLog bl WHERE bl.user.id = :userId " +
            "AND bl.eventTimestamp > :since")
    List<BehaviorLog.EventType> findDistinctEventTypesByUserId(@Param("userId") Long userId,
                                                                @Param("since") LocalDateTime since);

    /**
     * Fetch events by type across all users (analytics).
     * Uses index: ix_behavior_type_time
     */
    @Query("SELECT bl FROM BehaviorLog bl WHERE bl.eventType = :eventType " +
            "AND bl.eventTimestamp > :since ORDER BY bl.eventTimestamp DESC")
    List<BehaviorLog> findByTypeSince(@Param("eventType") BehaviorLog.EventType eventType,
                                       @Param("since") LocalDateTime since);

    /**
     * Get last event timestamp for a user.
     * Used by ICS temporal decay computation.
     */
    @Query("SELECT MAX(bl.eventTimestamp) FROM BehaviorLog bl WHERE bl.user.id = :userId")
    LocalDateTime findLastEventTimestamp(@Param("userId") Long userId);

    /**
     * Delete old events beyond retention period.
     * Used by scheduled cleanup job.
     */
    @Query("DELETE FROM BehaviorLog bl WHERE bl.eventTimestamp < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
