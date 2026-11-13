# syntax=docker/dockerfile:1.7
# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline
COPY data ./data
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S relay && adduser -S relay -G relay
WORKDIR /app
COPY --from=build /src/target/relay.jar /app/relay.jar
USER relay

# One artifact, three deployment modes (see docs/DEPLOYMENT.md):
#   API + worker (default) | API only (RELAY_WORKER_ENABLED=false) | worker only (--spring.profiles.active=worker)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health/liveness" | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/relay.jar"]
