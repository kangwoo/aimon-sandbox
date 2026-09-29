package at.aimon.sandbox.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * A connection to one {@link LocalProcessSandboxProvider} sandbox, and the path translation that makes a directory
 * look like a sandbox: {@code /workspace} ↔ {@code {dir}/workspace} in commands and output, and every absolute sandbox
 * path ↔ {@code {dir}/path} in the files API.
 */
final class LocalConnection implements SandboxConnection {

    /** {@code /workspace} where a path starts: not preceded or followed by a path character. */
    private static final Pattern WORKSPACE = Pattern.compile("(?<![\\w.\\-/~])/workspace(?![\\w.\\-])");
    private static final Pattern SHA256SUM = Pattern.compile("/usr/bin/sha256sum(?![\\w.\\-])");

    private final LocalProcessSandboxProvider.LocalSandbox sandbox;
    private final Path shims;
    private final LocalSandboxFiles files;

    LocalConnection(LocalProcessSandboxProvider.LocalSandbox sandbox, Path shims) {
        this.sandbox = sandbox;
        this.shims = shims;
        this.files = new LocalSandboxFiles(this);
    }

    /** A sandbox path as a host path under the sandbox directory. */
    Path toHost(String sandboxPath) {
        sandbox.requireLive();
        if (sandboxPath == null || !sandboxPath.startsWith("/")) {
            throw new InvalidPathException(String.valueOf(sandboxPath), "sandbox paths must be absolute");
        }
        final Path host = sandbox.dir.resolve(sandboxPath.substring(1)).normalize();
        if (!host.startsWith(sandbox.dir)) {
            throw new InvalidPathException(sandboxPath, "escapes the sandbox");
        }
        // A command can plant a symbolic link (`ln -s /etc /workspace/x`): the deepest existing ancestor must still
        // resolve inside the sandbox, or the files API would read and delete host files through it.
        Path existing = host;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        try {
            if (existing != null && !existing.toRealPath().startsWith(sandbox.dir)) {
                throw new InvalidPathException(sandboxPath, "resolves outside the sandbox through a link");
            }
        } catch (IOException e) {
            throw new InvalidPathException(sandboxPath, "cannot be resolved: " + e.getMessage());
        }
        return host;
    }

    /** A host path under the sandbox directory as a sandbox path. */
    String toSandbox(Path host) {
        final Path relative = sandbox.dir.relativize(host);
        return "/" + relative.toString().replace('\\', '/');
    }

    String translateCommand(String command) {
        final String workspace = Matcher.quoteReplacement(sandbox.dir.resolve("workspace").toString());
        final String sha = Matcher.quoteReplacement(shims.resolve("sha256sum").toString());
        return SHA256SUM.matcher(WORKSPACE.matcher(command).replaceAll(workspace)).replaceAll(sha);
    }

    String translateOutput(String output) {
        return output.replace(sandbox.dir.resolve("workspace").toString(), "/workspace");
    }

    @Override
    public RunningCommand run(ExecSpec spec, OutputSink sink) {
        sandbox.requireLive();
        final Path cwd = spec.workingDirectory().map(this::toHost).orElse(sandbox.dir.resolve("workspace"));
        if (!Files.isDirectory(cwd)) {
            // As a real exec server may: a command is not silently moved to another directory.
            throw new SandboxProviderException(
                    "cannot run in " + spec.workingDirectory().orElse("/workspace") + ": no such directory");
        }
        final ProcessBuilder builder = new ProcessBuilder(List.of("/usr/bin/perl", "-e",
                "setpgrp(0,0); exec @ARGV or die \"exec: $!\"", "/bin/bash", "-c", translateCommand(spec.command())))
                .directory(cwd.toFile());
        final Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", shims + ":" + LocalProcessSandboxProvider.BASE_PATH);
        env.put("HOME", sandbox.dir.resolve("workspace").toString());
        env.put("TMPDIR", sandbox.dir.resolve("tmp").toString());
        env.put("LANG", "en_US.UTF-8");
        sandbox.environment.forEach(env::put);
        spec.environment().forEach((key, value) -> env.put(key, translateCommand(value)));
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxProviderException("cannot start a local command: " + e.getMessage());
        }
        sandbox.processGroups.put(process.pid(), process.info().startInstant());
        final LocalRunningCommand command = new LocalRunningCommand(process, spec, sink, this, sandbox.running);
        sandbox.running.add(command);
        return command;
    }

    @Override
    public SandboxFiles files() {
        return files;
    }

    @Override
    public void close() {
        // Nothing node-local to release.
    }
}
