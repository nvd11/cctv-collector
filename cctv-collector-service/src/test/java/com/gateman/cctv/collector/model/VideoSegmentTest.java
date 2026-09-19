package com.gateman.cctv.collector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoSegmentTest {

    private final Path testPath = Path.of("/mnt/buffer/cctv/cctv_20260920_022630.mp4");
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Should create writing segment using factory method")
    void shouldCreateWritingSegment() {
        VideoSegment segment = VideoSegment.createWriting("seg_001", testPath, now);

        assertThat(segment.fileId()).isEqualTo("seg_001");
        assertThat(segment.filePath()).isEqualTo(testPath);
        assertThat(segment.fileName()).isEqualTo("cctv_20260920_022630.mp4");
        assertThat(segment.startTime()).isEqualTo(now);
        assertThat(segment.endTime()).isNull();
        assertThat(segment.durationSeconds()).isZero();
        assertThat(segment.fileSizeBytes()).isZero();
        assertThat(segment.status()).isEqualTo(SegmentStatus.WRITING);
        assertThat(segment.isReadyForUpload()).isFalse();
    }

    @Test
    @DisplayName("Should validate compact constructor invariants and reject invalid arguments")
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> new VideoSegment(null, testPath, "test.mp4", now, null, 10, 100, SegmentStatus.WRITING))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fileId must not be null");

        assertThatThrownBy(() -> new VideoSegment("id", null, "test.mp4", now, null, 10, 100, SegmentStatus.WRITING))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("filePath must not be null");

        assertThatThrownBy(() -> new VideoSegment("id", testPath, "test.mp4", now, null, -1, 100, SegmentStatus.WRITING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSeconds must not be negative");

        assertThatThrownBy(() -> new VideoSegment("id", testPath, "test.mp4", now, null, 10, -50, SegmentStatus.WRITING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileSizeBytes must not be negative");
    }

    @Test
    @DisplayName("Should correctly evaluate isReadyForUpload only when CLOSED and size > 0")
    void shouldEvaluateReadyForUpload() {
        VideoSegment writing = VideoSegment.createWriting("seg_001", testPath, now);
        assertThat(writing.isReadyForUpload()).isFalse();

        VideoSegment zeroByteClosed = writing.markClosed(now.plusSeconds(10), 10, 0);
        assertThat(zeroByteClosed.isReadyForUpload()).isFalse();

        VideoSegment validClosed = writing.markClosed(now.plusSeconds(10), 10, 500_000);
        assertThat(validClosed.isReadyForUpload()).isTrue();

        VideoSegment committed = validClosed.markCommitted();
        assertThat(committed.isReadyForUpload()).isFalse();
    }

    @Test
    @DisplayName("Should support immutable state transitions via wither methods")
    void shouldSupportStateTransitions() {
        Instant endTime = now.plusSeconds(900);
        VideoSegment initial = VideoSegment.createWriting("seg_001", testPath, now);

        VideoSegment closed = initial.markClosed(endTime, 900, 25_000_000L);
        assertThat(closed.status()).isEqualTo(SegmentStatus.CLOSED);
        assertThat(closed.endTime()).isEqualTo(endTime);
        assertThat(closed.durationSeconds()).isEqualTo(900L);
        assertThat(closed.fileSizeBytes()).isEqualTo(25_000_000L);

        VideoSegment committed = closed.markCommitted();
        assertThat(committed.status()).isEqualTo(SegmentStatus.COMMITTED);

        VideoSegment corrupted = initial.markCorrupted();
        assertThat(corrupted.status()).isEqualTo(SegmentStatus.CORRUPTED);
    }

    @Test
    @DisplayName("Should conform to Java 21 Record value-based equality and toString")
    void shouldConformToRecordValueSemantics() {
        VideoSegment seg1 = new VideoSegment("seg_01", testPath, "cctv.mp4", now, null, 10, 1000, SegmentStatus.WRITING);
        VideoSegment seg2 = new VideoSegment("seg_01", testPath, "cctv.mp4", now, null, 10, 1000, SegmentStatus.WRITING);

        assertThat(seg1).isEqualTo(seg2);
        assertThat(seg1.hashCode()).isEqualTo(seg2.hashCode());
        assertThat(seg1.toString()).contains("seg_01", "cctv.mp4", "WRITING");
    }
}
