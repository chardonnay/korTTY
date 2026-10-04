package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where the startup restore hooks into korTTY. Starting korTTY, showing the bar and restoring need a
 * shown stage, so like {@link SessionSnapshotWiringTest} this test pins the sources, line-ending
 * agnostic; the decision is tested in {@code SessionRestoreDecisionTest}, the waiting in
 * {@link SessionRestoreCoordinatorTest} and the snapshot marks in {@link SessionRestoreSnapshotTest}.
 */
class SessionRestoreStartupWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path APPLICATION = Path.of("src/main/java/de/kortty/KorTTYApplication.java");
    private static final Path SETTINGS_DIALOG = Path.of("src/main/java/de/kortty/ui/SettingsDialog.java");

    private static final String CONSENT_LINE =
        "Platform.runLater(() -> de.kortty.ui.TelemetryConsentDialog.maybeShow(this, primaryStage));";

    @Test
    void theStartupRestoreIsPostedAfterTheUntouchedConsentPrompt() throws IOException {
        String application = read(APPLICATION);

        int consent = application.indexOf("            if (!testMode) {\n                " + CONSENT_LINE + "\n            }");
        int restore = application.indexOf("mainWindow.startSessionRestore(sessionRestoreMode);");
        assertWithMessage("the telemetry consent prompt is left as it is").that(consent).isAtLeast(0);
        assertWithMessage("posted after the consent prompt, so it runs after it").that(restore).isGreaterThan(consent);
        assertThat(application.indexOf(CONSENT_LINE)).isEqualTo(application.lastIndexOf(CONSENT_LINE));

        String posted = application.substring(consent, restore);
        assertThat(posted).contains("globalSettingsManager.getSettings().getSessionRestoreMode();");
        assertThat(posted).contains("Platform.runLater(() -> {");
        assertWithMessage("the restore never depends on the telemetry choice or test mode")
            .that(application.substring(consent + 30, restore)).doesNotContain("testMode");
        assertWithMessage("the snapshot is started before, so the restore finds what the last run left")
            .that(application.indexOf("sessionAutosave = MainWindow.startSessionAutosave(")).isLessThan(consent);
    }

    @Test
    void theStartupRestoreDecidesFromWhatTheStoreFoundAndWaitsForModalDialogs() throws IOException {
        String start = methodBody(read(MAIN_WINDOW), "public void startSessionRestore(de.kortty.model.SessionRestoreMode mode) {");

        assertThat(start).contains("SessionRestoreDecision.Facts facts = SessionRestoreDecision.Facts.of(startup);");
        assertThat(start).contains("SessionRestoreDecision.decide(mode, facts);");
        assertThat(start).contains("return isModalDialogShowing();");
        assertThat(start).contains("sessionAutosave.carryForward(previous);");
        assertThat(start).contains("showSessionRestoreOffer(text);");
        assertThat(start).contains("restorePreviousSession(RestoreTrigger.AUTO);");
        assertThat(start).contains("}, decision, facts, I18n::get).start();");

        String modal = methodBody(read(MAIN_WINDOW), "private static boolean isModalDialogShowing() {");
        assertThat(modal).contains("stage.getModality() != javafx.stage.Modality.NONE");
        assertThat(modal).contains("window.isShowing()");
    }

    @Test
    void theOfferIsANonModalBarAboveTheStatusLineAndDismissDropsTheSession() throws IOException {
        String offer = methodBody(read(MAIN_WINDOW), "private void showSessionRestoreOffer(String text) {");

        assertThat(offer).contains("statusBar.getChildren().add(0, sessionRestoreOfferBar);");
        assertThat(offer).contains("sessionAutosave.dropCarriedForward();");
        assertThat(offer).doesNotContain("showAndWait");
        assertThat(offer.indexOf("sessionRestoreOfferBar.hideOffer();")).isLessThan(offer.indexOf("restorePreviousSession(RestoreTrigger.OFFER);"));
    }

    @Test
    void everyRestoreOfThePreviousSessionLeavesItsMarkFirstAndBecomesStableAfterAMinute() throws IOException {
        String restore = methodBody(read(MAIN_WINDOW), "private void restorePreviousSession(RestoreTrigger trigger) {");

        int once = restore.indexOf("sessionAutosave.markPreviousRestored();");
        int mark = restore.indexOf("sessionAutosave.beginSessionRestore();");
        int opens = restore.indexOf("restoreProject(project, target);");
        assertThat(once).isAtLeast(0);
        assertWithMessage("the mark is on disk before the first tab opens").that(mark).isGreaterThan(once);
        assertThat(mark).isLessThan(opens);
        assertThat(restore.indexOf("hideSessionRestoreOffers();")).isLessThan(opens);
        assertThat(restore.indexOf("markSessionStableLater();")).isGreaterThan(opens);

        String stable = methodBody(read(MAIN_WINDOW), "private static void markSessionStableLater() {");
        assertThat(stable).contains("SessionAutosaveCoordinator.STABLE_AFTER_MILLIS");
        assertThat(stable).contains("sessionAutosave.markStable();");
    }

    @Test
    void theWindowTabLoadsAndSavesTheModeInItsOwnSection() throws IOException {
        String dialog = read(SETTINGS_DIALOG);

        assertThat(dialog).contains("sessionRestoreModeCombo.getItems().setAll(SessionRestoreMode.values());");
        assertThat(dialog).contains("? globalSettings.getSessionRestoreMode() : SessionRestoreMode.DEFAULT);");
        assertThat(dialog).contains("globalSettings.setSessionRestoreMode(sessionRestoreModeCombo.getValue());");
        assertThat(dialog).contains("new Label(I18n.get(\"settings.window.restore.header\"))");
        assertWithMessage("after the Tabs section, before the fixed geometry")
            .that(dialog.indexOf("settings.window.restore.header\""))
            .isGreaterThan(dialog.indexOf("settings.window.tabSwitchMostRecentFirst.info\""));
        assertThat(dialog.indexOf("settings.window.restore.header\""))
            .isLessThan(dialog.indexOf("settings.window.fixedGeometry.header\""));
        String keys = methodBody(dialog, "private static String sessionRestoreModeKey(SessionRestoreMode mode) {");
        assertThat(keys).contains("case ASK -> \"settings.window.restore.mode.ask\";");
        assertThat(keys).contains("case AUTO -> \"settings.window.restore.mode.auto\";");
        assertThat(keys).contains("case OFF -> \"settings.window.restore.mode.off\";");
    }

    private static String read(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the brace closing its body. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
