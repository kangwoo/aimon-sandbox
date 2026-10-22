package at.aimon.sandbox.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StderrTrailerFilter — the command's stderr lines, without the wrapper's trailer")
class StderrTrailerFilterTest {

    private static final String NONCE = "AIMON-0123456789abcdef";

    private final List<String> lines = new ArrayList<>();
    private final StderrTrailerFilter filter = new StderrTrailerFilter(NONCE, lines::add);

    private void feed(String... in) {
        for (String line : in) {
            filter.accept(line);
        }
        filter.finish();
    }

    @Test
    @DisplayName("the trailer and the blank line the wrapper prints before it are dropped")
    void dropsTrailerAndItsBlankLine() {
        feed("first", "last", "", NONCE + " exit=2 cwd=0");

        assertThat(lines).containsExactly("first", "last");
    }

    @Test
    @DisplayName("a command's own trailing blank line is kept")
    void keepsTheCommandsTrailingBlank() {
        feed("foo", "", "", NONCE + " exit=0 cwd=0");

        assertThat(lines).containsExactly("foo", "");
    }

    @Test
    @DisplayName("a blank line in the middle is kept")
    void keepsMidStreamBlanks() {
        feed("a", "", "b", "", NONCE + " exit=0 cwd=0");

        assertThat(lines).containsExactly("a", "", "b");
    }

    @Test
    @DisplayName("an unterminated last line: the wrapper's newline ends it, no blank line is made")
    void unterminatedLastLine() {
        feed("partial", NONCE + " exit=1 cwd=0");

        assertThat(lines).containsExactly("partial");
    }

    @Test
    @DisplayName("without a trailer (timeout, cancel, lost exec) a held blank line was the command's")
    void noTrailerReleasesTheHeldBlank() {
        feed("still running", "");

        assertThat(lines).containsExactly("still running", "");
    }

    @Test
    @DisplayName("a line that only mentions the nonce elsewhere is the command's")
    void nonceMustLeadTheLine() {
        feed("echo " + NONCE + " exit=0", NONCE + "x exit=0");

        assertThat(lines).containsExactly("echo " + NONCE + " exit=0", NONCE + "x exit=0");
    }
}
