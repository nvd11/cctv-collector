package com.gateman.cctv.collector.supervisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FFmpegLogPumpTest {

    private StreamHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new StreamHealthTracker();
    }

    @Test
    @DisplayName("Should drain input stream, parse frame progress, and update health tracker")
    void shouldDrainStreamAndTriggerProgressUpdates() {
        String simulatedOutput = String.join("\n",
                "ffmpeg version 8.0.1 Copyright (c) 2000-2025",
                "[segment @ 0x5555] Opening '/mnt/buffer/cctv/cctv_20260920_023000.mp4' for writing",
                "frame=   15 fps=15.0 q=-1.0 size=     128KiB time=00:00:01.00 bitrate=1048.5kbits/s speed=   1x",
                "frame=   30 fps=15.0 q=-1.0 size=     256KiB time=00:00:02.00 bitrate=1048.5kbits/s speed=   1x",
                "[rtsp @ 0x5555] Connection closed"
        ) + "\n";

        ByteArrayInputStream inputStream = new ByteArrayInputStream(simulatedOutput.getBytes(StandardCharsets.UTF_8));
        List<String> capturedLines = new ArrayList<>();

        FFmpegLogPump pump = new FFmpegLogPump(inputStream, tracker, capturedLines::add);

        // Run pump directly in current thread (it will terminate upon reaching EOF of ByteArrayInputStream)
        pump.run();

        // Verifications
        assertThat(pump.isClosed()).isTrue();
        assertThat(pump.getLastActivityTime()).isPositive();
        assertThat(capturedLines).hasSize(5);

        // Health tracker was updated by the frame progress lines
        assertThat(tracker.getStatusSnapshot().alive()).isTrue();
        assertThat(tracker.getStatusSnapshot().lastFrameTimestamp()).isPositive();
    }

    @Test
    @DisplayName("Should accurately identify frame progress indicator lines")
    void shouldIdentifyFrameProgressLines() {
        assertThat(FFmpegLogPump.isFrameProgressLine(
                "frame=   43 fps= 27 q=-1.0 Lsize=     188KiB time=00:00:03.07 bitrate= 501.3kbits/s speed= 1.9x"
        )).isTrue();

        assertThat(FFmpegLogPump.isFrameProgressLine(
                "size= 100KiB fps=15.0 time=00:00:05.00"
        )).isTrue();

        // Non-progress lines
        assertThat(FFmpegLogPump.isFrameProgressLine("[rtsp @ 0x123] Method DESCRIBE failed")).isFalse();
        assertThat(FFmpegLogPump.isFrameProgressLine("[segment @ 0x123] Opening file for writing")).isFalse();
        assertThat(FFmpegLogPump.isFrameProgressLine(null)).isFalse();
        assertThat(FFmpegLogPump.isFrameProgressLine("")).isFalse();
    }

    @Test
    @DisplayName("Should reject null input stream on construction")
    void shouldRejectNullInputStream() {
        assertThatThrownBy(() -> new FFmpegLogPump(null, tracker))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inputStream must not be null");
    }

    @Test
    @DisplayName("Should cleanly close pump and ignore repeated close calls")
    void shouldCloseCleanly() {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[0]);
        FFmpegLogPump pump = new FFmpegLogPump(inputStream, tracker);

        assertThat(pump.isClosed()).isFalse();
        pump.close();
        assertThat(pump.isClosed()).isTrue();

        // Idempotent close
        pump.close();
        assertThat(pump.isClosed()).isTrue();
    }
}
