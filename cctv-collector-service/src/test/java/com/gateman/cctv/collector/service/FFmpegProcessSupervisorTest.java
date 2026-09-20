package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.dao.SegmentDao;
import com.gateman.cctv.collector.supervisor.DiskHealthChecker;
import com.gateman.cctv.collector.supervisor.FFmpegProcessExecutor;
import com.gateman.cctv.collector.supervisor.StreamHealthTracker;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FFmpegProcessSupervisorTest {

    @TempDir
    Path tempDir;

    private CollectorConfig config;
    private DiskHealthChecker diskChecker;
    private StreamHealthTracker healthTracker;
    private FFmpegProcessExecutor executor;
    private SegmentDao segmentDao;
    private SegmentLifecycleWatcher lifecycleWatcher;
    private FFmpegProcessSupervisor supervisor;

    @BeforeEach
    void setUp() {
        config = TestCollectorConfig.createDefault()
                .withBufferDir(tempDir.toString())
                .withReconnectDelaySeconds(5)
                .withMaxReconnectDelaySeconds(60);

        diskChecker = new DiskHealthChecker(config);
        healthTracker = new StreamHealthTracker();
        executor = new FFmpegProcessExecutor();
        segmentDao = new SegmentDao();
        lifecycleWatcher = new SegmentLifecycleWatcher(config, segmentDao, healthTracker);

        supervisor = new FFmpegProcessSupervisor(
                config,
                diskChecker,
                healthTracker,
                executor,
                lifecycleWatcher
        );
    }

    @Test
    @DisplayName("Should correctly calculate exponential backoff capped at maxReconnectDelaySeconds")
    void shouldCalculateExponentialBackoffAccurately() {
        // config has initial 5s, max 60s
        assertThat(supervisor.calculateBackoff(1)).isEqualTo(5);
        assertThat(supervisor.calculateBackoff(2)).isEqualTo(10);
        assertThat(supervisor.calculateBackoff(3)).isEqualTo(20);
        assertThat(supervisor.calculateBackoff(4)).isEqualTo(40);
        assertThat(supervisor.calculateBackoff(5)).isEqualTo(60); // capped at 60s
        assertThat(supervisor.calculateBackoff(6)).isEqualTo(60);
        assertThat(supervisor.calculateBackoff(100)).isEqualTo(60);
    }

    @Test
    @DisplayName("Should start and stop supervisor lifecycle cleanly")
    void shouldStartAndStopCleanly() throws InterruptedException {
        assertThat(supervisor.isRunning()).isFalse();

        supervisor.startSupervisor();
        assertThat(supervisor.isRunning()).isTrue();
        assertThat(healthTracker.getStatusSnapshot().running()).isTrue();

        // Let it start
        Thread.sleep(100);

        supervisor.stopSupervisor();
        assertThat(supervisor.isRunning()).isFalse();
        assertThat(healthTracker.getStatusSnapshot().running()).isFalse();
    }

    @Test
    @DisplayName("Should support manual restart trigger")
    void shouldSupportManualRestart() {
        supervisor.startSupervisor();
        assertThat(supervisor.isRunning()).isTrue();

        // Manual restart invocation
        supervisor.restartSupervisor();
        assertThat(supervisor.isRunning()).isTrue();

        supervisor.stopSupervisor();
        assertThat(supervisor.isRunning()).isFalse();
    }
}
