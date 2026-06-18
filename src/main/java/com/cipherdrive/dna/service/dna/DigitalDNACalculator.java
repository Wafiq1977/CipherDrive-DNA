package com.cipherdrive.dna.service.dna;

import com.cipherdrive.dna.dto.dna.DNAProfileResponse.WeightSet;
import com.cipherdrive.dna.dto.dna.DNAVectorResponse;
import com.cipherdrive.dna.entity.BehaviorLog;
import com.cipherdrive.dna.entity.DigitalDNA.DriftRegime;
import com.cipherdrive.dna.entity.FileEntity;
import com.cipherdrive.dna.entity.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Digital DNA Calculator — Core Mathematical Engine for CipherDrive-DNA.
 *
 * ══════════════════════════════════════════════════════════════════
 *  8-DIMENSIONAL BEHAVIORAL VECTOR EXTRACTION
 * ══════════════════════════════════════════════════════════════════
 *
 *  Each dimension captures a unique aspect of user behavior,
 *  normalized to [0.0, 1.0] for cross-dimensional comparability.
 *
 *  ┌────┬─────────────────────┬──────────────────────────────────┐
 *  │ #  │ Dimension           │ Formula                          │
 *  ├────┼─────────────────────┼──────────────────────────────────┤
 *  │ 1  │ Login Frequency     │ count(LOGIN) / window_days       │
 *  │ 2  │ Login Time Pattern  │ H(hour_dist) / log2(24)         │
 *  │ 3  │ Session Duration    │ avg_duration / MAX_DURATION      │
 *  │ 4  │ Upload Frequency    │ count(UPLOAD) / window_days      │
 *  │ 5  │ Download Frequency  │ count(DOWNLOAD) / window_days    │
 *  │ 6  │ Delete Frequency    │ count(DELETE) / window_days      │
 *  │ 7  │ Average File Size   │ mean(size) / MAX_FILE_SIZE       │
 *  │ 8  │ Device Consistency  │ max(device_count) / total_events │
 *  └────┴─────────────────────┴──────────────────────────────────┘
 *
 * ══════════════════════════════════════════════════════════════════
 *  WEIGHT FUSION PIPELINE
 * ══════════════════════════════════════════════════════════════════
 *
 *  Stage 4 — AHP (Analytic Hierarchy Process) Subjective Weights:
 *    Expert-judged pairwise comparison matrix → priority vector
 *    Based on security domain knowledge.
 *
 *  Stage 5 — Shannon Entropy Objective Weights:
 *    H_j = -k * sum(p_ij * ln(p_ij))     per dimension j
 *    d_j = 1 - H_j / sum(H)              divergence degree
 *    w_j = d_j / sum(d)                   entropy weight
 *
 *  Stage 6 — Composite Weight Fusion:
 *    w_final = alpha * w_ahp + (1 - alpha) * w_entropy
 *    Default: alpha = 0.5 (balanced fusion)
 *
 * ══════════════════════════════════════════════════════════════════
 *  SIMILARITY & DRIFT COMPUTATION
 * ══════════════════════════════════════════════════════════════════
 *
 *  Cosine Similarity:
 *    cos(A, B) = (A · B) / (||A|| * ||B||)
 *              = sum(w_i * a_i * b_i) / sqrt(sum(w_i*a_i^2) * sum(w_i*b_i^2))
 *
 *  Drift Score:
 *    drift = 1 - cos(A, B)
 *
 *  Drift Regime Classification:
 *    NORMAL    : drift < 0.15
 *    LOW_DRIFT : drift < 0.30
 *    HIGH_DRIFT: drift < 0.50
 *    ANOMALY   : drift >= 0.50
 */
@Slf4j
@Component
public class DigitalDNACalculator {

    // ── Normalization Constants ──

    /** Maximum expected login count per day for normalization */
    private static final double MAX_LOGIN_PER_DAY = 10.0;

    /** Maximum expected session duration in minutes for normalization */
    private static final double MAX_SESSION_DURATION_MIN = 480.0; // 8 hours

    /** Maximum expected file operations per day for normalization */
    private static final double MAX_FILE_OPS_PER_DAY = 50.0;

    /** Maximum expected file size in bytes for normalization (100 MB) */
    private static final double MAX_FILE_SIZE_BYTES = 100.0 * 1024 * 1024;

    /** Default observation window in days */
    private static final int DEFAULT_WINDOW_DAYS = 7;

    /** Drift regime thresholds */
    private static final double DRIFT_NORMAL_THRESHOLD = 0.15;
    private static final double DRIFT_LOW_THRESHOLD = 0.30;
    private static final double DRIFT_HIGH_THRESHOLD = 0.50;

    // ── AHP Expert Weights (Security-Optimized) ──
    // Based on domain expert pairwise comparison:
    //   Login Time Pattern > Login Frequency > Session Duration
    //   Upload > Download > Delete
    //   Device Consistency is critical for identity verification
    //   Avg File Size is least discriminative
    private static final double[] AHP_WEIGHTS = {
            0.12,   // loginFrequency
            0.18,   // loginTimePattern (high: temporal anomaly detection)
            0.10,   // sessionDuration
            0.10,   // uploadFrequency
            0.08,   // downloadFrequency
            0.06,   // deleteFrequency (low: infrequent behavior)
            0.06,   // avgFileSize (low: weak discriminant)
            0.30    // deviceConsistency (highest: strong identity signal)
    };

    /** Alpha parameter for composite weight fusion: w = alpha*w_ahp + (1-alpha)*w_entropy */
    private static final double COMPOSITE_ALPHA = 0.5;

    // ══════════════════════════════════════════════════════════════
    //  STAGE 2-3: DIMENSION EXTRACTION + NORMALIZATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the full 8-dimensional DNA behavioral vector from raw events.
     *
     * @param events    raw behavior logs for the user in the observation window
     * @param sessions  user's sessions for session duration computation
     * @param files     user's files for average file size computation
     * @param windowDays observation window in days
     * @return normalized 8-dimensional DNA vector
     */
    public DNAVectorResponse computeDNAVector(List<BehaviorLog> events,
                                               List<Session> sessions,
                                               List<FileEntity> files,
                                               int windowDays) {
        if (windowDays <= 0) windowDays = DEFAULT_WINDOW_DAYS;

        log.debug("Computing DNA vector: events={}, sessions={}, files={}, window={}d",
                events.size(), sessions.size(), files.size(), windowDays);

        // ── Dimension 1: Login Frequency ──
        // Formula: login_freq = count(LOGIN events) / window_days
        // Normalization: clamp(login_freq / MAX_LOGIN_PER_DAY, 0, 1)
        long loginCount = events.stream()
                .filter(e -> "LOGIN".equals(e.getEventSubtype()))
                .count();
        double loginFrequency = clamp(
                (double) loginCount / windowDays / MAX_LOGIN_PER_DAY,
                0.0, 1.0);

        // ── Dimension 2: Login Time Pattern ──
        // Formula: H(hour_dist) / log2(24)
        // Shannon entropy of the hour-of-day distribution of login events
        double loginTimePattern = computeLoginTimeEntropy(events);

        // ── Dimension 3: Session Duration ──
        // Formula: mean(session_duration) / MAX_SESSION_DURATION
        // Computed from completed sessions (LOGIN→LOGOUT pairs)
        double sessionDuration = computeAverageSessionDuration(sessions);

        // ── Dimension 4: Upload Frequency ──
        // Formula: count(UPLOAD events) / window_days / MAX_FILE_OPS_PER_DAY
        long uploadCount = events.stream()
                .filter(e -> "UPLOAD".equals(e.getEventSubtype()))
                .count();
        double uploadFrequency = clamp(
                (double) uploadCount / windowDays / MAX_FILE_OPS_PER_DAY,
                0.0, 1.0);

        // ── Dimension 5: Download Frequency ──
        // Formula: count(DOWNLOAD events) / window_days / MAX_FILE_OPS_PER_DAY
        long downloadCount = events.stream()
                .filter(e -> "DOWNLOAD".equals(e.getEventSubtype()))
                .count();
        double downloadFrequency = clamp(
                (double) downloadCount / windowDays / MAX_FILE_OPS_PER_DAY,
                0.0, 1.0);

        // ── Dimension 6: Delete Frequency ──
        // Formula: count(DELETE events) / window_days / MAX_FILE_OPS_PER_DAY
        long deleteCount = events.stream()
                .filter(e -> "DELETE".equals(e.getEventSubtype()))
                .count();
        double deleteFrequency = clamp(
                (double) deleteCount / windowDays / MAX_FILE_OPS_PER_DAY,
                0.0, 1.0);

        // ── Dimension 7: Average File Size ──
        // Formula: mean(file_sizes) / MAX_FILE_SIZE_BYTES
        double avgFileSize = computeAverageFileSize(files);

        // ── Dimension 8: Device Consistency ──
        // Formula: max(device_type_counts) / total_events
        // Higher = more consistent (uses fewer distinct devices)
        double deviceConsistency = computeDeviceConsistency(events);

        DNAVectorResponse vector = DNAVectorResponse.builder()
                .loginFrequency(loginFrequency)
                .loginTimePattern(loginTimePattern)
                .sessionDuration(sessionDuration)
                .uploadFrequency(uploadFrequency)
                .downloadFrequency(downloadFrequency)
                .deleteFrequency(deleteFrequency)
                .avgFileSize(avgFileSize)
                .deviceConsistency(deviceConsistency)
                .build();

        log.debug("DNA vector computed: {}", vector);
        return vector;
    }

    // ══════════════════════════════════════════════════════════════
    //  DIMENSION 2: LOGIN TIME PATTERN (Shannon Entropy)
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute normalized Shannon entropy of login hour distribution.
     *
     * Pseudocode:
     *   1. Extract hour-of-day for each LOGIN event
     *   2. Build probability distribution: p_i = count(hour_i) / total_logins
     *   3. Compute Shannon entropy: H = -sum(p_i * log2(p_i)) for p_i > 0
     *   4. Normalize by maximum entropy: H_normalized = H / log2(24)
     *
     * Interpretation:
     *   - H_normalized ≈ 1.0 → user logs in at random hours (high entropy)
     *   - H_normalized ≈ 0.0 → user always logs in at the same hour (low entropy)
     *   - Sudden change in entropy = behavioral drift signal
     *
     * @param events behavior logs containing LOGIN events
     * @return normalized entropy in [0, 1]
     */
    private double computeLoginTimeEntropy(List<BehaviorLog> events) {
        // Filter LOGIN events only
        List<Integer> loginHours = events.stream()
                .filter(e -> "LOGIN".equals(e.getEventSubtype()))
                .map(e -> e.getEventTimestamp().getHour())
                .toList();

        if (loginHours.isEmpty()) {
            return 0.0; // No data → minimum entropy
        }

        // Build hour-of-day frequency distribution (24 bins)
        int[] hourCounts = new int[24];
        for (int hour : loginHours) {
            hourCounts[hour]++;
        }

        int total = loginHours.size();

        // Compute Shannon entropy: H = -sum(p_i * log2(p_i))
        double entropy = 0.0;
        for (int count : hourCounts) {
            if (count > 0) {
                double p = (double) count / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }

        // Normalize by maximum entropy: log2(24) ≈ 4.585
        double maxEntropy = Math.log(24) / Math.log(2);
        return clamp(entropy / maxEntropy, 0.0, 1.0);
    }

    // ══════════════════════════════════════════════════════════════
    //  DIMENSION 3: SESSION DURATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute normalized average session duration.
     *
     * Pseudocode:
     *   1. For each completed session (has both login and logout):
     *      duration = logout_time - login_time
     *   2. Compute mean duration across all completed sessions
     *   3. Normalize: avg_duration / MAX_SESSION_DURATION_MIN
     *
     * For sessions without explicit logout, use:
     *   duration = min(now - created_at, expires_at - created_at)
     *
     * @param sessions list of user sessions
     * @return normalized session duration in [0, 1]
     */
    private double computeAverageSessionDuration(List<Session> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return 0.0;
        }

        double totalDurationMinutes = 0.0;
        int completedCount = 0;

        for (Session session : sessions) {
            long durationMinutes;

            if (session.getIsRevoked() && session.getRevokeReason() == Session.RevokeReason.LOGOUT) {
                // Completed session: use update time as logout time
                durationMinutes = Duration.between(session.getCreatedAt(), session.getUpdatedAt()).toMinutes();
            } else {
                // Active or expired session: estimate from creation to expiry
                durationMinutes = Duration.between(session.getCreatedAt(), session.getExpiresAt()).toMinutes();
            }

            // Only count reasonable session durations (> 1 minute, < 24 hours)
            if (durationMinutes > 1 && durationMinutes < 1440) {
                totalDurationMinutes += durationMinutes;
                completedCount++;
            }
        }

        if (completedCount == 0) {
            return 0.0;
        }

        double avgDuration = totalDurationMinutes / completedCount;
        return clamp(avgDuration / MAX_SESSION_DURATION_MIN, 0.0, 1.0);
    }

    // ══════════════════════════════════════════════════════════════
    //  DIMENSION 7: AVERAGE FILE SIZE
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute normalized average file size.
     *
     * Pseudocode:
     *   1. Collect all file sizes from user's uploaded files
     *   2. Compute mean file size
     *   3. Normalize: mean_size / MAX_FILE_SIZE_BYTES
     *
     * Uses log-scale normalization for better distribution:
     *   normalized = log(1 + mean_size) / log(1 + MAX_FILE_SIZE)
     *
     * @param files list of user's files
     * @return normalized average file size in [0, 1]
     */
    private double computeAverageFileSize(List<FileEntity> files) {
        if (files == null || files.isEmpty()) {
            return 0.0;
        }

        double totalSize = 0.0;
        int count = 0;

        for (FileEntity file : files) {
            if (file.getFileSize() != null && file.getFileSize() > 0) {
                totalSize += file.getFileSize();
                count++;
            }
        }

        if (count == 0) {
            return 0.0;
        }

        double meanSize = totalSize / count;

        // Log-scale normalization for heavy-tailed file size distribution
        double normalized = Math.log(1 + meanSize) / Math.log(1 + MAX_FILE_SIZE_BYTES);
        return clamp(normalized, 0.0, 1.0);
    }

    // ══════════════════════════════════════════════════════════════
    //  DIMENSION 8: DEVICE CONSISTENCY
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute device consistency ratio.
     *
     * Pseudocode:
     *   1. Classify each event's device type from user_agent
     *   2. Count events per device type
     *   3. consistency = max(device_counts) / total_events
     *
     * Interpretation:
     *   - consistency ≈ 1.0 → user always uses the same device type
     *   - consistency ≈ 0.5 → user uses 2 device types equally
     *   - Sudden new device = potential account compromise
     *
     * @param events behavior logs with user-agent data
     * @return device consistency in [0, 1]
     */
    private double computeDeviceConsistency(List<BehaviorLog> events) {
        if (events == null || events.isEmpty()) {
            return 1.0; // No data → assume consistent (no evidence of inconsistency)
        }

        // Count events by device type (classified from user agent)
        Map<String, Long> deviceCounts = events.stream()
                .filter(e -> e.getUserAgent() != null)
                .collect(Collectors.groupingBy(
                        this::classifyDeviceFromUA,
                        Collectors.counting()));

        if (deviceCounts.isEmpty()) {
            return 1.0;
        }

        // Find the most-used device type
        long maxCount = deviceCounts.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);

        long totalEvents = deviceCounts.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        return clamp((double) maxCount / totalEvents, 0.0, 1.0);
    }

    /**
     * Classify device type from User-Agent string.
     */
    private String classifyDeviceFromUA(com.cipherdrive.dna.entity.BehaviorLog event) {
        String ua = event.getUserAgent();
        if (ua == null) return "UNKNOWN";
        String lower = ua.toLowerCase();

        if (lower.contains("mobile") || lower.contains("android") || lower.contains("iphone"))
            return "MOBILE";
        if (lower.contains("ipad") || lower.contains("tablet"))
            return "TABLET";
        if (lower.contains("bot") || lower.contains("crawler") || lower.contains("headless"))
            return "BOT";
        return "DESKTOP";
    }

    // ══════════════════════════════════════════════════════════════
    //  STAGE 5: SHANNON ENTROPY OBJECTIVE WEIGHTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute Shannon entropy-based objective weights from multiple DNA profiles.
     *
     * Pseudocode:
     *   For each dimension j:
     *     1. Build probability distribution:
     *        p_ij = v_ij / sum_i(v_ij)   for each profile i
     *     2. Compute information entropy:
     *        H_j = -k * sum(p_ij * ln(p_ij))
     *        where k = 1 / ln(n), n = number of profiles
     *     3. Compute divergence degree:
     *        d_j = 1 - H_j
     *     4. Compute entropy weight:
     *        w_j = d_j / sum(d)
     *
     * Interpretation:
     *   - Low entropy H_j → high divergence d_j → high weight w_j
     *   - Dimensions with greater variation across profiles get more weight
     *
     * @param profiles list of historical DNA vectors for the user
     * @return 8-dimensional entropy weight vector
     */
    public WeightSet computeEntropyWeights(List<DNAVectorResponse> profiles) {
        if (profiles == null || profiles.size() < 2) {
            // Not enough data for entropy computation → return uniform weights
            double uniform = 1.0 / 8.0;
            return WeightSet.fromArray(new double[]{uniform, uniform, uniform, uniform,
                    uniform, uniform, uniform, uniform});
        }

        int n = profiles.size();
        double k = 1.0 / Math.log(n); // Normalization constant

        // Convert to matrix: dimensions[8][n]
        double[][] dimensionValues = new double[8][n];
        for (int i = 0; i < n; i++) {
            double[] v = profiles.get(i).toArray();
            for (int j = 0; j < 8; j++) {
                dimensionValues[j][i] = v[j];
            }
        }

        double[] entropy = new double[8];
        double[] divergence = new double[8];

        for (int j = 0; j < 8; j++) {
            // Step 1: Compute sum for this dimension
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                sum += dimensionValues[j][i];
            }

            if (sum == 0.0) {
                entropy[j] = 1.0; // All zeros → maximum entropy (no information)
                divergence[j] = 0.0;
                continue;
            }

            // Step 2: Compute probability distribution and entropy
            // H_j = -k * sum(p_ij * ln(p_ij))
            double h = 0.0;
            for (int i = 0; i < n; i++) {
                double p = dimensionValues[j][i] / sum;
                if (p > 0.0) {
                    h -= p * Math.log(p);
                }
            }
            entropy[j] = k * h;

            // Step 3: Divergence degree
            divergence[j] = 1.0 - entropy[j];
        }

        // Step 4: Normalize to get entropy weights
        double divergenceSum = Arrays.stream(divergence).sum();
        double[] weights = new double[8];
        if (divergenceSum > 0.0) {
            for (int j = 0; j < 8; j++) {
                weights[j] = divergence[j] / divergenceSum;
            }
        } else {
            Arrays.fill(weights, 1.0 / 8.0);
        }

        log.debug("Entropy weights computed: {}", Arrays.toString(weights));
        return WeightSet.fromArray(weights);
    }

    // ══════════════════════════════════════════════════════════════
    //  STAGE 4: AHP SUBJECTIVE WEIGHTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Get AHP (Analytic Hierarchy Process) subjective weights.
     *
     * These weights are derived from a pairwise comparison matrix
     * based on security domain expert judgment:
     *
     * Priority ranking (security significance):
     *   1. Device Consistency (0.30) — strongest identity signal
     *   2. Login Time Pattern (0.18) — temporal anomaly detection
     *   3. Login Frequency   (0.12) — behavioral baseline
     *   4. Session Duration  (0.10) — usage pattern
     *   5. Upload Frequency  (0.10) — data exfiltration indicator
     *   6. Download Frequency(0.08) — data access pattern
     *   7. Delete Frequency  (0.06) — destructive operation signal
     *   8. Average File Size (0.06) — weak discriminant
     *
     * @return 8-dimensional AHP weight vector
     */
    public WeightSet getAHPWeights() {
        return WeightSet.fromArray(AHP_WEIGHTS.clone());
    }

    // ══════════════════════════════════════════════════════════════
    //  STAGE 6: COMPOSITE WEIGHT FUSION
    // ══════════════════════════════════════════════════════════════

    /**
     * Fuse AHP subjective weights with entropy objective weights.
     *
     * Formula:
     *   w_final = alpha * w_ahp + (1 - alpha) * w_entropy
     *
     * where alpha controls the trade-off:
     *   - alpha = 1.0 → pure AHP (expert-driven)
     *   - alpha = 0.0 → pure entropy (data-driven)
     *   - alpha = 0.5 → balanced fusion (default)
     *
     * After fusion, weights are re-normalized to sum to 1.0.
     *
     * @param ahpWeights     subjective expert weights
     * @param entropyWeights objective data-driven weights
     * @param alpha          fusion parameter [0, 1]
     * @return composite weight vector (sum = 1.0)
     */
    public WeightSet computeCompositeWeights(WeightSet ahpWeights, WeightSet entropyWeights, double alpha) {
        double[] ahp = ahpWeights.toArray();
        double[] entropy = entropyWeights.toArray();
        double[] composite = new double[8];

        // Weighted fusion
        for (int i = 0; i < 8; i++) {
            composite[i] = alpha * ahp[i] + (1 - alpha) * entropy[i];
        }

        // Re-normalize to sum = 1.0
        double sum = Arrays.stream(composite).sum();
        if (sum > 0.0) {
            for (int i = 0; i < 8; i++) {
                composite[i] /= sum;
            }
        }

        log.debug("Composite weights (alpha={}): {}", alpha, Arrays.toString(composite));
        return WeightSet.fromArray(composite);
    }

    // ══════════════════════════════════════════════════════════════
    //  STAGE 7: COSINE SIMILARITY + DRIFT COMPUTATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute weighted cosine similarity between two DNA vectors.
     *
     * Formula:
     *   cos(A, B) = sum(w_i * a_i * b_i) / sqrt(sum(w_i*a_i^2) * sum(w_i*b_i^2))
     *
     * where w_i are the composite weights from Stage 6.
     *
     * Properties:
     *   - Range: [-1, 1], but for non-negative DNA vectors → [0, 1]
     *   - cos = 1.0 → identical behavioral profile
     *   - cos = 0.0 → completely dissimilar profiles
     *
     * @param current  current DNA vector
     * @param baseline baseline (reference) DNA vector
     * @param weights  composite weight vector
     * @return cosine similarity in [0, 1]
     */
    public double computeCosineSimilarity(DNAVectorResponse current,
                                           DNAVectorResponse baseline,
                                           WeightSet weights) {
        double[] a = current.toArray();
        double[] b = baseline.toArray();
        double[] w = weights.toArray();

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < 8; i++) {
            dotProduct += w[i] * a[i] * b[i];
            normA += w[i] * a[i] * a[i];
            normB += w[i] * b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0; // Zero vector(s) → no similarity
        }

        double similarity = dotProduct / denominator;
        return clamp(similarity, 0.0, 1.0);
    }

    /**
     * Compute Euclidean distance between two DNA vectors.
     *
     * Formula:
     *   d(A, B) = sqrt(sum(w_i * (a_i - b_i)^2))
     *
     * @param current  current DNA vector
     * @param baseline baseline DNA vector
     * @param weights  composite weight vector
     * @return weighted Euclidean distance
     */
    public double computeEuclideanDistance(DNAVectorResponse current,
                                            DNAVectorResponse baseline,
                                            WeightSet weights) {
        double[] a = current.toArray();
        double[] b = baseline.toArray();
        double[] w = weights.toArray();

        double sum = 0.0;
        for (int i = 0; i < 8; i++) {
            double diff = a[i] - b[i];
            sum += w[i] * diff * diff;
        }

        return Math.sqrt(sum);
    }

    /**
     * Compute per-dimension drift (absolute difference between current and baseline).
     *
     * @param current  current DNA vector
     * @param baseline baseline DNA vector
     * @return per-dimension drift vector
     */
    public DNAVectorResponse computeDimensionDrift(DNAVectorResponse current, DNAVectorResponse baseline) {
        double[] a = current.toArray();
        double[] b = baseline.toArray();
        double[] drift = new double[8];

        for (int i = 0; i < 8; i++) {
            drift[i] = Math.abs(a[i] - b[i]);
        }

        return DNAVectorResponse.fromArray(drift);
    }

    // ══════════════════════════════════════════════════════════════
    //  DRIFT REGIME CLASSIFICATION
    // ══════════════════════════════════════════════════════════════

    /**
     * Classify the drift regime based on drift score.
     *
     * Thresholds:
     *   NORMAL    : drift < 0.15 → profile matches baseline
     *   LOW_DRIFT : drift < 0.30 → minor deviation, monitor
     *   HIGH_DRIFT: drift < 0.50 → significant deviation, flag for ICS
     *   ANOMALY   : drift >= 0.50 → critical deviation, security challenge
     *
     * @param driftScore computed drift score (1 - cosine_similarity)
     * @return classified drift regime
     */
    public DriftRegime classifyDriftRegime(double driftScore) {
        if (driftScore < DRIFT_NORMAL_THRESHOLD) {
            return DriftRegime.NORMAL;
        } else if (driftScore < DRIFT_LOW_THRESHOLD) {
            return DriftRegime.LOW_DRIFT;
        } else if (driftScore < DRIFT_HIGH_THRESHOLD) {
            return DriftRegime.HIGH_DRIFT;
        } else {
            return DriftRegime.ANOMALY;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ══════════════════════════════════════════════════════════════

    /**
     * Clamp a value to [min, max] range.
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Compute weighted DNA score from vector and weights.
     * Used for single-number summary of DNA profile.
     *
     * Formula: score = sum(w_i * v_i)
     */
    public double computeWeightedScore(DNAVectorResponse vector, WeightSet weights) {
        double[] v = vector.toArray();
        double[] w = weights.toArray();

        double score = 0.0;
        for (int i = 0; i < 8; i++) {
            score += w[i] * v[i];
        }
        return score;
    }
}
