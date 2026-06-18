package com.cipherdrive.dna.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * DTO for file upload request.
 * Wraps Spring's MultipartFile with optional parent folder ID.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileUploadRequest {

    private MultipartFile file;

    /**
     * Parent folder ID — null means upload to root directory.
     */
    private Long parentFolderId;

    /**
     * Validate the upload request.
     */
    public void validate() {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        if (file.getSize() > 5368709120L) { // 5GB max
            throw new IllegalArgumentException("File size exceeds 5GB limit");
        }
        if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }
    }
}
