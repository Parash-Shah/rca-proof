FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY rcaproof-core rcaproof-core
COPY demo-system demo-system
COPY rcaproof-api rcaproof-api
COPY organization organization
COPY build-evidence build-evidence
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre
ARG SERVICE
ARG MODULE_PATH=demo-system/${SERVICE}
WORKDIR /app
COPY --from=build /workspace/${MODULE_PATH}/target/${SERVICE}-0.1.0-SNAPSHOT-exec.jar /app/app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
