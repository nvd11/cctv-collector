package com.gateman.cctv.collector.service;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.dao.SegmentDao;
import com.gateman.cctv.collector.model.StreamStatusSnapshot;
import com.gateman.cctv.collector.model.VideoSegment;
import com.gateman.cctv.collector.model.VideoStreamProfile;
import com.gateman.cctv.collector.supervisor.DiskHealthChecker;
import com.gateman.cctv.collector.supervisor.StreamHealthTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production implementation of {@link CollectorService} domain facade.
 */
@ApplicationScoped
public class CollectorServiceImpl implements CollectorService {

    private static final Logger LOG = Logger.getLogger(CollectorServiceImpl.class);

    private final CollectorConfig config;
    private final DiskHealthChecker diskChecker;
    private final FFmpegProcessSupervisor supervisor;
    private final StreamHealthTracker healthTracker;
    private final SegmentDao segmentDao;

    @Inject
    public CollectorServiceImpl(
            CollectorConfig config,
            DiskHealthChecker diskChecker,
            FFmpegProcessSupervisor supervisor,
            StreamHealthTracker healthTracker,
            SegmentDao segmentDao
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.diskChecker = Objects.requireNonNull(diskChecker, "diskChecker must not be null");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.healthTracker = Objects.requireNonNull(healthTracker, "healthTracker must not be null");
        this.segmentDao = Objects.requireNonNull(segmentDao, "segmentDao must not be null");
    }

    @Override
    public void startCollection() {
        LOG.info("CollectorService: starting collection...");
        supervisor.startSupervisor();
    }

    @Override
    public void stopCollection() {
        LOG.info("CollectorService: stopping collection...");
        supervisor.stopSupervisor();
    }

    @Override
    public void restartCollection() {
        LOG.info("CollectorService: restarting collection...");
        supervisor.restartSupervisor();
    }

    @Override
    public StreamStatusSnapshot getStatus() {
        return healthTracker.getStatusSnapshot();
    }

    @Override
    public VideoStreamProfile getProfile() {
        boolean alive = healthTracker.getStatusSnapshot().alive();
        return VideoStreamProfile.createDefault(config.rtspUrl(), alive);
    }

    @Override
    public List<VideoSegment> getRecentSegments(int limit) {
        return segmentDao.getRecentSegments(limit);
    }

    @Override
    public Optional<VideoSegment> getLatestSegment() {
        return segmentDao.getLatestSegment();
    }

    @Override
    public boolean isHealthy() {
        // Liveness backstop: only DOWN after the in-process stall watchdog has had a chance to heal the
        // stream (stallRestartSeconds < stallLivenessSeconds). This keeps liveness as a last resort instead
        // of the primary recovery mechanism, so a transient Wi-Fi hiccup no longer causes Pod churn.
        return supervisor.isRunning() && !healthTracker.isStreamStalled(config.stallLivenessSeconds() * 1000L);
    }

    @Override
    public boolean isReady() {
        // Pod is ready if buffer disk space is healthy and supervisor is initialized
        return diskChecker.isDiskHealthy() && supervisor.isRunning();
    }
}
