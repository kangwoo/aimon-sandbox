package at.aimon.sandbox.environment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.SandboxConnectionCache;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.WriteMode;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * Content-addressed staging into the sandbox: {@code /workspace/.aimon-staged/{name}/{contentKey}/}
 * (docs/design/workspace-sandbox.md §7, §11.1). Core's {@code LocalStaging} is the reference; every check it makes is
 * made here, so {@code stage()} has one contract on either environment — concurrency included.
 *
 * <ol>
 * <li>Shape checks before anything touches the sandbox: a single-segment name, a 16-hex key, confined relative file
 * paths, the size limit. A resource that already lives in this environment is returned uncopied.</li>
 * <li>Under a node-local lock per {@code (sandbox, target)}, verify an existing copy <i>inside the sandbox</i>: one
 * stateless exec (fixed {@code PATH}, {@code /usr/bin/sha256sum}) recomputes the content key over the expected file
 * list with core's algorithm and lists the files; the copy is reused only when the key matches and the file set is
 * exactly the resource's plus the marker. A marker alone proves nothing — the shell can write here.</li>
 * <li>Otherwise copy into {@code .tmp-{key}-{nonce}}, re-hashing the bytes as they are copied (a skill changed on
 * disk after it was loaded is refused, as core refuses it), write the marker last, and move the directory into place.
 * The target only ever appears complete.</li>
 * </ol>
 */
final class SandboxStaging {

    static final String STAGING_ROOT = "/workspace/.aimon-staged";
    static final String MARKER = ".staged";

    private static final Logger log = LoggerFactory.getLogger(SandboxStaging.class);
    private static final Pattern CONTENT_KEY = Pattern
            .compile("[0-9a-f]{" + StagedResource.CONTENT_KEY_HEX_LENGTH + "}");
    private static final Duration VERIFY_TIMEOUT = Duration.ofMinutes(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SandboxBinding binding;
    private final SandboxWorkspaceManager manager;
    private final SandboxConnectionCache connections;
    private final PendingNotices pending;
    private final SandboxFileSystem ownFileSystem;
    private final long maxStagedBytes;

    SandboxStaging(SandboxBinding binding, SandboxWorkspaceManager manager, SandboxConnectionCache connections,
            PendingNotices pending, SandboxFileSystem ownFileSystem) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.pending = Objects.requireNonNull(pending, "pending must not be null");
        this.ownFileSystem = Objects.requireNonNull(ownFileSystem, "ownFileSystem must not be null");
        this.maxStagedBytes = LocalExecutionEnvironmentProvider.DEFAULT_MAX_STAGED_BYTES;
    }

    String stage(StagedResource resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        if (resource.getSourceFileSystem() == ownFileSystem && ownFileSystem.exists(resource.getSourceDir())) {
            return ownFileSystem.resolve(resource.getSourceDir());
        }
        checkShape(resource);
        final ConnectedSlot slot = manager.connect(binding);
        pending.addAll(slot.notices());
        slot.activity().record(false);
        final String target = STAGING_ROOT + "/" + resource.getName() + "/" + resource.getContentKey();
        try (SandboxConnectionCache.Lease lease = connections.lockStaging(slot.ref(), target)) {
            if (ProviderCalls.guarded(slot, () -> verified(slot, target, resource))) {
                return target;
            }
            ProviderCalls.guarded(slot, () -> install(slot, resource, copy(slot, resource), target));
            log.debug("Staged {} ({} files) to {}", resource.getName(), resource.getFiles().size(), target);
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StagingException("Cannot stage '" + resource.getName() + "': interrupted", e);
        }
    }

    private void checkShape(StagedResource resource) {
        final String name = resource.getName();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new StagingException("Cannot stage '" + name + "': the name is not a single path segment");
        }
        if (!CONTENT_KEY.matcher(resource.getContentKey()).matches()) {
            throw new StagingException("Cannot stage '" + name + "': the content key '" + resource.getContentKey()
                    + "' is not 16 lowercase hex characters");
        }
        for (String relPath : resource.getFiles()) {
            if (!confined(relPath)) {
                throw new StagingException("Cannot stage '" + name + "': the file path '" + relPath
                        + "' is not a relative path inside the resource");
            }
        }
        if (resource.getTotalBytes() > maxStagedBytes) {
            throw new StagingException(
                    "Cannot stage '" + name + "': " + resource.getTotalBytes() + " bytes exceeds the staging limit of "
                            + maxStagedBytes + " bytes; exclude large files with " + StagedResource.STAGE_IGNORE_FILE);
        }
    }

    private static boolean confined(String relPath) {
        if (relPath.isEmpty() || relPath.startsWith("/") || relPath.contains("\\")) {
            return false;
        }
        for (String segment : relPath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the copy at {@code target} is complete and untouched: its content key, recomputed in the sandbox over the
     * expected files, matches, and it holds exactly those files plus the marker.
     */
    boolean verified(ConnectedSlot slot, String target, StagedResource resource) {
        final SandboxFiles files = slot.connection().files();
        if (files.stat(target + "/" + MARKER).isEmpty()) {
            return false;
        }
        final StringBuilder script = new StringBuilder("cd -- ").append(ShellWrapper.quote(target))
                .append(" || exit 3\nh=$( (for f in");
        for (String relPath : resource.getFiles()) {
            script.append(' ').append(ShellWrapper.quote(relPath));
        }
        script.append("; do printf '%s\\0' \"$f\"; cat -- \"$f\" 2>/dev/null; printf '\\0'; done) "
                + "| /usr/bin/sha256sum) || exit 4\nprintf '%s\\n' \"${h%% *}\"\nfind . ! -type d -print0\n");
        final ExecOutcome outcome;
        try {
            outcome = slot.connection()
                    .run(ExecSpec.builder().command(script.toString()).environment(Map.of("PATH", "/usr/bin:/bin"))
                            .timeout(VERIFY_TIMEOUT).maxCaptureBytes(4L * 1024 * 1024).build(), OutputSink.DISCARD)
                    .await(VERIFY_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StagingException("Cannot stage '" + resource.getName() + "': interrupted", e);
        }
        if (outcome.exitCode() != 0 || outcome.stdoutTruncated()) {
            log.debug("Staging verification of {} failed (exit {})", target, outcome.exitCode());
            return false;
        }
        final String out = new String(outcome.stdout(), StandardCharsets.UTF_8);
        final int newline = out.indexOf('\n');
        if (newline < StagedResource.CONTENT_KEY_HEX_LENGTH) {
            return false;
        }
        final String key = out.substring(0, StagedResource.CONTENT_KEY_HEX_LENGTH);
        final Set<String> listed = new HashSet<>();
        for (String entry : out.substring(newline + 1).split("\0")) {
            if (!entry.isEmpty()) {
                listed.add(entry.startsWith("./") ? entry.substring(2) : entry);
            }
        }
        final Set<String> expected = new HashSet<>(resource.getFiles());
        expected.add(MARKER);
        final boolean valid = key.equals(resource.getContentKey()) && listed.equals(expected);
        if (!valid) {
            log.warn("The staged copy at {} does not match its content key or file list; it is replaced", target);
        }
        return valid;
    }

    /** Copies the resource into a fresh temp directory next to the target and returns it. */
    private String copy(ConnectedSlot slot, StagedResource resource) {
        final SandboxFiles files = slot.connection().files();
        final String parent = STAGING_ROOT + "/" + resource.getName();
        final String prefix = ".tmp-" + resource.getContentKey() + "-";
        removeLeftovers(files, parent, prefix);
        final byte[] nonce = new byte[6];
        RANDOM.nextBytes(nonce);
        final String tmp = parent + "/" + prefix + HexFormat.of().formatHex(nonce);
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        for (String relPath : resource.getFiles()) {
            final byte[] bytes;
            try (InputStream in = resource.getSourceFileSystem().read(resource.sourcePath(relPath))) {
                bytes = in.readAllBytes();
            } catch (IOException | RuntimeException e) {
                discard(files, tmp);
                throw new StagingException("Cannot stage '" + resource.getName() + "': " + relPath
                        + " could not be read: " + e.getMessage(), e);
            }
            hasher.add(relPath, bytes);
            files.write(tmp + "/" + relPath, new ByteArrayInputStream(bytes), bytes.length,
                    WriteMode.CREATE_OR_REPLACE);
        }
        if (!hasher.build().equals(resource.getContentKey())) {
            discard(files, tmp);
            throw new StagingException("Skill '" + resource.getName() + "' changed on disk after it was loaded, so its"
                    + " files no longer match the version this session uses. Restart the application, or reload the"
                    + " skill registry, to pick up the change.");
        }
        final byte[] marker = resource.getContentKey().getBytes(StandardCharsets.UTF_8);
        files.write(tmp + "/" + MARKER, new ByteArrayInputStream(marker), marker.length, WriteMode.CREATE_OR_REPLACE);
        return tmp;
    }

    private void install(ConnectedSlot slot, StagedResource resource, String tmp, String target) {
        final SandboxFiles files = slot.connection().files();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (files.stat(target).isPresent()) {
                files.delete(target, true);
            }
            try {
                files.move(tmp, target, false);
                return;
            } catch (FileAlreadyExistsException e) {
                // Only another node can have put it there between the delete and the move (implementation step 6).
                if (verified(slot, target, resource)) {
                    discard(files, tmp);
                    return;
                }
            }
        }
        discard(files, tmp);
        throw new StagingException("Cannot stage '" + resource.getName() + "': the target kept being replaced");
    }

    private static void removeLeftovers(SandboxFiles files, String parent, String prefix) {
        final List<FileStat> entries;
        try {
            if (files.stat(parent).isEmpty()) {
                return;
            }
            entries = files.list(parent, false, 10_000);
        } catch (FileNotFoundException e) {
            return;
        }
        for (FileStat entry : entries) {
            final String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
            if (name.startsWith(prefix)) {
                discard(files, entry.path());
            }
        }
    }

    private static void discard(SandboxFiles files, String path) {
        try {
            if (files.stat(path).isPresent()) {
                files.delete(path, true);
            }
        } catch (RuntimeException e) {
            log.warn("Could not remove staging directory {}: {}", path, e.getMessage());
        }
    }
}
