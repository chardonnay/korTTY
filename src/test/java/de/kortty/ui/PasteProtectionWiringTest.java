package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.ConnectionSource;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
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
    void aConnectionWithItsOwnWarningModeDecidesForItsPanes() {
        GlobalSettings settings = new GlobalSettings();
        ServerConnection production = new ServerConnection("prod", "db.example.com", 22, "root");
        production.setPasteWarningMode(PasteWarningMode.ALWAYS);

        PasteRules rules = TerminalView.pasteRules(() -> settings, production);

        assertThat(((PasteDecision) rules).settings()).isEqualTo(
            new PasteProtectionSettings(PasteWarningMode.ALWAYS, PasteProtectionSettings.DEFAULT_LARGE_WARNING_KIB));
        assertThat(rules.setByConnection()).isTrue();
        assertThat(rules.reasons("echo one\necho two", true)).containsExactly(PasteReason.MULTI_LINE);
        assertWithMessage("a connection without a mode of its own follows Settings")
            .that(TerminalView.pasteRules(() -> settings, new ServerConnection()).setByConnection()).isFalse();
        assertThat(TerminalView.pasteRules(() -> settings).setByConnection()).isFalse();
    }

    @Test
    void theOrganizationsFloorAppliesAndThenTheConnectionNoLongerChoseTheWarning() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteWarningMode(PasteWarningMode.OFF);
        ServerConnection lab = new ServerConnection("lab", "lab.example.com", 22, "root");
        lab.setPasteWarningMode(PasteWarningMode.OFF);

        PasteRules raised = TerminalView.pasteRules(() -> settings, lab, PasteWarningMode.ALWAYS);
        assertThat(((PasteDecision) raised).settings().mode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertWithMessage("the policy raised the mode, so the dialog must not send the user to the connection")
            .that(raised.setByConnection()).isFalse();
        assertThat(raised.reasons("echo one\necho two", true)).containsExactly(PasteReason.MULTI_LINE);

        ServerConnection production = new ServerConnection("prod", "db.example.com", 22, "root");
        production.setPasteWarningMode(PasteWarningMode.ALWAYS);
        assertThat(TerminalView.pasteRules(() -> settings, production, PasteWarningMode.UNLESS_BRACKETED)
            .setByConnection()).isTrue();

        PasteRules unreadable = TerminalView.pasteRules(() -> {
            throw new IllegalStateException("no application");
        }, null, PasteWarningMode.ALWAYS);
        assertThat(((PasteDecision) unreadable).settings().mode()).isEqualTo(PasteWarningMode.ALWAYS);
    }

    @Test
    void theGuardReadsThePolicyFloorOnEveryPaste() throws IOException {
        String view = Files.readString(TERMINAL_VIEW, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(view).contains("floor = de.kortty.policy.PolicyManager.effective().pasteWarningFloor();");
        assertThat(view).contains("PasteProtectionSettings.resolve(global, connection, floor)");
    }

    @Test
    void aTeamworkConnectionCannotSwitchTheWarningOff() {
        GlobalSettings settings = new GlobalSettings();
        ServerConnection shared = new ServerConnection("shared", "db.example.com", 22, "root");
        shared.setConnectionSource(ConnectionSource.TEAMWORK);
        shared.setPasteWarningMode(PasteWarningMode.OFF);

        PasteRules rules = TerminalView.pasteRules(() -> settings, shared);

        assertThat(((PasteDecision) rules).settings()).isEqualTo(PasteProtectionSettings.DEFAULTS);
        assertThat(rules.setByConnection()).isFalse();
    }

    @Test
    void withoutReadableSettingsTheConnectionStillDecides() {
        ServerConnection production = new ServerConnection("prod", "db.example.com", 22, "root");
        production.setPasteWarningMode(PasteWarningMode.ALWAYS);
        production.setPasteLineDelayMs(40);

        PasteRules rules = TerminalView.pasteRules(() -> {
            throw new IllegalStateException("no application");
        }, production);

        assertThat(((PasteDecision) rules).settings().mode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(TerminalView.pasteLineDelayMs(() -> null, production)).isEqualTo(40);
        assertThat(TerminalView.pasteLineDelayMs(() -> {
            throw new IllegalStateException("no application");
        }, new ServerConnection())).isEqualTo(0);
    }

    @Test
    void theLineDelayIsThePanesConnectionsOwnElseTheGlobalOne() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteLineDelayMs(200);
        ServerConnection console = new ServerConnection("console", "con.example.com", 22, "admin");
        console.setPasteLineDelayMs(0);

        assertThat(TerminalView.pasteLineDelayMs(() -> settings)).isEqualTo(200);
        assertThat(TerminalView.pasteLineDelayMs(() -> settings, null)).isEqualTo(200);
        assertThat(TerminalView.pasteLineDelayMs(() -> settings, new ServerConnection())).isEqualTo(200);
        assertThat(TerminalView.pasteLineDelayMs(() -> settings, console)).isEqualTo(0);
    }

    @Test
    void theSavedConnectionWinsSoAChangeAppliesToOpenPanes() {
        ServerConnection atConnect = new ServerConnection("prod", "db.example.com", 22, "root");
        ServerConnection saved = ServerConnection.copyForAuth(atConnect);
        saved.setPasteWarningMode(PasteWarningMode.ALWAYS);

        assertThat(TerminalView.savedConnectionOr(atConnect, id -> id.equals(atConnect.getId()) ? saved : null))
            .isSameInstanceAs(saved);
        assertWithMessage("a connection that is not saved (teamwork, Quick Connect) keeps its own values")
            .that(TerminalView.savedConnectionOr(atConnect, id -> null)).isSameInstanceAs(atConnect);
        assertThat(TerminalView.savedConnectionOr(atConnect, null)).isSameInstanceAs(atConnect);
        assertThat(TerminalView.savedConnectionOr(null, id -> saved)).isNull();
    }

    @Test
    void thePanesOwnConnectionIsLookedUpForEveryPaste() throws IOException {
        String view = source(TERMINAL_VIEW);
        String connectionOf = body(view,
            "private @Nullable ServerConnection pasteConnectionOf(@Nullable PasteTarget target) {");

        assertWithMessage("a split to another server follows its own connection")
            .that(connectionOf).contains("connectionOf(unwrapTerminalEffectConnector(pane.getTtyConnector()))");
        assertThat(connectionOf).contains("ServerConnection paneConnection = own != null ? own : connection;");
        assertThat(connectionOf).contains("configManager::getConnectionById");
        assertWithMessage("a failing lookup must never break a paste")
            .that(connectionOf).contains("catch (RuntimeException e)");
    }

    @Test
    void everyTabsGuardAsksThroughTheConfirmationDialog() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(view).contains("private final PasteGuard pasteGuard = new PasteGuard(\n"
            + "        target -> pasteRules(TerminalView::readGlobalSettings, pasteConnectionOf(target)),\n"
            + "        new PasteConfirmationDialog(this::pasteConfirmationOwner), pastePacer,\n"
            + "        target -> pasteLineDelayMs(TerminalView::readGlobalSettings, pasteConnectionOf(target)));");
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

    @Test
    void droppedTextIsPastedIntoThePaneUnderThePointerThroughTheGuard() throws IOException {
        String view = source(TERMINAL_VIEW);

        String setup = body(view, "private void setupDragDrop() {");
        assertThat(setup).contains("splitPane.addEventFilter(DragEvent.DRAG_OVER, event -> {");
        assertThat(setup).contains("if (handleTerminalDragDropped(event)) {");

        String action = body(view, "private TerminalTextDropDecision.Action terminalDropAction(DragEvent event) {");
        assertThat(action).contains("db.getTransferModes().contains(TransferMode.COPY)");
        assertThat(action).contains("event.getGestureSource() != null");
        assertThat(action).contains("KorttyClipboard.isInternalMode()");

        String over = body(view, "private boolean handleTextDragOver(DragEvent event) {");
        assertWithMessage("a text drag is only ever a copy, so the source never deletes what it dragged")
            .that(over).contains("event.acceptTransferModes(TransferMode.COPY);");
        assertThat(over).doesNotContain("TransferMode.MOVE");
        assertThat(over).contains("KorttyTermWidget pane = textDropPane(event);");

        String dropped = body(view, "private boolean handleTextDragDropped(DragEvent event) {");
        assertThat(dropped).contains("KorttyTermWidget pane = textDropPane(event);");
        assertThat(dropped).contains("String text = event.getDragboard().getString();");
        assertWithMessage("the confirmation opens after the platform's drag loop, not inside it")
            .that(dropped).contains("Platform.runLater(() -> pasteIntoPane(pane, text, PasteSource.DROP));");

        String paste = body(view, "public void pasteIntoPane(KorttyTermWidget pane, String text, PasteSource source) {");
        assertThat(paste).contains("if (!terminalPanes().contains(pane)) {");
        assertThat(paste).contains("splitPane.focusWidget(pane);");
        assertWithMessage("a pane-precise paste goes through the guard with its own source")
            .that(paste).contains("pasteGuard.paste(pane.pasteTarget(), text, source);");

        assertWithMessage("the pane under the pointer, not the focused pane")
            .that(body(view, "private @Nullable KorttyTermWidget textDropPane(DragEvent event) {"))
            .contains("isUnderPointer(pane.getPane(), event.getSceneX(), event.getSceneY())");
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
