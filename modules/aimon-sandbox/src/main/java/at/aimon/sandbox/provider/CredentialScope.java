package at.aimon.sandbox.provider;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Where one credential binding is injected: scheme, host, method and path (docs/design/workspace-sandbox.md §12.1).
 * The shape is OpenSandbox's vault {@code CredentialMatch} without {@code ports} — deprecated there, the port follows
 * the scheme — so ports are ignored, which makes {@link #overlaps} err towards "overlaps".
 *
 * <ul>
 * <li>{@link #hosts()}: exact host names, or {@code *.suffix} for every host under {@code suffix};</li>
 * <li>{@link #paths()}: a trailing {@code *} matches by prefix, anything else exactly;</li>
 * <li>schemes default to {@code https}, methods to {@code GET POST PUT PATCH DELETE}, paths to {@code /*}.</li>
 * </ul>
 */
public final class CredentialScope {

    /** The vault's default methods. */
    public static final Set<String> DEFAULT_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final Set<String> schemes;
    private final Set<String> hosts;
    private final Set<String> methods;
    private final List<String> paths;

    private CredentialScope(Builder builder) {
        this.schemes = lower(builder.schemes.isEmpty() ? Set.of("https") : builder.schemes);
        if (builder.hosts.isEmpty()) {
            throw new IllegalArgumentException("a credential scope needs at least one host");
        }
        this.hosts = lower(builder.hosts);
        final Set<String> upper = new LinkedHashSet<>();
        (builder.methods.isEmpty() ? DEFAULT_METHODS : builder.methods)
                .forEach(method -> upper.add(method.toUpperCase(Locale.ROOT)));
        this.methods = Collections.unmodifiableSet(upper);
        this.paths = List.copyOf(builder.paths.isEmpty() ? List.of("/*") : builder.paths);
        for (String path : paths) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("credential scope path '" + path + "' must start with /");
            }
        }
    }

    private static Set<String> lower(Set<String> values) {
        final Set<String> out = new LinkedHashSet<>();
        values.forEach(value -> out.add(value.toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableSet(out);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the schemes, lower-case */
    public Set<String> schemes() {
        return schemes;
    }

    /** @return the hosts, lower-case: exact names or {@code *.suffix} */
    public Set<String> hosts() {
        return hosts;
    }

    /** @return the methods, upper-case */
    public Set<String> methods() {
        return methods;
    }

    /** @return the paths: a trailing {@code *} is a prefix, anything else exact */
    public List<String> paths() {
        return paths;
    }

    /**
     * Whether one request could match both scopes — the ambiguity the vault refuses, found at startup instead.
     *
     * @param other
     *            the other scope
     * @return true when schemes, methods, some host pair and some path pair all overlap
     */
    public boolean overlaps(CredentialScope other) {
        return !Collections.disjoint(schemes, other.schemes) && !Collections.disjoint(methods, other.methods)
                && hosts.stream().anyMatch(a -> other.hosts.stream().anyMatch(b -> hostsOverlap(a, b)))
                && paths.stream().anyMatch(a -> other.paths.stream().anyMatch(b -> pathsOverlap(a, b)));
    }

    static boolean hostsOverlap(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        final boolean wildA = a.startsWith("*.");
        final boolean wildB = b.startsWith("*.");
        if (wildA && wildB) {
            final String suffixA = a.substring(1);
            final String suffixB = b.substring(1);
            return suffixA.endsWith(suffixB) || suffixB.endsWith(suffixA);
        }
        if (wildA) {
            return b.endsWith(a.substring(1));
        }
        return wildB && a.endsWith(b.substring(1));
    }

    static boolean pathsOverlap(String a, String b) {
        final boolean prefixA = a.endsWith("*");
        final boolean prefixB = b.endsWith("*");
        final String stemA = prefixA ? a.substring(0, a.length() - 1) : a;
        final String stemB = prefixB ? b.substring(0, b.length() - 1) : b;
        if (prefixA && prefixB) {
            return stemA.startsWith(stemB) || stemB.startsWith(stemA);
        }
        if (prefixA) {
            return b.startsWith(stemA);
        }
        if (prefixB) {
            return a.startsWith(stemB);
        }
        return a.equals(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CredentialScope that)) {
            return false;
        }
        return schemes.equals(that.schemes) && hosts.equals(that.hosts) && methods.equals(that.methods)
                && paths.equals(that.paths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemes, hosts, methods, paths);
    }

    @Override
    public String toString() {
        return "CredentialScope{schemes=" + schemes + ", hosts=" + hosts + ", methods=" + methods + ", paths=" + paths
                + '}';
    }

    /** Builder for {@link CredentialScope}. */
    public static final class Builder {
        private Set<String> schemes = Set.of();
        private Set<String> hosts = Set.of();
        private Set<String> methods = Set.of();
        private List<String> paths = List.of();

        private Builder() {
        }

        public Builder schemes(Set<String> schemes) {
            this.schemes = Objects.requireNonNull(schemes, "schemes must not be null");
            return this;
        }

        public Builder hosts(Set<String> hosts) {
            this.hosts = Objects.requireNonNull(hosts, "hosts must not be null");
            return this;
        }

        public Builder methods(Set<String> methods) {
            this.methods = Objects.requireNonNull(methods, "methods must not be null");
            return this;
        }

        public Builder paths(List<String> paths) {
            this.paths = Objects.requireNonNull(paths, "paths must not be null");
            return this;
        }

        public CredentialScope build() {
            return new CredentialScope(this);
        }
    }
}
