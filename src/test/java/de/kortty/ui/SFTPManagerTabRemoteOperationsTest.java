package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.core.sftp.transfer.TransferCancellation;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpDragOutPolicy;
import de.kortty.ui.sftp.SftpFileItem;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * The remote steps of the SFTP manager tab that decide before anything is changed or downloaded:
 * which dragged files may leave the window, and whether a rename would meet another entry. Runs
 * against a session that answers from maps, without a server.
 */
class SFTPManagerTabRemoteOperationsTest {

    private static final int REGULAR_FILE = 0100644;
    private static final int DIRECTORY = 040755;
    private static final int CHARACTER_DEVICE = 020666;

    private FakeSession session;
    private Path directory;

    @BeforeMethod
    void setUp() throws IOException {
        session = new FakeSession();
        directory = Files.createTempDirectory("kortty-sftp-drag-out-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void dragOutDownloadsSmallRegularFiles() throws Exception {
        session.attributes.put("/srv/a.txt", attributes(REGULAR_FILE, 5));
        session.attributes.put("/srv/b.txt", attributes(REGULAR_FILE, 7));

        List<File> files = SFTPManagerTab.downloadForDragOut(session,
            List.of(listed("a.txt", 5), listed("b.txt", 7)), directory, TransferCancellation.create());

        assertThat(files).containsExactly(directory.resolve("a.txt").toFile(), directory.resolve("b.txt").toFile());
        assertThat(session.downloads).containsExactly("/srv/a.txt", "/srv/b.txt");
    }

    @Test
    void dragOutChecksTheSizeALinkPointsToBeforeDownloading() throws Exception {
        // The listing shows a symbolic link with the size of the link itself.
        SftpFileItem link = listed("big.log", 12);
        assertThat(SftpDragOutPolicy.check(List.of(link))).isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);
        session.attributes.put("/srv/big.log", attributes(REGULAR_FILE, 200L * 1024 * 1024));

        expectThrows(SFTPManagerTab.DragOutTooLarge.class,
            () -> SFTPManagerTab.downloadForDragOut(session, List.of(link), directory, TransferCancellation.create()));

        assertThat(session.downloads).isEmpty();
        try (var entries = Files.list(directory)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void dragOutRefusesALinkToADeviceOrAFolder() {
        // A link to /dev/zero: a download that would never end.
        session.attributes.put("/srv/zero", attributes(CHARACTER_DEVICE, 0));
        session.attributes.put("/srv/logs", attributes(DIRECTORY, 4096));

        expectThrows(SFTPManagerTab.DragOutTooLarge.class,
            () -> SFTPManagerTab.downloadForDragOut(session, List.of(listed("zero", 9)), directory, TransferCancellation.create()));
        expectThrows(SFTPManagerTab.DragOutTooLarge.class,
            () -> SFTPManagerTab.downloadForDragOut(session, List.of(listed("logs", 4)), directory, TransferCancellation.create()));
        assertThat(session.downloads).isEmpty();
    }

    @Test
    void dragOutChecksEveryFileBeforeTheFirstDownload() {
        session.attributes.put("/srv/a.txt", attributes(REGULAR_FILE, 5));
        session.attributes.put("/srv/big.iso", attributes(REGULAR_FILE, SftpDragOutPolicy.MAX_TOTAL_BYTES));

        expectThrows(SFTPManagerTab.DragOutTooLarge.class, () -> SFTPManagerTab.downloadForDragOut(session,
            List.of(listed("a.txt", 5), listed("big.iso", 12)), directory, TransferCancellation.create()));
        assertThat(session.downloads).isEmpty();
    }

    @Test
    void renameToAnotherNameLooksTheTargetUp() throws Exception {
        session.attributes.put("/srv/taken.txt", attributes(REGULAR_FILE, 1));

        assertThat(SFTPManagerTab.remoteRenameTargetTaken(session, "a.txt", "/srv/taken.txt", "taken.txt")).isTrue();
        assertThat(SFTPManagerTab.remoteRenameTargetTaken(session, "a.txt", "/srv/free.txt", "free.txt")).isFalse();
    }

    @Test
    void renameThatOnlyChangesTheCaseLooksForTheExactSpelling() throws Exception {
        // A case-sensitive server with both names: the rename must not reach a second file.
        session.listings.put("/srv", List.of(entry("a.txt"), entry("A.txt")));
        assertThat(SFTPManagerTab.remoteRenameTargetTaken(session, "a.txt", "/srv/A.txt", "A.txt")).isTrue();

        // A case-insensitive server answers a stat of "A.txt" with the file itself: only the listing tells.
        session.attributes.put("/srv/A.txt", attributes(REGULAR_FILE, 1));
        session.listings.put("/srv", List.of(entry("a.txt"), entry("b.txt")));
        assertThat(SFTPManagerTab.remoteRenameTargetTaken(session, "a.txt", "/srv/A.txt", "A.txt")).isFalse();
    }

    private static SftpFileItem listed(String name, long size) {
        return SftpFileItem.fromDetails(name, "/srv/" + name, true, size + " B", "", "", "", "", size);
    }

    private static SftpClient.Attributes attributes(int mode, long size) {
        SftpClient.Attributes attributes = new SftpClient.Attributes();
        attributes.setPermissions(mode);
        attributes.setSize(size);
        return attributes;
    }

    private static SftpClient.DirEntry entry(String name) {
        return new SftpClient.DirEntry(name, name, attributes(REGULAR_FILE, 1));
    }

    /** Answers stat and listings from maps; a download writes a one-line file and is recorded. */
    private static final class FakeSession extends SFTPSession {
        final Map<String, SftpClient.Attributes> attributes = new HashMap<>();
        final Map<String, List<SftpClient.DirEntry>> listings = new HashMap<>();
        final List<String> downloads = new ArrayList<>();

        FakeSession() {
            super(new ServerConnection("test", "localhost", 22, "user"), "");
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public SftpClient.Attributes getAttributes(String remotePath) throws IOException {
            SftpClient.Attributes found = attributes.get(remotePath);
            if (found == null) {
                throw new NoSuchFileException(remotePath);
            }
            return found;
        }

        @Override
        public List<SftpClient.DirEntry> listFiles(String remotePath) throws IOException {
            List<SftpClient.DirEntry> listing = listings.get(remotePath);
            if (listing == null) {
                throw new NoSuchFileException(remotePath);
            }
            return listing;
        }

        @Override
        public void downloadNewFile(String remotePath, Path localPath, TransferCancellation cancel)
                throws IOException {
            downloads.add(remotePath);
            Files.writeString(localPath, remotePath + "\n", StandardCharsets.UTF_8);
        }
    }
}
