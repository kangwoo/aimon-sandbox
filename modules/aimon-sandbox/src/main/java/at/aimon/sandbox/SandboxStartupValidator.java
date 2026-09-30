package at.aimon.sandbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.profile.SharedAccess;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.ProviderCapabilities;

/**
 * The startup checks of docs/design/workspace-sandbox.md §13.2 that implementation steps 3 and 4 can make, plus their
 * own refusals. Collects every violation so one failed start lists them all; nothing is started when any is found.
 *
 * <p>
 * Refused, rather than ignored, until the step that can do it: {@code pause-after} (no {@code PAUSE_RESUME} until
 * step 7), {@code shared-access} other than {@code none} (step 5) and any {@code seed} (git seeding arrives with step
 * 5).
 *
 * <p>
 * Credential bindings (step 4) are checked against the provider's {@linkplain ProviderCapabilities#credentialScopes()
 * scopes}: every name must exist, no two bindings of a profile may overlap (the vault could not tell which to inject),
 * and every binding host must be allowed by the profile's {@code egress} — the vault refuses a host the egress policy
 * does not allow explicitly, so a profile with credentials and no {@code egress} is refused too.
 */
final class SandboxStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SandboxStartupValidator.class);

    /**
     * How many activity writes a profile's {@code terminate-after} must span: a heartbeat writes once per
     * {@code activity-write-interval}, and one late or failed write must not let the janitor terminate a sandbox whose
     * command is still running.
     */
    static final int ACTIVITY_WRITES_PER_TERMINATE_AFTER = 3;

    /** Kubernetes label-value rules (§6.3). */
    static final Pattern LABEL_SAFE = Pattern.compile("[a-z0-9]([-a-z0-9_.]{0,61}[a-z0-9])?");

    private SandboxStartupValidator() {
    }

    /** What the assembly was given besides the settings. */
    static final class Wiring {
        private final ProviderCapabilities capabilities;
        private final boolean tenantResolverSupplied;
        private final boolean sessionOwnerLookupSupplied;

        Wiring(ProviderCapabilities capabilities, boolean tenantResolverSupplied, boolean sessionOwnerLookupSupplied) {
            this.capabilities = capabilities;
            this.tenantResolverSupplied = tenantResolverSupplied;
            this.sessionOwnerLookupSupplied = sessionOwnerLookupSupplied;
        }
    }

    /**
     * @param settings
     *            the settings
     * @param wiring
     *            the provider's capabilities and which optional beans were supplied
     * @throws SandboxConfigurationException
     *             listing every violation
     */
    static void validate(SandboxSettings settings, Wiring wiring) {
        final List<String> violations = new ArrayList<>();
        final String deployment = settings.deployment();
        if (deployment == null || deployment.isBlank()) {
            violations.add("deployment is required: it scopes labels and reconciliation, and has no default");
        } else if (!LABEL_SAFE.matcher(deployment).matches()) {
            violations.add("deployment '" + deployment + "' is not label-safe: it must match " + LABEL_SAFE.pattern());
        }
        checkTimings(settings, violations);
        final Set<String> names = new HashSet<>();
        for (SandboxProfile profile : settings.profiles()) {
            if (!names.add(profile.name())) {
                violations.add("profile '" + profile.name() + "' is defined twice");
            }
            checkProfile(profile, wiring.capabilities, violations);
            checkTerminateAfterSpansHeartbeats(profile, settings.activityWriteInterval(), violations);
        }
        if (settings.defaultProfile() == null) {
            violations.add("default-profile is required");
        } else if (!names.contains(settings.defaultProfile())) {
            violations.add("default-profile '" + settings.defaultProfile() + "' is not a defined profile");
        }
        if (settings.requirePrincipal() && !wiring.tenantResolverSupplied) {
            violations.add("require-principal needs a SandboxTenantResolver");
        }
        if (settings.requirePrincipal() && !wiring.sessionOwnerLookupSupplied) {
            violations.add("require-principal needs a SessionOwnerLookup");
        }
        if (settings.maxRunningPerTenant() < 1 && settings.maxRunningPerTenant() != SandboxSettings.UNLIMITED) {
            violations.add("admission.max-running-per-tenant must be at least 1, or 'unlimited' set explicitly");
        }
        if (!violations.isEmpty()) {
            throw new SandboxConfigurationException(violations);
        }
        warn(settings);
    }

    private static void checkTimings(SandboxSettings settings, List<String> violations) {
        final Map<String, Duration> positive = Map.ofEntries(
                Map.entry("provision-timeout", settings.provisionTimeout()),
                Map.entry("failure-backoff", settings.failureBackoff()),
                Map.entry("max-failure-backoff", settings.maxFailureBackoff()),
                Map.entry("activity-write-interval", settings.activityWriteInterval()),
                Map.entry("close-wait", settings.closeWait()),
                Map.entry("close-resume-after", settings.closeResumeAfter()),
                Map.entry("closed-retention", settings.closedRetention()),
                Map.entry("exec-shell-idle", settings.execShellIdle()),
                Map.entry("shell-lock-wait", settings.shellLockWait()),
                Map.entry("janitor.interval", settings.janitorInterval()),
                Map.entry("orphan-grace", settings.orphanGrace()),
                Map.entry("lost-confirm-after", settings.lostConfirmAfter()),
                Map.entry("workspace.close-after", settings.closeAfter()));
        positive.forEach((key, value) -> {
            if (value.isNegative() || value.isZero()) {
                violations.add(key + " must be positive, got " + value);
            }
        });
        // A sandbox whose record write is late must not look like an orphan: provisioning ends within provision-timeout
        // (a takeover after it), and the grace is measured from the sandbox's creation (§10.4).
        if (settings.orphanGrace().compareTo(settings.provisionTimeout()) <= 0) {
            violations.add("orphan-grace " + settings.orphanGrace() + " must exceed provision-timeout "
                    + settings.provisionTimeout() + ", or a slow provisioning's sandbox is reclaimed as an orphan");
        }
        if (settings.casRetries() < 0) {
            violations.add("cas-retries must not be negative");
        }
    }

    private static void checkProfile(SandboxProfile profile, ProviderCapabilities capabilities,
            List<String> violations) {
        final String p = "profile '" + profile.name() + "': ";
        final Optional<Duration> terminateAfter = profile.terminateAfter();
        if (terminateAfter.isEmpty()) {
            violations.add(p + "terminate-after is required — the provider expiry is the last line of defence");
        } else if (terminateAfter.get().isNegative() || terminateAfter.get().isZero()) {
            violations.add(p + "terminate-after must be positive, got " + terminateAfter.get());
        } else {
            capabilities.maxExpiry().filter(max -> terminateAfter.get().compareTo(max) > 0).ifPresent(max -> violations
                    .add(p + "terminate-after " + terminateAfter.get() + " exceeds the provider's max-expiry " + max));
        }
        if (profile.pauseAfter().isPresent()) {
            violations.add(p + "pause-after requires PAUSE_RESUME, which arrives with implementation step 7");
        }
        if (profile.sharedAccess() != SharedAccess.NONE) {
            violations.add(p + "shared-access " + profile.sharedAccess().name().toLowerCase(Locale.ROOT)
                    + " needs shared volumes, which arrive with implementation step 5");
        }
        if (profile.seed().isPresent()) {
            violations.add(p + "seed is not supported until implementation step 5");
        }
        checkCredentials(profile, capabilities, violations);
        profile.runtimeClass().ifPresent(requested -> {
            final Optional<String> served = capabilities.runtimeClass();
            final boolean waived = profile.insecureAllow().contains(Capability.RUNTIME_CLASS);
            if (served.isPresent() ? !served.get().equals(requested) : !waived) {
                violations.add(p + "runtime-class '" + requested + "' is not the provider's runtime class ("
                        + served.map(c -> "'" + c + "'").orElse("none declared")
                        + "): the server has one class for every sandbox");
            }
        });
        final Set<Capability> notWaivable = EnumSet.noneOf(Capability.class);
        notWaivable.addAll(profile.insecureAllow());
        notWaivable.removeAll(SandboxProfile.WAIVABLE);
        if (!notWaivable.isEmpty()) {
            violations.add(p + "insecure-allow may only waive " + SandboxProfile.WAIVABLE + ", not " + notWaivable);
        }
        if (profile.backgroundHeartbeatLimit().isNegative() || profile.backgroundHeartbeatLimit().isZero()) {
            violations.add(p + "background-heartbeat-limit must be positive");
        }
        final Set<Capability> missing = EnumSet.noneOf(Capability.class);
        missing.addAll(profile.requiredCapabilities());
        missing.removeAll(capabilities.advertised());
        if (!missing.isEmpty()) {
            violations.add(p + "the provider does not advertise " + missing + " (waive isolation capabilities for "
                    + "local development only, with insecure-allow)");
        }
    }

    private static void checkCredentials(SandboxProfile profile, ProviderCapabilities capabilities,
            List<String> violations) {
        if (profile.credentials().isEmpty()) {
            return;
        }
        final String p = "profile '" + profile.name() + "': ";
        final Map<String, CredentialScope> scopes = capabilities.credentialScopes();
        final List<String> known = new ArrayList<>();
        for (String name : profile.credentials()) {
            if (scopes.containsKey(name)) {
                known.add(name);
            } else {
                violations
                        .add(p + "unknown credential binding '" + name + "': the provider defines " + scopes.keySet());
            }
        }
        for (int i = 0; i < known.size(); i++) {
            for (int j = i + 1; j < known.size(); j++) {
                final CredentialScope a = scopes.get(known.get(i));
                final CredentialScope b = scopes.get(known.get(j));
                if (a.overlaps(b)) {
                    violations.add(p + "credential bindings '" + known.get(i) + "' and '" + known.get(j) + "' overlap ("
                            + a + " / " + b + "): the vault cannot tell which to inject");
                }
            }
        }
        final Optional<List<String>> egress = profile.egress();
        if (egress.isEmpty()) {
            violations.add(p + "credentials need an explicit egress allow-list: the vault injects only for hosts the "
                    + "egress policy allows, and no egress means no policy");
            return;
        }
        for (String name : known) {
            for (String host : scopes.get(name).hosts()) {
                if (!egressCovers(egress.get(), host)) {
                    violations.add(p + "credential binding '" + name + "' targets host '" + host + "', which egress "
                            + egress.get() + " does not allow");
                }
            }
        }
    }

    /**
     * Whether an egress allow-list covers a binding host the way the vault checks it: an equal entry, or an entry
     * {@code *.s} for a host under {@code s} (both measured against OpenSandbox; {@code *.s} does not cover {@code s}).
     */
    static boolean egressCovers(List<String> egress, String host) {
        for (String entry : egress) {
            final String allowed = entry.toLowerCase(Locale.ROOT);
            if (allowed.equals(host)) {
                return true;
            }
            if (allowed.startsWith("*.") && !host.startsWith("*.") && host.endsWith(allowed.substring(1))) {
                return true;
            }
        }
        return false;
    }

    private static void checkTerminateAfterSpansHeartbeats(SandboxProfile profile, Duration activityWriteInterval,
            List<String> violations) {
        if (activityWriteInterval.isNegative() || activityWriteInterval.isZero()) {
            return;
        }
        final Duration least = activityWriteInterval.multipliedBy(ACTIVITY_WRITES_PER_TERMINATE_AFTER);
        profile.terminateAfter()
                .filter(terminateAfter -> !terminateAfter.isNegative() && !terminateAfter.isZero()
                        && terminateAfter.compareTo(least) < 0)
                .ifPresent(terminateAfter -> violations.add(
                        "profile '" + profile.name() + "': terminate-after " + terminateAfter + " must be at least "
                                + ACTIVITY_WRITES_PER_TERMINATE_AFTER + " × activity-write-interval (" + least
                                + "), or a running command's sandbox can expire " + "between two heartbeats"));
    }

    private static void warn(SandboxSettings settings) {
        for (SandboxProfile profile : settings.profiles()) {
            if (!profile.insecureAllow().isEmpty()) {
                log.warn("Sandbox profile '{}' waives isolation capabilities {} (insecure-allow): use it for local "
                        + "development only", profile.name(), profile.insecureAllow());
            }
        }
        if (settings.requirePrincipal() && settings.maxRunningPerTenant() == SandboxSettings.UNLIMITED) {
            log.warn("Sandbox admission is unlimited in a multi-tenant deployment (require-principal is on): no "
                    + "tenant is limited in how many sandboxes it runs");
        }
    }
}
