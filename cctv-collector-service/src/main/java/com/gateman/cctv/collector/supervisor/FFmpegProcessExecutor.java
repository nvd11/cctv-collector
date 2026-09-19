package com.gateman.cctv.collector.supervisor;

import jakarta.enterprise.context.Dependent;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stateful operating system process wrapper and lifecycle adapter for an FFmpeg subprocess.
 * <p>
 * Annotated with {@link Dependent} pseudo-scope so that each watchdog instance (e.g. per camera stream)
 * receives its own dedicated executor instance. Encapsulates the underlying {@link Process} handle completely,
 * hiding pipe I/O and process destruction mechanics from higher-level supervisors.
 */
@Dependent
public class FFmpegProcessExecutor {

    private static final Logger LOG = Logger.getLogger(FFmpegProcessExecutor.class);

    private volatile Process currentProcess;

    /**
     * Spawns an external operating system process and retains its handle internally.
     * If an existing subprocess is currently active, it is forcibly terminated prior to launching the new one.
     *
     * @param commandArgs the complete command-line argument list (e.g., ["ffmpeg", "-rtsp_transport", "tcp", ...])
     * @throws IOException if an I/O error occurs during process creation
     * @throws NullPointerException if {@code commandArgs} is null
     * @throws IllegalArgumentException if {@code commandArgs} is empty
     */
    public synchronized void start(List<String> commandArgs) throws IOException {
        Objects.requireNonNull(commandArgs, "commandArgs must not be null");
        if (commandArgs.isEmpty()) {
            throw new IllegalArgumentException("commandArgs must not be empty");
        }

        if (isAlive()) {
            LOG.warnf("Existing process (pid: %d) is still active during start(). Forcibly terminating...", getPid());
            destroyForcibly();
        }

        LOG.infof("Spawning external process: %s", String.join(" ", commandArgs));

        this.currentProcess = new ProcessBuilder(commandArgs)
                .redirectErrorStream(false)
                .start();

        LOG.infof("Process successfully started with pid: %d", getPid());
    }

    /**
     * Blocks until the internally managed subprocess terminates, returning its exit code.
     *
     * @return the exit value of the subprocess
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws IllegalStateException if no process has been started
     */
    public int waitFor() throws InterruptedException {
        Process proc = this.currentProcess;
        if (proc == null) {
            throw new IllegalStateException("No active process has been started");
        }
        return proc.waitFor();
    }

    /**
     * Attempts to gracefully stop the active FFmpeg process by injecting the quit command character ('q') into its stdin pipe.
     * <p>
     * If the process does not terminate within {@code timeoutSeconds}, it is forcibly terminated using {@link Process#destroyForcibly()}.
     *
     * @param timeoutSeconds maximum seconds to wait for voluntary graceful exit before triggering SIGKILL
     * @return {@code true} if the process terminated voluntarily within the timeout, {@code false} if forcibly killed
     */
    public synchronized boolean stopGracefully(long timeoutSeconds) {
        Process proc = this.currentProcess;
        if (proc == null || !proc.isAlive()) {
            return true;
        }

        long pid = getPid();
        LOG.infof("Initiating graceful shutdown for process (pid: %d) with %ds timeout...", pid, timeoutSeconds);

        // 1. Inject 'q\n' into stdin to trigger FFmpeg moov atom finalization
        try {
            OutputStream stdin = proc.getOutputStream();
            stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            LOG.debugf("Sent 'q' signal to stdin of process (pid: %d)", pid);
        } catch (IOException e) {
            LOG.warnf("Failed to write 'q' to stdin of process (pid: %d): %s", pid, e.getMessage());
        }

        // 2. Wait for voluntary termination
        try {
            boolean exited = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (exited) {
                LOG.infof("Process (pid: %d) terminated gracefully with exitCode: %d", pid, proc.exitValue());
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Interrupted while waiting for process (pid: %d) to terminate gracefully", pid);
        }

        // 3. Fallback to forcible SIGKILL
        LOG.warnf("Process (pid: %d) did not terminate within %d seconds. Forcibly destroying...", pid, timeoutSeconds);
        destroyForcibly();
        return false;
    }

    /**
     * Checks whether the managed process is currently alive and running.
     *
     * @return {@code true} if an active process is alive, {@code false} otherwise
     */
    public boolean isAlive() {
        Process proc = this.currentProcess;
        return proc != null && proc.isAlive();
    }

    /**
     * Forcibly destroys the managed process using {@link Process#destroyForcibly()} (SIGKILL).
     */
    public synchronized void destroyForcibly() {
        Process proc = this.currentProcess;
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            try {
                proc.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LOG.warnf("Process (pid: %d) forcibly destroyed.", getProcessPidSafe(proc));
        }
    }

    /**
     * Returns the error stream (stderr) of the managed process, typically used for log and progress parsing.
     *
     * @return {@link InputStream} from stderr, or {@code null} if no process is active
     */
    public InputStream getErrorStream() {
        Process proc = this.currentProcess;
        return proc != null ? proc.getErrorStream() : null;
    }

    /**
     * Returns the standard input stream (stdout) of the managed process.
     *
     * @return {@link InputStream} from stdout, or {@code null} if no process is active
     */
    public InputStream getInputStream() {
        Process proc = this.currentProcess;
        return proc != null ? proc.getInputStream() : null;
    }

    /**
     * Retrieves the operating system process identifier (PID) of the managed process.
     *
     * @return the process PID, or {@code -1} if no active process exists
     */
    public long getPid() {
        Process proc = this.currentProcess;
        return proc != null ? getProcessPidSafe(proc) : -1L;
    }

    /**
     * Retrieves the exit code of the process if it has already terminated.
     *
     * @return {@link Optional} containing the exit code, or empty if still running or uninitialized
     */
    public Optional<Integer> exitValue() {
        Process proc = this.currentProcess;
        if (proc != null && !proc.isAlive()) {
            try {
                return Optional.of(proc.exitValue());
            } catch (IllegalThreadStateException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private long getProcessPidSafe(Process process) {
        try {
            return process.pid();
        } catch (Exception e) {
            return -1L;
        }
    }
}
