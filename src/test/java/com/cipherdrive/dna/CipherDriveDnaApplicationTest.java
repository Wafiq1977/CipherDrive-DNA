package com.cipherdrive.dna;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the {@link CipherDriveDnaApplication} entry point.
 *
 * <p>This test does NOT bootstrap the full Spring context (which would
 * require MySQL + MinIO). It only verifies that:</p>
 * <ul>
 *   <li>The main class exists and is loadable</li>
 *   <li>The {@code main} method is present</li>
 * </ul>
 *
 * <p>Full integration tests live in {@code ApplicationIntegrationTests}
 * and use the {@code test} profile with H2.</p>
 */
class CipherDriveDnaApplicationTest {

    @Test
    void mainClassIsLoadable() {
        Class<?> mainClass = CipherDriveDnaApplication.class;
        assertNotNull(mainClass, "CipherDriveDnaApplication class must exist");
        assertTrue(mainClass.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class),
                "Main class must be annotated with @SpringBootApplication");
    }

    @Test
    void mainMethodExists() throws NoSuchMethodException {
        // Verifies the entry point method signature without invoking it
        assertNotNull(
                CipherDriveDnaApplication.class.getMethod("main", String[].class),
                "public static void main(String[]) must be present"
        );
    }
}
