# --- build stage: compile just this module (and the parent) from the monorepo ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the reactor poms first so dependency resolution layers cache across rebuilds.
COPY pom.xml ./
COPY account-service/pom.xml account-service/
COPY event-gateway/pom.xml event-gateway/

COPY account-service/src account-service/src
# -pl selects this module, -am builds the parent it needs; skip tests in the image build.
RUN mvn -q -pl account-service -am package -DskipTests

# --- run stage: slim JRE 21 with just the fat jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/account-service/target/account-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
