package at.aimon.sandbox.tool;

import java.util.List;

import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.agent.tool.generic.ToolParam;

/**
 * The parameters of a {@link CopyToSandboxTool} call, and the source of its schema.
 *
 * <p>
 * A record rather than the project's usual builder class — the narrow exception granted to {@link GenericTool} input
 * types, whose reasoning is in {@code at.aimon.core.agent.tool.generic}'s package documentation.
 *
 * <p>
 * The file list was this tool's unchecked cast, the second of the two in the sandbox module. It arrived as
 * {@code List<Map<String, Object>>} and every field was read with {@code toString()}, so a number or a nested object
 * where a path belonged became a plausible-looking string instead of an error. {@link FileEntrySpec} makes each element
 * bind under the same rules as a top-level parameter, and a failure names its position.
 *
 * @param identifier
 *            the sandbox to copy into
 * @param files
 *            the files to package and transfer
 * @param destPath
 *            the destination directory inside the sandbox, or null for the default
 * @param ttlSeconds
 *            the requested sandbox TTL, or null for the configured default
 * @param lockSandbox
 *            whether to serialize on the identifier, or null for the configured default
 */
public record CopyToSandboxInput(

        @ToolParam(required = true, description = "Sandbox identifier. Regex: ^[a-zA-Z0-9_-]{1,36}$") String identifier,

        @ToolParam(required = true, description = "Files to copy into the sandbox") List<FileEntrySpec> files,

        @ToolParam(name = "dest_path", description = "Destination directory in sandbox "
                + "(default: /workspace)") String destPath,

        @ToolParam(name = "ttl_seconds", description = "Sandbox TTL in seconds "
                + "(default: 1800, max: 86400)") Integer ttlSeconds,

        @ToolParam(name = "lock_sandbox", description = "Serialize execution on this identifier "
                + "(default: true)") Boolean lockSandbox) {

    /**
     * One file to copy: where to read it from, and what to call it inside the archive.
     *
     * <p>
     * {@code source} is required here just as it was in the hand-written schema, so the tool's own "missing required
     * 'source' field" check is gone — binding rejects it before {@code doExecute} runs, and names which element was
     * wrong while doing so.
     *
     * @param source
     *            the VFS path to read
     * @param destName
     *            the name to store it under, or null to reuse the source file's own name
     */
    public record FileEntrySpec(

            @ToolParam(required = true, description = "VFS file path (source file to copy)") String source,

            @ToolParam(name = "dest_name", description = "File name in sandbox "
                    + "(default: original file name)") String destName) {
    }
}
