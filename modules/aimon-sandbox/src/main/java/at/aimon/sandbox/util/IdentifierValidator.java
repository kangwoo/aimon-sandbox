package at.aimon.sandbox.util;

import java.util.regex.Pattern;

/**
 * Validates sandbox identifiers.
 *
 * <p>
 * Identifiers must match the pattern {@code ^[a-zA-Z0-9_-]{1,36}$}.
 */
public final class IdentifierValidator {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,36}$");

    private IdentifierValidator() {
    }

    /**
     * Validates the given identifier.
     *
     * @param identifier
     *            the identifier to validate
     * @throws IllegalArgumentException
     *             if the identifier is null or does not match the required pattern
     */
    public static void validate(String identifier) {
        if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Invalid identifier: must match ^[a-zA-Z0-9_-]{1,36}$, got: " + identifier);
        }
    }
}
