package at.aimon.sandbox.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.environment.ContentQuery;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.ContentSearchResult;
import at.aimon.sandbox.binding.SandboxBinding;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.workspace.ConnectedSlot;
import at.aimon.sandbox.workspace.SandboxWorkspaceManager;

/**
 * {@code Grep} inside the sandbox: one {@code rg --json} exec (docs/design/workspace-sandbox.md §7) — the reason the
 * image contract requires ripgrep (§13.3). Matches core's local search: hidden files and ignored files are searched,
 * paths come back relative to the binding's root. A failure (rg missing, exit 2) throws, and core's {@code GrepTool}
 * then walks the filesystem instead; a multiline query is left to that walk too. The search path is normalised before
 * rg sees it, so results use the same spelling as a listing of that path; a {@linkplain ContentQuery#isCancelled()
 * cancelled} query kills rg and throws.
 */
public final class SandboxContentSearch implements ContentSearch {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final long MAX_OUTPUT = 32L * 1024 * 1024;
    private static final long CANCEL_POLL_MILLIS = 100;

    private final SandboxBinding binding;
    private final SandboxWorkspaceManager manager;
    private final SandboxFileSystem fileSystem;
    private final PendingNotices pending;

    SandboxContentSearch(SandboxBinding binding, SandboxWorkspaceManager manager, SandboxFileSystem fileSystem,
            PendingNotices pending) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        this.pending = Objects.requireNonNull(pending, "pending must not be null");
    }

    @Override
    public ContentSearchResult search(ContentQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isMultiline()) {
            throw new UnsupportedOperationException("multiline queries are answered by the filesystem walk");
        }
        final ConnectedSlot slot = manager.connect(binding);
        pending.addAll(slot.notices());
        slot.activity().record(false);
        final List<String> args = new ArrayList<>(
                List.of("rg", "--json", "--no-ignore", "--hidden", "-a", "--no-config", "--no-messages"));
        if (query.isCaseInsensitive()) {
            args.add("-i");
        }
        if (query.getBeforeContext() > 0) {
            args.add("-B");
            args.add(String.valueOf(query.getBeforeContext()));
        }
        if (query.getAfterContext() > 0) {
            args.add("-A");
            args.add(String.valueOf(query.getAfterContext()));
        }
        for (String extension : query.getExtensions()) {
            args.add("--iglob");
            args.add("*" + extension);
        }
        query.getGlob().ifPresent(glob -> {
            args.add("--glob");
            args.add(glob);
        });
        args.add("-e");
        args.add(query.getPattern());
        args.add("--");
        // Absolute and normalised: rg echoes the path as given, so "./sub//" or "../repo/sub" would otherwise come back
        // spelled differently from what the file tools list for the same files.
        args.add(ProviderFileSystem.absolute(fileSystem.resolve(query.getPath())));
        final StringBuilder command = new StringBuilder("cd -- ").append(ShellWrapper.quote(binding.root()))
                .append(" || exit 2\nexec");
        for (String arg : args) {
            command.append(' ').append(ShellWrapper.quote(arg));
        }
        final ExecSpec spec = ExecSpec.builder().command(command.toString()).environment(slot.profile().environment())
                .timeout(TIMEOUT).maxCaptureBytes(MAX_OUTPUT).build();
        final ExecOutcome outcome = await(
                ProviderCalls.guarded(slot, () -> slot.connection().run(spec, OutputSink.DISCARD)), query);
        if (outcome.exitCode() == 1) {
            return ContentSearchResult.of(List.of());
        }
        if (outcome.exitCode() != 0 || outcome.timedOut() || outcome.stdoutTruncated()) {
            throw new IllegalStateException("rg in the sandbox failed (exit " + outcome.exitCode() + "): "
                    + new String(outcome.stderr(), StandardCharsets.UTF_8).strip());
        }
        try {
            return parse(new String(outcome.stdout(), StandardCharsets.UTF_8), query);
        } catch (IOException e) {
            throw new IllegalStateException("could not read rg output: " + e.getMessage(), e);
        }
    }

    /**
     * Awaits rg while polling the query's cancellation, as core's local search does. {@link RunningCommand#await}
     * kills on its own timeout, so it runs on a helper thread and this one polls; a cancellation or an interrupt kills
     * rg — the caller that stops waiting must.
     */
    static ExecOutcome await(RunningCommand command, ContentQuery query) {
        final FutureTask<ExecOutcome> awaiting = new FutureTask<>(() -> command.await(TIMEOUT));
        final Thread waiter = new Thread(awaiting, "sandbox-rg-await");
        waiter.setDaemon(true);
        waiter.start();
        try {
            while (true) {
                try {
                    return awaiting.get(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (query.isCancelled()) {
                        command.kill();
                        throw new IllegalStateException("rg cancelled");
                    }
                }
            }
        } catch (InterruptedException e) {
            command.kill();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for rg", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("waiting for rg failed: " + e.getCause(), e.getCause());
        } finally {
            waiter.interrupt();
        }
    }

    private ContentSearchResult parse(String output, ContentQuery query) throws IOException {
        final Map<String, TreeMap<Integer, String>> lines = new LinkedHashMap<>();
        final Map<String, TreeSet<Integer>> matched = new LinkedHashMap<>();
        for (String raw : output.split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            final JsonNode message = MAPPER.readTree(raw);
            final String type = message.path("type").asText();
            if (!"match".equals(type) && !"context".equals(type)) {
                continue;
            }
            final JsonNode data = message.path("data");
            final JsonNode pathText = data.path("path").path("text");
            final JsonNode lineText = data.path("lines").path("text");
            if (pathText.isMissingNode() || lineText.isMissingNode()) {
                throw new IllegalStateException("rg reported a path or line that is not valid UTF-8");
            }
            final String path = present(pathText.asText());
            final int lineNumber = data.path("line_number").asInt();
            lines.computeIfAbsent(path, k -> new TreeMap<>()).put(lineNumber, stripLineEnd(lineText.asText()));
            if ("match".equals(type)) {
                matched.computeIfAbsent(path, k -> new TreeSet<>()).add(lineNumber);
            }
        }
        final List<ContentSearchResult.FileMatches> files = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> entry : matched.entrySet()) {
            final TreeMap<Integer, String> fileLines = lines.get(entry.getKey());
            final List<ContentSearchResult.Match> matches = new ArrayList<>();
            for (int lineNumber : entry.getValue()) {
                matches.add(ContentSearchResult.Match.of(lineNumber, fileLines.get(lineNumber),
                        context(fileLines, lineNumber - query.getBeforeContext(), lineNumber - 1),
                        context(fileLines, lineNumber + 1, lineNumber + query.getAfterContext())));
            }
            files.add(ContentSearchResult.FileMatches.of(entry.getKey(), matches));
        }
        // rg searches in parallel, so files arrive in no fixed order: sort, so one query always reads the same.
        files.sort(Comparator.comparing(ContentSearchResult.FileMatches::getPath));
        return ContentSearchResult.of(files);
    }

    private static List<String> context(TreeMap<Integer, String> lines, int from, int to) {
        final List<String> out = new ArrayList<>();
        for (int n = Math.max(1, from); n <= to; n++) {
            final String line = lines.get(n);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    /** An rg path as the file tools present it: relative under the root, absolute elsewhere. */
    private String present(String path) {
        final String p = path.startsWith("./") ? path.substring(2) : path;
        return p.startsWith("/") ? fileSystem.present(p.substring(1)) : p;
    }

    private static String stripLineEnd(String line) {
        if (line.endsWith("\r\n")) {
            return line.substring(0, line.length() - 2);
        }
        if (line.endsWith("\n") || line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }
}
