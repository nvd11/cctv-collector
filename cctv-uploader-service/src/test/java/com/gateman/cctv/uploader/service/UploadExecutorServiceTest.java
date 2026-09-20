package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.infra.AlistWebDavClient;
import com.gateman.cctv.uploader.model.TaskStatus;
import com.gateman.cctv.uploader.model.UploadTask;
import com.gateman.cctv.uploader.test.TestUploaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UploadExecutorServiceTest {

    @TempDir
    Path tempDir;

    private UploaderConfig config;
    private TaskDao taskDao;
    private UploadHealthTracker healthTracker;
    private MockAlistClient mockClient;
    private UploadExecutorService executorService;

    @BeforeEach
    void setUp() {
        config = TestUploaderConfig.createDefault()
                .withBufferDir(tempDir.toString())
                .withCleanupPolicy("DELETE");
        taskDao = new TaskDao();
        healthTracker = new UploadHealthTracker();
        mockClient = new MockAlistClient(config);
        executorService = new UploadExecutorService(config, mockClient, taskDao, healthTracker);
    }

    @Test
    @DisplayName("Should successfully upload file, mark SUCCESS, and evict local file")
    void shouldUploadAndEvictFile() throws IOException {
        Path file = tempDir.resolve("cctv_20260920_180000.mp4");
        Files.write(file, new byte[5000]);

        UploadTask task = UploadTask.createPending(file, "/remote/path.mp4", 5000L);
        taskDao.save(task);

        boolean success = executorService.executeSingle(task);

        assertThat(success).isTrue();
        assertThat(taskDao.findById(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(healthTracker.getStatusSnapshot(0).totalUploadedFiles()).isEqualTo(1L);
        assertThat(healthTracker.getStatusSnapshot(0).totalUploadedBytes()).isEqualTo(5000L);

        // Local file must be evicted (deleted) by DELETE policy
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("Should skip upload if file already exists remotely with matching size")
    void shouldSkipIfAlreadyExists() throws IOException {
        Path file = tempDir.resolve("cctv_20260920_181500.mp4");
        Files.write(file, new byte[3000]);

        mockClient.simulateRemoteExists = true;

        UploadTask task = UploadTask.createPending(file, "/remote/exists.mp4", 3000L);
        taskDao.save(task);

        boolean result = executorService.executeSingle(task);

        assertThat(result).isTrue();
        assertThat(taskDao.findById(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(Files.exists(file)).isFalse(); // Evicted since already on remote
    }

    // Mock client subclass overriding network I/O
    private static class MockAlistClient extends AlistWebDavClient {
        boolean simulateRemoteExists = false;

        public MockAlistClient(UploaderConfig config) {
            super(config);
        }

        @Override
        public boolean ensureRemoteDirExists(String remoteDir) {
            return true;
        }

        @Override
        public boolean existsRemoteFile(String remotePath, long expectedSize) {
            return simulateRemoteExists;
        }

        @Override
        public boolean uploadStream(String remotePath, Path localFile) {
            return true;
        }
    }
}
