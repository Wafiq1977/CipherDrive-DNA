package com.cipherdrive.dna.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cloud file storage metadata (actual blobs stored in MinIO).
 * Maps to: files table
 *
 * This entity stores ONLY metadata — file binary content resides in MinIO.
 * DIPL encryption: AES-256-GCM with Argon2id key derivation.
 */
@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "parentFolder", "childFiles"})
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_files_user_id"))
    private User user;

    // ── File Metadata ──

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", nullable = false, length = 128)
    @Builder.Default
    private String mimeType = "application/octet-stream";

    // ── MinIO Storage ──

    @Column(name = "storage_bucket", nullable = false, length = 128)
    @Builder.Default
    private String storageBucket = "cipherdrive-files";

    // ── Integrity & Encryption ──

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "encryption_key_ref", nullable = false, length = 255)
    private String encryptionKeyRef;

    @Column(name = "encryption_iv", nullable = false, length = 64)
    private String encryptionIv;

    @Column(name = "is_encrypted", nullable = false)
    @Builder.Default
    private Boolean isEncrypted = true;

    // ── Soft Delete ──

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── Versioning ──

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    // ── Folder Hierarchy (self-referencing) ──

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id", foreignKey = @ForeignKey(name = "fk_files_parent_folder"))
    private FileEntity parentFolder;

    @OneToMany(mappedBy = "parentFolder", cascade = CascadeType.MERGE, fetch = FetchType.LAZY)
    @Builder.Default
    private List<FileEntity> childFiles = new ArrayList<>();

    // ── Timestamps ──

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Helper Methods ──

    /**
     * Soft-delete this file and record the deletion timestamp.
     */
    public void softDelete() {
        this.isDeleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Restore a soft-deleted file.
     */
    public void restore() {
        this.isDeleted = false;
        this.deletedAt = null;
    }

    /**
     * Check if the retention period has expired for permanent deletion.
     * @param retentionDays number of days to retain soft-deleted files
     */
    public boolean isRetentionExpired(int retentionDays) {
        return isDeleted && deletedAt != null
                && deletedAt.plusDays(retentionDays).isBefore(LocalDateTime.now());
    }

    /**
     * Create a new version of this file.
     */
    public FileEntity createNewVersion(String newFilePath, Long newFileSize, String newChecksum) {
        return FileEntity.builder()
                .user(this.user)
                .fileName(this.fileName)
                .filePath(newFilePath)
                .fileSize(newFileSize)
                .mimeType(this.mimeType)
                .storageBucket(this.storageBucket)
                .checksumSha256(newChecksum)
                .encryptionKeyRef(this.encryptionKeyRef)
                .encryptionIv(this.encryptionIv)
                .isEncrypted(this.isEncrypted)
                .parentFolder(this.parentFolder)
                .version(this.version + 1)
                .build();
    }
}
