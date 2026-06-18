package com.cipherdrive.dna.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async and Scheduling configuration for CipherDrive-DNA.
 *
 * Provides dedicated thread pools for:
 *   - behaviorLogExecutor: behavioral event logging (high throughput)
 *   - dnaEngineExecutor: DNA computation pipeline (CPU-intensive)
 *   - temEngineExecutor: Trust Evolution Model computation (CPU-intensive)
 *
 * Enables:
 *   - @Async annotation support for non-blocking operations
 *   - @Scheduled annotation support for DNA Engine and TEM periodic tasks
 *
 * Behavior logging must NEVER block the request thread.
 * With 10K users each generating ~100 events/hour = ~280 events/second,
 * the thread pool must handle this volume without queue overflow.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("behaviorLogExecutor")
    public Executor behaviorLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("behavior-log-");
        executor.setRejectedExecutionHandler((r, e) -> {
            // Log and discard — never block request thread
            org.slf4j.LoggerFactory.getLogger("BehaviorLogExecutor")
                    .warn("Behavior log queue full — event dropped");
        });
        executor.initialize();
        return executor;
    }

    @Bean("dnaEngineExecutor")
    public Executor dnaEngineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("dna-engine-");
        executor.initialize();
        return executor;
    }

    /**
     * Thread pool for Trust Evolution Model (TEM) computation.
     *
     * TEM computation involves:
     *   - OLS-MLE parameter estimation (CPU-bound matrix operations)
     *   - Euler-Maruyama solver steps
     *   - Velocity/acceleration computation over ICS time series
     *
     * Sizing rationale:
     *   - Core: 2 threads (TEM is less frequent than DNA)
     *   - Max: 6 threads (spike capacity for bulk re-estimation)
     *   - Queue: 50 (lower volume, TEM runs every 5 min)
     */
    @Bean("temEngineExecutor")
    public Executor temEngineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("tem-engine-");
        executor.setRejectedExecutionHandler((r, e) -> {
            org.slf4j.LoggerFactory.getLogger("TEMEngineExecutor")
                    .warn("TEM engine queue full — computation deferred");
        });
        executor.initialize();
        return executor;
    }
}
