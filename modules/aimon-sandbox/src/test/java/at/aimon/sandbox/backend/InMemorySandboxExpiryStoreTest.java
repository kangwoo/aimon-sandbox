package at.aimon.sandbox.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemorySandboxExpiryStoreTest {

    private InMemorySandboxExpiryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySandboxExpiryStore();
    }

    @Test
    void put_And_Get_ReturnsStoredValue() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        store.put("my-sandbox", expiresAt);

        assertThat(store.get("my-sandbox")).isPresent().hasValue(expiresAt);
    }

    @Test
    void get_NotFound_ReturnsEmpty() {
        assertThat(store.get("nonexistent")).isEmpty();
    }

    @Test
    void put_OverwritesExistingValue() {
        Instant first = Instant.now().plusSeconds(1800);
        Instant second = Instant.now().plusSeconds(3600);

        store.put("my-sandbox", first);
        store.put("my-sandbox", second);

        assertThat(store.get("my-sandbox")).isPresent().hasValue(second);
    }

    @Test
    void remove_ExistingEntry_RemovesIt() {
        store.put("my-sandbox", Instant.now().plusSeconds(3600));
        store.remove("my-sandbox");

        assertThat(store.get("my-sandbox")).isEmpty();
    }

    @Test
    void remove_NonexistentEntry_DoesNotThrow() {
        store.remove("nonexistent");
    }

    @Test
    void multipleEntries_IndependentLifecycles() {
        Instant exp1 = Instant.now().plusSeconds(1000);
        Instant exp2 = Instant.now().plusSeconds(2000);

        store.put("sandbox-1", exp1);
        store.put("sandbox-2", exp2);

        store.remove("sandbox-1");

        assertThat(store.get("sandbox-1")).isEmpty();
        assertThat(store.get("sandbox-2")).isPresent().hasValue(exp2);
    }
}
