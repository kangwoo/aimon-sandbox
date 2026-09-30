package at.aimon.sandbox.opensandbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.SandboxFiles;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.provider.WriteMode;

/**
 * The files API over execd (docs/design/opensandbox-spike.md §3–4, B §e), with the exception semantics of the local
 * provider, which the shared contract tests pin:
 * <ul>
 * <li>{@code write} uploads to a temporary name in the target's directory, then renames with {@code mv -f}
 * ({@code CREATE_OR_REPLACE}) or links with {@code ln} ({@code CREATE_NEW}: link(2) refuses an existing target
 * atomically) through {@code /command} — execd's own upload truncates in place, and its move never overwrites;</li>
 * <li>{@code read} stats first: a download of a directory drops the connection;</li>
 * <li>{@code stat} has no etag: execd's nanosecond {@code modified_at} carries the change-detection contract;</li>
 * <li>{@code move} without overwrite is execd's {@code /files/mv}, which checks "exists" and renames in two steps — not
 * atomic, and documented so.</li>
 * </ul>
 */
final class OpenSandboxFiles implements SandboxFiles {

    /** execd's default mode is 755; files written here are not executables. */
    static final int FILE_MODE = 644;
    static final int DIRECTORY_MODE = 755;
    /** How deep a recursive listing goes; execd has no "unlimited". */
    static final int RECURSIVE_DEPTH = 64;
    private static final Duration HELPER_TIMEOUT = Duration.ofMinutes(2);

    private final OpenSandboxConnection connection;
    private final ExecdClient execd;
    private final Retry retry;

    OpenSandboxFiles(OpenSandboxConnection connection, ExecdClient execd, Retry retry) {
        this.connection = connection;
        this.execd = execd;
        this.retry = retry;
    }

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String shell(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public InputStream read(String path, long offset, long length) {
        final FileStat stat = stat(path).orElseThrow(() -> new FileNotFoundException(path));
        if (stat.directory()) {
            throw new VirtualFileSystemException("Is a directory: " + path);
        }
        if (length == 0 || offset >= stat.size()) {
            return new ByteArrayInputStream(new byte[0]);
        }
        final String range = "bytes=" + Math.max(0, offset) + "-" + (length < 0 ? "" : offset + length - 1);
        final HttpResponse<byte[]> response = retry
                .call(() -> execd.send(ep -> ep.request("/files/download?path=" + q(path)).header("Range", range).GET(),
                        "download file"));
        if (response.statusCode() == 416) {
            return new ByteArrayInputStream(new byte[0]);
        }
        try {
            HttpErrors.check(response, "download " + path);
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> new FileNotFoundException(path));
        } catch (SandboxProviderException e) {
            throw vfs("cannot read " + path, e);
        }
        return new ByteArrayInputStream(response.body());
    }

    @Override
    public void write(String path, InputStream content, long length, WriteMode mode) {
        final byte[] bytes = readContent(content, length, path);
        final int slash = path.lastIndexOf('/');
        final String directory = slash <= 0 ? "/" : path.substring(0, slash);
        final String temporary = (directory.equals("/") ? "" : directory) + "/." + path.substring(slash + 1)
                + ".aimon-tmp-" + UUID.randomUUID();
        upload(temporary, bytes);
        final String script = mode == WriteMode.CREATE_NEW
                ? "ln -- \"$1\" \"$2\"; r=$?; rm -f -- \"$1\"; exit $r"
                : "if [ -d \"$2\" ]; then rm -f -- \"$1\"; echo \"$2: Is a directory\" >&2; exit 21; fi; "
                        + "mv -f -- \"$1\" \"$2\" || { r=$?; rm -f -- \"$1\"; exit $r; }";
        final ExecOutcome outcome = helper(script, temporary, path);
        if (outcome.exitCode() == 0) {
            return;
        }
        final String error = new String(outcome.stderr(), StandardCharsets.UTF_8).strip();
        if (mode == WriteMode.CREATE_NEW && error.contains("File exists")) {
            throw new FileAlreadyExistsException(path);
        }
        throw new VirtualFileSystemException("cannot write " + path + ": " + error);
    }

    private static byte[] readContent(InputStream content, long length, String path) {
        try (content) {
            if (length >= 0) {
                return content.readNBytes((int) Math.min(length, Integer.MAX_VALUE - 8));
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            content.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new VirtualFileSystemException("cannot read the content for " + path + ": " + e.getMessage(), e);
        }
    }

    /** {@code POST /files/upload}: {@code metadata} must be a file part with a filename (B §e); parents are created. */
    private void upload(String path, byte[] bytes) {
        final String boundary = "aimon-" + UUID.randomUUID();
        final ObjectNode metadata = execd.transport().json.createObjectNode().put("path", path).put("mode", FILE_MODE);
        final ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length + 512);
        final byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"; "
                + "filename=\"metadata.json\"\r\nContent-Type: application/json\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(execd.transport().write(metadata));
        body.writeBytes(crlf);
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(crlf);
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        final byte[] payload = body.toByteArray();
        final HttpResponse<byte[]> response = execd.send(
                ep -> ep.request("/files/upload").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload)),
                "upload file");
        try {
            HttpErrors.check(response, "upload " + path);
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> new FileNotFoundException(path));
        } catch (SandboxProviderException e) {
            throw vfs("cannot write " + path, e);
        }
    }

    /** Runs a small script with {@code $1}, {@code $2} bound to the arguments, as execd's user, in {@code /}. */
    private ExecOutcome helper(String script, String first, String second) {
        final ExecSpec spec = ExecSpec.builder().command("set -- " + shell(first) + " " + shell(second) + "; " + script)
                .workingDirectory("/").timeout(HELPER_TIMEOUT).maxCaptureBytes(8 * 1024).build();
        try {
            return connection.run(spec, OutputSink.DISCARD).await(HELPER_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VirtualFileSystemException("interrupted while moving " + first + " to " + second);
        }
    }

    @Override
    public Optional<FileStat> stat(String path) {
        final HttpResponse<byte[]> response = retry
                .call(() -> execd.send(ep -> ep.request("/files/info?path=" + q(path)).GET(), "stat file"));
        try {
            HttpErrors.check(response, "stat " + path);
        } catch (HttpErrors.NotFound e) {
            // Throws SandboxNotFoundException when it is the sandbox that is gone.
            execd.notFound(e, () -> null);
            return Optional.empty();
        }
        final JsonNode body = execd.transport().read(response);
        final JsonNode info = body.has(path) ? body.get(path) : first(body);
        return info == null ? Optional.empty() : Optional.of(toStat(path, info));
    }

    private static JsonNode first(JsonNode body) {
        final Iterator<JsonNode> values = body.elements();
        return values.hasNext() ? values.next() : null;
    }

    static FileStat toStat(String path, JsonNode info) {
        final boolean directory = "directory".equals(info.path("type").asText());
        Instant modified;
        try {
            modified = Instant.parse(info.path("modified_at").asText());
        } catch (DateTimeParseException e) {
            modified = Instant.EPOCH;
        }
        return FileStat.of(path, directory ? 0 : info.path("size").asLong(0), modified, directory, null);
    }

    @Override
    public List<FileStat> list(String directory, boolean recursive, int limit) {
        final int depth = recursive ? RECURSIVE_DEPTH : 1;
        final HttpResponse<byte[]> response = retry.call(
                () -> execd.send(ep -> ep.request("/directories/list?path=" + q(directory) + "&depth=" + depth).GET(),
                        "list directory"));
        try {
            HttpErrors.check(response, "list " + directory);
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> new FileNotFoundException(directory));
        } catch (SandboxProviderException e) {
            if (e.kind() == SandboxProviderException.Kind.PERMANENT) {
                throw new VirtualFileSystemException("Not a directory: " + directory);
            }
            throw e;
        }
        final List<FileStat> out = new ArrayList<>();
        for (JsonNode entry : execd.transport().read(response)) {
            if (out.size() >= limit) {
                break;
            }
            out.add(toStat(entry.path("path").asText(), entry));
        }
        return out;
    }

    @Override
    public void createDirectories(String path) {
        final ObjectNode body = execd.transport().json.createObjectNode();
        body.putObject(path).put("mode", DIRECTORY_MODE);
        final byte[] payload = execd.transport().write(body);
        final HttpResponse<byte[]> response = retry
                .call(() -> execd.send(ep -> ep.request("/directories").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload)), "create directories"));
        try {
            HttpErrors.check(response, "create " + path);
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> new FileNotFoundException(path));
        } catch (SandboxProviderException e) {
            if (stat(path).map(stat -> !stat.directory()).orElse(false)) {
                throw new FileAlreadyExistsException(path);
            }
            throw vfs("cannot create " + path, e);
        }
    }

    @Override
    public void delete(String path, boolean recursive) {
        final FileStat stat = stat(path).orElseThrow(() -> new FileNotFoundException(path));
        if (stat.directory() && !recursive && !list(path, false, 1).isEmpty()) {
            throw new VirtualFileSystemException("Directory is not empty: " + path);
        }
        final String api = stat.directory() ? "/directories" : "/files";
        final HttpResponse<byte[]> response = retry
                .call(() -> execd.send(ep -> ep.request(api + "?path=" + q(path)).DELETE(), "delete"));
        try {
            HttpErrors.check(response, "delete " + path);
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> new FileNotFoundException(path));
        } catch (SandboxProviderException e) {
            throw vfs("cannot delete " + path, e);
        }
    }

    @Override
    public void move(String from, String to, boolean overwrite) {
        if (overwrite) {
            if (stat(from).isEmpty()) {
                throw new FileNotFoundException(from);
            }
            final ExecOutcome outcome = helper("mkdir -p -- \"$(dirname -- \"$2\")\" && mv -f -T -- \"$1\" \"$2\"",
                    from, to);
            if (outcome.exitCode() != 0) {
                throw new VirtualFileSystemException("cannot move " + from + " to " + to + ": "
                        + new String(outcome.stderr(), StandardCharsets.UTF_8).strip());
            }
            return;
        }
        final ArrayNode body = execd.transport().json.createArrayNode();
        body.addObject().put("src", from).put("dest", to);
        final byte[] payload = execd.transport().write(body);
        final HttpResponse<byte[]> response = execd.send(ep -> ep.request("/files/mv")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)),
                "move");
        try {
            HttpErrors.check(response, "move " + from);
        } catch (HttpErrors.NotFound e) {
            throw execd.notFound(e, () -> new FileNotFoundException(from));
        } catch (SandboxProviderException e) {
            // "Already exists" is only told apart by the message: the code is a generic RUNTIME_ERROR (B §e).
            if (e.getMessage().contains("already exists")) {
                throw new FileAlreadyExistsException(to);
            }
            throw vfs("cannot move " + from + " to " + to, e);
        }
    }

    /** A server-side failure of a file call as the VFS error the file tools report; a transient one stays retryable. */
    private static RuntimeException vfs(String what, SandboxProviderException e) {
        if (e.kind() == SandboxProviderException.Kind.TRANSIENT && !e.getMessage().contains("HTTP 500")) {
            return e;
        }
        return new VirtualFileSystemException(what + ": " + e.getMessage(), e);
    }
}
