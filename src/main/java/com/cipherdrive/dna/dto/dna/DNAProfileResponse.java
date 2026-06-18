package com.cipherdrive.dna.dto.dna;

import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing the complete Digital DNA profile snapshot.
 *
 * This is the FULL output of the DNA Engine pipeline:
 *   Stage 1: Event Ingestion   → BehaviorLog raw events
 *   Stage 2: Dimension Extraction → 8-dimensional vector
 *   Stage 3: Normalization     → [0,1] per dimension
 *   Stage 4: AHP Weighting     → subjective expert weights
 *   Stage 5: Entropy Weighting → objective data-driven weights
 *   Stage 6: Composite Fusion  → w_final = alpha*w_ahp + (1-alpha)*w_entropy
 *   Stage 7: Similarity & Drift → cosine similarity + drift classification
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DNAProfileResponse {

    private Long id;
    private Long userId;
    private Long sessionId;
    private Long baselineProfileId;

    // ── 8-Dimensional DNA Vector ──
    private DNAVectorResponse dnaVector;

    // ── Composite Scores ──
    private double cosineSimilarity;
    private double driftScore;
    private DriftRegime driftRegime;

    // ── Weight Configuration ──
    private WeightSet ahpWeights;
    private WeightSet entropyWeights;
    private WeightSet compositeWeights;

    // ── Metadata ──
    private int sampleCount;
    private String algorithmVersion;
    private LocalDateTime computedAt;

    /**
     * Weight set for the 8 DNA dimensions.
     * Used by AHP (subjective), Entropy (objective), and Composite (fusion).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WeightSet {
        private double loginFrequency;
        private double loginTimePattern;
        private double sessionDuration;
        private double uploadFrequency;
        private double downloadFrequency;
        private double deleteFrequency;
        private double avgFileSize;
        private double deviceConsistency;

        public double[] toArray() {
            return new double[]{
                    loginFrequency, loginTimePattern, sessionDuration,
                    uploadFrequency, downloadFrequency, deleteFrequency,
                    avgFileSize, deviceConsistency
            };
        }

        public static WeightSet fromArray(double[] w) {
            return WeightSet.builder()
                    .loginFrequency(w[0])
                    .loginTimePattern(w[1])
                    .sessionDuration(w[2])
                    .uploadFrequency(w[3])
                    .downloadFrequency(w[4])
                    .deleteFrequency(w[5])
                    .avgFileSize(w[6])
                    .deviceConsistency(w[7])
                    .build();
        }
    }
}
