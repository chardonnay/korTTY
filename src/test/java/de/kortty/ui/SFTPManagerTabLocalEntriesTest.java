package de.kortty.ui;

import de.kortty.ui.sftp.SftpFileItem;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The SFTP manager's row filter (hidden entries and the search field) and "Set Owner/Permissions"
 * on the local side, which used to ignore the owner, the group and "Apply recursively".
 */
class SFTPManagerTabLocalEntriesTest {

    private Path root;

    @BeforeMethod
    void createTree() throws IOException {
        root = Files.createTempDirectory("sftp-local-perms");
    }

    @AfterMethod(alwaysRun = true)
    void deleteTree() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        if (posix()) {
            // A test may leave folders without x; open them top-down before deleting.
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.filter(p -> !Files.isSymbolicLink(p)).toList()) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                }
            } catch (IOException ignored) {
                // best effort
            }
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void hiddenEntriesLeaveTheListButTheParentStays() {
        SftpFileItem parent = SftpFileItem.fromDetails("..", "/", false, "—", "", "", "", "", -1);
        SftpFileItem dotDir = SftpFileItem.fromDetails(".config", "/h/.config", false, "—", "", "", "", "", -1);
        SftpFileItem dotLog = SftpFileItem.fromDetails(".app.log", "/h/.app.log", true, "1 B", "", "", "", "", 1);
        SftpFileItem log = SftpFileItem.fromDetails("app.log", "/h/app.log", true, "1 B", "", "", "", "", 1);
        SftpFileItem windowsHidden =
            SftpFileItem.fromDetails("Thumbs.db", "C:/h/Thumbs.db", true, "1 B", "", "", "", "", 1, true);
        List<SftpFileItem> items = List.of(parent, dotDir, dotLog, log, windowsHidden);

        assertThat(items.stream().filter(SFTPManagerTab.rowFilter("", true)).toList()).isEqualTo(items);
        assertThat(items.stream().filter(SFTPManagerTab.rowFilter("", false)).toList())
            .containsExactly(parent, log).inOrder();
        // The search applies on top of either setting.
        assertThat(items.stream().filter(SFTPManagerTab.rowFilter("*.log", true)).toList())
            .containsExactly(parent, dotLog, log).inOrder();
        assertThat(items.stream().filter(SFTPManagerTab.rowFilter("*.log", false)).toList())
            .containsExactly(parent, log).inOrder();
    }

    @Test
    void recursiveModeReachesEverythingInsideButNotThroughLinks() throws IOException {
        requirePosix();
        Path sub = Files.createDirectories(root.resolve("dir/sub"));
        Path file = Files.writeString(root.resolve("dir/a.txt"), "a");
        Path nested = Files.writeString(sub.resolve("b.txt"), "b");
        Path outside = Files.writeString(root.resolve("outside.txt"), "o");
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-r--r--"));
        Files.createSymbolicLink(root.resolve("dir/link"), outside);

        SFTPManagerTab.applyLocalOwnerAndMode(root.resolve("dir"), "", "750", true);

        for (Path changed : List.of(root.resolve("dir"), sub, file, nested)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(changed))).isEqualTo("rwxr-x---");
        }
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(outside))).isEqualTo("rw-r--r--");
    }

    @Test
    void withoutRecursiveOnlyTheSelectedEntryChanges() throws IOException {
        requirePosix();
        Path dir = Files.createDirectories(root.resolve("dir"));
        Path inner = Files.writeString(dir.resolve("inner.txt"), "i");
        Files.setPosixFilePermissions(inner, PosixFilePermissions.fromString("rw-r--r--"));

        SFTPManagerTab.applyLocalOwnerAndMode(dir, "", "700", false);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(inner))).isEqualTo("rw-r--r--");
    }

    @Test
    void aModeWithoutSearchRightStillReachesTheDeepestEntries() throws IOException {
        requirePosix();
        // rw- on a folder takes away the right to reach inside it: changed parent-first, the
        // entries below would no longer be reachable.
        Path sub = Files.createDirectories(root.resolve("dir/sub"));
        Path nested = Files.writeString(sub.resolve("b.txt"), "b");

        SFTPManagerTab.applyLocalOwnerAndMode(root.resolve("dir"), "", "600", true);

        Files.setPosixFilePermissions(root.resolve("dir"), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwx------"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(nested))).isEqualTo("rw-------");
    }

    @Test
    void ownerAndGroupAreSetLocally() throws IOException {
        requirePosix();
        Path file = Files.writeString(root.resolve("owned.txt"), "x");
        PosixFileAttributes before = Files.readAttributes(file, PosixFileAttributes.class);
        String owner = before.owner().getName();
        String group = before.group().getName();

        // The user's own name and group: allowed without root, and proves the names are resolved.
        SFTPManagerTab.applyLocalOwnerAndMode(file, owner + ":" + group, "", false);
        SFTPManagerTab.applyLocalOwnerAndMode(file, ":" + group, "640", false);

        PosixFileAttributes after = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(after.owner().getName()).isEqualTo(owner);
        assertThat(after.group().getName()).isEqualTo(group);
        assertThat(PosixFilePermissions.toString(after.permissions())).isEqualTo("rw-r-----");
    }

    @Test
    void anUnknownUserIsAnErrorNotASilentSuccess() throws IOException {
        requirePosix();
        Path file = Files.writeString(root.resolve("f.txt"), "x");

        assertThrows(UserPrincipalNotFoundException.class,
            () -> SFTPManagerTab.applyLocalOwnerAndMode(file, "no-such-user-kortty-test", "", false));
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void requirePosix() {
        if (!posix()) {
            throw new SkipException("POSIX permissions are not available on this file system");
        }
    }
}
