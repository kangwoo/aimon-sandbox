package at.aimon.sandbox.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifierValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "abc", "ABC", "abc123", "my-sandbox", "my_sandbox", "a1b2c3",
            "abcdefghijklmnopqrstuvwxyz0123456789"})
    void validate_ValidIdentifier_NoException(String identifier) {
        assertThatCode(() -> IdentifierValidator.validate(identifier)).doesNotThrowAnyException();
    }

    @Test
    void validate_Null_ThrowsException() {
        assertThatThrownBy(() -> IdentifierValidator.validate(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid identifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc def", "abc.def", "abc/def", "abc@def",
            "abcdefghijklmnopqrstuvwxyz0123456789X"})
    void validate_InvalidIdentifier_ThrowsException(String identifier) {
        assertThatThrownBy(() -> IdentifierValidator.validate(identifier)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid identifier");
    }
}
