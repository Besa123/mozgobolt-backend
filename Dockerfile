FROM eclipse-temurin:24-jdk-noble AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon installDist --console=plain

FROM eclipse-temurin:24-jre-noble

WORKDIR /app
RUN groupadd --system --gid 10001 mozgobolt \
    && useradd --system --uid 10001 --gid mozgobolt --home-dir /app --no-create-home \
        --shell /usr/sbin/nologin mozgobolt \
    && mkdir -p /app/data/vehicle-images /app/logs \
    && chown -R mozgobolt:mozgobolt /app

COPY --from=build --chown=10001:10001 /workspace/build/install/mozgobolt/ /app/

ENV HOST=0.0.0.0 \
    PORT=8080 \
    JAVA_TOOL_OPTIONS=-Duser.timezone=UTC

EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["/app/bin/mozgobolt"]
