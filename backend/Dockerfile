# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------------
# Build. The Maven repository is a BuildKit cache mount rather than an image layer, so a rebuild reuses
# the downloaded dependencies without baking a few hundred megabytes into the image.
# ---------------------------------------------------------------------------------------------------
# --platform=$BUILDPLATFORM: the jar is the same bytecode for every CPU, so compile it natively on the build
# machine even when producing an arm64 image (e.g. for Apple Silicon); emulating Maven would be very slow.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# The wrapper and the POM first: they change rarely, so editing a source file does not invalidate the
# layer that knows how to fetch dependencies.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package

# ---------------------------------------------------------------------------------------------------
# Runtime. JRE only, and not root.
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
COPY --from=build /build/target/*.jar app.jar
USER app

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM sizes itself from the container limit, so compose
# memory settings take effect instead of being silently ignored.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Readiness includes the database, so this reports healthy only once Flyway has run and the pool is
# connected -- which is exactly what the frontend should wait for.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=18 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
