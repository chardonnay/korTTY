package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.Osc52Support.Rejection;
import de.kortty.shellintegration.OwnedOsc;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.shellintegration.ShellIntegrationEvent.ClipboardWrite;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import de.kortty.ui.TerminalClipboardWriter.Outcome;
import de.kortty.ui.TerminalClipboardWriter.Result;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.function.BiFunction;
import org.testng.annotations.Test;

/**
 * A program's clipboard write from the pane's output to the clipboard: {@code OSC 52} is taken out
 * of real output by {@link ShellIntegrationTtyConnector}, never reaches the screen and is never
 * answered; {@link TerminalClipboardWriter} writes its text only with the setting on, and the status
 * bar names the tab whatever happened.
 */
class Osc52ClipboardFlowTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";

    @Test
    void writesAreTakenOutOfTheOutputAndQueriesAreNeverAnswered() throws IOException {
        Screen screen = new Screen(60, 4, 100);
        ScriptedConnector connector = ScriptedConnector.inChunksOf("before"
            + ESC + "]52;c;" + base64("secret token") + BEL + "-"
            + ESC + "]52;c;?" + ST
            + ESC + "]52;;" + Base64.getMimeEncoder().encodeToString("x".repeat(100).getBytes(StandardCharsets.UTF_8)) + ST
            + "after", 7);
        List<ShellIntegrationEvent> events = new ArrayList<>();

        screen.run(new ShellIntegrationTtyConnector(connector, events::add));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new ClipboardWrite("c", base64("secret token")));
        assertThat(((ClipboardWrite) events.get(1)).selection()).isEmpty();
        assertWithMessage("neither the writes nor the query reach the screen")
            .that(screen.buffer.getLine(0).getText().strip()).isEqualTo("before-after");
        assertWithMessage("nothing is sent back: the clipboard is never handed to the program")
            .that(connector.written()).isEmpty();
    }

    @Test
    void nothingIsDecodedOrWrittenWhileTheSettingIsOff() {
        List<String> clipboard = new ArrayList<>();
        Outcome outcome = TerminalClipboardWriter.write(new ClipboardWrite("c", base64("rm -rf ~\n")), false, clipboard::add);

        assertThat(outcome.result()).isEqualTo(Result.BLOCKED);
        assertThat(clipboard).isEmpty();
        assertWithMessage("not even invalid data is looked at")
            .that(TerminalClipboardWriter.write(new ClipboardWrite("c", "!!"), false, clipboard::add).result())
            .isEqualTo(Result.BLOCKED);
    }

    @Test
    void withTheSettingOnValidTextGoesOnTheClipboard() {
        List<String> clipboard = new ArrayList<>();
        String text = "copied in vim 🙂\nsecond line";
        Outcome outcome = TerminalClipboardWriter.write(new ClipboardWrite("c",
            Base64.getMimeEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))), true, clipboard::add);

        assertThat(clipboard).containsExactly(text);
        assertThat(outcome.result()).isEqualTo(Result.WRITTEN);
        assertWithMessage("characters as the user counts them: the emoji is one")
            .that(outcome.characters()).isEqualTo(text.codePointCount(0, text.length()));
        assertThat(outcome.bytes()).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void invalidOrEmptyDataLeavesTheClipboardAlone() {
        List<String> clipboard = new ArrayList<>();
        Outcome invalid = TerminalClipboardWriter.write(new ClipboardWrite("c", "not base64!"), true, clipboard::add);
        Outcome notText = TerminalClipboardWriter.write(new ClipboardWrite("c",
            Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, 0})), true, clipboard::add);
        Outcome empty = TerminalClipboardWriter.write(new ClipboardWrite("c", " \n"), true, clipboard::add);

        assertThat(clipboard).isEmpty();
        assertThat(invalid).isEqualTo(new Outcome(Result.REJECTED, 0, 0, Rejection.NOT_BASE64));
        assertThat(notText).isEqualTo(new Outcome(Result.REJECTED, 0, 0, Rejection.NOT_UTF8));
        assertThat(empty).isEqualTo(new Outcome(Result.IGNORED, 0, 0, Rejection.EMPTY));
    }

    @Test
    void aWriteTooLongToKeepIsRefusedWithAMessageNotDroppedUnnoticed() throws IOException {
        Screen screen = new Screen(60, 4, 100);
        String tooLong = "QUFB".repeat(OwnedOsc.CLIPBOARD.maxPayloadLength() / 4 + 10);
        List<ShellIntegrationEvent> events = new ArrayList<>();

        screen.run(new ShellIntegrationTtyConnector(
            ScriptedConnector.inChunksOf("x" + ESC + "]52;c;" + tooLong + BEL + "y", 4096), events::add));

        assertWithMessage("the splitter keeps none of it and reports its size")
            .that(events).containsExactly(new ShellIntegrationEvent.Oversize(OwnedOsc.CLIPBOARD));
        assertThat(screen.buffer.getLine(0).getText().strip()).isEqualTo("xy");
        List<String> clipboard = new ArrayList<>();
        assertWithMessage("allowed, it is refused as too large, which the status bar says")
            .that(TerminalClipboardWriter.write(ClipboardWrite.overCap(), true, clipboard::add))
            .isEqualTo(new Outcome(Result.REJECTED, 0, 0, Rejection.TOO_LARGE));
        assertWithMessage("not allowed, it is a blocked attempt like any other write")
            .that(TerminalClipboardWriter.write(ClipboardWrite.overCap(), false, clipboard::add).result())
            .isEqualTo(Result.BLOCKED);
        assertThat(clipboard).isEmpty();
        assertThat(ClipboardWrite.overCap().summary()).isEqualTo("ClipboardWrite(too large)");
    }

    @Test
    void theStatusBarNamesTheTabWhateverHappened() throws IOException {
        BiFunction<String, Object[], String> english = filling(bundle());
        assertThat(TerminalClipboardWriter.statusText(new Outcome(Result.WRITTEN, 12, 12, null), "web-01", english))
            .isEqualTo("A program in the tab “web-01” copied 12 characters to the clipboard (OSC 52).");
        assertThat(TerminalClipboardWriter.statusText(Outcome.blocked(), "web-01", english))
            .isEqualTo("A program in the tab “web-01” tried to copy text to the clipboard (OSC 52). "
                + "Settings → Terminal can allow it.");
        assertThat(TerminalClipboardWriter.statusText(new Outcome(Result.REJECTED, 0, 0, Rejection.TOO_LARGE), "web-01",
            english)).startsWith("A program in the tab “web-01” sent text for the clipboard (OSC 52)");
        assertWithMessage("an empty write is not worth a message")
            .that(TerminalClipboardWriter.statusText(new Outcome(Result.IGNORED, 0, 0, Rejection.EMPTY), "web-01", english))
            .isNull();
    }

    @Test
    void aTabNameTheServerSetCannotDisguiseTheMessage() throws IOException {
        BiFunction<String, Object[], String> english = filling(bundle());
        assertWithMessage("control and bidi characters go")
            .that(TerminalClipboardWriter.statusText(Outcome.blocked(), "prod‮bd\u001B[31m", english))
            .startsWith("A program in the tab “prodbd[31m” tried");
        assertWithMessage("the name fills the last placeholder, so a placeholder in it stays text")
            .that(TerminalClipboardWriter.statusText(new Outcome(Result.WRITTEN, 3, 3, null), "x{0}{1}", english))
            .isEqualTo("A program in the tab “x{0}{1}” copied 3 characters to the clipboard (OSC 52).");
        String longName = "n".repeat(TerminalClipboardWriter.MAX_TAB_NAME_CHARS + 30);
        assertThat(TerminalClipboardWriter.statusText(Outcome.blocked(), longName, english))
            .contains("“" + "n".repeat(TerminalClipboardWriter.MAX_TAB_NAME_CHARS) + "”");
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The translations as {@code I18n.get(key, args)} gives them: plain replacement of {@code {n}}, in order. */
    private static BiFunction<String, Object[], String> filling(Properties bundle) {
        return (key, args) -> {
            String text = bundle.getProperty(key, key);
            for (int i = 0; i < args.length; i++) {
                text = text.replace("{" + i + "}", String.valueOf(args[i]));
            }
            return text;
        };
    }

    private static Properties bundle() throws IOException {
        try (InputStream in = Osc52ClipboardFlowTest.class.getClassLoader()
                .getResourceAsStream("i18n/messages.properties")) {
            assertThat(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
