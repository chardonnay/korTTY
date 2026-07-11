package de.kortty.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled Monaco WebView resources to a temp directory and hands out {@code file:} URLs.
 *
 * <p>Why this exists: in the packaged app the resources live inside the application jar, so
 * {@code getResource("/monaco/monaco-editor.html")} yields a {@code jar:} URL. JavaFX WebView loads
 * that page fine, but the page's own CSP ({@code script-src 'self'} / {@code style-src 'self'}) then
 * <em>blocks</em> its relative {@code monaco-host.js} / {@code monaco-host.css} siblings, because for a
 * {@code jar:}-origin document {@code 'self'} resolves to an opaque origin that does not authorize a
 * {@code jar:} sub-resource. The block fires {@code securitypolicyviolation} (not {@code window.onerror}),
 * so {@code window.korttyMonaco} is never defined, {@code korttyStartupErrors} stays {@code []}, and the
 * editor shows an empty pane — no caret, no typing, no paste. This only happens in the packaged build;
 * {@code ./gradlew run} loads from a {@code file:} classpath and works. Reproduced on the notarized
 * WebKit; adding {@code jar:} to the CSP does NOT help (opaque-origin), removing the CSP does, and a
 * {@code file:} load does. So we serve the page from {@code file:}: then {@code 'self'} (and the
 * already-present {@code file:} CSP source) authorize the siblings.
 *
 * <p>Extraction happens once per process, behind a double-checked lock, and is shared by every Monaco
 * editor/diff pane. The temp dir is registered for {@code deleteOnExit}; korTTY's hard-halt quit path
 * may skip that, leaving one {@code kortty-monaco-*} dir behind — cosmetic, and reaped by the OS.
 */
final class MonacoResourceBundle {

    private static final Logger logger = LoggerFactory.getLogger(MonacoResourceBundle.class);

    // Both HTML pages share one JS/CSS pair. The Monaco web workers are inline blobs baked into
    // the host bundle (common.js imports WORKER_SOURCES and does new Worker(blob:)), so the large
    // *.worker.js files are never fetched as resources and must NOT be extracted here.
    private static final String[] FILES = {
        "monaco-editor.html", "monaco-host.js", "monaco-host.css",
        "monaco-diff-editor.html"
    };

    private static final Object LOCK = new Object();
    private static volatile Path dir;

    private MonacoResourceBundle() {
    }

    /** {@code file:} URL for the editor page, or {@code null} if extraction failed. */
    static String editorHtmlUrl() {
        return urlFor("monaco-editor.html");
    }

    /** {@code file:} URL for the diff-editor page, or {@code null} if extraction failed. */
    static String diffEditorHtmlUrl() {
        return urlFor("monaco-diff-editor.html");
    }

    private static String urlFor(String htmlFile) {
        Path base = ensureExtracted();
        if (base == null) {
            return null;
        }
        try {
            return base.resolve(htmlFile).toUri().toURL().toExternalForm();
        } catch (IOException e) {
            logger.error("Could not build file: URL for {}", htmlFile, e);
            return null;
        }
    }

    private static Path ensureExtracted() {
        Path current = dir;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (dir != null) {
                return dir;
            }
            try {
                Path target = Files.createTempDirectory("kortty-monaco-");
                target.toFile().deleteOnExit();
                for (String name : FILES) {
                    try (InputStream in = MonacoResourceBundle.class.getResourceAsStream("/monaco/" + name)) {
                        if (in == null) {
                            throw new IOException("Missing bundled Monaco resource /monaco/" + name);
                        }
                        Path out = target.resolve(name);
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        out.toFile().deleteOnExit();
                    }
                }
                // Publish only after ALL files are on disk: a pane must never engine.load() a page whose
                // siblings aren't written yet — that race would reproduce the exact empty-pane symptom.
                dir = target;
                logger.info("Extracted Monaco WebView resources to {}", target);
                return dir;
            } catch (IOException e) {
                logger.error("Could not extract bundled Monaco resources", e);
                return null;
            }
        }
    }
}
