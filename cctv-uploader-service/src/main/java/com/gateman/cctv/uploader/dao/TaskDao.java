package com.gateman.cctv.uploader.dao;

import com.gateman.cctv.uploader.model.TaskStatus;
import com.gateman.cctv.uploader.model.UploadTask;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Enterprise Data Access Object (DAO) managing in-memory upload task states and history.
 */
@ApplicationScoped
public class TaskDao {

    private static final Logger LOG = Logger.getLogger(TaskDao.class);
    private static final int MAX_RECENT_CAPACITY = 200;

    private final ConcurrentMap<String, UploadTask> taskStore = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UploadTask> recentTasks = new ConcurrentLinkedDeque<>();

    public UploadTask save(UploadTask task) {
        Objects.requireNonNull(task, "task must not be null");
        taskStore.put(task.taskId(), task);
        LOG.debugf("Task saved [%s]: %s", task.status(), task.taskId());
        return task;
    }

    public boolean markSuccess(String taskId) {
        UploadTask task = taskStore.get(taskId);
        if (task != null) {
            UploadTask updated = task.markSuccess();
            taskStore.put(taskId, updated);
            recordRecent(updated);
            LOG.infof("Task succeeded: %s (%s)", taskId, task.fileName());
            return true;
        }
        return false;
    }

    public boolean markSkipped(String taskId) {
        UploadTask task = taskStore.get(taskId);
        if (task != null) {
            UploadTask updated = task.markSkipped();
            taskStore.put(taskId, updated);
            recordRecent(updated);
            LOG.infof("Task skipped (already exists): %s (%s)", taskId, task.fileName());
            return true;
        }
        return false;
    }

    public boolean markFailed(String taskId, String error) {
        UploadTask task = taskStore.get(taskId);
        if (task != null) {
            UploadTask updated = task.markFailed(error);
            taskStore.put(taskId, updated);
            recordRecent(updated);
            LOG.warnf("Task failed: %s (%s) - error: %s", taskId, task.fileName(), error);
            return true;
        }
        return false;
    }

    public List<UploadTask> findPendingTasks() {
        List<UploadTask> pending = new ArrayList<>();
        for (UploadTask task : taskStore.values()) {
            if (task.status() == TaskStatus.PENDING) {
                pending.add(task);
            }
        }
        return Collections.unmodifiableList(pending);
    }

    public List<UploadTask> findRecentTasks(int limit) {
        int max = (limit > 0) ? limit : 10;
        List<UploadTask> result = new ArrayList<>();
        int count = 0;
        for (UploadTask task : recentTasks) {
            if (count++ >= max) {
                break;
            }
            result.add(task);
        }
        return Collections.unmodifiableList(result);
    }

    public Optional<UploadTask> findById(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskStore.get(taskId));
    }

    public long count() {
        return taskStore.size();
    }

    public void clear() {
        taskStore.clear();
        recentTasks.clear();
        LOG.debug("TaskDao in-memory store cleared");
    }

    private void recordRecent(UploadTask task) {
        recentTasks.addFirst(task);
        while (recentTasks.size() > MAX_RECENT_CAPACITY) {
            recentTasks.pollLast();
        }
    }
}
