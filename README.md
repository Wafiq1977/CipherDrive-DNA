# CipherDrive-DNA MVP

**MVP implementation of the CipherDrive-DNA framework** — a multi-layered security model combining Digital DNA profiling, Identity Confidence Score (ICS), and Trust Evolution Management (TEM) for secure file storage.

> This repository contains the **Minimum Viable Product (MVP)** source code accompanying the DNA-TRUST research framework.

[![CI](https://github.com/Wafiq1977/CipherDrive-DNA/actions/workflows/ci.yml/badge.svg)](https://github.com/Wafiq1977/CipherDrive-DNA/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.5-green.svg)](https://spring.io/projects/spring-boot)

---

## 📖 Overview

CipherDrive-DNA is a security-first file storage system that continuously authenticates users through three interlocking layers:

| Layer | Abbreviation | Purpose |
|-------|--------------|---------|
| **Digital DNA** | `DNA` | Behavioral biometric profile built from keystroke, mouse, and usage patterns |
| **Identity Confidence Score** | `ICS` | Real-time confidence score (0–100) derived from DNA drift + session context |
| **Trust Evolution Management** | `TEM` | Long-term trust trajectory with alerts and auto-lock on degradation |

Files are encrypted at rest and access is gated by all three layers — not just a static password.

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      CipherDrive-DNA MVP                     │
├──────────────────────────────────────────────────────────────┤
│  REST Controller Layer (Spring Boot)                         │
│  ├── /api/auth          → register / login / refresh         │
│  ├── /api/dna           → DNA profile + drift                │
│  ├── /api/ics           → live confidence score              │
│  ├── /api/tem           → trust evolution + alerts           │
│  └── /api/files         → encrypted upload / download        │
├──────────────────────────────────────────────────────────────┤
│  Service Layer                                               │
│  ├── DigitalDNAService      → builds & refreshes DNA vector  │
│  ├── IdentityConfidenceService → computes ICS                │
│  ├── TrustEvolutionService  → TEM trajectory + scheduler     │
│  ├── AccessDecisionEngine   → ICS-gated access control       │
│  └── StorageService         → MinIO encrypted object storage │
├──────────────────────────────────────────────────────────────┤
│  Data Layer                                                  │
│  ├── MySQL (users, sessions, DNA, ICS, TEM, files)           │
│  └── MinIO (encrypted file blobs)                           │
└──────────────────────────────────────────────────────────────┘
```

---

## 📂 Project Structure

```
CipherDrive-DNA-MVP/
├── src/main/java/com/cipherdrive/dna/
│   ├── CipherDriveDnaApplication.java   # Spring Boot entry point
│   ├── config/                          # Spring + MinIO + Async configs
│   ├── controller/                      # REST endpoints
│   ├── dto/                             # Request/Response DTOs
│   │   ├── behavior/                    # Behavior event payloads
│   │   ├── dna/                         # DNA profile responses
│   │   ├── ics/                         # ICS responses
│   │   └── tem/                         # TEM responses
│   ├── entity/                          # JPA entities
│   ├── exception/                       # Domain exceptions + global handler
│   ├── repository/                      # Spring Data JPA repositories
│   ├── security/                        # JWT filter + service
│   └── service/
│       ├── behavioral/                  # Behavior log service
│       ├── dna/                         # DNA calculator + scheduler
│       ├── ics/                         # ICS calculator + access engine
│       ├── storage/                     # File metadata + storage
│       └── tem/                         # TEM calculator + scheduler
├── src/main/resources/
│   └── application.yml                  # App configuration
└── cipherdrive_dna_mvp.sql              # MySQL DDL schema
```

---

## 🗄️ Database Schema

The MySQL DDL is provided in [`cipherdrive_dna_mvp.sql`](./cipherdrive_dna_mvp.sql). It defines the following core tables:

- `users`, `roles`, `user_roles` — identity & RBAC
- `sessions` — active session tracking
- `digital_dna` — per-user DNA vectors (with versioning)
- `behavior_logs` — raw behavior events feeding the DNA
- `identity_confidence` — historical ICS readings
- `trust_evolution` — long-term TEM trajectory
- `files`, `file_metadata` — encrypted file metadata

---

## 🔐 Core Concepts

### Digital DNA (DNA)
A per-user behavioral biometric vector built from:
- Keystroke dynamics (dwell time, flight time)
- Mouse movement patterns
- Session metadata (time-of-day, IP, device fingerprint)

The vector is **recomputed on a schedule** (see `DigitalDNAScheduler`) and compared against the user's baseline to detect anomalies.

### Identity Confidence Score (ICS)
A real-time score (0–100) computed from:
- DNA drift (Mahalanobis distance from baseline)
- Session age & activity
- Recent behavior anomalies

ICS is recalculated on every request that touches a protected resource.

### Trust Evolution Management (TEM)
A long-term trust trajectory computed by `TEMCalculator`:
- Daily trust score aggregation
- Trend detection (rising / stable / degrading)
- Auto-locks and alerts when trust degrades below threshold

---

## 🛠️ Tech Stack

| Component       | Technology |
|-----------------|------------|
| Framework       | Spring Boot 3.3.x |
| ORM             | Spring Data JPA / Hibernate |
| Database        | MySQL 8.x (prod) / H2 (test) |
| Object Storage  | MinIO 8.5.x |
| Auth            | JWT (jjwt 0.12.x, HS256) |
| Build           | Maven 3.8+ |
| Java Version    | 17 (also tested on 21) |
| CI              | GitHub Actions |

---

## 🚀 Getting Started

### Prerequisites
- JDK 17+ (or 21)
- MySQL 8.x
- MinIO server (local or remote)
- Maven 3.8+

### 1. Clone the repository
```bash
git clone https://github.com/Wafiq1977/CipherDrive-DNA.git
cd CipherDrive-DNA
```

### 2. Initialize the database
```bash
mysql -u root -p < cipherdrive_dna_mvp.sql
```

### 3. Configure `application.yml`
Update `src/main/resources/application.yml` with your MySQL and MinIO credentials, JWT secret, and DNA engine properties.

### 4. Build & run
```bash
mvn clean package
java -jar target/cipherdrive-dna-mvp-0.1.0-SNAPSHOT.jar
```

The API will be available at `http://localhost:8080`.

### 5. Run tests
```bash
mvn test
```

Tests run with the `test` profile (in-memory H2 + mock MinIO) — no external services required.

---

## 🐳 Docker Deployment

Run the entire stack (app + MySQL + MinIO) locally with one command:

```bash
cp .env.example .env        # Fill in real passwords
docker compose up -d        # Start all services
docker compose logs -f app  # Tail application logs
```

Endpoints after startup:
- App:       http://localhost:8080
- MinIO UI:  http://localhost:9001
- MySQL:     localhost:3306

---

## ☁️ Production Deployment

Deploy to a public URL so anyone can access your instance. The recommended platform is **[Render.com](https://render.com)** — click-click deploy, $300 free credit for new users.

### One-click Render deploy

1. Generate a JWT secret locally:
   ```bash
   openssl rand -base64 32 | base64
   ```
2. Go to 👉 https://dashboard.render.com/select-repo?type=blueprint
3. Select this repository — Render auto-detects `render.yaml`.
4. Fill in the secret env vars when prompted (`JWT_SECRET_KEY`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`).
5. Click **Apply**. Render provisions MySQL + app + disk automatically.
6. Initialize the DB schema from `cipherdrive_dna_mvp.sql` (one-time, see [`DEPLOYMENT.md`](./DEPLOYMENT.md)).

### Full step-by-step guide

See **[DEPLOYMENT.md](./DEPLOYMENT.md)** for:
- Detailed Render walkthrough with screenshots
- Railway.app / Fly.io / VPS alternatives
- Production hardening checklist
- Troubleshooting common issues

---

## 🤖 Continuous Integration

This repository includes a GitHub Actions workflow (`.github/workflows/ci.yml`) that runs on every push / pull request to `main` and `develop`:

- **Matrix build**: JDK 17 and JDK 21
- **Steps**: compile → test → package → upload JAR artifact
- **Verify job**: extra build verification on PRs

Build status: [![CI](https://github.com/Wafiq1977/CipherDrive-DNA/actions/workflows/ci.yml/badge.svg)](https://github.com/Wafiq1977/CipherDrive-DNA/actions/workflows/ci.yml)

---

## 📜 License

This MVP is provided for **research and academic use** as part of the DNA-TRUST framework. Contact the author for commercial licensing.

---

## 📚 Related Research

This MVP accompanies the **DNA-TRUST Framework** research document series, which covers:
- Literature Review on continuous authentication
- Trust Evolution Modeling
- Digital DNA profiling methodology
- Research gap analysis
