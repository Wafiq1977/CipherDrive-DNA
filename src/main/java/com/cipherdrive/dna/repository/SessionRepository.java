package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    /**
     * Find active (non-revoked, non-expired) session by token hash.
     * Called on EVERY API request for JWT validation — must be fast.
     */
    @Query("SELECT s FROM Session s WHERE s.sessionToken = :tokenHash AND s.isRevoked = false AND s.expiresAt > :now")
    Optional<Session> findValidSession(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /**
     * Find all active sessions for a user — used for concurrent session limit.
     */
    @Query("SELECT s FROM Session s WHERE s.user.id = :userId AND s.isRevoked = false AND s.expiresAt > :now ORDER BY s.createdAt DESC")
    List<Session> findActiveSessionsByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Revoke all sessions for a user — used during account lockout or forced logout.
     */
    @Modifying
    @Query("UPDATE Session s SET s.isRevoked = true, s.revokeReason = 'SECURITY_EVENT' WHERE s.user.id = :userId AND s.isRevoked = false")
    void revokeAllByUserId(@Param("userId") Long userId);

    /**
     * Cleanup expired sessions — called by scheduled job.
     */
    @Modifying
    @Query("DELETE FROM Session s WHERE s.expiresAt < :cutoff AND s.isRevoked = false")
    int deleteExpiredSessions(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Find sessions for a user created after a given timestamp.
     * Used by DNA Engine for session duration computation.
     */
    @Query("SELECT s FROM Session s WHERE s.user.id = :userId AND s.expiresAt > :after ORDER BY s.createdAt DESC")
    List<Session> findByUserIdAndExpiresAtAfter(@Param("userId") Long userId, @Param("after") LocalDateTime after);

    /**
     * Find active (non-revoked, non-expired) sessions for a user.
     * Used by DNA Engine to find current session.
     */
    @Query("SELECT s FROM Session s WHERE s.user.id = :userId AND s.isRevoked = false AND s.expiresAt > :now ORDER BY s.createdAt DESC")
    List<Session> findByUserIdAndIsRevokedFalseAndExpiresAtAfter(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Find all sessions for a user ordered by creation time.
     * Used by DNA Engine as fallback for session lookup.
     */
    @Query("SELECT s FROM Session s WHERE s.user.id = :userId ORDER BY s.createdAt DESC")
    List<Session> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
}
