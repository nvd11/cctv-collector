package com.gateman.cctv.collector.dao;

import com.gateman.cctv.collector.model.SegmentStatus;
import com.gateman.cctv.collector.model.VideoSegment;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Enterprise Data Access Object (DAO) providing high-performance in-memory state management
 * and query capabilities for recorded MP4 video segments.
 * <p>
 * Decouples physical disk I/O from upper-level REST API controllers and upload workers.
 * Maintains an in-memory view of active writing segments and a bounded FIFO queue of recently completed segments.
 */
@ApplicationScoped
public class SegmentDao {

    private static final Logger LOG = Logger.getLogger(SegmentDao.class);
    private static final int MAX_RECENT_CAPACITY = 200;

    private final ConcurrentMap<String, VideoSegment> activeSegments = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<VideoSegment> recentSegments = new ConcurrentLinkedDeque<>();

    /**
     * Registers a new video segment currently in the active {@link SegmentStatus#WRITING} state.
     *
     * @param path the filesystem path of the new segment file
     * @return created {@link VideoSegment} instance
     */
    public VideoSegment registerWriting(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        String fileId = deriveFileId(path);
        VideoSegment segment = VideoSegment.createWriting(fileId, path.toAbsolutePath(), Instant.now());

        activeSegments.put(fileId, segment);
        LOG.debugf("Registered active segment [WRITING]: %s (id: %s)", path.getFileName(), fileId);
        return segment;
    }

    /**
     * Finalizes and transitions an active segment to the {@link SegmentStatus#CLOSED} state.
     * Inserts the completed segment at the head of the recent segments queue.
     *
     * @param path          the filesystem path of the finalized segment
     * @param fileSizeBytes finalized physical size on disk in bytes
     * @return updated {@link VideoSegment} with CLOSED status
     */
    public VideoSegment markClosed(Path path, long fileSizeBytes) {
        Objects.requireNonNull(path, "path must not be null");
        String fileId = deriveFileId(path);

        VideoSegment existing = activeSegments.get(fileId);
        Instant startTime = (existing != null) ? existing.startTime() : Instant.now();
        Instant endTime = Instant.now();
        long duration = Math.max(0L, endTime.getEpochSecond() - startTime.getEpochSecond());

        VideoSegment closedSegment = new VideoSegment(
                fileId,
                path.toAbsolutePath(),
                path.getFileName().toString(),
                startTime,
                endTime,
                duration,
                fileSizeBytes,
                SegmentStatus.CLOSED
        );

        activeSegments.put(fileId, closedSegment);
        recentSegments.addFirst(closedSegment);

        // Enforce upper bound on in-memory recent queue to prevent memory leak
        while (recentSegments.size() > MAX_RECENT_CAPACITY) {
            recentSegments.pollLast();
        }

        LOG.infof("Segment marked [CLOSED]: %s (size: %d bytes, duration: %ds)", path.getFileName(), fileSizeBytes, duration);
        return closedSegment;
    }

    /**
     * Transitions a segment to {@link SegmentStatus#COMMITTED} upon verified cloud upload.
     *
     * @param fileId the segment identifier
     * @return {@code true} if segment was found and updated, {@code false} otherwise
     */
    public boolean markCommitted(String fileId) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        VideoSegment existing = activeSegments.get(fileId);
        if (existing != null) {
            VideoSegment committed = existing.markCommitted();
            activeSegments.put(fileId, committed);
            LOG.infof("Segment marked [COMMITTED]: %s", fileId);
            return true;
        }
        return false;
    }

    /**
     * Transitions a segment to {@link SegmentStatus#CORRUPTED} if damaged or incomplete.
     *
     * @param fileId the segment identifier
     * @return {@code true} if segment was found and updated, {@code false} otherwise
     */
    public boolean markCorrupted(String fileId) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        VideoSegment existing = activeSegments.get(fileId);
        if (existing != null) {
            VideoSegment corrupted = existing.markCorrupted();
            activeSegments.put(fileId, corrupted);
            LOG.warnf("Segment marked [CORRUPTED]: %s", fileId);
            return true;
        }
        return false;
    }

    /**
     * Retrieves an immutable list of the most recent completed segments, ordered newest first.
     *
     * @param limit maximum number of records to return (defaults to 10 if non-positive)
     * @return unmodifiable list of {@link VideoSegment} records
     */
    public List<VideoSegment> getRecentSegments(int limit) {
        int max = (limit > 0) ? limit : 10;
        List<VideoSegment> result = new ArrayList<>();
        int count = 0;
        for (VideoSegment seg : recentSegments) {
            if (count++ >= max) {
                break;
            }
            result.add(seg);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Retrieves the latest completed or currently writing segment.
     *
     * @return {@link Optional} containing the latest segment if available
     */
    public Optional<VideoSegment> getLatestSegment() {
        VideoSegment latest = recentSegments.peekFirst();
        if (latest != null) {
            return Optional.of(latest);
        }
        return activeSegments.values().stream().findFirst();
    }

    /**
     * Finds all segments currently eligible for cloud upload (CLOSED status with size > 0).
     *
     * @return unmodifiable list of uploadable segments
     */
    public List<VideoSegment> findReadyForUpload() {
        List<VideoSegment> ready = new ArrayList<>();
        for (VideoSegment seg : activeSegments.values()) {
            if (seg.isReadyForUpload()) {
                ready.add(seg);
            }
        }
        return Collections.unmodifiableList(ready);
    }

    /**
     * Finds a segment by its unique identifier.
     *
     * @param fileId unique segment identifier
     * @return {@link Optional} containing the segment if found
     */
    public Optional<VideoSegment> findById(String fileId) {
        if (fileId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSegments.get(fileId));
    }

    /**
     * Returns the total count of active segments currently tracked in memory.
     *
     * @return total active segment count
     */
    public long count() {
        return activeSegments.size();
    }

    /**
     * Clears all in-memory segment records.
     */
    public void clear() {
        activeSegments.clear();
        recentSegments.clear();
        LOG.debug("SegmentDao in-memory store cleared");
    }

    private String deriveFileId(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".mp4")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }
}
