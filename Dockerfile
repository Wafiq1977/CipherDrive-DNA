# ============================================================
# CipherDrive-DNA MVP — Dockerfile (multi-stage)
# ============================================================
# Stage 1: Build the JAR with Maven + JDK 17
# Stage 2: Runtime image with JRE only (smaller, more secure)
#
# Build:    docker build -t cipherdrive-dna:latest .
# Run:      docker run -p 8080:8080 --env-file .env cipherdrive-dna:latest
# ============================================================

# ── Stage 1: Build ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Pre-copy only pom.xml to cache Maven dependencies separately from source.
# This makes rebuilds much faster when only Java files change.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd

# Use a wrapper Maven if available; otherwise fall back to system mvn.
# Disable daemon (no -T) and use batch mode to keep logs clean.
RUN --mount=type=cache,target=/root/.m2 \
    (./mvnw -B -ntp -q dependency:go-offline || mvn -B -ntp -q dependency:go-offline) \
    || true

# Now copy the rest of the source and build.
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    (./mvnw -B -ntp -DskipTests package || mvn -B -ntp -DskipTests package)

# Extract the layered JAR for optimal Docker layer caching.
# Spring Boot's layered JAR splits app into dependencies / spring-boot-loader /
# snapshot-dependencies / application layers → faster rebuilds & smaller diffs.
RUN mkdir -p build/extracted && \
    java -Djarmode=layertools -jar target/*.jar extract --destination build/extracted

# ── Stage 2: Runtime ────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Create a non-root user for security.
RUN groupadd --system cipherdrive && \
    useradd --system --gid cipherdrive --home-dir /app --shell /usr/sbin/nologin cipherdrive

WORKDIR /app

# Copy layered JAR contents (order matters for caching).
COPY --from=builder /workspace/build/extracted/dependencies/         ./
COPY --from=builder /workspace/build/extracted/spring-boot-loader/   ./
COPY --from=builder /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/build/extracted/application/          ./

# Switch to non-root user.
USER cipherdrive

# Expose the HTTP port (Render will inject PORT env var).
EXPOSE 8080

# Healthcheck: hit the Spring Boot Actuator /health endpoint.
# Render uses this to know when the container is ready to receive traffic.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Activate the prod profile by default.
# Override with SPRING_PROFILES_ACTIVE if needed.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Use exec form so JAVA_OPTS are properly tokenized via sh.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
