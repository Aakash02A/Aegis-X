# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy maven wrapper and pom.xml
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Copy source code and package application
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Container-aware heap sizing: the JVM sees the cgroup limit, not the host's RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Install Tesseract OCR and language data
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    && mkdir -p /usr/share/tessdata

# Create non-root system user
RUN addgroup -S aegisx && adduser -S aegisx -G aegisx

# Copy built artifact from builder stage
COPY --from=builder /workspace/target/aegisx-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /app/logs && chown aegisx:aegisx /app/logs
ENV LOGGING_FILE_NAME=/app/logs/aegisx.log

USER aegisx:aegisx

EXPOSE 8080

# Liveness only: readiness still belongs to the orchestrator, and the DB probe is exposed at
# /actuator/health/readiness. BusyBox wget ships with the Alpine base image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
