package com.cipherdrive.dna.repository;

import com.cipherdrive.dna.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository for FileEntity — file metadata operations.
 * Actual file blobs are stored in MinIO, this manages MySQL metadata only.
 */
@Repository
public interface FileRepository extends JpaRepository<FileEntity, Long> {

    /**
     * Find all active (non-deleted) files for a user, newest first.
     * Used by: file browser UI.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.isDeleted = false ORDER BY f.createdAt DESC")
    List<FileEntity> findActiveByUserId(@Param("userId") Long userId);

    /**
     * Find all files in a specific folder for a user.
     * Used by: folder navigation.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.parentFolder.id = :folderId AND f.isDeleted = false ORDER BY f.fileName ASC")
    List<FileEntity> findByUserIdAndParentFolderId(@Param("userId") Long userId, @Param("folderId") Long folderId);

    /**
     * Find root-level files (no parent folder) for a user.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.parentFolder IS NULL AND f.isDeleted = false ORDER BY f.fileName ASC")
    List<FileEntity> findRootFilesByUserId(@Param("userId") Long userId);

    /**
     * Find a specific active file by ID and user — prevents users from accessing other users' files.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.id = :fileId AND f.user.id = :userId AND f.isDeleted = false")
    Optional<FileEntity> findActiveByIdAndUserId(@Param("fileId") Long fileId, @Param("userId") Long userId);

    /**
     * Find file by checksum for deduplication check.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.checksumSha256 = :checksum AND f.user.id = :userId AND f.isDeleted = false LIMIT 1")
    Optional<FileEntity> findByChecksumAndUserId(@Param("checksum") String checksum, @Param("userId") Long userId);

    /**
     * Find all soft-deleted files past retention period — for permanent deletion cron.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.isDeleted = true AND f.deletedAt < :cutoff")
    List<FileEntity> findExpiredDeletedFiles(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count active files for a user — for storage quota check.
     */
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.user.id = :userId AND f.isDeleted = false")
    long countActiveByUserId(@Param("userId") Long userId);

    /**
     * Calculate total storage used by a user in bytes.
     */
    @Query("SELECT COALESCE(SUM(f.fileSize), 0) FROM FileEntity f WHERE f.user.id = :userId AND f.isDeleted = false")
    long totalStorageBytesByUserId(@Param("userId") Long userId);

    /**
     * Soft-delete all files for a user — cascading from user deletion.
     */
    @Modifying
    @Query("UPDATE FileEntity f SET f.isDeleted = true, f.deletedAt = CURRENT_TIMESTAMP WHERE f.user.id = :userId AND f.isDeleted = false")
    void softDeleteAllByUserId(@Param("userId") Long userId);

    /**
     * Search files by name pattern for a user.
     */
    @Query("SELECT f FROM FileEntity f WHERE f.user.id = :userId AND f.isDeleted = false AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY f.fileName ASC")
    List<FileEntity> searchByFileName(@Param("userId") Long userId, @Param("keyword") String keyword);
}
