package com.cipherdrive.dna.exception;

public class AuthenticationException extends RuntimeException {

    private final String errorCode;

    public AuthenticationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public AuthenticationException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    // ── Factory Methods ──

    public static AuthenticationException userNotFound(String username) {
        return new AuthenticationException(
                String.format("User not found: %s", username), "AUTH_USER_NOT_FOUND");
    }

    public static AuthenticationException invalidCredentials() {
        return new AuthenticationException("Invalid username or password", "AUTH_INVALID_CREDENTIALS");
    }

    public static AuthenticationException accountLocked() {
        return new AuthenticationException("Account is locked due to too many failed login attempts", "AUTH_ACCOUNT_LOCKED");
    }

    public static AuthenticationException accountDisabled() {
        return new AuthenticationException("Account is disabled by administrator", "AUTH_ACCOUNT_DISABLED");
    }

    public static AuthenticationException usernameAlreadyExists(String username) {
        return new AuthenticationException(
                String.format("Username already exists: %s", username), "AUTH_USERNAME_EXISTS");
    }

    public static AuthenticationException emailAlreadyExists(String email) {
        return new AuthenticationException(
                String.format("Email already exists: %s", email), "AUTH_EMAIL_EXISTS");
    }

    public static AuthenticationException invalidRefreshToken() {
        return new AuthenticationException("Invalid or expired refresh token", "AUTH_INVALID_REFRESH");
    }

    public static AuthenticationException roleNotFound(String roleName) {
        return new AuthenticationException(
                String.format("Role not found: %s", roleName), "AUTH_ROLE_NOT_FOUND");
    }
}
