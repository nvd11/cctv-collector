package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.model.UploadStatusSnapshot;
import com.gateman.cctv.uploader.model.UploadTask;

import java.util.List;

/**
 * Enterprise domain service facade coordinating cloud batch upload operations and telemetry.
 */
public interface UploaderService {

    /**
     * Manually triggers an immediate scan and batch upload.
     *
     * @return number of successfully processed tasks
     */
    int triggerUpload();

    /**
     * Retrieves a point-in-time snapshot of uploader operational metrics.
     *
     * @return current {@link UploadStatusSnapshot}
     */
    UploadStatusSnapshot getStatus();

    /**
     * Queries recently finalized or active upload tasks.
     *
     * @param limit maximum tasks to return
     * @return list of {@link UploadTask} records
     */
    List<UploadTask> getRecentTasks(int limit);

    /**
     * Evaluates Liveness health.
     */
    boolean isHealthy();

    /**
     * Evaluates Readiness health.
     */
    boolean isReady();
}
