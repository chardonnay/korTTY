package de.kortty.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extracts the bundled AI-chat MathJax library to a temp directory and writes
 * per-block render pages next to them, handing out {@code file:} URLs.
 *
 * <p>Serving from {@code file:} instead of the {@code jar:} classpath URL is required for the same
 * reason as {@link MonacoResourceBundle}: a {@code jar:}-origin page cannot load its sibling
 * scripts in the packaged app. Extraction happens once per process; the render pages are small
 * generated HTML files (one per rendered chat block) that reference the extracted libraries as
 * relative siblings.
 */
final class ChatRenderResourceBundle {

    private static final Logger logger = LoggerFactory.getLogger(ChatRenderResourceBundle.class);

    private static final String[] FILES = {"tex-svg.js"};

    private static final Object LOCK = new Object();
    private static final AtomicInteger PAGE_COUNTER = new AtomicInteger();
    private static volatile Path dir;

    private ChatRenderResourceBundle() {
    }

    /**
     * Writes a render page next to the extracted libraries and returns its {@code file:} URL, or
     * {@code null} when the bundled resources are unavailable.
     */
    static String writeRenderPage(String namePrefix, String html) {
        Path base = ensureExtracted();
        if (base == null) {
            return null;
        }
        try {
            Path page = base.resolve(namePrefix + "-" + PAGE_COUNTER.incrementAndGet() + ".html");
            Files.writeString(page, html, StandardCharsets.UTF_8);
            page.toFile().deleteOnExit();
            return page.toUri().toURL().toExternalForm();
        } catch (IOException e) {
            logger.error("Could not write chat render page {}", namePrefix, e);
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
                Path target = Files.createTempDirectory("kortty-chatrender-");
                target.toFile().deleteOnExit();
                for (String name : FILES) {
                    try (InputStream in = ChatRenderResourceBundle.class.getResourceAsStream("/chatrender/" + name)) {
                        if (in == null) {
                            throw new IOException("Missing bundled chat render resource /chatrender/" + name);
                        }
                        Path out = target.resolve(name);
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        out.toFile().deleteOnExit();
                    }
                }
                dir = target;
                logger.info("Extracted AI-chat render resources to {}", target);
                return dir;
            } catch (IOException e) {
                logger.error("Could not extract bundled chat render resources", e);
                return null;
            }
        }
    }
}
