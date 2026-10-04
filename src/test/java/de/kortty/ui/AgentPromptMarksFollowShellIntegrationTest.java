package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * The AI Agent's commands and the close question need to know whether an SSH session is at its shell
 * prompt. The OSC 133 marks in the raw output tell that, but only while shell integration is on: the
 * old AI setting "Use OSC 133 prompt markers when the shell already provides them" never changed
 * anything and is gone, so one switch, {@code shellIntegrationEnabled}, decides for every use of the
 * marks. Without it, only the prompt's text tells.
 */
class AgentPromptMarksFollowShellIntegrationTest {

    private static final String ESC = "\u001B";
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    private static String mark(String letter) {
        return ESC + "]133;" + letter + "\u0007";
    }

    @Test
    void aPromptMarkSaysTheShellIsAtItsPrompt() {
        assertThat(TerminalView.promptReadinessFromOsc133(mark("A") + "user@host:~$ ", ON)).isTrue();
        assertThat(TerminalView.promptReadinessFromOsc133("user@host:~$ " + mark("B"), ON)).isTrue();
        assertWithMessage("ST-terminated, as some shells send it")
            .that(TerminalView.promptReadinessFromOsc133(ESC + "]133;A" + ESC + "\\$ ", ON)).isTrue();
    }

    @Test
    void aCommandStartSaysItIsNot() {
        assertThat(TerminalView.promptReadinessFromOsc133(mark("C") + "building...\r\n", ON)).isFalse();
    }

    @Test
    void theLastMarkInAChunkDecides() {
        String shortCommand = mark("C") + "file.txt\r\n" + mark("D;0") + mark("A") + "$ " + mark("B");
        assertWithMessage("a short command's start, output, end and the next prompt in one read")
            .that(TerminalView.promptReadinessFromOsc133(shortCommand, ON)).isTrue();
        assertWithMessage("Enter at the prompt, and the command starts in the same read")
            .that(TerminalView.promptReadinessFromOsc133(mark("A") + "$ " + mark("B") + "make\r\n" + mark("C"), ON))
            .isFalse();
    }

    @Test
    void outputWithoutAPromptOrCommandMarkSaysNothing() {
        assertThat(TerminalView.promptReadinessFromOsc133("plain output\r\n", ON)).isNull();
        assertThat(TerminalView.promptReadinessFromOsc133("", ON)).isNull();
        assertThat(TerminalView.promptReadinessFromOsc133(null, ON)).isNull();
        assertWithMessage("a command end alone: the next prompt's own mark follows")
            .that(TerminalView.promptReadinessFromOsc133(mark("D;1"), ON)).isNull();
        assertWithMessage("OSC 7 and other OSCs are not prompt marks")
            .that(TerminalView.promptReadinessFromOsc133(ESC + "]7;file://host/tmp\u0007" + ESC + "]0;title\u0007", ON))
            .isNull();
    }

    @Test
    void withShellIntegrationOffTheMarksSayNothing() {
        assertThat(TerminalView.promptReadinessFromOsc133(mark("A") + "$ " + mark("B"), OFF)).isNull();
        assertThat(TerminalView.promptReadinessFromOsc133(mark("C") + "output", OFF)).isNull();
    }

    @Test
    void theSettingIsReadOnlyForAChunkWithAMark() {
        AtomicInteger reads = new AtomicInteger();
        BooleanSupplier counting = () -> {
            reads.incrementAndGet();
            return true;
        };
        TerminalView.promptReadinessFromOsc133("a lot of plain output\r\n", counting);
        TerminalView.promptReadinessFromOsc133(mark("D;0"), counting);
        assertThat(reads.get()).isEqualTo(0);
        TerminalView.promptReadinessFromOsc133(mark("A"), counting);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void theViewReadsThePromptMarksThroughTheShellIntegrationSetting() throws IOException {
        String view = source("ui/TerminalView.java");
        String record = body(view, "private void recordAgentShortcutPromptSignal(SshTtyConnector sourceConnector, String data) {");
        assertThat(record).contains(
            "Boolean markedPromptReady = promptReadinessFromOsc133(data, TerminalView::isShellIntegrationEnabled);");
        assertWithMessage("no OSC 133 sniffing besides the one that honours the setting")
            .that(record).doesNotContain("\\u001B]133;");
        assertWithMessage("the prompt's text still tells when the marks do not")
            .that(record).contains("if (looksLikeShellPrompt(lastLine)) {");
    }

    @Test
    void theOldAiPromptMarkerCheckboxIsGone() throws IOException {
        String dialog = source("ui/SettingsDialog.java");
        assertThat(dialog).doesNotContain("settings.ai.promptHook");
        assertThat(dialog).doesNotContain("PromptHookEnabled");
        for (String file : List.of("ui/TerminalView.java", "ui/MainWindow.java")) {
            assertWithMessage(file + " reads the old setting").that(source(file)).doesNotContain("PromptHookEnabled");
        }
        try (Stream<Path> bundles = Files.list(Path.of("src/main/resources/i18n"))) {
            List<Path> properties = bundles
                .filter(path -> path.getFileName().toString().matches("messages(_[a-z]{2})?\\.properties"))
                .toList();
            assertThat(properties).hasSize(8);
            for (Path bundle : properties) {
                assertWithMessage(bundle.getFileName().toString())
                    .that(Files.readString(bundle, StandardCharsets.UTF_8)).doesNotContain("settings.ai.promptHook");
            }
        }
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(Path.of("src/main/java/de/kortty", file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration is {@code signature}, up to its closing brace at that indent. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing " + signature).that(start).isAtLeast(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}\n", start);
        assertWithMessage("no end of " + signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
