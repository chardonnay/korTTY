package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The 0700-before-bind directory guard: creation, idempotence, and every refusal. */
class ControlDirectoryTest {

    private Path root;

    @BeforeMethod
    void createRoot() throws IOException {
        root = UdsTestSupport.newTempRoot();
    }

    @AfterMethod(alwaysRun = true)
    void removeRoot() {
        UdsTestSupport.deleteTree(root);
    }

    @Test
    void createAndVerifyCreatesAnOwnerOnlyDirectory() throws Exception {
        Path dir = ControlDirectory.createAndVerify(root);

        assertThat(dir).isEqualTo(root.resolve(ControlDirectory.DIRECTORY_NAME));
        assertThat(Files.isDirectory(dir)).isTrue();
        UdsTestSupport.skipWithoutPosix(dir);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
    }

    @Test
    void createAndVerifyIsIdempotent() throws Exception {
        Path first = ControlDirectory.createAndVerify(root);
        Path second = ControlDirectory.createAndVerify(root);

        assertThat(second).isEqualTo(first);
        assertThat(Files.isDirectory(second)).isTrue();
    }

    @Test
    void createAndVerifyRefusesWithoutAConfigurationDirectory() {
        expectThrows(ControlApiException.class, () -> ControlDirectory.createAndVerify(null));
    }

    @Test
    void verifyRefusesASymlinkedDirectory() throws Exception {
        UdsTestSupport.skipOnWindows();
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = root.resolve("control");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            throw new SkipException("this platform does not allow creating symbolic links");
        }

        ControlApiException refusal = expectThrows(ControlApiException.class,
            () -> ControlDirectory.verify(link));

        assertThat(refusal.code()).isEqualTo(ControlErrorCode.UNSUPPORTED);
        assertThat(refusal.data()).containsEntry("reason", "symlink");
    }

    @Test
    void verifyRefusesGroupOrOtherBits() throws Exception {
        Path dir = Files.createDirectory(root.resolve("control"));
        UdsTestSupport.skipWithoutPosix(dir);
        Files.setPosixFilePermissions(dir, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE));

        ControlApiException refusal = expectThrows(ControlApiException.class,
            () -> ControlDirectory.verify(dir));

        assertThat(refusal.data()).containsEntry("reason", "wide_permissions");
    }

    @Test
    void verifyRefusesAPlainFile() throws Exception {
        Path file = Files.createFile(root.resolve("control"));

        ControlApiException refusal = expectThrows(ControlApiException.class,
            () -> ControlDirectory.verify(file));

        assertThat(refusal.data()).containsEntry("reason", "not_a_directory");
    }

    @Test
    void verifyRefusesAMissingDirectory() {
        expectThrows(ControlApiException.class, () -> ControlDirectory.verify(root.resolve("absent")));
        expectThrows(ControlApiException.class, () -> ControlDirectory.verify(null));
    }

    @Test
    void aFilesystemWithoutPosixAttributesFallsBackToTheDirectoryAcl() throws Exception {
        Path dir = Files.createDirectory(root.resolve("control"));

        ControlDirectory.verify(dir, path -> {
            throw new UnsupportedOperationException("no POSIX view here");
        });
    }
}
