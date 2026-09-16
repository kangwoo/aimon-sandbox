package at.aimon.sandbox.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SandboxTest {

    @Test
    void builder_AllFields_CreatesImmutableInstance() {
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(1800);

        Sandbox sandbox = Sandbox.builder().identifier("my-sandbox").sandboxId("container-123")
                .name("sandbox-my-sandbox").image("ubuntu:22.04").createdAt(now).expiresAt(expires).build();

        assertThat(sandbox.getIdentifier()).isEqualTo("my-sandbox");
        assertThat(sandbox.getSandboxId()).isEqualTo("container-123");
        assertThat(sandbox.getName()).isEqualTo("sandbox-my-sandbox");
        assertThat(sandbox.getImage()).isEqualTo("ubuntu:22.04");
        assertThat(sandbox.getCreatedAt()).isEqualTo(now);
        assertThat(sandbox.getExpiresAt()).isEqualTo(expires);
    }

    @Test
    void builder_MissingIdentifier_ThrowsException() {
        assertThatThrownBy(() -> Sandbox.builder().sandboxId("id").name("name").image("img").createdAt(Instant.now())
                .expiresAt(Instant.now()).build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_SameIdentifierAndSandboxId_AreEqual() {
        Instant now = Instant.now();
        Sandbox a = Sandbox.builder().identifier("id").sandboxId("sid").name("n").image("i").createdAt(now)
                .expiresAt(now).build();
        Sandbox b = Sandbox.builder().identifier("id").sandboxId("sid").name("different").image("different")
                .createdAt(now).expiresAt(now).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
