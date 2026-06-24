# --- build stage: compile just this module (and the parent) from the monorepo ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy every reactor pom first so the aggregator resolves and dependency layers
# cache across rebuilds. integration-tests is listed by the parent but pruned by
# -am (this module doesn't depend on it); its pom must still be present to scan.
COPY pom.xml ./
COPY account-service/pom.xml account-service/
COPY event-gateway/pom.xml event-gateway/
COPY integration-tests/pom.xml integration-tests/

COPY account-service/src account-service/src
# -pl selects this module, -am builds the parent it needs; skip tests in the image build.
RUN mvn -q -pl account-service -am package -DskipTests

# --- run stage: slim JRE 21, run as a non-root user ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl backs the container healthcheck; a dedicated system user runs the app.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring spring

COPY --from=build --chown=spring:spring /build/account-service/target/account-service-*.jar app.jar
USER spring
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
