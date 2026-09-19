package com.gateman.cctv.collector.model;

/**
 * Lifecycle status of an individual MP4 video segment buffered on local disk.
 * <p>
 * Dictates concurrency boundaries between the continuous stream collector
 * and the asynchronous batch uploader service.
 */
public enum SegmentStatus {

    /**
     * The segment file is currently being actively written by the FFmpeg subprocess.
     * Downstream batch uploaders must NOT read or touch this file.
     */
    WRITING("Actively being written by FFmpeg"),

    /**
     * The segment has been finalized and closed with valid moov atom headers.
     * The file is stable and ready for asynchronous upload to cloud storage.
     */
    CLOSED("Finalized and ready for upload"),

    /**
     * The segment has been successfully uploaded and acknowledged by the cloud storage uploader.
     * Eligible for local disk cleanup.
     */
    COMMITTED("Uploaded and confirmed in cloud storage"),

    /**
     * The segment is corrupted, zero-byte, or damaged due to abnormal termination.
     * Marked for diagnostic quarantine or discard.
     */
    CORRUPTED("Corrupted or damaged segment");

    private final String description;

    SegmentStatus(String description) {
        this.description = description;
    }

    /**
     * Returns a human-readable description of this lifecycle status.
     *
     * @return status description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Determines whether the segment is eligible for cloud upload processing.
     *
     * @return {@code true} if status is {@link #CLOSED}, {@code false} otherwise
     */
    public boolean isReadyForUpload() {
        return this == CLOSED;
    }

    /**
     * Determines whether this status represents a terminal state.
     *
     * @return {@code true} if status is {@link #COMMITTED} or {@link #CORRUPTED}
     */
    public boolean isTerminal() {
        return this == COMMITTED || this == CORRUPTED;
    }
}
