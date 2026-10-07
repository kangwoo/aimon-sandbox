package at.aimon.sandbox.environment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellation;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.sandbox.SandboxSettings;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.provider.WriteMode;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.Heartbeat;
import at.aimon.sandbox.workspace.SandboxUnavailableException;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * The shell of a sandbox environment (docs/design/workspace-sandbox.md §9): one {@code run} per command, wrapped by
 * {@link ShellWrapper} so {@code cd} and {@code export} persist per {@link at.aimon.sandbox.binding.ShellKey} in a
 * state file inside the sandbox.
 *
 * <p>
 * Each call connects (lazy provisioning), records activity, takes the node-local lock of its shell (the in-sandbox
 * {@code flock} covers other nodes) and runs the wrapper with a heartbeat. The command timeout starts when the lock is
 * held; lock waits and provisioning do not count against it, and a {@code null} timeout is none, as core defines it
 * (only the exec's backstop of {@link #NO_TIMEOUT_BACKSTOP} applies). A background command
 * ({@link ExecutionOptions#isBackground()}) and a hook's command ({@link ExecutionOptions#isHook()}) take no lock
 * and save no state: they start from the session's cwd and exports, and their own {@code cd} and {@code export} end
 * with them. A hook's command keeps the foreground's in-sandbox watchdog, so it ends at its own timeout rather than at
 * the exec's backstop. A timeout or interrupt kills the command's process group; the state from before the command
 * remains, and the result says so.
 *
 * <p>
 * <b>Cancellation</b> ({@link ShellFeature#CANCELLATION}, foreground and background alike). The signal of
 * {@link ExecutionOptions#getCancellation()} is looked at three times before the command starts — on entry, before
 * anything is provisioned or recorded; once the shell lock is held; and right before the exec — and a signal tripped
 * by then means the command is not started. Provisioning and the lock wait themselves are not aborted: the first is
 * shared with every execution bound to the slot, the second is at most {@code shellLockWait}. Tripped while the command
 * runs, the signal kills it through {@link RunningCommand#kill()}, on the cancelling thread, and {@code execute} throws
 * {@link ShellCancelledException} with the output written so far. What decides between "cancelled" and a normal result
 * is the wrapper's trailer, not the signal: a command that printed it had ended by itself before the kill landed. An
 * interrupt of the waiting thread after the signal tripped — core's shutdown cancels, then interrupts — is the same
 * cancellation: the command is killed and {@code execute} throws {@link ShellCancelledException}, the interrupt flag
 * set; only an interrupt without a cancel is a failure of its own.
 *
 * <p>
 * The kill — for a timeout, an interrupt and a cancellation — reaches the exec's <b>process group</b>: the wrapper,
 * the command and every descendant that stayed in the group. A job the command moved into a group or session of its
 * own ({@code setsid}, {@code set -m}) outlives it until the sandbox goes; the exec server offers nothing wider (§9).
 */
final class SandboxShell implements VirtualShell {

    /** Room left for the wrapper's trailer above the in-sandbox cap; the trailer itself is under 128 bytes. */
    static final long TRAILER_ALLOWANCE = 1024;

    /**
     * How much a provider's line normalization can grow a stream: every invalid UTF-8 byte becomes U+FFFD, three bytes
     * (docs/design/workspace-sandbox.md §6.1).
     */
    static final long NORMALIZATION_GROWTH = 3;

    static final String KILLED_NOTICE = "the command was killed; its cd/export were not applied";

    static final String CANCELLED_MESSAGE = "the command was cancelled and killed";

    static final String CANCELLED_BEFORE_START_MESSAGE = "the command was cancelled before it started";

    private static final Logger log = LoggerFactory.getLogger(SandboxShell.class);
    /** The exec backstop of a command without a timeout: the provider still needs one. */
    static final Duration NO_TIMEOUT_BACKSTOP = Duration.ofDays(1);
    private static final long DEFAULT_MAX_CAPTURE = 1024 * 1024;
    private static final Duration BACKSTOP_SLACK = Duration.ofSeconds(5);
    private static final Pattern TRAILER = Pattern
            .compile("exit=(-?\\d+) out=\\s*(\\d+) err=\\s*(\\d+) cwd=(\\d)\\s*$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SandboxBinding binding;
    private final SandboxWorkspaceManager manager;
    private final SandboxConnectionCache connections;
    private final SandboxSettings settings;
    private final PendingNotices pending;
    private final Clock clock;

    SandboxShell(SandboxBinding binding, SandboxWorkspaceManager manager, SandboxConnectionCache connections,
            SandboxSettings settings, PendingNotices pending, Clock clock) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.pending = Objects.requireNonNull(pending, "pending must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
        return execute(command, ExecutionOptions.defaults());
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(options, "options must not be null");
        final String text = command.asString();
        if (text == null || text.indexOf('\0') >= 0) {
            throw new ShellExecutionException("the command contains a NUL byte, which a shell cannot run");
        }
        if (options.getWorkingDirectory() != null && options.getWorkingDirectory().indexOf('\0') >= 0) {
            throw new ShellExecutionException("the working directory contains a NUL byte, which no path can hold");
        }
        final List<String> nul = options.getEnvironment().entrySet().stream()
                .filter(variable -> variable.getValue() != null && variable.getValue().indexOf('\0') >= 0)
                .map(Map.Entry::getKey).sorted().toList();
        if (!nul.isEmpty()) {
            throw new ShellExecutionException(
                    "environment variables whose values contain a NUL byte, which a " + "shell cannot pass: " + nul);
        }
        final List<String> invalid = ShellWrapper.invalidNames(options.getEnvironment().keySet());
        if (!invalid.isEmpty()) {
            throw new ShellExecutionException("invalid environment variable names: " + invalid);
        }
        final long inline = ShellWrapper.inlineSize(options.getEnvironment(), options.getWorkingDirectory());
        if (inline > ShellWrapper.INLINE_ENVIRONMENT_LIMIT) {
            // They travel in the exec's one argument: over MAX_ARG_STRLEN the exec fails as if the sandbox were gone.
            throw new ShellExecutionException("the command's environment variables and working directory are too large"
                    + " to pass to the sandbox (" + inline + " bytes, at most " + ShellWrapper.INLINE_ENVIRONMENT_LIMIT
                    + "); pass large values through a file");
        }
        final ShellCancellation cancellation = options.getCancellation();
        if (cancellation.isCancelled()) {
            // Before connect: a command that is not to run provisions nothing and records no activity.
            throw cancelledBeforeStart();
        }
        final ConnectedSlot slot = manager.connect(binding);
        pending.addAll(slot.notices());
        slot.activity().record(false);
        if (options.isBackground() || options.isHook()) {
            // Neither may hold the session's shell: a background command outlives the call, and a hook runs on behalf
            // of the runtime between the model's own commands (§9, §12.1). Both read the session's state, save none.
            return run(slot, text, options, false);
        }
        final SandboxConnectionCache.Lease lease;
        try {
            lease = connections.lockShell(slot.ref(), binding.shellKey().value(), settings.shellLockWait());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShellExecutionException("interrupted while waiting for the shell", e);
        }
        if (!lease.acquired()) {
            throw new ShellExecutionException(
                    "the shell is busy with another command of this session (waited "
                            + settings.shellLockWait().toSeconds() + "s); retry once it finishes",
                    null, "", "", false, pending.drain());
        }
        try (lease) {
            if (cancellation.isCancelled()) {
                // Tripped while provisioning or waiting for the lock: neither wait is aborted, the command is.
                throw cancelledBeforeStart();
            }
            return run(slot, text, options, true);
        }
    }

    /**
     * The provider-side capture cap of one wrapper run: a backstop the wrapper's own {@code head -c {max}} never lets
     * raw output reach, sized so that a line-normalizing provider cannot cut the trailer off either — {@code max} raw
     * bytes can arrive as {@code 3 × max} (§6.1). Saturates instead of overflowing for a huge {@code max}.
     */
    static long captureBackstop(long max) {
        if (max > (Long.MAX_VALUE - TRAILER_ALLOWANCE) / NORMALIZATION_GROWTH) {
            return Long.MAX_VALUE;
        }
        return max * NORMALIZATION_GROWTH + TRAILER_ALLOWANCE;
    }

    private ShellCommandResult run(ConnectedSlot slot, String text, ExecutionOptions options, boolean foreground)
            throws ShellExecutionException {
        final String directory = ShellWrapper.directory(binding.shellKey().directoryName());
        final String runPrefix = directory + "/run-" + randomHex(6);
        final String nonce = "AIMON-" + randomHex(16);
        final Duration timeout = options.getTimeout();
        final long max = options.getMaxCaptureBytes() != null ? options.getMaxCaptureBytes() : DEFAULT_MAX_CAPTURE;
        final SandboxFiles files = slot.connection().files();
        final boolean execKey = binding.shellKey().isExecution();
        if (execKey) {
            connections.execShellStarted(slot.ref(), directory);
        }
        boolean wrapperCleanedUp = false;
        try {
            final ShellWrapper.Invocation invocation = new ShellWrapper.Invocation().command(text).directory(directory)
                    .runPrefix(runPrefix).root(binding.root()).nodeId(settings.nodeId()).nonce(nonce)
                    .lockWait(settings.shellLockWait()).timeout(timeout).maxBytes(max)
                    .redirectErrorStream(options.isRedirectErrorStream())
                    .workingDirectory(options.getWorkingDirectory()).environment(options.getEnvironment());
            if (!ShellWrapper.embeddable(text)) {
                // Too large for one exec argument (MAX_ARG_STRLEN): the command goes in as a file instead.
                upload(files, runPrefix + ".cmd", text.getBytes(StandardCharsets.UTF_8), "the command");
                invocation.commandUploaded(true);
            }
            if (options.getStdin() != null) {
                upload(files, runPrefix + ".in", options.getStdin().getBytes(options.getCharset()), "stdin");
                invocation.stdinPath(runPrefix + ".in");
            }
            final Mode mode = foreground ? Mode.FOREGROUND : options.isHook() ? Mode.HOOK : Mode.BACKGROUND;
            final String script = switch (mode) {
                case FOREGROUND -> ShellWrapper.foreground(invocation);
                case HOOK -> ShellWrapper.hook(invocation);
                case BACKGROUND -> ShellWrapper.background(invocation);
            };
            final Duration backstop = (timeout != null ? timeout : NO_TIMEOUT_BACKSTOP)
                    .plus(foreground ? settings.shellLockWait() : Duration.ZERO).plus(BACKSTOP_SLACK);
            // Not binding.root(): a command may have removed it, and an exec server may refuse a missing directory
            // before the wrapper could fall back. The wrapper moves into the root itself.
            final ExecSpec spec = ExecSpec.builder().command(script).workingDirectory(ShellWrapper.EXEC_DIRECTORY)
                    .environment(slot.profile().environment()).timeout(backstop).maxCaptureBytes(captureBackstop(max))
                    .build();
            final ShellCommandResult result = await(slot, spec, options.getCancellation(),
                    new Run(runPrefix, nonce, timeout != null ? timeout : backstop, max, options.getCharset(), mode,
                            options.getWorkingDirectory() != null));
            // A result means the trailer was read, and the wrapper removed its run files before printing it.
            wrapperCleanedUp = true;
            return result;
        } catch (SandboxNotFoundException e) {
            slot.activity().markLost();
            throw new ShellExecutionException(SandboxWorkspaceManager.LOST_MESSAGE, e, "", "", false, pending.drain());
        } catch (SandboxProviderException e) {
            throw new SandboxUnavailableException("the sandbox cannot be reached: " + e.getMessage(), e);
        } finally {
            if (!wrapperCleanedUp) {
                cleanUp(files, runPrefix);
            }
            if (execKey) {
                connections.execShellFinished(slot.ref(), directory);
            }
        }
    }

    /**
     * Writes a run file through the files API. A filesystem error there (a full disk) is the command's failure, not a
     * raw exception for the tool; a provider failure goes on to the caller's handling.
     */
    private static void upload(SandboxFiles files, String path, byte[] content, String what)
            throws ShellExecutionException {
        try {
            files.write(path, new ByteArrayInputStream(content), content.length, WriteMode.CREATE_OR_REPLACE);
        } catch (VirtualFileSystemException e) {
            throw new ShellExecutionException("could not pass " + what + " into the sandbox: " + e.getMessage(), e);
        }
    }

    /** Which wrapper script a run uses (§9). */
    private enum Mode {
        /** The model's command: the shell's lock, the state save and the watchdog. */
        FOREGROUND,
        /** A background command: none of the three; the exec's backstop is its timeout. */
        BACKGROUND,
        /** A hook's command: no lock and no save, but the watchdog, so it ends at its own timeout. */
        HOOK
    }

    /** One invocation's identity and limits. */
    private static final class Run {
        private final String prefix;
        private final String nonce;
        private final Duration timeout;
        private final long max;
        private final Charset charset;
        private final boolean foreground;
        /** The wrapper runs a watchdog, which marks a timeout with {@code .timedout}. */
        private final boolean watched;
        private final boolean ownWorkingDirectory;

        private Run(String prefix, String nonce, Duration timeout, long max, Charset charset, Mode mode,
                boolean ownWorkingDirectory) {
            this.prefix = prefix;
            this.nonce = nonce;
            this.timeout = timeout;
            this.max = max;
            this.charset = charset;
            this.foreground = mode == Mode.FOREGROUND;
            this.watched = mode != Mode.BACKGROUND;
            this.ownWorkingDirectory = ownWorkingDirectory;
        }
    }

    private ShellCommandResult await(ConnectedSlot slot, ExecSpec spec, ShellCancellation cancellation, Run run)
            throws ShellExecutionException {
        final AtomicReference<RunningCommand> running = new AtomicReference<>();
        final AtomicBoolean lost = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long startedNanos = System.nanoTime();
        final String heartbeatFile = ShellWrapper.directory(binding.shellKey().directoryName()) + "/heartbeat";
        // Runs on the cancelling thread, or here and now when the signal is already tripped. The flag goes first: a
        // kill that finds no command yet is made up for below, once there is one.
        final ShellCancellation.Registration registration = cancellation.onCancel(() -> {
            cancelled.set(true);
            final RunningCommand command = running.get();
            if (command != null) {
                killQuietly(command);
            }
        });
        final ExecOutcome outcome;
        try {
            if (cancelled.get()) {
                throw cancelledBeforeStart();
            }
            final Heartbeat heartbeat = slot.activity().startHeartbeat(!run.foreground,
                    run.foreground ? () -> writeHeartbeat(slot.connection().files(), heartbeatFile) : null, () -> {
                        lost.set(true);
                        final RunningCommand command = running.get();
                        if (command != null) {
                            killQuietly(command);
                        }
                    });
            try {
                running.set(slot.connection().run(spec, OutputSink.DISCARD));
                if (lost.get() || cancelled.get()) {
                    // A provider whose kill throws must not skip the await: the run files are removed only after it.
                    killQuietly(running.get());
                }
                outcome = running.get().await(spec.timeout());
            } catch (InterruptedException e) {
                killQuietly(running.get());
                if (cancelled.get()) {
                    // Interrupted after a cancel — a shutdown that gave a slow or failed kill its grace: the command
                    // was still stopped on request. The run files are read before the flag is restored, so a files
                    // API that refuses an interrupted thread still yields the output so far.
                    final ShellCancelledException stopped = cancelled(slot.connection().files(), run, null);
                    Thread.currentThread().interrupt();
                    throw stopped;
                }
                Thread.currentThread().interrupt();
                throw new ShellExecutionException("the command was interrupted and killed", e, "", "", false,
                        notices(List.of(KILLED_NOTICE)));
            } finally {
                heartbeat.close();
            }
        } finally {
            registration.remove();
        }
        final Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
        if (cancelled.get()) {
            // Ahead of the lost and timed-out answers: a command its caller asked to stop was stopped, whatever else
            // happened in that instant. Only a trailer says otherwise — the command had already ended by itself.
            final Optional<ShellCommandResult> completed = completed(outcome, run, duration);
            if (completed.isPresent()) {
                return completed.get();
            }
            throw cancelled(slot.connection().files(), run, outcome);
        }
        if (lost.get()) {
            throw new ShellExecutionException(SandboxWorkspaceManager.LOST_MESSAGE, null, "", "", false,
                    pending.drain());
        }
        return classify(slot, outcome, run, duration);
    }

    private ShellCommandResult classify(ConnectedSlot slot, ExecOutcome outcome, Run run, Duration duration)
            throws ShellExecutionException {
        final SandboxFiles files = slot.connection().files();
        if (outcome.timedOut()) {
            throw timeout(files, run);
        }
        final Optional<ShellCommandResult> completed = completed(outcome, run, duration);
        if (completed.isPresent()) {
            return completed.get();
        }
        if (run.watched && files.stat(run.prefix + ".timedout").isPresent()) {
            throw timeout(files, run);
        }
        if (run.foreground && outcome.exitCode() == ShellWrapper.LOCK_BUSY_EXIT) {
            throw new ShellExecutionException(busyLockMessage(slot), null, "", "", false, pending.drain());
        }
        final byte[] stderr = outcome.stderr();
        throw new ShellExecutionException(
                "the sandbox shell wrapper failed (exit " + outcome.exitCode() + "): "
                        + new String(stderr, run.charset).strip(),
                null, new String(outcome.stdout(), run.charset), new String(stderr, run.charset),
                outcome.stdoutTruncated() || outcome.stderrTruncated(), pending.drain());
    }

    /**
     * The result of a run whose wrapper printed its trailer — the command ended by itself, whatever it exited with —
     * or empty when there is none: the wrapper was killed, or failed before it got there.
     */
    private Optional<ShellCommandResult> completed(ExecOutcome outcome, Run run, Duration duration) {
        final byte[] stderr = outcome.stderr();
        final int marker = lastIndexOf(stderr, ("\n" + run.nonce).getBytes(StandardCharsets.US_ASCII));
        if (marker < 0) {
            return Optional.empty();
        }
        final Matcher m = TRAILER
                .matcher(new String(stderr, marker, stderr.length - marker, StandardCharsets.US_ASCII));
        if (!m.find()) {
            return Optional.empty();
        }
        final List<String> extra = new ArrayList<>();
        cwdNotice(m.group(4), run).ifPresent(extra::add);
        final boolean truncated = Long.parseLong(m.group(2)) > run.max || Long.parseLong(m.group(3)) > run.max;
        return Optional
                .of(new ShellCommandResult(Integer.parseInt(m.group(1)), new String(outcome.stdout(), run.charset),
                        new String(stderr, 0, marker, run.charset), duration, truncated, notices(extra)));
    }

    /**
     * The notice for a working directory the wrapper could not restore: {@code 1} the saved one is gone and the root
     * was used, {@code 2} the root is gone too and {@code /workspace} was. A command given its own working directory
     * ran there; only the shell's own directory moved.
     */
    private Optional<String> cwdNotice(String flag, Run run) {
        final String fallback = "1".equals(flag) ? binding.root() : ShellWrapper.EXEC_DIRECTORY;
        final String lost = "1".equals(flag)
                ? "the saved working directory no longer exists"
                : "the working directory and " + binding.root() + " no longer exist";
        if (!"1".equals(flag) && !"2".equals(flag)) {
            return Optional.empty();
        }
        return Optional.of(lost + (run.ownWorkingDirectory
                ? "; the shell continues in " + fallback
                : "; the command ran in " + fallback));
    }

    private ShellTimeoutException timeout(SandboxFiles files, Run run) {
        final Capture stdout = readRunFile(files, run.prefix + ".out", run).orElse(Capture.EMPTY);
        final Capture stderr = readRunFile(files, run.prefix + ".err", run).orElse(Capture.EMPTY);
        return new ShellTimeoutException("the command timed out after " + run.timeout.toMillis() + "ms and was killed",
                run.timeout, stdout.content, stderr.content, stdout.truncated || stderr.truncated,
                notices(List.of(KILLED_NOTICE)));
    }

    /**
     * A command stopped through its cancellation signal, with what it had written. The run files hold that; when they
     * are already gone — the kill landed in the wrapper's last lines, after it printed the output and removed them —
     * the output is what the exec stream carried, or nothing when there is no outcome (the wait was interrupted).
     */
    private ShellCancelledException cancelled(SandboxFiles files, Run run, ExecOutcome outcome) {
        final Capture stdout = readRunFile(files, run.prefix + ".out", run).orElseGet(() -> outcome == null
                ? Capture.EMPTY
                : new Capture(new String(outcome.stdout(), run.charset), outcome.stdoutTruncated()));
        final Capture stderr = readRunFile(files, run.prefix + ".err", run).orElseGet(() -> outcome == null
                ? Capture.EMPTY
                : new Capture(new String(outcome.stderr(), run.charset), outcome.stderrTruncated()));
        return new ShellCancelledException(CANCELLED_MESSAGE, stdout.content, stderr.content,
                stdout.truncated || stderr.truncated, notices(List.of(KILLED_NOTICE)));
    }

    private ShellCancelledException cancelledBeforeStart() {
        return new ShellCancelledException(CANCELLED_BEFORE_START_MESSAGE, "", "", false, pending.drain());
    }

    /** The stop action of a cancellation listener: it runs on someone else's thread and must not throw. */
    private static void killQuietly(RunningCommand command) {
        try {
            command.kill();
        } catch (RuntimeException e) {
            log.warn("Could not kill a cancelled command: {}", e.toString());
        }
    }

    /**
     * The lock stayed busy for {@code shellLockWait} although this node holds no command on the shell: another node
     * is running one (§9). Implementation step 3 is single-node, so it reports rather than takes over.
     */
    private String busyLockMessage(ConnectedSlot slot) {
        String owner = "";
        try (InputStream in = slot.connection().files()
                .read(ShellWrapper.directory(binding.shellKey().directoryName()) + "/owner", 0, 512)) {
            owner = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read the shell owner: {}", e.getMessage());
        }
        return "the shell is in use by a command on another node" + (owner.isEmpty() ? "" : " (" + owner + ")")
                + "; retry once it finishes";
    }

    private List<String> notices(List<String> extra) {
        final List<String> all = new ArrayList<>(pending.drain());
        all.addAll(extra);
        return all;
    }

    private void writeHeartbeat(SandboxFiles files, String path) {
        final byte[] content = (settings.nodeId() + " " + clock.millis() + "\n").getBytes(StandardCharsets.UTF_8);
        files.write(path, new ByteArrayInputStream(content), content.length, WriteMode.CREATE_OR_REPLACE);
    }

    /** What a run file held, up to the capture cap, and whether it held more. */
    private static final class Capture {
        private static final Capture EMPTY = new Capture("", false);

        private final String content;
        private final boolean truncated;

        private Capture(String content, boolean truncated) {
            this.content = content;
            this.truncated = truncated;
        }
    }

    /**
     * @return the run file's content up to the capture cap — truncated, as the wrapper's {@code head -c} would, when
     *         the
     *         file holds more — or empty when it cannot be read (it is gone)
     */
    private static Optional<Capture> readRunFile(SandboxFiles files, String path, Run run) {
        // One byte past the cap tells a file that reached it from one that went over.
        final long limit = run.max == Long.MAX_VALUE ? run.max : run.max + 1;
        try (InputStream in = files.read(path, 0, limit)) {
            final byte[] bytes = in.readAllBytes();
            if (bytes.length > run.max) {
                return Optional.of(new Capture(new String(bytes, 0, (int) run.max, run.charset), true));
            }
            return Optional.of(new Capture(new String(bytes, run.charset), false));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Removes the run files of a run that ended without a trailer (a timeout or a cancellation, which read
     * {@code .out}/{@code .err} first, a wrapper failure, a provider error): on the normal path the wrapper removes
     * them itself.
     */
    private static void cleanUp(SandboxFiles files, String prefix) {
        for (String suffix : List.of(".out", ".err", ".in", ".cmd", ".timedout", ".cwd")) {
            try {
                if (files.stat(prefix + suffix).isPresent()) {
                    files.delete(prefix + suffix, false);
                }
            } catch (RuntimeException e) {
                log.debug("Could not remove run file {}{}: {}", prefix, suffix, e.getMessage());
            }
        }
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            if (regionMatches(haystack, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionMatches(byte[] haystack, int offset, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[offset + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    private static String randomHex(int bytes) {
        final byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** @return the binding's root: the directory a new shell starts in */
    @Override
    public String getWorkingDirectory() {
        return binding.root();
    }

    @Override
    public boolean supports(ShellFeature feature) {
        return feature == ShellFeature.PIPE || feature == ShellFeature.REDIRECTION
                || feature == ShellFeature.CANCELLATION;
    }

    /** Nothing to release: the environment is a view (§3.2). */
    @Override
    public void close() {
        // Nothing is held between calls.
    }
}
