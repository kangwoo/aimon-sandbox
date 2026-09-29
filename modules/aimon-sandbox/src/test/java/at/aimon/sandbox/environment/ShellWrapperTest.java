package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
