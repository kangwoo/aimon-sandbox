package at.aimon.sandbox.opensandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.sandbox.provider.ExecKillCause;
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
 * <li>Output arrives as one event per line. From execd {@code release-1.1.1} on (upstream #2033) an event's text
 * keeps the line's own ending ({@code \n}, {@code \r}, or none for a last line the command did not end), so the
 * events concatenate to the exact output; older execd strips the ending and sends a blank line as {@code "\n"}. Which
 * contract a command's execd follows is read from its events ({@link Endings}), and older execd's lines get their
 * {@code \n} back. Each line is handed to the sink and captured per stream up to {@code maxCaptureBytes} counted after
 * UTF-8 encoding — the rest is discarded and the stream marked truncated, while the command keeps running to its own
 * end (step-4 design D8).</li>
 * <li>{@link #kill()} and an {@link #await} past its timeout always send {@code DELETE /command?id=}: a disconnect
 * does not stop a command. A kill before the {@code init} event is latched and sent when the id arrives.</li>
 * <li>A stream that ends without a terminal event is followed by polling {@code /command/status/{id}}, which keeps
 * the exit code for 24 hours.</li>
 * <li>Exit codes come from {@code error.exit_code} (execd {@code release-1.2.0}, upstream #2303), else from
 * {@code error.evalue}; a signal ({@code -1}) becomes {@code 128 + n} from the traceback's signal name.</li>
 * <li>An event line over {@link SseEvents#MAX_LINE_BYTES} stops the reading: the command is killed, both streams are
 * marked truncated, and the exit code is read from the command's status (docs/design/ptc-shell-prerequisites.md
 * S2). The outcome says so in {@link ExecOutcome#killCause()} (S8), unless a timeout or a caller's kill was already on
 * its way.</li>
 * <li>For a {@link ExecSpec#transientOutput() transient} command, execd's spool files of its output are removed once
 * it has ended, before {@link #await} reports it (S1). execd spools them in {@code $TMPDIR/opensandbox-execd/}, taken
 * as {@code /tmp/opensandbox-execd/}: the files API cannot expand an unset variable. (Only execd before
 * {@code release-1.1.0} spooled in {@code $TMPDIR} itself; the provider requires {@code release-1.2.0}.)</li>
 * </ul>
 */
final class OpenSandboxRunningCommand implements RunningCommand {

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxRunningCommand.class);

    /**
     * How long {@link #await} waits for a killed command's stream to end before reporting without it — the default of
     * the provider's {@code killGrace}, which only tests set otherwise.
     */
    static final Duration KILL_GRACE = Duration.ofSeconds(8);
    /** How long past the spec's timeout status polling may go on after a dropped stream. */
    private static final Duration POLL_SLACK = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final int SIGKILL_EXIT = 128 + 9;
    /** Where execd spools a foreground command's output: {@code os.TempDir()/opensandbox-execd}. */
    static final List<String> SPOOL_DIRECTORIES = List.of("/tmp/opensandbox-execd");
    /** A command id fit to name a spool file: execd's are 32 hex digits. */
    private static final Pattern SPOOL_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Map<String, Integer> SIGNALS = Map.of("hangup", 1, "interrupt", 2, "quit", 3, "killed", 9,
            "terminated", 15, "aborted", 6, "broken pipe", 13, "alarm clock", 14);

    private final ExecdClient execd;
    private final ExecSpec spec;
    private final OutputSink sink;
    private final Duration killGrace;
    private final Capture stdout;
    private final Capture stderr;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    /**
     * The command's exit as soon as it is known — before its spool is removed, after which {@link #exit} completes
     * with the same value: {@link #await} reports it when the removal outlasts the bounded wait for it.
     */
    private final CompletableFuture<Integer> endedExit = new CompletableFuture<>();
    private final long startedNanos = System.nanoTime();
    private final Object killLock = new Object();
    private volatile String commandId;
    private volatile InputStream stream;
    private volatile boolean signalled;
    /** Set when this provider ended the command itself (S8); then the outcome is never also timed out. */
    private volatile ExecKillCause killCause;
    private final AtomicBoolean spoolRemoved = new AtomicBoolean();
    private boolean killRequested;
    private boolean killSent;
    /** How this command's execd ends its output events; read and written on the reader thread only. */
    private Endings endings = Endings.UNKNOWN;
    /**
     * An output event without a line ending, held back while {@link #endings} is {@link Endings#UNKNOWN}: whether it
     * needs a {@code \n} is not known yet. Reader thread only.
     */
    private String heldStdout;
    private String heldStderr;

    /**
     * Which output-event contract a command's execd follows. Under {@link #PRESERVED} (execd {@code release-1.1.1}
     * and later) an event without an ending can only be a stream's last, flushed when the command ends; under
     * {@link #STRIPPED} every line arrives without one. So an unterminated event followed by another on the same
     * stream proves {@code STRIPPED}; an event ending in {@code \r}, or in {@code \n} after other text, proves
     * {@code PRESERVED} ({@code "\n"} alone is a blank line under both); and so does a terminal event carrying
     * {@code exit_code}, which only {@code release-1.2.0} sends.
     *
     * <p>
     * The provider requires {@code release-1.2.0}, yet still reads the older contract: the execd image is configured
     * apart from the server ({@code execd_image}, and on Kubernetes a pool's pre-warmed {@code execd-installer}), so a
     * 1.2.0 server can drive an older execd, and passing its stripped events through would glue every line together
     * without a word.
     */
    enum Endings {
        UNKNOWN, PRESERVED, STRIPPED
    }

    private OpenSandboxRunningCommand(ExecdClient execd, ExecSpec spec, OutputSink sink, Duration killGrace) {
        this.execd = execd;
        this.spec = spec;
        this.sink = sink;
        this.killGrace = killGrace;
        this.stdout = new Capture(spec.maxCaptureBytes());
        this.stderr = new Capture(spec.maxCaptureBytes());
    }

    /**
     * Starts the command: the request is sent here, so a refused one (a missing working directory is 400, a gone
     * sandbox 404) fails the call itself; the stream is read on {@code executor}.
     */
    static OpenSandboxRunningCommand start(ExecdClient execd, ExecSpec spec, OutputSink sink, Executor executor,
            Duration killGrace) {
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
        final OpenSandboxRunningCommand command = new OpenSandboxRunningCommand(execd, spec, sink, killGrace);
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
        try {
            try (in) {
                SseEvents.read(in, execd.transport().json, this::handle);
            } catch (SseEvents.LineTooLongException e) {
                // Before init there is no id to kill: the kill is latched and the command runs to its own timeout.
                // execd sends init before any output, so in practice the id is always known here.
                // Which stream the line belonged to is in the part not read: both are reported as cut.
                log.warn("Killing a command in {}: {}", execd.ref(), e.getMessage());
                stdout.truncate();
                stderr.truncate();
                synchronized (killLock) {
                    // The provider ends the command, and says why (S8) — unless a timeout or a caller's kill was
                    // already on its way: whichever ended the command first is the one reported.
                    if (!killRequested && !endedExit.isDone()) {
                        killCause = ExecKillCause.LINE_LIMIT_EXCEEDED;
                    }
                }
                kill();
            } catch (IOException | RuntimeException e) {
                log.debug("The command stream of {} broke: {}", execd.ref(), e.toString());
            }
            // No terminal event said which contract: a held line is taken as release-1.2.0, the supported execd,
            // sends it — its stream's unterminated last line.
            releaseHeld(endings == Endings.STRIPPED);
            if (!exit.isDone()) {
                pollStatus();
            }
        } finally {
            // When await gave up on the stream, the exit is settled without this thread: the spool goes now.
            removeSpool();
        }
    }

    /** The command has ended with {@code code}: what it spooled goes first, then {@link #await} returns. */
    private void finish(int code) {
        endedExit.complete(code);
        removeSpool();
        exit.complete(code);
    }

    private void fail(RuntimeException failure) {
        endedExit.completeExceptionally(failure);
        removeSpool();
        exit.completeExceptionally(failure);
    }

    /**
     * Removes execd's spool files of a transient command, once, through the files API (which reports a missing file
     * as success). All paths go in one call; execd stops at the first path it cannot remove (a directory, or a path
     * through a file — {@code /tmp/opensandbox-execd} made a plain file by the command) and answers 500, so a refused
     * call is followed by one call per path, and one bad path does not spare the others. A call execd did not act on
     * is not: a transport failure or timeout, a 502 (the server proxy's answer when it cannot connect to execd — at
     * once on a refused connection, after its own timeout on a connect timeout), or a 503/504
     * (execd before its runtime init, or a gateway in front). No path was tried, so none is known to be unremovable,
     * and two more calls would only reach the same execd that did not take the first — at best as fast failures, at
     * worst each waiting out its timeout. That run's spool then stays. Never throws: a failure is logged.
     */
    private void removeSpool() {
        if (!spec.transientOutput() || !spoolRemoved.compareAndSet(false, true)) {
            return;
        }
        final String id = commandId;
        if (id == null || !SPOOL_ID.matcher(id).matches()) {
            return;
        }
        final List<String> paths = new ArrayList<>();
        for (String directory : SPOOL_DIRECTORIES) {
            paths.add(directory + "/" + id + ".stdout");
            paths.add(directory + "/" + id + ".stderr");
        }
        if (removeFiles(paths, false) != Removal.REFUSED) {
            return;
        }
        for (String path : paths) {
            removeFiles(List.of(path), true);
        }
    }

    /** How one {@code DELETE /files} went. */
    private enum Removal {
        /** execd removed (or did not find) every path. */
        REMOVED,
        /** execd answered with an error: it stopped at a path it could not remove. */
        REFUSED,
        /** execd did not act on the call: it never reached execd, or got no answer from it. */
        UNANSWERED
    }

    private Removal removeFiles(List<String> paths, boolean warn) {
        final StringBuilder query = new StringBuilder();
        for (String path : paths) {
            query.append(query.length() == 0 ? "?" : "&").append("path=").append(encode(path));
        }
        String failure;
        Removal removal;
        try {
            final HttpResponse<byte[]> response = execd.send(ep -> ep.request("/files" + query).DELETE(),
                    "remove command output");
            final int status = response.statusCode();
            if (status < 300) {
                return Removal.REMOVED;
            }
            failure = "HTTP " + status;
            removal = status == 502 || status == 503 || status == 504 ? Removal.UNANSWERED : Removal.REFUSED;
        } catch (RuntimeException e) {
            failure = e.getMessage();
            removal = Removal.UNANSWERED;
        }
        if (warn || removal == Removal.UNANSWERED) {
            log.warn("Removing the output of a command in {} ({}) failed: {}", execd.ref(), paths, failure);
        } else {
            log.debug("Removing the output of a command in {} failed: {}; trying each file", execd.ref(), failure);
        }
        return removal;
    }

    /** @return false on a terminal event */
    private boolean handle(JsonNode event) {
        final String type = event.path("type").asText("");
        switch (type) {
            case "init" :
                // The first one names the command, for the kill and the spool paths; execd sends no other.
                if (commandId == null) {
                    commandId = event.path("text").asText(null);
                }
                synchronized (killLock) {
                    if (killRequested) {
                        sendKill();
                    }
                }
                return true;
            case "stdout" :
                output(OutputSink.Stream.STDOUT, event.path("text").asText(""));
                return true;
            case "stderr" :
                output(OutputSink.Stream.STDERR, event.path("text").asText(""));
                return true;
            case "execution_complete" :
                releaseHeld(!preserved(event));
                finish(0);
                return false;
            case "error" :
                releaseHeld(!preserved(event.path("error")));
                finish(exitCode(event.path("error")));
                return false;
            default :
                return true;
        }
    }

    /** @return whether, at a terminal event, the command's events are known to keep their endings */
    private boolean preserved(JsonNode carrier) {
        return endings == Endings.PRESERVED
                || endings == Endings.UNKNOWN && carrier.path("exit_code").isIntegralNumber();
    }

    /** One output event, its line ending restored when execd stripped it ({@link Endings}). */
    private void output(OutputSink.Stream stream, String text) {
        if (text.isEmpty()) {
            return;
        }
        final String held = stream == OutputSink.Stream.STDOUT ? heldStdout : heldStderr;
        if (held != null) {
            // Under PRESERVED nothing follows a stream's unterminated last line. Once that is known, the other
            // stream's held line goes too, so neither waits for its own next line.
            endings = Endings.STRIPPED;
            releaseHeld(true);
        }
        final char last = text.charAt(text.length() - 1);
        if (last == '\n' || last == '\r') {
            if (endings == Endings.UNKNOWN && !"\n".equals(text)) {
                endings = Endings.PRESERVED;
            }
            deliver(stream, text);
        } else if (endings == Endings.PRESERVED) {
            deliver(stream, text);
        } else if (endings == Endings.STRIPPED) {
            deliver(stream, text + "\n");
        } else {
            hold(stream, text);
        }
    }

    private void hold(OutputSink.Stream stream, String text) {
        if (stream == OutputSink.Stream.STDOUT) {
            heldStdout = text;
        } else {
            heldStderr = text;
        }
    }

    /** Delivers what {@link #output} held back, with a {@code \n} when {@code stripped}. */
    private void releaseHeld(boolean stripped) {
        final String suffix = stripped ? "\n" : "";
        if (heldStdout != null) {
            deliver(OutputSink.Stream.STDOUT, heldStdout + suffix);
            heldStdout = null;
        }
        if (heldStderr != null) {
            deliver(OutputSink.Stream.STDERR, heldStderr + suffix);
            heldStderr = null;
        }
    }

    private void deliver(OutputSink.Stream stream, String text) {
        final byte[] line = text.getBytes(StandardCharsets.UTF_8);
        (stream == OutputSink.Stream.STDOUT ? stdout : stderr).append(line);
        try {
            sink.accept(stream, line, 0, line.length);
        } catch (RuntimeException e) {
            log.warn("An OutputSink failed: {}", e.getMessage());
        }
    }

    private int exitCode(JsonNode error) {
        final JsonNode exitCode = error.path("exit_code");
        if (exitCode.isIntegralNumber() && exitCode.asInt() >= 0) {
            return exitCode.asInt();
        }
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
            fail(new SandboxProviderException(
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
                    finish(code < 0 ? SIGKILL_EXIT : code);
                    return;
                }
            } catch (SandboxNotFoundException e) {
                // Nothing is left to remove from a sandbox that is gone.
                endedExit.completeExceptionally(e);
                spoolRemoved.set(true);
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
        fail(new SandboxProviderException(
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
            try {
                return ended(exit.get(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                if (!endedExit.isDone()) {
                    return killed();
                }
                // The command ended in time and only the removal of its spool holds the exit back: it is neither
                // killed nor reported as timed out. The removal gets one request timeout more; past that, the exit
                // is reported while the removal goes on (several calls, or calls queued behind others, take longer).
                try {
                    return ended(exit.get(execd.transport().requestTimeout().toMillis(), TimeUnit.MILLISECONDS));
                } catch (TimeoutException stillRemoving) {
                    log.debug("Reporting a command in {} before its spool is removed", execd.ref());
                    return ended(endedExit.get());
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SandboxProviderException("the command failed: " + e.getCause(),
                    SandboxProviderException.Kind.TRANSIENT, e.getCause());
        }
    }

    /**
     * {@link #await} ran out: the command is killed and given the kill grace ({@link #KILL_GRACE}) to end. Whatever
     * ends it inside the grace — the kill, or itself just after the timeout — it is reported with its exit code and as
     * timed out: the caller's wait did run out before it ended.
     */
    private ExecOutcome killed() throws InterruptedException {
        kill();
        try {
            return outcome(exit.get(killGrace.toMillis(), TimeUnit.MILLISECONDS), true);
        } catch (TimeoutException | ExecutionException stillRunning) {
            // endedExit completing between the grace running out and this check reads as not done: that counts as
            // not ending inside the grace, so the command is given up on below with 137 like one that never ended.
            if (endedExit.isDone() && !endedExit.isCompletedExceptionally()) {
                // It ended inside the grace; only the removal of its spool outlasts it, and settles exit after.
                log.debug("Reporting a killed command in {} before its spool is removed", execd.ref());
                return outcome(endedExit.join(), true);
            }
            // Nothing answered even the kill (a half-open connection has no read timeout): give up on the
            // stream so its reader thread and connection do not outlive the command.
            exit.complete(SIGKILL_EXIT);
            closeQuietly(stream);
            return outcome(SIGKILL_EXIT, true);
        }
    }

    /** A command that ended by itself or by a kill: timed out when a signal ended it past its timeout. */
    private ExecOutcome ended(int code) {
        final boolean pastSpecTimeout = System.nanoTime() - startedNanos >= spec.timeout().toNanos();
        return outcome(code, signalled && pastSpecTimeout);
    }

    /**
     * The outcome. A command this provider ended for a long line reports that cause and is not timed out, even when
     * its exit — the line kill's — landed past the spec's timeout or after {@link #await} ran out: the line kill was
     * sent first ({@link ExecOutcome}).
     */
    private ExecOutcome outcome(int code, boolean timedOut) {
        final ExecKillCause cause = killCause;
        return ExecOutcome.builder().exitCode(code).stdout(stdout.bytes()).stderr(stderr.bytes())
                .stdoutTruncated(stdout.truncated()).stderrTruncated(stderr.truncated())
                .timedOut(timedOut && cause == null).killCause(cause).build();
    }

    /** Whether the command's exit is known, though its spool may still be being removed (for tests). */
    boolean hasEnded() {
        return endedExit.isDone();
    }

    @Override
    public void kill() {
        synchronized (killLock) {
            killRequested = true;
            if (commandId != null && !endedExit.isDone() && !exit.isDone()) {
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

        /** Marks the stream cut, as when output was dropped. */
        synchronized void truncate() {
            truncated = true;
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
