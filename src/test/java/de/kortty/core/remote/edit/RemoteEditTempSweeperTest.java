package de.kortty.core.remote.edit;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;

/**
 * The start-up sweep of leftover edit folders: old ones go, recent ones stay, names korTTY never
 * gives stay, and links are never followed.
 */
class RemoteEditTempSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration AGE = RemoteEditTempSweeper.MAXIMUM_AGE;

    private Path root;
    private Path outside;
    private UserPrincipal user;

    @BeforeMethod
    void setUp() throws IOException {
        root = Files.createTempDirectory("kortty-sweep-root-");
        outside = Files.createTempDirectory("kortty-sweep-outside-");
        user = Files.getOwner(root);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() {
        RemoteEditTempDirs.deleteTree(root);
        RemoteEditTempDirs.deleteTree(outside);
    }

    @Test
    void oldFoldersAreRemovedAndRecentOnesKept() throws IOException {
        Path old = editFolder(NOW.minus(Duration.ofHours(30)));
        Path recent = editFolder(NOW.minus(Duration.ofHours(2)));

        int removed = RemoteEditTempSweeper.sweep(root, user, NOW, AGE);

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(recent)).isTrue();
    }

    @Test
    void aFolderWithARecentlySavedFileIsKept() throws IOException {
        Path folder = editFolder(NOW.minus(Duration.ofHours(30)));
        Path file = folder.resolve("app.conf");
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofMinutes(5))));
        Files.setLastModifiedTime(folder, FileTime.from(NOW.minus(Duration.ofHours(30))));

        assertThat(RemoteEditTempSweeper.sweep(root, user, NOW, AGE)).isEqualTo(0);
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void otherNamesAreNeverTouched() throws IOException {
        Path foreign = Files.createDirectory(root.resolve("kortty-remote-edit-notauuid"));
        Path other = Files.createDirectory(root.resolve("something-else"));
        Files.setLastModifiedTime(foreign, FileTime.from(NOW.minus(Duration.ofDays(10))));
        Files.setLastModifiedTime(other, FileTime.from(NOW.minus(Duration.ofDays(10))));

        assertThat(RemoteEditTempSweeper.sweep(root, user, NOW, AGE)).isEqualTo(0);
        assertThat(Files.exists(foreign)).isTrue();
        assertThat(Files.exists(other)).isTrue();
    }

    @Test
    void linksAreNeverFollowed() throws IOException {
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
        Path folder = editFolder(NOW.minus(Duration.ofHours(30)));
        try {
            Files.createSymbolicLink(folder.resolve("link-to-file"), precious);
            Files.createSymbolicLink(folder.resolve("link-to-dir"), outside);
            // A link with an edit folder's name, pointing at a folder outside.
            Files.createSymbolicLink(root.resolve(RemoteEditTempDirs.PREFIX + UUID.randomUUID()), outside);
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Symbolic links are not available here: " + e);
        }
        Files.setLastModifiedTime(folder, FileTime.from(NOW.minus(Duration.ofHours(30))));
        setLinkTimes(folder);

        int removed = RemoteEditTempSweeper.sweep(root, user, NOW, AGE);

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(folder, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readString(precious)).isEqualTo("keep me");
        assertThat(Files.isDirectory(outside)).isTrue();
        try (var left = Files.list(root)) {
            // Only the top-level link is left: it is not a real folder, so the sweep ignores it.
            assertThat(left.allMatch(Files::isSymbolicLink)).isTrue();
        }
    }

    @Test
    void aMissingRootSweepsNothing() {
        assertThat(RemoteEditTempSweeper.sweep(root.resolve("absent"), user, NOW, AGE)).isEqualTo(0);
        assertThat(RemoteEditTempSweeper.sweep(null, user, NOW, AGE)).isEqualTo(0);
    }

    private Path editFolder(Instant modified) throws IOException {
        Path folder = Files.createDirectory(root.resolve(RemoteEditTempDirs.PREFIX + UUID.randomUUID()));
        Path file = Files.writeString(folder.resolve("app.conf"), "x");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        Files.setLastModifiedTime(folder, FileTime.from(modified));
        return folder;
    }

    /** Links get the time of their creation; the sweep reads them as links, so age them too where possible. */
    private static void setLinkTimes(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.toList()) {
                if (Files.isSymbolicLink(entry)) {
                    try {
                        Files.getFileAttributeView(entry, java.nio.file.attribute.BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS).setTimes(FileTime.from(NOW.minus(Duration.ofHours(30))), null, null);
                    } catch (IOException | UnsupportedOperationException e) {
                        throw new SkipException("Cannot set the time of a link here: " + e);
                    }
                }
            }
        }
    }
}
