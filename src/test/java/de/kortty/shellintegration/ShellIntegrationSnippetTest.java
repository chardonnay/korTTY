package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

/**
 * Which tab <i>Set Up Shell Integration…</i> opens on, and the text it shows and copies; that the
 * text equals the shipped resources and the guide is pinned in {@code ShellIntegrationSnippetsTest}.
 */
class ShellIntegrationSnippetTest {

    @Test
    void namesTheShellOfAPathOrACommandName() {
        assertThat(ShellIntegrationSnippet.forShell("bash")).isEqualTo(ShellIntegrationSnippet.BASH);
        assertThat(ShellIntegrationSnippet.forShell("/bin/zsh")).isEqualTo(ShellIntegrationSnippet.ZSH);
        assertThat(ShellIntegrationSnippet.forShell("/opt/homebrew/bin/fish")).isEqualTo(ShellIntegrationSnippet.FISH);
        assertThat(ShellIntegrationSnippet.forShell("  /usr/local/bin/bash  ")).isEqualTo(ShellIntegrationSnippet.BASH);
        assertThat(ShellIntegrationSnippet.forShell("/BIN/ZSH")).isEqualTo(ShellIntegrationSnippet.ZSH);
    }

    @Test
    void readsALoginShellAndAWindowsExecutable() {
        assertThat(ShellIntegrationSnippet.forShell("-zsh")).isEqualTo(ShellIntegrationSnippet.ZSH);
        assertThat(ShellIntegrationSnippet.forShell("C:\\Program Files\\Git\\bin\\bash.exe"))
            .isEqualTo(ShellIntegrationSnippet.BASH);
    }

    @Test
    void hasNoSnippetForOtherShells() {
        assertThat(ShellIntegrationSnippet.forShell(null)).isNull();
        assertThat(ShellIntegrationSnippet.forShell("")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("   ")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("/bin/sh")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("/bin/dash")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("powershell.exe")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("cmd.exe")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("/usr/bin/env")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("/usr/bin/bashful")).isNull();
        assertThat(ShellIntegrationSnippet.forShell("/home/me/bash/")).isNull();
    }

    @Test
    void everySnippetHasItsResourceAndItsInstructions() {
        assertThat(ShellIntegrationSnippet.BASH.resource()).isEqualTo("/shell-integration/kortty.bash");
        assertThat(ShellIntegrationSnippet.ZSH.resource()).isEqualTo("/shell-integration/kortty.zsh");
        assertThat(ShellIntegrationSnippet.FISH.resource()).isEqualTo("/shell-integration/kortty.fish");
        assertThat(ShellIntegrationSnippet.BASH.instructionsKey()).isEqualTo("terminal.shellIntegration.setup.bash");
        assertThat(ShellIntegrationSnippet.ZSH.instructionsKey()).isEqualTo("terminal.shellIntegration.setup.zsh");
        assertThat(ShellIntegrationSnippet.FISH.instructionsKey()).isEqualTo("terminal.shellIntegration.setup.fish");
    }

    @Test
    void theTextHasLineFeedsOnlyAndEndsWithOneLineBreak() {
        assertThat(ShellIntegrationSnippet.normalize("a\r\nb\rc")).isEqualTo("a\nb\nc\n");
        assertThat(ShellIntegrationSnippet.normalize("fi\n\n\n")).isEqualTo("fi\n");
        assertThat(ShellIntegrationSnippet.normalize("fi")).isEqualTo("fi\n");
        assertThat(ShellIntegrationSnippet.normalize("  if x\nfi \r\n")).isEqualTo("  if x\nfi\n");
        for (ShellIntegrationSnippet snippet : ShellIntegrationSnippet.values()) {
            String text = snippet.text();
            assertThat(text).doesNotContain("\r");
            assertThat(text).endsWith("\n");
            assertThat(text).doesNotContain("\n\n\n");
            assertThat(text).contains("__kortty_si_loaded");
        }
    }
}
