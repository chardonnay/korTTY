package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class ControlPaneWriterTest {

    private static final String PANE = "p1a2b";

    private static final String ESC = "\u001b";

    private FakeControlSurface surface;

    private List<String> audit;

    private ControlPaneWriter writer;

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, "t1", "w1", 0, true, true, 4711L));
        audit = new ArrayList<>();
        writer = new ControlPaneWriter(surface, UiDispatcher.DIRECT,
            (verb, pane, detail) -> audit.add(verb + " " + pane + " " + detail));
    }

    private String written() {
        return new String(surface.written(PANE), StandardCharsets.UTF_8);
    }

    @Test
    void lineFeedsBecomeCarriageReturnsAndSubmitAppendsOne() throws Exception {
        WriteResult result = writer.sendText(PANE, "one\ntwo", true, "never", false);

        assertThat(written()).isEqualTo("one\rtwo\r");
        assertThat(result.submitted()).isTrue();
        assertThat(result.bracketed()).isFalse();
        assertThat(result.bytesWritten()).isEqualTo(8);
    }

    @Test
    void autoWrapsOnlyWhenTheTextIsMultiLineAndThePaneReportsDecset2004() throws Exception {
        surface.setBracketedPaste(PANE, true);

        WriteResult single = writer.sendText(PANE, "ls", false, "auto", false);
        assertThat(single.bracketed()).isFalse();
        assertThat(written()).isEqualTo("ls");

        WriteResult multi = writer.sendText(PANE, "one\ntwo", false, "auto", false);
        assertThat(multi.bracketed()).isTrue();
        assertThat(written()).isEqualTo("ls" + ESC + "[200~one\rtwo" + ESC + "[201~");
    }

    @Test
    void autoDoesNotWrapWhenThePaneHasNotEnabledBracketedPaste() throws Exception {
        WriteResult result = writer.sendText(PANE, "one\ntwo", false, "auto", false);

        assertThat(result.bracketed()).isFalse();
        assertThat(written()).isEqualTo("one\rtwo");
    }

    @Test
    void alwaysAndNeverOverrideTheAutomaticDecision() throws Exception {
        assertThat(writer.sendText(PANE, "ls", false, "always", false).bracketed()).isTrue();
        assertThat(written()).isEqualTo(ESC + "[200~ls" + ESC + "[201~");

        surface.setBracketedPaste(PANE, true);
        assertThat(writer.sendText(PANE, "one\ntwo", false, "never", false).bracketed()).isFalse();
    }

    @Test
    void anUnknownBracketedModeIsInvalidParams() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> writer.sendText(PANE, "ls", false, "sometimes", false));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(surface.written(PANE)).isEmpty();
    }

    @Test
    void aFirstLineTheHostShortcutWouldSwallowIsRefusedWithTheCommandName() {
        surface.setHostShortcut(line -> line.startsWith("ai "), "ai");

        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> writer.sendText(PANE, "ai write a test", false, "auto", false));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.HOST_SHORTCUT_CONFLICT);
        assertThat(failure.data()).containsEntry("shortcut", "ai");
        assertThat(surface.written(PANE)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void theShortcutCheckIsSkippedWhenBracketingAppliesOrTheCallerOptedIn() throws Exception {
        surface.setHostShortcut(line -> line.startsWith("ai "), "ai");
        surface.setBracketedPaste(PANE, true);

        assertThat(writer.sendText(PANE, "ai one\ntwo", false, "auto", false).bracketed()).isTrue();
        assertThat(writer.sendText(PANE, "ai single", false, "never", true).bracketed()).isFalse();
    }

    @Test
    void blankTextIsEmptyInput() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> writer.sendText(PANE, "   ", false, "auto", false));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.EMPTY_INPUT);
    }

    @Test
    void aDisconnectedPaneIsNotConnectedAndAFailedWriteIsWriteFailed() {
        surface.failWrite(new ControlApiException(ControlErrorCode.NOT_CONNECTED, "down"));
        assertThat(expectThrows(ControlApiException.class,
            () -> writer.sendText(PANE, "ls", false, "auto", false)).code())
            .isEqualTo(ControlErrorCode.NOT_CONNECTED);

        surface.failWrite(new ControlApiException(ControlErrorCode.WRITE_FAILED, "broken pipe",
            Map.of("pane", PANE)));
        assertThat(expectThrows(ControlApiException.class,
            () -> writer.sendText(PANE, "ls", false, "auto", false)).code())
            .isEqualTo(ControlErrorCode.WRITE_FAILED);
    }

    @Test
    void runRefusesAnEmbeddedNewlineSoOneAuditedCommandCannotSmuggleASecond() {
        ControlApiException failure = expectThrows(ControlApiException.class,
            () -> writer.run(PANE, "ls\nrm -rf /"));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(surface.written(PANE)).isEmpty();
    }

    @Test
    void runSubmitsTheCommand() throws Exception {
        WriteResult result = writer.run(PANE, "ls -l");

        assertThat(written()).isEqualTo("ls -l\r");
        assertThat(result.submitted()).isTrue();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).startsWith("pane.run " + PANE);
    }

    @Test
    void sendKeysNormalisesTheNamesAndWritesTheirBytes() throws Exception {
        WriteResult result = writer.sendKeys(PANE, List.of("Ctrl-C", "ENTER"));

        assertThat(result.keys()).containsExactly("ctrl+c", "enter").inOrder();
        assertThat(surface.written(PANE)).isEqualTo(new byte[] {0x03, '\r'});
        assertThat(result.bracketed()).isFalse();
    }

    @Test
    void anUnknownKeyNameIsASyntaxErrorNotAnInternalError() {
        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> writer.sendKeys(PANE, List.of("hyperspace")));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.UNKNOWN_KEY);
        assertThat(failure.data()).containsKey("known");
    }

    @Test
    void everyCallEmitsExactlyOneAuditLineWithByteCountsAndNeverTheText() throws Exception {
        writer.sendText(PANE, "secret-token", true, "never", false);
        writer.sendKeys(PANE, List.of("enter"));

        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).isEqualTo("pane.send_text " + PANE
            + " bytes=13 bracketed=false submitted=true");
        assertThat(audit.get(1)).isEqualTo("pane.send_keys " + PANE + " keys=1 bytes=1");
        for (String line : audit) {
            assertThat(line).doesNotContain("secret-token");
        }
    }
}
