package com.cipherdrive.dna.config.minio;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * MinIO Bucket Initializer — runs on application startup.
 *
 * Ensures the cipherdrive-files bucket exists before any
 * storage operations are attempted. This is idempotent —
 * safe to run multiple times without error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioBucketInitializer implements CommandLineRunner {

    private final MinioClient minioClient;
    private final String minioBucketName;

    @Override
    public void run(String... args) throws Exception {
        try {
            boolean bucketExists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(minioBucketName)
                            .build()
            );

            if (bucketExists) {
                log.info("MinIO bucket '{}' already exists — OK", minioBucketName);
            } else {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(minioBucketName)
                                .build()
                );
                log.info("MinIO bucket '{}' created successfully", minioBucketName);
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket '{}': {}", minioBucketName, e.getMessage());
            log.warn("MinIO may not be running. File storage operations will fail until MinIO is available.");
        }
    }
}
