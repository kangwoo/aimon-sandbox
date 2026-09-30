package at.aimon.sandbox.opensandbox;

import java.util.Objects;
import java.util.function.Supplier;

import at.aimon.sandbox.provider.CredentialScope;

/**
 * One credential binding of the egress sidecar's vault (docs/design/workspace-sandbox.md §12.1): where it is injected
 * ({@link #scope()}), how ({@link #auth()}), and the secret. The secret is read from its supplier when a sandbox is
 * created, so a rotation takes effect with the next generation; it is sent only to the vault and never logged —
 * {@link #toString()} leaves it out.
 */
public final class CredentialDefinition {

    /** How the vault injects the secret. */
    public static final class Auth {
        private final String type;
        private final String headerName;

        private Auth(String type, String headerName) {
            this.type = type;
            this.headerName = headerName;
        }

        /** @return {@code Authorization: Bearer {secret}} */
        public static Auth bearer() {
            return new Auth("bearer", null);
        }

        /** @return {@code Authorization: Basic {secret}}, the secret already base64-encoded */
        public static Auth basic() {
            return new Auth("basic", null);
        }

        /**
         * @param headerName
         *            the header that carries the secret
         * @return {@code {headerName}: {secret}}
         */
        public static Auth apiKey(String headerName) {
            return new Auth("apiKey", Objects.requireNonNull(headerName, "headerName must not be null"));
        }

        /** @return the vault's auth type */
        public String type() {
            return type;
        }

        /** @return the header of an {@code apiKey} auth */
        public String headerName() {
            return headerName;
        }

        @Override
        public String toString() {
            return headerName == null ? type : type + "(" + headerName + ")";
        }
    }

    private final CredentialScope scope;
    private final Auth auth;
    private final Supplier<String> secret;

    private CredentialDefinition(CredentialScope scope, Auth auth, Supplier<String> secret) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.auth = Objects.requireNonNull(auth, "auth must not be null");
        this.secret = Objects.requireNonNull(secret, "secret must not be null");
    }

    /**
     * @param scope
     *            where the vault injects it
     * @param auth
     *            how
     * @param secret
     *            the secret, read at each sandbox creation
     * @return the definition
     */
    public static CredentialDefinition of(CredentialScope scope, Auth auth, Supplier<String> secret) {
        return new CredentialDefinition(scope, auth, secret);
    }

    /** @return where it is injected */
    public CredentialScope scope() {
        return scope;
    }

    /** @return how it is injected */
    public Auth auth() {
        return auth;
    }

    /** @return the secret's supplier */
    Supplier<String> secret() {
        return secret;
    }

    @Override
    public String toString() {
        return "CredentialDefinition{scope=" + scope + ", auth=" + auth + ", secret=<redacted>}";
    }
}
