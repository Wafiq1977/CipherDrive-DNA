package com.cipherdrive.dna.dto.dna;

import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the DNA drift analysis result.
 *
 * Drift Computation:
 *   1. Cosine Similarity:  cos(A, B) = (A·B) / (||A|| * ||B||)
 *   2. Drift Score:        drift = 1 - cosine_similarity
 *   3. Drift Regime:       classified by drift score thresholds
 *
 * Regime Classification:
 *   ┌──────────────────────────────────────────────────┐
 *   │ Regime      │ Drift Score Range  │ Action        │
 *   ├──────────────────────────────────────────────────┤
 *   │ NORMAL      │ [0.00, 0.15)       │ None          │
 *   │ LOW_DRIFT   │ [0.15, 0.30)       │ Monitor       │
 *   │ HIGH_DRIFT  │ [0.30, 0.50)       │ Flag + ICS    │
 *   │ ANOMALY     │ [0.50, 1.00]       │ Block + MFA   │
 *   └──────────────────────────────────────────────────┘
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DNADriftResponse {

    /** Cosine similarity between current and baseline DNA vectors [0, 1] */
    private double cosineSimilarity;

    /** Drift score: 1 - cosine_similarity [0, 1] */
    private double driftScore;

    /** Euclidean distance between current and baseline vectors */
    private double euclideanDistance;

    /** Classified drift regime */
    private DriftRegime driftRegime;

    /** Per-dimension drift breakdown (which dimensions changed most) */
    private DNAVectorResponse dimensionDrift;

    /** User ID */
    private Long userId;

    /** Session ID where drift was computed */
    private Long sessionId;

    /** Baseline profile ID (reference DNA) */
    private Long baselineProfileId;

    /** Current profile ID */
    private Long currentProfileId;

    /** Number of behavior samples used for computation */
    private int sampleCount;
}
