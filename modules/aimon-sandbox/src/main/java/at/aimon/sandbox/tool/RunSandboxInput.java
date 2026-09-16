package at.aimon.sandbox.tool;

import java.util.List;

import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.agent.tool.generic.ToolParam;

/**
 * The parameters of a {@link RunSandboxTool} call, and the source of its schema.
 *
 * <p>
 * A record rather than the project's usual builder class — the narrow exception granted to {@link GenericTool} input
 * types, whose reasoning is in {@code at.aimon.core.agent.tool.generic}'s package documentation.
 *
 * <p>
 * The command list was the tool's one unchecked cast: it used to arrive as {@code List<Map<String, Object>>} and be
 * read key by key, so a misspelled {@code timeout_ms} or a number where a boolean belonged was silently dropped.
 * Declaring {@link CommandSpec} makes each element bind under the same rules as a top-level parameter, and a failure
 * names its position — {@code commands[2].timeout_ms} rather than nothing at all.
 *
 * @param identifier
 *            the sandbox to run in
 * @param commands
 *            the commands to run, in order
 * @param ttlSeconds
 *            the requested sandbox TTL, or null for the configured default
 * @param continueOnError
 *            whether a failing command lets the rest of the sequence proceed, or null for false
 * @param lockSandbox
 *            whether to serialize on the identifier, or null for the configured default
 */
public record RunSandboxInput(

        @ToolParam(required = true, description = "Sandbox identifier. Regex: ^[a-zA-Z0-9_-]{1,36}$") String identifier,

        @ToolParam(required = true, description = "Commands to execute sequentially "
                + "in the sandbox") List<CommandSpec> commands,

        @ToolParam(name = "ttl_seconds", description = "Sandbox TTL in seconds "
                + "(default: 1800, max: 86400)") Integer ttlSeconds,

        @ToolParam(name = "continue_on_error", description = "Continue executing remaining commands "
                + "even if one fails") Boolean continueOnError,

        @ToolParam(name = "lock_sandbox", description = "Serialize execution on this identifier "
                + "(default: true)") Boolean lockSandbox) {

    /**
     * One command in the sequence.
     *
     * <p>
     * {@code shell} and {@code argv} are alternatives rather than both-optional, but the exclusion is not expressed
     * here: {@code CommandInput} already enforces it when it builds, and stating it twice is what this migration is
     * removing. Every component is a wrapper type so "not supplied" stays distinguishable from a supplied zero or
     * false — {@code timeout_ms: 0} must not read the same as an absent timeout, which falls back to the configured
     * default.
     *
     * @param shell
     *            the command as a shell string, or null when {@code argv} carries it
     * @param argv
     *            the command as an argument vector, or null when {@code shell} carries it
     * @param cwd
     *            the absolute working directory, or null for the sandbox default
     * @param timeoutMs
     *            the per-command timeout, or null for the default
     * @param allowFailure
     *            whether this command's failure is tolerated, or null for false
     */
    public record CommandSpec(

            @ToolParam(description = "Shell command string") String shell,

            @ToolParam(description = "Command as argument vector "
                    + "(provide either shell or argv, not both)") List<String> argv,

            @ToolParam(description = "Working directory (absolute path)") String cwd,

            @ToolParam(name = "timeout_ms", description = "Command timeout in milliseconds") Integer timeoutMs,

            @ToolParam(name = "allow_failure", description = "Continue even if this command "
                    + "fails") Boolean allowFailure) {
    }
}
