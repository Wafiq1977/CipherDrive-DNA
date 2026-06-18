package com.cipherdrive.dna.interceptor;

import com.cipherdrive.dna.dto.behavior.BehaviorEvent;
import com.cipherdrive.dna.dto.behavior.BehaviorEvent.SecurityEventType;
import com.cipherdrive.dna.security.JwtAuthenticationFilter.CipherDriveAuthDetails;
import com.cipherdrive.dna.service.behavioral.BehaviorLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * Behavioral Logging Interceptor for CipherDrive-DNA.
 *
 * Automatically captures user activities after controller methods execute.
 * Uses Spring HandlerInterceptor's afterCompletion() to log events
 * AFTER the response is sent — zero impact on request latency.
 *
 * Intercepted Endpoints:
 *   ┌──────────────────────────────────────────────────────┐
 *   │ Endpoint Pattern                → SecurityEventType  │
 *   ├──────────────────────────────────────────────────────┤
 *   │ POST /api/auth/login            → LOGIN              │
 *   │ POST /api/auth/logout           → LOGOUT             │
 *   │ POST /api/auth/logout-all       → LOGOUT             │
 *   │ POST /api/files/upload          → UPLOAD             │
 *   │ GET  /api/files/{id}/download   → DOWNLOAD           │
 *   │ DELETE /api/files/{id}          → DELETE             │
 *   └──────────────────────────────────────────────────────┘
 *
 * Data Captured Per Event:
 *   - user_id: from JWT SecurityContext
 *   - session_id: from JWT SecurityContext
 *   - timestamp: server-side event time
 *   - ip_address: X-Forwarded-For or remote address
 *   - user_agent: browser User-Agent header
 *   - device_type: DESKTOP/MOBILE/TABLET/BOT (parsed from UA)
 *   - event_type: LOGIN/LOGOUT/UPLOAD/DOWNLOAD/DELETE
 *   - event_metadata: JSON with event-specific data (file ID, filename, etc.)
 *
 * Thread Safety:
 *   - All logging is async via BehaviorLogService.logSecurityEvent()
 *   - Never blocks the HTTP response thread
 *   - Failed logging is silently swallowed (never affects user)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorLogInterceptor implements HandlerInterceptor {

    private final BehaviorLogService behaviorLogService;

    // ── Request attribute keys ──
    private static final String EVENT_ATTRIBUTE = "cipherdrive_behavior_event";
    private static final String START_TIME_ATTRIBUTE = "cipherdrive_request_start";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) {
        // Record request start time for latency measurement
        request.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        // Only intercept successful controller methods
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }

        // Only log successful requests (2xx status)
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            return;
        }

        // Determine event type from the endpoint
        SecurityEventType eventType = resolveEventType(request, handlerMethod);
        if (eventType == null) {
            return; // Not a trackable endpoint
        }

        // Extract authentication context
        CipherDriveAuthDetails authDetails = extractAuthDetails(request);
        if (authDetails == null) {
            // For LOGIN events, auth details might not be in SecurityContext yet
            // The AuthenticationService handles LOGIN logging directly
            if (eventType == SecurityEventType.LOGIN) {
                return; // LOGIN is logged by AuthenticationService
            }
            log.debug("No auth details for behavioral event: type={}, path={}",
                    eventType, request.getRequestURI());
            return;
        }

        // Calculate request latency
        Long startTime = (Long) request.getAttribute(START_TIME_ATTRIBUTE);
        long latencyMs = startTime != null ? System.currentTimeMillis() - startTime : 0;

        // Build event metadata
        String metadata = buildMetadata(eventType, request, authDetails, latencyMs);

        // Build and dispatch event
        BehaviorEvent event = BehaviorEvent.builder()
                .userId(authDetails.userId())
                .sessionId(authDetails.sessionId())
                .ipAddress(authDetails.clientIp())
                .userAgent(request.getHeader("User-Agent"))
                .deviceType(behaviorLogService.classifyDevice(request.getHeader("User-Agent")))
                .eventType(eventType)
                .eventMetadata(metadata)
                .build();

        // Async dispatch — never blocks the response
        behaviorLogService.logSecurityEvent(event);
    }

    // ── Event Type Resolution ──

    /**
     * Determine the security event type from the request URI and HTTP method.
     * Returns null for endpoints that should not be tracked.
     */
    private SecurityEventType resolveEventType(HttpServletRequest request, HandlerMethod handlerMethod) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // POST /api/auth/login → LOGIN (handled by AuthenticationService directly)
        if ("POST".equals(method) && path.equals("/api/auth/login")) {
            return null; // AuthenticationService logs this with full context
        }

        // POST /api/auth/logout → LOGOUT
        if ("POST".equals(method) && (path.equals("/api/auth/logout") || path.equals("/api/auth/logout-all"))) {
            return SecurityEventType.LOGOUT;
        }

        // POST /api/files/upload → UPLOAD
        if ("POST".equals(method) && path.equals("/api/files/upload")) {
            return SecurityEventType.UPLOAD;
        }

        // GET /api/files/{id}/download → DOWNLOAD
        if ("GET".equals(method) && path.matches("/api/files/\\d+/download")) {
            return SecurityEventType.DOWNLOAD;
        }

        // DELETE /api/files/{id} → DELETE
        if ("DELETE".equals(method) && path.matches("/api/files/\\d+")) {
            return SecurityEventType.DELETE;
        }

        return null; // Not a trackable endpoint
    }

    // ── Auth Context Extraction ──

    /**
     * Extract CipherDriveAuthDetails from SecurityContext.
     * Returns null if the user is not authenticated.
     */
    private CipherDriveAuthDetails extractAuthDetails(HttpServletRequest request) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.getDetails() instanceof CipherDriveAuthDetails details) {
                return details;
            }
        } catch (Exception e) {
            log.debug("Failed to extract auth details: {}", e.getMessage());
        }
        return null;
    }

    // ── Metadata Building ──

    /**
     * Build JSON metadata for the event.
     * Includes event-specific data (file ID, filename, etc.) plus request context.
     */
    private String buildMetadata(SecurityEventType eventType, HttpServletRequest request,
                                  CipherDriveAuthDetails auth, long latencyMs) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("latency_ms", latencyMs);
            metadata.put("request_method", request.getMethod());
            metadata.put("request_path", request.getRequestURI());
            metadata.put("username", auth.username());

            // Event-specific metadata
            switch (eventType) {
                case UPLOAD -> {
                    metadata.put("content_length", request.getContentLengthLong());
                    metadata.put("content_type", request.getContentType());
                }
                case DOWNLOAD, DELETE -> {
                    // Extract file ID from path: /api/files/{id}/download or /api/files/{id}
                    String fileId = extractPathParameter(request.getRequestURI(), 3);
                    if (fileId != null) metadata.put("file_id", fileId);
                }
                case LOGOUT -> {
                    metadata.put("logout_all", request.getRequestURI().contains("logout-all"));
                }
            }

            // Convert to JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(metadata);

        } catch (Exception e) {
            return "{\"error\":\"metadata_build_failed\"}";
        }
    }

    /**
     * Extract a path parameter by position.
     * Example: /api/files/42/download → position 3 → "42"
     */
    private String extractPathParameter(String path, int position) {
        try {
            String[] parts = path.split("/");
            if (parts.length > position) {
                return parts[position];
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
