-- ============================================================
-- CipherDrive-DNA MVP — MySQL Database Implementation
-- MySQL 8.0+ | InnoDB | utf8mb4 | COLLATE utf8mb4_unicode_ci
-- ============================================================
-- Author  : DNA-TRUST V2 Team
-- Version : 2.0
-- Date    : 2025
-- ============================================================
-- EXECUTION ORDER:
--   1. Run this file entirely on MySQL Workbench
--   2. All tables are created in dependency order
--   3. Seed data and stored procedures at the end
-- ============================================================

-- ============================================================
-- 0. DATABASE INITIALIZATION
-- ============================================================

CREATE DATABASE IF NOT EXISTS cipherdrive_dna
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE cipherdrive_dna;

SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- ============================================================
-- 1. TABLE: roles
-- Purpose: RBAC role definitions for CipherDrive-DNA access control
-- ============================================================

CREATE TABLE roles (
    id              BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment role ID',
    role_name       VARCHAR(64)        NOT NULL                 COMMENT 'Unique role identifier (e.g. ROLE_ADMIN, ROLE_USER)',
    description     VARCHAR(255)       NULL                     COMMENT 'Human-readable role description',
    permission_mask INT UNSIGNED       NOT NULL DEFAULT 0       COMMENT 'Bitwise permission mask for fine-grained access',
    is_active       TINYINT(1)         NOT NULL DEFAULT 1       COMMENT 'Soft-delete flag: 1=active, 0=disabled',
    created_at      DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                               COMMENT 'Row creation timestamp (millisecond precision)',
    updated_at      DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                               ON UPDATE CURRENT_TIMESTAMP(3)
                                                               COMMENT 'Last modification timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT uq_roles_role_name UNIQUE (role_name),
    CONSTRAINT ck_roles_permission_mask CHECK (permission_mask >= 0)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='RBAC role definitions for CipherDrive-DNA';

-- ============================================================
-- 2. TABLE: users
-- Purpose: Core user identity for CipherDrive-DNA platform
-- ============================================================

CREATE TABLE users (
    id                  BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment user ID',
    username            VARCHAR(64)        NOT NULL                 COMMENT 'Unique login identifier (alphanumeric + underscore)',
    email               VARCHAR(255)       NOT NULL                 COMMENT 'Unique email address for notifications and recovery',
    password_hash       VARCHAR(255)       NOT NULL                 COMMENT 'Argon2id hash (m=65536, t=3, p=4) of user password',
    role_id             BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → roles.id — assigned RBAC role',
    is_enabled          TINYINT(1)         NOT NULL DEFAULT 1       COMMENT 'Account enabled flag: 1=active, 0=disabled by admin',
    is_locked           TINYINT(1)         NOT NULL DEFAULT 0       COMMENT 'Account lock flag: 1=locked (brute-force), 0=normal',
    failed_login_count  TINYINT UNSIGNED   NOT NULL DEFAULT 0       COMMENT 'Consecutive failed login attempts (reset on success)',
    locked_until        DATETIME(3)        NULL                     COMMENT 'Auto-unlock timestamp after lockout period',
    last_login_at       DATETIME(3)        NULL                     COMMENT 'Last successful authentication timestamp',
    last_login_ip       VARCHAR(45)        NULL                     COMMENT 'IPv4/IPv6 address of last successful login',
    created_at          DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'Account creation timestamp',
    updated_at          DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   ON UPDATE CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'Last profile modification timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username  UNIQUE (username),
    CONSTRAINT uq_users_email     UNIQUE (email),
    CONSTRAINT fk_users_role_id   FOREIGN KEY (role_id)
                                   REFERENCES roles(id)
                                   ON UPDATE CASCADE
                                   ON DELETE RESTRICT,
    CONSTRAINT ck_users_username_format CHECK (
        username REGEXP '^[a-zA-Z][a-zA-Z0-9_]{2,63}$'
    ),
    CONSTRAINT ck_users_email_format CHECK (
        email LIKE '%@%.%'
    ),
    CONSTRAINT ck_users_failed_login CHECK (
        failed_login_count BETWEEN 0 AND 10
    )

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Core user identity for CipherDrive-DNA platform';

-- ── Indexes: users ──
CREATE INDEX ix_users_role_id ON users (role_id);
CREATE INDEX ix_users_is_enabled_locked ON users (is_enabled, is_locked);
CREATE INDEX ix_users_last_login_at ON users (last_login_at);

-- ============================================================
-- 3. TABLE: sessions
-- Purpose: JWT session tracking and revocation management
-- ============================================================

CREATE TABLE sessions (
    id                  BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment session ID',
    user_id             BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → users.id — session owner',
    session_token       VARCHAR(255)       NOT NULL                 COMMENT 'SHA-256 hash of JWT jti claim (not the JWT itself)',
    refresh_token       VARCHAR(255)       NULL                     COMMENT 'SHA-256 hash of refresh token for renewal flow',
    ip_address          VARCHAR(45)        NOT NULL                 COMMENT 'Client IPv4/IPv6 address at session creation',
    user_agent          VARCHAR(512)       NULL                     COMMENT 'Browser/device User-Agent string for fingerprinting',
    device_fingerprint  VARCHAR(128)       NULL                     COMMENT 'Browser fingerprint hash (Canvas+WebGL+Audio)',
    is_revoked          TINYINT(1)         NOT NULL DEFAULT 0       COMMENT 'Revocation flag: 1=revoked (logout/security event), 0=valid',
    revoke_reason       VARCHAR(64)        NULL                     COMMENT 'Revocation cause: LOGOUT|SECURITY_EVENT|ADMIN|EXPIRED',
    expires_at          DATETIME(3)        NOT NULL                 COMMENT 'JWT expiration timestamp — used for TTL validation',
    created_at          DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'Session creation timestamp',
    updated_at          DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   ON UPDATE CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'Last session update timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT uq_sessions_session_token  UNIQUE (session_token),
    CONSTRAINT fk_sessions_user_id        FOREIGN KEY (user_id)
                                            REFERENCES users(id)
                                            ON UPDATE CASCADE
                                            ON DELETE CASCADE,
    CONSTRAINT ck_sessions_revoke_reason  CHECK (
        revoke_reason IS NULL
        OR revoke_reason IN ('LOGOUT','SECURITY_EVENT','ADMIN','EXPIRED')
    ),
    CONSTRAINT ck_sessions_expires_future CHECK (
        expires_at > created_at
    )

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='JWT session tracking and revocation management';

-- ── Indexes: sessions ──
CREATE INDEX ix_sessions_user_id ON sessions (user_id);
CREATE INDEX ix_sessions_expires_at ON sessions (expires_at);
CREATE INDEX ix_sessions_user_revoked ON sessions (user_id, is_revoked);

-- ============================================================
-- 4. TABLE: files
-- Purpose: Cloud file storage metadata (actual blobs in MinIO)
-- ============================================================

CREATE TABLE files (
    id                  BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment file ID',
    user_id             BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → users.id — file owner',
    file_name           VARCHAR(255)       NOT NULL                 COMMENT 'Original filename as uploaded by user',
    file_path           VARCHAR(512)       NOT NULL                 COMMENT 'MinIO object key: userId/uuid/filename',
    file_size           BIGINT UNSIGNED    NOT NULL                 COMMENT 'File size in bytes',
    mime_type           VARCHAR(128)       NOT NULL DEFAULT 'application/octet-stream'
                                                                   COMMENT 'MIME type detected from upload',
    storage_bucket      VARCHAR(128)       NOT NULL DEFAULT 'cipherdrive-files'
                                                                   COMMENT 'MinIO bucket name',
    checksum_sha256     CHAR(64)           NOT NULL                 COMMENT 'SHA-256 hex digest for integrity verification',
    encryption_key_ref  VARCHAR(255)       NOT NULL                 COMMENT 'Reference to DIPL AES-256-GCM key in vault',
    encryption_iv       VARCHAR(64)        NOT NULL                 COMMENT 'Base64-encoded 96-bit IV for AES-256-GCM',
    is_encrypted        TINYINT(1)         NOT NULL DEFAULT 1       COMMENT 'Client-side encryption flag: 1=encrypted, 0=plain',
    is_deleted          TINYINT(1)         NOT NULL DEFAULT 0       COMMENT 'Soft-delete flag: 1=trash, 0=active',
    deleted_at          DATETIME(3)        NULL                     COMMENT 'Soft-delete timestamp for retention policy',
    version             INT UNSIGNED       NOT NULL DEFAULT 1       COMMENT 'File version counter for versioning support',
    parent_folder_id    BIGINT UNSIGNED    NULL                     COMMENT 'Self-referencing FK for folder hierarchy (NULL=root)',
    created_at          DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'File upload timestamp',
    updated_at          DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   ON UPDATE CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'Last file modification timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT fk_files_user_id          FOREIGN KEY (user_id)
                                            REFERENCES users(id)
                                            ON UPDATE CASCADE
                                            ON DELETE CASCADE,
    CONSTRAINT fk_files_parent_folder    FOREIGN KEY (parent_folder_id)
                                            REFERENCES files(id)
                                            ON UPDATE CASCADE
                                            ON DELETE SET NULL,
    CONSTRAINT ck_files_file_size        CHECK (file_size > 0 AND file_size <= 5368709120),
    CONSTRAINT ck_files_version          CHECK (version >= 1),
    CONSTRAINT ck_files_mime_type        CHECK (mime_type <> '')

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Cloud file storage metadata (blobs in MinIO)';

-- ── Indexes: files ──
CREATE INDEX ix_files_user_id ON files (user_id);
CREATE INDEX ix_files_user_deleted ON files (user_id, is_deleted, created_at);
CREATE INDEX ix_files_parent_folder ON files (parent_folder_id, is_deleted);
CREATE INDEX ix_files_checksum ON files (checksum_sha256);
CREATE INDEX ix_files_deleted_at ON files (deleted_at);

-- ============================================================
-- 5. TABLE: behavior_logs
-- Purpose: Raw behavioral event telemetry for Digital DNA extraction
-- ============================================================

CREATE TABLE behavior_logs (
    id                  BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment log ID',
    user_id             BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → users.id — behavior origin user',
    session_id          BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → sessions.id — originating session',
    event_type          VARCHAR(64)        NOT NULL                 COMMENT 'Event category: KEYSTROKE|MOUSE|NAVIGATION|FILE_OP|TEMPORAL|DEVICE|CONTEXT',
    event_subtype       VARCHAR(64)        NOT NULL                 COMMENT 'Granular event: KEY_PRESS|KEY_RELEASE|MOUSE_MOVE|MOUSE_CLICK|...',
    event_payload       JSON               NOT NULL                 COMMENT 'Event-specific data (keystroke timing, mouse coords, etc.)',
    event_timestamp     DATETIME(3)        NOT NULL                 COMMENT 'Client-side event timestamp (ms precision from browser)',
    server_timestamp    DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'Server ingestion timestamp',
    client_ip           VARCHAR(45)        NOT NULL                 COMMENT 'Client IP at event time',
    user_agent          VARCHAR(512)       NULL                     COMMENT 'Browser User-Agent at event time',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT fk_behavior_logs_user_id    FOREIGN KEY (user_id)
                                             REFERENCES users(id)
                                             ON UPDATE CASCADE
                                             ON DELETE CASCADE,
    CONSTRAINT fk_behavior_logs_session_id FOREIGN KEY (session_id)
                                             REFERENCES sessions(id)
                                             ON UPDATE CASCADE
                                             ON DELETE CASCADE,
    CONSTRAINT ck_behavior_event_type      CHECK (
        event_type IN ('KEYSTROKE','MOUSE','NAVIGATION','FILE_OP','TEMPORAL','DEVICE','CONTEXT')
    )

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Raw behavioral event telemetry for Digital DNA extraction';

-- ── Indexes: behavior_logs ──
-- PRIMARY: DNA extraction pipeline — fetches user events sorted by time
CREATE INDEX ix_behavior_user_time ON behavior_logs (user_id, event_timestamp DESC);
-- FK + session-scoped event retrieval for ICS computation
CREATE INDEX ix_behavior_session_id ON behavior_logs (session_id);
-- Analytics: cross-user filtering by event type
CREATE INDEX ix_behavior_type_time ON behavior_logs (event_type, event_timestamp DESC);
-- Per-dimension extraction: each DNA dimension queries its own event_type
CREATE INDEX ix_behavior_user_type ON behavior_logs (user_id, event_type, event_timestamp DESC);

-- ============================================================
-- 6. TABLE: digital_dna
-- Purpose: Computed Digital DNA profile snapshots per user per session
-- ============================================================

CREATE TABLE digital_dna (
    id                      BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment DNA ID',
    user_id                 BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → users.id — profile owner',
    session_id              BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → sessions.id — computation session',
    computation_window_ms   INT UNSIGNED       NOT NULL                 COMMENT 'Aggregation window in milliseconds (default: 300000 = 5min)',

    -- ── 7 Behavioral Dimension Vectors (JSON) ──
    keystroke_vector        JSON               NOT NULL                 COMMENT 'Keystroke dynamics: {mean_dwell, std_dwell, mean_flight, std_flight, ...}',
    mouse_vector            JSON               NOT NULL                 COMMENT 'Mouse dynamics: {mean_speed, std_speed, mean_accel, click_interval, ...}',
    navigation_vector       JSON               NOT NULL                 COMMENT 'Navigation patterns: {page_freq, transition_entropy, session_depth, ...}',
    file_op_vector          JSON               NOT NULL                 COMMENT 'File operation patterns: {op_freq, op_type_dist, file_size_pref, ...}',
    temporal_vector         JSON               NOT NULL                 COMMENT 'Temporal patterns: {hour_dist, day_dist, session_interval, ...}',
    device_vector           JSON               NOT NULL                 COMMENT 'Device fingerprint: {browser_hash, os_hash, screen_res, timezone, ...}',
    context_vector          JSON               NOT NULL                 COMMENT 'Contextual metadata: {location_hash, network_type, ip_class, ...}',

    -- ── Composite Scores ──
    combined_vector         JSON               NOT NULL                 COMMENT 'Weighted combination of all 7 vectors using AHP+Entropy weights',
    cosine_similarity       DECIMAL(10,8)      NOT NULL                 COMMENT 'Cosine similarity between current and baseline DNA profile',
    drift_score             DECIMAL(10,8)      NOT NULL                 COMMENT 'Aggregated drift score: 0=identical, 1=complete mismatch',
    drift_regime            VARCHAR(16)        NOT NULL                 COMMENT 'Classification: NORMAL|LOW_DRIFT|HIGH_DRIFT|ANOMALY',

    -- ── Weight Configuration ──
    ahp_weights             JSON               NOT NULL                 COMMENT 'AHP-derived weights for 7 dimensions',
    entropy_weights         JSON               NOT NULL                 COMMENT 'Information entropy weights for 7 dimensions',
    composite_weights       JSON               NOT NULL                 COMMENT 'Final AHP×Entropy composite weights',

    -- ── Metadata ──
    baseline_dna_id         BIGINT UNSIGNED    NULL                     COMMENT 'Reference baseline DNA profile ID (self-referencing FK)',
    sample_count            INT UNSIGNED       NOT NULL                 COMMENT 'Number of behavior_log samples used in computation',
    algorithm_version       VARCHAR(16)        NOT NULL DEFAULT '2.0'   COMMENT 'DNA engine algorithm version for reproducibility',
    computed_at             DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'DNA computation completion timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT fk_digital_dna_user_id     FOREIGN KEY (user_id)
                                            REFERENCES users(id)
                                            ON UPDATE CASCADE
                                            ON DELETE CASCADE,
    CONSTRAINT fk_digital_dna_session_id  FOREIGN KEY (session_id)
                                            REFERENCES sessions(id)
                                            ON UPDATE CASCADE
                                            ON DELETE CASCADE,
    CONSTRAINT fk_digital_dna_baseline    FOREIGN KEY (baseline_dna_id)
                                            REFERENCES digital_dna(id)
                                            ON UPDATE CASCADE
                                            ON DELETE SET NULL,
    CONSTRAINT ck_dna_cosine_range        CHECK (cosine_similarity BETWEEN -1.0 AND 1.0),
    CONSTRAINT ck_dna_drift_range         CHECK (drift_score BETWEEN 0.0 AND 1.0),
    CONSTRAINT ck_dna_regime              CHECK (
        drift_regime IN ('NORMAL','LOW_DRIFT','HIGH_DRIFT','ANOMALY')
    ),
    CONSTRAINT ck_dna_sample_count        CHECK (sample_count > 0)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Computed Digital DNA profile snapshots';

-- ── Indexes: digital_dna ──
CREATE INDEX ix_dna_user_computed ON digital_dna (user_id, computed_at DESC);
CREATE INDEX ix_dna_session_id ON digital_dna (session_id);
CREATE INDEX ix_dna_drift_regime ON digital_dna (drift_regime, computed_at DESC);
CREATE INDEX ix_dna_cosine_sim ON digital_dna (cosine_similarity);
CREATE INDEX ix_dna_baseline ON digital_dna (baseline_dna_id);

-- ============================================================
-- 7. TABLE: identity_confidence
-- Purpose: ICS (Identity Confidence Score) snapshots with temporal decay
-- ============================================================

CREATE TABLE identity_confidence (
    id                      BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment ICS ID',
    user_id                 BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → users.id — evaluated user',
    session_id              BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → sessions.id — evaluated session',
    dna_profile_id          BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → digital_dna.id — source DNA profile',

    -- ── ICS Score Components ──
    ics_score               DECIMAL(5,2)       NOT NULL                 COMMENT 'Composite ICS: 0.00–100.00 (100=max confidence)',
    behavioral_score        DECIMAL(5,2)       NOT NULL                 COMMENT 'Behavioral DNA contribution to ICS',
    device_score            DECIMAL(5,2)       NOT NULL                 COMMENT 'Device fingerprint match contribution',
    temporal_score          DECIMAL(5,2)       NOT NULL                 COMMENT 'Temporal pattern match contribution',
    context_score           DECIMAL(5,2)       NOT NULL                 COMMENT 'Contextual metadata match contribution',

    -- ── Temporal Decay ──
    decay_factor            DECIMAL(8,6)       NOT NULL                 COMMENT 'Exponential decay multiplier: exp(-lambda * delta_t)',
    decay_lambda            DECIMAL(6,4)       NOT NULL DEFAULT 0.0500  COMMENT 'Decay rate parameter (events per hour)',
    time_since_last_event   DECIMAL(12,2)      NOT NULL                 COMMENT 'Milliseconds since last behavioral event',
    pre_decay_score         DECIMAL(5,2)       NOT NULL                 COMMENT 'ICS score BEFORE temporal decay application',
    post_decay_score        DECIMAL(5,2)       NOT NULL                 COMMENT 'ICS score AFTER temporal decay application',

    -- ── Recovery Mechanism ──
    recovery_rate           DECIMAL(6,4)       NOT NULL DEFAULT 0.0200  COMMENT 'Score recovery rate per verified action',
    consecutive_verified    INT UNSIGNED       NOT NULL DEFAULT 0       COMMENT 'Consecutive verified actions for recovery bonus',

    -- ── Thresholds & Classification ──
    trust_level             VARCHAR(16)        NOT NULL                 COMMENT 'Classification: TRUSTED|ACCEPTED|CHALLENGED|REJECTED',
    is_below_threshold      TINYINT(1)         NOT NULL DEFAULT 0       COMMENT 'Flag: 1=ICS below minimum trust threshold',
    challenge_triggered     TINYINT(1)         NOT NULL DEFAULT 0       COMMENT 'Flag: 1=MFA/challenge was triggered for this ICS',

    -- ── Metadata ──
    computation_time_ms     INT UNSIGNED       NOT NULL                 COMMENT 'ICS computation duration in milliseconds',
    algorithm_version       VARCHAR(16)        NOT NULL DEFAULT '2.0'   COMMENT 'ICS algorithm version',
    computed_at             DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'ICS computation timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT fk_ics_user_id           FOREIGN KEY (user_id)
                                           REFERENCES users(id)
                                           ON UPDATE CASCADE
                                           ON DELETE CASCADE,
    CONSTRAINT fk_ics_session_id        FOREIGN KEY (session_id)
                                           REFERENCES sessions(id)
                                           ON UPDATE CASCADE
                                           ON DELETE CASCADE,
    CONSTRAINT fk_ics_dna_profile_id    FOREIGN KEY (dna_profile_id)
                                           REFERENCES digital_dna(id)
                                           ON UPDATE CASCADE
                                           ON DELETE RESTRICT,
    CONSTRAINT ck_ics_score_range       CHECK (ics_score BETWEEN 0.00 AND 100.00),
    CONSTRAINT ck_ics_component_range   CHECK (
        behavioral_score BETWEEN 0.00 AND 100.00
        AND device_score BETWEEN 0.00 AND 100.00
        AND temporal_score BETWEEN 0.00 AND 100.00
        AND context_score BETWEEN 0.00 AND 100.00
    ),
    CONSTRAINT ck_ics_decay_factor      CHECK (decay_factor BETWEEN 0.0 AND 1.0),
    CONSTRAINT ck_ics_trust_level       CHECK (
        trust_level IN ('TRUSTED','ACCEPTED','CHALLENGED','REJECTED')
    )

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ICS snapshots with temporal decay and recovery';

-- ── Indexes: identity_confidence ──
CREATE INDEX ix_ics_user_computed ON identity_confidence (user_id, computed_at DESC);
CREATE INDEX ix_ics_session_id ON identity_confidence (session_id);
CREATE INDEX ix_ics_trust_level ON identity_confidence (trust_level, computed_at DESC);
CREATE INDEX ix_ics_below_threshold ON identity_confidence (is_below_threshold, user_id, computed_at DESC);
CREATE INDEX ix_ics_dna_profile ON identity_confidence (dna_profile_id);

-- ============================================================
-- 8. TABLE: trust_evolution
-- Purpose: TEM (Trust Evolution Model) snapshots — OU process parameters
-- ============================================================

CREATE TABLE trust_evolution (
    id                      BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT  COMMENT 'Primary key — auto-increment TEM ID',
    user_id                 BIGINT UNSIGNED    NOT NULL                 COMMENT 'FK → users.id — evaluated user',

    -- ── Ornstein-Uhlenbeck Parameters ──
    theta                   DECIMAL(10,6)      NOT NULL                 COMMENT 'Mean-reversion speed (1/hour): pulls trust toward mu',
    mu                      DECIMAL(5,2)       NOT NULL                 COMMENT 'Long-term mean trust level (0–100)',
    sigma                   DECIMAL(10,6)      NOT NULL                 COMMENT 'Volatility/diffusion coefficient',
    current_trust           DECIMAL(5,2)       NOT NULL                 COMMENT 'Current trust level: X(t) from Euler-Maruyama solver',

    -- ── Euler-Maruyama Solver State ──
    dt_hours                DECIMAL(8,4)       NOT NULL                 COMMENT 'Time step in hours for EM discretization',
    dw_value                DECIMAL(10,8)      NULL                     COMMENT 'Last Wiener increment: sqrt(dt) * N(0,1)',
    em_iteration            INT UNSIGNED       NOT NULL DEFAULT 0       COMMENT 'Current EM solver iteration counter',

    -- ── Trust Velocity & Acceleration ──
    trust_velocity          DECIMAL(10,6)      NOT NULL                 COMMENT 'dX/dt — first derivative of trust trajectory',
    trust_acceleration      DECIMAL(10,6)      NOT NULL                 COMMENT 'd2X/dt2 — second derivative of trust trajectory',

    -- ── Trust Degradation Index ──
    tdi_composite           DECIMAL(10,6)      NOT NULL                 COMMENT 'Composite TDI: weighted velocity + acceleration',
    tdi_velocity_weight     DECIMAL(4,2)       NOT NULL DEFAULT 0.60    COMMENT 'Weight for velocity in TDI composite',
    tdi_acceleration_weight DECIMAL(4,2)       NOT NULL DEFAULT 0.40    COMMENT 'Weight for acceleration in TDI composite',

    -- ── Reversion Alignment ──
    reversion_alignment     DECIMAL(10,6)      NOT NULL                 COMMENT 'Alignment metric: theta * (mu - X(t))',
    is_reverting            TINYINT(1)         NOT NULL DEFAULT 0       COMMENT 'Flag: 1=trust moving toward mu (reverting)',

    -- ── Regime Classification ──
    regime                  VARCHAR(16)        NOT NULL                 COMMENT 'Current regime: STABLE|DRIFTING|DEGRADING|RECOVERING',
    previous_regime         VARCHAR(16)        NULL                     COMMENT 'Previous regime for hysteresis transition detection',
    regime_transition_count INT UNSIGNED       NOT NULL DEFAULT 0       COMMENT 'Count of regime transitions (for hysteresis debounce)',

    -- ── OLS-MLE Parameter Estimation ──
    estimation_window       INT UNSIGNED       NOT NULL                 COMMENT 'Number of ICS observations used for parameter fit',
    ols_r_squared           DECIMAL(8,6)       NULL                     COMMENT 'R-squared of OLS regression fit for theta estimation',
    theta_std_error         DECIMAL(10,6)      NULL                     COMMENT 'Standard error of theta estimate',

    -- ── Metadata ──
    computation_time_ms     INT UNSIGNED       NOT NULL                 COMMENT 'TEM computation duration in milliseconds',
    algorithm_version       VARCHAR(16)        NOT NULL DEFAULT '2.0'   COMMENT 'TEM algorithm version',
    computed_at             DATETIME(3)        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                                   COMMENT 'TEM computation timestamp',

    -- ── Constraints ──
    PRIMARY KEY (id),
    CONSTRAINT fk_tem_user_id             FOREIGN KEY (user_id)
                                             REFERENCES users(id)
                                             ON UPDATE CASCADE
                                             ON DELETE CASCADE,
    CONSTRAINT ck_tem_theta_positive      CHECK (theta > 0),
    CONSTRAINT ck_tem_mu_range            CHECK (mu BETWEEN 0.00 AND 100.00),
    CONSTRAINT ck_tem_sigma_positive      CHECK (sigma > 0),
    CONSTRAINT ck_tem_trust_range         CHECK (current_trust BETWEEN 0.00 AND 100.00),
    CONSTRAINT ck_tem_dt_positive         CHECK (dt_hours > 0),
    CONSTRAINT ck_tem_tdi_weights         CHECK (
        tdi_velocity_weight + tdi_acceleration_weight = 1.00
    ),
    CONSTRAINT ck_tem_regime              CHECK (
        regime IN ('STABLE','DRIFTING','DEGRADING','RECOVERING')
    ),
    CONSTRAINT ck_tem_previous_regime     CHECK (
        previous_regime IS NULL
        OR previous_regime IN ('STABLE','DRIFTING','DEGRADING','RECOVERING')
    ),
    CONSTRAINT ck_tem_estimation_window   CHECK (estimation_window >= 5)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='TEM snapshots — Ornstein-Uhlenbeck trust evolution';

-- ── Indexes: trust_evolution ──
CREATE INDEX ix_tem_user_computed ON trust_evolution (user_id, computed_at DESC);
CREATE INDEX ix_tem_regime ON trust_evolution (regime, computed_at DESC);
CREATE INDEX ix_tem_mu ON trust_evolution (user_id, mu, computed_at DESC);
CREATE INDEX ix_tem_regime_transition ON trust_evolution (user_id, regime, previous_regime);
CREATE INDEX ix_tem_current_trust ON trust_evolution (current_trust);

-- ============================================================
-- SEED DATA: Initial RBAC roles
-- ============================================================

INSERT INTO roles (role_name, description, permission_mask) VALUES
('ROLE_ADMIN',  'System Administrator — full access to all features and user management', 0xFFFFFFFF),
('ROLE_USER',   'Standard User — file storage, behavioral logging, DNA profiling',        0x0000FFFF),
('ROLE_AUDITOR','Security Auditor — read-only access to logs and trust metrics',           0x0000FF00);

-- ============================================================
-- STORED PROCEDURE: sp_compute_ics_with_decay
-- Purpose: Compute ICS with temporal decay for a given user
-- ============================================================

DELIMITER //

CREATE PROCEDURE sp_compute_ics_with_decay(
    IN p_user_id BIGINT UNSIGNED,
    IN p_session_id BIGINT UNSIGNED,
    IN p_dna_profile_id BIGINT UNSIGNED,
    IN p_behavioral_score DECIMAL(5,2),
    IN p_device_score DECIMAL(5,2),
    IN p_temporal_score DECIMAL(5,2),
    IN p_context_score DECIMAL(5,2),
    IN p_time_since_last_ms DECIMAL(12,2),
    IN p_decay_lambda DECIMAL(6,4),
    IN p_recovery_rate DECIMAL(6,4),
    IN p_consecutive_verified INT UNSIGNED
)
BEGIN
    DECLARE v_pre_decay DECIMAL(5,2);
    DECLARE v_decay_factor DECIMAL(8,6);
    DECLARE v_post_decay DECIMAL(5,2);
    DECLARE v_ics_score DECIMAL(5,2);
    DECLARE v_trust_level VARCHAR(16);
    DECLARE v_below_threshold TINYINT(1);

    -- Weighted composite (AHP weights: B=0.35, D=0.20, T=0.25, C=0.20)
    SET v_pre_decay = LEAST(100.00,
        ROUND(p_behavioral_score * 0.35
            + p_device_score * 0.20
            + p_temporal_score * 0.25
            + p_context_score * 0.20, 2)
    );

    -- Temporal decay: exp(-lambda * delta_t_hours)
    SET v_decay_factor = EXP(-p_decay_lambda * (p_time_since_last_ms / 3600000.0));
    SET v_post_decay = ROUND(v_pre_decay * v_decay_factor, 2);

    -- Recovery bonus: +recovery_rate * consecutive_verified (capped at 10)
    SET v_ics_score = LEAST(100.00, ROUND(v_post_decay
        + p_recovery_rate * LEAST(p_consecutive_verified, 10), 2));

    -- Trust level classification
    SET v_below_threshold = IF(v_ics_score < 40.00, 1, 0);
    IF v_ics_score >= 80.00 THEN
        SET v_trust_level = 'TRUSTED';
    ELSEIF v_ics_score >= 60.00 THEN
        SET v_trust_level = 'ACCEPTED';
    ELSEIF v_ics_score >= 40.00 THEN
        SET v_trust_level = 'CHALLENGED';
    ELSE
        SET v_trust_level = 'REJECTED';
    END IF;

    -- Insert ICS snapshot
    INSERT INTO identity_confidence (
        user_id, session_id, dna_profile_id,
        ics_score, behavioral_score, device_score, temporal_score, context_score,
        decay_factor, decay_lambda, time_since_last_event,
        pre_decay_score, post_decay_score,
        recovery_rate, consecutive_verified,
        trust_level, is_below_threshold, challenge_triggered,
        computation_time_ms, algorithm_version
    ) VALUES (
        p_user_id, p_session_id, p_dna_profile_id,
        v_ics_score, p_behavioral_score, p_device_score, p_temporal_score, p_context_score,
        v_decay_factor, p_decay_lambda, p_time_since_last_ms,
        v_pre_decay, v_post_decay,
        p_recovery_rate, p_consecutive_verified,
        v_trust_level, v_below_threshold, v_below_threshold,
        0, '2.0'
    );
END //

DELIMITER ;

-- ============================================================
-- STORED PROCEDURE: sp_tem_euler_maruyama_step
-- Purpose: Execute one Euler-Maruyama discretization step for TEM
-- ============================================================

DELIMITER //

CREATE PROCEDURE sp_tem_euler_maruyama_step(
    IN p_user_id BIGINT UNSIGNED,
    IN p_theta DECIMAL(10,6),
    IN p_mu DECIMAL(5,2),
    IN p_sigma DECIMAL(10,6),
    IN p_current_trust DECIMAL(5,2),
    IN p_dt_hours DECIMAL(8,4),
    IN p_estimation_window INT UNSIGNED,
    IN p_previous_regime VARCHAR(16)
)
BEGIN
    DECLARE v_dw DECIMAL(10,8);
    DECLARE v_dX DECIMAL(10,6);
    DECLARE v_new_trust DECIMAL(5,2);
    DECLARE v_velocity DECIMAL(10,6);
    DECLARE v_acceleration DECIMAL(10,6);
    DECLARE v_tdi DECIMAL(10,6);
    DECLARE v_reversion_align DECIMAL(10,6);
    DECLARE v_is_reverting TINYINT(1);
    DECLARE v_regime VARCHAR(16);
    DECLARE v_transition_count INT UNSIGNED;

    -- Wiener increment: sqrt(dt) * N(0,1) — Central Limit Theorem approximation
    SET v_dw = SQRT(p_dt_hours) * ((RAND() + RAND() + RAND() - 1.5) / 0.866025);

    -- Euler-Maruyama: dX = theta*(mu - X)*dt + sigma*sqrt(dt)*dW
    SET v_dX = p_theta * (p_mu - p_current_trust) * p_dt_hours + p_sigma * v_dw;
    SET v_new_trust = LEAST(100.00, GREATEST(0.00, ROUND(p_current_trust + v_dX, 2)));

    -- Trust velocity: dX/dt
    SET v_velocity = ROUND(v_dX / p_dt_hours, 6);

    -- Trust acceleration: approximate d2X/dt2 using finite difference
    SET v_acceleration = ROUND(-p_theta * v_velocity, 6);

    -- TDI composite: 0.6*|velocity| + 0.4*|acceleration|
    SET v_tdi = ROUND(0.60 * ABS(v_velocity) + 0.40 * ABS(v_acceleration), 6);

    -- Reversion alignment
    SET v_reversion_align = ROUND(p_theta * (p_mu - v_new_trust), 6);
    SET v_is_reverting = IF(SIGN(p_mu - p_current_trust) = SIGN(v_new_trust - p_current_trust), 0, 1);

    -- Regime classification with hysteresis
    IF v_new_trust >= 70.00 AND ABS(v_velocity) < 2.0 THEN
        SET v_regime = 'STABLE';
    ELSEIF v_new_trust >= 50.00 AND ABS(v_velocity) < 5.0 THEN
        SET v_regime = 'DRIFTING';
    ELSEIF v_new_trust < 50.00 AND v_velocity < -1.0 THEN
        SET v_regime = 'DEGRADING';
    ELSEIF v_velocity > 1.0 THEN
        SET v_regime = 'RECOVERING';
    ELSE
        SET v_regime = IFNULL(p_previous_regime, 'STABLE');
    END IF;

    -- Hysteresis: count regime transitions
    IF p_previous_regime IS NOT NULL AND v_regime <> p_previous_regime THEN
        SET v_transition_count = 1;
    ELSE
        SET v_transition_count = 0;
    END IF;

    -- Insert TEM snapshot
    INSERT INTO trust_evolution (
        user_id, theta, mu, sigma, current_trust,
        dt_hours, dw_value, em_iteration,
        trust_velocity, trust_acceleration,
        tdi_composite, tdi_velocity_weight, tdi_acceleration_weight,
        reversion_alignment, is_reverting,
        regime, previous_regime, regime_transition_count,
        estimation_window, computation_time_ms, algorithm_version
    ) VALUES (
        p_user_id, p_theta, p_mu, p_sigma, v_new_trust,
        p_dt_hours, v_dw, 0,
        v_velocity, v_acceleration,
        v_tdi, 0.60, 0.40,
        v_reversion_align, v_is_reverting,
        v_regime, p_previous_regime, v_transition_count,
        p_estimation_window, 0, '2.0'
    );
END //

DELIMITER ;

-- ============================================================
-- VIEW: v_user_trust_dashboard
-- Purpose: Real-time trust dashboard combining ICS + TEM + DNA
-- ============================================================

CREATE OR REPLACE VIEW v_user_trust_dashboard AS
SELECT
    u.id                                          AS user_id,
    u.username,
    u.email,
    r.role_name,
    u.is_enabled,
    u.is_locked,
    ics.ics_score                                 AS current_ics,
    ics.trust_level                               AS ics_trust_level,
    ics.decay_factor                              AS ics_decay_factor,
    tem.current_trust                             AS tem_trust_level,
    tem.regime                                    AS tem_regime,
    tem.trust_velocity,
    tem.tdi_composite                             AS tdi,
    tem.theta,
    tem.mu                                        AS long_term_mean,
    dna.cosine_similarity                         AS dna_similarity,
    dna.drift_regime,
    ics.computed_at                               AS ics_updated_at,
    tem.computed_at                               AS tem_updated_at
FROM users u
JOIN roles r                                    ON u.role_id = r.id
LEFT JOIN (
    SELECT user_id, ics_score, trust_level, decay_factor, computed_at,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY computed_at DESC) AS rn
    FROM identity_confidence
) ics                                           ON ics.user_id = u.id AND ics.rn = 1
LEFT JOIN (
    SELECT user_id, current_trust, regime, trust_velocity, tdi_composite,
           theta, mu, computed_at,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY computed_at DESC) AS rn
    FROM trust_evolution
) tem                                           ON tem.user_id = u.id AND tem.rn = 1
LEFT JOIN (
    SELECT user_id, cosine_similarity, drift_regime, computed_at,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY computed_at DESC) AS rn
    FROM digital_dna
) dna                                           ON dna.user_id = u.id AND dna.rn = 1;

-- ============================================================
-- VERIFICATION QUERIES
-- Run these to verify the schema was created correctly
-- ============================================================

-- Verify all tables exist
-- SELECT table_name, table_rows, data_length, index_length
-- FROM information_schema.tables
-- WHERE table_schema = 'cipherdrive_dna'
-- ORDER BY table_name;

-- Verify all foreign keys
-- SELECT constraint_name, table_name, column_name, referenced_table_name, referenced_column_name
-- FROM information_schema.key_column_usage
-- WHERE table_schema = 'cipherdrive_dna'
-- AND referenced_table_name IS NOT NULL
-- ORDER BY table_name;

-- Verify all indexes
-- SELECT table_name, index_name, column_name, non_unique, seq_in_index
-- FROM information_schema.statistics
-- WHERE table_schema = 'cipherdrive_dna'
-- ORDER BY table_name, index_name, seq_in_index;

-- Verify all CHECK constraints
-- SELECT constraint_name, table_name, check_clause
-- FROM information_schema.check_constraints
-- WHERE constraint_schema = 'cipherdrive_dna'
-- ORDER BY table_name;

-- ============================================================
-- END OF SCRIPT
-- ============================================================
