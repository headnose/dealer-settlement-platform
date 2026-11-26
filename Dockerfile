# syntax=docker/dockerfile:1
# One Dockerfile for all four services (the same build, only the module differs):
#   docker build --build-arg SERVICE=wire-service -t dsp/wire-service:local .
# Build context = repository root, because a module needs the parent pom.

# --- Stage 1: build the jar ---
FROM maven:3.9-eclipse-temurin-17 AS build
ARG SERVICE
WORKDIR /src
COPY . .
# -pl/-am: build only this module (and anything it depends on). Tests run in CI's Maven job, not here.
# Cache mount: the Maven repository is kept between local builds, so dependencies download once.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "$SERVICE" && \
    mvn -B -q -pl "$SERVICE" -am -DskipTests package && \
    cp "$SERVICE"/target/"$SERVICE"-*.jar app.jar
# Split the fat jar into layers (dependencies change rarely, application classes often), so a code change
# only rebuilds the small last layer of the image.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Stage 2: runtime image (JRE only, no Maven, no source) ---
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
# logging.file.name is relative (logs/<service>.json): give the non-root user a place to write it.
RUN mkdir logs && chown app logs
USER app
# MaxRAMPercentage: size the heap from the container's memory limit (ECS task size), not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
