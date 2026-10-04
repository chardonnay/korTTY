package de.kortty.ui;

import de.kortty.ui.sftp.SftpFileItem;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The pure parts of the SFTP manager tab: which folder a typed path lists, which permission
 * input may reach {@code chmod}, and source pins for the transfer fixes.
 */
class SFTPManagerTabRemotePathTest {

    private static final String HOME = "/home/demo";

    @Test
    void tildeResolvesAgainstTheLoginDirectory() {
        // SFTP itself does not expand '~'.
        assertThat(SFTPManagerTab.resolveRemoteListingPath("~", "~", HOME)).isEqualTo(HOME);
        assertThat(SFTPManagerTab.resolveRemoteListingPath("", "/srv", HOME)).isEqualTo(HOME);
        assertThat(SFTPManagerTab.resolveRemoteListingPath("~/logs", "/srv", HOME)).isEqualTo("/home/demo/logs");
    }

    @Test
    void absolutePathsStayAndLoseTrailingSlashes() {
        assertThat(SFTPManagerTab.resolveRemoteListingPath("/var/log/", HOME, HOME)).isEqualTo("/var/log");
        assertThat(SFTPManagerTab.resolveRemoteListingPath(" /etc ", HOME, HOME)).isEqualTo("/etc");
        assertThat(SFTPManagerTab.resolveRemoteListingPath("/", HOME, HOME)).isEqualTo("/");
    }

    @Test
    void relativePathsAreTakenFromTheFolderShown() {
        assertThat(SFTPManagerTab.resolveRemoteListingPath("sub", "/srv/app", HOME)).isEqualTo("/srv/app/sub");
        // Before the first listing there is no shown folder yet: the login directory is the base.
        assertThat(SFTPManagerTab.resolveRemoteListingPath("sub", "~", HOME)).isEqualTo("/home/demo/sub");
    }

    @Test
    void dotSegmentsAreResolvedSoUpLeadsToTheRealParent() {
        // Before: "/srv/app/.." was kept as the folder, so Up and the ".." row went back to /srv/app.
        assertThat(SFTPManagerTab.resolveRemoteListingPath("..", "/srv/app", HOME)).isEqualTo("/srv");
        assertThat(SFTPManagerTab.resolveRemoteListingPath("../logs", "/srv/app", HOME)).isEqualTo("/srv/logs");
        assertThat(SFTPManagerTab.resolveRemoteListingPath("./sub/", "/srv/app", HOME)).isEqualTo("/srv/app/sub");
        assertThat(SFTPManagerTab.resolveRemoteListingPath("/var//log/./../tmp", HOME, HOME)).isEqualTo("/var/tmp");
        assertThat(SFTPManagerTab.resolveRemoteListingPath("~/..", "/srv", HOME)).isEqualTo("/home");
        // ".." never climbs above the root.
        assertThat(SFTPManagerTab.resolveRemoteListingPath("/../..", HOME, HOME)).isEqualTo("/");
    }

    @Test
    void localCopyOntoItselfOrIntoItsOwnFolderIsDetected() {
        Path project = Path.of("/data/project");
        assertThat(SFTPManagerTab.isLocalCopyIntoItself(project, Path.of("/data/project"))).isTrue();
        assertThat(SFTPManagerTab.isLocalCopyIntoItself(project, Path.of("/data/project/sub/project"))).isTrue();
        assertThat(SFTPManagerTab.isLocalCopyIntoItself(project, Path.of("/data/other/../project"))).isTrue();
        // Path.startsWith compares whole names: a sibling with a longer name is fine.
        assertThat(SFTPManagerTab.isLocalCopyIntoItself(project, Path.of("/data/project-copy"))).isFalse();
        assertThat(SFTPManagerTab.isLocalCopyIntoItself(project, Path.of("/backup/project"))).isFalse();
    }

    @Test
    void onlyThreeOctalDigitsReachChmod() {
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("755", "644")).isTrue();
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("", "644")).isTrue();
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("644", "644")).isTrue();

        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("999", "644")).isFalse();
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("75", "644")).isFalse();
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("u+x", "644")).isFalse();
        // The value is part of a remote shell command line.
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("755; rm -rf ~", "644")).isFalse();
        assertThat(SFTPManagerTab.isAcceptedPermissionsInput("755 $(id)", "644")).isFalse();
    }

    @Test
    void searchMatchesGlobsAndKeepsTheParentEntry() {
        SftpFileItem parent = SftpFileItem.fromDetails("..", "/srv", false, "—", "", "", "", "", -1);
        SftpFileItem log = SftpFileItem.fromDetails("App.LOG", "/srv/app/App.LOG", true, "1 B", "", "", "", "", 1);
        SftpFileItem script = SftpFileItem.fromDetails("deploy.sh", "/srv/app/deploy.sh", true, "1 B", "", "", "", "", 1);

        var logs = SFTPManagerTab.searchFilter("*.log");
        assertThat(logs.test(log)).isTrue();
        assertThat(logs.test(script)).isFalse();
        // '..' stays, so a filtered folder can still be left.
        assertThat(logs.test(parent)).isTrue();

        assertThat(SFTPManagerTab.searchFilter("*.{py,sh}").test(script)).isTrue();
        assertThat(SFTPManagerTab.searchFilter("ploy").test(script)).isTrue();
        assertThat(SFTPManagerTab.searchFilter("").test(log)).isTrue();
    }

    @Test
    void downloadedNamesStayInsideTheTargetFolder() throws IOException {
        Path folder = Path.of("downloads").toAbsolutePath();
        assertThat(SFTPManagerTab.localChild(folder, "report.txt")).isEqualTo(folder.resolve("report.txt"));
        assertThat(SFTPManagerTab.localChild(folder, ".hidden")).isEqualTo(folder.resolve(".hidden"));

        // The names come from the server: none may point outside the folder.
        for (String name : new String[] {"..", ".", "", "../escaped", "sub/file", "/etc/passwd"}) {
            org.testng.Assert.expectThrows(IOException.class, () -> SFTPManagerTab.localChild(folder, name));
        }
        org.testng.Assert.expectThrows(IOException.class, () -> SFTPManagerTab.localChild(folder, null));
    }

    @Test
    void dragAndDropKeepsItsPromises() throws IOException {
        String tab = read("src/main/java/de/kortty/ui/SFTPManagerTab.java");
        // The tab's drag format is looked up before it is created: a second DataFormat would throw.
        assertThat(tab).contains("DataFormat.lookupMimeType(mimeType)");
        // A drag out of the window waits for the download at most the policy's time.
        assertThat(tab).contains("download.get(SftpDragOutPolicy.MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS)");
        // A drag-out download still running stops, and the temporary copies are removed, when the tab closes.
        assertThat(tab).contains("remoteListExecutor.shutdownNow();\n        cancelDragOut();\n        deleteDragOutDirectories();");
        // Running out of time stops the download mid-file instead of letting it fill the folder.
        assertThat(tab).contains("cancel.cancel();\n            download.cancel(true);");
        // Row drags end in the row, never in MainWindow's tab-drag DRAG_DONE handler.
        assertThat(tab).contains("row.setOnDragDone(DragEvent::consume);");
    }

    @Test
    void transfersStreamThroughTheQueue() throws IOException {
        String session = read("src/main/java/de/kortty/core/SFTPSession.java");
        assertThat(session).doesNotContain("readAllBytes");
        // Uploads and downloads stream through the pipelined copier, never a whole-file array.
        assertThat(session).contains("SftpStreamCopier.upload(sftpClient, remotePath, localPath, 0");
        assertThat(session).contains("SftpStreamCopier.download(sftpClient, remotePath, out, 0");

        String tab = read("src/main/java/de/kortty/ui/SFTPManagerTab.java");
        // Uploads and downloads go through the transfer queue (which merges a folder into an
        // existing one), never through raw threads of the tab.
        assertThat(tab).contains("transferQueueHost.enqueueUpload(toUpload, targetDir)");
        assertThat(tab).contains("transferQueueHost.enqueueDownload(entries, targetDir)");
        assertThat(tab).doesNotContain("\"SFTP-Upload\"");
        assertThat(tab).doesNotContain("\"SFTP-Download\"");
        // The queue follows the session and closes with the tab.
        assertThat(tab).contains("transferQueueHost.onSessionReady(session);");
        assertThat(tab).contains("transferQueueHost.onSessionLost();");
        assertThat(tab).contains("transferQueueHost.close();");
        assertThat(tab).doesNotContain("sftpSession.createDirectory(");
        // The remote listing runs on the listing executor, never on the FX thread.
        assertThat(tab).contains(".supplyAsync(() -> listRemote(session, requestedPath, basePath), remoteListExecutor)");
    }

    /** With LF line endings: Windows CI checks sources out with CRLF. */
    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
