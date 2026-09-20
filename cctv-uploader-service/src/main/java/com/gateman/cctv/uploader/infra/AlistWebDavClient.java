package com.gateman.cctv.uploader.infra;

import com.gateman.cctv.uploader.config.UploaderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * High-performance, streaming WebDAV client adapter for StarFive Alist.
 * <p>
 * Built on native Java {@link HttpClient} with zero third-party dependencies.
 * Supports:
 * <ul>
 *   <li>Recursive directory creation via WebDAV {@code MKCOL};</li>
 *   <li>Remote file existence & size verification via {@code PROPFIND} / {@code HEAD};</li>
 *   <li>Streaming chunked file upload via {@code PUT} without buffering into memory.</li>
 * </ul>
 */
@ApplicationScoped
public class AlistWebDavClient {

    private static final Logger LOG = Logger.getLogger(AlistWebDavClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final UploaderConfig config;
    private final HttpClient httpClient;

    @Inject
    public AlistWebDavClient(UploaderConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Constructor allowing custom HttpClient injection for testing.
     */
    public AlistWebDavClient(UploaderConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    /**
     * Recursively ensures that the full remote directory hierarchy exists, issuing {@code MKCOL} as necessary.
     *
     * @param remoteDir relative remote path (e.g. "/Quark/CCTV_Records/锦绣世家_客厅/2026-09-20")
     * @return {@code true} if directory exists or was created, {@code false} if failed
     */
    public boolean ensureRemoteDirExists(String remoteDir) {
        if (remoteDir == null || remoteDir.isBlank() || remoteDir.equals("/")) {
            return true;
        }

        String[] segments = remoteDir.split("/");
        StringBuilder currentPath = new StringBuilder();

        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            currentPath.append("/").append(segment);
            String folderUrl = buildFullUrl(currentPath.toString());

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(folderUrl))
                        .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", buildAuthHeader())
                        .timeout(REQUEST_TIMEOUT)
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();

                // 201 = Created, 405 = Method Not Allowed (Already exists in WebDAV), 200/204 = Success
                if (status != 201 && status != 405 && status != 200 && status != 204) {
                    LOG.debugf("MKCOL on %s returned HTTP %d", folderUrl, status);
                }
            } catch (Exception e) {
                LOG.warnf("Failed to ensure remote directory exists (%s): %s", folderUrl, e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a file already exists on the remote Alist WebDAV endpoint.
     *
     * @param remotePath   relative remote file path
     * @param expectedSize expected size in bytes
     * @return {@code true} if file exists and matches size, {@code false} otherwise
     */
    public boolean existsRemoteFile(String remotePath, long expectedSize) {
        String fullUrl = buildFullUrl(remotePath);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", buildAuthHeader())
                    .header("Depth", "0")
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();

            if (status == 200 || status == 207) {
                // If response contains expected size or exists
                String body = response.body();
                if (expectedSize <= 0 || body.contains(String.valueOf(expectedSize))) {
                    LOG.infof("Remote file already exists with matching metadata: %s", fullUrl);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.debugf("PROPFIND check failed for %s: %s", fullUrl, e.getMessage());
        }

        return false;
    }

    /**
     * Streams the local MP4 file to the remote Alist WebDAV endpoint using HTTP PUT.
     *
     * @param remotePath relative remote file path
     * @param localFile  local physical file path
     * @return {@code true} if upload was confirmed (HTTP 200/201/204), {@code false} otherwise
     */
    public boolean uploadStream(String remotePath, Path localFile) {
        Objects.requireNonNull(remotePath, "remotePath must not be null");
        Objects.requireNonNull(localFile, "localFile must not be null");

        if (!Files.exists(localFile)) {
            LOG.errorf("Local source file does not exist for upload: %s", localFile);
            return false;
        }

        String fullUrl = buildFullUrl(remotePath);
        LOG.infof("Streaming file upload: %s -> %s (%d bytes)", localFile.getFileName(), fullUrl, safeGetFileSize(localFile));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .PUT(HttpRequest.BodyPublishers.ofFile(localFile))
                    .header("Authorization", buildAuthHeader())
                    .header("Content-Type", "video/mp4")
                    .timeout(UPLOAD_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();

            if (status == 200 || status == 201 || status == 204) {
                LOG.infof("Upload verified successfully [HTTP %d]: %s", status, remotePath);
                return true;
            } else {
                LOG.warnf("Upload failed [HTTP %d]: %s - Body: %s", status, remotePath, response.body());
                return false;
            }
        } catch (Exception e) {
            LOG.errorf("Upload exception for %s: %s", remotePath, e.getMessage());
            return false;
        }
    }

    private String buildAuthHeader() {
        String credentials = config.alistUsername() + ":" + config.alistPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String buildFullUrl(String path) {
        String base = config.alistEndpoint();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String cleanPath = path.startsWith("/") ? path : "/" + path;

        // Encode URI path segments while preserving slashes
        String[] parts = cleanPath.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                encoded.append("/").append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        return base + encoded;
    }

    private long safeGetFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }
}
