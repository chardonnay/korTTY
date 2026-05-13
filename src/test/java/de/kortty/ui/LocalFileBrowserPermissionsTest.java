package de.kortty.ui;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LocalFileBrowserPermissionsTest {

    @Test
    void convertsOctalPermissionsToPosixString() {
        assertThat(LocalFileBrowser.octalToPosix("755")).isEqualTo("rwxr-xr-x");
        assertThat(LocalFileBrowser.octalToPosix("640")).isEqualTo("rw-r-----");
    }

    @Test
    void validatesOnlyThreeOctalPermissionDigits() {
        assertThat(LocalFileBrowser.isValidOctalPermissions("755")).isTrue();
        assertThat(LocalFileBrowser.isValidOctalPermissions("0755")).isFalse();
        assertThat(LocalFileBrowser.isValidOctalPermissions("888")).isFalse();
        assertThat(LocalFileBrowser.isValidOctalPermissions("rw-r--r--")).isFalse();
    }

    @Test
    void convertsPosixPermissionsToOctal() {
        var permissions = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE);

        assertThat(LocalFileBrowser.permissionsToOctal(permissions)).isEqualTo("755");
    }

    @Test
    void readsUnixPrincipalNamesFromColonSeparatedFile() throws Exception {
        Path file = Files.createTempFile("kortty-principals", ".txt");
        try {
            Files.writeString(file, """
                # comment
                root:x:0:0:root:/root:/bin/sh
                daniel:x:1000:1000:Daniel:/home/daniel:/bin/zsh

                """);

            assertThat(LocalFileBrowser.readUnixPrincipalNames(file)).containsExactly("daniel", "root").inOrder();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void writesTarOctalWithTrailingNull() {
        byte[] header = new byte[8];

        LocalFileBrowser.writeTarOctal(header, 0, 8, 0755);

        assertThat(new String(header, 0, 7, StandardCharsets.US_ASCII)).isEqualTo("0000755");
        assertThat(header[7]).isEqualTo((byte) 0);
    }

    @Test
    void rejectsTarOctalOverflowInsteadOfTruncating() {
        byte[] header = new byte[8];
        long valueNeedingEightOctalDigits = Long.parseLong("10000000", 8);

        IllegalArgumentException exception = expectThrows(
            IllegalArgumentException.class,
            () -> LocalFileBrowser.writeTarOctal(header, 0, 8, valueNeedingEightOctalDigits));

        assertThat(exception).hasMessageThat().contains("GNU/POSIX extended TAR headers are not supported");
    }

    @Test
    void movesDirectoryIntoExistingDirectoryWithFallback() throws Exception {
        Path root = Files.createTempDirectory("kortty-move-directory");
        try {
            Path source = root.resolve("source");
            Path destination = root.resolve("destination");
            Files.createDirectories(source);
            Files.createDirectories(destination);
            Files.writeString(source.resolve("new.txt"), "new");
            Files.writeString(destination.resolve("existing.txt"), "existing");

            LocalFileBrowser.moveDirectory(source, destination);

            assertThat(Files.exists(source)).isFalse();
            assertThat(Files.readString(destination.resolve("new.txt"))).isEqualTo("new");
            assertThat(Files.readString(destination.resolve("existing.txt"))).isEqualTo("existing");
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
