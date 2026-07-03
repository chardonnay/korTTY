package de.kortty.core;

import org.testng.SkipException;
import org.testng.annotations.Test;

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
    void javaHomeExecutableIsUsedInsteadOfRequiringJavaOnPath() {
        // resolveJavaExecutable() is private, but its effect is directly observable: rendering must
        // not report "Java is required" while this very test is running inside a JVM.
        PlantUmlRenderService.RenderResult result = new PlantUmlRenderService().renderSvg(VALID_DIAGRAM);
        assertThat(result.message()).isNotEqualTo("Java is required to render PlantUML diagrams.");
    }

    private static void skipIfNetworkUnavailable(String message) {
        String lower = message != null ? message.toLowerCase(java.util.Locale.ROOT) : "";
        if (lower.contains("download failed") || lower.contains("unknownhost")
            || lower.contains("timed out") || lower.contains("connect")) {
            throw new SkipException("PlantUML jar/checksum download unavailable in this environment: " + message);
        }
    }
}
