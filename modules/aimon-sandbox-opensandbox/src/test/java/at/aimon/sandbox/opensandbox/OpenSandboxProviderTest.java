package at.aimon.sandbox.opensandbox;

import static at.aimon.sandbox.opensandbox.FakeOpenSandboxServer.complete;
import static at.aimon.sandbox.opensandbox.FakeOpenSandboxServer.err;
import static at.aimon.sandbox.opensandbox.FakeOpenSandboxServer.exit;
import static at.aimon.sandbox.opensandbox.FakeOpenSandboxServer.init;
import static at.aimon.sandbox.opensandbox.FakeOpenSandboxServer.out;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.opensandbox.FakeOpenSandboxServer.Reply;
import at.aimon.sandbox.opensandbox.OpenSandboxProviderConfig.EgressEnforcement;
import at.aimon.sandbox.provider.Capability;
import at.aimon.sandbox.provider.CreateSpec;
import at.aimon.sandbox.provider.CredentialScope;
import at.aimon.sandbox.provider.ExecOutcome;
import at.aimon.sandbox.provider.ExecSpec;
import at.aimon.sandbox.provider.FileStat;
import at.aimon.sandbox.provider.OutputSink;
import at.aimon.sandbox.provider.ProviderSandbox;
import at.aimon.sandbox.provider.ProviderSandboxRef;
import at.aimon.sandbox.provider.ProviderSandboxState;
import at.aimon.sandbox.provider.ResourceSpec;
import at.aimon.sandbox.provider.RunningCommand;
import at.aimon.sandbox.provider.SandboxConnection;
import at.aimon.sandbox.provider.SandboxLabels;
import at.aimon.sandbox.provider.SandboxNotFoundException;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.provider.VerificationFailure;
import at.aimon.sandbox.provider.VolumeMount;
import at.aimon.sandbox.provider.VolumeRef;
import at.aimon.sandbox.provider.WriteMode;

/**
 * {@link OpenSandboxProvider} against {@link FakeOpenSandboxServer}: the wire shapes it sends, the server behaviour it
 * must compensate for (docs/design/opensandbox-spike.md §4), and the failure paths a real server cannot be made to
 * produce on demand. The contract suite runs against a real server in the docker tier.
 */
class OpenSandboxProviderTest {

    private FakeOpenSandboxServer server;
    private OpenSandboxProvider provider;

    @BeforeEach
    void start() throws IOException {
        server = new FakeOpenSandboxServer();
        provider = new OpenSandboxProvider(server.config().build());
    }

    @AfterEach
    void stop() {
        provider.close();
        server.close();
    }

    private static CreateSpec.Builder spec(String workspace, long generation) {
        return CreateSpec.builder().key(SandboxLabels.key("dep", workspace, "inc", "primary", generation)).image("img")
                .labels(SandboxLabels.labels("dep", workspace, "inc", "primary", generation, "t"))
                .expiresAt(Instant.now().plus(Duration.ofMinutes(30)));
    }

    private ProviderSandboxRef created() {
        return provider.create(spec("ws:a", 1).build());
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        void sendsTheBodyTheServerNeeds() {
            provider.create(spec("ws:a", 1).environment(Map.of("A", "1")).build());

            final JsonNode body = server.requests("POST", "/v1/sandboxes").get(0).json();
            assertThat(body.path("image").path("uri").asText()).isEqualTo("img");
            assertThat(body.path("entrypoint").toString()).isEqualTo("[\"tail\",\"-f\",\"/dev/null\"]");
            assertThat(body.path("timeout").asLong()).isBetween(1795L, 1800L);
            assertThat(body.path("resourceLimits").path("cpu").asText()).isEqualTo("1");
            assertThat(body.path("resourceLimits").path("memory").asText()).isEqualTo("1Gi");
            assertThat(body.path("env").path("A").asText()).isEqualTo("1");
            assertThat(body.path("metadata").path(SandboxLabels.GENERATION).asText()).isEqualTo("1");
            assertThat(body.has("networkPolicy")).as("no egress requested: no policy").isFalse();
            assertThat(body.has("credentialProxy")).isFalse();
            assertThat(body.has("runtimeClass")).isFalse();
            assertThat(server.requests("POST", "/v1/sandboxes").get(0).header("OPEN-SANDBOX-API-KEY"))
                    .isEqualTo(FakeOpenSandboxServer.API_KEY);
        }

        @Test
        void timeoutIsAlwaysSentAndClampedBetweenSixtySecondsAndMaxExpiry() {
            assertThat(provider.timeoutSeconds(Instant.now().plusSeconds(5))).isEqualTo(60);
            assertThat(provider.timeoutSeconds(Instant.now().plus(Duration.ofDays(3)))).isEqualTo(7200);
            assertThat(provider.timeoutSeconds(Instant.now().plusSeconds(600).plusMillis(500))).isEqualTo(601);
        }

        @Test
        void anEmptyEgressIsAnExplicitDenyAndProfileResourcesWin() {
            try (OpenSandboxProvider egress = new OpenSandboxProvider(
                    server.config().egressEnforcement(EgressEnforcement.DNS_NFT).build())) {
                egress.create(spec("ws:a", 1).egress(List.of()).resources(ResourceSpec.of("2", "4Gi", "10Gi", 64))
                        .volumes(List.of(VolumeMount.of(VolumeRef.of("aimon-v"), "/shared", true))).build());
            }

            final JsonNode body = server.requests("POST", "/v1/sandboxes").get(0).json();
            assertThat(body.path("networkPolicy").toString()).isEqualTo("{\"defaultAction\":\"deny\",\"egress\":[]}");
            assertThat(body.path("resourceLimits").toString()).isEqualTo("{\"cpu\":\"2\",\"memory\":\"4Gi\"}");
            assertThat(body.path("volumes").get(0).toString()).isEqualTo("{\"name\":\"aimon-v\",\"mountPath\":"
                    + "\"/shared\",\"readOnly\":true,\"pvc\":{\"claimName\":\"aimon-v\",\"createIfNotExists\":true}}");
        }

        @Test
        void requestsTheServerCannotServeArePermanentFailures() {
            assertThatThrownBy(() -> provider.create(spec("ws:a", 1).egress(List.of("x.com")).build()))
                    .isInstanceOfSatisfying(SandboxProviderException.class,
                            e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT))
                    .hasMessageContaining("egress");
            assertThatThrownBy(() -> provider.create(spec("ws:a", 1).credentials(List.of("gh")).build()))
                    .hasMessageContaining("no credential vault");
            try (OpenSandboxProvider kata = new OpenSandboxProvider(
                    server.config().runtime(OpenSandboxProviderConfig.Runtime.KUBERNETES)
                            .runtimeClass(OpenSandboxProviderConfig.RuntimeClass.of("kata",
                                    OpenSandboxProviderConfig.RuntimeKind.KATA))
                            .build())) {
                assertThatThrownBy(() -> kata.create(spec("ws:a", 1).runtimeClass("gvisor").build()))
                        .hasMessageContaining("runtime class gvisor");
                assertThat(kata.capabilities().runtimeClass()).contains("kata");
            }
            assertThat(server.requests("POST", "/v1/sandboxes")).isEmpty();
        }

        @Test
        void aSecondCreateWithTheSameKeyReturnsTheFirstSandbox() {
            final ProviderSandboxRef first = created();
            final ProviderSandboxRef second = created();

            assertThat(second).isEqualTo(first);
            assertThat(server.requests("POST", "/v1/sandboxes")).hasSize(1);
            final String filter = server.requests("GET", "/v1/sandboxes").get(0).param("metadata");
            assertThat(filter).contains("aimon.at%2Fsandbox-key=").contains("aimon.at%2Fmanaged=true").contains("&");
        }

        @Test
        void theOldestOfSeveralIsReusedAndFailedOnesAreSkipped() {
            final Map<String, String> labels = spec("ws:a", 1).build().labels();
            server.add("failed", "Failed", labels, Instant.now().minusSeconds(300));
            server.add("newer", "Running", labels, Instant.now().minusSeconds(10));
            server.add("older", "Running", labels, Instant.now().minusSeconds(100));

            assertThat(created().sandboxId()).isEqualTo("older");
            assertThat(server.requests("POST", "/v1/sandboxes")).isEmpty();
        }

        @Test
        void aPendingSandboxIsAwaitedUntilRunning() {
            server.createState = "Pending";
            server.pendingPolls = 3;

            final ProviderSandboxRef ref = created();

            assertThat(provider.status(ref).orElseThrow().state()).isEqualTo(ProviderSandboxState.RUNNING);
        }

        @Test
        void aSandboxThatNeverRunsIsATransientFailureAndIsNotDestroyed() {
            server.createState = "Pending";
            server.pendingPolls = Integer.MAX_VALUE;
            try (OpenSandboxProvider impatient = new OpenSandboxProvider(
                    server.config().createTimeout(Duration.ofMillis(600)).build())) {
                assertThatThrownBy(() -> impatient.create(spec("ws:a", 1).build()))
                        .isInstanceOfSatisfying(SandboxProviderException.class,
                                e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT))
                        .hasMessageContaining("not running within");
            }
            assertThat(server.requests("DELETE", "/v1/sandboxes")).isEmpty();
            assertThat(server.sandboxes).hasSize(1);
        }

        @Test
        void aFailedSandboxIsATransientFailure() {
            server.createState = "Failed";

            assertThatThrownBy(() -> created()).hasMessageContaining("FAILED instead of running");
        }

        @Test
        void createIsNeverRetried() {
            server.overrides.put("POST /v1/sandboxes",
                    request -> request.query.isEmpty() && request.path.equals("/v1/sandboxes")
                            ? Reply.error(503, "UNAVAILABLE", "busy")
                            : null);

            assertThatThrownBy(() -> created()).isInstanceOfSatisfying(SandboxProviderException.class,
                    e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT));
            assertThat(server.requests("POST", "/v1/sandboxes")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("status, list, destroy, extendExpiry")
    class Lifecycle {

        @Test
        void statusMapsStatesAndReportsLabelsAndTimes() {
            final ProviderSandbox status = provider.status(created()).orElseThrow();

            assertThat(status.labels()).containsEntry(SandboxLabels.SLOT, "primary");
            assertThat(status.expiresAt()).isPresent();
            assertThat(status.createdAt()).isPresent();
            assertThat(provider.status(ProviderSandboxRef.of(OpenSandboxProvider.NAME, "missing"))).isEmpty();
            assertThat(provider.status(ProviderSandboxRef.of("local", "x"))).isEmpty();
            assertThat(OpenSandboxProvider.state("Pending")).isEqualTo(ProviderSandboxState.PENDING);
            assertThat(OpenSandboxProvider.state("Resuming")).isEqualTo(ProviderSandboxState.PENDING);
            assertThat(OpenSandboxProvider.state("Pausing")).isEqualTo(ProviderSandboxState.PAUSED);
            assertThat(OpenSandboxProvider.state("Paused")).isEqualTo(ProviderSandboxState.PAUSED);
            assertThat(OpenSandboxProvider.state("Stopping")).isEqualTo(ProviderSandboxState.TERMINATED);
            assertThat(OpenSandboxProvider.state("Terminated")).isEqualTo(ProviderSandboxState.TERMINATED);
            assertThat(OpenSandboxProvider.state("Failed")).isEqualTo(ProviderSandboxState.FAILED);
        }

        @Test
        void listReadsEveryPageOnceWithOneMetadataParameter() {
            for (int i = 0; i < 450; i++) {
                server.add("s" + i, "Running", Map.of("aimon.at/managed", "true", "k", i % 2 == 0 ? "even" : "odd"),
                        Instant.now().minusSeconds(i));
            }

            final List<ProviderSandbox> even = provider.list(Map.of("aimon.at/managed", "true", "k", "even"));

            assertThat(even).hasSize(225).allMatch(s -> "even".equals(s.labels().get("k")));
            final List<FakeOpenSandboxServer.Request> pages = server.requests("GET", "/v1/sandboxes");
            assertThat(pages).hasSize(2)
                    .allMatch(r -> r.query.split("metadata=", -1).length == 2 && "200".equals(r.param("pageSize")));
        }

        @Test
        void destroyOfAnAbsentSandboxIsSuccess() {
            final ProviderSandboxRef ref = created();

            provider.destroy(ref);
            provider.destroy(ref);
            provider.destroy(ProviderSandboxRef.of("local", "x"));

            assertThat(server.sandboxes).isEmpty();
        }

        @Test
        void extendExpiryMovesForwardOnlyAndAtMostMaxExpiry() {
            final ProviderSandboxRef ref = created();
            final Instant before = server.sandboxes.get(ref.sandboxId()).expiresAt;

            provider.extendExpiry(ref, before.minusSeconds(60));
            assertThat(server.requests("POST", "/v1/sandboxes/" + ref.sandboxId() + "/renew")).isEmpty();

            provider.extendExpiry(ref, Instant.now().plus(Duration.ofDays(1)));
            final Instant capped = server.sandboxes.get(ref.sandboxId()).expiresAt;
            assertThat(capped).isBefore(Instant.now().plus(Duration.ofHours(2)).plusSeconds(5))
                    .isAfter(Instant.now().plus(Duration.ofHours(2)).minusSeconds(5));
        }

        @Test
        void extendExpiryRefusesAPausedSandboxAndReportsAGoneOne() {
            final ProviderSandboxRef ref = created();
            server.sandboxes.get(ref.sandboxId()).state = "Paused";

            assertThatThrownBy(() -> provider.extendExpiry(ref, Instant.now().plusSeconds(3600)))
                    .hasMessageContaining("paused");
            server.sandboxes.clear();
            assertThatThrownBy(() -> provider.extendExpiry(ref, Instant.now().plusSeconds(3600)))
                    .isInstanceOf(SandboxNotFoundException.class);
        }

        @Test
        void aSandboxGoneBetweenReadAndRenewIsNotFound() {
            final ProviderSandboxRef ref = created();
            server.overrides.put("POST /v1/sandboxes/",
                    request -> request.path.endsWith("/renew-expiration")
                            ? Reply.error(404, "DOCKER::SANDBOX_NOT_FOUND", "gone")
                            : null);

            assertThatThrownBy(() -> provider.extendExpiry(ref, Instant.now().plusSeconds(3600)))
                    .isInstanceOf(SandboxNotFoundException.class);
        }

        @Test
        void pauseAndResumeAreNotOffered() {
            final ProviderSandboxRef ref = created();

            assertThatThrownBy(() -> provider.pause(ref)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.resume(ref)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(provider.sharedVolumes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("errors and retries")
    class Errors {

        @Test
        void failuresAreClassifiedByStatus() {
            assertThat(HttpErrors.failure(400, "x", "m").kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT);
            assertThat(HttpErrors.failure(422, "x", "m").kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT);
            assertThat(HttpErrors.failure(401, "x", "m")).hasMessageContaining("api-key")
                    .extracting(SandboxProviderException::kind).isEqualTo(SandboxProviderException.Kind.PERMANENT);
            assertThat(HttpErrors.failure(403, "x", "m").kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT);
            assertThat(HttpErrors.failure(409, "x", "m").kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT);
            assertThat(HttpErrors.failure(429, "x", "m").kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT);
            assertThat(HttpErrors.failure(504, "x", "m").kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT);
        }

        @Test
        void aWrongApiKeyIsPermanentAndNotRetried() {
            try (OpenSandboxProvider wrong = new OpenSandboxProvider(server.config().apiKey(() -> "nope").build())) {
                assertThatThrownBy(() -> wrong.status(ProviderSandboxRef.of(OpenSandboxProvider.NAME, "x")))
                        .isInstanceOfSatisfying(SandboxProviderException.class,
                                e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT))
                        .hasMessageContaining("api-key");
            }
            assertThat(server.requests("GET", "/v1/sandboxes/x")).hasSize(1);
        }

        @Test
        void idempotentCallsAreRetriedOnTransientFailures() {
            final ProviderSandboxRef ref = created();
            final AtomicInteger failures = new AtomicInteger(2);
            server.overrides.put("GET /v1/sandboxes/",
                    request -> failures.getAndDecrement() > 0 ? Reply.error(503, "UNAVAILABLE", "busy") : null);

            assertThat(provider.status(ref)).isPresent();
            assertThat(server.requests("GET", "/v1/sandboxes/" + ref.sandboxId())).hasSize(4);
        }

        @Test
        void retriesGiveUpAfterMaxAttempts() {
            final ProviderSandboxRef ref = created();
            server.overrides.put("GET /v1/sandboxes/", request -> Reply.error(500, "X", "down"));

            assertThatThrownBy(() -> provider.status(ref))
                    .isInstanceOfSatisfying(SandboxProviderException.class,
                            e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT))
                    .hasMessageContaining("HTTP 500");
        }

        @Test
        void anUnreachableServerIsTransient() throws IOException {
            final int closed;
            try (ServerSocket socket = new ServerSocket(0)) {
                closed = socket.getLocalPort();
            }
            try (OpenSandboxProvider down = new OpenSandboxProvider(
                    server.config().endpoint(java.net.URI.create("http://127.0.0.1:" + closed)).build())) {
                assertThatThrownBy(() -> down.list(Map.of())).isInstanceOfSatisfying(SandboxProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.TRANSIENT));
            }
        }
    }

    @Nested
    @DisplayName("commands")
    class Commands {

        private SandboxConnection connection;

        @BeforeEach
        void connect() {
            connection = provider.connect(created());
        }

        private ExecOutcome run(String command, long maxCapture) throws InterruptedException {
            return connection.run(ExecSpec.builder().command(command).maxCaptureBytes(maxCapture)
                    .timeout(Duration.ofSeconds(10)).build(), OutputSink.DISCARD).await(Duration.ofSeconds(10));
        }

        @Test
        void eventsBecomeNewlineTerminatedOutputAndTheExitCode() throws Exception {
            server.commands = body -> Reply.events(List.of(init("c1"), "{\"type\":\"ping\",\"text\":\"pong\"}",
                    out("a"), "data: " + out("b"), out("\n"), err("e"), "not json", exit("7", "exit status 7")), null);

            final ExecOutcome outcome = run("x", 1024);

            assertThat(text(outcome.stdout())).isEqualTo("a\nb\n\n");
            assertThat(text(outcome.stderr())).isEqualTo("e\n");
            assertThat(outcome.exitCode()).isEqualTo(7);
            assertThat(outcome.timedOut()).isFalse();
            final JsonNode body = server.requests("POST", "/v1/sandboxes").stream()
                    .filter(r -> r.path.endsWith("/proxy/44772/command")).findFirst().orElseThrow().json();
            assertThat(body.path("timeout").asLong()).isEqualTo(10_000);
            assertThat(body.has("background")).isFalse();
        }

        @Test
        void aSignalIsReportedAs128PlusItsNumber() throws Exception {
            server.commands = body -> Reply.events(List.of(init("c1"), exit("-1", "signal: terminated")), null);
            assertThat(run("x", 10).exitCode()).isEqualTo(143);
            server.commands = body -> Reply.events(List.of(init("c1"), exit("-1", "signal: killed")), null);
            assertThat(run("x", 10).exitCode()).isEqualTo(137);
            server.commands = body -> Reply.events(List.of(init("c1"), exit("fork/exec: argument list too long", "x")),
                    null);
            assertThat(run("x", 10).exitCode()).isEqualTo(126);
        }

        @Test
        void theCapIsPerStreamAndCountedAfterUtf8Encoding() throws Exception {
            server.commands = body -> Reply
                    .events(List.of(init("c1"), out("ééééé"), out("more"), err("short"), complete()), null);
            final ByteArrayOutputStream sunk = new ByteArrayOutputStream();

            final ExecOutcome outcome = connection
                    .run(ExecSpec.builder().command("x").maxCaptureBytes(6).timeout(Duration.ofSeconds(10)).build(),
                            (stream, bytes, offset, length) -> {
                                if (stream == OutputSink.Stream.STDOUT) {
                                    sunk.write(bytes, offset, length);
                                }
                            })
                    .await(Duration.ofSeconds(10));

            assertThat(outcome.stdout()).hasSize(6);
            assertThat(text(outcome.stdout())).isEqualTo("ééé");
            assertThat(outcome.stdoutTruncated()).isTrue();
            assertThat(text(outcome.stderr())).isEqualTo("short\n");
            assertThat(outcome.stderrTruncated()).isFalse();
            assertThat(sunk.toString(StandardCharsets.UTF_8)).as("the sink gets everything").isEqualTo("ééééé\nmore\n");
        }

        @Test
        void awaitPastItsTimeoutSendsTheInterrupt() throws Exception {
            final CountDownLatch hold = new CountDownLatch(1);
            server.holds.put("c9", hold);
            server.commands = body -> Reply.events(List.of(init("c9"), out("started")), hold);
            final RunningCommand command = connection.run(
                    ExecSpec.builder().command("sleep 60").timeout(Duration.ofSeconds(60)).build(), OutputSink.DISCARD);

            final ExecOutcome outcome = command.await(Duration.ofMillis(300));

            assertThat(outcome.timedOut()).isTrue();
            assertThat(server.interrupted).containsExactly("c9");
            assertThat(text(outcome.stdout())).isEqualTo("started\n");
        }

        @Test
        void aKillBeforeTheCommandIdArrivesIsSentWhenItDoes() throws Exception {
            final CountDownLatch gate = new CountDownLatch(1);
            final CountDownLatch hold = new CountDownLatch(1);
            server.holds.put("late", hold);
            server.commands = body -> Reply.gated(gate, List.of(init("late")), hold);
            final RunningCommand command = connection.run(
                    ExecSpec.builder().command("sleep 60").timeout(Duration.ofSeconds(60)).build(), OutputSink.DISCARD);

            command.kill();
            assertThat(server.interrupted).isEmpty();
            gate.countDown();

            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (server.interrupted.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(server.interrupted).containsExactly("late");
            command.kill();
            assertThat(server.interrupted).as("sent once").hasSize(1);
        }

        @Test
        void aDroppedStreamIsFollowedByTheCommandStatus() throws Exception {
            server.commands = body -> Reply.events(List.of(init("c2"), out("partial")), null);
            server.commandStatus = FakeOpenSandboxServer.JSON.createObjectNode().put("running", false).put("exit_code",
                    3);

            final ExecOutcome outcome = run("x", 100);

            assertThat(outcome.exitCode()).isEqualTo(3);
            assertThat(text(outcome.stdout())).isEqualTo("partial\n");
            assertThat(server.requests("GET", "/v1/sandboxes").stream()
                    .anyMatch(r -> r.path.endsWith("/command/status/c2"))).isTrue();
        }

        @Test
        void aStreamThatEndsBeforeTheCommandStartedIsTransient() {
            server.commands = body -> Reply.events(List.of(), null);

            assertThatThrownBy(() -> run("x", 100)).isInstanceOf(SandboxProviderException.class)
                    .hasMessageContaining("ended before the command started");
        }

        @Test
        void aRefusedCommandIsAPermanentFailureWithTheServersMessage() {
            server.commands = body -> Reply.error(400, "INVALID_REQUEST_BODY",
                    "working directory does not exist: /nope");

            assertThatThrownBy(() -> connection.run(ExecSpec.builder().command("x").workingDirectory("/nope").build(),
                    OutputSink.DISCARD))
                    .isInstanceOfSatisfying(SandboxProviderException.class,
                            e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT))
                    .hasMessageContaining("working directory does not exist");
            final JsonNode body = server.requests("POST", "/v1/sandboxes").stream()
                    .filter(r -> r.path.endsWith("/command")).findFirst().orElseThrow().json();
            assertThat(body.path("cwd").asText()).isEqualTo("/nope");
        }

        @Test
        void streamsHoldNoPermitSoOtherCallsProceed() throws Exception {
            try (OpenSandboxProvider narrow = new OpenSandboxProvider(server.config().maxConcurrentCalls(1).build())) {
                final ProviderSandboxRef ref = narrow.create(spec("ws:narrow", 1).build());
                final CountDownLatch hold = new CountDownLatch(1);
                server.holds.put("long", hold);
                server.commands = body -> Reply.events(List.of(init("long")), hold);
                final RunningCommand running = narrow.connect(ref).run(
                        ExecSpec.builder().command("build").timeout(Duration.ofMinutes(20)).build(),
                        OutputSink.DISCARD);

                assertThat(narrow.status(ref)).as("a status call while the stream is open").isPresent();
                running.kill();
            }
        }

        @Test
        void aGoneSandboxIsNotFoundForCommandsAndFiles() {
            server.sandboxes.clear();

            assertThatThrownBy(() -> connection.run(ExecSpec.builder().command("true").build(), OutputSink.DISCARD))
                    .isInstanceOf(SandboxNotFoundException.class);
            assertThatThrownBy(() -> connection.files().stat("/x")).isInstanceOf(SandboxNotFoundException.class);
            assertThatThrownBy(() -> provider.connect(ProviderSandboxRef.of(OpenSandboxProvider.NAME, "gone")))
                    .isInstanceOf(SandboxNotFoundException.class);
            assertThatThrownBy(() -> provider.connect(ProviderSandboxRef.of("local", "x")))
                    .isInstanceOf(SandboxNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("endpoint re-resolution")
    class Endpoints {

        @Test
        void aConnectFailureResolvesTheEndpointOnceMore() throws Exception {
            final int closed;
            try (ServerSocket socket = new ServerSocket(0)) {
                closed = socket.getLocalPort();
            }
            try (OpenSandboxProvider direct = new OpenSandboxProvider(server.config().useServerProxy(false).build())) {
                final ProviderSandboxRef ref = direct.create(spec("ws:moved", 1).build());
                final AtomicInteger resolutions = new AtomicInteger();
                server.overrides.put("GET /v1/sandboxes/" + ref.sandboxId() + "/endpoints",
                        request -> Reply.json(200,
                                Map.of("endpoint",
                                        resolutions.getAndIncrement() == 0
                                                ? "127.0.0.1:" + closed + "/proxy/44772"
                                                : server.endpoint().getAuthority() + "/v1/sandboxes/" + ref.sandboxId()
                                                        + "/proxy/44772")));
                final SandboxConnection connection = direct.connect(ref);

                final ExecOutcome outcome = connection.run(ExecSpec.builder().command("x").build(), OutputSink.DISCARD)
                        .await(Duration.ofSeconds(10));

                assertThat(outcome.exitCode()).isZero();
                assertThat(resolutions.get()).isEqualTo(2);
            }
        }

        @Test
        void theServerProxyPathIsResolvedAgainstTheConfiguredEndpoint() throws Exception {
            // The fake answers "server.internal:8090/…" — its own view of its address; the client must not use it.
            final ExecOutcome outcome = provider.connect(created())
                    .run(ExecSpec.builder().command("x").build(), OutputSink.DISCARD).await(Duration.ofSeconds(10));

            assertThat(outcome.exitCode()).isZero();
        }
    }

    @Nested
    @DisplayName("files")
    class Files {

        private SandboxConnection connection;

        @BeforeEach
        void connect() {
            connection = provider.connect(created());
            server.commands = server.fileHelpers();
        }

        private void write(String path, String content, WriteMode mode) {
            final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            connection.files().write(path, new ByteArrayInputStream(bytes), bytes.length, mode);
        }

        private String read(String path, long offset, long length) throws IOException {
            try (InputStream in = connection.files().read(path, offset, length)) {
                return text(in.readAllBytes());
            }
        }

        @Test
        void writeUploadsToATemporaryNameThenRenames() throws Exception {
            write("/workspace/a.txt", "hello", WriteMode.CREATE_OR_REPLACE);

            final FakeOpenSandboxServer.Request upload = server.requests("POST", "/v1/sandboxes").stream()
                    .filter(r -> r.path.endsWith("/files/upload")).findFirst().orElseThrow();
            assertThat(upload.text()).contains("name=\"metadata\"; filename=\"metadata.json\"").contains("\"mode\":644")
                    .containsPattern("\"path\":\"/workspace/\\.a\\.txt\\.aimon-tmp-[0-9a-f-]+\"");
            final JsonNode helper = server.requests("POST", "/v1/sandboxes").stream()
                    .filter(r -> r.path.endsWith("/command")).findFirst().orElseThrow().json();
            assertThat(helper.path("command").asText()).contains("mv -f -- \"$1\" \"$2\"");
            assertThat(server.fileNames()).contains("/workspace/a.txt").noneMatch(p -> p.contains("aimon-tmp"));
            assertThat(read("/workspace/a.txt", 0, -1)).isEqualTo("hello");
            assertThat(read("/workspace/a.txt", 1, 3)).isEqualTo("ell");
            assertThat(read("/workspace/a.txt", 9, 3)).isEmpty();
            assertThat(read("/workspace/a.txt", 0, 0)).isEmpty();
        }

        @Test
        void createNewLinksAndRefusesAnExistingFile() {
            write("/workspace/a.txt", "a", WriteMode.CREATE_NEW);
            assertThatThrownBy(() -> write("/workspace/a.txt", "b", WriteMode.CREATE_NEW))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(server.fileNames()).noneMatch(p -> p.contains("aimon-tmp"));
        }

        @Test
        void writingOverADirectoryIsRefused() {
            connection.files().createDirectories("/workspace/dir");
            assertThatThrownBy(() -> write("/workspace/dir", "x", WriteMode.CREATE_OR_REPLACE))
                    .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("Is a directory");
        }

        @Test
        void statReportsNanosecondTimesAndNoEtag() {
            write("/workspace/a.txt", "hello", WriteMode.CREATE_OR_REPLACE);

            final FileStat stat = connection.files().stat("/workspace/a.txt").orElseThrow();
            assertThat(stat.size()).isEqualTo(5);
            assertThat(stat.modifiedAt()).isEqualTo(Instant.parse("2026-09-29T23:43:46.891791008Z"));
            assertThat(stat.etag()).isEmpty();
            assertThat(connection.files().stat("/workspace/missing")).isEmpty();
            assertThat(server.requests("GET", "/v1/sandboxes/").stream()
                    .filter(r -> r.path.matches("/v1/sandboxes/[^/]+"))).as("FILE_NOT_FOUND needs no second call")
                    .hasSizeLessThanOrEqualTo(2);
        }

        @Test
        void readingADirectoryOrAMissingFileFailsBeforeDownloading() {
            connection.files().createDirectories("/workspace/dir");

            assertThatThrownBy(() -> connection.files().read("/workspace/dir", 0, -1))
                    .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("Is a directory");
            assertThatThrownBy(() -> connection.files().read("/workspace/none", 0, -1))
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(server.requests("GET", "/v1/sandboxes").stream().filter(r -> r.path.endsWith("/download")))
                    .isEmpty();
        }

        @Test
        void listHonoursDepthAndLimit() {
            write("/workspace/d/a.txt", "a", WriteMode.CREATE_OR_REPLACE);
            write("/workspace/d/sub/b.txt", "b", WriteMode.CREATE_OR_REPLACE);

            assertThat(connection.files().list("/workspace/d", false, 100)).extracting(FileStat::path)
                    .containsExactlyInAnyOrder("/workspace/d/a.txt", "/workspace/d/sub");
            assertThat(connection.files().list("/workspace/d", true, 100)).extracting(FileStat::path)
                    .contains("/workspace/d/sub/b.txt");
            assertThat(connection.files().list("/workspace/d", true, 1)).hasSize(1);
            assertThatThrownBy(() -> connection.files().list("/workspace/none", false, 1))
                    .isInstanceOf(FileNotFoundException.class);
            assertThatThrownBy(() -> connection.files().list("/workspace/d/a.txt", false, 1))
                    .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("Not a directory");
        }

        @Test
        void deleteFollowsTheLocalProvidersRules() {
            write("/workspace/d/a.txt", "a", WriteMode.CREATE_OR_REPLACE);

            assertThatThrownBy(() -> connection.files().delete("/workspace/d", false))
                    .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("not empty");
            assertThatThrownBy(() -> connection.files().delete("/workspace/none", false))
                    .isInstanceOf(FileNotFoundException.class);
            connection.files().delete("/workspace/d/a.txt", false);
            connection.files().delete("/workspace/d", true);
            assertThat(connection.files().stat("/workspace/d")).isEmpty();
        }

        @Test
        void moveWithoutOverwriteRefusesAnExistingTargetAndWithOverwriteRenames() {
            write("/workspace/a.txt", "a", WriteMode.CREATE_OR_REPLACE);
            write("/workspace/b.txt", "b", WriteMode.CREATE_OR_REPLACE);

            assertThatThrownBy(() -> connection.files().move("/workspace/a.txt", "/workspace/b.txt", false))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThatThrownBy(() -> connection.files().move("/workspace/none", "/workspace/c.txt", false))
                    .isInstanceOf(FileNotFoundException.class);
            connection.files().move("/workspace/a.txt", "/workspace/c.txt", false);
            assertThat(server.fileNames()).contains("/workspace/c.txt").doesNotContain("/workspace/a.txt");

            connection.files().move("/workspace/c.txt", "/workspace/b.txt", true);
            final JsonNode last = server.requests("POST", "/v1/sandboxes").stream()
                    .filter(r -> r.path.endsWith("/command")).reduce((a, b) -> b).orElseThrow().json();
            assertThat(last.path("command").asText()).contains("mv -f -T --");
            assertThatThrownBy(() -> connection.files().move("/workspace/none", "/workspace/b.txt", true))
                    .isInstanceOf(FileNotFoundException.class);
        }

        @Test
        void createDirectoriesIsIdempotentAndRefusesAFile() {
            connection.files().createDirectories("/workspace/x/y");
            connection.files().createDirectories("/workspace/x/y");
            write("/workspace/f", "f", WriteMode.CREATE_OR_REPLACE);
            server.overrides.put("POST /v1/sandboxes/",
                    request -> request.path.endsWith("/directories")
                            ? Reply.error(500, "RUNTIME_ERROR", "mkdir /workspace/f: not a directory")
                            : null);

            assertThat(connection.files().stat("/workspace/x/y").orElseThrow().directory()).isTrue();
            assertThatThrownBy(() -> connection.files().createDirectories("/workspace/f"))
                    .isInstanceOf(FileAlreadyExistsException.class);
        }
    }

    @Nested
    @DisplayName("credentials and verify")
    class Verify {

        private OpenSandboxProvider vaulted;

        @BeforeEach
        void configure() {
            vaulted = new OpenSandboxProvider(
                    server.config().egressEnforcement(EgressEnforcement.DNS_NFT)
                            .credentials(Map.of(
                                    "gh", CredentialDefinition.of(
                                            CredentialScope.builder().hosts(Set.of("github.com")).methods(Set.of("GET"))
                                                    .paths(List.of("/repos/*")).build(),
                                            CredentialDefinition.Auth.apiKey("X-Token"), () -> "s3cr3t"),
                                    "other",
                                    CredentialDefinition.of(CredentialScope.builder().hosts(Set.of("x.org")).build(),
                                            CredentialDefinition.Auth.bearer(), () -> "never")))
                            .build());
        }

        @AfterEach
        void close() {
            vaulted.close();
        }

        @Test
        void theVaultGetsOnlyTheNamedBindingsWithTheEgressToken() {
            final ProviderSandboxRef ref = vaulted
                    .create(spec("ws:v", 1).egress(List.of("github.com")).credentials(List.of("gh")).build());

            final JsonNode create = server.requests("POST", "/v1/sandboxes").get(0).json();
            assertThat(create.path("credentialProxy").path("enabled").asBoolean()).isTrue();
            assertThat(create.path("metadata").path(OpenSandboxProvider.CREDENTIALS_LABEL).asText())
                    .isEqualTo(OpenSandboxProvider.credentialsHash(List.of("gh")));
            assertThat(server.vault.toString()).isEqualTo("{\"credentials\":[{\"name\":\"gh\",\"source\":{\"type\":"
                    + "\"inline\",\"value\":\"s3cr3t\"}}],\"bindings\":[{\"name\":\"gh\",\"match\":{\"schemes\":"
                    + "[\"https\"],\"hosts\":[\"github.com\"],\"methods\":[\"GET\"],\"paths\":[\"/repos/*\"]},\"auth\":"
                    + "{\"type\":\"apiKey\",\"credential\":\"gh\",\"name\":\"X-Token\"}}]}");
            assertThat(vaulted.verify(ref, Set.of(Capability.EGRESS_POLICY, Capability.CREDENTIAL_INJECTION)))
                    .isEmpty();
            assertThat(vaulted.capabilities().credentialScopes()).containsOnlyKeys("gh", "other");
        }

        @Test
        void aVaultThatLostItsBindingsFailsVerification() {
            final ProviderSandboxRef ref = vaulted
                    .create(spec("ws:v", 1).egress(List.of("github.com")).credentials(List.of("gh")).build());
            server.vault = FakeOpenSandboxServer.JSON.createObjectNode().putArray("bindings").addObject().put("name",
                    "other");

            assertThat(vaulted.verify(ref, Set.of(Capability.CREDENTIAL_INJECTION)))
                    .extracting(VerificationFailure::step).containsExactly("credentials");
        }

        @Test
        void aVaultHoldingOtherBindingsIsReplacedSoVerificationPasses() {
            final CreateSpec spec = spec("ws:v", 1).egress(List.of("github.com")).credentials(List.of("gh")).build();
            vaulted.create(spec);
            final ArrayNode bindings = FakeOpenSandboxServer.JSON.createObjectNode().putArray("bindings");
            bindings.addObject().put("name", "gh");
            bindings.addObject().put("name", "other");
            server.vault = FakeOpenSandboxServer.JSON.createObjectNode().set("bindings", bindings);

            final ProviderSandboxRef ref = vaulted.create(spec);

            assertThat(server.vault.path("bindings")).extracting(b -> b.path("name").asText()).containsExactly("gh");
            assertThat(vaulted.verify(ref, Set.of(Capability.CREDENTIAL_INJECTION))).isEmpty();
        }

        @Test
        void aServerThatOnlyFiltersDnsOrAllowsByDefaultFailsVerification() {
            final ProviderSandboxRef ref = vaulted.create(spec("ws:v", 1).egress(List.of()).build());

            server.networkPolicy = FakeOpenSandboxServer.JSON.createObjectNode().put("enforcementMode", "dns")
                    .set("policy", FakeOpenSandboxServer.JSON.createObjectNode().put("defaultAction", "deny"));
            assertThat(vaulted.verify(ref, Set.of(Capability.EGRESS_POLICY))).singleElement()
                    .satisfies(f -> assertThat(f.reason()).contains("'dns'"));
            server.networkPolicy = FakeOpenSandboxServer.JSON.createObjectNode().put("enforcementMode", "dns+nft")
                    .set("policy", FakeOpenSandboxServer.JSON.createObjectNode().put("defaultAction", "allow"));
            assertThat(vaulted.verify(ref, Set.of(Capability.EGRESS_POLICY))).singleElement()
                    .satisfies(f -> assertThat(f.reason()).contains("'allow'"));
            assertThat(vaulted.verify(ref, Set.of())).isEmpty();
            server.sandboxes.clear();
            assertThatThrownBy(() -> vaulted.verify(ref, Set.of(Capability.EGRESS_POLICY)))
                    .isInstanceOf(SandboxNotFoundException.class);
        }

        @Test
        void theEgressSidecarIsAwaitedBeforeTheVaultIsWritten() {
            final AtomicInteger notYet = new AtomicInteger(3);
            server.overrides.put("GET /v1/sandboxes/",
                    request -> request.path.endsWith("/networkpolicy") && notYet.getAndDecrement() > 0
                            ? Reply.error(500, "GENERAL::UNKNOWN_ERROR", "not up")
                            : null);

            vaulted.create(spec("ws:v", 1).egress(List.of("github.com")).credentials(List.of("gh")).build());

            assertThat(server.vault).isNotNull();
        }

        @Test
        void aVaultTheSidecarRefusesIsPermanent() {
            server.overrides.put("POST /v1/sandboxes/",
                    request -> request.path.endsWith("/credential-vault")
                            ? Reply.error(412, "PRECONDITION_FAILED", "mode is dns")
                            : null);

            assertThatThrownBy(() -> vaulted
                    .create(spec("ws:v", 1).egress(List.of("github.com")).credentials(List.of("gh")).build()))
                    .isInstanceOfSatisfying(SandboxProviderException.class,
                            e -> assertThat(e.kind()).isEqualTo(SandboxProviderException.Kind.PERMANENT))
                    .hasMessageContaining("credential vault");
        }
    }
}
