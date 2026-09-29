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
import at.aimon.sandbox.provider.ProviderCapabilities;

/**
 * The startup checks of docs/design/workspace-sandbox.md §13.2 that implementation step 3 can make, plus the step's
 * own refusals. Collects every violation so one failed start lists them all; nothing is started when any is found.
 *
 * <p>
 * Step 3 refuses, rather than ignores, what it cannot do yet: {@code pause-after} (no {@code PAUSE_RESUME} until
 * step 7), {@code shared-access} other than {@code none} (step 5), any {@code seed} (git seeding arrives with step 5),
 * and any {@code credentials} (the §13.2 overlap check needs the step-4 provider's vault definitions, and an
 * unchecked binding must not start).
 */
final class SandboxStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SandboxStartupValidator.class);

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
                Map.entry("workspace.close-after", settings.closeAfter()));
        positive.forEach((key, value) -> {
            if (value.isNegative() || value.isZero()) {
                violations.add(key + " must be positive, got " + value);
            }
        });
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
        if (!profile.credentials().isEmpty()) {
            violations.add(p + "credential bindings arrive with implementation step 4 (their overlap check needs the "
                    + "provider's vault definitions)");
        }
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
