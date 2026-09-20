package com.gateman.cctv.collector.test;

import com.gateman.cctv.collector.config.CollectorConfig;

/**
 * Test fixture implementing {@link CollectorConfig} using a Java 21 Record.
 * Provides an immutable, zero-boilerplate configuration stub for unit tests.
 */
public record TestCollectorConfig(
        String rtspUrl,
        String bufferDir,
        int segmentSeconds,
        long minFreeDiskGb,
        int reconnectDelaySeconds,
        int maxReconnectDelaySeconds
) implements CollectorConfig {

    public static final String DEFAULT_RTSP_URL = "rtsp://localhost:554/stream1";
    public static final String DEFAULT_BUFFER_DIR = "/mnt/buffer/cctv";
    public static final int DEFAULT_SEGMENT_SECONDS = 900;
    public static final long DEFAULT_MIN_FREE_DISK_GB = 5L;
    public static final int DEFAULT_RECONNECT_DELAY_SECONDS = 5;
    public static final int DEFAULT_MAX_RECONNECT_DELAY_SECONDS = 60;

    /**
     * Creates a test configuration instance populated with standard default values.
     */
    public static TestCollectorConfig createDefault() {
        return new TestCollectorConfig(
                DEFAULT_RTSP_URL,
                DEFAULT_BUFFER_DIR,
                DEFAULT_SEGMENT_SECONDS,
                DEFAULT_MIN_FREE_DISK_GB,
                DEFAULT_RECONNECT_DELAY_SECONDS,
                DEFAULT_MAX_RECONNECT_DELAY_SECONDS
        );
    }

    public TestCollectorConfig withRtspUrl(String rtspUrl) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestCollectorConfig withBufferDir(String bufferDir) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestCollectorConfig withSegmentSeconds(int segmentSeconds) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestCollectorConfig withMinFreeDiskGb(long minFreeDiskGb) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestCollectorConfig withReconnectDelaySeconds(int reconnectDelaySeconds) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestCollectorConfig withMaxReconnectDelaySeconds(int maxReconnectDelaySeconds) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }
}
