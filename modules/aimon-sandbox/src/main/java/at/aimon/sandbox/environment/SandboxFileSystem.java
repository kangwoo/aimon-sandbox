package at.aimon.sandbox.environment;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * The file tools' view of a sandbox (docs/design/workspace-sandbox.md §7, §11.1). Three layers, so core's public
 * path-rule factory is reused unchanged:
 *
 * <ol>
 * <li>this class resolves a relative path against the binding's {@code root} and reports {@code root} as its working
 * directory — file tools resolve relative paths against {@code root}, never against the shell's cwd;</li>
 * <li>{@link VirtualFileSystems#withPathRules} anchored at {@code /} makes {@code /workspace/.aimon-staged} and
 * {@code /workspace/.aimon-shell} read-only ("Access denied") — a guard against edits by mistake, not a security
 * boundary: the shell can still write there;</li>
 * <li>{@link ProviderFileSystem} over the connection's files API.</li>
 * </ol>
 *
 * Every call connects first (lazy provisioning) and records activity; notices the connect produced are queued for the
 * next shell result. There is no other path restriction: nothing outside the sandbox is reachable, and inside it the
 * shell can do anything the file tools could.
 */
public final class SandboxFileSystem implements VirtualFileSystem {

    /** The read-only areas, relative to {@code /}. */
    static final List<PathRule> RULES = List.of(PathRule.readOnly("workspace/.aimon-staged"),
            PathRule.readOnly("workspace/.aimon-shell"));

    private final SandboxBinding binding;
    private final SandboxWorkspaceManager manager;
    private final PendingNotices pending;
    private final String root;

    SandboxFileSystem(SandboxBinding binding, SandboxWorkspaceManager manager, PendingNotices pending) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.pending = Objects.requireNonNull(pending, "pending must not be null");
        this.root = binding.root().endsWith("/") && binding.root().length() > 1
                ? binding.root().substring(0, binding.root().length() - 1)
                : binding.root();
    }

    private <T> T call(Function<VirtualFileSystem, T> operation) {
        final ConnectedSlot slot = connect();
        return ProviderCalls.guarded(slot, () -> operation.apply(view(slot)));
    }

    private ConnectedSlot connect() {
        final ConnectedSlot slot = manager.connect(binding);
        pending.addAll(slot.notices());
        slot.activity().record(false);
        return slot;
    }

    private static VirtualFileSystem view(ConnectedSlot slot) {
        return VirtualFileSystems.withPathRules(new ProviderFileSystem(slot.connection().files()), RULES);
    }

    private void run(Consumer<VirtualFileSystem> operation) {
        call(view -> {
            operation.accept(view);
            return null;
        });
    }

    /** A caller path as an absolute sandbox path: relative paths are relative to {@code root}. */
    String resolve(String path) {
        if (path == null) {
            return root;
        }
        final String p = path.replace('\\', '/');
        if (p.startsWith("/")) {
            return p;
        }
        return p.isEmpty() || ".".equals(p) ? root : root + "/" + p;
    }

    /**
     * A path the raw layer returned (relative to {@code /}) as the caller sees it: relative under root, else absolute.
     */
    String present(String rootRelative) {
        final String absolute = "/" + rootRelative;
        if (absolute.equals(root)) {
            return "";
        }
        return absolute.startsWith(root + "/") ? absolute.substring(root.length() + 1) : absolute;
    }

    private List<String> presentAll(List<String> paths) {
        return paths.stream().map(this::present).toList();
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        run(fs -> fs.write(resolve(path), content, contentLength));
    }

    @Override
    public InputStream read(String path) {
        return call(fs -> fs.read(resolve(path)));
    }

    @Override
    public void delete(String path) {
        run(fs -> fs.delete(resolve(path)));
    }

    @Override
    public boolean exists(String path) {
        return call(fs -> fs.exists(resolve(path)));
    }

    @Override
    public boolean isDirectory(String path) {
        return call(fs -> fs.isDirectory(resolve(path)));
    }

    @Override
    public FileMetadata getMetadata(String path) {
        final FileMetadata raw = call(fs -> fs.getMetadata(resolve(path)));
        return FileMetadata.builder().path(present(raw.getPath())).size(raw.getSize()).createdAt(raw.getCreatedAt())
                .modifiedAt(raw.getModifiedAt()).directory(raw.isDirectory()).etag(raw.getEtag().orElse(null)).build();
    }

    @Override
    public List<String> list(String directory) {
        return presentAll(call(fs -> fs.list(resolve(directory))));
    }

    @Override
    public List<String> listRecursive(String directory) {
        return presentAll(call(fs -> fs.listRecursive(resolve(directory))));
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        run(fs -> fs.copy(resolve(sourcePath), resolve(destinationPath), overwrite));
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        run(fs -> fs.move(resolve(sourcePath), resolve(destinationPath), overwrite));
    }

    /**
     * The bytes reach the sandbox when the stream is closed, so the close is a provider call too: it fails the way
     * every other call here does ("lost", "unavailable"), never with a raw provider exception.
     */
    @Override
    public OutputStream openOutputStream(String path) {
        final ConnectedSlot slot = connect();
        final OutputStream out = ProviderCalls.guarded(slot, () -> view(slot).openOutputStream(resolve(path)));
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                try {
                    ProviderCalls.guarded(slot, () -> {
                        try {
                            out.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            }
        };
    }

    @Override
    public InputStream openInputStream(String path) {
        return call(fs -> fs.openInputStream(resolve(path)));
    }

    @Override
    public void createDirectory(String path) {
        run(fs -> fs.createDirectory(resolve(path)));
    }

    @Override
    public void deleteRecursive(String path) {
        run(fs -> fs.deleteRecursive(resolve(path)));
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        return presentAll(call(fs -> fs.search(resolve(directory), pattern, maxResults)));
    }

    /** Not supported: sizing a remote tree would walk it file by file. */
    @Override
    public FileSystemUsage getUsageSummary() {
        throw new UnsupportedOperationException("usage summaries are not supported inside a sandbox");
    }

    /** @return the binding's root */
    @Override
    public String getWorkingDirectory() {
        return root;
    }

    @Override
    public void initialize() {
        // Lazy: the first call connects.
    }

    @Override
    public BackendStatus getStatus() {
        return BackendStatus.unknown(ProviderFileSystem.SANDBOX);
    }

    /** Nothing to release: the environment is a view (§3.2). */
    @Override
    public void close() {
        // Nothing is held between calls.
    }
}
