package com.cipherdrive.dna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CipherDrive-DNA MVP — Spring Boot Application Entry Point.
 *
 * <p>Bootstraps the three-layer security stack:</p>
 * <ul>
 *   <li><b>Digital DNA (DNA)</b> — behavioral biometric profiling</li>
 *   <li><b>Identity Confidence Score (ICS)</b> — real-time trust scoring</li>
 *   <li><b>Trust Evolution Management (TEM)</b> — long-term trust trajectory</li>
 * </ul>
 *
 * <p>Component scanning covers {@code com.cipherdrive.dna} and all sub-packages
 * (config, controller, dto, entity, exception, interceptor, repository,
 * security, service).</p>
 */
@SpringBootApplication
public class CipherDriveDnaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CipherDriveDnaApplication.class, args);
    }
}
