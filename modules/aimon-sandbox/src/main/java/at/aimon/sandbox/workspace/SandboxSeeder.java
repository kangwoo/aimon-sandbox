package at.aimon.sandbox.workspace;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import at.aimon.sandbox.profile.SandboxProfile;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxProviderException;

/**
 * The seed of implementation step 3 (docs/design/workspace-sandbox.md §11.3, §7): one exec, idempotent, that checks
 * the image against the image contract and the profile's declarations and lays out {@code /workspace}.
 *
 * <ul>
 * <li>{@code /workspace} is a writable directory, and the seed lock in it can be opened — the mount contract
 * (§13.3);</li>
 * <li>{@code command -v git rg flock sha256sum} — the image contract (§13.3);</li>
 * <li>{@code uname -s}, lower-cased, equals the declared platform; {@code uname -sr} matches the declared OS version
 * as a glob ({@code Linux 6.x} reads as {@code Linux 6.*}) when one is declared;</li>
 * <li>not root and no service-account token — skipped when {@code HARDENED_SECURITY_CONTEXT} is waived;</li>
 * <li>{@code rm -rf /workspace/.tmp-*}, then {@code mkdir -p} the staging and shell areas and the root.</li>
 * </ul>
 * Everything after the contract check runs under {@code flock} on {@code /workspace/.aimon-seed.lock}, held on an fd
 * like the shell lock. A failed check prints {@code AIMON_SEED_FAIL {step} {reason}} and exits 65: a permanent
 * failure. The control-plane network probe needs a provider endpoint and joins with implementation step 4; git seeds
 * with step 5.
 */
final class SandboxSeeder {

    static final int FAIL_EXIT = 65;
    private static final String FAIL_MARKER = "AIMON_SEED_FAIL ";
    private static final Pattern VERSION_X = Pattern.compile("(?<=\\.)x(?=$|\\.)");

    private final Duration provisionTimeout;

    SandboxSeeder(Duration provisionTimeout) {
        this.provisionTimeout = provisionTimeout;
    }

    /** A check that failed: permanent until the profile changes. */
    static final class Failure {
        private final String step;
        private final String reason;

        Failure(String step, String reason) {
            this.step = step;
            this.reason = reason;
        }

        String step() {
            return step;
        }

        String reason() {
            return reason;
        }
    }

    /**
     * @param connection
     *            the new sandbox
     * @param profile
     *            its profile
     * @param root
     *            the binding's root
     * @return the failed check, or empty when the seed completed
     * @throws SandboxProviderException
     *             (transient) when the seed could not run at all
     */
    Optional<Failure> seed(SandboxConnection connection, SandboxProfile profile, String root) {
        final RunningCommand command = connection.run(ExecSpec.builder().command(script(profile, root))
                .workingDirectory("/workspace").environment(profile.environment()).timeout(provisionTimeout)
                .maxCaptureBytes(64 * 1024).build(), OutputSink.DISCARD);
        final ExecOutcome outcome;
        try {
            outcome = command.await(provisionTimeout);
        } catch (InterruptedException e) {
            // RunningCommand.await: the seed keeps running unless the one who stops waiting kills it.
            command.kill();
            Thread.currentThread().interrupt();
            throw new SandboxProviderException("interrupted while seeding the sandbox");
        }
        final String stdout = new String(outcome.stdout(), StandardCharsets.UTF_8);
        if (outcome.exitCode() == 0) {
            return Optional.empty();
        }
        final int marker = stdout.lastIndexOf(FAIL_MARKER);
        if (outcome.exitCode() == FAIL_EXIT && marker >= 0) {
            final String line = stdout.substring(marker + FAIL_MARKER.length()).strip();
            final int space = line.indexOf(' ');
            return Optional.of(space < 0
                    ? new Failure(line, line)
                    : new Failure(line.substring(0, space), line.substring(space + 1)));
        }
        throw new SandboxProviderException(
                "the seed exited with " + outcome.exitCode() + (outcome.timedOut() ? " (timed out)" : "") + ": "
                        + new String(outcome.stderr(), StandardCharsets.UTF_8).strip());
    }

    String script(SandboxProfile profile, String root) {
        final StringBuilder s = new StringBuilder();
        s.append("fail() { printf '").append(FAIL_MARKER).append("%s %s\\n' \"$1\" \"$2\"; exit ").append(FAIL_EXIT)
                .append("; }\n");
        // The mount contract (§13.3): without it the lock below cannot even be opened.
        s.append("[ -d /workspace ] && [ -w /workspace ] || fail workspace '/workspace is missing or not writable'\n");
        s.append("for t in git rg flock sha256sum; do command -v \"$t\" >/dev/null 2>&1 || fail image-contract "
                + "\"the image lacks $t (it must provide git, rg, flock and sha256sum)\"; done\n");
        // bash (not in POSIX mode) carries on after a failed exec redirection; flock would then report the missing
        // fd as lock contention, a transient failure retried forever.
        s.append("exec 8>/workspace/.aimon-seed.lock || fail workspace 'cannot open /workspace/.aimon-seed.lock'\n");
        s.append("flock -w ").append(Math.max(1, provisionTimeout.toSeconds()))
                .append(" 8 || { echo 'another seed of this sandbox did not finish' >&2; exit 75; }\n");
        // Declared values are data: single-quoted into variables, never pasted into double-quoted text.
        s.append("declared_platform=").append(quote(profile.platform())).append('\n');
        s.append("p=$(uname -s | tr '[:upper:]' '[:lower:]')\n");
        s.append("[ \"$p\" = \"$declared_platform\" ] || fail platform \"the profile declares $declared_platform "
                + "but the image runs $p\"\n");
        profile.osVersion().filter(declared -> !declared.isBlank())
                .ifPresent(declared -> s.append("declared_os=").append(quote(declared))
                        .append("\nv=$(uname -sr)\ncase \"$v\" in ").append(glob(declared))
                        .append(") ;; *) fail os-version \"the profile declares $declared_os but the "
                                + "image runs $v\";; esac\n"));
        if (!profile.insecureAllow().contains(Capability.HARDENED_SECURITY_CONTEXT)) {
            s.append("[ \"$(id -u)\" != 0 ] || fail root 'the sandbox runs as root'\n");
            s.append("[ ! -e /var/run/secrets/kubernetes.io/serviceaccount/token ] || fail service-account-token "
                    + "'a service account token is mounted'\n");
        }
        s.append("rm -rf /workspace/.tmp-*\n");
        s.append("mkdir -p /workspace/.aimon-staged /workspace/.aimon-shell ").append(quote(root))
                .append(" || fail root-directory 'cannot create the working root'\n");
        return s.toString();
    }

    /** A declared version as a {@code case} pattern: literal parts single-quoted, {@code x} segments as {@code *}. */
    static String glob(String declared) {
        final String withStars = VERSION_X.matcher(declared).replaceAll("*");
        final StringBuilder out = new StringBuilder();
        final String[] parts = withStars.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('*');
            }
            if (!parts[i].isEmpty()) {
                out.append(quote(parts[i]));
            }
        }
        return out.toString();
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
