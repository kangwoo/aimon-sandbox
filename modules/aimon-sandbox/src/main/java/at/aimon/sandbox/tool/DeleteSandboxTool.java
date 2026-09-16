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
import at.aimon.sandbox.lock.SandboxLock;
import at.aimon.sandbox.util.IdentifierValidator;

/**
 * Deletes a persistent sandbox environment by identifier.
 *
 * <p>
 * The sandbox container/pod is removed immediately. Artifacts are preserved. A new sandbox will be created on the next
 * RunSandbox call.
 */
public class DeleteSandboxTool extends AbstractTool {

    public static final String TOOL_NAME = "DeleteSandbox";

    private static final Logger log = LoggerFactory.getLogger(DeleteSandboxTool.class);

    private final SandboxBackend backend;
    private final SandboxLock sandboxLock;

    public DeleteSandboxTool(SandboxBackend backend, SandboxLock sandboxLock) {
        super(TOOL_NAME,
                "Delete a persistent sandbox environment by identifier. "
                        + "The sandbox container/pod is removed immediately. "
                        + "Artifacts are preserved. A new sandbox will be created on next RunSandbox call.",
                ToolCategories.EXECUTION, createInputSchema());
        this.backend = Objects.requireNonNull(backend, "Backend cannot be null");
        this.sandboxLock = Objects.requireNonNull(sandboxLock, "SandboxLock cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("identifier", Map.of("type", "string", "description", "Sandbox identifier to delete")),
                "required", List.of("identifier"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            String identifier = input.getRequiredString("identifier");
            IdentifierValidator.validate(identifier);
            backend.delete(identifier);
            sandboxLock.removeLock(identifier);
            return ToolResult.success("Sandbox deleted: identifier=" + identifier);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Delete failed: {}", e.getMessage(), e);
            return ToolResult.error("Delete failed: " + e.getMessage());
        }
    }
}
