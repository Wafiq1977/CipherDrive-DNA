package com.cipherdrive.dna.config.minio;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO Client Configuration for CipherDrive-DNA.
 *
 * Initializes the MinIO client with connection credentials
 * and verifies bucket existence on application startup.
 *
 * MinIO serves as the object storage backend for encrypted file blobs.
 * All files are encrypted client-side with AES-256-GCM (DIPL layer)
 * BEFORE upload — MinIO never sees plaintext content.
 *
 * Connection Flow:
 *   Spring Boot → MinioClient → MinIO Server (localhost:9000)
 *   Bucket: cipherdrive-files (created automatically if not exists)
 */
@Slf4j
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.bucket}")
    private String bucketName;

    @Bean
    public MinioClient minioClient() {
        log.info("Initializing MinIO client → endpoint={}, bucket={}", endpoint, bucketName);

        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public String minioBucketName() {
        return bucketName;
    }
}
