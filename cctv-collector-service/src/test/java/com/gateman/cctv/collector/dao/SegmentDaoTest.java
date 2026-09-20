package com.gateman.cctv.collector.dao;

import com.gateman.cctv.collector.model.SegmentStatus;
import com.gateman.cctv.collector.model.VideoSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentDaoTest {

    private SegmentDao dao;

    @BeforeEach
    void setUp() {
        dao = new SegmentDao();
    }

    @Test
    @DisplayName("Should register writing segment and retrieve by ID")
    void shouldRegisterWritingSegment() {
        Path path = Path.of("/mnt/buffer/cctv/cctv_20260920_120000.mp4");
        VideoSegment segment = dao.registerWriting(path);

        assertThat(segment.fileId()).isEqualTo("cctv_20260920_120000");
        assertThat(segment.status()).isEqualTo(SegmentStatus.WRITING);
        assertThat(dao.count()).isEqualTo(1L);

        Optional<VideoSegment> found = dao.findById("cctv_20260920_120000");
        assertThat(found).isPresent().contains(segment);
    }

    @Test
    @DisplayName("Should transition segment to CLOSED and list under ready for upload")
    void shouldTransitionToClosedAndListReady() {
        Path path = Path.of("/mnt/buffer/cctv/cctv_20260920_121500.mp4");
        dao.registerWriting(path);

        VideoSegment closed = dao.markClosed(path, 15_000_000L);
        assertThat(closed.status()).isEqualTo(SegmentStatus.CLOSED);
        assertThat(closed.fileSizeBytes()).isEqualTo(15_000_000L);

        // Verify ready for upload
        List<VideoSegment> ready = dao.findReadyForUpload();
        assertThat(ready).contains(closed);

        // Verify recent segments
        List<VideoSegment> recent = dao.getRecentSegments(10);
        assertThat(recent).hasSize(1).contains(closed);

        // Verify latest segment
        assertThat(dao.getLatestSegment()).contains(closed);
    }

    @Test
    @DisplayName("Should handle COMMITTED and CORRUPTED state updates")
    void shouldHandleCommittedAndCorruptedStates() {
        Path path = Path.of("/mnt/buffer/cctv/cctv_20260920_123000.mp4");
        dao.registerWriting(path);
        dao.markClosed(path, 5_000_000L);

        boolean committed = dao.markCommitted("cctv_20260920_123000");
        assertThat(committed).isTrue();
        assertThat(dao.findById("cctv_20260920_123000").orElseThrow().status())
                .isEqualTo(SegmentStatus.COMMITTED);

        // Now test marking a separate segment as CORRUPTED
        Path corruptedPath = Path.of("/mnt/buffer/cctv/cctv_20260920_124500.mp4");
        dao.registerWriting(corruptedPath);
        boolean corrupted = dao.markCorrupted("cctv_20260920_124500");
        assertThat(corrupted).isTrue();
        assertThat(dao.findById("cctv_20260920_124500").orElseThrow().status())
                .isEqualTo(SegmentStatus.CORRUPTED);
    }

    @Test
    @DisplayName("Should bound recent queue capacity to prevent unbounded memory growth")
    void shouldBoundRecentSegmentsCapacity() {
        for (int i = 0; i < 250; i++) {
            Path path = Path.of(String.format("/mnt/buffer/cctv/cctv_20260920_%06d.mp4", i));
            dao.markClosed(path, 1000L);
        }

        // Recent segments queue should be capped at MAX_RECENT_CAPACITY (200)
        List<VideoSegment> recent = dao.getRecentSegments(300);
        assertThat(recent).hasSize(200);

        // Latest added (i = 249) should be first
        assertThat(recent.get(0).fileId()).isEqualTo("cctv_20260920_000249");
    }

    @Test
    @DisplayName("Should cleanly clear store")
    void shouldClearStore() {
        dao.registerWriting(Path.of("/mnt/buffer/cctv/test.mp4"));
        assertThat(dao.count()).isEqualTo(1L);

        dao.clear();
        assertThat(dao.count()).isZero();
        assertThat(dao.getRecentSegments(10)).isEmpty();
    }

    @Test
    @DisplayName("Should reject null paths on register and markClosed")
    void shouldRejectNullPaths() {
        assertThatThrownBy(() -> dao.registerWriting(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path must not be null");

        assertThatThrownBy(() -> dao.markClosed(null, 100))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path must not be null");
    }
}
