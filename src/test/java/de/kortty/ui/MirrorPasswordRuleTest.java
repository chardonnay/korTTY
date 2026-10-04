package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.testng.annotations.Test;

/**
 * While the pane the user types in shows a password prompt, broadcast mode mirrors the keys only into
 * the panes that show one too, so a sudo password never runs as a command at an ordinary shell prompt,
 * where it would land in the history and the session journal. The rule is pure and checked here; that
 * every tab installs it, and how a pane's cursor line is read, is read from the source (CRLF-safe),
 * because a live pane needs a JavaFX toolkit.
 */
class MirrorPasswordRuleTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    private static final String SUDO_PROMPT = "[sudo] password for anna: ";
    private static final String SHELL_PROMPT = "anna@web1:~$ ";

    @Test
    void aSourceAtAPasswordPromptMirrorsOnlyIntoPanesAtOneToo() {
        Map<String, String> cursorLines = new HashMap<>();
        cursorLines.put("web2", "[sudo] password for anna:");
        cursorLines.put("web3", SHELL_PROMPT);
        cursorLines.put("db1", "Enter passphrase for key '/home/anna/.ssh/id_ed25519': ");
        cursorLines.put("vim", "  password: hunter2");
        cursorLines.put("unreadable", null);

        assertThat(receivers(SUDO_PROMPT, cursorLines)).containsExactly("web2", "db1").inOrder();
    }

    @Test
    void aSourceAtAnyOtherLineMirrorsIntoEveryPaneWithoutReadingThem() {
        List<String> read = new ArrayList<>();
        Predicate<String> receivers = MirrorPasswordRule.receiversOf(SHELL_PROMPT, pane -> {
            read.add(pane);
            return SHELL_PROMPT;
        });

        assertThat(receivers.test("web2")).isTrue();
        assertThat(receivers.test("web3")).isTrue();
        assertWithMessage("an ordinary key reads no other pane").that(read).isEmpty();
    }

    @Test
    void aSourceWhoseLineCannotBeReadMirrorsAsBefore() {
        assertThat(MirrorPasswordRule.receiversOf(null, pane -> null).test("web2")).isTrue();
        assertThat(MirrorPasswordRule.admits(null, SHELL_PROMPT)).isTrue();
        assertThat(MirrorPasswordRule.admits("", SHELL_PROMPT)).isTrue();
    }

    @Test
    void admitsFollowsBothLines() {
        assertThat(MirrorPasswordRule.admits(SUDO_PROMPT, "Password:")).isTrue();
        assertThat(MirrorPasswordRule.admits(SUDO_PROMPT, SHELL_PROMPT)).isFalse();
        assertThat(MirrorPasswordRule.admits(SUDO_PROMPT, null)).isFalse();
        assertThat(MirrorPasswordRule.admits(SHELL_PROMPT, SUDO_PROMPT)).isTrue();
        assertThat(MirrorPasswordRule.admits("Passwort:", "Kennwort: ")).isTrue();
        // Output that only mentions a password is no prompt.
        assertThat(MirrorPasswordRule.admits("cat passwords.txt", SHELL_PROMPT)).isTrue();
    }

    @Test
    void everyTabAppliesTheRuleToTheCursorLineOfEachPane() throws IOException {
        String view = Files.readString(TERMINAL_VIEW, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(view).contains("splitPane.setMirrorInputRule(source -> MirrorPasswordRule.receiversOf(\n"
            + "            cursorLineOf(source), TerminalView::cursorLineOf));");
        String cursorLine = body(view, "static @Nullable String cursorLineOf(@Nullable SithTermFxWidget widget) {");
        assertThat(cursorLine).contains("buffer.lock();");
        assertThat(cursorLine).contains("buffer.unlock();");
        assertThat(cursorLine.indexOf("buffer.lock();")).isLessThan(cursorLine.indexOf("terminal.getCursorY()"));
        assertWithMessage("the cursor row is 1-based")
            .that(cursorLine).contains("int row = terminal.getCursorY() - 1;");
        assertThat(cursorLine).contains("row >= 0 && row < buffer.getHeight() ? buffer.getLine(row).getText() : null");
        assertThat(cursorLine).contains("catch (RuntimeException e)");
    }

    private static List<String> receivers(String sourceLine, Map<String, String> cursorLines) {
        Predicate<String> receivers = MirrorPasswordRule.receiversOf(sourceLine, cursorLines::get);
        List<String> admitted = new ArrayList<>();
        for (String pane : List.of("web2", "web3", "db1", "vim", "unreadable")) {
            if (receivers.test(pane)) {
                admitted.add(pane);
            }
        }
        return admitted;
    }

    /** The text from {@code signature} to the first line that closes a member at four spaces. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("member %s", signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }
}
