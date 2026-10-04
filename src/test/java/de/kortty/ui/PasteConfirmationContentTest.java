package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.paste.PasteConfirmationRequest;
import de.kortty.paste.PasteInspection;
import de.kortty.paste.PasteReason;
import de.kortty.paste.PasteSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * What the paste confirmation tells the user, worked out without JavaFX. The translator answers with
 * the key and its arguments, so each assertion names the text it expects.
 */
class PasteConfirmationContentTest {

    private static final String ESC = "\u001b";

    private static final String RLO = "\u202e";

    private final List<String> requestedKeys = new ArrayList<>();

    private final PasteConfirmationContent.Translator translator = (key, args) -> {
        requestedKeys.add(key);
        return args.length == 0 ? key : key + Arrays.toString(args);
    };

    private PasteConfirmationContent content(String text, Set<PasteReason> reasons, boolean bracketed,
            PasteSource source, boolean broadcast, String label) {
        PasteConfirmationRequest request = new PasteConfirmationRequest(label, text, reasons, bracketed, source, broadcast);
        return PasteConfirmationContent.of(request, PasteInspection.of(text), translator, Locale.ENGLISH);
    }

    private PasteConfirmationContent content(String text, Set<PasteReason> reasons) {
        return content(text, reasons, false, PasteSource.CLIPBOARD, false, "prod-db");
    }

    @Test
    void theQuestionNamesThePaneAndTheSummaryCountsLinesAndSize() {
        PasteConfirmationContent content = content("echo one\necho two\n", EnumSet.of(PasteReason.MULTI_LINE));

        assertThat(content.title()).isEqualTo("terminal.paste.confirm.title");
        assertThat(content.header()).isEqualTo("terminal.paste.confirm.header[prod-db]");
        assertThat(content.summary())
            .isEqualTo("terminal.paste.confirm.summary[2, terminal.paste.confirm.size.bytes[18]]");
        assertThat(content.reasons()).containsExactly("terminal.paste.confirm.reason.multiLine");
        assertThat(content.pasteButton()).isEqualTo("terminal.paste.confirm.paste");
        assertThat(content.cancelButton()).isEqualTo("dialog.cancel");
        assertThat(content.settingsHint()).isEqualTo("terminal.paste.confirm.settingsHint");
    }

    @Test
    void aConnectionWithItsOwnPasteWarningPointsToTheConnectionInsteadOfSettings() {
        PasteConfirmationRequest request = new PasteConfirmationRequest("prod-db", "a\nb",
            EnumSet.of(PasteReason.MULTI_LINE), true, PasteSource.CLIPBOARD, false, true);

        PasteConfirmationContent content = PasteConfirmationContent.of(request, PasteInspection.of("a\nb"),
            translator, Locale.ENGLISH);

        assertThat(content.settingsHint()).isEqualTo("terminal.paste.confirm.settingsHint.connection");
        assertWithMessage("a request without the flag comes from Settings → Terminal")
            .that(new PasteConfirmationRequest("x", "a", Set.of(PasteReason.LARGE), false, PasteSource.CLIPBOARD,
                false).setByConnection())
            .isFalse();
    }

    @Test
    void aPasteThatAsksOnlyForItsSizePointsToSettingsEvenWhenTheConnectionSetsItsOwnWarning() {
        String text = "x".repeat(6 * 1024);
        PasteConfirmationRequest request = new PasteConfirmationRequest("prod-db", text,
            EnumSet.of(PasteReason.LARGE), false, PasteSource.CLIPBOARD, false, true);

        PasteConfirmationContent content = PasteConfirmationContent.of(request, PasteInspection.of(text),
            translator, Locale.ENGLISH);

        assertWithMessage("the size check is only in Settings → Terminal, never in the connection editor")
            .that(content.settingsHint()).isEqualTo("terminal.paste.confirm.settingsHint");
    }

    @Test
    void aPasteThatAsksForTheConnectionsWarningAndItsSizeNamesBothPlaces() {
        String text = "x".repeat(6 * 1024) + "\n" + ESC;
        PasteConfirmationRequest request = new PasteConfirmationRequest("prod-db", text,
            EnumSet.of(PasteReason.CONTROL_CHARACTERS, PasteReason.LARGE), false, PasteSource.CLIPBOARD, false,
            true);

        PasteConfirmationContent content = PasteConfirmationContent.of(request, PasteInspection.of(text),
            translator, Locale.ENGLISH);

        assertThat(content.settingsHint())
            .isEqualTo("terminal.paste.confirm.settingsHint.connection terminal.paste.confirm.settingsHint.size");
    }

    @Test
    void aPaneWithoutANameIsCalledTheTerminal() {
        assertThat(content("a\nb", EnumSet.of(PasteReason.MULTI_LINE), false, PasteSource.CLIPBOARD, false, "  ")
            .header()).isEqualTo("terminal.paste.confirm.header.unnamed");
    }

    @Test
    void aSingleLineThatEndsInALineBreakSaysItRunsAtOnce() {
        assertThat(content("rm -rf build\n", EnumSet.of(PasteReason.MULTI_LINE)).reasons())
            .containsExactly("terminal.paste.confirm.reason.trailingLineBreak");
        assertWithMessage("two lines are not one line that runs at once")
            .that(content("a\nb\n", EnumSet.of(PasteReason.MULTI_LINE)).reasons())
            .containsExactly("terminal.paste.confirm.reason.multiLine");
    }

    @Test
    void controlAndBidiCharactersAreCountedSeparately() {
        String text = "ls" + ESC + "[2J\u0003 " + RLO + "txt.exe";
        PasteConfirmationContent content = content(text, EnumSet.of(PasteReason.CONTROL_CHARACTERS));

        assertThat(content.reasons()).containsExactly(
            "terminal.paste.confirm.reason.controlCharacters[2]",
            "terminal.paste.confirm.reason.bidiCharacters[1]").inOrder();
        assertThat(content.previewLegend()).isEqualTo("terminal.paste.confirm.previewLegend");
        assertThat(content.previewText()).doesNotContain(ESC);
        assertThat(content.previewText()).doesNotContain(RLO);
        assertThat(content.previewText()).contains("\u241b[2J");
        assertThat(content.previewText()).contains("<U+202E>");
    }

    @Test
    void onlyBidiCharactersLeaveOutTheControlCharacterLine() {
        assertThat(content("abc" + RLO, EnumSet.of(PasteReason.CONTROL_CHARACTERS)).reasons())
            .containsExactly("terminal.paste.confirm.reason.bidiCharacters[1]");
    }

    @Test
    void theReasonsFollowTheOrderOfTheEnum() {
        String text = "x".repeat(6000) + "\n" + ESC + "y";
        PasteConfirmationContent content = content(text,
            EnumSet.of(PasteReason.LARGE, PasteReason.CONTROL_CHARACTERS, PasteReason.MULTI_LINE));

        assertThat(content.reasons()).containsExactly(
            "terminal.paste.confirm.reason.multiLine",
            "terminal.paste.confirm.reason.controlCharacters[1]",
            "terminal.paste.confirm.reason.large[terminal.paste.confirm.size.kib[5.9]]").inOrder();
    }

    @Test
    void sizesAreShownInBytesKibOrMibWithOneDecimal() {
        assertThat(PasteConfirmationContent.formatSize(0, translator, Locale.ENGLISH))
            .isEqualTo("terminal.paste.confirm.size.bytes[0]");
        assertThat(PasteConfirmationContent.formatSize(1023, translator, Locale.ENGLISH))
            .isEqualTo("terminal.paste.confirm.size.bytes[1023]");
        assertThat(PasteConfirmationContent.formatSize(1024, translator, Locale.ENGLISH))
            .isEqualTo("terminal.paste.confirm.size.kib[1.0]");
        assertThat(PasteConfirmationContent.formatSize(5 * 1024 + 1, translator, Locale.ENGLISH))
            .isEqualTo("terminal.paste.confirm.size.kib[5.0]");
        assertThat(PasteConfirmationContent.formatSize(3 * 1024 * 1024 / 2, translator, Locale.ENGLISH))
            .isEqualTo("terminal.paste.confirm.size.mib[1.5]");
        assertWithMessage("the decimal separator follows the UI language")
            .that(PasteConfirmationContent.formatSize(1536, translator, Locale.GERMAN))
            .isEqualTo("terminal.paste.confirm.size.kib[1,5]");
    }

    @Test
    void theBracketedNoteFollowsThePaneAndCarriesTheCaveat() {
        assertThat(content("a\nb", EnumSet.of(PasteReason.LARGE), true, PasteSource.CLIPBOARD, false, "x").notes())
            .containsExactly("terminal.paste.confirm.bracketed");
        assertThat(content("a\nb", EnumSet.of(PasteReason.MULTI_LINE)).notes())
            .containsExactly("terminal.paste.confirm.notBracketed");
    }

    @Test
    void theNotesNameTheSourceRemovedMarkersAndBroadcastMode() {
        String text = "a" + ESC + "[201~\nb";
        PasteConfirmationContent selection = content(text,
            EnumSet.of(PasteReason.MULTI_LINE, PasteReason.CONTROL_CHARACTERS), false, PasteSource.SELECTION, true, "x");
        assertThat(selection.notes()).containsExactly(
            "terminal.paste.confirm.notBracketed",
            "terminal.paste.confirm.source.selection",
            "terminal.paste.confirm.markersRemoved",
            "terminal.paste.confirm.broadcast").inOrder();

        PasteConfirmationContent drop = content("a\nb", EnumSet.of(PasteReason.MULTI_LINE), false,
            PasteSource.DROP, false, "x");
        assertThat(drop.notes()).containsExactly(
            "terminal.paste.confirm.notBracketed", "terminal.paste.confirm.source.drop").inOrder();

        PasteConfirmationContent clipboard = content("a\nb", EnumSet.of(PasteReason.MULTI_LINE));
        assertThat(clipboard.notes()).containsExactly("terminal.paste.confirm.notBracketed");
    }

    @Test
    void aPlainPreviewHasNoLegendAndALongOneSaysItIsShortened() {
        PasteConfirmationContent plain = content("a\nb", EnumSet.of(PasteReason.MULTI_LINE));
        assertThat(plain.previewText()).isEqualTo("a\nb");
        assertThat(plain.previewLegend()).isNull();
        assertThat(plain.previewTruncated()).isNull();

        String many = "line\n".repeat(PasteInspection.DEFAULT_PREVIEW_LINES + 5);
        PasteConfirmationContent shortened = content(many, EnumSet.of(PasteReason.MULTI_LINE));
        assertThat(shortened.previewTruncated()).isEqualTo("terminal.paste.confirm.previewTruncated");
        assertThat(shortened.previewText().split("\n", -1)).hasLength(PasteInspection.DEFAULT_PREVIEW_LINES);
    }

    @Test
    void aZeroWidthCharacterGetsTheLegendThoughItDoesNotAsk() {
        PasteConfirmationContent content = content("pass\u200bword\n", EnumSet.of(PasteReason.MULTI_LINE));
        assertThat(content.previewText()).contains("<U+200B>");
        assertThat(content.previewLegend()).isEqualTo("terminal.paste.confirm.previewLegend");
    }

    @Test
    void theAccessibleTextReadsTheQuestionAndTheReasons() {
        PasteConfirmationContent content = content("a\nb", EnumSet.of(PasteReason.MULTI_LINE));
        assertThat(content.accessibleText())
            .isEqualTo("terminal.paste.confirm.header[prod-db] terminal.paste.confirm.reason.multiLine");
    }

    @Test
    void everyKeyTheDialogAsksForIsInTheEnglishBundle() throws Exception {
        String text = "x".repeat(2 * 1024 * 1024) + "\n" + ESC + "[201~" + RLO + "\u200b\n";
        for (PasteSource source : PasteSource.values()) {
            for (boolean bracketed : new boolean[] {false, true}) {
                content(text, EnumSet.allOf(PasteReason.class), bracketed, source, true, "x");
                content("one\n", EnumSet.of(PasteReason.MULTI_LINE), bracketed, source, false, "");
                content("line\n".repeat(40), EnumSet.of(PasteReason.MULTI_LINE), bracketed, source, false, "x");
            }
        }
        PasteConfirmationContent.of(new PasteConfirmationRequest("x", "a\nb", EnumSet.of(PasteReason.MULTI_LINE),
            false, PasteSource.CLIPBOARD, false, true), PasteInspection.of("a\nb"), translator, Locale.ENGLISH);
        PasteConfirmationContent.of(new PasteConfirmationRequest("x", text, EnumSet.allOf(PasteReason.class),
            false, PasteSource.CLIPBOARD, false, true), PasteInspection.of(text), translator, Locale.ENGLISH);
        PasteConfirmationContent.formatSize(10, translator, Locale.ENGLISH);
        PasteConfirmationContent.formatSize(10_000, translator, Locale.ENGLISH);

        Properties english = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/messages.properties")) {
            english.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertThat(requestedKeys).isNotEmpty();
        for (String key : requestedKeys) {
            assertWithMessage("messages.properties has no %s", key).that(english.getProperty(key)).isNotNull();
        }
    }
}
