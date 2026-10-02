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
    void transfersStreamAndMergeFolders() throws IOException {
        String session = read("src/main/java/de/kortty/core/SFTPSession.java");
        assertThat(session).doesNotContain("readAllBytes");
        assertThat(session).contains("in.transferTo(out)");

        String tab = read("src/main/java/de/kortty/ui/SFTPManagerTab.java");
        // Uploading a folder again merges into the existing remote folder.
        assertThat(tab).contains("session.createDirectoryIfMissing(remotePath)");
        assertThat(tab).doesNotContain("sftpSession.createDirectory(");
        // The remote listing runs on the listing executor, never on the FX thread.
        assertThat(tab).contains(".supplyAsync(() -> listRemote(session, requestedPath, basePath), remoteListExecutor)");
    }

    /** With LF line endings: Windows CI checks sources out with CRLF. */
    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
