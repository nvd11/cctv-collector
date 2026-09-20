package com.gateman.cctv.uploader.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain model representing a single file upload job to Alist / Cloud Netdisk.
 *
 * @param taskId           unique task identifier (e.g. "task_cctv_20260920_025913")
 * @param localPath        local filesystem path of the source MP4 file
 * @param fileName         name of the file (e.g. "cctv_20260920_025913.mp4")
 * @param remotePath       target remote path on Alist (e.g. "/Quark/CCTV_Records/锦绣世家_客厅/2026-09-20/cctv_01.mp4")
 * @param fileSizeBytes    physical size of the local file in bytes
 * @param attemptCount     number of upload attempts made
 * @param status           current lifecycle status of this upload task
 * @param discoveredTime   time when the scanner first audited and queued this file
 * @param startTime        time when the current upload attempt started
 * @param completedTime    time when upload finished (null if pending or in flight)
 * @param lastErrorMessage last diagnostic error message if failed
 */
public record UploadTask(
        String taskId,
        Path localPath,
        String fileName,
        String remotePath,
        long fileSizeBytes,
        int attemptCount,
        TaskStatus status,
        Instant discoveredTime,
        Instant startTime,
        Instant completedTime,
        String lastErrorMessage
) {

    public UploadTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(localPath, "localPath must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(remotePath, "remotePath must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(discoveredTime, "discoveredTime must not be null");

        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must not be negative");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must not be negative");
        }
    }

    public static UploadTask createPending(Path localPath, String remotePath, long fileSizeBytes) {
        Objects.requireNonNull(localPath, "localPath must not be null");
        String fileName = localPath.getFileName().toString();
        String taskId = "task_" + (fileName.endsWith(".mp4") ? fileName.substring(0, fileName.length() - 4) : fileName);
        return new UploadTask(taskId, localPath, fileName, remotePath, fileSizeBytes, 0, TaskStatus.PENDING, Instant.now(), null, null, null);
    }

    public UploadTask markUploading() {
        return new UploadTask(taskId, localPath, fileName, remotePath, fileSizeBytes, attemptCount + 1, TaskStatus.UPLOADING, discoveredTime, Instant.now(), null, null);
    }

    public UploadTask markSuccess() {
        return new UploadTask(taskId, localPath, fileName, remotePath, fileSizeBytes, attemptCount, TaskStatus.SUCCESS, discoveredTime, startTime, Instant.now(), null);
    }

    public UploadTask markSkipped() {
        return new UploadTask(taskId, localPath, fileName, remotePath, fileSizeBytes, attemptCount, TaskStatus.SKIPPED, discoveredTime, startTime, Instant.now(), "Already present on remote storage");
    }

    public UploadTask markFailed(String errorMessage) {
        return new UploadTask(taskId, localPath, fileName, remotePath, fileSizeBytes, attemptCount, TaskStatus.FAILED, discoveredTime, startTime, null, errorMessage);
    }

    public boolean isEligibleForRetry(int maxAttempts) {
        return status == TaskStatus.FAILED && attemptCount < maxAttempts;
    }
}
