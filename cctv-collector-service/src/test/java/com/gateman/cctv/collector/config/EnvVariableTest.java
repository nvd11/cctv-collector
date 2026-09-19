package com.gateman.cctv.collector.config;

import com.gateman.cctv.collector.supervisor.FFmpegCommandBuilder;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import com.gateman.cctv.collector.util.DotEnv;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates and verifies retrieving dynamic environment variables
 * without pre-defining fields in Java classes.
 */
class EnvVariableTest {

    private static final Logger LOG = LoggerFactory.getLogger(EnvVariableTest.class);

    @Test
    @DisplayName("1. Native Java System.getenv() returns a dynamic dictionary (Map<String, String>)")
    void testSystemGetEnvDynamicDictionary() {
        // Native Java dynamic dictionary: identical to Python's os.environ
        Map<String, String> sysEnv = System.getenv();

        assertThat(sysEnv).isNotNull();
        LOG.info("Total OS Environment Variables detected: {}", sysEnv.size());

        // Any variable can be queried dynamically without pre-defining
        String user = sysEnv.get("USER");
        String path = sysEnv.get("PATH");
        String custom = sysEnv.get("NON_EXISTENT_KEY");

        LOG.info("Dynamic USER from OS: {}", user);
        LOG.info("Non-existent key safely returns null: {}", custom);

        assertThat(user).isNotBlank();
        assertThat(custom).isNull();
    }

    @Test
    @DisplayName("2. DotEnv.load() dynamically parses .env file into a Map<String, String>")
    void testDotEnvDynamicLoading() {
        // Parse .env dynamically (identical to Python's dotenv_values)
        Map<String, String> env = DotEnv.load();

        assertThat(env).isNotNull();
        LOG.info("Dynamic keys loaded from .env + OS env: {}", env.keySet());

        // Fetch dynamic variables without any pre-defined class fields
        String rtspUrl = env.get("CCTV_RTSP_URL");
        String bufferDir = env.get("CCTV_BUFFER_DIR");
        String customKey = env.get("CUSTOM_DYNAMIC_TEST_KEY");

        LOG.info("Retrieved CCTV_RTSP_URL dynamically: {}", rtspUrl);
        LOG.info("Retrieved CCTV_BUFFER_DIR dynamically: {}", bufferDir);
        LOG.info("Retrieved CUSTOM_DYNAMIC_TEST_KEY dynamically: {}", customKey);

        assertThat(rtspUrl).isNotNull();
        assertThat(rtspUrl).startsWith("rtsp://");
        assertThat(customKey).isEqualTo("hello_cindy_from_env");
    }

    @Test
    @DisplayName("3. Dynamically compose CollectorConfig and FFmpeg command using .env values")
    void testBuildFFmpegArgsFromDynamicEnv() {
        Map<String, String> env = DotEnv.load();

        String realRtsp = env.getOrDefault("CCTV_RTSP_URL", "rtsp://localhost:554/stream1");
        String bufferDir = env.getOrDefault("CCTV_BUFFER_DIR", "/mnt/buffer/cctv");
        int segmentSeconds = Integer.parseInt(env.getOrDefault("CCTV_SEGMENT_SECONDS", "900"));

        CollectorConfig config = TestCollectorConfig.createDefault()
                .withRtspUrl(realRtsp)
                .withBufferDir(bufferDir)
                .withSegmentSeconds(segmentSeconds);

        List<String> commandArgs = FFmpegCommandBuilder.buildArgs(config);

        LOG.info("Final assembled FFmpeg command from dynamic env:");
        LOG.info(String.join(" ", commandArgs));

        assertThat(commandArgs).contains("-i", realRtsp);
        assertThat(commandArgs).contains("-segment_time", String.valueOf(segmentSeconds));
    }
}
