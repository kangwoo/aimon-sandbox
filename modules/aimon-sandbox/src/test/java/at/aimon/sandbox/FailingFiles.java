package at.aimon.sandbox;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.WriteMode;

/** A files API whose writes to matching paths fail as a full disk would: a filesystem error, not a provider one. */
public final class FailingFiles implements SandboxFiles {

    private final SandboxFiles delegate;
    private final Predicate<String> failing;

    public FailingFiles(SandboxFiles delegate, Predicate<String> failing) {
        this.delegate = delegate;
        this.failing = failing;
    }

    @Override
    public InputStream read(String path, long offset, long length) {
        return delegate.read(path, offset, length);
    }

    @Override
    public void write(String path, InputStream content, long length, WriteMode mode) {
        if (failing.test(path)) {
            throw new VirtualFileSystemException("cannot write " + path + ": No space left on device");
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
