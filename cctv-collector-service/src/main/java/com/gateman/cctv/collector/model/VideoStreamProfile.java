package com.gateman.cctv.collector.model;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable domain model describing technical specifications and real-time health of the RTSP video stream.
 * <p>
 * Built as a Java 21 Record for immutability, thread-safety, and JSON telemetry reporting.
 *
 * @param streamUrl        sanitized RTSP stream endpoint (credentials masked for security)
 * @param protocol         transport protocol (typically "RTSP/TCP")
 * @param videoCodec       detected video compression format (e.g. "H.265 (HEVC)")
 * @param audioCodec       detected audio encoding format (e.g. "AAC (16000Hz, mono)")
 * @param resolutionWidth  horizontal resolution in pixels (e.g. 2560)
 * @param resolutionHeight vertical resolution in pixels (e.g. 1440)
 * @param fps              frames per second (e.g. 15)
 * @param bitRateBps       real-time streaming bitrate in bits per second
 * @param isAlive          whether the physical stream is currently delivering frames actively
 */
public record VideoStreamProfile(
        String streamUrl,
        String protocol,
        String videoCodec,
        String audioCodec,
        int resolutionWidth,
        int resolutionHeight,
        int fps,
        long bitRateBps,
        boolean isAlive
) {

    private static final Pattern RTSP_CREDENTIAL_PATTERN = Pattern.compile("^(rtsp://[^:]+:)([^@]+)(@.*)$");

    public static final String DEFAULT_PROTOCOL = "RTSP/TCP";
    public static final String DEFAULT_VIDEO_CODEC = "H.265 (HEVC)";
    public static final String DEFAULT_AUDIO_CODEC = "AAC (16000Hz, mono)";
    public static final int DEFAULT_WIDTH = 2560;
    public static final int DEFAULT_HEIGHT = 1440;
    public static final int DEFAULT_FPS = 15;
    public static final long DEFAULT_BITRATE = 1_500_000L;

    /**
     * Compact constructor enforcing non-null assertions and boundary constraints.
     */
    public VideoStreamProfile {
        Objects.requireNonNull(streamUrl, "streamUrl must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(videoCodec, "videoCodec must not be null");
        Objects.requireNonNull(audioCodec, "audioCodec must not be null");

        if (resolutionWidth < 0 || resolutionHeight < 0) {
            throw new IllegalArgumentException("Resolution dimensions must not be negative");
        }
        if (fps < 0) {
            throw new IllegalArgumentException("FPS must not be negative, got: " + fps);
        }
        if (bitRateBps < 0) {
            throw new IllegalArgumentException("BitRate must not be negative, got: " + bitRateBps);
        }

        // Always sanitize and mask passwords in RTSP URL
        streamUrl = sanitizeStreamUrl(streamUrl);
    }

    /**
     * Creates a profile using default specifications matching the TP-LINK TL-IPC44AW hardware.
     *
     * @param rawStreamUrl raw RTSP URL (will be automatically sanitized and credentials masked)
     * @param isAlive      initial stream connectivity status
     * @return new {@link VideoStreamProfile} instance
     */
    public static VideoStreamProfile createDefault(String rawStreamUrl, boolean isAlive) {
        return new VideoStreamProfile(
                rawStreamUrl,
                DEFAULT_PROTOCOL,
                DEFAULT_VIDEO_CODEC,
                DEFAULT_AUDIO_CODEC,
                DEFAULT_WIDTH,
                DEFAULT_HEIGHT,
                DEFAULT_FPS,
                DEFAULT_BITRATE,
                isAlive
        );
    }

    /**
     * Masks passwords in RTSP connection URLs to prevent credential leakage in logs and API telemetry.
     * E.g. {@code rtsp://admin:pass123@10.0.1.20:554/stream1} &rarr; {@code rtsp://admin:****@10.0.1.20:554/stream1}.
     *
     * @param url raw RTSP URL
     * @return sanitized URL with password masked
     */
    public static String sanitizeStreamUrl(String url) {
        if (url == null) {
            return "";
        }
        Matcher matcher = RTSP_CREDENTIAL_PATTERN.matcher(url);
        if (matcher.matches()) {
            return matcher.group(1) + "****" + matcher.group(3);
        }
        return url;
    }

    /**
     * Derives a new immutable profile with updated connectivity status.
     *
     * @param alive updated streaming status
     * @return new {@link VideoStreamProfile}
     */
    public VideoStreamProfile withAlive(boolean alive) {
        return new VideoStreamProfile(streamUrl, protocol, videoCodec, audioCodec, resolutionWidth, resolutionHeight, fps, bitRateBps, alive);
    }

    /**
     * Derives a new immutable profile with updated real-time bitrate.
     *
     * @param newBitRateBps updated bitrate in bps
     * @return new {@link VideoStreamProfile}
     */
    public VideoStreamProfile withBitRate(long newBitRateBps) {
        return new VideoStreamProfile(streamUrl, protocol, videoCodec, audioCodec, resolutionWidth, resolutionHeight, fps, newBitRateBps, isAlive);
    }
}
