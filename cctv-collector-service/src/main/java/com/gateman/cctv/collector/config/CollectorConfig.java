package com.gateman.cctv.collector.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Strong-typed configuration mapping for the CCTV stream collector service.
 * <p>
 * <b>Configuration Namespace (prefix = "cctv"):</b><br>
 * The {@code prefix = "cctv"} designates the root configuration hierarchy for all properties defined in this interface.
 * SmallRye Config automatically bridges this namespace to two external configuration sources:
 * <ul>
 *   <li><b>Application Properties:</b> Resolves properties in {@code application.properties} prefixed with
 *       {@code cctv.*} using kebab-case naming (e.g., {@code cctv.rtsp-url}, {@code cctv.buffer-dir}, {@code cctv.segment-seconds}).</li>
 *   <li><b>Environment Variables:</b> Resolves environment variables injected by K3s ConfigMap or Secret
 *       prefixed with {@code CCTV_*} using UPPER_UNDERSCORE naming (e.g., {@code CCTV_RTSP_URL}, {@code CCTV_BUFFER_DIR}, {@code CCTV_SEGMENT_SECONDS}).</li>
 * </ul>
 * This decouples business logic from environment-specific configuration mechanisms, adhering to Cloud Native Twelve-Factor principles.
 */
@ConfigMapping(prefix = "cctv")
public interface CollectorConfig {

    /**
     * Source RTSP stream URL for the IP camera (TP-LINK TL-IPC44AW).
     * Mapped from {@code cctv.rtsp-url} or environment variable {@code CCTV_RTSP_URL}.
     * Example: rtsp://admin:password@10.0.1.20:554/stream1
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
