package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Renders PlantUML locally. No remote PlantUML server is used.
 */
public class PlantUmlRenderService {

    private static final Logger logger = LoggerFactory.getLogger(PlantUmlRenderService.class);
    public static final String PLANTUML_VERSION = "1.2026.2";
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(30);
    private static final String PLANTUML_BASE_URL =
        "https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/" + PLANTUML_VERSION;

    private final HttpClient httpClient;
    private final Path cacheDir;

    public record RenderResult(boolean success, Path imagePath, String message) {
    }

    public record SyntaxCheckResult(boolean available, boolean valid, String message) {
    }

    public PlantUmlRenderService() {
        this(HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build(), defaultCacheDir());
    }

    PlantUmlRenderService(HttpClient httpClient, Path cacheDir) {
        this.httpClient = httpClient;
        this.cacheDir = cacheDir;
    }

    public RenderResult renderPng(String plantUmlSource) {
        return render(plantUmlSource, "png");
    }

    public RenderResult renderSvg(String plantUmlSource) {
        return render(plantUmlSource, "svg");
    }

    private RenderResult render(String plantUmlSource, String format) {
        if (!SnippetDiagramSupport.isRenderablePlantUml(plantUmlSource)) {
            return new RenderResult(false, null, "PlantUML source must start with @startuml and end with @enduml.");
        }
        String javaExecutable = resolveJavaExecutable();
        if (javaExecutable == null) {
            return new RenderResult(false, null, "Java is required to render PlantUML diagrams.");
        }
        String dotExecutable = resolveDotExecutable();
        if (dotExecutable == null) {
            return new RenderResult(false, null, "Graphviz dot is required to render PlantUML diagrams.");
        }
        try {
            Path jar = ensurePlantUmlJar();
            Path workDir = Files.createTempDirectory("kortty-snippet-plantuml-");
            Path sourceFile = workDir.resolve("snippet-diagram.puml");
            Files.writeString(sourceFile, plantUmlSource, StandardCharsets.UTF_8);
            ProcessBuilder builder =
                new ProcessBuilder(javaExecutable, "-jar", jar.toString(), "-t" + format, sourceFile.toString())
                    .redirectErrorStream(true);
            // PlantUML resolves Graphviz via the GRAPHVIZ_DOT env var (or falls back to its own PATH
            // search), and this JVM's own PATH search above already found dot via Homebrew/common
            // install dirs — those don't necessarily flow through to what PlantUML sees, so pass the
            // resolved path explicitly rather than relying on PlantUML to rediscover it.
            builder.environment().put("GRAPHVIZ_DOT", dotExecutable);
            Process process = builder.start();
            boolean finished = process.waitFor(RENDER_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RenderResult(false, null, "PlantUML rendering timed out.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return new RenderResult(false, null, plantUmlFailureMessage(output));
            }
            Path outputFile = workDir.resolve("snippet-diagram." + format);
            if (!Files.isRegularFile(outputFile)) {
                return new RenderResult(false, null, "PlantUML did not create a " + format.toUpperCase(Locale.ROOT) + " image.");
            }
            return new RenderResult(true, outputFile, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RenderResult(false, null, "PlantUML rendering was interrupted.");
        } catch (Exception e) {
            logger.warn("Could not render PlantUML snippet diagram", e);
            String message = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
            return new RenderResult(false, null, message);
        }
    }

    private static String plantUmlFailureMessage(String output) {
        String message = output != null ? output.trim() : "";
        if (message.isBlank()) {
            return "PlantUML rendering failed.";
        }
        message = message.replaceAll("(?m)^Error line \\d+ in file:.*(?:\\R|$)", "").trim();
        return message.isBlank() ? "Generated PlantUML contains syntax errors." : message;
    }

    public SyntaxCheckResult checkSyntax(String plantUmlSource) {
        if (!SnippetDiagramSupport.isRenderablePlantUml(plantUmlSource)) {
            return new SyntaxCheckResult(true, false, "PlantUML source must start with @startuml and end with @enduml.");
        }
        String javaExecutable = resolveJavaExecutable();
        if (javaExecutable == null) {
            return new SyntaxCheckResult(false, false, "Java is required to check PlantUML syntax.");
        }
        Path workDir = null;
        try {
            Path jar = ensurePlantUmlJar();
            workDir = Files.createTempDirectory("kortty-snippet-plantuml-check-");
            Path sourceFile = workDir.resolve("snippet-diagram.puml");
            Files.writeString(sourceFile, plantUmlSource, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(javaExecutable, "-jar", jar.toString(), "--check-syntax", sourceFile.toString())
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(RENDER_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SyntaxCheckResult(true, false, "PlantUML syntax check timed out.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0) {
                return new SyntaxCheckResult(true, true, "");
            }
            return new SyntaxCheckResult(
                true,
                false,
                output.isBlank() ? "Generated PlantUML contains syntax errors." : output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SyntaxCheckResult(false, false, "PlantUML syntax check was interrupted.");
        } catch (Exception e) {
            logger.warn("Could not check PlantUML snippet diagram syntax", e);
            String message = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
            return new SyntaxCheckResult(false, false, message);
        } finally {
            if (workDir != null) {
                try {
                    Files.deleteIfExists(workDir.resolve("snippet-diagram.puml"));
                    Files.deleteIfExists(workDir);
                } catch (IOException e) {
                    logger.debug("Could not clean PlantUML syntax-check temp directory {}", workDir, e);
                }
            }
        }
    }

    private Path ensurePlantUmlJar() throws Exception {
        Files.createDirectories(cacheDir);
        Path jar = cacheDir.resolve("plantuml-" + PLANTUML_VERSION + ".jar");
        Path shaFile = cacheDir.resolve("plantuml-" + PLANTUML_VERSION + ".jar.sha1");
        if (!Files.isRegularFile(jar)) {
            download(PLANTUML_BASE_URL + "/plantuml-" + PLANTUML_VERSION + ".jar", jar);
        }
        download(PLANTUML_BASE_URL + "/plantuml-" + PLANTUML_VERSION + ".jar.sha1", shaFile);
        String expected = Files.readString(shaFile, StandardCharsets.UTF_8).trim().split("\\s+")[0];
        String actual = sha1(jar);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(jar);
            throw new IOException("PlantUML checksum mismatch.");
        }
        return jar;
    }

    private void download(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed for " + url + " with HTTP " + response.statusCode());
        }
        Files.write(target, response.body());
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The Java executable to launch the PlantUML jar with: the currently-running JVM's own
     * {@code bin/java} (guaranteed to exist and be a compatible version, and present even in a
     * jpackage-bundled app with no system-wide "java" on PATH), falling back to a PATH/common-dirs
     * search for a system "java" if {@code java.home} is somehow unavailable. Returns {@code null}
     * when no usable Java can be found at all.
     */
    private static String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path candidate = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return AiCliProviderRegistry.findExecutable("java").orElse(null);
    }

    /**
     * The Graphviz {@code dot} executable. GUI apps launched from the desktop (especially macOS)
     * often inherit a minimal PATH that omits Homebrew, so a naive PATH-only lookup (or the shell
     * built-in {@code command -v}, which only sees that same minimal PATH) reports "not found" even
     * when dot is installed. Delegate to {@link AiCliProviderRegistry#findExecutable}, which also
     * scans common install directories.
     */
    private static String resolveDotExecutable() {
        return AiCliProviderRegistry.findExecutable("dot").orElse(null);
    }

    private static Path defaultCacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        String base = xdg != null && !xdg.isBlank()
            ? xdg
            : Path.of(System.getProperty("user.home", "."), ".cache").toString();
        return Path.of(base, "kortty", "plantuml");
    }
}
