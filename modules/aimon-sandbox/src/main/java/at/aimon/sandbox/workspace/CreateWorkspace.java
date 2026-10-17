package at.aimon.sandbox.workspace;

import java.util.Objects;
import java.util.Optional;

import at.aimon.sandbox.SandboxSettings;

/**
 * A request to create a managed workspace (docs/design/workspace-sandbox-management.md §5.1). Its id is generated —
 * never chosen — so the {@link #name()} is only for people: it need not be unique and never reaches the model.
 */
public final class CreateWorkspace {

    /** The longest name accepted. */
    public static final int MAX_NAME_LENGTH = 80;

    private final String name;
    private final String profile;
    private final SandboxSettings.WorkspaceAccess access;

    private CreateWorkspace(Builder builder) {
        this.name = requireName(builder.name);
        this.profile = builder.profile;
        this.access = builder.access;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.isBlank()) {
            throw new IllegalArgumentException("a workspace name has 1.." + MAX_NAME_LENGTH
                    + " characters and is not blank, got " + name.length() + " characters");
        }
        if (name.codePoints().anyMatch(c -> Character.isISOControl(c) || !Character.isDefined(c))) {
            throw new IllegalArgumentException("a workspace name must be printable: no control characters");
        }
        return name;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param name
     *            the display name
     * @return a request for a workspace of the default profile and the deployment's access
     */
    public static CreateWorkspace named(String name) {
        return builder().name(name).build();
    }

    /** @return the display name: 1..80 printable characters, not unique */
    public String name() {
        return name;
    }

    /** @return the profile; absent means {@code default-profile} */
    public Optional<String> profile() {
        return Optional.ofNullable(profile);
    }

    /**
     * @return the workspace's own access; absent means {@code workspace-access}. {@code TENANT} in a {@code principal}
     *         deployment needs {@code allow-shared-workspaces}
     */
    public Optional<SandboxSettings.WorkspaceAccess> access() {
        return Optional.ofNullable(access);
    }

    /** Builder for {@link CreateWorkspace}. */
    public static final class Builder {
        private String name;
        private String profile;
        private SandboxSettings.WorkspaceAccess access;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder access(SandboxSettings.WorkspaceAccess access) {
            this.access = access;
            return this;
        }

        public CreateWorkspace build() {
            return new CreateWorkspace(this);
        }
    }
}
