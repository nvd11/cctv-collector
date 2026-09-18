package com.gateman.cctv.collector.supervisor;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FFmpegCommandBuilderTest {

    @Test
    @DisplayName("Should build exact FFmpeg argument list from CollectorConfig")
    void shouldBuildExactFFmpegArgumentList() {
        CollectorConfig config = TestCollectorConfig.createDefault()
                .withRtspUrl("rtsp://admin:pass123@10.0.1.20:554/stream1")
                .withBufferDir("/mnt/buffer/cctv")
                .withSegmentSeconds(900);

        List<String> args = FFmpegCommandBuilder.buildArgs(config);

        assertThat(args).containsExactly(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "info",
                "-rtsp_transport", "tcp",
                "-i", "rtsp://admin:pass123@10.0.1.20:554/stream1",
                "-c", "copy",
                "-f", "segment",
                "-segment_time", "900",
                "-segment_format", "mp4",
                "-reset_timestamps", "1",
                "-strftime", "1",
                "/mnt/buffer/cctv/cctv_%Y%m%d_%H%M%S.mp4"
        );
    }

    @Test
    @DisplayName("Should adjust segment duration and output path dynamically")
    void shouldAdjustSegmentDurationAndOutputPath() {
        CollectorConfig config = TestCollectorConfig.createDefault()
                .withRtspUrl("rtsp://127.0.0.1:8554/live")
                .withBufferDir("/data/custom-cctv")
                .withSegmentSeconds(60);

        List<String> args = FFmpegCommandBuilder.buildArgs(config);

        assertThat(args).containsSequence("-segment_time", "60");
        assertThat(args.get(args.size() - 1)).isEqualTo("/data/custom-cctv/cctv_%Y%m%d_%H%M%S.mp4");
    }

    @Test
    @DisplayName("Should return an unmodifiable list")
    void shouldReturnUnmodifiableList() {
        CollectorConfig config = TestCollectorConfig.createDefault();
        List<String> args = FFmpegCommandBuilder.buildArgs(config);

        assertThatThrownBy(() -> args.add("-extra_param"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should throw NullPointerException when config is null")
    void shouldThrowWhenConfigIsNull() {
        assertThatThrownBy(() -> FFmpegCommandBuilder.buildArgs(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CollectorConfig must not be null");
    }
}
