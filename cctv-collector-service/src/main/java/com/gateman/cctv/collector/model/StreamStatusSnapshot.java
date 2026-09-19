package com.gateman.cctv.collector.model;

import java.time.Instant;

/**
 * Immutable telemetry snapshot capturing real-time stream health and supervisor performance metrics.
 * <p>
 * Built as a Java 21 Record for fast, zero-copy JSON serialization via Quarkus REST.
 *
 * @param running              whether the supervisor watchdog is currently active
 * @param alive                whether video frames are actively flowing from the camera
 * @param lastFrameTimestamp   epoch millisecond timestamp of the most recent frame progress update
 * @param secondsSinceLastFrame seconds elapsed since the last frame was received
 * @param restartCount         cumulative count of abnormal reconnections
 * @param totalSegmentsWritten cumulative number of successfully finalized segments
 * @param snapshotTime         exact point in time when this telemetry snapshot was captured
 */
public record StreamStatusSnapshot(
        boolean running,
        boolean alive,
        long lastFrameTimestamp,
        long secondsSinceLastFrame,
        long restartCount,
        long totalSegmentsWritten,
        Instant snapshotTime
) {
}
