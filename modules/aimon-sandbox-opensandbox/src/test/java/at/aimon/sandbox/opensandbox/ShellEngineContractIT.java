package at.aimon.sandbox.opensandbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Core's engine contract suite with core's Python shell engine on a real OpenSandbox sandbox (shell doc §14;
 * docs/design/ptc-shell-provider.md §5.7): the real execd, the real {@code /workspace}, the tier's image with
 * {@code python3}. It exercises the seed's {@code runtime-python3} check, isolated streamed runs whose run directory
 * carries a secret file (the S4 upload path), the runtime file system over execd, the spool removal of S1 and the
 * deletion of each run's directory. Not the engine's offer-time availability or its probe: the suite calls
 * {@code execute} directly — {@link ShellEngineAvailabilityIT} covers those.
 *
 * <p>
 * The engine sees the sandbox's environment unchanged. Core's runtime ends with the shell's process-group kill (its
 * anchor stays in the exec's group; a watchdog kills the runtime's own group when the anchor dies — aimon-core#336),
 * which is all this module's kill reaches (docs/design/workspace-sandbox.md §9). What that leaves in the sandbox is
 * checked after every test ({@link AbstractShellEngineSandboxIT}): nothing. {@link LeftoverCheckNegativeControlIT}
 * shows that check can fail.
 */
@Tag("docker")
class ShellEngineContractIT extends AbstractShellEngineSandboxIT {

    private static SharedSandbox sandbox;

    @BeforeAll
    static void start() throws Exception {
        sandbox = SharedSandbox.started();
    }

    @AfterAll
    static void stop() {
        if (sandbox != null) {
            sandbox.close();
        }
    }

    @Override
    protected SharedSandbox sandbox() {
        return sandbox;
    }
}
