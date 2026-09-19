package com.gateman.cctv.collector.supervisor;

import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamHealthTrackerTest {

    private StreamHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new StreamHealthTracker();
    }

    @Test
    @DisplayName("Should initialize with clean zeroed state")
    void shouldInitializeWithZeroState() {
        StreamStatusSnapshot snapshot = tracker.getStatusSnapshot();

        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.alive()).isFalse();
        assertThat(snapshot.lastFrameTimestamp()).isZero();
        assertThat(snapshot.secondsSinceLastFrame()).isEqualTo(-1L);
        assertThat(snapshot.restartCount()).isZero();
        assertThat(snapshot.totalSegmentsWritten()).isZero();
        assertThat(snapshot.snapshotTime()).isNotNull();
    }

    @Test
    @DisplayName("Should update telemetry on frame progress")
    void shouldUpdateOnFrameProgress() {
        tracker.recordFrameProgress();

        StreamStatusSnapshot snapshot = tracker.getStatusSnapshot();
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.alive()).isTrue();
        assertThat(snapshot.lastFrameTimestamp()).isPositive();
        assertThat(snapshot.secondsSinceLastFrame()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Should track disconnection counts and flag stream as not alive")
    void shouldTrackDisconnections() {
        tracker.recordFrameProgress();
        assertThat(tracker.getStatusSnapshot().alive()).isTrue();

        tracker.recordDisconnection();
        StreamStatusSnapshot snapshot = tracker.getStatusSnapshot();
        assertThat(snapshot.alive()).isFalse();
        assertThat(snapshot.restartCount()).isEqualTo(1L);

        tracker.recordDisconnection();
        assertThat(tracker.getStatusSnapshot().restartCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should increment total segments written count")
    void shouldTrackCompletedSegments() {
        tracker.recordSegmentCompleted();
        tracker.recordSegmentCompleted();
        tracker.recordSegmentCompleted();

        assertThat(tracker.getStatusSnapshot().totalSegmentsWritten()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should detect stream stall when elapsed time exceeds timeout")
    void shouldDetectStreamStall() throws InterruptedException {
        // Case 1: not running -> not stalled
        assertThat(tracker.isStreamStalled(100)).isFalse();

        // Case 2: running but no frame yet -> not stalled (startup grace period)
        tracker.setRunning(true);
        assertThat(tracker.isStreamStalled(100)).isFalse();

        // Case 3: frame just arrived -> not stalled
        tracker.recordFrameProgress();
        assertThat(tracker.isStreamStalled(5000)).isFalse();

        // Case 4: wait past small timeout window -> stalled
        Thread.sleep(60);
        assertThat(tracker.isStreamStalled(50)).isTrue();
    }

    @Test
    @DisplayName("Should reset all accumulators properly")
    void shouldResetProperly() {
        tracker.recordFrameProgress();
        tracker.recordRestart();
        tracker.recordSegmentCompleted();

        tracker.reset();
        StreamStatusSnapshot snapshot = tracker.getStatusSnapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.alive()).isFalse();
        assertThat(snapshot.restartCount()).isZero();
        assertThat(snapshot.totalSegmentsWritten()).isZero();
    }
}
