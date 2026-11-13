FROM eclipse-temurin:27-jdk@sha256:9d4c29a53a1a7a028416306d090080484602ccc43cc316404e12cabd133c7056 AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:27-jre@sha256:05a57c9f4d6d8bf8d8c2213049ea816609672ffcc14463a9c83433a610703e10 AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app /data && chown -R 10001:10001 /app /data
WORKDIR /app
COPY --from=build /build/target/openservice-0.2.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV DATABASE_URL=sqlite:////data/openservice.db
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD curl --fail --silent http://localhost:8080/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
