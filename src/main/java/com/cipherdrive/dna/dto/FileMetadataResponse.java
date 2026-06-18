package com.cipherdrive.dna.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for file metadata response — returned after upload or file listing.
 * Never exposes internal MinIO path or encryption key references.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileMetadataResponse {

    private Long id;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private Boolean isEncrypted;
    private Integer version;
    private Long parentFolderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Storage quota info returned alongside file metadata.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StorageQuota {
        private long usedBytes;
        private long maxBytes;
        private int fileCount;
        private double usagePercent;
    }
}
