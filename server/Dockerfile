FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# jvm.config holds the Error Prone --add-exports flags; without them the JDK 21
# compile fails with IllegalAccessError.
COPY .mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

# Local: the debug tag adds a busybox wget for the compose healthcheck.
FROM gcr.io/distroless/java21-debian12:debug-nonroot AS local
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# Prod: health is probed externally (ECS over HTTP), so no in-container tooling.
FROM gcr.io/distroless/java21-debian12:nonroot AS prod
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
