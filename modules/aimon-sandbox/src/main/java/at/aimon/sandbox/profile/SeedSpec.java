package at.aimon.sandbox.profile;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a profile's {@code /workspace/repo} comes from (docs/design/workspace-sandbox.md §11.3, §13.1): a git remote, a
 * ref, and the name of a read-only seed credential. Implementation step 3 rejects any seed at startup (the git paths
 * arrive with step 5); the type exists so profiles keep one shape across steps.
 */
public final class SeedSpec {

    private final String gitUrl;
    private final String ref;
    private final String credential;

    private SeedSpec(String gitUrl, String ref, String credential) {
        this.gitUrl = Objects.requireNonNull(gitUrl, "gitUrl must not be null");
        this.ref = ref;
        this.credential = credential;
    }

    /**
     * @param gitUrl
     *            the remote
     * @param ref
     *            the ref to check out, or {@code null} for the remote's default
     * @param credential
     *            the seed credential's name, or {@code null}
     * @return the seed
     */
    public static SeedSpec git(String gitUrl, String ref, String credential) {
        return new SeedSpec(gitUrl, ref, credential);
    }

    /** @return the remote */
    public String gitUrl() {
        return gitUrl;
    }

    /** @return the ref */
    public Optional<String> ref() {
        return Optional.ofNullable(ref);
    }

    /** @return the seed credential's name */
    public Optional<String> credential() {
        return Optional.ofNullable(credential);
    }

    @Override
    public String toString() {
        return "git " + gitUrl + (ref != null ? "@" + ref : "") + (credential != null ? " as " + credential : "");
    }
}
