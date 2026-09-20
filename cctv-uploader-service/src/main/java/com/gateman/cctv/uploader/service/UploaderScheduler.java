package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.model.UploadTask;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled job orchestrator triggering directory scanning and batch uploads.
 */
@ApplicationScoped
public class UploaderScheduler {

    private static final Logger LOG = Logger.getLogger(UploaderScheduler.class);

    private final UploaderConfig config;
    private final SegmentScanner scanner;
    private final UploadExecutorService executor;
    private final TaskDao taskDao;
    private final UploadHealthTracker healthTracker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    public UploaderScheduler(
            UploaderConfig config,
            SegmentScanner scanner,
            UploadExecutorService executor,
            TaskDao taskDao,
            UploadHealthTracker healthTracker
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.taskDao = Objects.requireNonNull(taskDao, "taskDao must not be null");
        this.healthTracker = Objects.requireNonNull(healthTracker, "healthTracker must not be null");
    }

    void onStartup(@Observes StartupEvent ev) {
        LOG.info("UploaderScheduler started. Setting running state to true.");
        this.running.set(true);
        this.healthTracker.setRunning(true);
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        LOG.info("UploaderScheduler shutting down.");
        this.running.set(false);
        this.healthTracker.setRunning(false);
    }

    /**
     * Cron-scheduled batch scan and upload job.
     */
    @Scheduled(cron = "{cctv.uploader.scan-cron-expression}")
    public void scheduledScanAndUpload() {
        if (!running.get()) {
            return;
        }

        LOG.info("Executing scheduled buffer directory scan...");
        try {
            // 1. Scan and register new eligible segments
            scanner.scanEligibleSegments();

            // 2. Fetch all pending tasks from DAO
            List<UploadTask> pending = taskDao.findPendingTasks();
            if (!pending.isEmpty()) {
                LOG.infof("Found %d pending upload tasks. Launching batch upload...", pending.size());
                executor.executeBatch(pending);
            } else {
                LOG.debug("No pending segments eligible for upload at this time.");
            }
        } catch (Exception e) {
            LOG.errorf(e, "Scheduled upload job encountered an error: %s", e.getMessage());
        }
    }

    /**
     * Triggers an immediate, on-demand scan and upload execution.
     *
     * @return number of tasks successfully processed
     */
    public synchronized int triggerManualUpload() {
        LOG.info("Manual upload triggered via API.");
        scanner.scanEligibleSegments();
        List<UploadTask> pending = taskDao.findPendingTasks();
        return executor.executeBatch(pending);
    }

    public boolean isRunning() {
        return running.get();
    }
}
