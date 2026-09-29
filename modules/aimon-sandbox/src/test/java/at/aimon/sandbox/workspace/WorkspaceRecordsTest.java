package at.aimon.sandbox.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.ProviderVolume;
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.provider.VolumeMount;
import at.aimon.sandbox.provider.VolumeRef;

/** The immutable records: copies, value equality (what the CAS store compares), and their invariants. */
class WorkspaceRecordsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static SandboxSlot slot() {
        return SandboxSlot.builder().name("primary").profile("standard").profileHash("h").state(SlotState.RUNNING)
                .generation(2).provisioning(ProvisioningClaim.of(T0, "node"))
                .providerRef(ProviderSandboxRef.of("p", "s1")).seeded(true).missingSince(T0).lastActivityAt(T0)
                .lastActiveAt(T0).lostAt(T0).failure(SlotFailure.builder().at(T0).kind(SlotFailure.Kind.TRANSIENT)
                        .step("create").reason("5xx").attempts(2).profileHash("h").build())
                .build();
    }

    private static SandboxWorkspace workspace() {
        return SandboxWorkspace.builder().id(SandboxWorkspaceId.of("ws:a"))
                .owner(WorkspaceOwner.of(TenantId.of("t"), "u")).incarnation("inc").sharedVolume(VolumeRef.of("v"))
                .stateSince(T0).closeCause(CloseCause.IDLE).retainVolumeUntil(T0)
                .retainedVolumes(List.of(RetainedVolume.of("old", VolumeRef.of("v0"), T0)))
                .quota(WorkspaceQuota.of(4, 2, "8", "16Gi")).createdAt(T0).lastActivityAt(T0).build().withSlot(slot());
    }

    @Test
    void copiesAreEqualAndEveryFieldTakesPartInEquality() {
        final SandboxWorkspace workspace = workspace();

        assertThat(workspace.toBuilder().build()).isEqualTo(workspace).hasSameHashCodeAs(workspace);
        assertThat(workspace.toBuilder().incarnation("other").build()).isNotEqualTo(workspace);
        assertThat(workspace.withSlot(slot().withState(SlotState.TERMINATED))).isNotEqualTo(workspace);
        assertThat(slot().toBuilder().build()).isEqualTo(slot()).hasSameHashCodeAs(slot());
        assertThat(slot().toBuilder().seeded(false).build()).isNotEqualTo(slot());
        assertThat(workspace.toString()).contains("ws:a").contains("primary");
        assertThat(slot().toString()).contains("transient failure at step 'create' (attempt 2): 5xx");
        assertThat(workspace.retainedVolumes().get(0)).isEqualTo(RetainedVolume.of("old", VolumeRef.of("v0"), T0));
        assertThat(workspace.quota()).isEqualTo(WorkspaceQuota.of(4, 2, "8", "16Gi"));
        assertThat(workspace.quota().maxCpu()).contains("8");
        assertThat(workspace.sharedVolume()).contains(VolumeRef.of("v"));
    }

    @Test
    void activityTimesNeverMoveBackwards() {
        final SandboxSlot slot = slot();

        assertThat(slot.withLastActivityAt(T0.minusSeconds(5))).isSameAs(slot);
        assertThat(slot.withLastActivityAt(T0.plusSeconds(5)).lastActivityAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(workspace().withLastActivityAt(T0.minusSeconds(1)).lastActivityAt()).isEqualTo(T0);
    }

    @Test
    void terminatingStampsLastActiveOnlyWhenLeavingALiveState() {
        final Instant later = T0.plus(Duration.ofHours(1));

        assertThat(slot().terminated(later).lastActiveAt()).contains(later);
        assertThat(slot().terminated(later).provisioning()).isEmpty();
        assertThat(slot().withState(SlotState.FAILED).terminated(later).lastActiveAt()).contains(T0);
        assertThat(slot().live()).isTrue();
        assertThat(slot().withState(SlotState.FAILED).live()).isFalse();
    }

    @Test
    void idsAndOwnersAreValues() {
        assertThat(SandboxWorkspaceId.of("a")).isEqualTo(SandboxWorkspaceId.of("a"))
                .isLessThan(SandboxWorkspaceId.of("b"));
        assertThat(WorkspaceOwner.of(TenantId.of("t"), "u")).isEqualTo(WorkspaceOwner.of(TenantId.of("t"), "u"))
                .hasToString("t/u");
        assertThat(ProvisioningClaim.of(T0, "n")).isEqualTo(ProvisioningClaim.of(T0, "n")).hasToString("n@" + T0);
        assertThatThrownBy(() -> SandboxWorkspaceId.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceQuota.of(0, 1, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scanQueriesCarryTheirFiltersToTheNextPage() {
        final WorkspaceScan scan = WorkspaceScan.builder().states(Set.of(WorkspaceState.OPEN)).tenant(TenantId.of("t"))
                .lastActivityBefore(T0).stateSinceBefore(T0).limit(5).build();

        final WorkspaceScan next = scan.next(SandboxWorkspaceId.of("ws:m"));

        assertThat(next.states()).containsExactly(WorkspaceState.OPEN);
        assertThat(next.tenant()).contains(TenantId.of("t"));
        assertThat(next.lastActivityBefore()).contains(T0);
        assertThat(next.stateSinceBefore()).contains(T0);
        assertThat(next.afterId()).contains(SandboxWorkspaceId.of("ws:m"));
        assertThat(next.limit()).isEqualTo(5);
        assertThatThrownBy(() -> WorkspaceScan.builder().limit(0).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerValueTypesKeepWhatTheyAreGiven() {
        final CreateSpec spec = CreateSpec.builder().key("k").image("i").platform("linux")
                .resources(ResourceSpec.of("2", "4Gi", "20Gi", 512)).runtimeClass("gvisor").egress(List.of())
                .credentials(List.of("c")).environment(Map.of("A", "1")).labels(Map.of("l", "v")).expiresAt(T0)
                .volumes(List.of(VolumeMount.of(VolumeRef.of("v"), "/shared", true))).build();

        assertThat(spec.platform()).contains("linux");
        assertThat(spec.resources()).isEqualTo(ResourceSpec.of("2", "4Gi", "20Gi", 512));
        assertThat(spec.resources().pids()).contains(512);
        assertThat(spec.runtimeClass()).contains("gvisor");
        assertThat(spec.egress()).contains(List.of());
        assertThat(spec.volumes().get(0)).hasToString("v:/shared:ro");
        assertThat(spec.toString()).contains("k");
        final ProviderVolume volume = ProviderVolume.of(VolumeRef.of("v"), Map.of(), true);
        assertThat(volume.mounted()).isTrue();
        assertThat(volume.ref().name()).isEqualTo("v");
        assertThat(ProviderSandbox.of(ProviderSandboxRef.of("p", "s"), ProviderSandboxState.RUNNING, Map.of(), null)
                .expiresAt()).isEmpty();
        assertThat(FileStat.of("/a", 1, T0, false, "e").toString()).contains("/a");
        assertThat(ExecOutcome.builder().exitCode(3).stdout(new byte[]{1}).build().toString()).contains("exitCode=3");
        assertThat(AdmissionDecision.rejected("full").rejection()).contains("full");
        assertThat(AdmissionDecision.admitted().isAdmitted()).isTrue();
    }
}
