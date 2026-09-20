package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.infra.AlistWebDavClient;
import com.gateman.cctv.uploader.model.UploadTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Core batch upload engine managing Alist streaming, rate limiting, and local storage eviction.
 */
@ApplicationScoped
public class UploadExecutorService {

    private static final Logger LOG = Logger.getLogger(UploadExecutorService.class);

    private final UploaderConfig config;
    private final AlistWebDavClient alistClient;
    private final TaskDao taskDao;
    private final UploadHealthTracker healthTracker;

    @Inject
    public UploadExecutorService(
            UploaderConfig config,
            AlistWebDavClient alistClient,
            TaskDao taskDao,
            UploadHealthTracker healthTracker
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.alistClient = Objects.requireNonNull(alistClient, "alistClient must not be null");
        this.taskDao = Objects.requireNonNull(taskDao, "taskDao must not be null");
        this.healthTracker = Objects.requireNonNull(healthTracker, "healthTracker must not be null");
    }

    /**
     * Sequentially executes upload tasks, respecting broadband and rate limit boundaries.
     *
     * @param tasks list of upload tasks to process
     * @return number of successfully processed/skipped tasks
     */
    public int executeBatch(List<UploadTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        LOG.infof("Starting upload batch of %d tasks...", tasks.size());
        int successCount = 0;

        for (UploadTask task : tasks) {
            boolean success = executeSingle(task);
            if (success) {
                successCount++;
            }
        }

        LOG.infof("Upload batch completed: %d/%d tasks successful", successCount, tasks.size());
        return successCount;
    }

    /**
     * Executes a single upload task through the complete lifecycle:
     * 1. Check remote existence (idempotency);
     * 2. Ensure remote parent directory exists;
     * 3. Stream file via WebDAV PUT;
     * 4. Evict local file if cleanup policy is DELETE.
     *
     * @param task the upload task
     * @return {@code true} if upload was confirmed or skipped, {@code false} if failed
     */
    public boolean executeSingle(UploadTask task) {
        Objects.requireNonNull(task, "task must not be null");
        String taskId = task.taskId();
        Path localPath = task.localPath();

        if (!Files.exists(localPath)) {
            LOG.warnf("Local source file missing: %s", localPath);
            taskDao.markFailed(taskId, "Local file missing");
            healthTracker.recordUploadFailure();
            return false;
        }

        // 1. Idempotency check: does file already exist on remote storage with matching size?
        if (alistClient.existsRemoteFile(task.remotePath(), task.fileSizeBytes())) {
            LOG.infof("Remote file already exists with matching size. Marking SKIPPED: %s", task.remotePath());
            taskDao.markSkipped(taskId);
            cleanLocalFile(localPath);
            return true;
        }

        // 2. Ensure parent remote directory exists
        String parentDir = extractParentDir(task.remotePath());
        if (!alistClient.ensureRemoteDirExists(parentDir)) {
            LOG.errorf("Failed to create remote directory: %s", parentDir);
            taskDao.markFailed(taskId, "Failed to create remote directory");
            healthTracker.recordUploadFailure();
            return false;
        }

        // 3. Perform streaming WebDAV upload
        taskDao.save(task.markUploading());
        boolean uploaded = alistClient.uploadStream(task.remotePath(), localPath);

        if (uploaded) {
            taskDao.markSuccess(taskId);
            healthTracker.recordUploadSuccess(task.fileSizeBytes());

            // 4. Safely evict local file upon verified upload
            cleanLocalFile(localPath);
            return true;
        } else {
            taskDao.markFailed(taskId, "HTTP upload failed or timed out");
            healthTracker.recordUploadFailure();
            return false;
        }
    }

    private void cleanLocalFile(Path localPath) {
        if ("DELETE".equalsIgnoreCase(config.cleanupPolicy())) {
            try {
                Files.deleteIfExists(localPath);
                LOG.infof("Evicted local segment to free storage: %s", localPath.getFileName());
            } catch (IOException e) {
                LOG.warnf("Failed to delete local segment: %s (%s)", localPath, e.getMessage());
            }
        }
    }

    private String extractParentDir(String fullRemotePath) {
        int lastSlash = fullRemotePath.lastIndexOf('/');
        if (lastSlash > 0) {
            return fullRemotePath.substring(0, lastSlash);
        }
        return "/";
    }
}
