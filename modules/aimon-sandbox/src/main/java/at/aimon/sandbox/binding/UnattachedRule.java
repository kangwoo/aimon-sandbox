package at.aimon.sandbox.binding;

import java.util.Locale;

/**
 * What a root request with no attached workspace gets (docs/design/workspace-sandbox-management.md §6.3). Three layers
 * decide it — the {@code unattached} setting, the definition's {@code sandbox.unattached} attribute and the session's
 * own option — and each can only tighten the one above it: {@link #strictest}.
 */
public enum UnattachedRule {

    /** A workspace of its own: the delegate policy's binding ({@code ws:{sessionId}} by default). */
    PER_SESSION,

    /** No sandbox: the bind is rejected and every tool reports it. */
    REJECT;

    /**
     * @param a
     *            one rule
     * @param b
     *            another
     * @return the stricter of the two
     */
    public static UnattachedRule strictest(UnattachedRule a, UnattachedRule b) {
        return a == REJECT || b == REJECT ? REJECT : PER_SESSION;
    }

    /**
     * @param value
     *            {@code per-session} or {@code reject}, in any case, with {@code _} for {@code -}
     * @return the rule
     * @throws IllegalArgumentException
     *             for any other value
     */
    public static UnattachedRule parse(String value) {
        final String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (UnattachedRule rule : values()) {
            if (rule.name().equals(normalized)) {
                return rule;
            }
        }
        throw new IllegalArgumentException("unattached rule '" + value + "' is neither 'per-session' nor 'reject'");
    }
}
