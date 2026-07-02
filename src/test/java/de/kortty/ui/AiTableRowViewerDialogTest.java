package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiTableRowViewerDialogTest {

    @Test
    void decodeCellTextConvertsAllBrVariantsToNewlines() {
        assertThat(AiTableRowViewerDialog.decodeCellText("a<br>b<BR>c<br/>d<br />e"))
            .isEqualTo("a\nb\nc\nd\ne");
        assertThat(AiTableRowViewerDialog.decodeCellText(null)).isEmpty();
        assertThat(AiTableRowViewerDialog.decodeCellText("no breaks")).isEqualTo("no breaks");
    }

    @Test
    void clipboardTextPairsHeadersWithDecodedValues() {
        String text = AiTableRowViewerDialog.buildClipboardText(
            List.of("Server", "Antwort", "Fehler"),
            List.of("Fedora44", "1. a.txt<br>2. b.txt", "-"));
        String separator = System.lineSeparator();
        assertThat(text).isEqualTo(
            "Server:" + separator + "Fedora44"
                + separator + separator
                + "Antwort:" + separator + "1. a.txt" + separator + "2. b.txt"
                + separator + separator
                + "Fehler:" + separator + "-");
    }

    @Test
    void clipboardTextSkipsFullyEmptyColumnsAndToleratesLengthMismatch() {
        String text = AiTableRowViewerDialog.buildClipboardText(
            Arrays.asList("Server", "", "Extra"),
            Arrays.asList("srv-1", ""));
        String separator = System.lineSeparator();
        assertThat(text).isEqualTo("Server:" + separator + "srv-1" + separator + separator + "Extra:" + separator);
        assertThat(AiTableRowViewerDialog.buildClipboardText(null, null)).isEmpty();
    }
}
