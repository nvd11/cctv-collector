package com.gateman.cctv.collector.supervisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FFmpegProcessExecutorTest {

    private FFmpegProcessExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new FFmpegProcessExecutor();
    }

    @Test
    @DisplayName("Should successfully spawn and manage lifecycle of an external process")
    void shouldSpawnProcessSuccessfully() throws Exception {
        List<String> command = List.of("sh", "-c", "echo 'cctv-test-process'");
        executor.start(command);

        int exitCode = executor.waitFor();
        assertThat(exitCode).isZero();
        assertThat(executor.isAlive()).isFalse();
        assertThat(executor.exitValue()).contains(0);
    }

    @Test
    @DisplayName("Should reject null or empty command argument lists")
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> executor.start(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("commandArgs must not be null");

        assertThatThrownBy(() -> executor.start(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandArgs must not be empty");
    }

    @Test
    @DisplayName("Should return true when gracefully stopping when no process is active")
    void shouldHandleNoActiveProcessOnStop() {
        assertThat(executor.stopGracefully(5)).isTrue();
        assertThat(executor.isAlive()).isFalse();
        assertThat(executor.getPid()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Should voluntarily terminate process upon injecting 'q' into stdin")
    void shouldGracefullyTerminateOnStdinQuitSignal() throws Exception {
        // A bash process that waits for 'q' from stdin and exits cleanly with 0
        List<String> command = List.of("sh", "-c", "read line; if [ \"$line\" = 'q' ]; then exit 0; else exit 1; fi");
        executor.start(command);

        assertThat(executor.isAlive()).isTrue();
        assertThat(executor.getPid()).isPositive();

        boolean graceful = executor.stopGracefully(5);
        assertThat(graceful).isTrue();
        assertThat(executor.isAlive()).isFalse();
        assertThat(executor.exitValue()).contains(0);
    }

    @Test
    @DisplayName("Should fall back to forcible SIGKILL when process ignores 'q' and exceeds timeout")
    void shouldFallbackToForcibleKillWhenTimeoutExceeded() throws Exception {
        // A bash process that sleeps for 30 seconds, ignoring stdin
        List<String> command = List.of("sh", "-c", "sleep 30");
        executor.start(command);

        assertThat(executor.isAlive()).isTrue();

        // 1 second timeout should trigger destroyForcibly()
        boolean graceful = executor.stopGracefully(1);

        assertThat(graceful).isFalse();
        assertThat(executor.isAlive()).isFalse();
    }

    @Test
    @DisplayName("Should expose error stream and PID for monitoring")
    void shouldExposeErrorStreamAndPid() throws Exception {
        List<String> command = List.of("sh", "-c", "echo 'error-log-data' >&2; sleep 5");
        executor.start(command);

        assertThat(executor.getPid()).isPositive();
        assertThat(executor.getErrorStream()).isNotNull();

        // Verify stderr output can be read
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(executor.getErrorStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            assertThat(line).isEqualTo("error-log-data");
        }

        executor.destroyForcibly();
        assertThat(executor.isAlive()).isFalse();
    }
}
