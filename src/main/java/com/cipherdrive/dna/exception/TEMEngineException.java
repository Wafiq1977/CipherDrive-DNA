package com.cipherdrive.dna.exception;

/**
 * TEM (Trust Evolution Model) Engine Exception.
 *
 * Error codes map to specific failure scenarios in the TEM computation pipeline:
 *
 *   INSUFFICIENT_DATA    — Not enough ICS observations for OLS estimation
 *   NO_PRIOR_TEM         — No previous TEM snapshot exists for the user
 *   OU_ESTIMATION_FAILED — OLS regression failed (singular matrix, etc.)
 *   EM_SOLVER_FAILED     — Euler-Maruyama step computation error
 *   REGIME_ERROR         — Invalid regime classification
 *   COMPUTATION_FAILED   — General TEM pipeline failure
 *   RATE_LIMITED         — TEM recomputation too frequent (cooldown active)
 *   INVALID_REFERENCE    — Referenced ICS/DNA entity not found
 */
public class TEMEngineException extends RuntimeException {

    private final ErrorCode errorCode;

    public enum ErrorCode {
        INSUFFICIENT_DATA(2001, "Insufficient ICS data for TEM computation"),
        NO_PRIOR_TEM(2002, "No prior TEM snapshot found"),
        OU_ESTIMATION_FAILED(2003, "OU parameter estimation failed"),
        EM_SOLVER_FAILED(2004, "Euler-Maruyama solver error"),
        REGIME_ERROR(2005, "Regime classification error"),
        COMPUTATION_FAILED(2006, "TEM computation pipeline failed"),
        RATE_LIMITED(2007, "TEM recomputation rate limited"),
        INVALID_REFERENCE(2008, "Referenced entity not found");

        private final int code;
        private final String description;

        ErrorCode(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() { return code; }
        public String getDescription() { return description; }
    }

    public TEMEngineException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TEMEngineException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }

    public int getCode() { return errorCode.getCode(); }
}
