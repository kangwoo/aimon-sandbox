package at.aimon.sandbox.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.base.Principal;

/**
 * {@link SessionOwnerLookup#recorded} and {@link SessionOwnerLookup#orElse} (kangwoo/aimon-core#253,
 * kangwoo/aimon-core#262).
 */
class SessionOwnerLookupTest {

    private static final Principal ALICE = Principal.user("alice");
    private static final Principal BOB = Principal.user("bob");

    private final InMemorySessionRecordStore records = new InMemorySessionRecordStore();

    @Test
    @DisplayName("core-033 §5.2: the owner core recorded at provisioning; empty without one or without a record")
    void recordedAnswersTheProvisionedOwner() {
        final SessionId owned = SessionId.generate();
        final SessionId ownerless = SessionId.generate();
        records.provision(owned, "agent", ALICE);
        records.provision(ownerless, "agent");

        final SessionOwnerLookup lookup = SessionOwnerLookup.recorded(records);

        assertThat(lookup.ownerOf(owned)).contains(ALICE);
        assertThat(lookup.ownerOf(ownerless)).isEmpty();
        assertThat(lookup.ownerOf(SessionId.generate())).isEmpty();
    }

    @Test
    @DisplayName("core SO-5: the owner is read alone, never through a load of the whole record")
    void recordedReadsTheOwnerWithoutLoadingTheRecord() {
        final InMemorySessionRecordStore narrowOnly = new InMemorySessionRecordStore() {
            @Override
            public Optional<SessionRecordView> load(SessionId sessionId) {
                throw new AssertionError("the owner lookup loaded the whole record");
            }
        };
        final SessionId owned = SessionId.generate();
        narrowOnly.provision(owned, "agent", ALICE);

        final SessionOwnerLookup lookup = SessionOwnerLookup.recorded(narrowOnly);

        assertThat(lookup.ownerOf(owned)).contains(ALICE);
        assertThat(lookup.ownerOf(SessionId.generate())).isEmpty();
    }

    @Test
    void orElseAsksTheFallbackOnlyWhenThisLookupIsEmpty() {
        final SessionId owned = SessionId.generate();
        records.provision(owned, "agent", ALICE);
        final AtomicInteger asked = new AtomicInteger();

        final SessionOwnerLookup lookup = SessionOwnerLookup.recorded(records).orElse(id -> {
            asked.incrementAndGet();
            return Optional.of(BOB);
        });

        assertThat(lookup.ownerOf(owned)).contains(ALICE);
        assertThat(asked).hasValue(0);
        assertThat(lookup.ownerOf(SessionId.generate())).contains(BOB);
        assertThat(asked).hasValue(1);
    }

    @Test
    void theSupplierFormResolvesTheStoreAtEachCall() {
        final AtomicReference<SessionRecordStore> late = new AtomicReference<>();
        final SessionOwnerLookup lookup = SessionOwnerLookup.recorded(late::get);
        final SessionId owned = SessionId.generate();
        records.provision(owned, "agent", ALICE);

        assertThatThrownBy(() -> lookup.ownerOf(owned)).hasMessageContaining("not available yet");
        late.set(records);
        assertThat(lookup.ownerOf(owned)).contains(ALICE);
    }
}
