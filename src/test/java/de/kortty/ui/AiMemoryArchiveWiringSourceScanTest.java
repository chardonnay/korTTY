package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The archive view, the secret warning and the rescan after a backup restore are wired where they are needed. The wiring
 * is checked in the source, because the windows are not unit-tested here.
 */
class AiMemoryArchiveWiringSourceScanTest {

    private static final Path PANE = Path.of("src/main/java/de/kortty/ui/AiMemoryPane.java");
    private static final Path VIEW = Path.of("src/main/java/de/kortty/ui/AiMemoryArchiveViewDialog.java");
    private static final Path ROWS = Path.of("src/main/java/de/kortty/ui/AiMemoryArchiveRows.java");
    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    /** Assembled so that this file does not contain the needle itself. */
    private static final String GRANT = "MemoryConsent." + "grant(";

    private static String read(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }

    @Test
    void theMemoryTabOpensTheArchiveFromItsActionRow() throws IOException {
        String pane = read(PANE);

        assertThat(pane).contains("new HBox(10, cleanupButton, archiveButton, historyButton, clearButton)");
        assertThat(pane).contains("archiveButton.setOnAction(event -> openArchive());");
        assertThat(pane).contains("new AiMemoryArchiveViewDialog(");
        assertThat(pane).contains("archiveButton.setDisable(!visible);");
    }

    @Test
    void restoreAndDeleteAreOffWhileTheMemoryCannotBeChanged() throws IOException {
        String view = read(VIEW);

        assertThat(view).contains("restoreButton.setDisable(!writable || !any);");
        assertThat(view).contains("deleteButton.setDisable(!writable || !any);");
        assertThat(view).contains("restoreButton.setTooltip(writable ? null : new Tooltip(readOnlyReason));");
        assertThat(view).contains("if (chosen.isEmpty() || !writable || !confirmRestore(chosen))");
    }

    @Test
    void restoreGoesThroughTheStoreAfterTheConfirmation() throws IOException {
        String view = read(VIEW);

        assertThat(view).contains("cleanup.restoreFromArchive(store, scope, keys, budgetTokens)");
        assertThat(view).contains("confirmRestore(chosen)");
        assertThat(view).doesNotContain(GRANT);
        assertThat(read(ROWS)).doesNotContain(GRANT);
    }

    @Test
    void theWarningReadsTheRegistryAndNeverTheValue() throws IOException {
        String pane = read(PANE);

        assertThat(pane).contains("AiSecretScanRegistry.shared().memoryFindings(currentScope().key())");
        assertThat(pane).contains("settings.ai.memory.secret.banner");
        assertThat(pane).doesNotContain("maskedExcerpt");
    }

    @Test
    void theBackupRestoreRescansAfterTheFilesAreReloaded() throws IOException {
        String window = read(MAIN_WINDOW);
        String call = "rescanSecretsAfterBackupImport();";

        assertThat(window.split(java.util.regex.Pattern.quote(call), -1)).hasLength(2);
        int reload = window.indexOf("            reloadStoresAfterBackupImport();\n");
        int rescan = window.indexOf("            " + call + "\n");
        assertThat(reload).isGreaterThan(-1);
        assertThat(rescan).isGreaterThan(reload);
    }

    @Test
    void theNewUiUsesNoThemeTokensInItsStyles() throws IOException {
        for (Path file : new Path[] {VIEW, ROWS, PANE}) {
            String text = read(file);
            assertThat(text).doesNotContain("-color-");
            assertThat(text).doesNotContain("-fx-base");
            assertThat(text).doesNotContain("-fx-accent");
            assertThat(text).doesNotContain("-fx-text-base-color");
            assertThat(text).doesNotContain("-fx-control-inner-background");
        }
    }

    @Test
    void theArchiveViewIsOwnedLikeItsNeighbours() throws IOException {
        String view = read(VIEW);

        assertThat(view).contains("DialogThemeHelper.ownedBy(confirm, ownerWindow());");
        assertThat(view).contains("if (owner != null) {\n            initOwner(owner);");
        assertThat(view).contains("Platform.runLater(");
    }
}
