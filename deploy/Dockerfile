# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY app/pom.xml app/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -f app/pom.xml -B -q -DskipTests dependency:go-offline

COPY app/src app/src
RUN --mount=type=cache,target=/root/.m2 mvn -f app/pom.xml -B -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /workspace/app/target/plan-service-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=2s --retries=10 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status"\s*:\s*"UP"' || exit 1

ENV JAVA_OPTS=""

# run as non-root (simple)
RUN addgroup -S app && adduser -S app -G app
USER app

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
