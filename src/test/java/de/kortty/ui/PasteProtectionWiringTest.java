package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import de.kortty.paste.PasteDecision;
import de.kortty.paste.PasteProtectionSettings;
import de.kortty.paste.PasteReason;
import de.kortty.paste.PasteRules;
import de.kortty.paste.PasteWarningMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Paste protection is wired where the design puts it: every tab's guard reads the Settings on each
 * paste and asks through {@link PasteConfirmationDialog}, the dialog never blocks and never lets
 * type-ahead confirm, and the Settings page saves and reports both values. Building the dialogs needs
 * a JavaFX toolkit, so the wiring is checked in the source (CRLF-safe); the rules themselves run.
 */
class PasteProtectionWiringTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    private static final Path DIALOG = Path.of("src/main/java/de/kortty/ui/PasteConfirmationDialog.java");

    private static final Path SETTINGS_DIALOG = Path.of("src/main/java/de/kortty/ui/SettingsDialog.java");

    @Test
    void theRulesFollowTheGlobalSettings() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteWarningMode(PasteWarningMode.ALWAYS);
        settings.setPasteLargeWarningKiB(64);
        PasteRules rules = TerminalView.pasteRules(() -> settings);

        assertThat(rules).isInstanceOf(PasteDecision.class);
        assertThat(((PasteDecision) rules).settings())
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 64));
        assertThat(rules.reasons("echo one\necho two", true)).containsExactly(PasteReason.MULTI_LINE);
    }

    @Test
    void withoutReadableSettingsTheDefaultsApplyNeverOff() {
        PasteRules missing = TerminalView.pasteRules(() -> null);
        PasteRules failing = TerminalView.pasteRules(() -> {
            throw new IllegalStateException("no application");
        });

        for (PasteRules rules : new PasteRules[] {missing, failing}) {
            assertThat(((PasteDecision) rules).settings()).isEqualTo(PasteProtectionSettings.DEFAULTS);
            assertThat(rules.reasons("echo one\necho two", false)).containsExactly(PasteReason.MULTI_LINE);
            assertThat(rules.reasons("echo one\necho two", true)).isEmpty();
        }
    }

    @Test
    void everyTabsGuardAsksThroughTheConfirmationDialog() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(view).contains("private final PasteGuard pasteGuard = new PasteGuard("
            + "() -> pasteRules(TerminalView::readGlobalSettings),\n"
            + "        new PasteConfirmationDialog(this::pasteConfirmationOwner), pastePacer,\n"
            + "        () -> pasteLineDelayMs(TerminalView::readGlobalSettings));");
        assertWithMessage("the placeholder that declined every confirmation is gone")
            .that(view).doesNotContain("answer.accept(false)");
        assertThat(body(view, "private static GlobalSettings readGlobalSettings() {"))
            .contains("KorTTYApplication.getInstance().getGlobalSettingsManager()");
    }

    @Test
    void theDialogNeverBlocksAndCancelIsTheFocusedDefault() throws IOException {
        String dialog = source(DIALOG);
        String show = body(dialog, "public void confirm(PasteConfirmationRequest request, Consumer<Boolean> answer) {");
        String confirm = body(dialog, "Alert build(PasteConfirmationRequest request, Consumer<Boolean> answer) {");
        assertThat(show).contains("build(request, answer).show();");
        assertThat(show).doesNotContain("showAndWait");
        assertThat(confirm).doesNotContain("showAndWait");

        assertThat(confirm).contains("pasteButton.setDefaultButton(false);");
        assertThat(confirm).contains("cancelButton.setDefaultButton(true);");
        assertThat(confirm).contains("alert.setOnShown(event -> cancelButton.requestFocus());");
        assertThat(confirm).contains("alert.setOnHidden(event -> answer.accept(paste.equals(alert.getResult())));");
        assertThat(confirm).contains("alert.initModality(Modality.WINDOW_MODAL);");
    }

    @Test
    void thePreviewCopiesOnlyThroughTheClipboardPolicy() throws IOException {
        String preview = body(source(DIALOG),
            "private static TextArea previewArea(PasteConfirmationContent content, Button cancelButton) {");

        assertThat(preview).contains("preview.setEditable(false);");
        assertThat(preview).contains("copy.setOnAction(event -> KorttyClipboard.copySelection(preview));");
        assertThat(preview).contains("preview.setContextMenu(new ContextMenu(copy));");
        assertThat(preview).contains("cancelButton.requestFocus();");
    }

    @Test
    void theSettingsPageSavesAndReportsBothValues() throws IOException {
        String settings = source(SETTINGS_DIALOG);

        assertThat(settings).contains("globalSettings.setPasteWarningMode(pasteWarningModeCombo.getValue());");
        assertThat(settings).contains("globalSettings.setPasteLargeWarningKiB(");
        assertThat(settings).contains(
            "new TrackedSetting(\"terminal\", \"paste_warning_mode\", () -> gs.getPasteWarningMode().id(), true)");
        assertThat(settings).contains(
            "new TrackedSetting(\"terminal\", \"paste_large_warning_kib\", gs::getPasteLargeWarningKiB, true)");
        assertThat(body(settings, "private static String pasteWarningModeKey(PasteWarningMode mode) {"))
            .contains("case UNLESS_BRACKETED -> \"settings.terminal.paste.mode.unlessBracketed\";");
    }

    private static String source(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the first line that closes a member at four spaces. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing: %s", signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertWithMessage("no end for: %s", signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
