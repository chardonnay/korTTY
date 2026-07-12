package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Extracts the pinned Mermaid browser bundle and its isolated host page for JavaFX WebView. */
final class MermaidResourceBundle {

    private static final Logger logger = LoggerFactory.getLogger(MermaidResourceBundle.class);
    private static final String[][] RESOURCES = {
        {"/mermaid/mermaid.min.js", "mermaid.min.js"},
        {"/mermaid/mermaid-host.html", "mermaid-host.html"},
        {"/mermaid/mermaid-host.js", "mermaid-host.js"}
    };
    private static final Object LOCK = new Object();
    private static volatile String hostUrl;

    private MermaidResourceBundle() {
    }

    static boolean isBundled() {
        for (String[] resource : RESOURCES) {
            try (InputStream input = MermaidResourceBundle.class.getResourceAsStream(resource[0])) {
                if (input == null) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    static String hostUrl() throws IOException {
        String current = hostUrl;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (hostUrl != null) {
                return hostUrl;
            }
            Path directory = Files.createTempDirectory("kortty-mermaid-");
            directory.toFile().deleteOnExit();
            try {
                for (String[] resource : RESOURCES) {
                    Path target = directory.resolve(resource[1]);
                    try (InputStream input = MermaidResourceBundle.class.getResourceAsStream(resource[0])) {
                        if (input == null) {
                            throw new IOException("Missing bundled Mermaid resource " + resource[0]);
                        }
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    target.toFile().deleteOnExit();
                }
                hostUrl = directory.resolve("mermaid-host.html").toUri().toURL().toExternalForm();
                return hostUrl;
            } catch (IOException e) {
                logger.error("Could not extract Mermaid render resources", e);
                throw e;
            }
        }
    }
}
