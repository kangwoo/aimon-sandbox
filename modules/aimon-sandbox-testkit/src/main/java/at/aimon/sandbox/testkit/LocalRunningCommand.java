package at.aimon.sandbox.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;

/** A local process group: output drained on two daemon threads, capped per stream, killed as a group. */
final class LocalRunningCommand implements RunningCommand {

    private static final long KILL_GRACE_MILLIS = 1500;
    private static final long DRAIN_GRACE_MILLIS = 500;

    private final Process process;
    private final long pgid;
    private final long deadlineNanos;
    private final LocalConnection connection;
    private final Set<LocalRunningCommand> registry;
    private final Capture stdout;
    private final Capture stderr;
    private final AtomicBoolean killed = new AtomicBoolean();
    private volatile boolean timedOut;

    LocalRunningCommand(Process process, ExecSpec spec, OutputSink sink, LocalConnection connection,
            Set<LocalRunningCommand> registry) {
        this.process = process;
        this.pgid = process.pid();
        this.deadlineNanos = System.nanoTime() + spec.timeout().toNanos();
        this.connection = connection;
        this.registry = registry;
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // stdin of a finished process: nothing to close
        }
        this.stdout = new Capture(process.getInputStream(), spec.maxCaptureBytes(), sink, OutputSink.Stream.STDOUT);
        this.stderr = new Capture(process.getErrorStream(), spec.maxCaptureBytes(), sink, OutputSink.Stream.STDERR);
    }

    @Override
    public ExecOutcome await(Duration timeout) throws InterruptedException {
        final long waitNanos = Math.min(timeout.toNanos(), Math.max(0, deadlineNanos - System.nanoTime()));
        if (!process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
            timedOut = true;
            kill();
            process.waitFor(KILL_GRACE_MILLIS * 2, TimeUnit.MILLISECONDS);
        }
        stdout.join(DRAIN_GRACE_MILLIS);
        stderr.join(DRAIN_GRACE_MILLIS);
        registry.remove(this);
        return ExecOutcome.builder().exitCode(process.isAlive() ? 137 : process.exitValue())
                .stdout(translate(stdout.bytes())).stderr(translate(stderr.bytes())).stdoutTruncated(stdout.truncated())
                .stderrTruncated(stderr.truncated()).timedOut(timedOut).build();
    }

    private byte[] translate(byte[] bytes) {
        final String text = new String(bytes, StandardCharsets.ISO_8859_1);
        final String translated = connection.translateOutput(text);
        return translated.equals(text) ? bytes : translated.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void kill() {
        if (!killed.compareAndSet(false, true)) {
            return;
        }
        signal("TERM");
        final Thread killer = new Thread(() -> {
            try {
                // Even when the leader is gone after the grace, descendants in its group may not be.
                process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                signal("KILL");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "local-sandbox-kill-" + pgid);
        killer.setDaemon(true);
        killer.start();
    }

    /**
     * Signals the process group. Right after the start, perl may not have run {@code setpgrp} yet, so there is no
     * group to signal: the leader itself gets the signal then — it has not started anything that could escape it.
     */
    private void signal(String name) {
        boolean delivered = false;
        try {
            final Process kill = new ProcessBuilder("/bin/kill", "-" + name, "--", "-" + pgid).start();
            kill.getInputStream().readAllBytes();
            kill.getErrorStream().readAllBytes();
            delivered = kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0;
        } catch (IOException e) {
            delivered = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!delivered && process.isAlive()) {
            if ("KILL".equals(name)) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }

    /** One stream, drained on a daemon thread: everything is counted, at most {@code max} bytes are kept. */
    private static final class Capture implements Runnable {
        private final InputStream in;
        private final long max;
        private final OutputSink sink;
        private final OutputSink.Stream stream;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final Thread thread;
        private long total;

        Capture(InputStream in, long max, OutputSink sink, OutputSink.Stream stream) {
            this.in = in;
            this.max = max;
            this.sink = sink;
            this.stream = stream;
            this.thread = new Thread(this, "local-sandbox-" + stream.name().toLowerCase(java.util.Locale.ROOT));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        @Override
        public void run() {
            final byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (this) {
                        final long room = Math.max(0, max - kept.size());
                        kept.write(buffer, 0, (int) Math.min(room, n));
                        total += n;
                    }
                    sink.accept(stream, buffer, 0, n);
                }
            } catch (IOException e) {
                // The process ended or was killed: keep what was read.
            }
        }

        void join(long millis) throws InterruptedException {
            thread.join(millis);
        }

        synchronized byte[] bytes() {
            return kept.toByteArray();
        }

        synchronized boolean truncated() {
            return total > max;
        }
    }
}
