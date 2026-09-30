package at.aimon.sandbox;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.workspace.WorkspaceQuota;

/**
 * The workspace sandbox's configuration — the keys implementation steps 3 and 4 read, named after the Spring
 * properties docs/design/workspace-sandbox.md §13.2 proposes ({@code provision-timeout} is {@link #provisionTimeout()},
 * and so on). Keys a later step reads (volume and orchestrator keys) are added with that step: a key nothing reads
 * rots. The provider's own keys ({@code aimon.sandbox.opensandbox.*}) belong to the provider module, never here.
 *
 * <p>
 * Validated as a whole by {@link WorkspaceSandbox.Builder#build()}, not here, so one failed start lists every problem.
 */
public final class SandboxSettings {

    /** {@link #maxRunningPerTenant()} value that turns admission off; it has to be set explicitly (§12.2). */
    public static final int UNLIMITED = -1;

    /** Who may use a workspace besides its owner (§8.3). */
    public enum WorkspaceAccess {
        /** The owner's tenant <i>and</i> principal must match (the default). */
        PRINCIPAL,
        /** The owner's tenant must match. */
        TENANT
    }

    private final String deployment;
    private final boolean requirePrincipal;
    private final WorkspaceAccess workspaceAccess;
    private final Set<String> allowedSystemPrincipals;
    private final Duration provisionTimeout;
    private final Duration failureBackoff;
    private final Duration maxFailureBackoff;
    private final Duration activityWriteInterval;
    private final int casRetries;
    private final Duration closeWait;
    private final Duration closeResumeAfter;
    private final Duration closedRetention;
    private final Duration execShellIdle;
    private final Duration shellLockWait;
    private final Duration janitorInterval;
    private final Duration orphanGrace;
    private final Duration lostConfirmAfter;
    private final int maxRunningPerTenant;
    private final String defaultProfile;
    private final List<SandboxProfile> profiles;
    private final Duration closeAfter;
    private final WorkspaceQuota quota;
    private final String nodeId;

    private SandboxSettings(Builder builder) {
        this.deployment = builder.deployment;
        this.requirePrincipal = builder.requirePrincipal;
        this.workspaceAccess = Objects.requireNonNull(builder.workspaceAccess, "workspaceAccess must not be null");
        this.allowedSystemPrincipals = Set.copyOf(builder.allowedSystemPrincipals);
        this.provisionTimeout = Objects.requireNonNull(builder.provisionTimeout, "provisionTimeout");
        this.failureBackoff = Objects.requireNonNull(builder.failureBackoff, "failureBackoff");
        this.maxFailureBackoff = Objects.requireNonNull(builder.maxFailureBackoff, "maxFailureBackoff");
        this.activityWriteInterval = Objects.requireNonNull(builder.activityWriteInterval, "activityWriteInterval");
        this.casRetries = builder.casRetries;
        this.closeWait = Objects.requireNonNull(builder.closeWait, "closeWait");
        this.closeResumeAfter = Objects.requireNonNull(builder.closeResumeAfter, "closeResumeAfter");
        this.closedRetention = Objects.requireNonNull(builder.closedRetention, "closedRetention");
        this.execShellIdle = Objects.requireNonNull(builder.execShellIdle, "execShellIdle");
        this.shellLockWait = Objects.requireNonNull(builder.shellLockWait, "shellLockWait");
        this.janitorInterval = Objects.requireNonNull(builder.janitorInterval, "janitorInterval");
        this.orphanGrace = Objects.requireNonNull(builder.orphanGrace, "orphanGrace");
        this.lostConfirmAfter = Objects.requireNonNull(builder.lostConfirmAfter, "lostConfirmAfter");
        this.maxRunningPerTenant = builder.maxRunningPerTenant;
        this.defaultProfile = builder.defaultProfile;
        this.profiles = List.copyOf(builder.profiles);
        this.closeAfter = Objects.requireNonNull(builder.closeAfter, "closeAfter");
        this.quota = Objects.requireNonNull(builder.quota, "quota");
        this.nodeId = builder.nodeId != null ? builder.nodeId : defaultNodeId();
    }

    /** @return a new builder with the §13.2 defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a builder holding these settings */
    public Builder toBuilder() {
        return new Builder().deployment(deployment).requirePrincipal(requirePrincipal).workspaceAccess(workspaceAccess)
                .allowedSystemPrincipals(allowedSystemPrincipals).provisionTimeout(provisionTimeout)
                .failureBackoff(failureBackoff).maxFailureBackoff(maxFailureBackoff)
                .activityWriteInterval(activityWriteInterval).casRetries(casRetries).closeWait(closeWait)
                .closeResumeAfter(closeResumeAfter).closedRetention(closedRetention).execShellIdle(execShellIdle)
                .shellLockWait(shellLockWait).janitorInterval(janitorInterval).orphanGrace(orphanGrace)
                .lostConfirmAfter(lostConfirmAfter).maxRunningPerTenant(maxRunningPerTenant)
                .defaultProfile(defaultProfile).profiles(profiles).closeAfter(closeAfter).quota(quota).nodeId(nodeId);
    }

    /**
     * {@code {hostname}-{uuid}}, drawn fresh in every JVM so a restarted node never mistakes its old commands for its
     * current ones (§9).
     */
    private static String defaultNodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException e) {
            host = "node";
        }
        return host + "-" + UUID.randomUUID();
    }

    /** @return {@code deployment}: the name of the set of nodes sharing one store; required, label-safe (§6.3) */
    public String deployment() {
        return deployment;
    }

    /** @return {@code require-principal}: only USER/GROUP principals, or listed system principals (§8.3) */
    public boolean requirePrincipal() {
        return requirePrincipal;
    }

    /** @return {@code workspace-access} */
    public WorkspaceAccess workspaceAccess() {
        return workspaceAccess;
    }

    /** @return {@code allowed-system-principals}: SYSTEM/SERVICE principal ids that may use sandboxes */
    public Set<String> allowedSystemPrincipals() {
        return allowedSystemPrincipals;
    }

    /** @return {@code provision-timeout} (5m) */
    public Duration provisionTimeout() {
        return provisionTimeout;
    }

    /** @return {@code failure-backoff} (1m): the first retry delay of a transient failure, doubling */
    public Duration failureBackoff() {
        return failureBackoff;
    }

    /** @return {@code max-failure-backoff} (15m) */
    public Duration maxFailureBackoff() {
        return maxFailureBackoff;
    }

    /** @return {@code activity-write-interval} (30s): activity writes, expiry extension and heartbeat */
    public Duration activityWriteInterval() {
        return activityWriteInterval;
    }

    /** @return {@code cas-retries} (5) */
    public int casRetries() {
        return casRetries;
    }

    /** @return {@code close-wait} (2m): how long close waits for the sandboxes to disappear */
    public Duration closeWait() {
        return closeWait;
    }

    /** @return {@code close-resume-after} (5m): the janitor resumes a close stuck in CLOSING this long */
    public Duration closeResumeAfter() {
        return closeResumeAfter;
    }

    /** @return {@code closed-retention} (7d): how long a CLOSED record is kept */
    public Duration closedRetention() {
        return closedRetention;
    }

    /** @return {@code exec-shell-idle} (10m): an unused {@code exec:} shell directory is deleted after this */
    public Duration execShellIdle() {
        return execShellIdle;
    }

    /** @return {@code shell-lock-wait} (10s): how long a command waits for its shell (§9) */
    public Duration shellLockWait() {
        return shellLockWait;
    }

    /** @return {@code janitor.interval} (30s) */
    public Duration janitorInterval() {
        return janitorInterval;
    }

    /**
     * @return {@code orphan-grace} (10m): how old a sandbox with no matching record, or a duplicate, must be
     *         before reconciliation destroys it (§10.4); longer than {@code provision-timeout}
     */
    public Duration orphanGrace() {
        return orphanGrace;
    }

    /**
     * @return {@code lost-confirm-after} (90s): how long a RUNNING slot's sandbox must stay missing before
     *         reconciliation declares it LOST (§10.4)
     */
    public Duration lostConfirmAfter() {
        return lostConfirmAfter;
    }

    /** @return {@code admission.max-running-per-tenant} (10), or {@link #UNLIMITED} */
    public int maxRunningPerTenant() {
        return maxRunningPerTenant;
    }

    /** @return {@code default-profile} */
    public String defaultProfile() {
        return defaultProfile;
    }

    /** @return {@code profiles} */
    public List<SandboxProfile> profiles() {
        return profiles;
    }

    /** @return {@code workspace.close-after} (24h) */
    public Duration closeAfter() {
        return closeAfter;
    }

    /** @return {@code workspace.max-slots}, {@code workspace.max-running}, copied into every new record */
    public WorkspaceQuota quota() {
        return quota;
    }

    /** @return this node's id, {@code {hostname}-{uuid}} unless set */
    public String nodeId() {
        return nodeId;
    }

    /** Builder for {@link SandboxSettings}, holding the §13.2 defaults. */
    public static final class Builder {
        private String deployment;
        private boolean requirePrincipal;
        private WorkspaceAccess workspaceAccess = WorkspaceAccess.PRINCIPAL;
        private Set<String> allowedSystemPrincipals = Set.of();
        private Duration provisionTimeout = Duration.ofMinutes(5);
        private Duration failureBackoff = Duration.ofMinutes(1);
        private Duration maxFailureBackoff = Duration.ofMinutes(15);
        private Duration activityWriteInterval = Duration.ofSeconds(30);
        private int casRetries = 5;
        private Duration closeWait = Duration.ofMinutes(2);
        private Duration closeResumeAfter = Duration.ofMinutes(5);
        private Duration closedRetention = Duration.ofDays(7);
        private Duration execShellIdle = Duration.ofMinutes(10);
        private Duration shellLockWait = Duration.ofSeconds(10);
        private Duration janitorInterval = Duration.ofSeconds(30);
        private Duration orphanGrace = Duration.ofMinutes(10);
        private Duration lostConfirmAfter = Duration.ofSeconds(90);
        private int maxRunningPerTenant = 10;
        private String defaultProfile;
        private List<SandboxProfile> profiles = List.of();
        private Duration closeAfter = Duration.ofHours(24);
        private WorkspaceQuota quota = WorkspaceQuota.defaults();
        private String nodeId;

        private Builder() {
        }

        public Builder deployment(String deployment) {
            this.deployment = deployment;
            return this;
        }

        public Builder requirePrincipal(boolean requirePrincipal) {
            this.requirePrincipal = requirePrincipal;
            return this;
        }

        public Builder workspaceAccess(WorkspaceAccess workspaceAccess) {
            this.workspaceAccess = workspaceAccess;
            return this;
        }

        public Builder allowedSystemPrincipals(Set<String> allowedSystemPrincipals) {
            this.allowedSystemPrincipals = Objects.requireNonNull(allowedSystemPrincipals);
            return this;
        }

        public Builder provisionTimeout(Duration provisionTimeout) {
            this.provisionTimeout = provisionTimeout;
            return this;
        }

        public Builder failureBackoff(Duration failureBackoff) {
            this.failureBackoff = failureBackoff;
            return this;
        }

        public Builder maxFailureBackoff(Duration maxFailureBackoff) {
            this.maxFailureBackoff = maxFailureBackoff;
            return this;
        }

        public Builder activityWriteInterval(Duration activityWriteInterval) {
            this.activityWriteInterval = activityWriteInterval;
            return this;
        }

        public Builder casRetries(int casRetries) {
            this.casRetries = casRetries;
            return this;
        }

        public Builder closeWait(Duration closeWait) {
            this.closeWait = closeWait;
            return this;
        }

        public Builder closeResumeAfter(Duration closeResumeAfter) {
            this.closeResumeAfter = closeResumeAfter;
            return this;
        }

        public Builder closedRetention(Duration closedRetention) {
            this.closedRetention = closedRetention;
            return this;
        }

        public Builder execShellIdle(Duration execShellIdle) {
            this.execShellIdle = execShellIdle;
            return this;
        }

        public Builder shellLockWait(Duration shellLockWait) {
            this.shellLockWait = shellLockWait;
            return this;
        }

        public Builder janitorInterval(Duration janitorInterval) {
            this.janitorInterval = janitorInterval;
            return this;
        }

        public Builder orphanGrace(Duration orphanGrace) {
            this.orphanGrace = orphanGrace;
            return this;
        }

        public Builder lostConfirmAfter(Duration lostConfirmAfter) {
            this.lostConfirmAfter = lostConfirmAfter;
            return this;
        }

        public Builder maxRunningPerTenant(int maxRunningPerTenant) {
            this.maxRunningPerTenant = maxRunningPerTenant;
            return this;
        }

        public Builder defaultProfile(String defaultProfile) {
            this.defaultProfile = defaultProfile;
            return this;
        }

        public Builder profiles(List<SandboxProfile> profiles) {
            this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
            return this;
        }

        public Builder closeAfter(Duration closeAfter) {
            this.closeAfter = closeAfter;
            return this;
        }

        public Builder quota(WorkspaceQuota quota) {
            this.quota = quota;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public SandboxSettings build() {
            return new SandboxSettings(this);
        }
    }
}
