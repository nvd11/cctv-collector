package com.gateman.cctv.collector.supervisor;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import com.gateman.cctv.collector.util.DotEnv;


public class RealCmdTest {


    private static final Logger logger = LoggerFactory.getLogger(RealCmdTest.class);

    @Test
    public void testHello() {
        logger.info("Hello Test executed successfully.");
           Map<String, String> env = DotEnv.load();

        String realRtsp = env.getOrDefault("CCTV_RTSP_URL", "rtsp://localhost:554/stream1");
        String bufferDir = env.getOrDefault("CCTV_BUFFER_DIR", "/mnt/buffer/cctv");
        int segmentSeconds = Integer.parseInt(env.getOrDefault("CCTV_SEGMENT_SECONDS", "900"));

        CollectorConfig config = TestCollectorConfig.createDefault()
                .withRtspUrl(realRtsp)
                .withBufferDir(bufferDir)
                .withSegmentSeconds(segmentSeconds);

        List<String> commandArgs = FFmpegCommandBuilder.buildArgs(config);

        logger.info("Final assembled FFmpeg command from dynamic env:");
        logger.info(String.join(" ", commandArgs));
    }

}
