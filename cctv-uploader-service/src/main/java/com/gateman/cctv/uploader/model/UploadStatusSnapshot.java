package com.gateman.cctv.uploader.model;

import java.time.Instant;

/**
 * Point-in-time telemetry snapshot capturing cloud uploader health, throughput, and queue metrics.
 */
public record UploadStatusSnapshot(
        boolean running,
        long totalUploadedFiles,
        long totalUploadedBytes,
        long failedUploads,
        long lastUploadTimestamp,
        long secondsSinceLastUpload,
        int activeQueueSize,
        Instant snapshotTime
) {
}
