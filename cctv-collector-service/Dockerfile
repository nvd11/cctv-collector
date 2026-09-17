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

# Stage 2: Ultra-lightweight minimal runtime image with FFmpeg
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.3
WORKDIR /app

# Install FFmpeg and required runtime dynamic libraries
RUN microdnf update -y \
    && microdnf install -y --nodocs epel-release || true \
    && microdnf install -y --nodocs ffmpeg tzdata ca-certificates \
    && microdnf clean all \
    && rm -rf /var/cache/yum

# Non-root user setup
RUN useradd -u 1000 -d /app cctv && chown -R 1000:1000 /app

COPY --from=build --chown=1000:1000 /work/cctv-collector-service/target/*-runner /app/application

USER 1000
EXPOSE 8081

ENV QUARKUS_HTTP_HOST=0.0.0.0

ENTRYPOINT ["./application"]
