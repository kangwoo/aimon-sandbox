package at.aimon.sandbox.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.sandbox.backend.SandboxBackend;
import at.aimon.sandbox.config.SandboxConfig;
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.model.Sandbox;
import at.aimon.sandbox.util.IdentifierValidator;

/**
 * Restarts a persistent sandbox environment by deleting and recreating it.
 *
 * <p>
 * The sandbox is reset to a clean state while keeping the same identifier. Succeeds even if no existing sandbox is
 * found.
 */
public class RestartSandboxTool extends AbstractTool {

    public static final String TOOL_NAME = "RestartSandbox";

    private static final Logger log = LoggerFactory.getLogger(RestartSandboxTool.class);

    private final SandboxBackend backend;
    private final SandboxConfig config;
    private final SandboxLock sandboxLock;

    public RestartSandboxTool(SandboxBackend backend, SandboxConfig config, SandboxLock sandboxLock) {
        super(TOOL_NAME,
                "Restart a persistent sandbox environment by deleting and recreating it. "
                        + "The sandbox is reset to a clean state while keeping the same identifier. "
                        + "Succeeds even if no existing sandbox is found.",
                ToolCategories.EXECUTION, createInputSchema());
        this.backend = Objects.requireNonNull(backend, "Backend cannot be null");
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.sandboxLock = Objects.requireNonNull(sandboxLock, "SandboxLock cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("identifier", Map.of("type", "string", "description", "Sandbox identifier to restart"),
                        "ttl_seconds",
                        Map.of("type", "integer", "description", "New TTL in seconds (default: 1800, max: 86400)"),
                        "lock_sandbox",
                        Map.of("type", "boolean", "description",
                                "Serialize execution on this identifier (default: true)")),
                "required", List.of("identifier"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            String identifier = input.getRequiredString("identifier");
            IdentifierValidator.validate(identifier);
            int ttlSeconds = SandboxToolHelper.resolveTtl(input, config);
            boolean lockSandbox = SandboxToolHelper.resolveLockSandbox(input, config);

            return SandboxToolHelper.withOptionalLock(sandboxLock, identifier, lockSandbox, () -> {
                Sandbox sandbox = backend.restart(identifier, ttlSeconds);
                return ToolResult.success(
                        "Sandbox restarted: identifier=" + identifier + ", sandbox_id=" + sandbox.getSandboxId());
            });
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Restart failed: {}", e.getMessage(), e);
            return ToolResult.error("Restart failed: " + e.getMessage());
        }
    }
}
