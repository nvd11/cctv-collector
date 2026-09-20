package com.gateman.cctv.uploader.service;

import com.gateman.cctv.uploader.config.UploaderConfig;
import com.gateman.cctv.uploader.dao.TaskDao;
import com.gateman.cctv.uploader.model.UploadTask;
import com.gateman.cctv.uploader.test.TestUploaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentScannerTest {

    @TempDir
    Path tempDir;

    private UploaderConfig config;
    private TaskDao taskDao;
    private SegmentScanner scanner;

    @BeforeEach
    void setUp() {
        config = TestUploaderConfig.createDefault()
                .withBufferDir(tempDir.toString())
                .withLocationName("锦绣世家_客厅")
                .withMinFileAgeSeconds(10L);
        taskDao = new TaskDao();
        scanner = new SegmentScanner(config, taskDao);
    }

    @Test
    @DisplayName("Should scan stable files and construct remote location hierarchy")
    void shouldScanAndConstructRemotePath() throws IOException {
        Path file = tempDir.resolve("cctv_20260920_150000.mp4");
        Files.write(file, new byte[2048]);

        // Set modification time 60 seconds ago (older than 10s minAge)
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));

        List<UploadTask> tasks = scanner.scanEligibleSegments();
        assertThat(tasks).hasSize(1);

        UploadTask task = tasks.get(0);
        assertThat(task.fileName()).isEqualTo("cctv_20260920_150000.mp4");
        assertThat(task.remotePath()).isEqualTo("/Quark/CCTV_Records/锦绣世家_客厅/2026-09-20/cctv_20260920_150000.mp4");
        assertThat(task.fileSizeBytes()).isEqualTo(2048L);
    }

    @Test
    @DisplayName("Should ignore active files modified recently within safety window")
    void shouldIgnoreRecentActiveFiles() throws IOException {
        Path recentFile = tempDir.resolve("cctv_20260920_151500.mp4");
        Files.write(recentFile, new byte[1024]);
        // Modified right now (age < 10s)
        Files.setLastModifiedTime(recentFile, FileTime.from(Instant.now()));

        List<UploadTask> tasks = scanner.scanEligibleSegments();
        assertThat(tasks).isEmpty();
        assertThat(taskDao.count()).isZero();
    }
}
