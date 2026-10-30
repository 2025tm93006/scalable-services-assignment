# syntax=docker/dockerfile:1
# scanner-service image. Build from the repository root, since the module needs the parent POM and common/:
#   docker build -f scanner-service/Dockerfile -t scanner-service:1.0.0 .

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
# The parent POM lists every module, so all module POMs must exist for the reactor to resolve.
COPY pom.xml .
COPY common/pom.xml common/
COPY intake-service/pom.xml intake-service/
COPY quarantine-service/pom.xml quarantine-service/
COPY scanner-service/pom.xml scanner-service/
COPY release-service/pom.xml release-service/
COPY common/src common/src
COPY scanner-service/src scanner-service/src
# Tests run in the normal mvn build; the image build only packages this module and common.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl scanner-service -am package -DskipTests \
 && cp scanner-service/target/scanner-service-*.jar /workspace/app.jar

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app app \
 && mkdir -p /app/data && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar app.jar
USER app
ENV SERVER_PORT=8083 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8083
# H2 file database lives at ./data/scanner relative to /app, the default DB_URL in application.yml.
VOLUME ["/app/data"]
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -q -O /dev/null "http://localhost:${SERVER_PORT}/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
