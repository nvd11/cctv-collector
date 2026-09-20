package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.dao.SegmentDao;
import com.gateman.cctv.collector.model.VideoSegment;
import com.gateman.cctv.collector.supervisor.StreamHealthTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Enterprise service responsible for auditing physical disk files and coordinating segment lifecycles.
 * <p>
 * Bridges physical disk files in {@link CollectorConfig#bufferDir()} with the in-memory {@link SegmentDao}.
 * Responsible for:
 * <ul>
 *   <li>Auditing physical file stability and size before marking segments as {@code CLOSED};</li>
 *   <li>Pumping segment completion events into {@link StreamHealthTracker#recordSegmentCompleted()};</li>
 *   <li><b>Pod Startup Re-hydration (scanBufferDirectory):</b> Automatically scanning existing historical MP4
 *       files upon container startup, restoring the in-memory DAO state without data loss across restarts.</li>
 * </ul>
 */
@ApplicationScoped
public class SegmentLifecycleWatcher {

    private static final Logger LOG = Logger.getLogger(SegmentLifecycleWatcher.class);
    private static final long WRITING_RECENCY_THRESHOLD_SECONDS = 5L;

    private final CollectorConfig config;
    private final SegmentDao segmentDao;
    private final StreamHealthTracker healthTracker;

    /**
     * CDI constructor injection point.
     *
     * @param config        collector configuration
     * @param segmentDao    enterprise segment DAO
     * @param healthTracker stream health and metrics tracker
     */
    @Inject
    public SegmentLifecycleWatcher(
            CollectorConfig config,
            SegmentDao segmentDao,
            StreamHealthTracker healthTracker
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.segmentDao = Objects.requireNonNull(segmentDao, "segmentDao must not be null");
        this.healthTracker = Objects.requireNonNull(healthTracker, "healthTracker must not be null");
    }

    /**
     * Invoked when FFmpeg initiates writing a new segment file.
     *
     * @param filePath physical path of the newly opened MP4 file
     * @return created {@link VideoSegment} in WRITING state
     */
    public VideoSegment onSegmentOpened(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        LOG.infof("Segment file opened for writing: %s", filePath.getFileName());
        return segmentDao.registerWriting(filePath);
    }

    /**
     * Invoked when FFmpeg finalizes and closes an MP4 segment.
     * Audits file size and transitions the segment to CLOSED in the DAO while notifying health tracker.
     *
     * @param filePath physical path of the closed MP4 file
     * @return updated {@link VideoSegment} in CLOSED state, or CORRUPTED if empty
     */
    public VideoSegment onSegmentCompleted(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");

        long fileSize = safeGetFileSize(filePath);
        if (fileSize <= 0) {
            LOG.warnf("Segment file %s has invalid size (%d bytes). Marking CORRUPTED.", filePath.getFileName(), fileSize);
            String fileId = deriveFileId(filePath);
            segmentDao.markCorrupted(fileId);
            return segmentDao.findById(fileId).orElse(null);
        }

        VideoSegment closedSegment = segmentDao.markClosed(filePath, fileSize);
        healthTracker.recordSegmentCompleted();

        LOG.infof("Segment file finalized and verified: %s (%d bytes)", filePath.getFileName(), fileSize);
        return closedSegment;
    }

    /**
     * Scans the configured buffer storage directory to discover and re-hydrate existing historical MP4 segments.
     * <p>
     * Ensures container restart resilience: recovers historical segments into the {@link SegmentDao} in-memory view.
     *
     * @return unmodifiable list of recovered segments ordered chronologically
     */
    public List<VideoSegment> scanBufferDirectory() {
        Path bufferDir = Paths.get(config.bufferDir());
        if (!Files.exists(bufferDir) || !Files.isDirectory(bufferDir)) {
            LOG.warnf("Buffer directory does not exist for scanning: %s", bufferDir);
            return Collections.emptyList();
        }

        List<Path> mp4Files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bufferDir, "*.mp4")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    mp4Files.add(entry);
                }
            }
        } catch (IOException e) {
            LOG.errorf(e, "Failed to scan buffer directory: %s", bufferDir);
            return Collections.emptyList();
        }

        // Sort files chronologically by filename (timestamp format: cctv_YYYYMMDD_HHMMSS.mp4)
        mp4Files.sort(Comparator.comparing(Path::getFileName));

        List<VideoSegment> recovered = new ArrayList<>();
        long nowSeconds = Instant.now().getEpochSecond();

        for (Path file : mp4Files) {
            long size = safeGetFileSize(file);
            long lastModifiedSeconds = safeGetLastModifiedSeconds(file);
            long ageSeconds = Math.max(0L, nowSeconds - lastModifiedSeconds);

            if (size <= 0) {
                String fileId = deriveFileId(file);
                segmentDao.registerWriting(file);
                segmentDao.markCorrupted(fileId);
                segmentDao.findById(fileId).ifPresent(recovered::add);
            } else if (ageSeconds <= WRITING_RECENCY_THRESHOLD_SECONDS) {
                recovered.add(segmentDao.registerWriting(file));
            } else {
                recovered.add(segmentDao.markClosed(file, size));
            }
        }

        LOG.infof("Buffer directory re-hydration complete: recovered %d segments from %s", recovered.size(), bufferDir);
        return Collections.unmodifiableList(recovered);
    }

    /**
     * Lists all segments currently ready for cloud upload.
     *
     * @return unmodifiable list of uploadable segments
     */
    public List<VideoSegment> listReadySegments() {
        return segmentDao.findReadyForUpload();
    }

    private long safeGetFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            LOG.warnf("Unable to determine file size for %s: %s", path, e.getMessage());
            return -1L;
        }
    }

    private long safeGetLastModifiedSeconds(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant().getEpochSecond();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String deriveFileId(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".mp4")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }
}
