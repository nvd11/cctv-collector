package com.gateman.cctv.collector.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Strong-typed configuration mapping for the CCTV stream collector service.
 * <p>
 * <b>Automatic Mapping Algorithm (MicroProfile / SmallRye Config Specification):</b><br>
 * The mapping between Java method names (e.g. {@code rtspUrl()}) and environment variables (e.g. {@code CCTV_RTSP_URL})
 * is derived deterministically by Quarkus / SmallRye Config without requiring manual mapping tables:
 * <ol>
 *   <li><b>CamelCase to kebab-case:</b> Method names in camelCase are converted to hyphenated lowercase
 *       (e.g., {@code rtspUrl} &rarr; {@code rtsp-url}, {@code segmentSeconds} &rarr; {@code segment-seconds}).</li>
 *   <li><b>Prefix Concatenation:</b> The {@code prefix = "cctv"} is prepended with a dot delimiter to form
 *       the canonical property key (e.g., {@code cctv.rtsp-url} for {@code application.properties}).</li>
 *   <li><b>POSIX Environment Variable Conversion:</b> Because Linux and Kubernetes POSIX standards prohibit
 *       dots ({@code .}) and hyphens ({@code -}) in environment variable names, all delimiters are converted
 *       to underscores ({@code _}) and uppercased (e.g., {@code cctv.rtsp-url} &rarr; {@code CCTV_RTSP_URL}).</li>
 * </ol>
 * In K3s / Kubernetes, injecting {@code CCTV_RTSP_URL} via ConfigMap or Secret automatically maps directly
 * to {@link #rtspUrl()} at runtime, overriding any default values.
 * <p>
 * <b>Fail-Fast Validation:</b><br>
 * Properties without {@link WithDefault} are strictly mandatory. If missing or misspelled, Quarkus aborts
 * container startup with a {@code ConfigValidationException}, preventing runtime failures in production.
 */
@ConfigMapping(prefix = "cctv")
public interface CollectorConfig {

    /**
     * Source RTSP stream URL for the IP camera (TP-LINK TL-IPC44AW).
     * <p>
     * <b>Mapping Derivation:</b><br>
     * Method {@code rtspUrl()} + prefix {@code "cctv"} &rarr; canonical property {@code cctv.rtsp-url}
     * &rarr; POSIX environment variable {@code CCTV_RTSP_URL}.
     * <p>
     * Example: {@code rtsp://admin:password@10.0.1.20:554/stream1}
     */
    @WithDefault("rtsp://localhost:554/stream1")
    String rtspUrl();

    /**
     * Local storage directory path where video segments are buffered before upload.
     * Mapped from {@code cctv.buffer-dir} or environment variable {@code CCTV_BUFFER_DIR}.
     */
    @WithDefault("/mnt/buffer/cctv")
    String bufferDir();

    /**
     * Target segment duration in seconds per MP4 file.
     * Mapped from {@code cctv.segment-seconds} or environment variable {@code CCTV_SEGMENT_SECONDS}.
     * Default: 900 seconds (15 minutes).
     */
    @WithDefault("900")
    int segmentSeconds();

    /**
     * Minimum free disk space threshold in Gigabytes.
     * If the free disk space falls below this value, the collector triggers a circuit breaker
     * to pause recording and prevent disk exhaustion.
     * Mapped from {@code cctv.min-free-disk-gb} or environment variable {@code CCTV_MIN_FREE_DISK_GB}.
     */
    @WithDefault("5")
    long minFreeDiskGb();

    /**
     * Initial backoff delay in seconds when the RTSP stream disconnects or FFmpeg exits abnormally.
     * Mapped from {@code cctv.reconnect-delay-seconds} or environment variable {@code CCTV_RECONNECT_DELAY_SECONDS}.
     */
    @WithDefault("5")
    int reconnectDelaySeconds();

    /**
     * Maximum exponential backoff cap in seconds for reconnection attempts.
     * Mapped from {@code cctv.max-reconnect-delay-seconds} or environment variable {@code CCTV_MAX_RECONNECT_DELAY_SECONDS}.
     */
    @WithDefault("60")
    int maxReconnectDelaySeconds();
}
