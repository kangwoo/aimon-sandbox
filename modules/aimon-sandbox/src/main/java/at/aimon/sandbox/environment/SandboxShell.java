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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCancellation;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
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
 * the exec's backstop. Core marks an operator's {@code hooks.json} command isolated
 * ({@link ExecutionOptions#isIsolated()}, along with {@code isHook()}) and sends it only to a shell that declares
 * {@link ShellFeature#ISOLATED_EXECUTION}. Such a command takes the hook's path but starts as a new shell would: in the
 * root, with the profile's exec environment and its own variables, never the session's cwd or exports, so the model's
 * {@code cd} and exports cannot steer an operator's policy hook. A timeout or interrupt kills the command's process
 * group; the state from before the command remains, and the result says so.
 *
 * <p>
 * <b>Hook and isolated runs keep nothing in the shell directory</b> (kangwoo/aimon-sandbox#33): their command, a small
 * stdin and their output travel in the exec itself — the script's argument and the exec's own streams, capped here —
 * and a larger stdin is uploaded, copied into a private directory and checked against a digest the script carries. A
 * timeout is the JVM's clock, not a marker file. Their exec is killed once the trailer has arrived
 * ({@link #TRAILER_KILL_GRACE} later), so a hook's {@code cmd &} ends with the hook instead of holding the call open
 * until the exec's backstop.
 *
 * <p>
 * <b>Run directories</b> ({@link ShellFeature#RUN_DIRECTORY}). The files of
 * {@link ExecutionOptions#getRunDirectory()} are placed for the one run in a fresh private directory,
 * {@code /workspace/.aimon-shell/.run.{hex}/d}, exported in the run directory's variable after every other variable:
 * inline in the script when they fit, otherwise uploaded through the files API, which the agent can write, as two
 * files whatever their number — their manifest and one blob of their base64 — and copied in. Either way the script
 * checks them against SHA-256 digests computed here and carried only in the script; a
 * mismatch throws {@link RunDirectoryIntegrityException} and the command does not run. Any other failure to place them
 * is a plain {@link ShellExecutionException}. The wrapper removes the directory, and so does {@code cleanUp} when the
 * wrapper was killed first. Narrowing, not a privilege boundary: a process of the same user can still reach the
 * directory while the command runs (docs/design/workspace-sandbox.md §12.1).
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
 *
 * <p>
 * <b>Streaming</b> ({@link ShellFeature#STREAMING}). A command run through
 * {@link #execute(ShellCommand, ExecutionOptions, ShellLineListener)} takes a hook's stream path whatever its flags —
 * no lock, no state save, the watchdog — and its stdout reaches the listener line by line as the provider delivers it.
 * This is what a background monitor runs on; it relies on the provider calling its {@link OutputSink} as output
 * arrives, which the SPI already asks of it (§6.2).
 *
 * <p>
 * <b>Notices.</b> A result or exception carries only what is true of its own command: {@link #KILLED_NOTICE} and a
 * working-directory fallback. What {@code connect} reported is queued in the environment's {@link PendingNotices}, and
 * core delivers it through {@code drainNotices()} after the tool call — after the PostTool hooks, so a hook's command
 * needs no special case (kangwoo/aimon-core#254).
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
    /** A stream run's trailer after its marker: no byte counts, and not anchored at the end (§9). */
    private static final Pattern STREAM_TRAILER = Pattern.compile("exit=(-?\\d+) cwd=(\\d)\\r?\\n");
    /** How long a stream run's exec lives after its trailer arrived, unless its wait returns first. */
    static final Duration TRAILER_KILL_GRACE = Duration.ofMillis(500);
    /** How often the trailer's kill looks again for a command that {@code run(...)} has not returned yet. */
    private static final Duration TRAILER_KILL_POLL = Duration.ofMillis(10);
    /** A run directory's ceiling: core's, which its builder enforces. */
    static final long MAX_RUN_DIRECTORY_BYTES = RunDirectory.MAX_TOTAL_BYTES;
    /** The exit code of a process group the watchdog killed: 128 + SIGKILL. */
    private static final int KILLED_EXIT = 137;
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
        return runCommand(command, options, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * A streamed command takes the stream path of a hook's command (§9): no shell lock and no state save, the
     * session's cwd and exports to start from, the watchdog for its timeout, and its output in the exec's own streams.
     * Its stdout reaches the listener as the provider delivers it ({@link StdoutLines}); its trailer is on stderr and
     * never reaches the listener. The result keeps stdout capped as any stream run's is.
     */
    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener)
            throws ShellExecutionException {
        return runCommand(command, options, Objects.requireNonNull(listener, "listener must not be null"));
    }

    private ShellCommandResult runCommand(ShellCommand command, ExecutionOptions options, ShellLineListener listener)
            throws ShellExecutionException {
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
        if (listener != null && !options.isBackground() && !options.isHook() && !options.isIsolated()) {
            // The stream path takes no shell lock and saves no state, so a foreground command's cd and export would be
            // lost without a word. Refused rather than silently weakened; a monitor streams background work.
            throw new ShellExecutionException("streaming is supported for background commands only"
                    + " (ExecutionOptions.background(true)): a streamed command does not keep cd or export");
        }
        final boolean streams = options.isHook() || options.isIsolated() || listener != null;
        if (streams && !ShellWrapper.embeddable(text)) {
            // A stream run's command travels as an argument, never as a run file the agent could rewrite.
            throw new ShellExecutionException((listener != null ? "a streamed command over " : "a hook command over ")
                    + ShellWrapper.INLINE_COMMAND_LIMIT / 1024 + " KiB is not supported");
        }
        if (options.getRunDirectory().isPresent()) {
            checkRunDirectory(options.getRunDirectory().get());
        }
        final boolean foreground = !options.isBackground() && !streams;
        // Before connect, like the checks above: a run that cannot be passed to the sandbox provisions nothing.
        final Plan plan = plan(text, options, foreground, listener);
        final ShellCancellation cancellation = options.getCancellation();
        if (cancellation.isCancelled()) {
            // Before connect: a command that is not to run provisions nothing and records no activity.
            throw cancelledBeforeStart();
        }
        final ConnectedSlot slot = pending.connect(manager, binding);
        slot.activity().record(false);
        if (!foreground) {
            // None may hold the session's shell: a background command outlives the call, and a hook or an isolated
            // command runs on behalf of the runtime between the model's own commands (§9, §12.1). None saves state;
            // only an isolated command does not read it either.
            return run(slot, plan, options);
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
                    null, "", "", false, List.of());
        }
        try (lease) {
            if (cancellation.isCancelled()) {
                // Tripped while provisioning or waiting for the lock: neither wait is aborted, the command is.
                throw cancelledBeforeStart();
            }
            return run(slot, plan, options);
        }
    }

    /**
     * The checks of a run directory, all of which core's builder already makes ({@link ShellFeature#RUN_DIRECTORY},
     * core #283): a valid variable, confined paths without a control character (so no newline, carriage return or NUL
     * reaches a {@code sha256sum} manifest line), and at most {@link RunDirectory#MAX_TOTAL_BYTES}. They are kept as a
     * backstop. File/directory overlaps, which core also refuses, are not checked here; what is left of placement (a
     * letter-case collision on a case-insensitive filesystem, a vanished upload) fails in the wrapper, as exit 70. All
     * are a bad argument, never the integrity failure.
     */
    private static void checkRunDirectory(RunDirectory run) throws ShellExecutionException {
        if (!ShellWrapper.invalidNames(List.of(run.getVariable())).isEmpty()) {
            throw new ShellExecutionException("invalid run directory variable name: " + run.getVariable());
        }
        for (String relPath : run.getPaths()) {
            if (relPath.indexOf('\n') >= 0 || relPath.indexOf('\r') >= 0) {
                throw new ShellExecutionException(
                        "a hook file name contains a newline, which the sandbox cannot place");
            }
            if (relPath.indexOf('\0') >= 0 || !SandboxStaging.confined(relPath)) {
                throw new ShellExecutionException(
                        "a hook file path is not a relative path inside the run directory: " + relPath);
            }
        }
        if (run.getTotalBytes() > MAX_RUN_DIRECTORY_BYTES) {
            throw new ShellExecutionException("the hook's files are too large to pass to the sandbox ("
                    + run.getTotalBytes() + " bytes, at most " + MAX_RUN_DIRECTORY_BYTES + ")");
        }
    }

    /**
     * Everything about a run that does not need the sandbox: its names, the script and what to upload before it.
     * Run files and a hook's stdin go into the script when it stays within {@link ShellWrapper#ARGUMENT_BUDGET} —
     * stdin first, then the run files past {@link ShellWrapper#INLINE_RUN_FILES_LIMIT} — and are uploaded otherwise;
     * a script still over the budget is refused. The digests are computed here, from the bytes core handed over, and
     * travel only in the script.
     */
    private Plan plan(String text, ExecutionOptions options, boolean foreground, ShellLineListener listener)
            throws ShellExecutionException {
        // A streamed command that is neither a hook's nor isolated runs as a hook's would: the stream path (§9).
        final Mode mode = foreground
                ? Mode.FOREGROUND
                : options.isIsolated()
                        ? Mode.ISOLATED
                        : options.isHook() || listener != null ? Mode.HOOK : Mode.BACKGROUND;
        final String directory = ShellWrapper.directory(binding.shellKey().directoryName());
        final String runPrefix = directory + "/run-" + randomHex(6);
        final String nonce = "AIMON-" + randomHex(16);
        final Optional<RunDirectory> runDirectory = options.getRunDirectory();
        // Not derived from runPrefix: an upload shows that in the shell directory before the exec starts.
        final String privateDirectory = mode.streams() || runDirectory.isPresent()
                ? ShellWrapper.PRIVATE_PREFIX + randomHex(16)
                : null;
        final Duration timeout = options.getTimeout();
        final long max = options.getMaxCaptureBytes() != null ? options.getMaxCaptureBytes() : DEFAULT_MAX_CAPTURE;
        final ShellWrapper.Invocation invocation = new ShellWrapper.Invocation().command(text).directory(directory)
                .runPrefix(runPrefix).root(binding.root()).nodeId(settings.nodeId()).nonce(nonce)
                .lockWait(settings.shellLockWait()).timeout(timeout).maxBytes(max)
                .redirectErrorStream(options.isRedirectErrorStream()).workingDirectory(options.getWorkingDirectory())
                .environment(options.getEnvironment()).privateDirectory(privateDirectory);
        final List<Upload> uploads = new ArrayList<>();
        if (!mode.streams()) {
            if (!ShellWrapper.embeddable(text)) {
                // Too large for one exec argument (MAX_ARG_STRLEN): the command goes in as a file instead.
                uploads.add(new Upload(runPrefix + ".cmd", text.getBytes(StandardCharsets.UTF_8), "the command"));
                invocation.commandUploaded(true);
            }
            if (options.getStdin() != null) {
                uploads.add(new Upload(runPrefix + ".in", options.getStdin().getBytes(options.getCharset()), "stdin"));
                invocation.stdinPath(runPrefix + ".in");
            }
        }
        final List<ShellWrapper.RunFile> runFiles = new ArrayList<>();
        final List<Upload> fileUploads = new ArrayList<>();
        boolean filesUploaded = false;
        if (runDirectory.isPresent()) {
            long inlineBytes = 0;
            for (String relPath : runDirectory.get().getPaths()) {
                final byte[] content = runDirectory.get().getContent(relPath);
                final String base64 = Base64.getEncoder().encodeToString(content);
                runFiles.add(new ShellWrapper.RunFile(relPath, base64, ShellWrapper.sha256Hex(content)));
                inlineBytes += ShellWrapper.quote(base64).length();
            }
            final String manifest = ShellWrapper.manifest(runFiles);
            // Uploaded, they are two files whatever their number: the manifest and one blob of their base64.
            fileUploads.add(new Upload(runPrefix + ".manifest", manifest.getBytes(StandardCharsets.UTF_8),
                    "the hook files' manifest"));
            fileUploads.add(new Upload(runPrefix + ".blob", ShellWrapper.blob(runFiles), "the hook files"));
            inlineBytes += ShellWrapper.scriptBytes(ShellWrapper.quote(manifest));
            filesUploaded = inlineBytes > ShellWrapper.INLINE_RUN_FILES_LIMIT;
            invocation.runDirectory(runDirectory.get().getVariable(), runFiles, filesUploaded);
        }
        final byte[] stdin = mode.streams() && options.getStdin() != null
                ? options.getStdin().getBytes(options.getCharset())
                : null;
        boolean stdinUploaded = false;
        if (stdin != null) {
            // base64, not quoted text: the exact bytes in the caller's charset, a NUL included.
            invocation.stdinInline(Base64.getEncoder().encodeToString(stdin));
        }
        String script = script(mode, invocation);
        if (stdin != null && ShellWrapper.scriptBytes(script) > ShellWrapper.ARGUMENT_BUDGET) {
            stdinUploaded = true;
            invocation.stdinInline(null).stdinUploaded(ShellWrapper.sha256Hex(stdin));
            script = script(mode, invocation);
        }
        if (runDirectory.isPresent() && !filesUploaded && !runFiles.isEmpty()
                && ShellWrapper.scriptBytes(script) > ShellWrapper.ARGUMENT_BUDGET) {
            filesUploaded = true;
            invocation.runDirectory(runDirectory.get().getVariable(), runFiles, true);
            script = script(mode, invocation);
        }
        if (privateDirectory != null && ShellWrapper.scriptBytes(script) > ShellWrapper.ARGUMENT_BUDGET) {
            throw new ShellExecutionException("the hook's command, environment and files are too large to pass to the"
                    + " sandbox (" + ShellWrapper.scriptBytes(script) + " bytes, at most "
                    + ShellWrapper.ARGUMENT_BUDGET + ")");
        }
        if (filesUploaded) {
            uploads.addAll(fileUploads);
        }
        if (stdinUploaded) {
            uploads.add(new Upload(runPrefix + ".in", stdin, "stdin"));
        }
        final Duration backstop = (timeout != null ? timeout : NO_TIMEOUT_BACKSTOP)
                .plus(foreground ? settings.shellLockWait() : Duration.ZERO).plus(BACKSTOP_SLACK);
        return new Plan(script, backstop, uploads, new Run(runPrefix, nonce, timeout != null ? timeout : backstop, max,
                mode, options, new Placement(privateDirectory, filesUploaded, stdinUploaded)), listener);
    }

    private static String script(Mode mode, ShellWrapper.Invocation invocation) {
        return switch (mode) {
            case FOREGROUND -> ShellWrapper.foreground(invocation);
            case HOOK -> ShellWrapper.hook(invocation);
            case ISOLATED -> ShellWrapper.isolated(invocation);
            case BACKGROUND -> ShellWrapper.background(invocation);
        };
    }

    /** A file to write through the files API before the exec. */
    private static final class Upload {
        private final String path;
        private final byte[] content;
        private final String what;

        private Upload(String path, byte[] content, String what) {
            this.path = path;
            this.content = content;
            this.what = what;
        }
    }

    /** A planned run: its script, the exec's backstop, the uploads to make first, and its identity. */
    private static final class Plan {
        private final String script;
        private final Duration backstop;
        private final List<Upload> uploads;
        private final Run run;
        /** Receives stdout line by line, or {@code null} for a run whose output is only in its result. */
        private final ShellLineListener listener;

        private Plan(String script, Duration backstop, List<Upload> uploads, Run run, ShellLineListener listener) {
            this.script = script;
            this.backstop = backstop;
            this.uploads = uploads;
            this.run = run;
            this.listener = listener;
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

    private ShellCommandResult run(ConnectedSlot slot, Plan plan, ExecutionOptions options)
            throws ShellExecutionException {
        final String directory = ShellWrapper.directory(binding.shellKey().directoryName());
        final SandboxFiles files = slot.connection().files();
        final boolean execKey = binding.shellKey().isExecution();
        if (execKey) {
            connections.execShellStarted(slot.ref(), directory);
        }
        boolean wrapperCleanedUp = false;
        try {
            for (Upload upload : plan.uploads) {
                upload(files, upload.path, upload.content, upload.what);
            }
            // Not binding.root(): a command may have removed it, and an exec server may refuse a missing directory
            // before the wrapper could fall back. The wrapper moves into the root itself.
            final ExecSpec spec = ExecSpec.builder().command(plan.script).workingDirectory(ShellWrapper.EXEC_DIRECTORY)
                    .environment(slot.profile().environment()).timeout(plan.backstop)
                    .maxCaptureBytes(captureBackstop(plan.run.max)).build();
            final ShellCommandResult result = await(slot, spec, options.getCancellation(), plan.run, plan.listener);
            // A result means the trailer was read, and the wrapper removed its run files before printing it.
            wrapperCleanedUp = true;
            return result;
        } catch (SandboxNotFoundException e) {
            slot.activity().markLost();
            throw new ShellExecutionException(SandboxWorkspaceManager.LOST_MESSAGE, e, "", "", false, List.of());
        } catch (SandboxProviderException e) {
            throw new SandboxUnavailableException("the sandbox cannot be reached: " + e.getMessage(), e);
        } finally {
            if (!wrapperCleanedUp) {
                cleanUp(files, plan.run);
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
        HOOK,
        /** An isolated command: a hook's command that does not read the session's state either. */
        ISOLATED;

        /**
         * Whether the run keeps nothing in the shell directory: its output is the exec's own streams, capped here, and
         * its timeout is read from the clock (kangwoo/aimon-sandbox#33).
         */
        boolean streams() {
            return this == HOOK || this == ISOLATED;
        }
    }

    /** One invocation's identity and limits. */
    private static final class Run {
        private final String prefix;
        private final String nonce;
        private final Duration timeout;
        /** The command has a timeout of its own, so a watchdog enforces it. */
        private final boolean commandTimeout;
        private final long max;
        private final Charset charset;
        private final Mode mode;
        private final boolean foreground;
        /**
         * The wrapper runs a watchdog when the command has a timeout. A model's command's watchdog marks the timeout
         * with {@code .timedout}; a stream run's writes nothing, and its timeout is read from the clock.
         */
        private final boolean watched;
        private final boolean ownWorkingDirectory;
        /** The run's private directory, or {@code null} for a model's command without a run directory. */
        private final String privateDirectory;
        private final boolean uploadedRunFiles;
        private final boolean uploadedStdin;

        private Run(String prefix, String nonce, Duration timeout, long max, Mode mode, ExecutionOptions options,
                Placement placement) {
            this.prefix = prefix;
            this.nonce = nonce;
            this.timeout = timeout;
            this.commandTimeout = options.getTimeout() != null;
            this.max = max;
            this.charset = options.getCharset();
            this.mode = mode;
            this.foreground = mode == Mode.FOREGROUND;
            this.watched = mode != Mode.BACKGROUND;
            this.ownWorkingDirectory = options.getWorkingDirectory() != null;
            this.privateDirectory = placement.privateDirectory;
            this.uploadedRunFiles = placement.uploadedRunFiles;
            this.uploadedStdin = placement.uploadedStdin;
        }
    }

    /** Where a run's private directory is, and what reaches it through an upload. */
    private static final class Placement {
        private final String privateDirectory;
        private final boolean uploadedRunFiles;
        private final boolean uploadedStdin;

        private Placement(String privateDirectory, boolean uploadedRunFiles, boolean uploadedStdin) {
            this.privateDirectory = privateDirectory;
            this.uploadedRunFiles = uploadedRunFiles;
            this.uploadedStdin = uploadedStdin;
        }
    }

    private ShellCommandResult await(ConnectedSlot slot, ExecSpec spec, ShellCancellation cancellation, Run run,
            ShellLineListener listener) throws ShellExecutionException {
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
        final CountDownLatch awaited = new CountDownLatch(1);
        // A stream run ends at its trailer: what it left running in the exec's process group goes with it (§9).
        final TrailerWatch watch = run.mode.streams()
                ? new TrailerWatch(run.nonce, () -> killAfterTrailer(running, awaited))
                : null;
        // Only a stream run streams: its stdout is the command's own, the trailer being on stderr.
        final StdoutLines lines = listener != null ? new StdoutLines(listener, run.charset) : null;
        final OutputSink sink = watch == null
                ? OutputSink.DISCARD
                : lines == null ? watch : (stream, bytes, offset, length) -> {
                    if (stream == OutputSink.Stream.STDOUT) {
                        lines.accept(bytes, offset, length);
                    } else {
                        watch.accept(stream, bytes, offset, length);
                    }
                };
        final ExecOutcome outcome;
        try {
            if (cancelled.get()) {
                throw cancelledBeforeStart();
            }
            // A streamed run with a timeout of its own is bounded by its watchdog, so it keeps the sandbox awake for as
            // long as it runs: past the background heartbeat limit the idle policy would reap the sandbox under a
            // monitor that is still watching.
            final boolean bounded = listener != null && run.commandTimeout;
            final Heartbeat heartbeat = slot.activity().startHeartbeat(!run.foreground && !bounded,
                    run.foreground ? () -> writeHeartbeat(slot.connection().files(), heartbeatFile) : null, () -> {
                        lost.set(true);
                        final RunningCommand command = running.get();
                        if (command != null) {
                            killQuietly(command);
                        }
                    });
            try {
                running.set(slot.connection().run(spec, sink));
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
                        List.of(KILLED_NOTICE));
            } finally {
                heartbeat.close();
            }
        } finally {
            awaited.countDown();
            registration.remove();
        }
        final Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
        if (lines != null) {
            // The exec is over: what it printed without a final newline is a line too.
            lines.finish();
        }
        if (cancelled.get()) {
            // Ahead of the lost and timed-out answers: a command its caller asked to stop was stopped, whatever else
            // happened in that instant. Only a trailer says otherwise — the command had already ended by itself.
            final Optional<ShellCommandResult> completed = completed(outcome, run, duration, watch);
            if (completed.isPresent()) {
                return completed.get();
            }
            throw cancelled(slot.connection().files(), run, outcome);
        }
        if (lost.get()) {
            throw new ShellExecutionException(SandboxWorkspaceManager.LOST_MESSAGE, null, "", "", false, List.of());
        }
        return run.mode.streams()
                ? classifyStream(outcome, run, duration, watch)
                : classify(slot, outcome, run, duration);
    }

    /**
     * Kills the exec of a stream run once its trailer arrived, off the provider's thread (an {@link OutputSink} must
     * not block, and a kill may be a remote call). It waits {@link #TRAILER_KILL_GRACE} first, or until the wait for
     * the exec returned: an exec server that forwards stdout and stderr apart can deliver the trailer before the
     * command's last stdout lines, which it already holds. When the trailer arrives before {@code run(...)} has
     * returned, the kill follows as soon as it returns. The kill is idempotent, so a finished exec costs nothing.
     */
    static void killAfterTrailer(AtomicReference<RunningCommand> running, CountDownLatch awaited) {
        final Thread killer = new Thread(() -> {
            try {
                boolean ended = awaited.await(TRAILER_KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                // The trailer came before run(...) returned: kill as soon as there is a command, not when the wait for
                // it ends — that wait is what a hook's `cmd &` holds open.
                while (!ended && running.get() == null) {
                    ended = awaited.await(TRAILER_KILL_POLL.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final RunningCommand command = running.get();
            if (command != null) {
                killQuietly(command);
            }
        }, "sandbox-hook-trailer-kill");
        killer.setDaemon(true);
        killer.start();
    }

    /**
     * A stream run: its trailer decides, then an integrity failure of the private directory's files, then a timeout
     * by the clock — the watchdog killed the group ({@code 137}) once the timeout had passed, which the exec's own
     * duration, measured from before the exec started, always exceeds — and otherwise the wrapper failed.
     */
    private ShellCommandResult classifyStream(ExecOutcome outcome, Run run, Duration duration, TrailerWatch watch)
            throws ShellExecutionException {
        final Optional<ShellCommandResult> completed = completed(outcome, run, duration, watch);
        if (completed.isPresent()) {
            return completed.get();
        }
        if (outcome.exitCode() == ShellWrapper.RUN_FILES_TAMPERED_EXIT) {
            throw tampered();
        }
        if (outcome.timedOut()
                || run.commandTimeout && outcome.exitCode() == KILLED_EXIT && duration.compareTo(run.timeout) >= 0) {
            throw timeout(null, run, outcome);
        }
        throw wrapperFailed(outcome, run);
    }

    private static RunDirectoryIntegrityException tampered() {
        // No content: the message reaches logs and core's hook result.
        return new RunDirectoryIntegrityException(
                "a hook file or the hook's input placed for the run does not match its digest");
    }

    private ShellCommandResult classify(ConnectedSlot slot, ExecOutcome outcome, Run run, Duration duration)
            throws ShellExecutionException {
        final SandboxFiles files = slot.connection().files();
        if (outcome.timedOut()) {
            throw timeout(files, run, outcome);
        }
        final Optional<ShellCommandResult> completed = completed(outcome, run, duration, null);
        if (completed.isPresent()) {
            return completed.get();
        }
        if (run.watched && files.stat(run.prefix + ".timedout").isPresent()) {
            throw timeout(files, run, outcome);
        }
        if (run.foreground && outcome.exitCode() == ShellWrapper.LOCK_BUSY_EXIT) {
            throw new ShellExecutionException(busyLockMessage(slot), null, "", "", false, List.of());
        }
        if (run.privateDirectory != null && outcome.exitCode() == ShellWrapper.RUN_FILES_TAMPERED_EXIT) {
            throw tampered();
        }
        throw wrapperFailed(outcome, run);
    }

    private static ShellExecutionException wrapperFailed(ExecOutcome outcome, Run run) {
        final byte[] stderr = outcome.stderr();
        return new ShellExecutionException(
                "the sandbox shell wrapper failed (exit " + outcome.exitCode() + "): "
                        + new String(stderr, run.charset).strip(),
                null, new String(outcome.stdout(), run.charset), new String(stderr, run.charset),
                outcome.stdoutTruncated() || outcome.stderrTruncated(), List.of());
    }

    /**
     * The result of a run whose wrapper printed its trailer — the command ended by itself, whatever it exited with —
     * or empty when there is none: the wrapper was killed, or failed before it got there.
     */
    private Optional<ShellCommandResult> completed(ExecOutcome outcome, Run run, Duration duration,
            TrailerWatch watch) {
        if (run.mode.streams()) {
            return completedStream(outcome, run, duration, watch);
        }
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
                        new String(stderr, 0, marker, run.charset), duration, truncated, extra));
    }

    /**
     * A stream run's result. The trailer is the one in the captured stderr — matched at its marker, not at the end: a
     * descendant may write after it before the kill lands, and that is dropped — or, when the provider's capture cap
     * cut it off, the one {@link TrailerWatch} parsed from the stream. The cap is applied here, on the bytes received
     * (after any line normalization, §6.1): stdout and the stderr before the trailer, each cut to {@code max}.
     */
    private Optional<ShellCommandResult> completedStream(ExecOutcome outcome, Run run, Duration duration,
            TrailerWatch watch) {
        final byte[] stderr = outcome.stderr();
        final byte[] marker = ("\n" + run.nonce + " ").getBytes(StandardCharsets.US_ASCII);
        final int at = lastIndexOf(stderr, marker);
        int exitCode = 0;
        String cwd = null;
        int stderrEnd = stderr.length;
        if (at >= 0) {
            final Matcher m = STREAM_TRAILER.matcher(new String(stderr, at + marker.length,
                    stderr.length - at - marker.length, StandardCharsets.ISO_8859_1));
            if (m.lookingAt()) {
                try {
                    exitCode = Integer.parseInt(m.group(1));
                    cwd = m.group(2);
                    stderrEnd = at;
                } catch (NumberFormatException e) {
                    cwd = null;
                }
            }
        }
        if (cwd == null) {
            final Optional<TrailerWatch.Trailer> watched = watch == null ? Optional.empty() : watch.trailer();
            if (watched.isEmpty()) {
                return Optional.empty();
            }
            exitCode = watched.get().exitCode();
            cwd = watched.get().cwd();
        }
        final List<String> extra = new ArrayList<>();
        cwdNotice(cwd, run).ifPresent(extra::add);
        final Capture out = capped(outcome.stdout(), outcome.stdout().length, outcome.stdoutTruncated(), run);
        final Capture err = capped(stderr, stderrEnd, outcome.stderrTruncated(), run);
        return Optional.of(new ShellCommandResult(exitCode, out.content, err.content, duration,
                out.truncated || err.truncated, extra));
    }

    /** The first {@code length} bytes of a stream, cut to the run's cap. */
    private static Capture capped(byte[] bytes, int length, boolean providerTruncated, Run run) {
        final int kept = (int) Math.min(length, run.max);
        return new Capture(new String(bytes, 0, kept, run.charset), providerTruncated || length > run.max);
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

    /**
     * The timeout of a run, with what it had written: the run files of a model's command; the exec's own streams for
     * a stream run, which has none ({@code files} unused).
     */
    private ShellTimeoutException timeout(SandboxFiles files, Run run, ExecOutcome outcome) {
        final Capture stdout = run.mode.streams()
                ? capped(outcome.stdout(), outcome.stdout().length, outcome.stdoutTruncated(), run)
                : readRunFile(files, run.prefix + ".out", run).orElse(Capture.EMPTY);
        final Capture stderr = run.mode.streams()
                ? capped(outcome.stderr(), outcome.stderr().length, outcome.stderrTruncated(), run)
                : readRunFile(files, run.prefix + ".err", run).orElse(Capture.EMPTY);
        return new ShellTimeoutException("the command timed out after " + run.timeout.toMillis() + "ms and was killed",
                run.timeout, stdout.content, stderr.content, stdout.truncated || stderr.truncated,
                List.of(KILLED_NOTICE));
    }

    /**
     * A command stopped through its cancellation signal, with what it had written. The run files hold that; when they
     * are already gone — the kill landed in the wrapper's last lines, after it printed the output and removed them —
     * or the run is a stream run, which has none, the output is what the exec stream carried, or nothing when there is
     * no outcome (the wait was interrupted).
     */
    private ShellCancelledException cancelled(SandboxFiles files, Run run, ExecOutcome outcome) {
        final Capture stdout = (run.mode.streams()
                ? Optional.<Capture>empty()
                : readRunFile(files, run.prefix + ".out", run))
                .orElseGet(() -> outcome == null
                        ? Capture.EMPTY
                        : streamCapture(outcome.stdout(), outcome.stdoutTruncated(), run));
        final Capture stderr = (run.mode.streams()
                ? Optional.<Capture>empty()
                : readRunFile(files, run.prefix + ".err", run))
                .orElseGet(() -> outcome == null
                        ? Capture.EMPTY
                        : streamCapture(outcome.stderr(), outcome.stderrTruncated(), run));
        return new ShellCancelledException(CANCELLED_MESSAGE, stdout.content, stderr.content,
                stdout.truncated || stderr.truncated, List.of(KILLED_NOTICE));
    }

    /**
     * What an exec stream carried: cut to the cap for a stream run, as the wrapper's {@code head -c} cut it otherwise.
     */
    private static Capture streamCapture(byte[] bytes, boolean providerTruncated, Run run) {
        return run.mode.streams()
                ? capped(bytes, bytes.length, providerTruncated, run)
                : new Capture(new String(bytes, run.charset), providerTruncated);
    }

    private static ShellCancelledException cancelledBeforeStart() {
        return new ShellCancelledException(CANCELLED_BEFORE_START_MESSAGE, "", "", false, List.of());
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
     * Removes what a run that ended without a trailer left (a timeout or a cancellation, which read
     * {@code .out}/{@code .err} first, a wrapper failure, a provider error, a watchdog kill that skipped the wrapper's
     * {@code EXIT} trap): on the normal path the wrapper removes it itself. A stream run has no run files, only its
     * uploads and private directory; a private directory that cannot be removed is logged, not the command's failure.
     */
    private static void cleanUp(SandboxFiles files, Run run) {
        if (!run.mode.streams()) {
            for (String suffix : List.of(".out", ".err", ".in", ".cmd", ".timedout", ".cwd")) {
                remove(files, run.prefix + suffix, false, false);
            }
        } else if (run.uploadedStdin) {
            remove(files, run.prefix + ".in", false, false);
        }
        if (run.uploadedRunFiles) {
            remove(files, run.prefix + ".manifest", false, false);
            remove(files, run.prefix + ".blob", false, false);
        }
        if (run.privateDirectory != null) {
            remove(files, run.privateDirectory, true, true);
        }
    }

    private static void remove(SandboxFiles files, String path, boolean recursive, boolean warn) {
        try {
            if (files.stat(path).isPresent()) {
                files.delete(path, recursive);
            }
        } catch (RuntimeException e) {
            if (warn) {
                log.warn("Could not remove the run's private directory {}: {}", path, e.getMessage());
            } else {
                log.debug("Could not remove run file {}: {}", path, e.getMessage());
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
                || feature == ShellFeature.CANCELLATION || feature == ShellFeature.ISOLATED_EXECUTION
                || feature == ShellFeature.RUN_DIRECTORY || feature == ShellFeature.STREAMING;
    }

    /** Nothing to release: the environment is a view (§3.2). */
    @Override
    public void close() {
        // Nothing is held between calls.
    }
}
