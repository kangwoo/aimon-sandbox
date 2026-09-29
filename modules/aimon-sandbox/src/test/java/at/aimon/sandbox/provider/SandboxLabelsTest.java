package at.aimon.sandbox.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SandboxLabelsTest {

    @Test
    void hashIsThirtyTwoLowerCaseBase32CharactersAndDeterministic() {
        final String h = SandboxLabels.h("ws:session/with:colons");

        assertThat(h).hasSize(32).matches("[a-z2-7]{32}");
        assertThat(SandboxLabels.h("ws:session/with:colons")).isEqualTo(h);
        assertThat(SandboxLabels.h("ws:other")).isNotEqualTo(h);
        assertThat(SandboxLabels.h("")).isEqualTo("4oymiquy7qobjgx36tejs35zeqt24qpe");
    }

    @Test
    void keyAndLabelsFollowSection63() {
        final Map<String, String> labels = SandboxLabels.labels("prod", "ws:s1", "inc12345", "primary", 3, "acme");

        assertThat(SandboxLabels.key("prod", "ws:s1", "inc12345", "primary", 3))
                .isEqualTo("prod/ws:s1/inc12345/primary/3");
        assertThat(labels).containsEntry(SandboxLabels.MANAGED, "true").containsEntry(SandboxLabels.DEPLOYMENT, "prod")
                .containsEntry(SandboxLabels.WORKSPACE, SandboxLabels.h("ws:s1"))
                .containsEntry(SandboxLabels.SANDBOX_KEY, SandboxLabels.h("prod/ws:s1/inc12345/primary/3"))
                .containsEntry(SandboxLabels.INCARNATION, "inc12345").containsEntry(SandboxLabels.SLOT, "primary")
                .containsEntry(SandboxLabels.GENERATION, "3")
                .containsEntry(SandboxLabels.OWNER, SandboxLabels.h("acme"));
        assertThat(labels.values()).allMatch(value -> value.matches("[a-z0-9]([-a-z0-9_.]{0,61}[a-z0-9])?"));
        assertThat(SandboxLabels.workspaceSelector("prod", "ws:s1")).hasSize(3).containsEntry(SandboxLabels.WORKSPACE,
                SandboxLabels.h("ws:s1"));
    }

    @Test
    void mismatchesNameOnlyTheVerifiedLabelsThatDiffer() {
        final Map<String, String> expected = SandboxLabels.labels("prod", "ws:s1", "inc", "primary", 1, "acme");
        final Map<String, String> actual = new HashMap<>(expected);
        actual.put(SandboxLabels.GENERATION, "2");
        actual.put(SandboxLabels.SLOT, "renamed");

        assertThat(SandboxLabels.mismatches(expected, expected)).isEmpty();
        assertThat(SandboxLabels.mismatches(expected, actual)).containsExactly(SandboxLabels.GENERATION);
    }
}
