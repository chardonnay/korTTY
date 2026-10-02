package de.kortty.core;

import de.kortty.model.SSHKey;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class SSHKeyManagerTest {

    @Test
    void copiedPrivateKeyAndKeyDirectoryAreOwnerOnly() throws Exception {
        Path root = Files.createTempDirectory("kortty-ssh-key-copy");
        try {
            if (Files.getFileAttributeView(root, PosixFileAttributeView.class) == null) {
                throw new SkipException("POSIX file attributes are not supported on this platform");
            }
            Path source = Files.createDirectory(root.resolve("source"));
            Path privateKey = Files.writeString(source.resolve("id_demo"), "demo private key material");
            Path publicKey = Files.writeString(source.resolve("id_demo.pub"), "ssh-ed25519 AAAA demo");
            // A private key the user keeps readable by others: korTTY's copy must not inherit that.
            Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("rw-r--r--"));
            Files.setPosixFilePermissions(publicKey, PosixFilePermissions.fromString("rw-r--r--"));
            Path configDir = Files.createDirectory(root.resolve("config"));
            // An ssh-keys/ directory an older korTTY created with the default umask.
            Path keysDir = Files.createDirectory(configDir.resolve("ssh-keys"));
            Files.setPosixFilePermissions(keysDir, PosixFilePermissions.fromString("rwxr-xr-x"));
            SSHKeyManager manager = new SSHKeyManager(configDir);
            SSHKey key = new SSHKey("demo", privateKey.toString());

            Path copy = manager.copyKeyToUserDir(key);

            assertThat(Files.readString(copy)).isEqualTo("demo private key material");
            assertThat(mode(copy)).isEqualTo("rw-------");
            assertThat(mode(keysDir)).isEqualTo("rwx------");
            assertThat(key.isCopiedToUserDir()).isTrue();
            assertThat(key.getUserDirPath()).isEqualTo(copy.toString());
            Path publicCopy = copy.resolveSibling(copy.getFileName() + ".pub");
            assertThat(Files.readString(publicCopy)).isEqualTo("ssh-ed25519 AAAA demo");
            // The source is the user's file: korTTY does not touch its permissions.
            assertThat(mode(privateKey)).isEqualTo("rw-r--r--");
        } finally {
            deleteTree(root);
        }
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
