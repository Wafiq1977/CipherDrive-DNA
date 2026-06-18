package com.cipherdrive.dna.exception;

public class StorageException extends RuntimeException {

    private final String errorCode;

    public StorageException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public StorageException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    // ── Factory Methods ──

    public static StorageException uploadFailed(String fileName, Throwable cause) {
        return new StorageException(
                String.format("Failed to upload file: %s", fileName), "STORAGE_UPLOAD_FAILED", cause);
    }

    public static StorageException downloadFailed(String fileName, Throwable cause) {
        return new StorageException(
                String.format("Failed to download file: %s", fileName), "STORAGE_DOWNLOAD_FAILED", cause);
    }

    public static StorageException deleteFailed(String fileName, Throwable cause) {
        return new StorageException(
                String.format("Failed to delete file: %s", fileName), "STORAGE_DELETE_FAILED", cause);
    }

    public static StorageException fileNotFound(Long fileId) {
        return new StorageException(
                String.format("File not found: %d", fileId), "STORAGE_FILE_NOT_FOUND");
    }

    public static StorageException accessDenied(Long fileId, Long userId) {
        return new StorageException(
                String.format("Access denied: fileId=%d, userId=%d", fileId, userId), "STORAGE_ACCESS_DENIED");
    }

    public static StorageException storageQuotaExceeded(Long userId) {
        return new StorageException(
                String.format("Storage quota exceeded for user: %d", userId), "STORAGE_QUOTA_EXCEEDED");
    }

    public static StorageException bucketInitFailed(String bucket) {
        return new StorageException(
                String.format("Failed to initialize bucket: %s", bucket), "STORAGE_BUCKET_INIT_FAILED");
    }

    public static StorageException minioUnavailable() {
        return new StorageException("MinIO server is unavailable", "STORAGE_MINIO_UNAVAILABLE");
    }
}
