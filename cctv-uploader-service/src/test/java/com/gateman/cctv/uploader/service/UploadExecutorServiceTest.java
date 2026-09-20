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
                .withCleanupPolicy("DELETE")
                .withMinRetainedFiles(0);
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

    @Test
    @DisplayName("Should retain local file when total buffer files are within minRetainedFiles limit")
    void shouldRetainLocalFileWhenUnderMinRetainedLimit() throws IOException {
        UploaderConfig retainedConfig = ((TestUploaderConfig) config).withMinRetainedFiles(10);
        UploadExecutorService retainedService = new UploadExecutorService(retainedConfig, mockClient, taskDao, healthTracker);

        Path file = tempDir.resolve("cctv_20260920_180000.mp4");
        Files.write(file, new byte[5000]);

        UploadTask task = UploadTask.createPending(file, "/remote/path.mp4", 5000L);
        taskDao.save(task);

        boolean success = retainedService.executeSingle(task);

        assertThat(success).isTrue();
        assertThat(taskDao.findById(task.taskId()).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);
        // File must NOT be evicted because 1 file <= 10
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("Should evict oldest uploaded files when buffer exceeds minRetainedFiles limit")
    void shouldEvictOldestUploadedFilesWhenExceedingLimit() throws IOException {
        UploaderConfig retainedConfig = ((TestUploaderConfig) config).withMinRetainedFiles(2);
        UploadExecutorService retainedService = new UploadExecutorService(retainedConfig, mockClient, taskDao, healthTracker);

        // Create 4 files in tempDir
        Path file1 = tempDir.resolve("cctv_20260920_010000.mp4");
        Path file2 = tempDir.resolve("cctv_20260920_020000.mp4");
        Path file3 = tempDir.resolve("cctv_20260920_030000.mp4");
        Path file4 = tempDir.resolve("cctv_20260920_040000.mp4");
        Files.write(file1, new byte[1000]);
        Files.write(file2, new byte[1000]);
        Files.write(file3, new byte[1000]);
        Files.write(file4, new byte[1000]);

        UploadTask task1 = UploadTask.createPending(file1, "/remote/01.mp4", 1000L);
        UploadTask task2 = UploadTask.createPending(file2, "/remote/02.mp4", 1000L);
        taskDao.save(task1);
        taskDao.save(task2);

        // Upload file1: 4 files total > 2, file1 is uploaded and oldest -> file1 evicted!
        retainedService.executeSingle(task1);
        assertThat(Files.exists(file1)).isFalse();
        assertThat(Files.exists(file2)).isTrue();
        assertThat(Files.exists(file3)).isTrue();
        assertThat(Files.exists(file4)).isTrue();

        // Upload file2: 3 files total > 2, file2 is uploaded and oldest -> file2 evicted!
        retainedService.executeSingle(task2);
        assertThat(Files.exists(file2)).isFalse();
        // Now 2 files remain on disk (file3 and file4) <= minRetainedFiles (2)
        assertThat(Files.exists(file3)).isTrue();
        assertThat(Files.exists(file4)).isTrue();
    }

    @Test
    @DisplayName("Should NOT evict pending unverified files even if buffer exceeds minRetainedFiles limit")
    void shouldNotEvictPendingFilesEvenIfExceedingLimit() throws IOException {
        UploaderConfig retainedConfig = ((TestUploaderConfig) config).withMinRetainedFiles(2);
        UploadExecutorService retainedService = new UploadExecutorService(retainedConfig, mockClient, taskDao, healthTracker);

        // Create 3 files in tempDir without marking them uploaded
        Path file1 = tempDir.resolve("cctv_20260920_010000.mp4");
        Path file2 = tempDir.resolve("cctv_20260920_020000.mp4");
        Path file3 = tempDir.resolve("cctv_20260920_030000.mp4");
        Files.write(file1, new byte[1000]);
        Files.write(file2, new byte[1000]);
        Files.write(file3, new byte[1000]);

        retainedService.cleanLocalFiles();

        // None are uploaded, so none should be deleted despite 3 > 2
        assertThat(Files.exists(file1)).isTrue();
        assertThat(Files.exists(file2)).isTrue();
        assertThat(Files.exists(file3)).isTrue();
    }

    @Test
    @DisplayName("Should NOT evict any file when cleanup policy is NONE")
    void shouldNotEvictWhenCleanupPolicyIsNone() throws IOException {
        UploaderConfig noneConfig = ((TestUploaderConfig) config)
                .withCleanupPolicy("NONE")
                .withMinRetainedFiles(0);
        UploadExecutorService noneService = new UploadExecutorService(noneConfig, mockClient, taskDao, healthTracker);

        Path file = tempDir.resolve("cctv_20260920_180000.mp4");
        Files.write(file, new byte[5000]);

        UploadTask task = UploadTask.createPending(file, "/remote/path.mp4", 5000L);
        taskDao.save(task);

        boolean success = noneService.executeSingle(task);

        assertThat(success).isTrue();
        assertThat(Files.exists(file)).isTrue();
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
