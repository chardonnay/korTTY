package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

/** Removes files left behind by the retired PlantUML renderer. */
public final class LegacyDiagramCacheCleanup {

    private static final Logger logger = LoggerFactory.getLogger(LegacyDiagramCacheCleanup.class);
    private static final String APP_CACHE_DIRECTORY = "kortty";
    private static final String PLANTUML_CACHE_DIRECTORY = "plantuml";
    static final String TEMP_DIRECTORY_PREFIX = "kortty-snippet-plantuml-";
    private static final Duration STALE_TEMP_AGE = Duration.ofHours(24);

    private LegacyDiagramCacheCleanup() {
    }

    /**
     * Performs the one-way PlantUML cleanup using the current process environment.
     * Every failure is logged and ignored so migration leftovers cannot block startup.
     */
    public static void cleanupAtStartup() {
        Path cacheDirectory = null;
        try {
            cacheDirectory = resolveCacheDirectory(
                System.getenv("XDG_CACHE_HOME"), System.getProperty("user.home"));
        } catch (InvalidPathException | SecurityException e) {
            logger.warn("Could not resolve the legacy PlantUML cache directory", e);
        }

        Path tempDirectory = null;
        try {
            String configuredTempDirectory = System.getProperty("java.io.tmpdir");
            if (configuredTempDirectory != null && !configuredTempDirectory.isBlank()) {
                tempDirectory = Path.of(configuredTempDirectory);
            }
        } catch (InvalidPathException | SecurityException e) {
            logger.warn("Could not resolve the system temporary directory for PlantUML cleanup", e);
        }

        cleanup(cacheDirectory, tempDirectory);
    }

    static Path resolveCacheDirectory(String xdgCacheHome, String userHome) {
        Path cacheBase = null;
        if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
            Path configuredBase = Path.of(xdgCacheHome);
            if (configuredBase.isAbsolute()) {
                cacheBase = configuredBase;
            } else {
                logger.warn("Ignoring relative XDG_CACHE_HOME during PlantUML cleanup: {}", xdgCacheHome);
            }
        }

        if (cacheBase == null && userHome != null && !userHome.isBlank()) {
            cacheBase = Path.of(userHome).resolve(".cache");
        }
        return cacheBase == null
            ? null
            : cacheBase.resolve(APP_CACHE_DIRECTORY).resolve(PLANTUML_CACHE_DIRECTORY)
                .toAbsolutePath().normalize();
    }

    static void cleanup(Path cacheDirectory, Path tempDirectory) {
        try {
            cleanupCacheDirectory(cacheDirectory);
        } catch (RuntimeException e) {
            logger.warn("Unexpected failure while removing the legacy PlantUML cache", e);
        }
        try {
            cleanupTempDirectories(tempDirectory);
        } catch (RuntimeException e) {
            logger.warn("Unexpected failure while removing legacy PlantUML temporary paths", e);
        }
    }

    private static void cleanupCacheDirectory(Path cacheDirectory) {
        if (cacheDirectory == null) {
            return;
        }

        Path normalized = cacheDirectory.toAbsolutePath().normalize();
        if (!hasExpectedCacheSuffix(normalized)) {
            logger.warn("Refusing to remove unexpected legacy PlantUML cache path: {}", normalized);
            return;
        }

        // Never traverse through the app-owned `kortty` component if somebody replaced it with a
        // link. The configured cache base itself is the trusted boundary supplied by the OS/user.
        Path appCacheDirectory = normalized.getParent();
        if (appCacheDirectory != null && Files.isSymbolicLink(appCacheDirectory)) {
            logger.warn("Refusing to follow symbolic link while removing PlantUML cache: {}",
                appCacheDirectory);
            return;
        }

        deleteTreeWithoutFollowingLinks(normalized, "legacy PlantUML cache");
    }

    private static boolean hasExpectedCacheSuffix(Path cacheDirectory) {
        Path fileName = cacheDirectory.getFileName();
        Path parent = cacheDirectory.getParent();
        return fileName != null
            && PLANTUML_CACHE_DIRECTORY.equals(fileName.toString())
            && parent != null
            && parent.getFileName() != null
            && APP_CACHE_DIRECTORY.equals(parent.getFileName().toString());
    }

    private static void cleanupTempDirectories(Path tempDirectory) {
        if (tempDirectory == null) {
            return;
        }

        Path normalized = tempDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            logger.warn("Refusing to follow symbolic link while inspecting PlantUML temp files: {}",
                normalized);
            return;
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            logger.warn("PlantUML temp cleanup root is not a directory: {}", normalized);
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(
            normalized, TEMP_DIRECTORY_PREFIX + "*")) {

            Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);
            for (Path entry : entries) {
                if (!Files.isSymbolicLink(entry)) {
                    try {
                        Instant modified = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant();
                        if (!modified.isBefore(cutoff)) {
                            continue;
                        }
                    } catch (IOException | SecurityException e) {
                        logger.warn("Could not inspect legacy diagram temporary path {}", entry, e);
                        continue;
                    }
                }
                deleteTreeWithoutFollowingLinks(entry, "legacy PlantUML temporary path");
            }
        } catch (IOException | DirectoryIteratorException | SecurityException e) {
            logger.warn("Could not inspect legacy PlantUML temporary paths in {}", normalized, e);
        }
    }

    private static void deleteTreeWithoutFollowingLinks(Path root, String description) {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    deleteEntry(file, description);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    logger.warn("Could not access {} {}", description, file, failure);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    if (failure != null) {
                        logger.warn("Could not fully inspect {} {}", description, directory, failure);
                    }
                    deleteEntry(directory, description);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                logger.debug("Removed {}: {}", description, root);
            }
        } catch (IOException | SecurityException e) {
            logger.warn("Could not remove {} {}", description, root, e);
        }
    }

    private static void deleteEntry(Path entry, String description) {
        try {
            Files.deleteIfExists(entry);
        } catch (IOException | SecurityException e) {
            logger.warn("Could not remove {} {}", description, entry, e);
        }
    }
}
