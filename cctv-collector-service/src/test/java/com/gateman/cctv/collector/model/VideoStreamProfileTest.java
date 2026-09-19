package com.gateman.cctv.collector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoStreamProfileTest {

    private final String rawUrlWithPass = "rtsp://admin:secretPassword123@10.0.1.20:554/stream1";

    @Test
    @DisplayName("Should create default profile matching hardware specs and mask sensitive credentials")
    void shouldCreateDefaultProfileWithMaskedUrl() {
        VideoStreamProfile profile = VideoStreamProfile.createDefault(rawUrlWithPass, true);

        assertThat(profile.streamUrl()).isEqualTo("rtsp://admin:****@10.0.1.20:554/stream1");
        assertThat(profile.protocol()).isEqualTo("RTSP/TCP");
        assertThat(profile.videoCodec()).isEqualTo("H.265 (HEVC)");
        assertThat(profile.audioCodec()).isEqualTo("AAC (16000Hz, mono)");
        assertThat(profile.resolutionWidth()).isEqualTo(2560);
        assertThat(profile.resolutionHeight()).isEqualTo(1440);
        assertThat(profile.fps()).isEqualTo(15);
        assertThat(profile.bitRateBps()).isEqualTo(1_500_000L);
        assertThat(profile.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Should properly mask RTSP URLs and handle URLs without credentials")
    void shouldSanitizeRtspUrlsCorrectly() {
        assertThat(VideoStreamProfile.sanitizeStreamUrl("rtsp://admin:ga32565624@10.0.1.20:554/stream1"))
                .isEqualTo("rtsp://admin:****@10.0.1.20:554/stream1");

        assertThat(VideoStreamProfile.sanitizeStreamUrl("rtsp://10.0.1.20:554/stream1"))
                .isEqualTo("rtsp://10.0.1.20:554/stream1");

        assertThat(VideoStreamProfile.sanitizeStreamUrl(null)).isEmpty();
    }

    @Test
    @DisplayName("Should validate compact constructor and reject negative numbers or nulls")
    void shouldRejectInvalidProfileArguments() {
        assertThatThrownBy(() -> new VideoStreamProfile(null, "TCP", "H.265", "AAC", 1920, 1080, 15, 1000, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("streamUrl must not be null");

        assertThatThrownBy(() -> new VideoStreamProfile("rtsp://test", "TCP", "H.265", "AAC", -1, 1080, 15, 1000, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resolution dimensions must not be negative");

        assertThatThrownBy(() -> new VideoStreamProfile("rtsp://test", "TCP", "H.265", "AAC", 1920, 1080, -15, 1000, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FPS must not be negative");

        assertThatThrownBy(() -> new VideoStreamProfile("rtsp://test", "TCP", "H.265", "AAC", 1920, 1080, 15, -1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BitRate must not be negative");
    }

    @Test
    @DisplayName("Should support wither methods for immutable status updates")
    void shouldSupportWitherTransitions() {
        VideoStreamProfile initial = VideoStreamProfile.createDefault(rawUrlWithPass, false);
        assertThat(initial.isAlive()).isFalse();

        VideoStreamProfile active = initial.withAlive(true);
        assertThat(active.isAlive()).isTrue();
        assertThat(active.streamUrl()).isEqualTo(initial.streamUrl());

        VideoStreamProfile withHigherBitrate = active.withBitRate(2_000_000L);
        assertThat(withHigherBitrate.bitRateBps()).isEqualTo(2_000_000L);
        assertThat(withHigherBitrate.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Should adhere to Record value semantics for equals and hashCode")
    void shouldAdhereToRecordValueSemantics() {
        VideoStreamProfile p1 = VideoStreamProfile.createDefault(rawUrlWithPass, true);
        VideoStreamProfile p2 = VideoStreamProfile.createDefault(rawUrlWithPass, true);

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
        assertThat(p1.toString()).contains("2560", "1440", "H.265 (HEVC)");
    }
}
