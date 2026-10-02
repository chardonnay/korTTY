package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class LegacyTemporaryKeyFileCleanupTest {

    private static final FileTime STALE = FileTime.from(Instant.now().minus(Duration.ofHours(1)));

    @Test
    void deletesOnlyStaleKeyFilesWithTheExactLegacyNames() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-temp-key-sweep-");
        try {
            Path staleTerminalKey = staleFile(tmp.resolve("kortty_temp_key_123.key"));
            Path staleSchedulerKey = staleFile(tmp.resolve("kortty_scheduler_key_9223372036854775807.key"));
            Path freshKey = Files.writeString(tmp.resolve("kortty_temp_key_456.key"), "fresh");
            Path nonNumeric = staleFile(tmp.resolve("kortty_temp_key_abc.key"));
            Path wrongSuffix = staleFile(tmp.resolve("kortty_temp_key_123.key.bak"));
            Path otherKind = staleFile(tmp.resolve("kortty_other_key_123.key"));
            Path prefixed = staleFile(tmp.resolve("xkortty_temp_key_123.key"));
            Path unrelated = staleFile(tmp.resolve("notes.txt"));
            Path directory = Files.createDirectory(tmp.resolve("kortty_temp_key_789.key"));
            Files.setLastModifiedTime(directory, STALE);

            int removed = LegacyTemporaryKeyFileCleanup.cleanup(tmp, ownerOf(staleTerminalKey), Instant.now());

            assertThat(removed).isEqualTo(2);
            assertThat(Files.exists(staleTerminalKey, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.exists(staleSchedulerKey, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.readString(freshKey)).isEqualTo("fresh");
            assertThat(Files.exists(nonNumeric)).isTrue();
            assertThat(Files.exists(wrongSuffix)).isTrue();
            assertThat(Files.exists(otherKind)).isTrue();
            assertThat(Files.exists(prefixed)).isTrue();
            assertThat(Files.exists(unrelated)).isTrue();
            assertThat(Files.isDirectory(directory)).isTrue();

            // Idempotent: nothing left to remove on a second start.
            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(tmp, ownerOf(freshKey), Instant.now())).isEqualTo(0);
        } finally {
            deleteTestTree(tmp);
        }
    }

    @Test
    void theNamePatternMatchesWhatTheJdkGaveTheLeftoverFiles() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-temp-key-sweep-names-");
        try {
            for (int i = 0; i < 20; i++) {
                // How earlier versions created them: File.createTempFile and Files.createTempFile.
                String terminalKey = java.io.File.createTempFile("kortty_temp_key_", ".key", tmp.toFile()).getName();
                String schedulerKey = Files.createTempFile(tmp, "kortty_scheduler_key_", ".key")
                    .getFileName().toString();

                assertThat(LegacyTemporaryKeyFileCleanup.KEY_FILE_NAME.matcher(terminalKey).matches()).isTrue();
                assertThat(LegacyTemporaryKeyFileCleanup.KEY_FILE_NAME.matcher(schedulerKey).matches()).isTrue();
            }
        } finally {
            deleteTestTree(tmp);
        }
    }

    @Test
    void keepsAKeyFileWrittenWithinTheLastFiveMinutes() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-temp-key-sweep-age-");
        try {
            Path key = Files.writeString(tmp.resolve("kortty_temp_key_1.key"), "in use");
            Instant modified = Files.getLastModifiedTime(key).toInstant();

            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(
                tmp, ownerOf(key), modified.plus(Duration.ofMinutes(4)))).isEqualTo(0);
            assertThat(Files.exists(key)).isTrue();

            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(
                tmp, ownerOf(key), modified.plus(Duration.ofMinutes(6)))).isEqualTo(1);
            assertThat(Files.exists(key)).isFalse();
        } finally {
            deleteTestTree(tmp);
        }
    }

    @Test
    void keepsKeyFilesOwnedBySomebodyElse() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-temp-key-sweep-owner-");
        try {
            Path key = staleFile(tmp.resolve("kortty_temp_key_42.key"));
            UserPrincipal somebodyElse = () -> "kortty-test-other-user";

            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(tmp, somebodyElse, Instant.now())).isEqualTo(0);
            assertThat(Files.exists(key)).isTrue();
        } finally {
            deleteTestTree(tmp);
        }
    }

    @Test
    void neverFollowsALinkNamedLikeAKey() throws Exception {
        Path root = Files.createTempDirectory("kortty-temp-key-sweep-link-");
        try {
            Path tmp = Files.createDirectory(root.resolve("tmp"));
            Path target = staleFile(root.resolve("kortty_temp_key_7.key"));
            Path link = tmp.resolve("kortty_temp_key_8.key");
            createSymbolicLinkOrSkip(link, target);

            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(tmp, ownerOf(target), Instant.now())).isEqualTo(0);
            assertThat(Files.readString(target)).isEqualTo("legacy key");
            assertThat(Files.isSymbolicLink(link)).isTrue();
        } finally {
            deleteTestTree(root);
        }
    }

    @Test
    void refusesALinkedTempFolder() throws Exception {
        Path root = Files.createTempDirectory("kortty-temp-key-sweep-linked-root-");
        try {
            Path real = Files.createDirectory(root.resolve("real"));
            Path key = staleFile(real.resolve("kortty_temp_key_9.key"));
            Path linkedRoot = root.resolve("linked");
            createSymbolicLinkOrSkip(linkedRoot, real);

            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(linkedRoot, ownerOf(key), Instant.now())).isEqualTo(0);
            assertThat(Files.exists(key)).isTrue();
        } finally {
            deleteTestTree(root);
        }
    }

    @Test
    void ignoresMissingInputs() throws Exception {
        Path root = Files.createTempDirectory("kortty-temp-key-sweep-missing-");
        try {
            Path key = staleFile(root.resolve("kortty_temp_key_10.key"));

            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(null, ownerOf(key), Instant.now())).isEqualTo(0);
            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(root, null, Instant.now())).isEqualTo(0);
            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(
                root.resolve("absent"), ownerOf(key), Instant.now())).isEqualTo(0);
            assertThat(LegacyTemporaryKeyFileCleanup.cleanup(key, ownerOf(key), Instant.now())).isEqualTo(0);
            assertThat(Files.exists(key)).isTrue();
        } finally {
            deleteTestTree(root);
        }
    }

    @Test
    void resolvesTheCurrentUserAsTheOwnerOfItsOwnFiles() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            // Files an elevated Windows account creates may belong to the Administrators group.
            throw new SkipException("The owner of a new file is the current user only on POSIX systems");
        }
        Path file = Files.createTempFile("kortty-temp-key-owner-", ".tmp");
        try {
            assertThat(LegacyTemporaryKeyFileCleanup.currentUser(FileSystems.getDefault()))
                .isEqualTo(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Path staleFile(Path file) throws IOException {
        Files.writeString(file, "legacy key", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, STALE);
        return file;
    }

    private static UserPrincipal ownerOf(Path file) throws IOException {
        return Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SkipException("Symbolic links are not available for this test", e);
        }
    }

    private static void deleteTestTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
