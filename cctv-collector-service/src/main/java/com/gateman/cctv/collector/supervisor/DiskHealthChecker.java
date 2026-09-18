package com.gateman.cctv.collector.supervisor;

import com.gateman.cctv.collector.config.CollectorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Health checker and circuit breaker for local buffer disk storage.
 * <p>
 * Ensures that the local storage path has sufficient free capacity prior to spawning or continuing
 * FFmpeg stream recording. If free disk space drops below {@link CollectorConfig#minFreeDiskGb()},
 * this component flags the storage as unhealthy to prevent edge node disk exhaustion.
 */
@ApplicationScoped
public class DiskHealthChecker {

    private static final Logger LOG = Logger.getLogger(DiskHealthChecker.class);
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    private final CollectorConfig config;

    /**
     * CDI constructor injection point.
     * <p>
     * The {@link Inject} annotation explicitly marks this constructor for Quarkus Arc dependency injection,
     * resolving and providing the {@link CollectorConfig} bean instance at runtime.
     * Constructor injection ensures immutability (enabling the {@code final} modifier on {@code config})
     * and allows clean, container-free instantiation during unit tests.
     *
     * @param config the strongly-typed collector configuration
     */
    @Inject
    public DiskHealthChecker(CollectorConfig config) {
        this.config = config;
    }

    /**
     * Resolves the configured buffer storage directory, ensuring it exists on the filesystem.
     *
     * @return {@link Path} representation of the buffer directory.
     */
    public Path getBufferPath() {
        Path path = Paths.get(config.bufferDir());
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                LOG.infof("Created buffer directory at: %s", path.toAbsolutePath());
            } catch (IOException e) {
                LOG.errorf(e, "Failed to create buffer directory at: %s", path.toAbsolutePath());
            }
        }
        return path;
    }

    /**
     * Retrieves the usable free disk space of the buffer storage volume in Gigabytes.
     *
     * @return Available space in Gigabytes.
     */
    public long getFreeDiskSpaceGb() {
        return getUsableSpaceBytes() / BYTES_PER_GB;
    }

    /**
     * Retrieves the usable free disk space in bytes for the storage partition.
     *
     * @return Available usable space in bytes.
     */
    public long getUsableSpaceBytes() {
        File file = resolveExistingLocation(Paths.get(config.bufferDir()));
        return file.getUsableSpace();
    }

    /**
     * Evaluates whether the disk storage meets the minimum free capacity threshold.
     *
     * @return {@code true} if free disk space >= {@link CollectorConfig#minFreeDiskGb()}, {@code false} otherwise.
     */
    public boolean isDiskHealthy() {
        long freeGb = getFreeDiskSpaceGb();
        long thresholdGb = config.minFreeDiskGb();

        if (freeGb < thresholdGb) {
            LOG.errorf("Disk storage circuit breaker triggered! Free space (%d GB) is below minimum threshold (%d GB) on %s",
                    freeGb, thresholdGb, config.bufferDir());
            return false;
        }

        LOG.debugf("Disk space check passed: %d GB available (threshold: %d GB)", freeGb, thresholdGb);
        return true;
    }

    /**
     * Walks up the directory tree until an existing ancestor directory is found,
     * allowing usable space calculation even if the target buffer directory has not yet been created.
     */
    private File resolveExistingLocation(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return (current != null) ? current.toFile() : new File(".");
    }
}
