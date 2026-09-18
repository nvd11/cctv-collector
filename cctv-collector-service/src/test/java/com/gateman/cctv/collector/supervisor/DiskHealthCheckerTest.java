package com.gateman.cctv.collector.supervisor;

import com.gateman.cctv.collector.config.CollectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiskHealthCheckerTest {

    @Test
    @DisplayName("Should create buffer directory when it does not exist")
    void shouldCreateBufferDirectoryIfMissing(@TempDir Path tempDir) {
        Path targetDir = tempDir.resolve("cctv-buffer-test");
        assertThat(Files.exists(targetDir)).isFalse();

        CollectorConfig config = createTestConfig(targetDir.toString(), 1L);
        DiskHealthChecker checker = new DiskHealthChecker(config);

        Path resolvedPath = checker.getBufferPath();
        assertThat(resolvedPath).isEqualTo(targetDir);
        assertThat(Files.exists(resolvedPath)).isTrue();
    }

    @Test
    @DisplayName("Should report healthy when free space exceeds minimum threshold")
    void shouldBeHealthyWhenFreeSpaceExceedsThreshold(@TempDir Path tempDir) {
        // Set threshold to 0 GB (or 1 GB on systems with free space)
        CollectorConfig config = createTestConfig(tempDir.toString(), 0L);
        DiskHealthChecker checker = new DiskHealthChecker(config);

        assertThat(checker.isDiskHealthy()).isTrue();
        assertThat(checker.getFreeDiskSpaceGb()).isGreaterThanOrEqualTo(0L);
        assertThat(checker.getUsableSpaceBytes()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Should trigger circuit breaker when free space is below threshold")
    void shouldTriggerCircuitBreakerWhenSpaceInsufficient(@TempDir Path tempDir) {
        // Set an impossible threshold (e.g. 10,000,000 GB)
        CollectorConfig config = createTestConfig(tempDir.toString(), 10_000_000L);
        DiskHealthChecker checker = new DiskHealthChecker(config);

        assertThat(checker.isDiskHealthy()).isFalse();
    }

    private CollectorConfig createTestConfig(String bufferDir, long minFreeDiskGb) {
        return new CollectorConfig() {
            @Override
            public String rtspUrl() {
                return "rtsp://localhost:554/stream1";
            }

            @Override
            public String bufferDir() {
                return bufferDir;
            }

            @Override
            public int segmentSeconds() {
                return 900;
            }

            @Override
            public long minFreeDiskGb() {
                return minFreeDiskGb;
            }

            @Override
            public int reconnectDelaySeconds() {
                return 5;
            }

            @Override
            public int maxReconnectDelaySeconds() {
                return 60;
            }
        };
    }
}
