# Multi-stage Dockerfile for Quarkus Native CCTV Collector Service
# Stage 1: Build native executable using Mandrel/GraalVM builder image
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS build
USER root
WORKDIR /work

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY cctv-collector-service/pom.xml cctv-collector-service/
COPY cctv-uploader-service/pom.xml cctv-uploader-service/

# Copy source files
COPY cctv-collector-service/src cctv-collector-service/src

# Compile to native binary executable
RUN ./mvnw -B package -Dnative -DskipTests -pl cctv-collector-service -am

# Stage 2: Minimal runtime image based on Debian slim with official FFmpeg
FROM debian:12-slim
WORKDIR /app

# Install FFmpeg, ca-certificates, tzdata and cleanup apt caches
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

# Non-root user setup
RUN groupadd -r cctv --gid 1000 && useradd -r -g cctv --uid 1000 -d /app cctv

COPY --from=build --chown=cctv:cctv /work/cctv-collector-service/target/*-runner /app/application

USER 1000
EXPOSE 8081

ENV QUARKUS_HTTP_HOST=0.0.0.0

ENTRYPOINT ["./application"]
