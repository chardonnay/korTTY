package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentDetectorTest {

    private static final String CODEX_OVERRIDE = """
        {
          "kind": "CODEX",
          "version": 1,
          "fallbackState": "UNKNOWN",
          "rules": [
            { "id": "first-tie", "state": "WORKING", "priority": 50, "contains": ["tie"] },
            { "id": "low", "state": "IDLE", "priority": 10, "contains": ["shared"] },
            { "id": "second-tie", "state": "DONE", "priority": 50, "contains": ["tie"] },
            { "id": "high", "state": "BLOCKED", "priority": 100, "anyLineRegex": ["shared"] }
          ]
        }
        """;

    private Path configDir;
    private AgentRuleRepository repository;
    private CodingAgentDetector detector;

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-coding-agents-detector");
        Path overrideDir = Files.createDirectories(configDir.resolve(AgentRuleRepository.USER_DIR_NAME));
        Files.writeString(overrideDir.resolve("codex.json"), CODEX_OVERRIDE, StandardCharsets.UTF_8);
        repository = new AgentRuleRepository(configDir);
        assertThat(repository.problems()).isEmpty();
        assertThat(repository.ruleSetFor(CodingAgentKind.CODEX).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.USER_OVERRIDE);
        detector = new CodingAgentDetector(repository);
    }

    @AfterMethod
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(configDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static ScreenSnapshot screen(String text) {
        return ScreenSnapshot.ofText(text, null, false);
    }

    @Test
    void highestPriorityWins() {
        DetectionResult result = detector.classify(CodingAgentKind.CODEX, screen("a\nshared line\nb"));

        assertThat(result).isEqualTo(new DetectionResult(CodingAgentKind.CODEX, CodingAgentState.BLOCKED, "high",
            "shared line"));
        assertThat(result.ruleMatched()).isTrue();
        assertThat(result.fallbackApplied()).isFalse();
    }

    @Test
    void tieKeepsFileOrder() {
        DetectionResult result = detector.classify(CodingAgentKind.CODEX, screen("tie"));

        assertThat(result.matchedRuleId()).isEqualTo("first-tie");
        assertThat(result.state()).isEqualTo(CodingAgentState.WORKING);
        assertThat(result.evidence()).isEqualTo("first-tie");
    }

    @Test
    void noMatchYieldsTheFallbackStateWithoutRuleOrEvidence() {
        DetectionResult result = detector.classify(CodingAgentKind.CODEX, screen("nothing relevant"));

        assertThat(result).isEqualTo(new DetectionResult(CodingAgentKind.CODEX, CodingAgentState.UNKNOWN, null, null));
        assertThat(result.fallbackApplied()).isTrue();
        assertThat(result.ruleMatched()).isFalse();
        assertThat(result.agentDetected()).isTrue();
    }

    @Test
    void bundledFallbackStateIsIdle() {
        DetectionResult result = detector.classify(CodingAgentKind.CLAUDE_CODE, screen("nothing relevant"));

        assertThat(result.state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(result.fallbackApplied()).isTrue();
    }

    @Test
    void unknownKindYieldsNone() {
        assertThat(detector.classify(CodingAgentKind.UNKNOWN, screen("shared"))).isSameInstanceAs(DetectionResult.NONE);
        assertThat(detector.classify(null, screen("shared"))).isSameInstanceAs(DetectionResult.NONE);
    }

    @Test
    void emptyRuleSetAppliesItsFallback() throws IOException {
        Path overrideDir = configDir.resolve(AgentRuleRepository.USER_DIR_NAME);
        Files.writeString(overrideDir.resolve("gemini-cli.json"),
            "{\"kind\":\"GEMINI_CLI\",\"version\":1,\"fallbackState\":\"WORKING\",\"rules\":[]}",
            StandardCharsets.UTF_8);
        repository.reload();

        DetectionResult result = detector.classify(CodingAgentKind.GEMINI_CLI, screen("anything"));
        assertThat(result).isEqualTo(new DetectionResult(CodingAgentKind.GEMINI_CLI, CodingAgentState.WORKING, null,
            null));
    }

    @Test
    void kindWithoutRuleSetYieldsUnknownState() throws ReflectiveOperationException {
        // The bundled files always supply every kind, so the "no rule set" branch is reached by emptying the
        // repository's published map the way a missing index resource would leave it.
        Field ruleSets = AgentRuleRepository.class.getDeclaredField("ruleSets");
        ruleSets.setAccessible(true);
        ruleSets.set(repository, Map.of());
        assertThat(repository.ruleSetFor(CodingAgentKind.GEMINI_CLI)).isEmpty();

        DetectionResult result = detector.classify(CodingAgentKind.GEMINI_CLI, screen("anything"));
        assertThat(result).isEqualTo(new DetectionResult(CodingAgentKind.GEMINI_CLI, CodingAgentState.UNKNOWN, null,
            null));
        assertThat(result.agentDetected()).isTrue();
        assertThat(result.fallbackApplied()).isTrue();
    }

    @Test
    void nullSnapshotIsTreatedAsEmptyScreen() {
        DetectionResult result = detector.classify(CodingAgentKind.CODEX, null);
        assertThat(result.state()).isEqualTo(CodingAgentState.UNKNOWN);
        assertThat(result.fallbackApplied()).isTrue();
    }

    @Test
    void explainDescribesMatchAndFallback() {
        DetectionResult match = detector.classify(CodingAgentKind.CODEX, screen("  shared line  "));
        assertThat(match.explain()).isEqualTo("Codex is BLOCKED (rule high: \"shared line\")");

        DetectionResult fallback = detector.classify(CodingAgentKind.CODEX, screen("quiet"));
        assertThat(fallback.explain()).isEqualTo("Codex is UNKNOWN (no rule matched, fallback)");

        assertThat(detector.classify(CodingAgentKind.UNKNOWN, screen("quiet")).explain())
            .isEqualTo("No coding agent detected");
    }
}
