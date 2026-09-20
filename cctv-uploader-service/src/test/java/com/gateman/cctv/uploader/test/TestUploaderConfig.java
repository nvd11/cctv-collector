package com.gateman.cctv.uploader.test;

import com.gateman.cctv.uploader.config.UploaderConfig;

/**
 * Test fixture implementing {@link UploaderConfig} using a Java 21 Record.
 */
public record TestUploaderConfig(
        String bufferDir,
        String alistEndpoint,
        String alistUsername,
        String alistPassword,
        String remoteBaseDir,
        String locationName,
        long minFileAgeSeconds,
        int maxConcurrentUploads,
        String cleanupPolicy,
        int minRetainedFiles,
        String scanCronExpression,
        int reconnectDelaySeconds,
        int maxReconnectDelaySeconds
) implements UploaderConfig {

    public static TestUploaderConfig createDefault() {
        return new TestUploaderConfig(
                "/mnt/buffer/cctv",
                "http://10.0.1.227:5244/dav",
                "gateman",
                "32565624",
                "/Quark/CCTV_Records",
                "锦绣世家_客厅",
                60L,
                1,
                "DELETE",
                10,
                "0 */5 * * * ?",
                5,
                300
        );
    }

    public TestUploaderConfig withBufferDir(String bufferDir) {
        return new TestUploaderConfig(bufferDir, alistEndpoint, alistUsername, alistPassword, remoteBaseDir, locationName, minFileAgeSeconds, maxConcurrentUploads, cleanupPolicy, minRetainedFiles, scanCronExpression, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestUploaderConfig withAlistEndpoint(String alistEndpoint) {
        return new TestUploaderConfig(bufferDir, alistEndpoint, alistUsername, alistPassword, remoteBaseDir, locationName, minFileAgeSeconds, maxConcurrentUploads, cleanupPolicy, minRetainedFiles, scanCronExpression, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestUploaderConfig withLocationName(String locationName) {
        return new TestUploaderConfig(bufferDir, alistEndpoint, alistUsername, alistPassword, remoteBaseDir, locationName, minFileAgeSeconds, maxConcurrentUploads, cleanupPolicy, minRetainedFiles, scanCronExpression, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestUploaderConfig withMinFileAgeSeconds(long minFileAgeSeconds) {
        return new TestUploaderConfig(bufferDir, alistEndpoint, alistUsername, alistPassword, remoteBaseDir, locationName, minFileAgeSeconds, maxConcurrentUploads, cleanupPolicy, minRetainedFiles, scanCronExpression, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestUploaderConfig withCleanupPolicy(String cleanupPolicy) {
        return new TestUploaderConfig(bufferDir, alistEndpoint, alistUsername, alistPassword, remoteBaseDir, locationName, minFileAgeSeconds, maxConcurrentUploads, cleanupPolicy, minRetainedFiles, scanCronExpression, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestUploaderConfig withMinRetainedFiles(int minRetainedFiles) {
        return new TestUploaderConfig(bufferDir, alistEndpoint, alistUsername, alistPassword, remoteBaseDir, locationName, minFileAgeSeconds, maxConcurrentUploads, cleanupPolicy, minRetainedFiles, scanCronExpression, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }
}
