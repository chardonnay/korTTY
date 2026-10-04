package de.kortty.ui.sftp;

import de.kortty.policy.LoadIntoEditorMode;
import de.kortty.policy.PolicyRestrictionException;
import de.kortty.ui.SnippetEditDialog;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SftpEditGateTest {

    private static final SnippetEditDialog.ExternalFileAction WRITE = draft -> true;

    @Test
    void allowOpensEverythingAndKeepsTheWriteBackActions() {
        SftpEditGate gate = SftpEditGate.of(LoadIntoEditorMode.ALLOW);

        assertThat(gate.remoteEditorAvailable()).isTrue();
        assertThat(gate.remoteImageAvailable()).isTrue();
        assertThat(gate.localEditorAvailable()).isTrue();
        assertThat(gate.remoteWriteBackAllowed()).isTrue();
        assertThat(gate.remoteWriteAction(WRITE)).isSameInstanceAs(WRITE);
        gate.requireRemoteWriteBack("blocked");
    }

    @Test
    void readOnlyOpensFilesButDropsTheActionsThatWriteToTheServer() {
        SftpEditGate gate = SftpEditGate.of(LoadIntoEditorMode.READ_ONLY);

        assertThat(gate.remoteEditorAvailable()).isTrue();
        assertThat(gate.remoteImageAvailable()).isTrue();
        assertThat(gate.localEditorAvailable()).isTrue();
        assertThat(gate.remoteWriteBackAllowed()).isFalse();
        assertThat(gate.remoteWriteAction(WRITE)).isNull();

        // The null actions become locked buttons; the snippet action stays.
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            "/srv/app.conf", "Overwrite", "Save as", "Save snippet", "saved", "saved", "saved",
            gate.remoteWriteAction(WRITE), gate.remoteWriteAction(WRITE), WRITE);
        assertThat(config.overwriteAction()).isNull();
        assertThat(config.saveAsAction()).isNull();
        assertThat(config.saveAsSnippetAction()).isSameInstanceAs(WRITE);
        assertThat(config.lockedReason()).isNull();

        PolicyRestrictionException e = expectThrows(PolicyRestrictionException.class,
            () -> gate.requireRemoteWriteBack("read-only"));
        assertThat(e).hasMessageThat().isEqualTo("read-only");
    }

    @Test
    void denyDisablesTheRemoteEditorAndImageViewerButNotLocalFiles() {
        SftpEditGate gate = SftpEditGate.of(LoadIntoEditorMode.DENY);

        assertThat(gate.remoteEditorAvailable()).isFalse();
        assertThat(gate.remoteImageAvailable()).isFalse();
        assertThat(gate.localEditorAvailable()).isTrue();
        assertThat(gate.remoteWriteBackAllowed()).isFalse();
        assertThat(gate.remoteWriteAction(WRITE)).isNull();
        expectThrows(PolicyRestrictionException.class, () -> gate.requireRemoteWriteBack("denied"));
    }

    @Test
    void noPolicyValueMeansAllow() {
        assertThat(SftpEditGate.of(null).mode()).isEqualTo(LoadIntoEditorMode.ALLOW);
        for (LoadIntoEditorMode mode : LoadIntoEditorMode.values()) {
            assertThat(SftpEditGate.of(mode).mode()).isEqualTo(mode);
        }
    }

    @Test
    void withoutAPolicyFileTheCurrentGateAllows() {
        // No PolicyManager is installed in unit tests: the unrestricted policy applies.
        assertThat(SftpEditGate.current().remoteWriteBackAllowed()).isTrue();
    }

    @Test
    void sftpManagerConsultsTheGateOnEveryRemoteEditPath() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(source).contains("|| !SftpEditGate.current().remoteEditorAvailable());");
        assertThat(source).contains(
            "editWithSnippetEditorItem.setDisable(!isSingleFile || !editGate.remoteEditorAvailable());");
        assertThat(source).contains(
            "openImageItem.setDisable(!isImageFile || !editGate.remoteImageAvailable());");
        assertThat(source).contains("editGate.remoteWriteAction(overwrite),");
        assertThat(source).contains("editGate.remoteWriteAction(saveAs),");
        assertThat(source).contains(
            "if (!remoteEditAllowedByPolicy(SftpEditGate.current().remoteImageAvailable())) return;");
        String overwrite = source.substring(source.indexOf("private boolean overwriteRemoteSnippetFile("));
        assertThat(overwrite.substring(0, overwrite.indexOf("uploadFileBytes")))
            .contains("requireRemoteWriteBack(");
        String saveAs = source.substring(source.indexOf("private boolean saveRemoteSnippetFileAs("));
        assertThat(saveAs.substring(0, saveAs.indexOf("callOnFxThread")))
            .contains("requireRemoteWriteBack(");
    }
}
