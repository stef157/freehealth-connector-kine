# Image for the local deployment, packaging the jar that ./gradlew bootJar has already produced.
#
# Upstream builds its own image from build.Dockerfile + package.Dockerfile onto gcr.io/distroless/java21,
# driven by Cloud Build. That base has no shell, so its ENTRYPOINT cannot expand $JAVA_OPTS - and the CIN
# licence and the registered package name travel through JAVA_OPTS here. Hence this separate image on a JRE
# base with a shell entrypoint. Build it with `./gradlew dockerize`, which is what the DockerJavaPlugin used
# to offer before the Spring Boot 3 migration dropped it.
FROM eclipse-temurin:21-jre

WORKDIR /app
ARG jar
COPY ${jar} /app/fhc.jar

# MiddlewareApplication extracts the eHealth trust stores under KEYSTORE_DIR on startup, so it must be
# writable. The connector also caches the BCP endpoint list and the TSL state in /tmp; mount a named volume
# on both to keep them across runs.
RUN mkdir -p /opt/ehealth
VOLUME ["/opt/ehealth", "/tmp"]

EXPOSE 8090

# A shell entrypoint on purpose: JAVA_OPTS carries -Dmycarenet.license.* and -Dpackage.name.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/fhc.jar"]
