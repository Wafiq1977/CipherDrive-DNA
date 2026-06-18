package com.cipherdrive.dna.service.storage;

import com.cipherdrive.dna.exception.StorageException;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * MinIO Storage Service for CipherDrive-DNA.
 *
 * Handles all direct object storage operations against MinIO:
 *   - putObject: Upload encrypted file blob to MinIO
 *   - getObject: Download encrypted file blob from MinIO
 *   - removeObject: Delete file blob from MinIO
 *   - objectExists: Check if an object key exists in the bucket
 *
 * Object Key Convention:
 *   {userId}/{uuid}/{originalFilename}
 *
 *   Example: 42/a1b2c3d4-e5f6-7890-abcd-ef1234567890/report.pdf
 *
 * Security Notes:
 *   - Files are encrypted BEFORE upload (AES-256-GCM via DIPL layer)
 *   - MinIO never sees plaintext file content
 *   - Object keys include UUID to prevent path traversal attacks
 *   - Each user's files are isolated by userId prefix (virtual tenancy)
 *   - Pre-signed URLs are NOT used — all access goes through the backend
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucketName;

    @Value("${cipherdrive.storage.max-file-size-bytes:5368709120}")
    private long maxFileSizeBytes;

    // ── UPLOAD ──

    /**
     * Upload a file to MinIO.
     *
     * @param objectKey MinIO object key: {userId}/{uuid}/{filename}
     * @param file      MultipartFile from client
     * @param contentType MIME type of the file
     * @return objectKey for database storage
     */
    public String uploadFile(String objectKey, MultipartFile file, String contentType) {
        try {
            log.debug("Uploading to MinIO: bucket={}, key={}, size={}, contentType={}",
                    bucketName, objectKey, file.getSize(), contentType);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(contentType)
                            .build()
            );

            log.info("File uploaded to MinIO: key={}, size={} bytes", objectKey, file.getSize());
            return objectKey;

        } catch (Exception e) {
            log.error("MinIO upload failed: key={}, error={}", objectKey, e.getMessage(), e);
            throw StorageException.uploadFailed(objectKey, e);
        }
    }

    /**
     * Upload file from InputStream (used for server-side encryption pipeline).
     */
    public String uploadFile(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            log.debug("Uploading stream to MinIO: bucket={}, key={}, size={}", bucketName, objectKey, size);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build()
            );

            log.info("Stream uploaded to MinIO: key={}, size={} bytes", objectKey, size);
            return objectKey;

        } catch (Exception e) {
            log.error("MinIO stream upload failed: key={}, error={}", objectKey, e.getMessage(), e);
            throw StorageException.uploadFailed(objectKey, e);
        }
    }

    // ── DOWNLOAD ──

    /**
     * Download a file from MinIO.
     *
     * @param objectKey MinIO object key
     * @return InputStream of the file content (caller MUST close this stream!)
     */
    public InputStream downloadFile(String objectKey) {
        try {
            log.debug("Downloading from MinIO: bucket={}, key={}", bucketName, objectKey);

            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );

            log.info("File downloaded from MinIO: key={}", objectKey);
            return stream;

        } catch (Exception e) {
            log.error("MinIO download failed: key={}, error={}", objectKey, e.getMessage(), e);
            throw StorageException.downloadFailed(objectKey, e);
        }
    }

    // ── DELETE ──

    /**
     * Delete a file from MinIO (permanent — cannot be undone).
     * Used only after retention period expires for soft-deleted files.
     *
     * @param objectKey MinIO object key
     */
    public void deleteFile(String objectKey) {
        try {
            log.debug("Deleting from MinIO: bucket={}, key={}", bucketName, objectKey);

            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );

            log.info("File deleted from MinIO: key={}", objectKey);

        } catch (Exception e) {
            log.error("MinIO delete failed: key={}, error={}", objectKey, e.getMessage(), e);
            throw StorageException.deleteFailed(objectKey, e);
        }
    }

    // ── EXISTS CHECK ──

    /**
     * Check if an object exists in MinIO.
     * Uses statObject — throws exception if not found.
     */
    public boolean objectExists(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── OBJECT KEY GENERATION ──

    /**
     * Generate a unique MinIO object key.
     * Format: {userId}/{uuid}/{originalFilename}
     *
     * The UUID prevents:
     *   - Path traversal attacks (../../../etc/passwd)
     *   - Filename collisions between uploads
     *   - Predictable object paths for unauthorized access
     */
    public String generateObjectKey(Long userId, String originalFilename) {
        String uuid = java.util.UUID.randomUUID().toString();
        // Sanitize filename — remove path separators
        String safeName = originalFilename
                .replace("/", "_")
                .replace("\\", "_")
                .replace("..", "_");
        return String.format("%d/%s/%s", userId, uuid, safeName);
    }

    // ── HEALTH CHECK ──

    /**
     * Check MinIO connectivity by listing bucket.
     * Used by health endpoint.
     */
    public boolean isHealthy() {
        try {
            minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketName)
                            .build()
            );
            return true;
        } catch (Exception e) {
            log.warn("MinIO health check failed: {}", e.getMessage());
            return false;
        }
    }
}
