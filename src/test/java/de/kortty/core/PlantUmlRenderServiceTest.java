package de.kortty.core;

import com.sun.net.httpserver.HttpServer;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression coverage for the "Graphviz dot is required to render PlantUML diagrams" failure
 * reported from the packaged app: {@code dot} was actually installed (via Homebrew), but korTTY's
 * detection and the PlantUML subprocess it launches only saw a minimal PATH — the kind a macOS GUI
 * app inherits from launchd, which omits {@code /opt/homebrew/bin}. {@link PlantUmlRenderService}
 * now resolves {@code java}/{@code dot} the same way {@link AiCliProviderRegistry} resolves AI CLIs
 * (PATH plus common install directories) and passes the resolved dot path to the PlantUML
 * subprocess via {@code GRAPHVIZ_DOT}, so it does not need to rediscover it on its own.
 *
 * <p>Self-skips when {@code dot} is not installed on the test machine at all, or when rendering
 * fails for a network reason (the PlantUML jar/checksum download from Maven Central), mirroring how
 * {@link Mosh4jReleaseIntegrationTest} self-skips when its prerequisites are unavailable.
 */
public class PlantUmlRenderServiceTest {

    private static final String VALID_DIAGRAM = "@startuml\nstart\n:Run;\nstop\n@enduml";

    @Test
    void rendersSvgWhenGraphvizIsInstalledOutsideTheMinimalGuiPath() {
        if (AiCliProviderRegistry.findExecutable("dot").isEmpty()) {
            throw new SkipException("Graphviz 'dot' is not installed on this machine");
        }

        PlantUmlRenderService.RenderResult result = new PlantUmlRenderService().renderSvg(VALID_DIAGRAM);

        if (!result.success()) {
            skipIfNetworkUnavailable(result.message());
        }
        // The exact defect this guards: PlantUML's own error when it can't find dot on its side.
        assertThat(result.message()).doesNotContain("GRAPHVIZ_DOT");
        assertThat(result.success()).isTrue();
    }

    @Test
    void checkSyntaxSucceedsWhenGraphvizIsInstalledOutsideTheMinimalGuiPath() {
        if (AiCliProviderRegistry.findExecutable("dot").isEmpty()) {
            throw new SkipException("Graphviz 'dot' is not installed on this machine");
        }

        PlantUmlRenderService.SyntaxCheckResult result = new PlantUmlRenderService().checkSyntax(VALID_DIAGRAM);

        if (!result.valid()) {
            skipIfNetworkUnavailable(result.message());
        }
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rendersActivityDiagramWhenGraphvizIsNotInstalled() {
        // The reported failure: on a Mac without Graphviz, korTTY refused to render with
        // "Graphviz dot is required to render PlantUML diagrams." — even though the activity
        // diagrams it generates from code use PlantUML's built-in engine and need no Graphviz.
        // withoutDot() reproduces "no dot installed" without touching the host's real dot.
        PlantUmlRenderService.RenderResult result = PlantUmlRenderService.withoutDot().renderSvg(VALID_DIAGRAM);

        if (!result.success()) {
            skipIfNetworkUnavailable(result.message());
        }
        assertThat(result.message()).doesNotContain("Graphviz dot is required");
        assertThat(result.success()).isTrue();
    }

    @Test
    void javaHomeExecutableIsUsedInsteadOfRequiringJavaOnPath() {
        // resolveJavaExecutable() is private, but its effect is directly observable: rendering must
        // not report "Java is required" while this very test is running inside a JVM.
        PlantUmlRenderService.RenderResult result = new PlantUmlRenderService().renderSvg(VALID_DIAGRAM);
        assertThat(result.message()).isNotEqualTo("Java is required to render PlantUML diagrams.");
    }

    @Test
    void bakedBackgroundColorAppearsInTheRenderedActivitySvg() throws Exception {
        // The AI Code Analysis diagram viewer (SnippetDiagramView) bakes the chosen background colour into
        // the PlantUML source (skinparam backgroundColor) so the rendered SVG page itself is coloured — not
        // only the HTML padding around it. This proves the colour reaches the SVG the viewer displays/exports.
        String colored = SnippetDiagramSupport.applyBackgroundColor(VALID_DIAGRAM, "#123456");
        PlantUmlRenderService.RenderResult result = PlantUmlRenderService.withoutDot().renderSvg(colored);

        if (!result.success()) {
            skipIfNetworkUnavailable(result.message());
        }
        assertThat(result.success()).isTrue();
        String svg = java.nio.file.Files.readString(result.imagePath());
        assertThat(svg.toLowerCase(java.util.Locale.ROOT)).contains("123456");
    }

    @Test
    void darkModeRendersDarkCanvasInActivitySvg() throws Exception {
        // The diagram viewers' "Dark mode" bakes a dark canvas (and light connectors) into the source via
        // SnippetDiagramSupport.applyDarkMode; this proves the dark background reaches the rendered SVG.
        String dark = SnippetDiagramSupport.applyDarkMode(VALID_DIAGRAM);
        PlantUmlRenderService.RenderResult result = PlantUmlRenderService.withoutDot().renderSvg(dark);

        if (!result.success()) {
            skipIfNetworkUnavailable(result.message());
        }
        assertThat(result.success()).isTrue();
        String svg = java.nio.file.Files.readString(result.imagePath()).toLowerCase(java.util.Locale.ROOT);
        assertThat(svg).contains("1e1e1e");
    }

    @Test
    void pinsTheExpectedPlantUmlSha256() {
        assertThat(PlantUmlRenderService.PLANTUML_SHA256)
            .isEqualTo("7a3eacbccd08311f14b107b1254e179adc1f81fa8bd52bbaf563a37f00ea026f");
    }

    @Test
    void downloadsOnlyTheJarAndCleansObsoleteCacheFilesAfterVerification() throws Exception {
        Path root = Files.createTempDirectory("kortty-plantuml-cache-test-");
        HttpServer server = null;
        try {
            Path cache = Files.createDirectories(root.resolve("cache"));
            byte[] payload = "verified-plantuml-test-jar".getBytes(StandardCharsets.UTF_8);
            Path payloadFile = root.resolve("payload.bin");
            Files.write(payloadFile, payload);
            String expectedSha256 = PlantUmlRenderService.sha256(payloadFile);

            Path currentJar = cache.resolve("plantuml-" + PlantUmlRenderService.PLANTUML_VERSION + ".jar");
            Path currentSha1 = cache.resolve(currentJar.getFileName() + ".sha1");
            Path oldJar = cache.resolve("plantuml-1.2025.0.jar");
            Path oldSha1 = cache.resolve("plantuml-1.2025.0.jar.sha1");
            Path unrelated = cache.resolve("notes.txt");
            Files.writeString(currentJar, "corrupt", StandardCharsets.UTF_8);
            Files.writeString(currentSha1, "obsolete", StandardCharsets.UTF_8);
            Files.writeString(oldJar, "obsolete", StandardCharsets.UTF_8);
            Files.writeString(oldSha1, "obsolete", StandardCharsets.UTF_8);
            Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);

            AtomicInteger requests = new AtomicInteger();
            server = startJarServer(payload, requests);
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            PlantUmlRenderService service = new PlantUmlRenderService(
                HttpClient.newHttpClient(), cache, () -> null, baseUri, expectedSha256);

            assertThat(service.ensurePlantUmlJar()).isEqualTo(currentJar);
            assertThat(Files.readAllBytes(currentJar)).isEqualTo(payload);
            assertThat(requests.get()).isEqualTo(1);
            assertThat(Files.exists(currentSha1)).isFalse();
            assertThat(Files.exists(oldJar)).isFalse();
            assertThat(Files.exists(oldSha1)).isFalse();
            assertThat(Files.exists(unrelated)).isTrue();

            // A verified cached jar avoids all network access, including the former SHA-1 request.
            assertThat(service.ensurePlantUmlJar()).isEqualTo(currentJar);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            if (server != null) {
                server.stop(0);
            }
            deleteRecursively(root);
        }
    }

    @Test
    void rejectsBadDownloadWithoutLeavingJarOrPartialFile() throws Exception {
        Path root = Files.createTempDirectory("kortty-plantuml-bad-download-test-");
        HttpServer server = null;
        try {
            Path cache = Files.createDirectories(root.resolve("cache"));
            server = startJarServer("tampered".getBytes(StandardCharsets.UTF_8), new AtomicInteger());
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            PlantUmlRenderService service = new PlantUmlRenderService(
                HttpClient.newHttpClient(), cache, () -> null, baseUri, "0".repeat(64));

            Exception failure = null;
            try {
                service.ensurePlantUmlJar();
            } catch (Exception e) {
                failure = e;
            }

            assertThat(failure).isInstanceOf(java.io.IOException.class);
            assertThat(failure).hasMessageThat().contains("checksum mismatch");
            assertThat(Files.exists(cache.resolve(
                "plantuml-" + PlantUmlRenderService.PLANTUML_VERSION + ".jar"))).isFalse();
            try (Stream<Path> files = Files.list(cache)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList()).isEmpty();
            }
        } finally {
            if (server != null) {
                server.stop(0);
            }
            deleteRecursively(root);
        }
    }

    @Test
    void removesOnlyPlantUmlTempDirectoriesOlderThanTwentyFourHours() throws Exception {
        Path root = Files.createTempDirectory("kortty-plantuml-temp-maintenance-");
        try {
            Instant now = Instant.parse("2026-07-11T12:00:00Z");
            Path stale = Files.createDirectories(root.resolve("kortty-snippet-plantuml-stale/nested"))
                .getParent();
            Files.writeString(stale.resolve("nested/source.puml"), VALID_DIAGRAM, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(stale, FileTime.from(now.minus(Duration.ofHours(25))));

            Path exactlyTwentyFourHoursOld = Files.createDirectory(
                root.resolve("kortty-snippet-plantuml-boundary"));
            Files.setLastModifiedTime(
                exactlyTwentyFourHoursOld, FileTime.from(now.minus(Duration.ofHours(24))));
            Path fresh = Files.createDirectory(root.resolve("kortty-snippet-plantuml-fresh"));
            Files.setLastModifiedTime(fresh, FileTime.from(now.minus(Duration.ofHours(1))));
            Path unrelated = Files.createDirectory(root.resolve("unrelated-stale-directory"));
            Files.setLastModifiedTime(unrelated, FileTime.from(now.minus(Duration.ofDays(5))));

            PlantUmlRenderService.cleanupStaleTempDirectories(root, now);

            assertThat(Files.exists(stale)).isFalse();
            assertThat(Files.exists(exactlyTwentyFourHoursOld)).isTrue();
            assertThat(Files.exists(fresh)).isTrue();
            assertThat(Files.exists(unrelated)).isTrue();
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void usesNativeLauncherWorkerBeforeSystemJavaWhenBundledJavaIsStripped() throws Exception {
        Path root = Files.createTempDirectory("kortty-plantuml-runtime-test-");
        try {
            Path nativeLauncher = Files.writeString(
                root.resolve(System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "korTTY.exe"
                    : "korTTY"),
                "launcher",
                StandardCharsets.UTF_8);
            nativeLauncher.toFile().setExecutable(true, true);

            PlantUmlRenderService.PlantUmlRuntime runtime = PlantUmlRenderService.resolvePlantUmlRuntime(
                root.resolve("missing-java"), Optional.of(nativeLauncher.toString()), Optional.of("system-java"));

            assertThat(runtime).isNotNull();
            assertThat(runtime.nativeLauncher()).isTrue();
            assertThat(runtime.command(Path.of("plantuml.jar"), "-tsvg", "source.puml"))
                .containsExactly(
                    nativeLauncher.toString(),
                    InternalPlantUmlWorker.ARGUMENT,
                    "plantuml.jar",
                    "-tsvg",
                    "source.puml")
                .inOrder();
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void retainsSystemJavaFallbackWhenNoBundledOrNativeLauncherExists() {
        PlantUmlRenderService.PlantUmlRuntime runtime = PlantUmlRenderService.resolvePlantUmlRuntime(
            Path.of("missing-java"), Optional.empty(), Optional.of("system-java"));

        assertThat(runtime).isNotNull();
        assertThat(runtime.nativeLauncher()).isFalse();
        assertThat(runtime.command(Path.of("plantuml.jar"), "--check-syntax", "source.puml"))
            .containsExactly("system-java", "-jar", "plantuml.jar", "--check-syntax", "source.puml")
            .inOrder();
    }

    @Test
    void internalWorkerRejectsAnythingExceptThePinnedJar() throws Exception {
        Path root = Files.createTempDirectory("kortty-plantuml-worker-test-");
        try {
            Path unverifiedJar = Files.writeString(
                root.resolve("plantuml-" + PlantUmlRenderService.PLANTUML_VERSION + ".jar"),
                "not PlantUML",
                StandardCharsets.UTF_8);

            assertThat(InternalPlantUmlWorker.isInvocation(new String[]{InternalPlantUmlWorker.ARGUMENT})).isTrue();
            assertThat(InternalPlantUmlWorker.isInvocation(new String[]{"--other-mode"})).isFalse();
            assertThat(InternalPlantUmlWorker.run(new String[]{
                InternalPlantUmlWorker.ARGUMENT, unverifiedJar.toString(), "--check-syntax", "source.puml"}))
                .isEqualTo(2);
        } finally {
            deleteRecursively(root);
        }
    }

    private static HttpServer startJarServer(byte[] payload, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plantuml-" + PlantUmlRenderService.PLANTUML_VERSION + ".jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        return server;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void skipIfNetworkUnavailable(String message) {
        String lower = message != null ? message.toLowerCase(java.util.Locale.ROOT) : "";
        if (lower.contains("download failed") || lower.contains("unknownhost")
            || lower.contains("timed out") || lower.contains("connect")) {
            throw new SkipException("PlantUML jar/checksum download unavailable in this environment: " + message);
        }
    }
}
