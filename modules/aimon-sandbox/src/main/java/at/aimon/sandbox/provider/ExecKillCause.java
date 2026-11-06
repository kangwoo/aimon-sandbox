package at.aimon.sandbox.provider;

/**
 * Why a provider ended a command itself — not a timeout ({@link ExecOutcome#timedOut()}) and not a
 * {@link RunningCommand#kill()} call (docs/design/ptc-shell-prerequisites.md S8).
 */
public enum ExecKillCause {

    /**
     * One line of output exceeded the provider's line limit, so the provider ended the command before holding the
     * line in memory. Both streams are reported truncated: which one the line belonged to is in the part not read.
     */
    LINE_LIMIT_EXCEEDED
}
