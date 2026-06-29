# syntax=docker/dockerfile:1

# ========= Stage 1: build the boot jar with the Gradle wrapper =========
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace

# Copy wrapper + build scripts first so the dependency layer caches across source edits.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# Strip Windows CRLF line endings (gradlew may be checked out with \r\n on Windows hosts)
RUN sed -i 's/\r//' gradlew && chmod +x ./gradlew && ./gradlew --no-daemon --version

# Best-effort dependency warm-up (ignored if it cannot resolve without sources).
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Copy sources and build the executable jar. Tests run in CI (Task 4), not in the image build.
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# Explode the layered Spring Boot jar for better runtime-image layer caching.
RUN cp build/libs/voucher-system-0.0.1-SNAPSHOT.jar app.jar \
 && java -Djarmode=layertools -jar app.jar extract --destination extracted

# ========= Stage 2: slim JRE runtime =========
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# curl is used by the container HEALTHCHECK below.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Run as a non-root system user.
RUN groupadd --system appgroup \
 && useradd --system --gid appgroup --create-home appuser

# Copy exploded layers, most-stable first (dependencies change least, application most).
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/extracted/application/ ./

USER appuser

# MaxRAMPercentage lets the JVM size the heap from the container memory limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# exec form so the JVM is PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
