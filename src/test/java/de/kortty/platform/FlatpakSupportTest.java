package de.kortty.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class FlatpakSupportTest {

    @Test
    void recognizesOnlyNonBlankFlatpakId() {
        assertThat(FlatpakSupport.isFlatpakEnvironment(Map.of("FLATPAK_ID", FlatpakSupport.APP_ID))).isTrue();
        assertThat(FlatpakSupport.isFlatpakEnvironment(Map.of("FLATPAK_ID", "  "))).isFalse();
        assertThat(FlatpakSupport.isFlatpakEnvironment(Map.of())).isFalse();
    }

    @Test
    void wrapsHostCommandWithDirectoryAndTerminalEnvironment() {
        List<String> wrapped = FlatpakSupport.hostCommand(
            List.of("/bin/zsh", "-l"),
            "/home/daniel/work tree",
            Map.of(
                "FLATPAK_ID", FlatpakSupport.APP_ID,
                "TERM", "xterm-256color",
                "LANG", "de_DE.UTF-8",
                "SECRET", "must-not-leak"));

        assertThat(wrapped).containsExactly(
            "flatpak-spawn",
            "--host",
            "--watch-bus",
            "--directory=/home/daniel/work tree",
            "--env=TERM=xterm-256color",
            "--env=LANG=de_DE.UTF-8",
            "/bin/zsh",
            "-l").inOrder();
    }

    @Test
    void leavesCommandUntouchedOutsideFlatpak() {
        assertThat(FlatpakSupport.hostCommand(List.of("bash", "-l"), "/tmp", Map.of()))
            .containsExactly("bash", "-l").inOrder();
    }

    @Test
    void quotesBundlePathForManualInstall() {
        String command = FlatpakSupport.installCommand(Path.of("downloads", "daniel's korTTY.flatpak"));

        assertThat(command).startsWith("flatpak install --user '");
        assertThat(command).contains("daniel'\\''s korTTY.flatpak'");
    }
}
