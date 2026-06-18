package com.cipherdrive.dna.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Raw behavioral event telemetry for Digital DNA extraction.
 * Maps to: behavior_logs table
 *
 * This is the HIGHEST VOLUME table in the system.
 * Each event feeds into the Digital DNA Engine's 7-stage pipeline.
 * Index: ix_behavior_user_time is the HOTTEST index in the entire schema.
 *
 * Two category of events:
 *   1. SecurityEventType: LOGIN, LOGOUT, UPLOAD, DOWNLOAD, DELETE
 *      → Captured automatically by BehaviorLogInterceptor
 *      → Maps to FILE_OP / TEMPORAL / DEVICE / CONTEXT dimensions
 *
 *   2. DNADimensionType: KEYSTROKE, MOUSE, NAVIGATION, FILE_OP, TEMPORAL, DEVICE, CONTEXT
 *      → Captured by client-side SDK (browser telemetry)
 *      → Raw material for DNA vector extraction
 */
@Entity
@Table(name = "behavior_logs", indexes = {
        @Index(name = "ix_behavior_user_time", columnList = "user_id, event_timestamp DESC"),
        @Index(name = "ix_behavior_session_id", columnList = "session_id"),
        @Index(name = "ix_behavior_type_time", columnList = "event_type, event_timestamp DESC"),
        @Index(name = "ix_behavior_user_type", columnList = "user_id, event_type, event_timestamp DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "session"})
public class BehaviorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_behavior_logs_user_id"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, foreignKey = @ForeignKey(name = "fk_behavior_logs_session_id"))
    private Session session;

    // ── Event Classification ──

    @Column(name = "event_type", nullable = false, length = 64)
    @Enumerated(EnumType.STRING)
    private EventType eventType;

    @Column(name = "event_subtype", nullable = false, length = 64)
    private String eventSubtype;

    // ── Event Data ──

    @Column(name = "event_payload", nullable = false, columnDefinition = "JSON")
    private String eventPayload;

    // ── Timestamps ──

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @CreationTimestamp
    @Column(name = "server_timestamp", nullable = false, updatable = false)
    private LocalDateTime serverTimestamp;

    // ── Client Context ──

    @Column(name = "client_ip", nullable = false, length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    // ── Enum: 7 Digital DNA Behavioral Dimensions ──
    // Also doubles as security event classifier:
    //   LOGIN/LOGOUT → TEMPORAL dimension
    //   UPLOAD/DOWNLOAD/DELETE → FILE_OP dimension

    public enum EventType {
        /** Keystroke dynamics: dwell time, flight time */
        KEYSTROKE,
        /** Mouse dynamics: speed, acceleration, click patterns */
        MOUSE,
        /** Navigation patterns: page frequency, transition entropy */
        NAVIGATION,
        /** File operation patterns: upload/download/delete frequency, type distribution */
        FILE_OP,
        /** Temporal patterns: login times, session intervals, hour/day distribution */
        TEMPORAL,
        /** Device fingerprint: browser hash, OS hash, screen resolution, device type */
        DEVICE,
        /** Contextual metadata: location hash, network type, IP classification */
        CONTEXT
    }
}
