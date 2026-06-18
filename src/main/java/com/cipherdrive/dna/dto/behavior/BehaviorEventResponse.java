package com.cipherdrive.dna.dto.behavior;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for behavioral event query response.
 * Returned by the behavior log API for admin dashboard and DNA engine queries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BehaviorEventResponse {

    private Long id;
    private Long userId;
    private Long sessionId;
    private String eventType;
    private String eventSubtype;
    private String clientIp;
    private String userAgent;
    private LocalDateTime eventTimestamp;
    private LocalDateTime serverTimestamp;
}
