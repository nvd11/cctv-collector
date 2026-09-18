package com.gateman.cctv.collector.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CollectorConfigTest {

    @Inject
    CollectorConfig config;

    @Test
    @DisplayName("Should properly inject CollectorConfig with default values")
    void testConfigInjectionAndDefaults() {
        assertThat(config).isNotNull();
        assertThat(config.rtspUrl()).isNotBlank();
        assertThat(config.bufferDir()).isEqualTo("/mnt/buffer/cctv");
        assertThat(config.segmentSeconds()).isEqualTo(900);
        assertThat(config.minFreeDiskGb()).isEqualTo(5L);
        assertThat(config.reconnectDelaySeconds()).isEqualTo(5);
        assertThat(config.maxReconnectDelaySeconds()).isEqualTo(60);
    }
}
