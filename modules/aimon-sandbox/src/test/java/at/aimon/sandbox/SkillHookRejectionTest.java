package at.aimon.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.parser.MarkdownSkillParser;

/** Skill-declared shell hooks are refused in sandbox mode (§12.1; §16 row 16). */
class SkillHookRejectionTest {

    private static String skill(String frontmatter) {
        return "---\nname: sample\ndescription: A skill\n" + frontmatter + "---\n\nRun ./scripts/build.sh";
    }

    @Test
    @DisplayName("§16: a skill declaring a shell-action hook is not loaded and nothing runs on the host")
    void shellHookSkillIsRejectedAndNothingRunsOnHost() {
        final Path marker = Path.of(System.getProperty("java.io.tmpdir"), "aimon-hook-" + UUID.randomUUID());
        final MarkdownSkillParser parser = WorkspaceSandbox.markdownSkillParser();

        assertThatThrownBy(() -> parser.parse("sample",
                skill("hooks:\n  preTool:\n    - action: { type: shell, " + "command: \"touch " + marker + "\" }\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("shell")
                .hasMessageContaining("not supported");
        assertThatThrownBy(() -> parser.parse("sample",
                skill("hooks:\n  onStart:\n    - action: { type: shell, " + "command: \"touch " + marker + "\" }\n")))
                .isInstanceOf(SkillParseException.class);

        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void skillsWithoutShellHooksStillLoad() {
        final Skill skill = WorkspaceSandbox.markdownSkillParser().parse("sample",
                skill("hooks:\n  preTool:\n    - action: { type: deny, reason: \"not here\" }\n"));

        assertThat(skill.getName()).isEqualTo("sample");
    }
}
