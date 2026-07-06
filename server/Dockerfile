# Multi-stage build after docker/awesome-compose spring-postgres. Maven builds
# the jar, a JRE-only stage runs it, and the cache mount keeps ~/.m2 across
# rebuilds.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# jvm.config carries the --add-exports flags Error Prone needs to reach javac's
# internals. Without it in the image the JDK 21 compile fails with IllegalAccessError.
COPY .mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
# curl serves the compose healthcheck only.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
