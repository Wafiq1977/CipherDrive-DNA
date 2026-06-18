package com.cipherdrive.dna.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for authentication response containing JWT tokens.
 * Access token: short-lived (15 min) for API authorization.
 * Refresh token: long-lived (7 days) for token renewal.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
    private String username;
    private String role;

    public static AuthResponse of(String accessToken, String refreshToken,
                                   long expiresIn, String username, String role) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .username(username)
                .role(role)
                .build();
    }
}
