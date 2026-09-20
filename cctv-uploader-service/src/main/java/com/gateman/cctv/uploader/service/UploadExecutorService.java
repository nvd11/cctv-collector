package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.infra.AlistWebDavClient;
import com.gateman.cctv.uploader.model.TaskStatus;
import com.gateman.cctv.uploader.model.UploadTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
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
            cleanLocalFiles();
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

            // 4. Safely evict excess local files while retaining minimum configured count
            cleanLocalFiles();
            return true;
        } else {
            // Attempt to purge any incomplete/corrupted remote session on Alist/Quark
            alistClient.deleteRemoteFile(task.remotePath());
            taskDao.markFailed(taskId, "HTTP upload failed or timed out");
            healthTracker.recordUploadFailure();
            return false;
        }
    }

    /**
     * Enforces the local retention policy using a rolling window:
     * Retains at least {@code minRetainedFiles()} most recent segment files on local SSD,
     * evicting older files only when they have been confirmed uploaded to remote storage.
     */
    void cleanLocalFiles() {
        if (!"DELETE".equalsIgnoreCase(config.cleanupPolicy())) {
            return;
        }

        Path bufferDir = Paths.get(config.bufferDir());
        if (!Files.exists(bufferDir) || !Files.isDirectory(bufferDir)) {
            return;
        }

        List<Path> allSegments = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bufferDir, "*.mp4")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    allSegments.add(entry);
                }
            }
        } catch (IOException e) {
            LOG.warnf("Error reading buffer directory during local eviction: %s", e.getMessage());
            return;
        }

        int totalCount = allSegments.size();
        int minRetained = config.minRetainedFiles();

        if (totalCount <= minRetained) {
            LOG.debugf("Buffer file count (%d) is within retention limit (%d). No eviction performed.",
                    totalCount, minRetained);
            return;
        }

        // Sort chronologically (oldest files first)
        allSegments.sort(Comparator.comparing(Path::getFileName));

        int excess = totalCount - minRetained;
        LOG.infof("Buffer file count (%d) exceeds retention limit (%d). Evicting up to %d oldest uploaded segment(s)...",
                totalCount, minRetained, excess);

        for (Path file : allSegments) {
            if (excess <= 0) {
                break;
            }

            // A file may only be evicted if it has been successfully uploaded (or skipped as already present remotely)
            String taskId = SegmentScanner.deriveTaskId(file);
            boolean isUploaded = taskDao.findById(taskId)
                    .map(t -> t.status() == TaskStatus.SUCCESS || t.status() == TaskStatus.SKIPPED)
                    .orElse(false);

            if (!isUploaded) {
                // Fallback check against remote Alist/Quark storage
                String remotePath = SegmentScanner.buildRemotePath(config.remoteBaseDir(), config.locationName(), file);
                long fileSize = SegmentScanner.safeGetFileSize(file);
                if (fileSize > 0 && alistClient.existsRemoteFile(remotePath, fileSize)) {
                    isUploaded = true;
                }
            }

            if (isUploaded) {
                try {
                    Files.deleteIfExists(file);
                    excess--;
                    LOG.infof("Evicted local segment to free storage (retaining %d newest): %s",
                            minRetained, file.getFileName());
                } catch (IOException e) {
                    LOG.warnf("Failed to delete local segment: %s (%s)", file, e.getMessage());
                }
            } else {
                LOG.debugf("Skipping eviction of pending/unverified local segment: %s", file.getFileName());
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
