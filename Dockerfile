# ---- build stage: the whole workspace is the build context, because the
# project pulls bricks via :local/root ----
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /workspace
COPY . .
WORKDIR /workspace/projects/hotel-system
RUN clojure -T:build uber

# ---- run stage: one jar, one process, the modular monolith ----
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home hotel
USER hotel
COPY --from=build /workspace/projects/hotel-system/target/hotel-system.jar /app.jar
EXPOSE 3000
# Required env: DATABASE_URL, RABBITMQ_URI, SENDGRID_API_KEY, FROM_EMAIL. Optional: PORT.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
