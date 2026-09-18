package com.gateman.cctv.collector.supervisor;

import com.gateman.cctv.collector.config.CollectorConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure utility class responsible for constructing the native FFmpeg command-line invocation arguments.
 * <p>
 * Enforces production-grade audio/video parameters tailored for 24x7 security camera stream ingestion:
 * <ul>
 *   <li>{@code -rtsp_transport tcp}: Enforces TCP transport to eliminate packet loss artifacts (green screen / glitches).</li>
 *   <li>{@code -c copy}: Lossless stream copying without CPU decoding or re-encoding (< 1% CPU utilization).</li>
 *   <li>{@code -f segment}: Native segmented MP4 container muxing.</li>
 *   <li>{@code -reset_timestamps 1}: Resets timestamps to zero at each segment boundary for smooth seekability.</li>
 *   <li>{@code -strftime 1}: Formats output segment filenames using system time templates.</li>
 * </ul>
 */
public final class FFmpegCommandBuilder {

    /**
     * Standard timestamp pattern for output MP4 segment filenames.
     * Generates filenames like {@code cctv_20260919_153000.mp4}.
     */
    public static final String SEGMENT_FILE_PATTERN = "cctv_%Y%m%d_%H%M%S.mp4";

    private FFmpegCommandBuilder() {
        // Utility class: prevent direct instantiation
    }

    /**
     * Constructs the complete, ordered command argument list suitable for {@link ProcessBuilder}.
     *
     * @param config the strongly-typed collector configuration
     * @return unmodifiable list of command arguments starting with {@code "ffmpeg"}
     * @throws NullPointerException if {@code config} is null
     */
    public static List<String> buildArgs(CollectorConfig config) {
        Objects.requireNonNull(config, "CollectorConfig must not be null");

        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-rtsp_transport");
        args.add("tcp");
        args.add("-i");
        args.add(config.rtspUrl());
        args.add("-c");
        args.add("copy");
        args.add("-f");
        args.add("segment");
        args.add("-segment_time");
        args.add(String.valueOf(config.segmentSeconds()));
        args.add("-segment_format");
        args.add("mp4");
        args.add("-reset_timestamps");
        args.add("1");
        args.add("-strftime");
        args.add("1");
        args.add(resolveOutputPathPattern(config.bufferDir()));

        return Collections.unmodifiableList(args);
    }

    /**
     * Resolves the full filesystem output template path using the configured buffer directory.
     *
     * @param bufferDir the base buffer directory path string
     * @return full path pattern string (e.g. {@code /mnt/buffer/cctv/cctv_%Y%m%d_%H%M%S.mp4})
     */
    public static String resolveOutputPathPattern(String bufferDir) {
        Objects.requireNonNull(bufferDir, "bufferDir must not be null");
        Path dirPath = Paths.get(bufferDir);
        return dirPath.resolve(SEGMENT_FILE_PATTERN).toString();
    }
}
