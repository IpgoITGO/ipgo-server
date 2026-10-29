FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
COPY src src
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 ipgo \
    && useradd --system --uid 10001 --gid ipgo --home-dir /app --shell /usr/sbin/nologin ipgo
WORKDIR /app
COPY --from=build --chown=ipgo:ipgo /workspace/build/libs/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
