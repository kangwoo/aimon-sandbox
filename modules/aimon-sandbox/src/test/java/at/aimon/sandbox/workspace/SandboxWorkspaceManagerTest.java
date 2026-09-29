package at.aimon.sandbox.workspace;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static at.aimon.sandbox.SandboxHarness.bash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.sandbox.DelegatingProvider;
import at.aimon.sandbox.SandboxHarness;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.testkit.SandboxTestProfiles;

/** {@link SandboxWorkspaceManager}: failure classification and backoff, labels, seed checks, takeover (§10.1). */
class SandboxWorkspaceManagerTest {

    private SandboxHarness harness = SandboxHarness.standard();

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxWorkspaceManager manager() {
        return harness.sandbox.manager();
    }

    @Test
    void backoffDoublesFromFailureBackoffUpToTheCap() {
        assertThat(manager().backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(manager().backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(manager().backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(manager().backoff(5)).isEqualTo(Duration.ofMinutes(15));
        assertThat(manager().backoff(40)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aTransientCreateFailureIsRetriedOnlyAfterBackoffWithANewGeneration() throws Exception {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        assertThatThrownBy(() -> bash(env, "true")).hasMessageContaining("could not be provisioned")
                .hasMessageContaining("[step create]").hasMessageContaining("next attempt");
        final SandboxSlot failed = harness.primary(session);
        assertThat(failed.state()).isEqualTo(SlotState.FAILED);
        assertThat(failed.failure().orElseThrow().kind()).isEqualTo(SlotFailure.Kind.TRANSIENT);
        assertThat(failed.lastActiveAt()).isPresent();

        final int creates = harness.faults.calls(Operation.CREATE);
        assertThatThrownBy(() -> bash(env, "true")).hasMessageContaining("could not be provisioned");
        assertThat(harness.faults.calls(Operation.CREATE)).as("no retry within the backoff").isEqualTo(creates);

        harness.clock.advance(Duration.ofMinutes(1));
        assertThat(bash(env, "echo retried").stdout()).isEqualTo("retried\n");
        assertThat(harness.primary(session).generation()).isEqualTo(2);
        assertThat(harness.primary(session).failure()).isEmpty();
    }

    @Test
    void consecutiveTransientFailuresCountAttempts() {
        final SessionId session = SessionId.generate();
        final ExecutionEnvironment env = harness.mainTurn(session, ALICE);
        harness.faults.inject(Operation.CREATE, Fault.timeout());

        assertThatThrownBy(() -> bash(env, "true")).isInstanceOf(SandboxUnavailableException.class);
        harness.clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> bash(env, "true")).isInstanceOf(SandboxUnavailableException.class);

        assertThat(harness.primary(session).failure().orElseThrow().attempts()).isEqualTo(2);
        assertThat(harness.primary(session).generation()).isEqualTo(2);
    }

    @Test
    void aPermanentFailureWaitsForTheProfileToChange() throws Exception {
        final SessionId session = SessionId.generate();
        harness.faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.PERMANENT));
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                .hasMessageContaining("not retried until the profile changes");

        harness.clock.advance(Duration.ofHours(1));
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                .hasMessageContaining("not retried until the profile changes");

        try (SandboxHarness changed = SandboxHarness.builder().store(harness.store).local(harness.local)
                .profile(SandboxTestProfiles.local("standard").environment(Map.of("FIXED", "1")).build()).build()) {
            changed.clock.set(harness.clock.instant());
            assertThat(bash(changed.mainTurn(session, ALICE), "echo $FIXED").stdout()).isEqualTo("1\n");
        }
    }

    @Test
    void aLostCreateResponseFailsTransientlyAndLeavesTheSandboxToExpiry() {
        final SessionId session = SessionId.generate();
        harness.faults.injectOnce(Operation.CREATE, Fault.succeedButLoseResponse());

        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                .isInstanceOf(SandboxUnavailableException.class);

        assertThat(harness.primary(session).failure().orElseThrow().kind()).isEqualTo(SlotFailure.Kind.TRANSIENT);
        assertThat(harness.local.sandboxCount()).as("reconciliation arrives with step 4").isEqualTo(1);
    }

    @Test
    void aSandboxWhoseLabelsDoNotMatchIsNeverUsed() {
        harness.close();
        harness = SandboxHarness.builder().decorate((provider, clock) -> new DelegatingProvider(provider) {
            @Override
            public Optional<ProviderSandbox> status(ProviderSandboxRef ref) {
                return super.status(ref).map(sandbox -> {
                    final Map<String, String> labels = new HashMap<>(sandbox.labels());
                    labels.put(SandboxLabels.OWNER, SandboxLabels.h("someone-else"));
                    return ProviderSandbox.of(sandbox.ref(), sandbox.state(), labels, sandbox.expiresAt().orElse(null));
                });
            }
        }).build();
        final SessionId session = SessionId.generate();

        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true")).hasMessageContaining("[step labels]");

        final SlotFailure failure = harness.primary(session).failure().orElseThrow();
        assertThat(failure.kind()).isEqualTo(SlotFailure.Kind.PERMANENT);
        assertThat(failure.reason()).contains(SandboxLabels.OWNER);
        assertThat(harness.local.sandboxCount()).as("never destroyed: it may be someone else's").isEqualTo(1);
    }

    @Test
    void aSeedCheckFailureIsPermanentAndTheSandboxIsDestroyed() {
        harness.close();
        harness = SandboxHarness.builder().profile(SandboxTestProfiles.local("standard").platform("plan9").build())
                .build();
        final SessionId session = SessionId.generate();

        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true")).hasMessageContaining("declares plan9")
                .hasMessageContaining("[step platform]");

        assertThat(harness.primary(session).failure().orElseThrow().kind()).isEqualTo(SlotFailure.Kind.PERMANENT);
        assertThat(harness.local.sandboxCount()).isZero();
    }

    @Test
    void aDeclaredOsVersionThatDoesNotMatchFailsTheSeed() {
        harness.close();
        harness = SandboxHarness.builder().profile(SandboxTestProfiles.local("standard").osVersion("Plan9 1.x").build())
                .build();

        assertThatThrownBy(() -> bash(harness.mainTurn(SessionId.generate(), ALICE), "true"))
                .hasMessageContaining("[step os-version]");
    }

    @Test
    void aStuckProvisioningClaimIsTakenOverAfterProvisionTimeout() throws Exception {
        final SessionId session = SessionId.generate();
        final Instant now = harness.clock.instant();
        harness.store
                .createIfAbsent(stuck(session, ProvisioningClaim.of(now.minus(Duration.ofMinutes(6)), "dead-node")));

        final ShellCommandResult result = bash(harness.mainTurn(session, ALICE), "echo taken");

        assertThat(result.stdout()).isEqualTo("taken\n");
        assertThat(harness.primary(session).generation()).isEqualTo(1);
        assertThat(harness.primary(session).state()).isEqualTo(SlotState.RUNNING);
    }

    @Test
    void aFreshProvisioningClaimOfAnotherNodeIsWaitedForThenReported() {
        harness.close();
        harness = SandboxHarness.builder().settings(s -> s.provisionTimeout(Duration.ofSeconds(1))).build();
        final SessionId session = SessionId.generate();
        harness.store.createIfAbsent(stuck(session, ProvisioningClaim.of(harness.clock.instant(), "busy-node")));

        final long started = System.nanoTime();
        assertThatThrownBy(() -> bash(harness.mainTurn(session, ALICE), "true"))
                .hasMessageContaining("another execution is provisioning");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(harness.faults.calls(Operation.CREATE)).isZero();
    }

    @Test
    void slowProvisioningAddsANotice() throws Exception {
        harness.close();
        harness = SandboxHarness.builder().decorate((provider, clock) -> new DelegatingProvider(provider) {
            @Override
            public ProviderSandboxRef create(CreateSpec spec) {
                clock.advance(Duration.ofSeconds(7));
                return super.create(spec);
            }
        }).build();

        final ShellCommandResult result = bash(harness.mainTurn(SessionId.generate(), ALICE), "true");

        assertThat(result.notices()).contains("sandbox provisioned in 7s");
    }

    @Test
    void aSlotWhoseProfileWasRemovedIsReported() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");

        try (SandboxHarness redeployed = SandboxHarness.builder().store(harness.store).local(harness.local)
                .profile(SandboxTestProfiles.local("other").build()).build()) {
            assertThatThrownBy(() -> bash(redeployed.mainTurn(session, ALICE), "true"))
                    .hasMessageContaining("'standard', which is no longer configured");
        }
    }

    @Test
    void eventsCarryTheWorkspaceSlotAndOwner() throws Exception {
        final SessionId session = SessionId.generate();
        bash(harness.mainTurn(session, ALICE), "true");

        final SandboxEvent provisioned = harness.events.get(0);
        assertThat(provisioned.type()).isEqualTo(SandboxEvent.Type.PROVISIONED);
        assertThat(provisioned.slot()).contains("primary");
        assertThat(provisioned.generation()).contains(1L);
        assertThat(provisioned.profile()).contains("standard");
        assertThat(provisioned.owner().principalId()).isEqualTo("alice");
    }

    private SandboxWorkspace stuck(SessionId session, ProvisioningClaim claim) {
        final Instant now = harness.clock.instant();
        final String hash = harness.sandbox.profiles().find("standard").orElseThrow().contentHash();
        return SandboxWorkspace.builder().id(SandboxWorkspaceId.of("ws:" + session.value()))
                .owner(WorkspaceOwner.of(TenantId.DEFAULT, "alice")).incarnation("stuck001").stateSince(now)
                .createdAt(now).lastActivityAt(now)
                .slots(Map.of("primary", SandboxSlot.builder().name("primary").profile("standard").profileHash(hash)
                        .state(SlotState.PROVISIONING).generation(1).provisioning(claim).lastActivityAt(now).build()))
                .build();
    }
}
