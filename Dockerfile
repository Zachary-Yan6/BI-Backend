# ---- Stage 1: build the jar -------------------------------------------------
FROM maven:3.9.16-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy only pom.xml first and download dependencies into their own layer.
# Docker reuses this layer (and CI restores it from cache) until pom.xml changes,
# so editing Java code no longer re-downloads every dependency.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests already ran in CI before this image is built, so skip them here.
RUN mvn -B -q package -DskipTests

# ---- Stage 2: minimal runtime image -----------------------------------------
# Only the JRE and the jar ship to production: no Maven, no source code, no compiler.
FROM eclipse-temurin:21-jre

# Run as an unprivileged user so a compromised app cannot act as root inside the container.
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/data/uploads \
    && chown -R app:app /app

WORKDIR /app
COPY --from=builder --chown=app:app /app/target/bi-backend-1.0.0.jar app.jar
USER app

EXPOSE 8101
# Size the heap from the container's memory limit instead of the host's RAM.
# Exit on the first OutOfMemoryError so Docker (restart: unless-stopped) restarts a clean JVM. Otherwise only the
# thread that hit it fails, and a RabbitMQ consumer or scheduler thread can die while the container looks healthy.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["--spring.profiles.active=prod"]
