package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.model.UploadTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans local storage buffer for finalized video segments and registers pending upload tasks.
 */
@ApplicationScoped
public class SegmentScanner {

    private static final Logger LOG = Logger.getLogger(SegmentScanner.class);
    private static final Pattern DATE_PATTERN = Pattern.compile(".*_(\\d{4})(\\d{2})(\\d{2})_.*\\.mp4$");

    private final UploaderConfig config;
    private final TaskDao taskDao;

    @Inject
    public SegmentScanner(UploaderConfig config, TaskDao taskDao) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.taskDao = Objects.requireNonNull(taskDao, "taskDao must not be null");
    }

    /**
     * Scans the buffer directory and returns newly queued or pending upload tasks.
     *
     * @return list of eligible {@link UploadTask} instances
     */
    public List<UploadTask> scanEligibleSegments() {
        Path bufferDir = Paths.get(config.bufferDir());
        if (!Files.exists(bufferDir) || !Files.isDirectory(bufferDir)) {
            LOG.warnf("Uploader buffer directory not found: %s", bufferDir);
            return Collections.emptyList();
        }

        List<Path> candidateFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bufferDir, "*.mp4")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    candidateFiles.add(entry);
                }
            }
        } catch (IOException e) {
            LOG.errorf(e, "Error reading buffer directory: %s", bufferDir);
            return Collections.emptyList();
        }

        candidateFiles.sort(Comparator.comparing(Path::getFileName));
        List<UploadTask> newlyEligible = new ArrayList<>();

        for (Path file : candidateFiles) {
            if (isFileStable(file, config.minFileAgeSeconds())) {
                String remotePath = buildRemotePath(file);
                long fileSize = safeGetFileSize(file);

                String taskId = deriveTaskId(file);
                if (taskDao.findById(taskId).isEmpty()) {
                    UploadTask task = UploadTask.createPending(file, remotePath, fileSize);
                    taskDao.save(task);
                    newlyEligible.add(task);
                    LOG.infof("Queued new upload task: %s -> %s (%d bytes)", file.getFileName(), remotePath, fileSize);
                }
            }
        }

        return Collections.unmodifiableList(newlyEligible);
    }

    /**
     * Determines whether a file has ceased being written by checking its last modified age and non-zero size.
     */
    public boolean isFileStable(Path path, long minAgeSeconds) {
        try {
            long size = Files.size(path);
            if (size <= 0) {
                return false;
            }
            long lastModifiedSeconds = Files.getLastModifiedTime(path).toInstant().getEpochSecond();
            long nowSeconds = Instant.now().getEpochSecond();
            return (nowSeconds - lastModifiedSeconds) >= minAgeSeconds;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Constructs the remote hierarchical cloud path:
     * {@code {remoteBaseDir}/{locationName}/{YYYY-MM-DD}/{fileName}}
     */
    public String buildRemotePath(Path localPath) {
        String fileName = localPath.getFileName().toString();
        String dateFolder = parseDateFolder(fileName);

        String base = config.remoteBaseDir();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        return base + "/" + config.locationName() + "/" + dateFolder + "/" + fileName;
    }

    public static String parseDateFolder(String fileName) {
        Matcher matcher = DATE_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
        }
        return "archive";
    }

    private String deriveTaskId(Path path) {
        String fileName = path.getFileName().toString();
        return "task_" + (fileName.endsWith(".mp4") ? fileName.substring(0, fileName.length() - 4) : fileName);
    }

    private long safeGetFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
