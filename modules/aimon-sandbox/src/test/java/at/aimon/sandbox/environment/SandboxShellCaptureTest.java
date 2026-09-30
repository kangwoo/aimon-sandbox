package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The provider-side capture backstop of a wrapper run (§6.1: output may be line-normalized). */
class SandboxShellCaptureTest {

    @Test
    void theBackstopLeavesRoomForU00fffdExpansionAndTheTrailer() {
        // head -c caps raw bytes; each invalid byte can arrive as three, and the trailer must still fit after them.
        assertThat(SandboxShell.captureBackstop(1024 * 1024)).isEqualTo(3L * 1024 * 1024 + 1024);
        assertThat(SandboxShell.captureBackstop(0)).isEqualTo(1024);
    }

    @Test
    void aHugeCapSaturatesInsteadOfOverflowing() {
        assertThat(SandboxShell.captureBackstop(Long.MAX_VALUE / 3)).isEqualTo(Long.MAX_VALUE);
        assertThat(SandboxShell.captureBackstop(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }
}
