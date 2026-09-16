package at.aimon.sandbox.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ByteFormatUtilsTest {

    @Test
    void formatBytes_Zero() {
        assertThat(ByteFormatUtils.formatBytes(0)).isEqualTo("0 B");
    }

    @Test
    void formatBytes_Bytes() {
        assertThat(ByteFormatUtils.formatBytes(512)).isEqualTo("512 B");
        assertThat(ByteFormatUtils.formatBytes(1023)).isEqualTo("1023 B");
    }

    @Test
    void formatBytes_Kilobytes() {
        assertThat(ByteFormatUtils.formatBytes(1024)).isEqualTo("1.0 KB");
        assertThat(ByteFormatUtils.formatBytes(1536)).isEqualTo("1.5 KB");
    }

    @Test
    void formatBytes_Megabytes() {
        assertThat(ByteFormatUtils.formatBytes(1024 * 1024)).isEqualTo("1.0 MB");
        assertThat(ByteFormatUtils.formatBytes(5 * 1024 * 1024)).isEqualTo("5.0 MB");
    }
}
