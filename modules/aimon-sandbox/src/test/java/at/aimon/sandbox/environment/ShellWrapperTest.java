package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShellWrapperTest {

    private static ShellWrapper.Invocation invocation(String command) {
        return new ShellWrapper.Invocation().command(command).directory("/workspace/.aimon-shell/abc")
                .runPrefix("/workspace/.aimon-shell/abc/run-1").root("/workspace/repo").nodeId("node-1")
                .nonce("AIMON-n").lockWait(Duration.ofMillis(1500)).timeout(Duration.ofMillis(2500)).maxBytes(100);
    }

    @Test
    void theCommandIsSingleQuotedDataWrittenByABuiltinNeverAnArgument() {
        final String script = ShellWrapper.foreground(invocation("echo 'x' # it's; exit 3"));

        assertThat(script).contains("printf '%s' 'echo '\\''x'\\'' # it'\\''s; exit 3' > \"$r.cmd\" || exit 70\n");
        assertThat(script).contains("/bin/bash -c \"$__aimon_inner\" aimon \"$d\" \"$r\" fg")
                .doesNotContain("\"$r\" \"$__aimon_cmd\"");
        assertThat(ShellWrapper.INNER).contains("IFS= read -r -d '' __aimon_cmd < \"$__aimon_run.cmd\"");
    }

    @Test
    void anUploadedCommandIsNotInTheScriptAtAll() {
        final String script = ShellWrapper.foreground(invocation("echo secret-payload").commandUploaded(true));

        assertThat(script).doesNotContain("secret-payload").doesNotContain("> \"$r.cmd\"");
        assertThat(ShellWrapper.embeddable("x".repeat(ShellWrapper.INLINE_COMMAND_LIMIT - 2))).isTrue();
        assertThat(ShellWrapper.embeddable("x".repeat(ShellWrapper.INLINE_COMMAND_LIMIT))).isFalse();
        // Quoting counts: every ' becomes four bytes.
        assertThat(ShellWrapper.embeddable("'".repeat(ShellWrapper.INLINE_COMMAND_LIMIT / 3))).isFalse();
    }

    @Test
    void noTimeoutMeansNoWatchdog() {
        final String script = ShellWrapper.foreground(invocation("true").timeout(null));

        assertThat(script).contains("flock -w").doesNotContain("kill -KILL 0").doesNotContain("$r.timedout");
    }

    @Test
    void foregroundTakesTheLockThenStartsTheWatchdogAndPrintsTheTrailer() {
        final String script = ShellWrapper.foreground(invocation("true"));

        assertThat(script).contains("flock -w 2 9 || exit 75").contains("sleep 2.500 & w=$!").contains("kill -KILL 0")
                .contains(" 9>&- >\"$r.out\" 2>\"$r.err\" </dev/null").contains("head -c 100 \"$r.out\"")
                .contains("printf '\\n%s exit=%d out=%d err=%d cwd=%d\\n' " + "'AIMON-n'");
        assertThat(script.indexOf("flock -w")).isLessThan(script.indexOf("sleep 2.500"));
    }

    @Test
    void theInlineSizeCountsTheQuotedEnvironmentAndWorkingDirectory() {
        // " 'A=x'" is 6 bytes; "'it'\''s'" is 9.
        assertThat(ShellWrapper.inlineSize(Map.of("A", "x"), null)).isEqualTo(6);
        assertThat(ShellWrapper.inlineSize(Map.of(), "it's")).isEqualTo(9);
        assertThat(ShellWrapper.inlineSize(Map.of(), null)).isZero();
    }

    @Test
    void theEpilogueRemovesTheRunFilesBeforePrintingTheTrailer() {
        final String script = ShellWrapper.foreground(invocation("true"));
        final String rm = "rm -f \"$r.out\" \"$r.err\" \"$r.in\" \"$r.cmd\" \"$r.cwd\" \"$r.timedout\"\n";

        assertThat(script).contains(rm).contains("\"$rc\" \"$o\" \"$e\" \"$c\" >&2");
        assertThat(script.indexOf("wc -c < \"$r.err\"")).isLessThan(script.indexOf(rm));
        assertThat(script.indexOf(rm)).isLessThan(script.indexOf("printf '\\n%s exit="));
        assertThat(ShellWrapper.background(invocation("true"))).contains("rm -f \"$r.out\"");
    }

    @Test
    void backgroundTakesNoLockAndHasNoWatchdog() {
        final String script = ShellWrapper.background(invocation("sleep 1"));

        assertThat(script).doesNotContain("flock").doesNotContain("kill -KILL").contains("\" bg '/workspace/repo'");
    }

    private static ShellWrapper.Invocation stream(String command) {
        return invocation(command).privateDirectory("/workspace/.aimon-shell/.run.0123");
    }

    @Test
    void aHookTakesNoLockSavesNoStateAndKeepsTheWatchdog() {
        final String script = ShellWrapper.hook(stream("true"));

        assertThat(script).doesNotContain("flock").contains("\" hook '/workspace/repo'").contains("sleep 2.500 & w=$!")
                .contains("  kill -KILL 0 ) </dev/null");
        assertThat(ShellWrapper.hook(stream("true").timeout(null))).doesNotContain("kill -KILL 0");
    }

    @Test
    void anIsolatedCommandTakesNoLockKeepsTheWatchdogAndRunsTheInnerBashAsANewShell() {
        final String script = ShellWrapper.isolated(stream("true"));

        assertThat(script).doesNotContain("flock").contains("\" iso '/workspace/repo'").contains("sleep 2.500 & w=$!")
                .contains("  kill -KILL 0 ) </dev/null");
        assertThat(ShellWrapper.isolated(stream("true").timeout(null))).doesNotContain("kill -KILL 0");
        // iso never reads the state file and, not being fg, installs no save trap.
        assertThat(ShellWrapper.INNER).contains(
                "if [ \"$__aimon_mode\" != iso ] && [ -r \"$__aimon_dir/state\" ]; then . \"$__aimon_dir/state\"\n"
                        + "else builtin cd -- \"$__aimon_root\" 2>/dev/null || __aimon_fallback; fi")
                .contains("if [ \"$__aimon_mode\" = fg ]; then\n  trap");
    }

    @Test
    @DisplayName("#33: a hook's or an isolated command's script keeps nothing in the shell directory")
    void aStreamScriptHasNoRunFiles() {
        for (String script : List.of(ShellWrapper.hook(stream("echo 'x'").stdinInline("aGk=")),
                ShellWrapper.isolated(stream("echo 'x'").redirectErrorStream(true)))) {
            assertThat(script).doesNotContain("$r.out").doesNotContain("$r.err").doesNotContain("$r.cmd")
                    .doesNotContain("$r.timedout").doesNotContain("$r.cwd").doesNotContain("mkdir -p \"$d\"")
                    .doesNotContain("head -c").doesNotContain("out=%d");
            // The command is the inner bash's sixth argument, after root and working directory.
            assertThat(script).contains("\"$d\" \"$p/r\" ").contains(" '/workspace/repo' '' 'echo '\\''x'\\'''")
                    .contains("printf '\\n%s exit=%d cwd=%d\\n' 'AIMON-n' \"$rc\" \"$c\" >&2\n");
            assertThat(script.indexOf("rm -rf -- \"$p\"\n")).isLessThan(script.indexOf("printf '\\n%s exit="));
        }
        assertThat(ShellWrapper.hook(stream("true").stdinInline("aGk=")))
                .contains("printf '%s' 'aGk=' | /usr/bin/base64 -d 2>/dev/null | /bin/bash -c")
                .doesNotContain("</dev/null\n");
        assertThat(ShellWrapper.isolated(stream("true").redirectErrorStream(true))).contains(" 2>&1 </dev/null\n");
        assertThat(ShellWrapper.INNER).contains("then __aimon_cmd=$6; shift 6\n"
                + "else shift 5; IFS= read -r -d '' __aimon_cmd < \"$__aimon_run.cmd\"; fi");
    }

    @Test
    @DisplayName("#33: the private directory is made exclusively, after the shell root, with placement under umask 077")
    void thePrivateDirectoryIsExclusiveAndPlacementIsScoped() {
        final String script = ShellWrapper.isolated(stream("true"));

        final int root = script.indexOf("mkdir -p -- '/workspace/.aimon-shell' || exit 70\n");
        final int mkdir = script.indexOf("mkdir -m 700 -- \"$p\" || exit 70\n");
        assertThat(root).isNotNegative().isLessThan(mkdir);
        assertThat(script).contains("p='/workspace/.aimon-shell/.run.0123'\n").contains("m=$(umask); umask 077\n")
                .contains("trap 'rm -rf -- \"$p\"' EXIT\n").contains("set -C\n").contains("set +C\n");
        assertThat(script.indexOf("umask \"$m\"\n")).isLessThan(script.indexOf("sleep 2.500"))
                .isLessThan(script.indexOf("/bin/bash -c \"$__aimon_inner\""));
        // No here-document anywhere: bash would write it to a temp file in $TMPDIR.
        assertThat(script).doesNotContain("<<");
    }

    private static List<ShellWrapper.RunFile> runFiles() {
        return List.of(new ShellWrapper.RunFile("a", "QQ==", "aa".repeat(32)),
                new ShellWrapper.RunFile("lib/b", "Qg==", "bb".repeat(32)));
    }

    @Test
    @DisplayName("RUN_DIRECTORY: inline files are decoded under noclobber and checked against the embedded manifest")
    void inlineRunFilesArePlacedAndChecked() {
        final String script = ShellWrapper.hook(stream("true").runDirectory("AIMON_HOOK_DIR", runFiles(), false));

        assertThat(script).contains("mkdir -m 700 -- \"$p/d\" || exit 70\n")
                .contains("printf '%s' 'QQ==' | /usr/bin/base64 -d > \"$p/d/\"'a'")
                .contains("mkdir -p -- \"$p/d/\"'lib' && printf '%s' 'Qg==' | /usr/bin/base64 -d > \"$p/d/\"'lib/b'")
                .contains("printf 'cannot place %s\\n' 'lib/b' >&2; exit 70; }")
                .contains("printf '%s' '" + "aa".repeat(32) + "  a\n" + "bb".repeat(32) + "  lib/b\n'"
                        + " | PATH=/usr/bin:/bin /usr/bin/sha256sum -c --strict - >/dev/null 2>&1\n")
                .contains("[ \"$s\" = 1 ] && exit 73; exit 70 ) || exit $?\n").contains("[ $n -eq 2 ] || exit 73\n")
                .doesNotContain("$r.files");
        assertThat(script.indexOf("set -C")).isLessThan(script.indexOf("base64 -d >"));
        // Exported last: it wins over the same name among the per-command variables.
        assertThat(script).contains(" 'AIMON_HOOK_DIR'\"=$p/d\" </dev/null\n");
    }

    @Test
    @DisplayName("RUN_DIRECTORY: uploaded files are two uploads, both checked against digests in the script before use")
    void uploadedRunFilesAreCopiedThenRemovedAndAnEmptyDirectoryIsOnlyCounted() {
        final String uploaded = ShellWrapper.isolated(stream("true").runDirectory("AIMON_HOOK_DIR", runFiles(), true));

        final String manifest = "aa".repeat(32) + "  a\n" + "bb".repeat(32) + "  lib/b\n";
        final byte[] blob = "QQ==\nQg==\n".getBytes(StandardCharsets.US_ASCII);
        assertThat(ShellWrapper.manifest(runFiles())).isEqualTo(manifest);
        assertThat(ShellWrapper.blob(runFiles())).isEqualTo(blob);
        // Only the two digests travel: no file name, no file digest, no content.
        assertThat(uploaded).doesNotContain("'a'").doesNotContain("lib/b").doesNotContain("aa".repeat(32))
                .doesNotContain("QQ==").doesNotContain("$r.files")
                .contains("cat -- \"$r.manifest\" > \"$p/.manifest\" && cat -- \"$r.blob\" > \"$p/.blob\" || exit 70\n")
                .contains("rm -f -- \"$r.manifest\" \"$r.blob\"\n")
                .contains("printf '%s' '" + ShellWrapper.sha256Hex(manifest.getBytes(StandardCharsets.UTF_8))
                        + "  .manifest\n" + ShellWrapper.sha256Hex(blob)
                        + "  .blob\n' | PATH=/usr/bin:/bin /usr/bin/sha256sum -c --strict -")
                .contains("exec 4<\"$p/.blob\" || exit 70\n").contains("while IFS= read -r l; do f=${l:66}\n")
                .contains("IFS= read -r b <&4 && printf '%s' \"$b\" | /usr/bin/base64 -d > \"$p/d/$f\"")
                .contains("done < \"$p/.manifest\"\n").contains("exec 4<&-; b=; rm -f -- \"$p/.blob\"\n")
                .contains("/usr/bin/sha256sum -c --strict - <\"$p/.manifest\"").contains("[ $n -eq 2 ] || exit 73\n");
        // Both uploads are checked before a byte of either is used.
        assertThat(uploaded.indexOf("  .blob\n'")).isLessThan(uploaded.indexOf("exec 4<"))
                .isLessThan(uploaded.indexOf("while IFS="));

        final String empty = ShellWrapper.hook(stream("true").runDirectory("D", List.of(), false));
        assertThat(empty).doesNotContain("sha256sum").contains("[ $n -eq 0 ] || exit 73\n");
    }

    @Test
    void anUploadedStdinIsCopiedCheckedAndRedirected() {
        final String script = ShellWrapper.hook(stream("cat").stdinUploaded("cc".repeat(32)));

        assertThat(script).contains("cat -- \"$r.in\" > \"$p/.stdin\" || exit 70\n").contains("rm -f -- \"$r.in\"\n")
                .contains("printf '%s' '" + "cc".repeat(32) + "  .stdin\n'").contains(" <\"$p/.stdin\"\n")
                .doesNotContain("base64 -d");
    }

    @Test
    @DisplayName("RUN_DIRECTORY on a model's command: run files kept, the private directory removed before the trailer")
    void aForegroundRunDirectoryKeepsTheRunFiles() {
        final String script = ShellWrapper.foreground(invocation("true")
                .privateDirectory("/workspace/.aimon-shell/.run.9").runDirectory("V", runFiles(), false));

        assertThat(script).contains(">\"$r.out\" 2>\"$r.err\"").contains("\" fg '/workspace/repo' '' 'V'\"=$p/d\"")
                .contains("\"$r.timedout\"");
        assertThat(script.indexOf("rm -rf -- \"$p\"\n")).isLessThan(script.indexOf("printf '\\n%s exit="));
        // Placed once the lock is held: nothing of the run exists through the lock wait or a busy lock.
        assertThat(script.indexOf("flock -w ")).isLessThan(script.indexOf("mkdir -m 700 -- \"$p\""));
        assertThat(script.indexOf("> \"$d/owner\"")).isLessThan(script.indexOf("p='/workspace/.aimon-shell/.run.9'"));
        assertThat(ShellWrapper.foreground(invocation("true"))).doesNotContain("$p");
    }

    @Test
    void scriptBytesAndDigestsAreWhatTheShellSees() {
        assertThat(ShellWrapper.scriptBytes("é")).isEqualTo(2);
        assertThat(ShellWrapper.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void optionsBecomeQuotedArgumentsAndStreamsCanMerge() {
        final String script = ShellWrapper.foreground(invocation("true").workingDirectory("/tmp/it's")
                .environment(Map.of("K", "v'1")).redirectErrorStream(true).stdinPath("/workspace/in"));

        assertThat(script).contains("'/tmp/it'\\''s' 'K=v'\\''1'").contains(">\"$r.out\" 2>&1")
                .contains("<'/workspace/in'");
    }

    @Test
    void invalidVariableNamesAreReported() {
        assertThat(ShellWrapper.invalidNames(List.of("OK", "_ok2", "1bad", "bad-name"))).containsExactly("1bad",
                "bad-name");
    }

    @Test
    void theInnerScriptKeepsItsStateReadonlyAndReservedFromTheSave() {
        assertThat(ShellWrapper.INNER).contains("readonly __aimon_dir __aimon_run __aimon_base")
                .contains("__aimon_*|PWD|OLDPWD|SHLVL|_) continue")
                .contains("trap '__aimon_nosave=1; exit 143' " + "TERM");
    }
}
