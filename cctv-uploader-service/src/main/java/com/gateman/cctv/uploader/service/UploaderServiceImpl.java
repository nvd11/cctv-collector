package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.model.UploadStatusSnapshot;
import com.gateman.cctv.uploader.model.UploadTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class UploaderServiceImpl implements UploaderService {

    private final UploaderConfig config;
    private final UploaderScheduler scheduler;
    private final TaskDao taskDao;
    private final UploadHealthTracker healthTracker;

    @Inject
    public UploaderServiceImpl(
            UploaderConfig config,
            UploaderScheduler scheduler,
            TaskDao taskDao,
            UploadHealthTracker healthTracker
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.taskDao = Objects.requireNonNull(taskDao, "taskDao must not be null");
        this.healthTracker = Objects.requireNonNull(healthTracker, "healthTracker must not be null");
    }

    @Override
    public int triggerUpload() {
        return scheduler.triggerManualUpload();
    }

    @Override
    public UploadStatusSnapshot getStatus() {
        int queueSize = taskDao.findPendingTasks().size();
        return healthTracker.getStatusSnapshot(queueSize);
    }

    @Override
    public List<UploadTask> getRecentTasks(int limit) {
        return taskDao.findRecentTasks(limit);
    }

    @Override
    public boolean isHealthy() {
        return scheduler.isRunning();
    }

    @Override
    public boolean isReady() {
        return scheduler.isRunning() && Files.exists(Paths.get(config.bufferDir()));
    }
}
