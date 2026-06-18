package com.cipherdrive.dna.controller;

import com.cipherdrive.dna.dto.behavior.BehaviorEventResponse;
import com.cipherdrive.dna.entity.BehaviorLog;
import com.cipherdrive.dna.repository.SessionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import com.cipherdrive.dna.security.JwtAuthenticationFilter.CipherDriveAuthDetails;
import com.cipherdrive.dna.service.behavioral.BehaviorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Behavioral Log REST Controller for CipherDrive-DNA.
 *
 * Endpoints:
 *   GET  /api/behavior/recent        — Get recent events for current user
 *   GET  /api/behavior/since?time=    — Get events since a timestamp
 *   GET  /api/behavior/type/{type}    — Get events by DNA dimension type
 *   GET  /api/behavior/session/{id}   — Get events for a session
 *   GET  /api/behavior/dimensions     — Get available DNA dimensions
 *   GET  /api/behavior/stats          — Get event statistics
 *   POST /api/behavior/sdk            — Receive client SDK events (KEYSTROKE, MOUSE, etc.)
 */
@Slf4j
@RestController
@RequestMapping("/api/behavior")
@RequiredArgsConstructor
public class BehaviorLogController {

    private final BehaviorLogService behaviorLogService;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;

    /**
     * Get recent events for the current user.
     */
    @GetMapping("/recent")
    public ResponseEntity<List<BehaviorEventResponse>> getRecentEvents(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(behaviorLogService.getRecentEvents(userId, limit));
    }

    /**
     * Get events since a specific timestamp (incremental DNA update).
     */
    @GetMapping("/since")
    public ResponseEntity<List<BehaviorEventResponse>> getEventsSince(
            @RequestParam("time") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(behaviorLogService.getEventsSince(userId, since));
    }

    /**
     * Get events by DNA dimension type — per-dimension vector extraction.
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<List<BehaviorEventResponse>> getEventsByType(
            @PathVariable String type,
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

        Long userId = getAuthDetails().userId();
        if (since == null) {
            since = LocalDateTime.now().minusHours(1); // Default: last 1 hour
        }

        BehaviorLog.EventType eventType = BehaviorLog.EventType.valueOf(type.toUpperCase());
        return ResponseEntity.ok(behaviorLogService.getEventsByType(userId, eventType, since));
    }

    /**
     * Get events for a specific session — session-scoped ICS computation.
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<BehaviorEventResponse>> getSessionEvents(
            @PathVariable Long sessionId) {

        return ResponseEntity.ok(behaviorLogService.getSessionEvents(sessionId));
    }

    /**
     * Get available DNA dimensions for the current user.
     * Returns which dimensions have data available for extraction.
     */
    @GetMapping("/dimensions")
    public ResponseEntity<List<BehaviorLog.EventType>> getAvailableDimensions() {
        Long userId = getAuthDetails().userId();
        return ResponseEntity.ok(behaviorLogService.getAvailableDimensions(userId));
    }

    /**
     * Get event statistics for the current user.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(value = "hours", defaultValue = "24") int hours) {

        Long userId = getAuthDetails().userId();
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(hours);

        long totalEvents = behaviorLogService.getEventCount(userId, start, end);

        Map<String, Object> stats = Map.of(
                "userId", userId,
                "timeRange", Map.of("start", start.toString(), "end", end.toString(), "hours", hours),
                "totalEvents", totalEvents,
                "eventsPerHour", hours > 0 ? totalEvents / hours : 0
        );

        return ResponseEntity.ok(stats);
    }

    /**
     * Receive client SDK behavioral events.
     * Called by the browser-based telemetry SDK for KEYSTROKE, MOUSE, NAVIGATION events.
     *
     * Request body:
     *   {
     *     "eventType": "KEYSTROKE",
     *     "eventSubtype": "KEY_PRESS",
     *     "payload": { "key": "a", "dwellTime": 85, "flightTime": 120 },
     *     "clientTimestamp": "2025-01-15T10:30:00.123"
     *   }
     */
    @PostMapping("/sdk")
    public ResponseEntity<Map<String, String>> receiveSdkEvent(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        var authDetails = getAuthDetails();
        if (authDetails == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        try {
            String eventTypeStr = (String) body.get("eventType");
            String eventSubtype = (String) body.getOrDefault("eventSubtype", "UNKNOWN");
            Object payloadObj = body.get("payload");
            String payload = payloadObj != null
                    ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payloadObj)
                    : "{}";

            BehaviorLog.EventType eventType = BehaviorLog.EventType.valueOf(eventTypeStr.toUpperCase());

            behaviorLogService.logClientEvent(
                    authDetails.userId(),
                    authDetails.sessionId(),
                    eventType,
                    eventSubtype,
                    payload,
                    authDetails.clientIp(),
                    request.getHeader("User-Agent")
            );

            return ResponseEntity.ok(Map.of("status", "logged", "eventType", eventTypeStr));

        } catch (Exception e) {
            log.error("Failed to process SDK event: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid event data"));
        }
    }

    // ── Internal ──

    private CipherDriveAuthDetails getAuthDetails() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof CipherDriveAuthDetails details) {
            return details;
        }
        return null;
    }
}
