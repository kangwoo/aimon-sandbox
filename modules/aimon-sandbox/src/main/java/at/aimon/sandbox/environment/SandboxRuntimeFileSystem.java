package at.aimon.sandbox.environment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.WriteMode;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * core's {@link RuntimeFileSystem} over a sandbox (docs/design/ptc-shell-prerequisites.md S5): the framework's own file
 * access for the {@code ExecuteCode} shell engine's reply files. Framework-only — outside the file tools' path rules,
 * never handed to a model tool.
 *
 * <ul>
 * <li><b>Confined to {@code /workspace/**}</b>, the mount contract and the only tree the testkit's local provider
 * translates. A path outside it, relative, with a NUL, or with an empty, {@code .} or {@code ..} segment is refused
 * with
 * an {@link IOException} before any provider call — refused, never normalised. {@code /workspace} itself may be read
 * and created, not written or deleted. A binding root outside {@code /workspace} is therefore not served: the engine's
 * probe fails there and the engine stays unavailable.</li>
 * <li>{@link #writeNew} is the files API's {@link WriteMode#CREATE_NEW} — a temporary name in the target directory,
 * then link(2), which is atomic and refuses anything existing, a dangling link included. When the provider refuses
 * the write otherwise, one helper exec tells "something is there" from a plain failure.</li>
 * <li>{@link #read} and {@link #delete} are one in-sandbox helper exec each: the files API follows links (execd's
 * download opens the path), and whether its stat does is not known, so neither can say "this is a link".</li>
 * <li>{@link #createDirectories} is the files API's.</li>
 * </ul>
 *
 * Every call connects first, like the file tools' (lazy provisioning, activity, notices queued for core's
 * {@code drainNotices()}); constructing it, or asking an environment for it, provisions nothing. Every failure —
 * connecting included: a lost sandbox, a FAILED slot — is an {@link IOException}, never a runtime exception, since
 * core's engine catches only that around its clean-up. Thread-safe: it holds nothing between calls.
 *
 * <p>
 * <b>Known limit of {@link #read}:</b> the link check and the read are two steps in the helper. A process of the
 * sandbox's user that swaps the path between them makes the host read what that user can read anyway — the helper runs
 * as that user — so what is left is confidentiality within the sandbox, not an escape. Core's engine does not read
 * through this interface today.
 */
final class SandboxRuntimeFileSystem implements RuntimeFileSystem {

    /** The largest {@code maxBytes} {@link #read} accepts; above it the call is refused, never clamped. */
    static final int MAX_READ = 16 * 1024 * 1024;

    private static final String ROOT = "/workspace";
    /** Same as the files API's own helpers ({@code OpenSandboxFiles}). */
    private static final Duration HELPER_TIMEOUT = Duration.ofMinutes(2);
    private static final int LINK_EXIT = 40;
    private static final int NOT_REGULAR_EXIT = 41;
    private static final int UNREADABLE_EXIT = 42;
    private static final int REMAINS_EXIT = 43;
    private static final int MISSING_EXIT = 44;

    private final SandboxBinding binding;
    private final SandboxWorkspaceManager manager;
    private final PendingNotices pending;

    SandboxRuntimeFileSystem(SandboxBinding binding, SandboxWorkspaceManager manager, PendingNotices pending) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.pending = Objects.requireNonNull(pending, "pending must not be null");
    }

    @Override
    public void writeNew(String path, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes must not be null");
        check(path, false);
        call(slot -> {
            try {
                slot.connection().files().write(path, new ByteArrayInputStream(bytes), bytes.length,
                        WriteMode.CREATE_NEW);
            } catch (at.aimon.core.filesystem.exception.FileAlreadyExistsException e) {
                throw new FileAlreadyExistsException(path);
            } catch (VirtualFileSystemException e) {
                // Both providers refuse a directory at the target this way ("Is a directory"), and the testkit's
                // refuses a dangling link before link(2) could; the interface says anything existing is
                // FileAlreadyExistsException. Asked in the sandbox, since the files API's stat may follow a link.
                if (exists(slot.connection(), path)) {
                    throw new FileAlreadyExistsException(path);
                }
                throw new IOException("cannot write " + path + ": " + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The helper refuses a link and anything not a regular file, reads with {@code head -c} and prints base64 through
     * {@code /usr/bin/base64} — by absolute path: the seed requires it to be GNU, which wraps at 76 columns, so no
     * output line comes near a provider's line limit. The output is ASCII, so a provider's line normalisation cannot
     * corrupt it; the testkit's local provider also rewrites its host directory in output, which a base64 line would
     * have to contain verbatim. The output is {@link ExecSpec#transientOutput() transient}: it is file content.
     */
    @Override
    public byte[] read(String path, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative, got " + maxBytes);
        }
        check(path, true);
        if (maxBytes > MAX_READ) {
            throw new IOException("cannot read more than " + MAX_READ + " bytes at once, asked for " + maxBytes);
        }
        // pipefail: an unreadable file (EACCES, EIO) is exit 42, not an empty result. `head -c 0` is an error on BSD
        // (the testkit runs this on the host), so a zero-byte read only checks that one byte could be read.
        final String script = "set -o pipefail\nset -- " + ShellWrapper.quote(path) + " " + maxBytes + "\n"
                + "[ -L \"$1\" ] && exit " + LINK_EXIT + "\n[ -e \"$1\" ] || exit " + MISSING_EXIT + "\n"
                + "[ -f \"$1\" ] || exit " + NOT_REGULAR_EXIT + "\n"
                + (maxBytes == 0
                        ? "head -c 1 -- \"$1\" >/dev/null || exit " + UNREADABLE_EXIT + "\n"
                        : "head -c \"$2\" -- \"$1\" | /usr/bin/base64 || exit " + UNREADABLE_EXIT + "\n");
        final long encoded = 4L * ((maxBytes + 2L) / 3);
        final ExecOutcome outcome = call(
                slot -> helper(slot.connection(), script, encoded + (encoded + 56) / 57 + 1024, true));
        switch (outcome.exitCode()) {
            case 0 :
                break;
            case LINK_EXIT :
                throw new IOException(path + " is a symbolic link, which is not followed");
            case NOT_REGULAR_EXIT :
                throw new IOException(path + " is not a regular file");
            case MISSING_EXIT :
                throw new NoSuchFileException(path);
            default :
                throw failed("read", path, outcome);
        }
        if (outcome.stdoutTruncated()) {
            throw new IOException("the content of " + path + " did not fit the capture");
        }
        try {
            return Base64.getMimeDecoder().decode(outcome.stdout());
        } catch (IllegalArgumentException e) {
            throw new IOException("the content of " + path + " came back garbled: " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * {@code rm -rf --} never follows a final-component link — it removes the link, dangling or not — and handles a
     * file, a tree and nothing alike; the check after it makes "something remains" an {@link IOException}.
     */
    @Override
    public void delete(String path) throws IOException {
        check(path, false);
        final String script = "set -- " + ShellWrapper.quote(path) + "\nrm -rf -- \"$1\"\n"
                + "if [ -e \"$1\" ] || [ -L \"$1\" ]; then exit " + REMAINS_EXIT + "; fi\nexit 0\n";
        final ExecOutcome outcome = call(slot -> helper(slot.connection(), script, 8 * 1024, false));
        if (outcome.exitCode() != 0) {
            throw failed("delete", path, outcome);
        }
    }

    /** Whether anything — a link included, dangling or not — is at {@code path}, without following it. */
    private static boolean exists(SandboxConnection connection, String path) throws IOException {
        final ExecOutcome outcome = helper(connection,
                "set -- " + ShellWrapper.quote(path) + "\nif [ -e \"$1\" ] || [ -L \"$1\" ]; then exit 0; fi\nexit 1\n",
                8 * 1024, false);
        return outcome.exitCode() == 0;
    }

    @Override
    public void createDirectories(String path) throws IOException {
        check(path, true);
        call(slot -> {
            try {
                slot.connection().files().createDirectories(path);
            } catch (VirtualFileSystemException e) {
                // A FileAlreadyExistsException here is something other than a directory in the way: a plain
                // IOException, as the interface says.
                throw new IOException("cannot create the directory " + path + ": " + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * Refuses a path this file system does not serve (class doc).
     *
     * @param rootAllowed
     *            whether {@code /workspace} itself is acceptable
     */
    static void check(String path, boolean rootAllowed) throws IOException {
        if (path == null) {
            throw new IOException("a null path is not served by the sandbox runtime file system");
        }
        final boolean root = ROOT.equals(path);
        if (path.indexOf('\0') >= 0 || !(path.startsWith(ROOT + "/") || root && rootAllowed)) {
            throw refused(path);
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw refused(path);
            }
        }
    }

    private static IOException refused(String path) {
        return new IOException(path.replace('\0', '?') + " is not served by the sandbox runtime file system "
                + "(only absolute paths under " + ROOT + ", without . or .. segments)");
    }

    /** One provider operation that may fail with an {@link IOException}. */
    @FunctionalInterface
    private interface Operation<T> {
        T run(ConnectedSlot slot) throws IOException;
    }

    /**
     * Connects and runs one operation; every failure comes out as an {@link IOException} — a lost sandbox is marked
     * so, as for the file tools ({@link ProviderCalls}).
     */
    private <T> T call(Operation<T> operation) throws IOException {
        final ConnectedSlot slot;
        try {
            slot = pending.connect(manager, binding);
            slot.activity().record(false);
        } catch (RuntimeException e) {
            throw new IOException("the sandbox is not available: " + e.getMessage(), e);
        }
        try {
            return ProviderCalls.guarded(slot, () -> {
                try {
                    return operation.run(slot);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static ExecOutcome helper(SandboxConnection connection, String script, long maxCapture,
            boolean transientOutput) throws IOException {
        final ExecSpec spec = ExecSpec.builder().command(script).workingDirectory("/").timeout(HELPER_TIMEOUT)
                .maxCaptureBytes(maxCapture).transientOutput(transientOutput).build();
        final RunningCommand command = connection.run(spec, OutputSink.DISCARD);
        final ExecOutcome outcome;
        try {
            outcome = command.await(HELPER_TIMEOUT);
        } catch (InterruptedException e) {
            command.kill();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the sandbox");
        }
        if (outcome.timedOut()) {
            throw new IOException("the sandbox did not answer within " + HELPER_TIMEOUT.toSeconds() + "s");
        }
        return outcome;
    }

    private static IOException failed(String what, String path, ExecOutcome outcome) {
        final String stderr = new String(outcome.stderr(), StandardCharsets.UTF_8).strip();
        return new IOException("cannot " + what + " " + path + " (exit " + outcome.exitCode() + ")"
                + (stderr.isEmpty() ? "" : ": " + stderr));
    }
}
