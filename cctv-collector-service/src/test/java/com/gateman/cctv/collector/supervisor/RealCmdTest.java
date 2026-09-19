package com.gateman.cctv.collector.supervisor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gateman.cctv.collector.config.CollectorConfig;
import com.gateman.cctv.collector.test.TestCollectorConfig;
import com.gateman.cctv.collector.util.DotEnv;


public class RealCmdTest {


    private static final Logger logger = LoggerFactory.getLogger(RealCmdTest.class);

    private FFmpegProcessExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new FFmpegProcessExecutor();
    }

    @Test
    public void testHello() throws IOException, InterruptedException {
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

        


        // 1. 启动录制
        executor.start(commandArgs);
        logger.info("FFmpeg 已成功启动，PID: {}", executor.getPid());

        // 2. 让它在后台录制 12 秒（刚好验证切出至少一个完整切片）
        logger.info("正在录制中，等待 12 秒...");
        Thread.sleep(12_000L);

        // 3. 发送 'q' 触发优雅停机，闭合最后的切片
        logger.info("12 秒已到，发送 'q' 优雅停机...");
        boolean stopped = executor.stopGracefully(5);

        logger.info("FFmpeg 优雅停机完成: {}, 退出码: {}", stopped, executor.exitValue().orElse(-1));
    }

}
