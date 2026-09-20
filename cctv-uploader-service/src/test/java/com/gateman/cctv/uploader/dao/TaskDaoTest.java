package com.gateman.cctv.uploader.dao;

import com.gateman.cctv.uploader.model.TaskStatus;
import com.gateman.cctv.uploader.model.UploadTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskDaoTest {

    private TaskDao dao;

    @BeforeEach
    void setUp() {
        dao = new TaskDao();
    }

    @Test
    @DisplayName("Should save, find, and update task status")
    void shouldSaveAndQueryTask() {
        Path path = Path.of("/mnt/buffer/cctv/cctv_20260920_010000.mp4");
        UploadTask task = UploadTask.createPending(path, "/remote/path.mp4", 5000L);

        dao.save(task);
        assertThat(dao.count()).isEqualTo(1L);
        assertThat(dao.findById(task.taskId())).isPresent().contains(task);

        // Find pending
        List<UploadTask> pending = dao.findPendingTasks();
        assertThat(pending).containsExactly(task);

        // Mark success
        boolean marked = dao.markSuccess(task.taskId());
        assertThat(marked).isTrue();
        assertThat(dao.findById(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(dao.findPendingTasks()).isEmpty();

        // Recent tasks queue contains it
        List<UploadTask> recent = dao.findRecentTasks(5);
        assertThat(recent).hasSize(1);
    }

    @Test
    @DisplayName("Should handle skipped and failed transitions")
    void shouldHandleFailedAndSkipped() {
        Path path = Path.of("/mnt/buffer/cctv/cctv_20260920_020000.mp4");
        UploadTask task = UploadTask.createPending(path, "/remote/path.mp4", 10000L);
        dao.save(task);

        dao.markFailed(task.taskId(), "Timeout error");
        assertThat(dao.findById(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(dao.findById(task.taskId()).orElseThrow().lastErrorMessage()).isEqualTo("Timeout error");

        dao.markSkipped(task.taskId());
        assertThat(dao.findById(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SKIPPED);
    }
}
