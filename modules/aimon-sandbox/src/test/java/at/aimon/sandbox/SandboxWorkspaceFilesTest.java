package at.aimon.sandbox;

import static at.aimon.sandbox.SandboxHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.sandbox.provider.SandboxProviderException;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Fault;
import at.aimon.sandbox.testkit.FaultInjectingSandboxProvider.Operation;
import at.aimon.sandbox.workspace.CreateWorkspace;
import at.aimon.sandbox.workspace.SandboxEvent;
import at.aimon.sandbox.workspace.SandboxWorkspaceId;
import at.aimon.sandbox.workspace.SlotState;
import at.aimon.sandbox.workspace.WorkspaceFile;
import at.aimon.sandbox.workspace.WorkspaceFileListing;
import at.aimon.sandbox.workspace.WorkspaceFilesUnavailableException;
import at.aimon.sandbox.workspace.WorkspaceFilesUnavailableException.Reason;
import at.aimon.sandbox.workspace.WorkspaceState;

/** {@link SandboxWorkspaceFiles}: a file explorer's backend (docs/design/workspace-sandbox-files.md §9). */
class SandboxWorkspaceFilesTest {

    private static final Principal BOB = Principal.user("bob");
    private static final Principal OPERATOR = Principal.user("operator");

    /** Shared workspaces allowed, for the tenant-access case; nothing else depends on it. */
    private final SandboxHarness harness = SandboxHarness.builder().settings(s -> s.allowSharedWorkspaces(true))
            .build();
    private SandboxWorkspaceId ws;

    @BeforeEach
    void createWorkspace() {
        ws = harness.sandbox.workspaces().create(CreateWorkspace.named("explorer"), ALICE).id();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private SandboxWorkspaceFiles files() {
        return harness.sandbox.workspaces().files();
    }

    private SandboxWorkspaceFiles adminFiles() {
        return SandboxAdministration.of(harness.sandbox).files();
    }

    private Path host(String sandboxPath) {
        final var ref = harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().providerRef().orElseThrow();
        return harness.local.hostRoot(ref).resolve(sandboxPath.substring(1));
    }

    private void hostWrite(String sandboxPath, String content) throws IOException {
        final Path path = host(sandboxPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<SandboxEvent> fileEvents() {
        return harness.events.stream().filter(e -> e.type() == SandboxEvent.Type.FILES_CHANGED).toList();
    }

    private Reason reasonOf(Runnable call) {
        try {
            call.run();
        } catch (WorkspaceFilesUnavailableException e) {
            return e.reason();
        }
        throw new AssertionError("expected WorkspaceFilesUnavailableException");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // reads (§6.2)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.2: list shows one level, directories first then by name, with paths relative to /workspace")
    void listsOneLevelDirectoriesFirst() throws IOException {
        hostWrite("/workspace/repo/b.txt", "b");
        hostWrite("/workspace/repo/a.txt", "aa");
        hostWrite("/workspace/repo/zdir/deep.txt", "deep");

        final WorkspaceFileListing listing = files().list(ws, "repo", 100, ALICE);

        assertThat(listing.directory().path()).isEqualTo("repo");
        assertThat(listing.entries()).extracting(WorkspaceFile::path).containsExactly("repo/zdir", "repo/a.txt",
                "repo/b.txt");
        assertThat(listing.entries().get(1).size()).isEqualTo(2);
        assertThat(listing.entries().get(0).directory()).isTrue();
        assertThat(listing.truncated()).isFalse();
    }

    @Test
    @DisplayName("§6.2: the root lists the sandbox's own .aimon-* entries as read-only")
    void rootListsReservedEntriesReadOnly() {
        final WorkspaceFileListing root = files().list(ws, "", 100, ALICE);

        assertThat(root.directory().path()).isEmpty();
        assertThat(root.directory().readOnly()).isTrue();
        assertThat(root.entries()).filteredOn(f -> f.name().startsWith(".aimon-")).isNotEmpty()
                .allMatch(WorkspaceFile::readOnly);
        assertThat(root.entries()).filteredOn(f -> f.name().equals("repo")).singleElement()
                .satisfies(repo -> assertThat(repo.readOnly()).isFalse());
    }

    @Test
    void aListOverTheLimitSaysItIsTruncated() throws IOException {
        for (int i = 0; i < 5; i++) {
            hostWrite("/workspace/many/f" + i, "x");
        }

        final WorkspaceFileListing listing = files().list(ws, "many", 3, ALICE);

        assertThat(listing.entries()).hasSize(3);
        assertThat(listing.truncated()).isTrue();
        assertThat(files().list(ws, "many", 5, ALICE).truncated()).isFalse();
    }

    @Test
    void listRefusesAFileAndAMissingDirectory() throws IOException {
        hostWrite("/workspace/repo/a.txt", "a");

        assertThatThrownBy(() -> files().list(ws, "repo/a.txt", 10, ALICE)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> files().list(ws, "nope", 10, ALICE)).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> files().list(ws, "", 0, ALICE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files().list(ws, "", SandboxWorkspaceFiles.MAX_LIST + 1, ALICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("§6.2: read takes a range; a preview asks for its cap and compares the size")
    void readsARange() throws IOException {
        hostWrite("/workspace/repo/text.txt", "0123456789");

        assertThat(readAll(files().read(ws, "repo/text.txt", 0, -1, ALICE))).isEqualTo("0123456789");
        assertThat(readAll(files().read(ws, "repo/text.txt", 2, 3, ALICE))).isEqualTo("234");
        assertThat(files().stat(ws, "repo/text.txt", ALICE).orElseThrow().size()).isEqualTo(10);
        assertThatThrownBy(() -> files().read(ws, "repo", 0, -1, ALICE)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> files().read(ws, "repo/none", 0, -1, ALICE)).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void statIsEmptyForAMissingPath() {
        assertThat(files().stat(ws, "repo/none", ALICE)).isEmpty();
        assertThat(files().stat(ws, "repo", ALICE)).hasValueSatisfying(f -> assertThat(f.directory()).isTrue());
        assertThat(files().stat(ws, "", ALICE).orElseThrow().sandboxPath()).isEqualTo("/workspace");
    }

    @Test
    @DisplayName("§6.2: search walks the tree; a plain pattern is a case-insensitive substring, a glob is a glob")
    void searchesByName() throws IOException {
        hostWrite("/workspace/repo/src/Main.java", "class Main {}");
        hostWrite("/workspace/repo/src/util/MainHelper.java", "class MainHelper {}");
        hostWrite("/workspace/repo/README.md", "# readme");

        assertThat(files().search(ws, "repo", "main", 10, ALICE)).extracting(WorkspaceFile::path)
                .containsExactlyInAnyOrder("repo/src/Main.java", "repo/src/util/MainHelper.java");
        assertThat(files().search(ws, "repo", "*.md", 10, ALICE)).extracting(WorkspaceFile::path)
                .containsExactly("repo/README.md");
        assertThat(files().search(ws, "repo", "util", 10, ALICE)).extracting(WorkspaceFile::path)
                .containsExactly("repo/src/util");
        assertThat(files().search(ws, "repo", "main", 1, ALICE)).hasSize(1);
        assertThatThrownBy(() -> files().search(ws, "repo", " ", 10, ALICE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // changes (§6.3, §6.4)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.3: write creates parents, refuses an existing file without overwrite, and publishes FILES_CHANGED")
    void writesAndPublishes() throws IOException {
        final WorkspaceFile written = files().write(ws, "repo/in/data.csv", bytes("a,b\n"), 4, false, ALICE);

        assertThat(written.path()).isEqualTo("repo/in/data.csv");
        assertThat(written.size()).isEqualTo(4);
        assertThat(Files.readString(host("/workspace/repo/in/data.csv"))).isEqualTo("a,b\n");
        assertThatThrownBy(() -> files().write(ws, "repo/in/data.csv", bytes("x"), 1, false, ALICE))
                .isInstanceOf(FileAlreadyExistsException.class);
        files().write(ws, "repo/in/data.csv", bytes("x"), 1, true, ALICE);
        assertThat(Files.readString(host("/workspace/repo/in/data.csv"))).isEqualTo("x");
        assertThatThrownBy(() -> files().write(ws, "repo/in", bytes("x"), 1, true, ALICE))
                .isInstanceOf(InvalidPathException.class);

        assertThat(fileEvents()).extracting(SandboxEvent::cause).containsExactly("wrote repo/in/data.csv (4 bytes)",
                "replaced repo/in/data.csv (1 bytes)");
        assertThat(fileEvents()).allSatisfy(e -> {
            assertThat(e.workspace()).contains(ws);
            assertThat(e.actor()).contains("default/USER:alice");
            assertThat(e.slot()).contains("primary");
        });
    }

    @Test
    void createsMovesAndDeletes() throws IOException {
        files().createDirectory(ws, "repo/out/nested", ALICE);
        assertThat(Files.isDirectory(host("/workspace/repo/out/nested"))).isTrue();
        assertThat(files().createDirectory(ws, "repo/out/nested", ALICE).directory()).isTrue();
        hostWrite("/workspace/repo/out/a.txt", "a");
        assertThatThrownBy(() -> files().createDirectory(ws, "repo/out/a.txt", ALICE))
                .isInstanceOf(FileAlreadyExistsException.class);

        final WorkspaceFile renamed = files().move(ws, "repo/out/a.txt", "repo/out/b.txt", false, ALICE);
        assertThat(renamed.path()).isEqualTo("repo/out/b.txt");
        assertThat(Files.exists(host("/workspace/repo/out/a.txt"))).isFalse();
        hostWrite("/workspace/repo/out/c.txt", "c");
        assertThatThrownBy(() -> files().move(ws, "repo/out/c.txt", "repo/out/b.txt", false, ALICE))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> files().move(ws, "repo/out", "repo/out/nested/out", false, ALICE))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> files().move(ws, "repo/none", "repo/x", false, ALICE))
                .isInstanceOf(FileNotFoundException.class);

        assertThatThrownBy(() -> files().delete(ws, "repo/out", false, ALICE))
                .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining("not empty");
        files().delete(ws, "repo/out/b.txt", false, ALICE);
        assertThat(Files.exists(host("/workspace/repo/out/b.txt"))).isFalse();
        files().delete(ws, "repo/out", true, ALICE);
        assertThat(Files.exists(host("/workspace/repo/out"))).isFalse();
        assertThatThrownBy(() -> files().delete(ws, "repo/out", true, ALICE)).isInstanceOf(FileNotFoundException.class);

        assertThat(fileEvents()).extracting(SandboxEvent::cause).containsExactly("created directory repo/out/nested",
                "moved repo/out/a.txt to repo/out/b.txt", "deleted repo/out/b.txt", "deleted directory repo/out");
    }

    @Test
    @DisplayName("§6.3: /workspace itself and the .aimon-* entries can be read, never changed")
    void reservedEntriesAreReadOnly() throws IOException {
        hostWrite("/workspace/repo/a.txt", "a");
        assertThat(files().list(ws, ".aimon-shell", 10, ALICE).directory().readOnly()).isTrue();

        assertThatThrownBy(() -> files().write(ws, ".aimon-shell/x", bytes("x"), 1, true, ALICE))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().createDirectory(ws, ".aimon-staged/x", ALICE))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().move(ws, "repo/a.txt", ".aimon-shell/a.txt", true, ALICE))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().move(ws, ".aimon-shell", "repo/shell", true, ALICE))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().delete(ws, ".aimon-seed.lock", false, ALICE))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().delete(ws, "", true, ALICE)).isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().delete(ws, ".", true, ALICE)).isInstanceOf(FileAccessDeniedException.class);
        assertThat(fileEvents()).isEmpty();
    }

    @Test
    @DisplayName("§6.3: a path is relative to /workspace and never escapes it")
    void pathsStayUnderWorkspace() {
        assertThatThrownBy(() -> files().stat(ws, "../etc/passwd", ALICE)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> files().stat(ws, "repo/../../etc", ALICE)).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> files().stat(ws, "/etc/passwd", ALICE)).isInstanceOf(InvalidPathException.class)
                .hasMessageContaining("relative to /workspace");
        assertThatThrownBy(() -> files().stat(ws, "a\0b", ALICE)).isInstanceOf(InvalidPathException.class);
        assertThat(files().stat(ws, "repo/./x/..", ALICE).orElseThrow().path()).isEqualTo("repo");
        assertThat(files().stat(ws, "repo\\", ALICE).orElseThrow().path()).isEqualTo("repo");
    }

    @Test
    @DisplayName("§6.4: a change counts as activity and pushes the expiry; a read does not")
    void changesRecordActivityReadsDoNot() {
        final Instant before = harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().lastActivityAt();
        harness.clock.advance(Duration.ofMinutes(5));

        files().list(ws, "", 10, ALICE);
        files().stat(ws, "repo", ALICE);
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().lastActivityAt())
                .isEqualTo(before);

        files().createDirectory(ws, "repo/new", ALICE);
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().lastActivityAt())
                .isEqualTo(harness.clock.instant());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // nothing is provisioned (§6.1)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.1: a terminated slot is NOT_RUNNING, and looking does not provision it")
    void aTerminatedSlotIsNotRunningAndStaysSo() {
        SandboxAdministration.of(harness.sandbox).terminateSlot(ws, "primary", OPERATOR);
        final int sandboxes = harness.local.sandboxCount();

        assertThat(reasonOf(() -> files().list(ws, "", 10, ALICE))).isEqualTo(Reason.NOT_RUNNING);
        assertThat(reasonOf(() -> files().write(ws, "repo/x", bytes("x"), 1, true, ALICE)))
                .isEqualTo(Reason.NOT_RUNNING);
        assertThat(harness.local.sandboxCount()).isEqualTo(sandboxes);
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.TERMINATED);
    }

    @Test
    @DisplayName("§6.1: a closed workspace is CLOSED and stays closed")
    void aClosedWorkspaceIsClosedAndStaysSo() {
        harness.sandbox.workspaces().close(ws, ALICE);

        assertThat(reasonOf(() -> files().stat(ws, "", ALICE))).isEqualTo(Reason.CLOSED);
        assertThat(harness.store.find(ws).orElseThrow().state()).isEqualTo(WorkspaceState.CLOSED);
    }

    @Test
    @DisplayName("§6.1: a session's workspace is reachable once its first command ran, never created by looking")
    void aSessionWorkspaceIsReachableOnceItRuns() throws Exception {
        final SessionId session = SessionId.generate();
        final SandboxWorkspaceId derived = SandboxWorkspaceId.of("ws:" + session.value());

        assertThat(reasonOf(() -> files().list(derived, "", 10, ALICE))).isEqualTo(Reason.NOT_FOUND);
        assertThat(harness.store.find(derived)).isEmpty();

        SandboxHarness.bash(harness.mainTurn(session, ALICE), "echo hello > /workspace/repo/out.txt");

        assertThat(readAll(files().read(derived, "repo/out.txt", 0, -1, ALICE))).isEqualTo("hello\n");
    }

    @Test
    @DisplayName("§6.1: a sandbox found gone is marked lost and answers NOT_RUNNING")
    void aLostSandboxIsMarkedLost() {
        harness.faults.injectOnce(Operation.FILES_STAT, Fault.notFound());

        assertThat(reasonOf(() -> files().stat(ws, "repo", ALICE))).isEqualTo(Reason.NOT_RUNNING);
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.TERMINATED);
    }

    @Test
    void aProviderFailureIsUnreachable() {
        harness.faults.injectOnce(Operation.FILES_LIST, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        assertThat(reasonOf(() -> files().list(ws, "", 10, ALICE))).isEqualTo(Reason.UNREACHABLE);
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.RUNNING);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // rights (§6.5)
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.5: another principal is answered as for a workspace that does not exist")
    void anotherPrincipalIsNotFound() {
        assertThat(reasonOf(() -> files().list(ws, "", 10, BOB))).isEqualTo(Reason.NOT_FOUND);
        assertThat(reasonOf(() -> files().list(SandboxWorkspaceId.of("wsm:missing"), "", 10, ALICE)))
                .isEqualTo(Reason.NOT_FOUND);
    }

    @Test
    @DisplayName("§6.5: tenant access lets the tenant use the files; changes record who made them")
    void tenantAccessLetsTheTenantIn() {
        final SandboxWorkspaceId shared = harness.sandbox.workspaces()
                .create(CreateWorkspace.builder().name("shared").access(SandboxSettings.WorkspaceAccess.TENANT).build(),
                        ALICE)
                .id();

        files().write(shared, "repo/bob.txt", bytes("bob"), 3, false, BOB);

        assertThat(fileEvents()).singleElement().satisfies(e -> assertThat(e.actor()).contains("default/USER:bob"));
    }

    @Test
    @DisplayName("§6.5: the operator's files check no owner, need an actor and record it")
    void theOperatorChecksNoOwnerAndIsRecorded() {
        adminFiles().write(ws, "repo/fix.txt", bytes("fix"), 3, false, OPERATOR);

        assertThat(files().stat(ws, "repo/fix.txt", ALICE)).isPresent();
        assertThat(fileEvents()).singleElement()
                .satisfies(e -> assertThat(e.actor()).contains("default/USER:operator"));
        assertThatThrownBy(() -> adminFiles().list(ws, "", 10, null)).isInstanceOf(NullPointerException.class);
        assertThat(reasonOf(() -> adminFiles().list(SandboxWorkspaceId.of("wsm:missing"), "", 10, OPERATOR)))
                .isEqualTo(Reason.NOT_FOUND);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // review of PR #40
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PR #40 review: only top-level .aimon-* is read-only, however the path is spelled")
    void onlyTopLevelReservedEntriesAreReadOnly() {
        assertThatThrownBy(() -> files().write(ws, "repo/../.aimon-shell/x", bytes("x"), 1, true, ALICE))
                .isInstanceOf(FileAccessDeniedException.class);
        assertThatThrownBy(() -> files().createDirectory(ws, ".aimon-anything", ALICE))
                .isInstanceOf(FileAccessDeniedException.class);

        final WorkspaceFile nested = files().write(ws, "repo/.aimon-notes", bytes("n"), 1, false, ALICE);

        assertThat(nested.readOnly()).isFalse();
    }

    @Test
    @DisplayName("PR #40 review: a read larger than one chunk streams across chunks, ranges included")
    void readsAcrossChunks() throws IOException {
        final int size = SandboxWorkspaceFiles.READ_CHUNK * 2 + 123;
        final byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 31);
        }
        final Path big = host("/workspace/repo/big.bin");
        Files.createDirectories(big.getParent());
        Files.write(big, content);
        harness.faults.resetCounts();

        try (InputStream in = files().read(ws, "repo/big.bin", 0, -1, ALICE)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(harness.faults.calls(Operation.FILES_READ)).isEqualTo(3);

        final long from = SandboxWorkspaceFiles.READ_CHUNK - 10L;
        try (InputStream in = files().read(ws, "repo/big.bin", from, 20, ALICE)) {
            assertThat(in.readAllBytes()).isEqualTo(java.util.Arrays.copyOfRange(content, (int) from, (int) from + 20));
        }
        try (InputStream in = files().read(ws, "repo/big.bin", size + 5L, -1, ALICE)) {
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("PR #40 review: a fetch that fails mid-stream is UNREACHABLE, not a raw provider exception")
    void aFailedChunkFetchIsMapped() throws IOException {
        hostWrite("/workspace/repo/a.txt", "abc");
        harness.faults.injectOnce(Operation.FILES_READ, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        try (InputStream in = files().read(ws, "repo/a.txt", 0, -1, ALICE)) {
            assertThat(reasonOf(() -> {
                try {
                    in.read();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            })).isEqualTo(Reason.UNREACHABLE);
        }
    }

    @Test
    @DisplayName("PR #40 review: a write that landed publishes FILES_CHANGED even when the stat after it fails")
    void aWriteThatLandedIsPublishedEvenIfTheStatAfterFails() throws IOException {
        harness.faults.resetCounts();
        // The first stat is the precondition check; the second is the one after the write.
        harness.faults.injectAt(Operation.FILES_STAT, 2, Fault.fail(SandboxProviderException.Kind.TRANSIENT));

        assertThat(reasonOf(() -> files().write(ws, "repo/landed.txt", bytes("abc"), 3, false, ALICE)))
                .isEqualTo(Reason.UNREACHABLE);
        assertThat(Files.readString(host("/workspace/repo/landed.txt"))).isEqualTo("abc");
        assertThat(fileEvents()).extracting(SandboxEvent::cause).containsExactly("wrote repo/landed.txt (3 bytes)");
    }

    @Test
    @DisplayName("PR #40 review: move creates the destination's missing parents")
    void moveCreatesMissingParents() throws IOException {
        hostWrite("/workspace/repo/d/inner.txt", "i");

        files().move(ws, "repo/d", "repo/archive/2026/d", false, ALICE);

        assertThat(Files.readString(host("/workspace/repo/archive/2026/d/inner.txt"))).isEqualTo("i");
        assertThat(Files.exists(host("/workspace/repo/d"))).isFalse();
    }

    @Test
    @DisplayName("PR #40 review: bracket characters core strips do not turn a search into an exact glob")
    void bracketsInAPatternStayASubstring() throws IOException {
        hostWrite("/workspace/repo/report[1].txt", "r");

        assertThat(files().search(ws, "repo", "report[1]", 10, ALICE)).extracting(WorkspaceFile::path)
                .containsExactly("repo/report[1].txt");
        assertThat(files().search(ws, "repo", "port", 10, ALICE)).extracting(WorkspaceFile::path)
                .containsExactly("repo/report[1].txt");
    }

    @Test
    @DisplayName("PR #40 review: a CLOSING workspace and a slot not yet seeded answer without waiting or provisioning")
    void closingAndNotSeeded() {
        final var record = harness.store.find(ws).orElseThrow();
        final var slot = record.slot("primary").orElseThrow();
        final var unseeded = harness.store.update(ws, record.version(),
                record.withSlot(slot.toBuilder().seeded(false).build()));
        final int sandboxes = harness.local.sandboxCount();

        assertThat(reasonOf(() -> files().list(ws, "", 10, ALICE))).isEqualTo(Reason.NOT_RUNNING);

        harness.store.update(ws, unseeded.version(), unseeded.toBuilder().state(WorkspaceState.CLOSING).build());

        assertThat(reasonOf(() -> files().list(ws, "", 10, ALICE))).isEqualTo(Reason.CLOSING);
        assertThat(harness.local.sandboxCount()).isEqualTo(sandboxes);
    }

    @Test
    @DisplayName("PR #40 review: a change that finds the sandbox gone on the expiry push changes nothing")
    void aChangeFindingTheSandboxGoneChangesNothing() {
        harness.clock.advance(Duration.ofMinutes(5));
        harness.faults.injectOnce(Operation.EXTEND_EXPIRY, Fault.notFound());

        assertThat(reasonOf(() -> files().write(ws, "repo/x.txt", bytes("x"), 1, false, ALICE)))
                .isEqualTo(Reason.NOT_RUNNING);
        assertThat(Files.exists(host("/workspace/repo/x.txt"))).isFalse();
        assertThat(fileEvents()).isEmpty();
        assertThat(harness.store.find(ws).orElseThrow().slot("primary").orElseThrow().state())
                .isEqualTo(SlotState.TERMINATED);
    }
}
