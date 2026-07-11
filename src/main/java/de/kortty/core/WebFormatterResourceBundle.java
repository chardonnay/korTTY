package de.kortty.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Extracts the bundled browser formatter resources to one process-private directory.
 *
 * <p>The formatter page is deliberately loaded from a {@code file:} URL. Relative script loads
 * from a page inside the application JAR have an opaque origin in JavaFX WebView and can be
 * rejected by the page's content security policy. Publishing the directory only after every
 * resource has been copied also prevents a concurrent first request from observing a partial
 * formatter installation.</p>
 */
final class WebFormatterResourceBundle {

    static final String RESOURCE_ROOT = "/formatters-web/";
    static final String HOST_FILE = "formatter-host.html";
    static final String[] FILES = {
        HOST_FILE,
        "prettier-standalone.js",
        "prettier-plugin-babel.js",
        "prettier-plugin-estree.js",
        "prettier-plugin-typescript.js",
        "prettier-plugin-html.js",
        "prettier-plugin-postcss.js",
        "sql-formatter.js"
    };

    private static final Object LOCK = new Object();
    private static volatile Path extractedDirectory;

    private WebFormatterResourceBundle() {
    }

    static boolean isBundled() {
        for (String file : FILES) {
            if (resource(file) == null) {
                return false;
            }
        }
        return true;
    }

    static String hostUrl() throws IOException {
        return ensureExtracted().resolve(HOST_FILE).toUri().toURL().toExternalForm();
    }

    static Path ensureExtracted() throws IOException {
        Path current = extractedDirectory;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (extractedDirectory != null) {
                return extractedDirectory;
            }

            Path target = Files.createTempDirectory("kortty-formatters-web-");
            restrictToOwner(target);
            target.toFile().deleteOnExit();
            try {
                for (String file : FILES) {
                    URL source = resource(file);
                    if (source == null) {
                        throw new IOException("Missing bundled formatter resource " + RESOURCE_ROOT + file);
                    }
                    Path output = target.resolve(file);
                    try (InputStream input = source.openStream()) {
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                    output.toFile().deleteOnExit();
                }
                extractedDirectory = target;
                return target;
            } catch (IOException | RuntimeException e) {
                deletePartialDirectory(target);
                throw e;
            }
        }
    }

    private static URL resource(String file) {
        return WebFormatterResourceBundle.class.getResource(RESOURCE_ROOT + file);
    }

    private static void restrictToOwner(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX filesystems use the platform's temp-directory ACLs.
        }
    }

    private static void deletePartialDirectory(Path directory) {
        for (String file : FILES) {
            try {
                Files.deleteIfExists(directory.resolve(file));
            } catch (IOException ignored) {
                // Best-effort cleanup after a failed extraction.
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed extraction.
        }
    }
}
