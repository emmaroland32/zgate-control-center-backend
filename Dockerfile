# Multi-stage build for ZGATE Control Center backend
FROM maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /app

# Copy pom.xml first so the dependency layer is cached
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build (tests need Postgres, so skip them here)
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Non-root user
RUN addgroup -S zgate && adduser -S zgate -G zgate

# Copy the fat jar from the builder stage
COPY --from=builder /app/target/*.jar app.jar
RUN chown -R zgate:zgate /app

USER zgate

# Control Center backend listens on 8090
EXPOSE 8090

# Actuator health is exposed (management.endpoints.web.exposure.include=health,info)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8090/actuator/health || exit 1

# Container-aware JVM tuning
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
