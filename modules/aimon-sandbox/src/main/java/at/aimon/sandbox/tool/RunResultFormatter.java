package at.aimon.sandbox.tool;

import at.aimon.sandbox.model.CommandResult;
import at.aimon.sandbox.model.SandboxRun;
import at.aimon.sandbox.util.ByteFormatUtils;

/**
 * Formats {@link SandboxRun} results into human-readable output for the LLM agent.
 */
final class RunResultFormatter {

    private static final int MAX_OUTPUT_LENGTH = 30_000;

    private RunResultFormatter() {
    }

    /**
     * Formats a completed or failed run into a summary string.
     *
     * @param run
     *            the sandbox run
     * @param artifactsError
     *            optional error message from artifact extraction (nullable)
     * @return the formatted result string
     */
    static String format(SandboxRun run, String artifactsError) {
        StringBuilder sb = new StringBuilder();

        long succeeded = run.getCommands().stream().filter(c -> c.getExitCode() == 0).count();
        long failedCount = run.getCommands().size() - succeeded;

        sb.append("Run ").append(run.getState().name().toLowerCase()).append(": run_id=").append(run.getRunId())
                .append(", identifier=").append(run.getIdentifier()).append('\n');
        sb.append("Commands: ").append(run.getCommands().size()).append(" executed, ").append(succeeded)
                .append(" succeeded, ").append(failedCount).append(" failed\n");

        for (CommandResult cmd : run.getCommands()) {
            sb.append('\n');
            sb.append("[Command ").append(cmd.getIndex() + 1).append("] ").append(cmd.getCommand()).append('\n');
            sb.append("Exit code: ").append(cmd.getExitCode()).append('\n');

            if (cmd.getError() != null) {
                sb.append("Error: ").append(cmd.getError()).append('\n');
            }
            appendOutput(sb, "Stdout", cmd.getStdout(), cmd.getIndex(), "stdout");
            appendOutput(sb, "Stderr", cmd.getStderr(), cmd.getIndex(), "stderr");
        }

        if (run.getArtifactCount() > 0) {
            sb.append('\n');
            sb.append("Artifacts: ").append(run.getArtifactCount()).append(" files extracted (")
                    .append(ByteFormatUtils.formatBytes(run.getArtifactTotalBytes())).append(" total)\n");
            sb.append("Logs: full output saved to /artifacts/logs/\n");
        }

        if (artifactsError != null) {
            sb.append('\n');
            sb.append("Artifacts extraction error: ").append(artifactsError).append('\n');
        }

        return sb.toString();
    }

    private static void appendOutput(StringBuilder sb, String label, String output, int commandIndex, String suffix) {
        if (output != null && !output.isEmpty()) {
            String truncated = truncate(output, MAX_OUTPUT_LENGTH);
            sb.append(label).append(": ").append(truncated).append('\n');
            if (output.length() > MAX_OUTPUT_LENGTH) {
                sb.append("[Output truncated at ").append(MAX_OUTPUT_LENGTH)
                        .append(" characters. Full log: /artifacts/logs/command-").append(commandIndex).append(".")
                        .append(suffix).append("]\n");
            }
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
