package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Renders PlantUML locally. No remote PlantUML server is used.
 *
 * <p>Graphviz {@code dot} is <em>optional</em>: the activity and sequence diagrams korTTY
 * generates from code render with PlantUML's own built-in layout engine and need no Graphviz at
 * all. Only class/state/component diagrams require {@code dot}. So a missing {@code dot} must not
 * block rendering — when it is present we merely pass its location to PlantUML via
 * {@code GRAPHVIZ_DOT} so PlantUML need not rediscover it.
 */
public class PlantUmlRenderService {

    private static final Logger logger = LoggerFactory.getLogger(PlantUmlRenderService.class);
    public static final String PLANTUML_VERSION = "1.2026.2";
    static final String PLANTUML_SHA256 = "7a3eacbccd08311f14b107b1254e179adc1f81fa8bd52bbaf563a37f00ea026f";
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration RENDER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STALE_TEMP_DIRECTORY_AGE = Duration.ofHours(24);
    private static final String TEMP_DIRECTORY_PREFIX = "kortty-snippet-plantuml-";
    private static final URI PLANTUML_BASE_URI = URI.create(
        "https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/" + PLANTUML_VERSION + "/");
    private static final Object CACHE_LOCK = new Object();

    private final HttpClient httpClient;
    private final Path cacheDir;
    private final Supplier<String> dotResolver;
    private final URI plantUmlBaseUri;
    private final String expectedSha256;

    public record RenderResult(boolean success, Path imagePath, String message) {
    }

    public record SyntaxCheckResult(boolean available, boolean valid, String message) {
    }

    public PlantUmlRenderService() {
        this(HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build(), defaultCacheDir());
    }

    PlantUmlRenderService(HttpClient httpClient, Path cacheDir) {
        this(httpClient, cacheDir, PlantUmlRenderService::resolveDotExecutable);
    }

    PlantUmlRenderService(HttpClient httpClient, Path cacheDir, Supplier<String> dotResolver) {
        this(httpClient, cacheDir, dotResolver, PLANTUML_BASE_URI, PLANTUML_SHA256);
    }

    PlantUmlRenderService(
        HttpClient httpClient,
        Path cacheDir,
        Supplier<String> dotResolver,
        URI plantUmlBaseUri,
        String expectedSha256) {

        this.httpClient = httpClient;
        this.cacheDir = cacheDir;
        this.dotResolver = dotResolver;
        this.plantUmlBaseUri = plantUmlBaseUri;
        this.expectedSha256 = expectedSha256;
    }

    /**
     * Test seam: a renderer that behaves as if Graphviz {@code dot} is not installed, so tests can
     * reproduce a machine without Graphviz without touching the host's real {@code dot}.
     */
    static PlantUmlRenderService withoutDot() {
        return new PlantUmlRenderService(
            HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build(),
            defaultCacheDir(),
            () -> null);
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
        PlantUmlRuntime runtime = resolvePlantUmlRuntime();
        if (runtime == null) {
            return new RenderResult(false, null, "Java is required to render PlantUML diagrams.");
        }
        // Graphviz 'dot' is optional (see class javadoc): a missing dot must not block rendering,
        // because the activity/sequence diagrams korTTY generates render without it. When dot IS
        // present we pass its resolved path along, since a desktop-launched JVM often finds dot via
        // Homebrew/common dirs that PlantUML's own subprocess search would otherwise miss.
        String dotExecutable = dotResolver.get();
        try {
            Path jar = ensurePlantUmlJar();
            Path workDir = Files.createTempDirectory("kortty-snippet-plantuml-");
            Path sourceFile = workDir.resolve("snippet-diagram.puml");
            Files.writeString(sourceFile, plantUmlSource, StandardCharsets.UTF_8);
            ProcessBuilder builder =
                new ProcessBuilder(runtime.command(jar, "-t" + format, sourceFile.toString()))
                    .redirectErrorStream(true);
            if (dotExecutable != null) {
                builder.environment().put("GRAPHVIZ_DOT", dotExecutable);
            }
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
        PlantUmlRuntime runtime = resolvePlantUmlRuntime();
        if (runtime == null) {
            return new SyntaxCheckResult(false, false, "Java is required to check PlantUML syntax.");
        }
        Path workDir = null;
        try {
            Path jar = ensurePlantUmlJar();
            workDir = Files.createTempDirectory("kortty-snippet-plantuml-check-");
            Path sourceFile = workDir.resolve("snippet-diagram.puml");
            Files.writeString(sourceFile, plantUmlSource, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(runtime.command(jar, "--check-syntax", sourceFile.toString()))
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

    Path ensurePlantUmlJar() throws Exception {
        synchronized (CACHE_LOCK) {
            Files.createDirectories(cacheDir);
            cleanupStaleTempDirectories(systemTempDirectory(), Instant.now());

            Path jar = cacheDir.resolve("plantuml-" + PLANTUML_VERSION + ".jar");
            if (Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
                && !expectedSha256.equalsIgnoreCase(sha256(jar))) {

                Files.delete(jar);
            }
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(jar);
                downloadVerifiedJar(jar);
            }
            if (!expectedSha256.equalsIgnoreCase(sha256(jar))) {
                Files.deleteIfExists(jar);
                throw new IOException("PlantUML checksum mismatch.");
            }

            cleanupLegacyCacheFiles(jar);
            return jar;
        }
    }

    private void downloadVerifiedJar(Path jar) throws Exception {
        String fileName = jar.getFileName().toString();
        HttpRequest request = HttpRequest.newBuilder(plantUmlBaseUri.resolve(fileName))
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream responseBody = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(
                    "Download failed for " + request.uri() + " with HTTP " + response.statusCode());
            }

            Path partial = Files.createTempFile(cacheDir, fileName + ".", ".part");
            try {
                MessageDigest digest = sha256Digest();
                try (DigestInputStream input = new DigestInputStream(responseBody, digest);
                     OutputStream output = Files.newOutputStream(partial)) {
                    input.transferTo(output);
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!expectedSha256.equalsIgnoreCase(actual)) {
                    throw new IOException("PlantUML checksum mismatch.");
                }
                moveAtomically(partial, jar);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream fileInput = Files.newInputStream(file);
             DigestInputStream input = new DigestInputStream(fileInput, digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every supported Java runtime must provide SHA-256", e);
        }
    }

    static boolean isVerifiedPlantUmlJar(Path jar) throws IOException {
        return Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
            && jar.getFileName().toString().equals("plantuml-" + PLANTUML_VERSION + ".jar")
            && PLANTUML_SHA256.equalsIgnoreCase(sha256(jar));
    }

    private void cleanupLegacyCacheFiles(Path currentJar) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir, "plantuml-*")) {
            for (Path candidate : files) {
                String name = candidate.getFileName().toString();
                boolean obsoleteJar = name.endsWith(".jar") && !candidate.equals(currentJar);
                boolean obsoleteSha1 = name.endsWith(".jar.sha1");
                if ((obsoleteJar || obsoleteSha1)
                    && !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {

                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        logger.debug("Could not remove obsolete PlantUML cache file {}", candidate, e);
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect PlantUML cache directory {}", cacheDir, e);
        }
    }

    static void cleanupStaleTempDirectories(Path tempRoot, Instant now) {
        if (!Files.isDirectory(tempRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant cutoff = now.minus(STALE_TEMP_DIRECTORY_AGE);
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(
            tempRoot, TEMP_DIRECTORY_PREFIX + "*")) {

            for (Path directory : directories) {
                try {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {

                        continue;
                    }
                    deleteDirectoryWithoutFollowingLinks(directory);
                } catch (IOException e) {
                    logger.debug("Could not remove stale PlantUML temp directory {}", directory, e);
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect PlantUML temp directory {}", tempRoot, e);
        }
    }

    private static void deleteDirectoryWithoutFollowingLinks(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(visitedDirectory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Resolves an isolated PlantUML process. Development/runtime images with {@code bin/java} use
     * the ordinary {@code java -jar} invocation. A jpackage image stripped of native commands
     * re-enters its current native launcher in the private worker mode; a system Java found in
     * PATH/common install directories remains the final fallback.
     */
    private static PlantUmlRuntime resolvePlantUmlRuntime() {
        String javaHome = System.getProperty("java.home");
        Path bundledJava = null;
        if (javaHome != null && !javaHome.isBlank()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            bundledJava = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        }
        Optional<String> currentCommand = ProcessHandle.current().info().command();
        Optional<String> systemJava = AiCliProviderRegistry.findExecutable("java");
        return resolvePlantUmlRuntime(bundledJava, currentCommand, systemJava);
    }

    static PlantUmlRuntime resolvePlantUmlRuntime(
        Path bundledJava,
        Optional<String> currentCommand,
        Optional<String> systemJava) {

        if (bundledJava != null && Files.isExecutable(bundledJava)) {
            return new PlantUmlRuntime(bundledJava.toString(), false);
        }
        if (currentCommand.isPresent()) {
            try {
                Path launcher = Path.of(currentCommand.get());
                if (Files.isExecutable(launcher) && !isJavaExecutable(launcher)) {
                    return new PlantUmlRuntime(launcher.toString(), true);
                }
            } catch (RuntimeException ignored) {
                // A malformed/unrepresentable ProcessHandle command must not suppress system Java.
            }
        }
        return systemJava.map(executable -> new PlantUmlRuntime(executable, false)).orElse(null);
    }

    private static boolean isJavaExecutable(Path executable) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe");
    }

    record PlantUmlRuntime(String executable, boolean nativeLauncher) {
        List<String> command(Path jar, String... plantUmlArguments) {
            List<String> command = new ArrayList<>(plantUmlArguments.length + 3);
            command.add(executable);
            command.add(nativeLauncher ? InternalPlantUmlWorker.ARGUMENT : "-jar");
            command.add(jar.toString());
            command.addAll(List.of(plantUmlArguments));
            return List.copyOf(command);
        }
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

    private static Path systemTempDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", "."));
    }
}
