# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/kjv-bible-reader-1.0.0.jar app.jar
# Durable volume for the rotated xAI OAuth refresh token (ai.xai.oauth.refresh-token-file).
# Mount this in prod — the container layer is ephemeral and the env seed is already invalidated.
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
