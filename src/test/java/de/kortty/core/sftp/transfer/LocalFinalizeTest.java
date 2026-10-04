package de.kortty.core.sftp.transfer;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Local part files and their finalize: exclusive owner-only creation, no symbolic links followed,
 * final permissions after D25, and the hardened name checks (Windows device names, case-insensitive
 * collisions).
 */
class LocalFinalizeTest {

    private Path tmp;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-local-finalize-");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tmp)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aSymlinkedTargetIsRefusedAndItsTargetKept() throws IOException {
        requirePosix();
        Path elsewhere = Files.writeString(tmp.resolve("elsewhere.txt"), "keep");
        Path target = Files.createSymbolicLink(tmp.resolve("target.txt"), elsewhere);
        Path part = writtenPart(target, "new");

        expectThrows(IOException.class, () -> LocalFinalizer.finalizePart(part, target, 0644, null));

        assertThat(Files.readString(elsewhere)).isEqualTo("keep");
        assertThat(Files.isSymbolicLink(target)).isTrue();
        expectThrows(IOException.class, () -> LocalFinalizer.requireReplaceableTarget(target));
    }

    @Test
    void aFolderTargetIsRefused() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("folder"));
        Path part = writtenPart(target, "new");

        expectThrows(IOException.class, () -> LocalFinalizer.finalizePart(part, target, null, null));

        assertThat(Files.isDirectory(target)).isTrue();
    }

    @Test
    void thePartIsCreatedOwnerOnlyAndExclusively() throws IOException {
        requirePosix();
        Path part = PartFiles.localPart(tmp.resolve("file.bin"));
        try (LocalFinalizer.LocalPart created = LocalFinalizer.createPart(part)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(part))).isEqualTo("rw-------");
            assertThat(created.path()).isEqualTo(part);
        }
        expectThrows(PartExistsException.class, () -> LocalFinalizer.createPart(part));
    }

    @Test
    void aSymlinkAtThePartNameIsNotFollowed() throws IOException {
        requirePosix();
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "precious");
        Path part = PartFiles.localPart(tmp.resolve("file.bin"));
        Files.createSymbolicLink(part, outside);
        Path dangling = tmp.resolve("dangling-target");
        Path otherPart = PartFiles.localPart(tmp.resolve("other.bin"));
        Files.createSymbolicLink(otherPart, dangling);

        expectThrows(PartExistsException.class, () -> LocalFinalizer.createPart(part));
        expectThrows(PartExistsException.class, () -> LocalFinalizer.createPart(otherPart));

        assertThat(Files.readString(outside)).isEqualTo("precious");
        assertThat(Files.exists(dangling)).isFalse();
    }

    @Test
    void aReplacedFileKeepsItsPermissions() throws IOException {
        requirePosix();
        Path target = Files.writeString(tmp.resolve("script.sh"), "old");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-x---"));
        Path part = writtenPart(target, "new");

        FinalizeMethod method = LocalFinalizer.finalizePart(part, target, 0600, null);

        assertThat(method).isAnyOf(FinalizeMethod.LOCAL_ATOMIC_MOVE, FinalizeMethod.LOCAL_MOVE);
        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rwxr-x---");
        assertThat(Files.exists(part)).isFalse();
    }

    @Test
    void aNewFileGetsTheRemoteModeWithoutSpecialBitsAndGroupOtherWrite() throws IOException {
        requirePosix();
        Path target = tmp.resolve("tool");
        Path part = writtenPart(target, "bin");

        LocalFinalizer.finalizePart(part, target, 04777, null);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rwxr-xr-x");
    }

    @Test
    void aNewFileWithoutRemoteModeGetsAPlainCreateMode() throws IOException {
        requirePosix();
        Path target = tmp.resolve("plain.txt");
        Path part = writtenPart(target, "text");

        LocalFinalizer.finalizePart(part, target, null, null);

        // Never left at the part's rw------- silently.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-r--r--");
    }

    @Test
    void finalModeMasksAsDecided() {
        assertThat(LocalFinalizer.finalMode(null)).isEqualTo(0644);
        assertThat(LocalFinalizer.finalMode(0600)).isEqualTo(0600);
        assertThat(LocalFinalizer.finalMode(0777)).isEqualTo(0755);
        assertThat(LocalFinalizer.finalMode(06775)).isEqualTo(0755);
        assertThat(LocalFinalizer.finalMode(01666)).isEqualTo(0644);
        assertThat(PosixFilePermissions.toString(LocalFinalizer.fromMode(0754))).isEqualTo("rwxr-xr--");
    }

    @Test
    void windowsReservedAndAlteredNamesAreRejectedUnderWindowsRules() throws IOException {
        for (String name : new String[] {"CON", "con", "nul.txt", "Com1", "LPT9.tar.gz", "aux .log", "PRN",
                "trailing.", "trailing ", "a:b", "what?", "pipe|name", "a\\b"}) {
            // The Windows rules are a pure name check that runs before any Path exists, so they
            // hold on every host; on Windows itself the name never reaches Path.resolve.
            expectThrows(IOException.class, () -> LocalNames.checkName(name, true));
            expectThrows(IOException.class, () -> LocalNames.localChild(tmp, name, true));
            // Other systems store these names as they are. A Windows host cannot even build such a
            // Path (InvalidPathException for "a:b", "what?", "trailing "), so it is checked elsewhere.
            if (name.indexOf('\\') < 0 && !LocalNames.windowsRules()) {
                assertThat(LocalNames.localChild(tmp, name, false)).isEqualTo(tmp.resolve(name));
            }
        }
        for (String name : new String[] {"console.log", "CONFIG", "COM10", "nul-device", "report.pdf", ".env"}) {
            assertThat(LocalNames.localChild(tmp, name, true)).isEqualTo(tmp.resolve(name));
        }
        for (String name : new String[] {"..", ".", "", "../escaped", "sub/file", "/etc/passwd", "nul\0byte"}) {
            expectThrows(IOException.class, () -> LocalNames.localChild(tmp, name, false));
        }
    }

    @Test
    void caseInsensitiveCollisionsAreFlagged() throws IOException {
        Files.writeString(tmp.resolve("Report.txt"), "existing");
        if (!LocalNames.isCaseInsensitive(tmp)) {
            assertThat(LocalNames.caseCollision(tmp, "report.txt")).isEmpty();
            throw new SkipException("case-sensitive file store");
        }
        assertThat(LocalNames.caseCollision(tmp, "report.txt")).hasValue("Report.txt");
        assertThat(LocalNames.caseCollision(tmp, "Report.txt")).isEmpty();
        assertThat(LocalNames.caseCollision(tmp, "other.txt")).isEmpty();
        // The part of a differently cased target collides with an existing part too.
        Path part = PartFiles.localPart(tmp.resolve("data.bin"));
        try (LocalFinalizer.LocalPart ignored = LocalFinalizer.createPart(part)) {
            expectThrows(PartExistsException.class,
                () -> LocalFinalizer.createPart(PartFiles.localPart(tmp.resolve("DATA.bin"))));
        }
    }

    @Test
    void partNamesAreDerivedAndRecognised() throws IOException {
        assertThat(PartFiles.localPart(tmp.resolve("a.tar.gz"))).isEqualTo(tmp.resolve("a.tar.gz.kortty-part"));
        assertThat(PartFiles.remotePart("/srv/www/index.html")).isEqualTo("/srv/www/index.html.kortty-part");
        assertThat(PartFiles.remotePart("relative.txt")).isEqualTo("relative.txt.kortty-part");
        assertThat(PartFiles.remoteBackup("/srv/a", 0)).isEqualTo("/srv/a.kortty-old");
        assertThat(PartFiles.remoteBackup("/srv/a", 2)).isEqualTo("/srv/a.kortty-old.2");
        assertThat(PartFiles.isPartName("a.kortty-part")).isTrue();
        assertThat(PartFiles.isPartName(".kortty-part")).isFalse();
        assertThat(PartFiles.isPartName("a.txt")).isFalse();
        assertThat(PartFiles.isBackupName("a.kortty-old")).isTrue();
        assertThat(PartFiles.isBackupName("a.kortty-old.7")).isTrue();
        assertThat(PartFiles.isBackupName("a.kortty-older")).isFalse();
        expectThrows(IOException.class, () -> PartFiles.remotePart("/srv/"));
        expectThrows(IOException.class, () -> PartFiles.remotePart("/srv/.."));
    }

    private static Path writtenPart(Path target, String content) throws IOException {
        Path part = PartFiles.localPart(target);
        try (LocalFinalizer.LocalPart created = LocalFinalizer.createPart(part)) {
            created.channel().write(ByteBuffer.wrap(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return part;
    }

    private static void requirePosix() {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            throw new SkipException("POSIX permissions and symbolic links only");
        }
    }
}
