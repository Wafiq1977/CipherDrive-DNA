package com.cipherdrive.dna.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException as SpringAuthException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication error [{}]: {}", ex.getErrorCode(), ex.getMessage());

        HttpStatus status = switch (ex.getErrorCode()) {
            case "AUTH_USER_NOT_FOUND", "AUTH_INVALID_CREDENTIALS" -> HttpStatus.UNAUTHORIZED;
            case "AUTH_ACCOUNT_LOCKED" -> HttpStatus.FORBIDDEN;
            case "AUTH_ACCOUNT_DISABLED" -> HttpStatus.FORBIDDEN;
            case "AUTH_USERNAME_EXISTS", "AUTH_EMAIL_EXISTS" -> HttpStatus.CONFLICT;
            case "AUTH_INVALID_REFRESH" -> HttpStatus.UNAUTHORIZED;
            case "AUTH_ROLE_NOT_FOUND" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.UNAUTHORIZED;
        };

        return buildErrorResponse(status, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "VALIDATION_ERROR");
        body.put("message", "Input validation failed");
        body.put("fieldErrors", fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorageException(StorageException ex) {
        log.warn("Storage error [{}]: {}", ex.getErrorCode(), ex.getMessage());

        HttpStatus status = switch (ex.getErrorCode()) {
            case "STORAGE_FILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "STORAGE_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "STORAGE_QUOTA_EXCEEDED" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "STORAGE_UPLOAD_FAILED", "STORAGE_DOWNLOAD_FAILED", "STORAGE_DELETE_FAILED" -> HttpStatus.BAD_GATEWAY;
            case "STORAGE_MINIO_UNAVAILABLE", "STORAGE_BUCKET_INIT_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return buildErrorResponse(status, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(DNAEngineException.class)
    public ResponseEntity<Map<String, Object>> handleDNAEngineException(DNAEngineException ex) {
        log.warn("DNA Engine error [{}]: {}", ex.getErrorCode(), ex.getMessage());

        HttpStatus status = switch (ex.getErrorCode()) {
            case INSUFFICIENT_DATA -> HttpStatus.NO_CONTENT;
            case NO_BASELINE -> HttpStatus.NOT_FOUND;
            case DIMENSION_MISMATCH -> HttpStatus.INTERNAL_SERVER_ERROR;
            case COMPUTATION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REFERENCE -> HttpStatus.BAD_REQUEST;
        };

        return buildErrorResponse(status, "DNA_" + ex.getErrorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(TEMEngineException.class)
    public ResponseEntity<Map<String, Object>> handleTEMEngineException(TEMEngineException ex) {
        log.warn("TEM Engine error [{}]: {}", ex.getErrorCode(), ex.getMessage());

        HttpStatus status = switch (ex.getErrorCode()) {
            case INSUFFICIENT_DATA -> HttpStatus.NO_CONTENT;
            case NO_PRIOR_TEM -> HttpStatus.NOT_FOUND;
            case OU_ESTIMATION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case EM_SOLVER_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case REGIME_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case COMPUTATION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REFERENCE -> HttpStatus.BAD_REQUEST;
        };

        return buildErrorResponse(status, "TEM_" + ex.getErrorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String errorCode, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
