package de.kortty.security;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * {@code master.key} carries the salt every stored secret is derived from: a truncated file loses
 * them all, and a world-readable one lets anyone guess the master password offline. It is written
 * atomically and owner-only, and a damaged file is reported by name but never moved aside — that
 * would make korTTY think no master password was set and mint a new salt.
 */
class MasterPasswordKeyFileTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-master-key");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDir() throws IOException {
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void masterKeyIsOwnerOnlyAfterSetupAndPasswordChange() throws Exception {
        Path keyFile = dir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        MasterPasswordManager manager = new MasterPasswordManager(dir);

        manager.setupPassword("first-pass".toCharArray());
        requirePosix(keyFile);
        assertThat(mode(keyFile)).isEqualTo("rw-------");

        // As an older korTTY left it: created with the default umask.
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"));
        manager.commitPasswordChange(manager.beginPasswordChange(
            "first-pass".toCharArray(), "second-pass".toCharArray()));

        assertThat(mode(keyFile)).isEqualTo("rw-------");
        assertThat(siblings()).containsExactly(MasterPasswordManager.MASTER_KEY_FILE);
        MasterPasswordManager restarted = new MasterPasswordManager(dir);
        assertThat(restarted.verifyPassword("second-pass".toCharArray())).isTrue();
        assertThat(restarted.verifyPassword("first-pass".toCharArray())).isFalse();
    }

    @Test
    void setupLeavesNoTempFileBehind() throws Exception {
        new MasterPasswordManager(dir).setupPassword("pass".toCharArray());

        assertThat(siblings()).containsExactly(MasterPasswordManager.MASTER_KEY_FILE);
        assertThat(Files.readString(dir.resolve(MasterPasswordManager.MASTER_KEY_FILE)))
            .contains("salt=");
    }

    @Test
    void truncatedMasterKeyFailsWithAnErrorNamingTheFileAndStaysInPlace() throws Exception {
        new MasterPasswordManager(dir).setupPassword("pass".toCharArray());
        Path keyFile = dir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        String complete = Files.readString(keyFile);
        assertThat(complete).contains("salt=");
        assertThat(complete).contains("hash=");
        // A write cut off after either line, an empty file, blank values and a damaged salt.
        String saltOnly = withoutLine(complete, "hash=");
        String hashOnly = withoutLine(complete, "salt=");
        for (String damaged : List.of(saltOnly, hashOnly, "", "#KorTTY Master Password\nsalt=\nhash=\n",
                "salt=%%%\nhash=abc\n")) {
            Files.writeString(keyFile, damaged);
            MasterPasswordManager manager = new MasterPasswordManager(dir);

            IOException failure = expectThrows(IOException.class,
                () -> manager.verifyPassword("pass".toCharArray()));

            assertThat(failure).hasMessageThat().contains(keyFile.toString());
            assertThat(manager.isPasswordSet()).isTrue();
            assertThat(Files.readString(keyFile)).isEqualTo(damaged);
            assertThat(siblings()).containsExactly(MasterPasswordManager.MASTER_KEY_FILE);
        }
    }

    @Test
    void masterKeyWhoseExistenceCannotBeCheckedIsNeverTreatedAsAFreshProfile() throws Exception {
        assertThat(new MasterPasswordManager(dir).isPasswordSet()).isFalse();
        new MasterPasswordManager(dir).setupPassword("pass".toCharArray());
        Path keyFile = dir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        String original = Files.readString(keyFile);
        requirePosix(dir);
        if ("root".equals(System.getProperty("user.name"))) {
            throw new SkipException("needs POSIX permissions that apply to the current user");
        }
        MasterPasswordManager manager = new MasterPasswordManager(dir);

        // ~/.kortty cannot be searched, as with a network home directory that is briefly
        // unreachable: Files.exists is false, but master.key is there. Reporting "not set" would
        // start the first-run setup, which mints a new salt over it and orphans every secret.
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rw-------"));
        try {
            assertThat(Files.exists(keyFile)).isFalse();
            assertThat(manager.isPasswordSet()).isTrue();
            expectThrows(IOException.class, () -> manager.verifyPassword("pass".toCharArray()));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(Files.readString(keyFile)).isEqualTo(original);
        assertThat(manager.verifyPassword("pass".toCharArray())).isTrue();
    }

    private static String withoutLine(String properties, String prefix) {
        StringBuilder kept = new StringBuilder();
        properties.lines().filter(line -> !line.startsWith(prefix)).forEach(line -> kept.append(line).append('\n'));
        return kept.toString();
    }

    private static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    private List<String> siblings() throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void requirePosix(Path path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
            throw new SkipException("POSIX file attributes are not supported on this platform");
        }
    }
}
