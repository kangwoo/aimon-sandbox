package at.aimon.sandbox.opensandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * One execd {@code POST /command}, streamed on a provider thread (docs/design/opensandbox-spike.md §3).
 *
 * <ul>
 * <li>Output arrives as one event per line: each gets its {@code \n} back ({@code "\n"} itself is an empty line), is
 * handed to the sink, and is captured per stream up to {@code maxCaptureBytes} counted after UTF-8 encoding — the rest
 * is discarded and the stream marked truncated, while the command keeps running to its own end (step-4 design D8).</li>
 * <li>{@link #kill()} and an {@link #await} past its timeout always send {@code DELETE /command?id=}: a disconnect
 * does not stop a command. A kill before the {@code init} event is latched and sent when the id arrives.</li>
 * <li>A stream that ends without a terminal event is followed by polling {@code /command/status/{id}}, which keeps
 * the exit code for 24 hours.</li>
 * <li>Exit codes come from {@code error.evalue}; a signal ({@code -1}) becomes {@code 128 + n} from the traceback's
 * signal name.</li>
 * </ul>
 */
final class OpenSandboxRunningCommand implements RunningCommand {

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxRunningCommand.class);

    /** How long {@link #await} waits for a killed command's stream to end before reporting without it. */
    static final Duration KILL_GRACE = Duration.ofSeconds(8);
    /** How long past the spec's timeout status polling may go on after a dropped stream. */
    private static final Duration POLL_SLACK = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final int SIGKILL_EXIT = 128 + 9;
    private static final Map<String, Integer> SIGNALS = Map.of("hangup", 1, "interrupt", 2, "quit", 3, "killed", 9,
            "terminated", 15, "aborted", 6, "broken pipe", 13, "alarm clock", 14);

    private final ExecdClient execd;
    private final ExecSpec spec;
    private final OutputSink sink;
    private final Capture stdout;
    private final Capture stderr;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final long startedNanos = System.nanoTime();
    private final Object killLock = new Object();
    private volatile String commandId;
    private volatile InputStream stream;
    private volatile boolean signalled;
    private boolean killRequested;
    private boolean killSent;

    private OpenSandboxRunningCommand(ExecdClient execd, ExecSpec spec, OutputSink sink) {
        this.execd = execd;
        this.spec = spec;
        this.sink = sink;
        this.stdout = new Capture(spec.maxCaptureBytes());
        this.stderr = new Capture(spec.maxCaptureBytes());
    }

    /**
     * Starts the command: the request is sent here, so a refused one (a missing working directory is 400, a gone
     * sandbox 404) fails the call itself; the stream is read on {@code executor}.
     */
    static OpenSandboxRunningCommand start(ExecdClient execd, ExecSpec spec, OutputSink sink, Executor executor) {
        final ObjectNode body = execd.transport().json.createObjectNode().put("command", spec.command());
        spec.workingDirectory().ifPresent(cwd -> body.put("cwd", cwd));
        if (!spec.environment().isEmpty()) {
            final ObjectNode envs = body.putObject("envs");
            spec.environment().forEach(envs::put);
        }
        body.put("timeout", Math.max(1, spec.timeout().toMillis()));
        final byte[] payload = execd.transport().write(body);
        final HttpResponse<InputStream> response = execd.stream(ep -> ep.request("/command")
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), "run command");
        if (response.statusCode() != 200) {
            final byte[] error = drain(response.body());
            try {
                HttpErrors.check(response.statusCode(), error, "run command");
            } catch (HttpErrors.NotFound e) {
                throw execd.notFound(e, () -> HttpErrors.failure(404, "run command", e.getMessage()));
            }
            throw HttpErrors.failure(response.statusCode(), "run command", new String(error, StandardCharsets.UTF_8));
        }
        final OpenSandboxRunningCommand command = new OpenSandboxRunningCommand(execd, spec, sink);
        command.stream = response.body();
        try {
            executor.execute(() -> command.read(response.body()));
        } catch (RejectedExecutionException e) {
            closeQuietly(response.body());
            throw new SandboxProviderException("the OpenSandbox provider is closed",
                    SandboxProviderException.Kind.TRANSIENT, e);
        }
        return command;
    }

    private void read(InputStream in) {
        try (in) {
            SseEvents.read(in, execd.transport().json, this::handle);
        } catch (IOException | RuntimeException e) {
            log.debug("The command stream of {} broke: {}", execd.ref(), e.toString());
        } finally {
            if (!exit.isDone()) {
                pollStatus();
            }
        }
    }

    /** @return false on a terminal event */
    private boolean handle(JsonNode event) {
        final String type = event.path("type").asText("");
        switch (type) {
            case "init" :
                commandId = event.path("text").asText(null);
                synchronized (killLock) {
                    if (killRequested) {
                        sendKill();
                    }
                }
                return true;
            case "stdout" :
                deliver(OutputSink.Stream.STDOUT, stdout, event.path("text").asText(""));
                return true;
            case "stderr" :
                deliver(OutputSink.Stream.STDERR, stderr, event.path("text").asText(""));
                return true;
            case "execution_complete" :
                exit.complete(0);
                return false;
            case "error" :
                exit.complete(exitCode(event.path("error")));
                return false;
            default :
                return true;
        }
    }

    private void deliver(OutputSink.Stream stream, Capture capture, String text) {
        final byte[] line = ("\n".equals(text) ? "\n" : text + "\n").getBytes(StandardCharsets.UTF_8);
        capture.append(line);
        try {
            sink.accept(stream, line, 0, line.length);
        } catch (RuntimeException e) {
            log.warn("An OutputSink failed: {}", e.getMessage());
        }
    }

    private int exitCode(JsonNode error) {
        final String value = error.path("evalue").asText("");
        try {
            final int code = Integer.parseInt(value.strip());
            if (code >= 0) {
                return code;
            }
        } catch (NumberFormatException e) {
            // an exec failure (argument list too long) carries no number: reported as 126, "cannot execute"
            if (!value.isEmpty() && !"-1".equals(value.strip())) {
                return 126;
            }
        }
        signalled = true;
        final String traceback = error.path("traceback").toString().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> signal : SIGNALS.entrySet()) {
            if (traceback.contains("signal: " + signal.getKey())) {
                return 128 + signal.getValue();
            }
        }
        return SIGKILL_EXIT;
    }

    /** The stream ended without a terminal event: the exit code is read from the command's status. */
    private void pollStatus() {
        final String id = commandId;
        if (id == null) {
            exit.completeExceptionally(new SandboxProviderException(
                    "the command stream of " + execd.ref() + " ended before the command started"));
            return;
        }
        final long deadline = startedNanos + spec.timeout().plus(POLL_SLACK).toNanos();
        while (System.nanoTime() - deadline < 0 && !exit.isDone()) {
            try {
                final JsonNode status = status(id);
                if (!status.path("running").asBoolean(true)) {
                    final int code = status.path("exit_code").asInt(-1);
                    if (code < 0) {
                        signalled = true;
                    }
                    exit.complete(code < 0 ? SIGKILL_EXIT : code);
                    return;
                }
            } catch (SandboxNotFoundException e) {
                exit.completeExceptionally(e);
                return;
            } catch (RuntimeException e) {
                log.debug("Reading the status of a command in {} failed: {}", execd.ref(), e.getMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        exit.completeExceptionally(new SandboxProviderException(
                "lost the command stream of " + execd.ref() + " and could not read its exit code"));
    }

    private JsonNode status(String id) {
        final HttpResponse<byte[]> response = execd.send(ep -> ep.request("/command/status/" + encode(id)).GET(),
                "command status");
        try {
            return execd.transport().read(HttpErrors.check(response, "command status"));
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> HttpErrors.failure(404, "command status", e.getMessage()));
        }
    }

    @Override
    public ExecOutcome await(Duration timeout) throws InterruptedException {
        try {
            final int code = exit.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
            final boolean pastSpecTimeout = System.nanoTime() - startedNanos >= spec.timeout().toNanos();
            return outcome(code, signalled && pastSpecTimeout);
        } catch (TimeoutException e) {
            kill();
            try {
                return outcome(exit.get(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS), true);
            } catch (TimeoutException | ExecutionException stillRunning) {
                // Nothing answered even the kill (a half-open connection has no read timeout): give up on the
                // stream so its reader thread and connection do not outlive the command.
                exit.complete(SIGKILL_EXIT);
                closeQuietly(stream);
                return outcome(SIGKILL_EXIT, true);
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SandboxProviderException("the command failed: " + e.getCause(),
                    SandboxProviderException.Kind.TRANSIENT, e.getCause());
        }
    }

    private ExecOutcome outcome(int code, boolean timedOut) {
        return ExecOutcome.builder().exitCode(code).stdout(stdout.bytes()).stderr(stderr.bytes())
                .stdoutTruncated(stdout.truncated()).stderrTruncated(stderr.truncated()).timedOut(timedOut).build();
    }

    @Override
    public void kill() {
        synchronized (killLock) {
            killRequested = true;
            if (commandId != null && !exit.isDone()) {
                sendKill();
            }
        }
    }

    /** {@code DELETE /command?id=}: SIGTERM to the process group, SIGKILL three seconds later. Sent once. */
    private void sendKill() {
        if (killSent) {
            return;
        }
        killSent = true;
        try {
            final HttpResponse<byte[]> response = execd
                    .send(ep -> ep.request("/command?id=" + encode(commandId)).DELETE(), "interrupt command");
            if (response.statusCode() >= 300 && response.statusCode() != 404) {
                log.warn("Interrupting a command in {} answered HTTP {}", execd.ref(), response.statusCode());
            }
        } catch (RuntimeException e) {
            log.warn("Interrupting a command in {} failed: {}", execd.ref(), e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] drain(InputStream in) {
        try (in) {
            return in.readNBytes(64 * 1024);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // nothing to release
        }
    }

    /** One stream's capture: the first {@code max} bytes, then a count of what was dropped. */
    private static final class Capture {
        private final long max;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;

        Capture(long max) {
            this.max = max;
        }

        synchronized void append(byte[] chunk) {
            final long room = max - bytes.size();
            if (room >= chunk.length) {
                bytes.write(chunk, 0, chunk.length);
                return;
            }
            if (room > 0) {
                bytes.write(chunk, 0, (int) room);
            }
            truncated = true;
        }

        synchronized byte[] bytes() {
            return bytes.toByteArray();
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
