package com.cipherdrive.dna.exception;

/**
 * Exception thrown by the Digital DNA Engine.
 *
 * Covers error scenarios:
 *   - Insufficient behavioral data for DNA computation
 *   - Baseline profile not found for drift computation
 *   - Invalid DNA vector dimensions
 *   - DNA computation pipeline failure
 */
public class DNAEngineException extends RuntimeException {

    private final ErrorCode errorCode;

    public enum ErrorCode {
        /** Not enough behavioral events to compute DNA profile */
        INSUFFICIENT_DATA,
        /** No baseline profile exists for drift computation */
        NO_BASELINE,
        /** DNA vector dimension mismatch */
        DIMENSION_MISMATCH,
        /** DNA computation pipeline failure */
        COMPUTATION_FAILED,
        /** Rate limit: too frequent DNA recomputation */
        RATE_LIMITED,
        /** Invalid user or session reference */
        INVALID_REFERENCE
    }

    public DNAEngineException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DNAEngineException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
