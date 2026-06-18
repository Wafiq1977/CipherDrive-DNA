package com.cipherdrive.dna.service.behavioral;

import com.cipherdrive.dna.dto.behavior.BehaviorEvent;
import com.cipherdrive.dna.dto.behavior.BehaviorEvent.DeviceType;
import com.cipherdrive.dna.dto.behavior.BehaviorEvent.SecurityEventType;
import com.cipherdrive.dna.dto.behavior.BehaviorEventResponse;
import com.cipherdrive.dna.entity.BehaviorLog;
import com.cipherdrive.dna.entity.Session;
import com.cipherdrive.dna.entity.User;
import com.cipherdrive.dna.repository.BehaviorLogRepository;
import com.cipherdrive.dna.repository.SessionRepository;
import com.cipherdrive.dna.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavioral Logging Service for CipherDrive-DNA.
 *
 * Core Principles:
 *   - ALL logging is ASYNC — never block the request thread
 *   - Events are ENRICHED before storage (device classification, metadata)
 *   - Every event maps to one of the 7 DNA behavioral dimensions
 *   - Security events (LOGIN/LOGOUT/UPLOAD/DOWNLOAD/DELETE) are auto-captured
 *   - Client-side events (KEYSTROKE/MOUSE/NAVIGATION) are received via SDK API
 *
 * Event → Dimension Mapping:
 *   ┌──────────────────────────────────────────────┐
 *   │ SecurityEventType    → DNA Dimension         │
 *   ├──────────────────────────────────────────────┤
 *   │ LOGIN, LOGOUT        → TEMPORAL              │
 *   │ UPLOAD, DOWNLOAD     → FILE_OP               │
 *   │ DELETE               → FILE_OP               │
 *   │ (client KEYSTROKE)   → KEYSTROKE             │
 *   │ (client MOUSE)       → MOUSE                 │
 *   │ (client NAVIGATION)  → NAVIGATION            │
 *   │ device fingerprint   → DEVICE                │
 *   │ IP/location context  → CONTEXT               │
 *   └──────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorLogService {

    private final BehaviorLogRepository behaviorLogRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    // ── ASYNC SECURITY EVENT LOGGING ──

    /**
     * Log a security event asynchronously.
     * Called by BehaviorLogInterceptor after controller methods complete.
     *
     * @param event behavioral event with all context data
     */
    @Async("behaviorLogExecutor")
    @Transactional
    public void logSecurityEvent(BehaviorEvent event) {
        try {
            BehaviorLog logEntry = buildBehaviorLog(event);
            behaviorLogRepository.save(logEntry);

            log.debug("Security event logged: userId={}, type={}, ip={}",
                    event.getUserId(), event.getEventType(), event.getIpAddress());
        } catch (Exception e) {
            // NEVER let logging failure affect the user's request
            log.error("Failed to log security event: userId={}, type={}, error={}",
                    event.getUserId(), event.getEventType(), e.getMessage());
        }
    }

    /**
     * Log a security event synchronously (for critical events that must be persisted).
     */
    @Transactional
    public void logSecurityEventSync(BehaviorEvent event) {
        try {
            BehaviorLog logEntry = buildBehaviorLog(event);
            behaviorLogRepository.save(logEntry);

            log.info("Security event logged (sync): userId={}, type={}, ip={}",
                    event.getUserId(), event.getEventType(), event.getIpAddress());
        } catch (Exception e) {
            log.error("Failed to log security event (sync): userId={}, type={}, error={}",
                    event.getUserId(), event.getEventType(), e.getMessage());
        }
    }

    // ── CLIENT-SIDE EVENT LOGGING (for DNA Engine) ──

    /**
     * Log a client-side behavioral event.
     * Called by the browser SDK for KEYSTROKE, MOUSE, NAVIGATION events.
     */
    @Async("behaviorLogExecutor")
    @Transactional
    public void logClientEvent(Long userId, Long sessionId, BehaviorLog.EventType eventType,
                                String eventSubtype, String payload, String clientIp, String userAgent) {
        try {
            User user = userRepository.getReferenceById(userId);
            Session session = sessionRepository.getReferenceById(sessionId);

            BehaviorLog logEntry = BehaviorLog.builder()
                    .user(user)
                    .session(session)
                    .eventType(eventType)
                    .eventSubtype(eventSubtype)
                    .eventPayload(payload)
                    .eventTimestamp(LocalDateTime.now())
                    .clientIp(clientIp)
                    .userAgent(truncate(userAgent, 512))
                    .build();

            behaviorLogRepository.save(logEntry);

            log.debug("Client event logged: userId={}, type={}, subtype={}",
                    userId, eventType, eventSubtype);
        } catch (Exception e) {
            log.error("Failed to log client event: userId={}, type={}, error={}",
                    userId, eventType, e.getMessage());
        }
    }

    // ── QUERY METHODS (for DNA Engine + Admin Dashboard) ──

    /**
     * Get recent events for a user — primary DNA extraction query.
     */
    @Transactional(readOnly = true)
    public List<BehaviorEventResponse> getRecentEvents(Long userId, int limit) {
        return behaviorLogRepository.findRecentByUserId(userId, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get events for a user since a given timestamp — incremental DNA update.
     */
    @Transactional(readOnly = true)
    public List<BehaviorEventResponse> getEventsSince(Long userId, LocalDateTime since) {
        return behaviorLogRepository.findByUserIdSince(userId, since).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get events by DNA dimension type — per-dimension vector extraction.
     */
    @Transactional(readOnly = true)
    public List<BehaviorEventResponse> getEventsByType(Long userId,
                                                        BehaviorLog.EventType eventType,
                                                        LocalDateTime since) {
        return behaviorLogRepository.findByUserIdAndTypeSince(userId, eventType, since).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get events for a session — session-scoped ICS computation.
     */
    @Transactional(readOnly = true)
    public List<BehaviorEventResponse> getSessionEvents(Long sessionId) {
        return behaviorLogRepository.findBySessionId(sessionId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get last event timestamp for a user — used by ICS decay computation.
     */
    @Transactional(readOnly = true)
    public LocalDateTime getLastEventTimestamp(Long userId) {
        return behaviorLogRepository.findLastEventTimestamp(userId);
    }

    /**
     * Get event count for a user in a time window — activity level estimation.
     */
    @Transactional(readOnly = true)
    public long getEventCount(Long userId, LocalDateTime start, LocalDateTime end) {
        return behaviorLogRepository.countByUserIdBetween(userId, start, end);
    }

    /**
     * Get event count by type for temporal pattern extraction.
     */
    @Transactional(readOnly = true)
    public long getEventCountByType(Long userId, BehaviorLog.EventType eventType,
                                     LocalDateTime start, LocalDateTime end) {
        return behaviorLogRepository.countByUserIdAndTypeBetween(userId, eventType, start, end);
    }

    /**
     * Get available DNA dimensions for a user — which dimensions have data.
     */
    @Transactional(readOnly = true)
    public List<BehaviorLog.EventType> getAvailableDimensions(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        return behaviorLogRepository.findDistinctEventTypesByUserId(userId, since);
    }

    // ── INTERNAL HELPERS ──

    /**
     * Build a BehaviorLog entity from a BehaviorEvent DTO.
     * Enriches with: DNA dimension mapping, device classification, metadata JSON.
     */
    private BehaviorLog buildBehaviorLog(BehaviorEvent event) {
        User user = userRepository.getReferenceById(event.getUserId());
        Session session = sessionRepository.getReferenceById(event.getSessionId());

        // Map security event to DNA dimension
        BehaviorLog.EventType dnaDimension = mapToDNADimension(event.getEventType());

        // Build enriched metadata JSON
        String metadataJson = buildMetadataJson(event);

        return BehaviorLog.builder()
                .user(user)
                .session(session)
                .eventType(dnaDimension)
                .eventSubtype(event.getEventType().name())
                .eventPayload(metadataJson)
                .eventTimestamp(LocalDateTime.now())
                .clientIp(event.getIpAddress())
                .userAgent(truncate(event.getUserAgent(), 512))
                .build();
    }

    /**
     * Map SecurityEventType to DNA behavioral dimension.
     *
     * LOGIN/LOGOUT → TEMPORAL (temporal patterns: login times, session intervals)
     * UPLOAD/DOWNLOAD/DELETE → FILE_OP (file operation patterns)
     */
    private BehaviorLog.EventType mapToDNADimension(SecurityEventType securityEvent) {
        return switch (securityEvent) {
            case LOGIN, LOGOUT -> BehaviorLog.EventType.TEMPORAL;
            case UPLOAD, DOWNLOAD, DELETE -> BehaviorLog.EventType.FILE_OP;
        };
    }

    /**
     * Build enriched metadata JSON from the event context.
     * Includes: original event type, device classification, user agent parsing, etc.
     */
    private String buildMetadataJson(BehaviorEvent event) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("security_event_type", event.getEventType().name());
            metadata.put("device_type", event.getDeviceType() != null
                    ? event.getDeviceType() : classifyDevice(event.getUserAgent()));
            metadata.put("ip_address", event.getIpAddress());
            metadata.put("user_agent", truncate(event.getUserAgent(), 200));
            metadata.put("server_timestamp", LocalDateTime.now().toString());

            // Parse additional metadata if provided
            if (event.getEventMetadata() != null && !event.getEventMetadata().isBlank()) {
                try {
                    Map<String, Object> extra = objectMapper.readValue(
                            event.getEventMetadata(), Map.class);
                    metadata.put("extra", extra);
                } catch (JsonProcessingException ignored) {
                    metadata.put("extra_raw", event.getEventMetadata());
                }
            }

            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"metadata_serialization_failed\"}";
        }
    }

    /**
     * Classify device type from User-Agent string.
     */
    public String classifyDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return DeviceType.UNKNOWN.name();

        String ua = userAgent.toLowerCase();

        // Bot detection
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")
                || ua.contains("scraper") || ua.contains("headless")) {
            return DeviceType.BOT.name();
        }

        // Mobile detection
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")
                || ua.contains("ipod") || ua.contains("blackberry") || ua.contains("opera mini")
                || ua.contains("iemobile") || ua.contains("windows phone")) {
            return DeviceType.MOBILE.name();
        }

        // Tablet detection
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("kindle")
                || ua.contains("silk") || ua.contains("playbook")) {
            return DeviceType.TABLET.name();
        }

        return DeviceType.DESKTOP.name();
    }

    /**
     * Map BehaviorLog entity to BehaviorEventResponse DTO.
     */
    private BehaviorEventResponse toResponse(BehaviorLog entity) {
        return BehaviorEventResponse.builder()
                .id(entity.getId())
                .userId(entity.getUser().getId())
                .sessionId(entity.getSession().getId())
                .eventType(entity.getEventType().name())
                .eventSubtype(entity.getEventSubtype())
                .clientIp(entity.getClientIp())
                .userAgent(entity.getUserAgent())
                .eventTimestamp(entity.getEventTimestamp())
                .serverTimestamp(entity.getServerTimestamp())
                .build();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}
