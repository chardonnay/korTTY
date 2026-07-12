package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class LegacyDiagramCacheCleanupTest {

    @Test
    void resolvesXdgCacheAndFallsBackToUserCache() throws Exception {
        Path root = Files.createTempDirectory("kortty-legacy-cache-resolution-");
        try {
            Path xdgCache = root.resolve("xdg-cache").toAbsolutePath();
            Path userHome = root.resolve("home").toAbsolutePath();

            assertThat(LegacyDiagramCacheCleanup.resolveCacheDirectory(
                xdgCache.toString(), userHome.toString()))
                .isEqualTo(xdgCache.resolve("kortty/plantuml"));
            assertThat(LegacyDiagramCacheCleanup.resolveCacheDirectory(
                "relative-cache", userHome.toString()))
                .isEqualTo(userHome.resolve(".cache/kortty/plantuml"));
            assertThat(LegacyDiagramCacheCleanup.resolveCacheDirectory(null, null)).isNull();
        } finally {
            deleteTestTree(root);
        }
    }

    @Test
    void removesOnlyPlantUmlOwnedCacheAndTempPathsIdempotently() throws Exception {
        Path root = Files.createTempDirectory("kortty-legacy-plantuml-cleanup-");
        try {
            Path cacheDirectory = Files.createDirectories(
                root.resolve("cache/kortty/plantuml/nested")).getParent();
            Files.writeString(cacheDirectory.resolve("nested/plantuml.jar"), "legacy",
                StandardCharsets.UTF_8);

            Path tempDirectory = Files.createDirectory(root.resolve("tmp"));
            Path firstLegacyTemp = Files.createDirectories(
                tempDirectory.resolve("kortty-snippet-plantuml-first/nested")).getParent();
            Files.writeString(firstLegacyTemp.resolve("nested/source.puml"), "@startuml",
                StandardCharsets.UTF_8);
            Path secondLegacyTemp = Files.writeString(
                tempDirectory.resolve("kortty-snippet-plantuml-file"), "legacy",
                StandardCharsets.UTF_8);
            Path freshLegacyTemp = Files.createDirectory(
                tempDirectory.resolve("kortty-snippet-plantuml-active"));
            FileTime stale = FileTime.from(Instant.now().minus(Duration.ofHours(25)));
            Files.setLastModifiedTime(firstLegacyTemp, stale);
            Files.setLastModifiedTime(secondLegacyTemp, stale);
            Path unrelatedDirectory = Files.createDirectories(
                tempDirectory.resolve("kortty-snippet-mermaid/nested"));
            Path unrelatedFile = Files.writeString(tempDirectory.resolve("notes.txt"), "keep",
                StandardCharsets.UTF_8);

            LegacyDiagramCacheCleanup.cleanup(cacheDirectory, tempDirectory);
            LegacyDiagramCacheCleanup.cleanup(cacheDirectory, tempDirectory);

            assertThat(Files.exists(cacheDirectory, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.exists(cacheDirectory.getParent(), LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.exists(firstLegacyTemp, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.exists(secondLegacyTemp, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.exists(freshLegacyTemp, LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.exists(unrelatedDirectory, LinkOption.NOFOLLOW_LINKS)).isTrue();
            assertThat(Files.readString(unrelatedFile, StandardCharsets.UTF_8)).isEqualTo("keep");
        } finally {
            deleteTestTree(root);
        }
    }

    @Test
    void removesLinksWithoutFollowingThemAndRejectsLinkedAppCacheParent() throws Exception {
        Path root = Files.createTempDirectory("kortty-legacy-plantuml-links-");
        try {
            Path externalDirectory = Files.createDirectories(root.resolve("external"));
            Path externalSentinel = Files.writeString(externalDirectory.resolve("keep.txt"), "keep",
                StandardCharsets.UTF_8);

            Path cacheDirectory = Files.createDirectories(root.resolve("cache/kortty/plantuml"));
            createSymbolicLinkOrSkip(cacheDirectory.resolve("external-link"), externalDirectory);

            Path tempDirectory = Files.createDirectory(root.resolve("tmp"));
            Path legacyTempLink = tempDirectory.resolve(
                LegacyDiagramCacheCleanup.TEMP_DIRECTORY_PREFIX + "link");
            createSymbolicLinkOrSkip(legacyTempLink, externalDirectory);
            Path unrelatedLink = tempDirectory.resolve("unrelated-link");
            createSymbolicLinkOrSkip(unrelatedLink, externalDirectory);

            LegacyDiagramCacheCleanup.cleanup(cacheDirectory, tempDirectory);

            assertThat(Files.exists(cacheDirectory, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.exists(legacyTempLink, LinkOption.NOFOLLOW_LINKS)).isFalse();
            assertThat(Files.isSymbolicLink(unrelatedLink)).isTrue();
            assertThat(Files.readString(externalSentinel, StandardCharsets.UTF_8)).isEqualTo("keep");

            Path linkedTarget = Files.createDirectories(root.resolve("linked-target/plantuml"));
            Path linkedTargetSentinel = Files.writeString(linkedTarget.resolve("keep.jar"), "keep",
                StandardCharsets.UTF_8);
            Path linkedCacheBase = Files.createDirectories(root.resolve("linked-cache"));
            Path linkedAppCache = linkedCacheBase.resolve("kortty");
            createSymbolicLinkOrSkip(linkedAppCache, linkedTarget.getParent());

            LegacyDiagramCacheCleanup.cleanup(linkedAppCache.resolve("plantuml"), null);

            assertThat(Files.readString(linkedTargetSentinel, StandardCharsets.UTF_8))
                .isEqualTo("keep");
            assertThat(Files.isSymbolicLink(linkedAppCache)).isTrue();
        } finally {
            deleteTestTree(root);
        }
    }

    @Test
    void ignoresUnexpectedOrUnavailableCleanupRoots() throws Exception {
        Path root = Files.createTempDirectory("kortty-legacy-plantuml-failure-");
        try {
            Path unexpected = Files.createDirectories(root.resolve("do-not-delete"));
            Path sentinel = Files.writeString(unexpected.resolve("keep.txt"), "keep",
                StandardCharsets.UTF_8);
            Path nonDirectoryTempRoot = Files.writeString(root.resolve("not-a-directory"), "keep",
                StandardCharsets.UTF_8);

            LegacyDiagramCacheCleanup.cleanup(unexpected, nonDirectoryTempRoot);
            LegacyDiagramCacheCleanup.cleanup(
                root.resolve("missing/kortty/plantuml"), root.resolve("missing-temp"));

            assertThat(Files.readString(sentinel, StandardCharsets.UTF_8)).isEqualTo("keep");
            assertThat(Files.readString(nonDirectoryTempRoot, StandardCharsets.UTF_8))
                .isEqualTo("keep");
        } finally {
            deleteTestTree(root);
        }
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
