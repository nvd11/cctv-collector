package com.gateman.cctv.collector.supervisor;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.test.TestCollectorConfig;


public class RealCmdTest {


    private static final Logger logger = LoggerFactory.getLogger(RealCmdTest.class);

    @Test
    public void testHello() {
        logger.info("Hello Test executed successfully.");
         CollectorConfig config = TestCollectorConfig.createDefault()
                .withRtspUrl("rtsp://admin:pass123@10.0.1.20:554/stream1")
                .withBufferDir("/mnt/buffer/cctv")
                .withSegmentSeconds(900);

        List<String> args = FFmpegCommandBuilder.buildArgs(config);
        logger.info("Generated FFmpeg command arguments: {}", args);
    }

}
