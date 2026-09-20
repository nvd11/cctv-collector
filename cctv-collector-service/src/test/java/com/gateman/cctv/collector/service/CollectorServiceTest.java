package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.dao.SegmentDao;
import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import com.gateman.cctv.collector.model.VideoSegment;
import com.gateman.cctv.collector.model.VideoStreamProfile;
import com.gateman.cctv.collector.supervisor.DiskHealthChecker;
import com.gateman.cctv.collector.supervisor.FFmpegProcessExecutor;
import com.gateman.cctv.collector.supervisor.StreamHealthTracker;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorServiceTest {

    @TempDir
    Path tempDir;

    private CollectorConfig config;
    private DiskHealthChecker diskChecker;
    private StreamHealthTracker healthTracker;
    private FFmpegProcessExecutor executor;
    private SegmentDao segmentDao;
    private SegmentLifecycleWatcher lifecycleWatcher;
    private FFmpegProcessSupervisor supervisor;
    private CollectorServiceImpl collectorService;

    @BeforeEach
    void setUp() {
        config = TestCollectorConfig.createDefault().withBufferDir(tempDir.toString());
        diskChecker = new DiskHealthChecker(config);
        healthTracker = new StreamHealthTracker();
        executor = new FFmpegProcessExecutor();
        segmentDao = new SegmentDao();
        lifecycleWatcher = new SegmentLifecycleWatcher(config, segmentDao, healthTracker);

        supervisor = new FFmpegProcessSupervisor(config, diskChecker, healthTracker, executor, lifecycleWatcher);
        collectorService = new CollectorServiceImpl(config, diskChecker, supervisor, healthTracker, segmentDao);
    }

    @Test
    @DisplayName("Should assemble valid stream status snapshot")
    void shouldAssembleStatusSnapshot() {
        StreamStatusSnapshot status = collectorService.getStatus();
        assertThat(status).isNotNull();
        assertThat(status.running()).isFalse();
        assertThat(status.alive()).isFalse();
    }

    @Test
    @DisplayName("Should assemble sanitized video stream profile")
    void shouldAssembleProfile() {
        VideoStreamProfile profile = collectorService.getProfile();
        assertThat(profile).isNotNull();
        assertThat(profile.streamUrl()).doesNotContain("pass");
        assertThat(profile.protocol()).isEqualTo("RTSP/TCP");
    }

    @Test
    @DisplayName("Should return recent segments from DAO")
    void shouldReturnRecentSegments() {
        segmentDao.markClosed(tempDir.resolve("cctv_01.mp4"), 1000L);
        segmentDao.markClosed(tempDir.resolve("cctv_02.mp4"), 2000L);

        List<VideoSegment> recent = collectorService.getRecentSegments(10);
        assertThat(recent).hasSize(2);

        Optional<VideoSegment> latest = collectorService.getLatestSegment();
        assertThat(latest).isPresent();
        assertThat(latest.get().fileName()).isEqualTo("cctv_02.mp4");
    }

    @Test
    @DisplayName("Should delegate lifecycle controls to supervisor")
    void shouldDelegateLifecycle() {
        assertThat(collectorService.isHealthy()).isFalse();

        collectorService.startCollection();
        assertThat(supervisor.isRunning()).isTrue();
        assertThat(collectorService.isReady()).isTrue();

        collectorService.restartCollection();
        assertThat(supervisor.isRunning()).isTrue();

        collectorService.stopCollection();
        assertThat(supervisor.isRunning()).isFalse();
    }
}
