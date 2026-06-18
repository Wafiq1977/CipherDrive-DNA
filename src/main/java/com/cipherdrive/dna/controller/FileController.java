package com.cipherdrive.dna.controller;

import com.cipherdrive.dna.dto.FileMetadataResponse;
import com.cipherdrive.dna.dto.FileMetadataResponse.StorageQuota;
import com.cipherdrive.dna.security.JwtAuthenticationFilter.CipherDriveAuthDetails;
import com.cipherdrive.dna.service.storage.FileMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * File Storage REST Controller for CipherDrive-DNA.
 *
 * Endpoints:
 *   POST   /api/files/upload          — Upload file (multipart/form-data)
 *   GET    /api/files                  — List all files for current user
 *   GET    /api/files/root             — List root-level files
 *   GET    /api/files/folder/{id}      — List files in a folder
 *   GET    /api/files/{id}/download    — Download file
 *   DELETE /api/files/{id}             — Soft-delete file
 *   POST   /api/files/{id}/restore     — Restore soft-deleted file
 *   GET    /api/files/search?keyword=  — Search files by name
 *   GET    /api/files/quota            — Get storage quota info
 *
 * Security:
 *   - All endpoints require ROLE_USER or ROLE_ADMIN
 *   - File ownership is verified on every operation (no cross-user access)
 *   - Files are encrypted in transit and at rest (DIPL layer)
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileMetadataService fileMetadataService;

    // ── UPLOAD ──

    /**
     * Upload a file to CipherDrive-DNA cloud storage.
     *
     * Content-Type: multipart/form-data
     * Form fields:
     *   - file: the file blob (required)
     *   - parentFolderId: optional parent folder ID
     */
    @PostMapping("/upload")
    public ResponseEntity<FileMetadataResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "parentFolderId", required = false) Long parentFolderId) {

        Long userId = getAuthDetails().userId();
        log.info("File upload: userId={}, name={}, size={} bytes, parentFolder={}",
                userId, file.getOriginalFilename(), file.getSize(), parentFolderId);

        FileMetadataResponse response = fileMetadataService.uploadFile(userId, file, parentFolderId);
        return ResponseEntity.ok(response);
    }

    // ── DOWNLOAD ──

    /**
     * Download a file by ID.
     * Returns the file as a streaming response with Content-Disposition header.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable Long id) {
        Long userId = getAuthDetails().userId();

        var context = fileMetadataService.downloadFile(id, userId);

        String encodedFilename = URLEncoder.encode(context.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(context.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .contentLength(context.fileSize())
                .body(new InputStreamResource(context.inputStream()));
    }

    // ── LISTING ──

    /**
     * List all active files for the current user.
     */
    @GetMapping
    public ResponseEntity<List<FileMetadataResponse>> listFiles() {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(fileMetadataService.listFiles(userId));
    }

    /**
     * List root-level files (no parent folder).
     */
    @GetMapping("/root")
    public ResponseEntity<List<FileMetadataResponse>> listRootFiles() {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(fileMetadataService.listRootFiles(userId));
    }

    /**
     * List files in a specific folder.
     */
    @GetMapping("/folder/{folderId}")
    public ResponseEntity<List<FileMetadataResponse>> listFolder(@PathVariable Long folderId) {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(fileMetadataService.listFolder(userId, folderId));
    }

    // ── SEARCH ──

    /**
     * Search files by name keyword.
     */
    @GetMapping("/search")
    public ResponseEntity<List<FileMetadataResponse>> searchFiles(
            @RequestParam("keyword") String keyword) {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(fileMetadataService.searchFiles(userId, keyword));
    }

    // ── DELETE ──

    /**
     * Soft-delete a file (move to trash, recoverable for 30 days).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteFile(@PathVariable Long id) {
        Long userId = getAuthDetails().userId();
        fileMetadataService.softDeleteFile(id, userId);
        return ResponseEntity.ok(Map.of(
                "message", "File moved to trash",
                "fileId", id.toString(),
                "retentionDays", "30"
        ));
    }

    // ── RESTORE ──

    /**
     * Restore a soft-deleted file (within retention period).
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<FileMetadataResponse> restoreFile(@PathVariable Long id) {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(fileMetadataService.restoreFile(id, userId));
    }

    // ── QUOTA ──

    /**
     * Get storage quota information for the current user.
     */
    @GetMapping("/quota")
    public ResponseEntity<StorageQuota> getStorageQuota() {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(fileMetadataService.getStorageQuota(userId));
    }

    // ── Internal ──

    private CipherDriveAuthDetails getAuthDetails() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof CipherDriveAuthDetails details) {
            return details;
        }
        throw new IllegalStateException("No authentication details found in SecurityContext");
    }
}
