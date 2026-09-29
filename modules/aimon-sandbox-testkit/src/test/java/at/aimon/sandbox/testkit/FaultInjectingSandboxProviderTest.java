package at.aimon.sandbox.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.provider.WriteMode;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;

class FaultInjectingSandboxProviderTest {

    private final LocalProcessSandboxProvider local = LocalProcessSandboxProvider.builder().build();
    private final FaultInjectingSandboxProvider faults = new FaultInjectingSandboxProvider(local);

    @AfterEach
    void close() {
        faults.close();
        local.close();
    }

    private static CreateSpec spec(String key) {
        return CreateSpec.builder().key(key).image("local").labels(Map.of("aimon.at/sandbox-key", key))
                .expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    @Test
    void injectsFailuresOfEachKindAndCountsCalls() {
        faults.injectOnce(Operation.CREATE, Fault.fail(SandboxProviderException.Kind.PERMANENT));
        faults.injectOnce(Operation.CREATE, Fault.timeout());

        assertThatThrownBy(() -> faults.create(spec("a"))).isInstanceOfSatisfying(SandboxProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT));
        assertThatThrownBy(() -> faults.create(spec("a"))).isInstanceOfSatisfying(SandboxProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT));
        final ProviderSandboxRef ref = faults.create(spec("a"));

        assertThat(ref).isNotNull();
        assertThat(faults.calls(Operation.CREATE)).isEqualTo(3);
    }

    @Test
    void aLostResponseStillPerformsTheCall() {
        faults.injectOnce(Operation.CREATE, Fault.succeedButLoseResponse());

        assertThatThrownBy(() -> faults.create(spec("b"))).isInstanceOf(SandboxProviderException.class)
                .hasMessageContaining("lost response");
        assertThat(local.sandboxCount()).isEqualTo(1);
    }

    @Test
    void faultsCanTargetOneCallNumberAndBeCleared() {
        final ProviderSandboxRef ref = faults.create(spec("c"));
        faults.injectAt(Operation.EXTEND_EXPIRY, 2, Fault.notFound());

        faults.extendExpiry(ref, Instant.now().plusSeconds(7200));
        assertThatThrownBy(() -> faults.extendExpiry(ref, Instant.now().plusSeconds(7200)))
                .isInstanceOf(SandboxNotFoundException.class);
        faults.extendExpiry(ref, Instant.now().plusSeconds(7200));

        faults.inject(Operation.STATUS, Fault.crash());
        assertThatThrownBy(() -> faults.status(ref)).isInstanceOf(FaultInjectingSandboxProvider.SimulatedCrash.class);
        faults.clear();
        assertThat(faults.status(ref)).isPresent();
        faults.resetCounts();
        assertThat(faults.calls(Operation.STATUS)).isZero();
    }

    @Test
    void connectionsAndFilesAreWrappedToo() throws Exception {
        final SandboxConnection connection = faults.connect(faults.create(spec("d")));
        faults.inject(Operation.FILES_WRITE, Fault.delay(Duration.ofMillis(50)));

        connection.files().write("/workspace/x", new ByteArrayInputStream(new byte[]{1}), 1,
                WriteMode.CREATE_OR_REPLACE);
        connection.files().stat("/workspace/x");
        connection.files().list("/workspace", false, 10);
        connection.files().createDirectories("/workspace/d");
        connection.files().move("/workspace/x", "/workspace/y", false);
        connection.files().read("/workspace/y", 0, -1).close();
        connection.files().delete("/workspace/y", false);
        connection.run(ExecSpec.builder().command("true").build(), OutputSink.DISCARD).await(Duration.ofSeconds(10));

        for (Operation operation : new Operation[]{Operation.CONNECT, Operation.FILES_WRITE, Operation.FILES_STAT,
                Operation.FILES_LIST, Operation.FILES_MKDIR, Operation.FILES_MOVE, Operation.FILES_READ,
                Operation.FILES_DELETE, Operation.RUN}) {
            assertThat(faults.calls(operation)).as(operation.name()).isEqualTo(1);
        }
        assertThat(faults.capabilities()).isEqualTo(local.capabilities());
        assertThat(faults.sharedVolumes()).isEmpty();
        assertThatThrownBy(() -> faults.pause(ProviderSandboxRef.of("local", "z")))
                .isInstanceOf(UnsupportedOperationException.class);
        connection.close();
    }

    @Test
    void aOneShotFaultWinsOverAStandingOneAddedBefore() {
        final ProviderSandboxRef ref = faults.create(spec("e"));
        faults.inject(Operation.STATUS, Fault.delay(Duration.ofMillis(1)));
        faults.injectOnce(Operation.STATUS, Fault.notFound());
        faults.injectAt(Operation.STATUS, 3, Fault.fail(SandboxProviderException.Kind.PERMANENT));

        assertThatThrownBy(() -> faults.status(ref)).isInstanceOf(SandboxNotFoundException.class);
        assertThat(faults.status(ref)).isPresent();
        assertThatThrownBy(() -> faults.status(ref)).isInstanceOf(SandboxProviderException.class)
                .hasMessageContaining("PERMANENT");
        assertThat(faults.status(ref)).isPresent();
    }

    @Test
    void injectAtCountsFromTheLastReset() {
        final ProviderSandboxRef ref = faults.create(spec("f"));
        faults.status(ref);
        faults.status(ref);
        faults.resetCounts();
        faults.injectAt(Operation.STATUS, 1, Fault.notFound());

        assertThatThrownBy(() -> faults.status(ref)).isInstanceOf(SandboxNotFoundException.class);
        assertThat(faults.calls(Operation.STATUS)).isEqualTo(1);
    }
}
