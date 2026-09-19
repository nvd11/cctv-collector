package com.gateman.cctv.collector.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain model representing a single recorded MP4 video segment on disk.
 * <p>
 * Built as a Java 21 Record to guarantee immutability, thread-safety, and seamless JSON serialization.
 * Tracks the complete lifecycle of a video segment from initial writing to finalized upload.
 *
 * @param fileId          unique identifier for the segment (e.g. "seg_20260920_022630")
 * @param filePath        absolute filesystem path of the MP4 file
 * @param fileName        name of the MP4 file (e.g. "cctv_20260920_022630.mp4")
 * @param startTime       point in time when the segment recording began
 * @param endTime         point in time when the segment was finalized and closed (null if still active)
 * @param durationSeconds actual recorded duration in seconds
 * @param fileSizeBytes   physical size of the file on disk in bytes
 * @param status          current lifecycle status of the segment
 */
public record VideoSegment(
        String fileId,
        Path filePath,
        String fileName,
        Instant startTime,
        Instant endTime,
        long durationSeconds,
        long fileSizeBytes,
        SegmentStatus status
) {

    /**
     * Compact constructor enforcing non-null assertions and domain invariants.
     */
    public VideoSegment {
        Objects.requireNonNull(fileId, "fileId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(status, "status must not be null");

        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative, got: " + durationSeconds);
        }
        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must not be negative, got: " + fileSizeBytes);
        }
    }

    /**
     * Factory method creating a new active segment in the {@link SegmentStatus#WRITING} state.
     *
     * @param fileId    unique segment identifier
     * @param filePath  filesystem path of the segment
     * @param startTime recording start time
     * @return new {@link VideoSegment} instance in WRITING status
     */
    public static VideoSegment createWriting(String fileId, Path filePath, Instant startTime) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        String fileName = filePath.getFileName() != null ? filePath.getFileName().toString() : fileId;
        return new VideoSegment(fileId, filePath, fileName, startTime, null, 0L, 0L, SegmentStatus.WRITING);
    }

    /**
     * Determines whether this segment is completely finalized and ready for cloud upload.
     *
     * @return {@code true} if status is CLOSED and physical size > 0
     */
    public boolean isReadyForUpload() {
        return status == SegmentStatus.CLOSED && fileSizeBytes > 0;
    }

    /**
     * Derives a new immutable segment marked as {@link SegmentStatus#CLOSED}.
     *
     * @param closedTime      time when FFmpeg completed closing the file
     * @param durationSeconds recorded duration in seconds
     * @param fileSizeBytes   final physical size in bytes
     * @return new {@link VideoSegment} with CLOSED status
     */
    public VideoSegment markClosed(Instant closedTime, long durationSeconds, long fileSizeBytes) {
        return new VideoSegment(fileId, filePath, fileName, startTime, closedTime, durationSeconds, fileSizeBytes, SegmentStatus.CLOSED);
    }

    /**
     * Derives a new immutable segment marked as {@link SegmentStatus#COMMITTED} upon successful cloud upload.
     *
     * @return new {@link VideoSegment} with COMMITTED status
     */
    public VideoSegment markCommitted() {
        return new VideoSegment(fileId, filePath, fileName, startTime, endTime, durationSeconds, fileSizeBytes, SegmentStatus.COMMITTED);
    }

    /**
     * Derives a new immutable segment marked as {@link SegmentStatus#CORRUPTED}.
     *
     * @return new {@link VideoSegment} with CORRUPTED status
     */
    public VideoSegment markCorrupted() {
        return new VideoSegment(fileId, filePath, fileName, startTime, endTime, durationSeconds, fileSizeBytes, SegmentStatus.CORRUPTED);
    }
}
