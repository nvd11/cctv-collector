package com.gateman.cctv.uploader.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Strong-typed configuration mapping for the CCTV cloud netdisk batch uploader service.
 * <p>
 * Binds properties prefixed with {@code cctv.uploader.*} in {@code application.properties}
 * and environment variables prefixed with {@code CCTV_UPLOADER_*}.
 */
@ConfigMapping(prefix = "cctv.uploader")
public interface UploaderConfig {

    /**
     * Local buffer directory path containing MP4 segments to upload.
     * Mapped from {@code cctv.uploader.buffer-dir} or {@code CCTV_UPLOADER_BUFFER_DIR}.
     */
    @WithDefault("/mnt/buffer/cctv")
    String bufferDir();

    /**
     * Alist WebDAV root endpoint URL.
     * Example: {@code http://10.0.1.227:5244/dav}
     */
    @WithDefault("http://10.0.1.227:5244/dav")
    String alistEndpoint();

    /**
     * Alist WebDAV authentication username.
     */
    @WithDefault("gateman")
    String alistUsername();

    /**
     * Alist WebDAV authentication password.
     */
    @WithDefault("32565624")
    String alistPassword();

    /**
     * Base remote directory path on the cloud storage (e.g. within Quark Netdisk).
     */
    @WithDefault("/Quark/CCTV_Records")
    String remoteBaseDir();

    /**
     * Camera installation location subfolder (e.g. "锦绣世家_客厅").
     */
    @WithDefault("锦绣世家_客厅")
    String locationName();

    /**
     * Minimum age in seconds since last file modification before a segment is considered finalized.
     * Prevents uploading active in-progress recording files.
     */
    @WithDefault("60")
    long minFileAgeSeconds();

    /**
     * Maximum concurrent upload worker threads.
     * Default: 1 (conservative to prevent home broadband saturation).
     */
    @WithDefault("1")
    int maxConcurrentUploads();

    /**
     * Local file cleanup policy upon verified successful upload.
     * Supported: "DELETE" or "NONE".
     */
    @WithDefault("DELETE")
    String cleanupPolicy();

    /**
     * Minimum number of finalized segment files to retain locally in the buffer directory.
     * Maintains a rolling window cache of recent recordings on local SSD.
     * Default: 10 segments (approx 2.5 hours at 15-minute intervals).
     */
    @WithDefault("10")
    int minRetainedFiles();

    /**
     * Cron expression for the periodic directory scan and upload scheduler.
     * Default: every 5 minutes ("0 *&#47;5 * * * ?").
     */
    @WithDefault("0 */5 * * * ?")
    String scanCronExpression();

    /**
     * Initial retry backoff delay in seconds upon network failure or rate limiting.
     */
    @WithDefault("5")
    int reconnectDelaySeconds();

    /**
     * Maximum exponential backoff ceiling in seconds.
     */
    @WithDefault("300")
    int maxReconnectDelaySeconds();
}
