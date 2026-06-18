package com.cipherdrive.dna.dto.dna;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the computed 8-dimensional DNA behavioral vector.
 *
 * Each dimension is normalized to [0.0, 1.0]:
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │ Dimension            │ Formula Summary                      │
 *   ├─────────────────────────────────────────────────────────────┤
 *   │ loginFrequency       │ login_count / observation_window     │
 *   │ loginTimePattern     │ H(hour_dist) / log2(24)             │
 *   │ sessionDuration      │ avg_duration / max_duration          │
 *   │ uploadFrequency      │ upload_count / observation_window    │
 *   │ downloadFrequency    │ download_count / observation_window  │
 *   │ deleteFrequency      │ delete_count / observation_window    │
 *   │ avgFileSize          │ mean(file_size) / max_observed_size  │
 *   │ deviceConsistency    │ max(device_counts) / total_events    │
 *   └─────────────────────────────────────────────────────────────┘
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DNAVectorResponse {

    /** Login frequency: normalized login count per observation window */
    private double loginFrequency;

    /** Login time pattern: Shannon entropy of hour-of-day distribution / log2(24) */
    private double loginTimePattern;

    /** Session duration: normalized mean session duration */
    private double sessionDuration;

    /** Upload frequency: normalized upload count per observation window */
    private double uploadFrequency;

    /** Download frequency: normalized download count per observation window */
    private double downloadFrequency;

    /** Delete frequency: normalized delete count per observation window */
    private double deleteFrequency;

    /** Average file size: normalized mean file size of uploaded files */
    private double avgFileSize;

    /** Device consistency: ratio of most-used device to total events */
    private double deviceConsistency;

    /** Raw array form for vector operations */
    public double[] toArray() {
        return new double[]{
                loginFrequency, loginTimePattern, sessionDuration,
                uploadFrequency, downloadFrequency, deleteFrequency,
                avgFileSize, deviceConsistency
        };
    }

    /** Construct from raw array */
    public static DNAVectorResponse fromArray(double[] v) {
        return DNAVectorResponse.builder()
                .loginFrequency(v[0])
                .loginTimePattern(v[1])
                .sessionDuration(v[2])
                .uploadFrequency(v[3])
                .downloadFrequency(v[4])
                .deleteFrequency(v[5])
                .avgFileSize(v[6])
                .deviceConsistency(v[7])
                .build();
    }
}
