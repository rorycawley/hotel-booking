# ---- build stage: the whole workspace is the build context, because the
# project pulls bricks via :local/root ----
ARG CLOJURE_BUILD_IMAGE=clojure:temurin-21-tools-deps-1.11.3.1463
FROM ${CLOJURE_BUILD_IMAGE} AS build
WORKDIR /workspace
COPY . .
WORKDIR /workspace/projects/hotel-system
RUN clojure -T:build uber

# ---- run stage: one jar, one process, the modular monolith ----
ARG JVM_RUNTIME_IMAGE=eclipse-temurin:21.0.4_7-jre
FROM ${JVM_RUNTIME_IMAGE}
RUN useradd --system --no-create-home hotel
USER hotel
COPY --from=build /workspace/projects/hotel-system/target/hotel-system.jar /app.jar
EXPOSE 3000
# Required env: DATABASE_URL, RABBITMQ_URI, SENDGRID_API_KEY, FROM_EMAIL. Optional: PORT.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
