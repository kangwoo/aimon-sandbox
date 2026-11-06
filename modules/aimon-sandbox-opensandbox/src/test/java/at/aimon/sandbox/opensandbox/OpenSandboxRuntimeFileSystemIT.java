package at.aimon.sandbox.opensandbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.sandbox.testkit.RuntimeFileSystemContract;

/**
 * The runtime file system's contract on a real OpenSandbox server (docs/design/ptc-shell-provider.md §6.2): execd's
 * files API for {@code writeNew} (upload, then its {@code ln} helper) and {@code createDirectories}, the in-sandbox
 * helpers for {@code read} and {@code delete}. The sandbox runs as root here, so the unreadable-file case is skipped.
 */
@Tag("docker")
class OpenSandboxRuntimeFileSystemIT extends RuntimeFileSystemContract {

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
    protected ExecutionEnvironment environment() {
        return sandbox.environment;
    }
}
