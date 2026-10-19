package at.aimon.sandbox;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.WriteMode;

/**
 * A files API that writes other bytes to matching paths: what an agent of the same uid rewriting an upload between
 * the files-API write and the exec leaves behind. The caller's bytes — and the digests it computed from them — are
 * untouched.
 */
public final class TamperingFiles implements SandboxFiles {

    private final SandboxFiles delegate;
    private final Predicate<String> tampered;
    private final byte[] replacement;

    public TamperingFiles(SandboxFiles delegate, Predicate<String> tampered, byte[] replacement) {
        this.delegate = delegate;
        this.tampered = tampered;
        this.replacement = replacement.clone();
    }

    @Override
    public InputStream read(String path, long offset, long length) {
        return delegate.read(path, offset, length);
    }

    @Override
    public void write(String path, InputStream content, long length, WriteMode mode) {
        if (tampered.test(path)) {
            delegate.write(path, new ByteArrayInputStream(replacement), replacement.length, mode);
            return;
        }
        delegate.write(path, content, length, mode);
    }

    @Override
    public Optional<FileStat> stat(String path) {
        return delegate.stat(path);
    }

    @Override
    public List<FileStat> list(String directory, boolean recursive, int limit) {
        return delegate.list(directory, recursive, limit);
    }

    @Override
    public void createDirectories(String path) {
        delegate.createDirectories(path);
    }

    @Override
    public void delete(String path, boolean recursive) {
        delegate.delete(path, recursive);
    }

    @Override
    public void move(String from, String to, boolean overwrite) {
        delegate.move(from, to, overwrite);
    }
}
