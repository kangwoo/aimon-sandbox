package at.aimon.sandbox.binding;

import java.util.Objects;
import java.util.Optional;

import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.WorkspaceOwner;

/**
 * What one execution uses: {@code (workspaceId, owner, slot, requiredProfile?, shellKey, root)}
 * (docs/design/workspace-sandbox.md §3.1), plus the {@link #caller()} — the executing principal, which every manager
 * entry point checks against the owner (§8.3). Only a {@link SandboxBindingPolicy} makes one; tool arguments never
 * reach it.
 */
public final class SandboxBinding {

    /** The default working root inside a sandbox. */
    public static final String DEFAULT_ROOT = "/workspace/repo";

    /** The default slot. */
    public static final String PRIMARY = "primary";

    private final SandboxWorkspaceId workspaceId;
    private final WorkspaceOwner owner;
    private final WorkspaceOwner caller;
    private final String slot;
    private final String requiredProfile;
    private final ShellKey shellKey;
    private final String root;

    private SandboxBinding(Builder builder) {
        this.workspaceId = Objects.requireNonNull(builder.workspaceId, "workspaceId must not be null");
        this.owner = Objects.requireNonNull(builder.owner, "owner must not be null");
        this.caller = Objects.requireNonNull(builder.caller, "caller must not be null");
        this.slot = SlotChoice.requireSlotName(builder.slot);
        this.requiredProfile = builder.requiredProfile;
        this.shellKey = Objects.requireNonNull(builder.shellKey, "shellKey must not be null");
        this.root = Objects.requireNonNull(builder.root, "root must not be null");
    }

    /** @return a new builder ({@code slot = primary}, {@code root = /workspace/repo}) */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder holding this binding's values */
    public Builder toBuilder() {
        return new Builder().workspaceId(workspaceId).owner(owner).caller(caller).slot(slot)
                .requiredProfile(requiredProfile).shellKey(shellKey).root(root);
    }

    /** @return the workspace */
    public SandboxWorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the owner a new record is created with */
    public WorkspaceOwner owner() {
        return owner;
    }

    /** @return the executing principal, checked against the record's owner */
    public WorkspaceOwner caller() {
        return caller;
    }

    /** @return the slot */
    public String slot() {
        return slot;
    }

    /** @return the profile the definition requires, when it names one */
    public Optional<String> requiredProfile() {
        return Optional.ofNullable(requiredProfile);
    }

    /** @return the shell state key */
    public ShellKey shellKey() {
        return shellKey;
    }

    /** @return the root file tools resolve relative paths against */
    public String root() {
        return root;
    }

    @Override
    public String toString() {
        return "SandboxBinding{" + workspaceId + ", slot=" + slot + ", shellKey=" + shellKey + ", root=" + root
                + (requiredProfile != null ? ", requiredProfile=" + requiredProfile : "") + '}';
    }

    /** Builder for {@link SandboxBinding}. */
    public static final class Builder {
        private SandboxWorkspaceId workspaceId;
        private WorkspaceOwner owner;
        private WorkspaceOwner caller;
        private String slot = PRIMARY;
        private String requiredProfile;
        private ShellKey shellKey;
        private String root = DEFAULT_ROOT;

        private Builder() {
        }

        public Builder workspaceId(SandboxWorkspaceId workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder owner(WorkspaceOwner owner) {
            this.owner = owner;
            return this;
        }

        public Builder caller(WorkspaceOwner caller) {
            this.caller = caller;
            return this;
        }

        public Builder slot(String slot) {
            this.slot = slot;
            return this;
        }

        public Builder requiredProfile(String requiredProfile) {
            this.requiredProfile = requiredProfile;
            return this;
        }

        public Builder shellKey(ShellKey shellKey) {
            this.shellKey = shellKey;
            return this;
        }

        public Builder root(String root) {
            this.root = root;
            return this;
        }

        public SandboxBinding build() {
            return new SandboxBinding(this);
        }
    }
}
