package com.cipherdrive.dna.dto.behavior;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for behavioral event logging.
 * Used by BehaviorLogInterceptor to capture security events automatically.
 *
 * Captured data per event:
 *   - userId: authenticated user ID from JWT
 *   - sessionId: active session ID from JWT
 *   - timestamp: client-side or server-side event time
 *   - ipAddress: client IP (X-Forwarded-For aware)
 *   - userAgent: browser User-Agent string
 *   - deviceType: classified device (DESKTOP, MOBILE, TABLET)
 *   - eventType: security event category (LOGIN, LOGOUT, UPLOAD, DOWNLOAD, DELETE)
 *   - eventMetadata: JSON payload with event-specific data
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BehaviorEvent {

    private Long userId;
    private Long sessionId;
    private String ipAddress;
    private String userAgent;
    private String deviceType;
    private SecurityEventType eventType;
    private String eventMetadata;

    /**
     * Security event types — automatically captured by interceptor.
     * Each maps to a DNA behavioral dimension for vector extraction.
     */
    public enum SecurityEventType {
        /** User authentication success → maps to TEMPORAL dimension */
        LOGIN,
        /** User session termination → maps to TEMPORAL dimension */
        LOGOUT,
        /** File upload operation → maps to FILE_OP dimension */
        UPLOAD,
        /** File download operation → maps to FILE_OP dimension */
        DOWNLOAD,
        /** File deletion operation → maps to FILE_OP dimension */
        DELETE
    }

    /**
     * Device type classification from User-Agent parsing.
     */
    public enum DeviceType {
        DESKTOP,
        MOBILE,
        TABLET,
        BOT,
        UNKNOWN
    }
}
