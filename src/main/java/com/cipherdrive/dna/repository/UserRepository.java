package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository for User entity.
 * Provides custom lookup methods for Spring Security authentication.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by username — used during login authentication.
     * Fetches role eagerly to avoid N+1 during SecurityContext population.
     */
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.username = :username")
    Optional<User> findByUsernameWithRole(@Param("username") String username);

    /**
     * Find user by username (basic lookup without role join).
     */
    Optional<User> findByUsername(String username);

    /**
     * Find user by email — used for email uniqueness check and password reset.
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if username already exists — used during registration.
     */
    boolean existsByUsername(String username);

    /**
     * Check if email already exists — used during registration.
     */
    boolean existsByEmail(String email);

    /**
     * Find users who logged in after a given timestamp.
     * Used by DNA Scheduler to identify active users for recalculation.
     */
    List<User> findByLastLoginAtAfter(LocalDateTime since);

    /**
     * Find all enabled, non-locked users — for bulk DNA computation.
     */
    @Query("SELECT u FROM User u WHERE u.isEnabled = true AND u.isLocked = false")
    List<User> findAllActiveUsers();
}
