package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.testng.annotations.Test;

class TerminalRecordingStyleRunTest {

    @Test
    void replacesTerminalControlPlaceholdersWithSpaces() {
        TerminalRecordingStyleRun run = new TerminalRecordingStyleRun(
            0,
            0,
            "a\u0000b\u001Fc\u007Fd",
            "#FFFFFF",
            "#000000",
            List.of());

        assertThat(run.text()).isEqualTo("a b c d");
    }
}
