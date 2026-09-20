package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.dao.SegmentDao;
import com.gateman.cctv.collector.model.SegmentStatus;
import com.gateman.cctv.collector.model.VideoSegment;
import com.gateman.cctv.collector.supervisor.StreamHealthTracker;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentLifecycleWatcherTest {

    @TempDir
    Path tempDir;

    private SegmentDao segmentDao;
    private StreamHealthTracker healthTracker;
    private SegmentLifecycleWatcher watcher;
    private CollectorConfig config;

    @BeforeEach
    void setUp() {
        segmentDao = new SegmentDao();
        healthTracker = new StreamHealthTracker();
        config = TestCollectorConfig.createDefault().withBufferDir(tempDir.toString());
        watcher = new SegmentLifecycleWatcher(config, segmentDao, healthTracker);
    }

    @Test
    @DisplayName("Should register newly opened segment in WRITING state")
    void shouldRegisterOpenedSegment() {
        Path file = tempDir.resolve("cctv_20260920_100000.mp4");
        VideoSegment segment = watcher.onSegmentOpened(file);

        assertThat(segment.status()).isEqualTo(SegmentStatus.WRITING);
        assertThat(segment.fileId()).isEqualTo("cctv_20260920_100000");
        assertThat(segmentDao.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should finalize completed segment, update DAO and notify health tracker")
    void shouldFinalizeCompletedSegment() throws IOException {
        Path file = tempDir.resolve("cctv_20260920_101500.mp4");
        Files.write(file, new byte[1024]); // 1KB mock file

        watcher.onSegmentOpened(file);
        VideoSegment closed = watcher.onSegmentCompleted(file);

        assertThat(closed.status()).isEqualTo(SegmentStatus.CLOSED);
        assertThat(closed.fileSizeBytes()).isEqualTo(1024L);

        // Verify health tracker recorded completion
        assertThat(healthTracker.getStatusSnapshot().totalSegmentsWritten()).isEqualTo(1L);

        // Verify ready for upload
        assertThat(watcher.listReadySegments()).contains(closed);
    }

    @Test
    @DisplayName("Should mark zero-byte finalized file as CORRUPTED")
    void shouldMarkZeroByteFileAsCorrupted() throws IOException {
        Path file = tempDir.resolve("cctv_20260920_103000.mp4");
        Files.createFile(file); // 0 bytes

        watcher.onSegmentOpened(file);
        VideoSegment corrupted = watcher.onSegmentCompleted(file);

        assertThat(corrupted.status()).isEqualTo(SegmentStatus.CORRUPTED);
        assertThat(healthTracker.getStatusSnapshot().totalSegmentsWritten()).isZero();
    }

    @Test
    @DisplayName("Should scan buffer directory and re-hydrate historical segments into memory")
    void shouldScanAndRehydrateHistoricalSegments() throws IOException {
        // Create 2 historical closed files (older than 10 seconds)
        Path file1 = tempDir.resolve("cctv_20260920_080000.mp4");
        Path file2 = tempDir.resolve("cctv_20260920_081500.mp4");
        Files.write(file1, new byte[5000]);
        Files.write(file2, new byte[8000]);

        FileTime pastTime = FileTime.from(Instant.now().minusSeconds(60));
        Files.setLastModifiedTime(file1, pastTime);
        Files.setLastModifiedTime(file2, pastTime);

        // Create 1 corrupted zero-byte file
        Path emptyFile = tempDir.resolve("cctv_20260920_083000.mp4");
        Files.createFile(emptyFile);

        // Execute directory re-hydration
        List<VideoSegment> recovered = watcher.scanBufferDirectory();

        assertThat(recovered).hasSize(3);
        assertThat(segmentDao.count()).isEqualTo(3L);

        // The two non-empty historical files must be marked CLOSED and ready for upload
        List<VideoSegment> ready = watcher.listReadySegments();
        assertThat(ready).hasSize(2);
        assertThat(ready).extracting(VideoSegment::fileName)
                .containsExactly("cctv_20260920_080000.mp4", "cctv_20260920_081500.mp4");

        // The zero-byte file must be marked CORRUPTED
        VideoSegment corrupted = segmentDao.findById("cctv_20260920_083000").orElseThrow();
        assertThat(corrupted.status()).isEqualTo(SegmentStatus.CORRUPTED);
    }
}
