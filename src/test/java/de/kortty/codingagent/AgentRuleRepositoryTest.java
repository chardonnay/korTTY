package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class AgentRuleRepositoryTest {

    private static final String OVERRIDE_JSON = """
        {
          "kind": "CLAUDE_CODE",
          "version": 1,
          "comment": "user override",
          "fallbackState": "UNKNOWN",
          "rules": [
            { "id": "custom", "state": "WORKING", "priority": 1, "contains": ["custom marker"] }
          ]
        }
        """;

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kortty-coding-agents");
    }

    @AfterMethod
    void deleteConfigDir() throws IOException {
        if (configDir == null || !Files.exists(configDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(configDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path overrideDir() throws IOException {
        return Files.createDirectories(configDir.resolve(AgentRuleRepository.USER_DIR_NAME));
    }

    private Path writeOverride(String fileName, String json) throws IOException {
        return Files.writeString(overrideDir().resolve(fileName), json, StandardCharsets.UTF_8);
    }

    @Test
    void bundledRuleSetsExistForEveryKnownKind() {
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).isEmpty();
        for (CodingAgentKind kind : List.of(CodingAgentKind.CLAUDE_CODE, CodingAgentKind.CODEX,
            CodingAgentKind.GEMINI_CLI)) {
            AgentRuleSet set = repository.ruleSetFor(kind).orElseThrow();
            assertThat(set.kind()).isEqualTo(kind);
            assertThat(set.source()).isEqualTo(AgentRuleSet.Source.BUNDLED);
            assertThat(set.sourcePath()).isNull();
            assertThat(set.rules()).isNotEmpty();
            assertThat(set.comment()).isNotEmpty();
        }
        assertThat(repository.ruleSetFor(CodingAgentKind.UNKNOWN)).isEmpty();
        assertThat(repository.ruleSetFor(null)).isEmpty();
        assertThat(repository.ruleSets().stream().map(AgentRuleSet::kind).toList())
            .containsExactly(CodingAgentKind.CLAUDE_CODE, CodingAgentKind.CODEX, CodingAgentKind.GEMINI_CLI)
            .inOrder();
        assertThat(repository.userOverrideDirectory())
            .isEqualTo(configDir.toAbsolutePath().normalize().resolve(AgentRuleRepository.USER_DIR_NAME));
    }

    @Test
    void missingUserDirectoryIsToleratedWithoutProblems() {
        assertThat(Files.exists(configDir.resolve(AgentRuleRepository.USER_DIR_NAME))).isFalse();
        AgentRuleRepository repository = new AgentRuleRepository(configDir);
        assertThat(repository.problems()).isEmpty();
        assertThat(repository.ruleSets()).hasSize(3);
    }

    @Test
    void userOverrideWinsForItsKindOnly() throws IOException {
        Path override = writeOverride("claude-code.json", OVERRIDE_JSON);
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).isEmpty();
        AgentRuleSet claude = repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow();
        assertThat(claude.source()).isEqualTo(AgentRuleSet.Source.USER_OVERRIDE);
        assertThat(claude.sourcePath()).isEqualTo(override);
        assertThat(claude.fallbackState()).isEqualTo(CodingAgentState.UNKNOWN);
        assertThat(claude.rules().stream().map(AgentRule::id).toList()).containsExactly("custom");

        assertThat(repository.ruleSetFor(CodingAgentKind.CODEX).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
        assertThat(repository.ruleSetFor(CodingAgentKind.GEMINI_CLI).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
    }

    @Test
    void overrideWhoseKindDoesNotMatchTheFileNameIsRejected() throws IOException {
        writeOverride("codex.json", OVERRIDE_JSON);
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).hasSize(1);
        assertThat(repository.problems().get(0).location()).endsWith("codex.json");
        assertThat(repository.problems().get(0).message()).contains("CLAUDE_CODE");
        assertThat(repository.ruleSetFor(CodingAgentKind.CODEX).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
    }

    @Test
    void invalidJsonOverrideKeepsTheBundledRulesAndReportsOneProblem() throws IOException {
        Path override = writeOverride("claude-code.json", "{ \"kind\": \"CLAUDE_CODE\", ");
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).hasSize(1);
        RuleLoadProblem problem = repository.problems().get(0);
        assertThat(problem.location()).isEqualTo(override.toString());
        assertThat(problem.message()).isNotEmpty();
        AgentRuleSet claude = repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow();
        assertThat(claude.source()).isEqualTo(AgentRuleSet.Source.BUNDLED);
        assertThat(claude.sourcePath()).isNull();
    }

    @Test
    void schemaViolationInOverrideIsReportedWithTheOffendingKey() throws IOException {
        writeOverride("claude-code.json", OVERRIDE_JSON.replace("\"comment\"", "\"coment\""));
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).hasSize(1);
        assertThat(repository.problems().get(0).message()).contains("coment");
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
    }

    @Test
    void symlinkedOverrideIsRejected() throws IOException {
        Path target = Files.writeString(configDir.resolve("real-claude-code.json"), OVERRIDE_JSON,
            StandardCharsets.UTF_8);
        Path link = overrideDir().resolve("claude-code.json");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SkipException("Symbolic links are not available here: " + e);
        }
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).hasSize(1);
        assertThat(repository.problems().get(0).location()).isEqualTo(link.toString());
        assertThat(repository.problems().get(0).message()).contains("regular file");
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
    }

    @Test
    void oversizedOverrideIsRejectedWithoutBeingParsed() throws IOException {
        String padding = " ".repeat((int) AgentRuleRepository.MAX_RULE_FILE_BYTES);
        Path override = writeOverride("claude-code.json", OVERRIDE_JSON + padding);
        assertThat(Files.size(override)).isGreaterThan(AgentRuleRepository.MAX_RULE_FILE_BYTES);
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).hasSize(1);
        assertThat(repository.problems().get(0).message()).contains("larger than the limit");
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
    }

    @Test
    void overrideExactlyAtTheSizeLimitIsAccepted() throws IOException {
        String json = OVERRIDE_JSON.stripTrailing();
        String padded = json + " ".repeat((int) AgentRuleRepository.MAX_RULE_FILE_BYTES - json.length());
        Path override = writeOverride("claude-code.json", padded);
        assertThat(Files.size(override)).isEqualTo(AgentRuleRepository.MAX_RULE_FILE_BYTES);
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).isEmpty();
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.USER_OVERRIDE);
    }

    @Test
    void fileNamesNotMatchingThePatternAreIgnoredSilently() throws IOException {
        writeOverride("claude-code.json.bak", "not json at all");
        writeOverride(".claude-code.json", "not json at all");
        writeOverride("README.txt", "not json at all");
        Files.createDirectories(overrideDir().resolve("nested.json"));
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        // A directory named *.json passes the name filter but is not a regular file.
        assertThat(repository.problems()).hasSize(1);
        assertThat(repository.problems().get(0).location()).endsWith("nested.json");
        assertThat(repository.ruleSets()).hasSize(3);
        for (AgentRuleSet set : repository.ruleSets()) {
            assertThat(set.source()).isEqualTo(AgentRuleSet.Source.BUNDLED);
        }
    }

    @Test
    void validNameButUnknownKindFileIsReportedNotIgnored() throws IOException {
        writeOverride("my-agent.json", OVERRIDE_JSON);
        AgentRuleRepository repository = new AgentRuleRepository(configDir);

        assertThat(repository.problems()).hasSize(1);
        assertThat(repository.problems().get(0).message()).contains("my-agent.json");
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
    }

    @Test
    void reloadPicksUpANewlyWrittenOverrideAndItsRemoval() throws IOException {
        AgentRuleRepository repository = new AgentRuleRepository(configDir);
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);

        Path override = writeOverride("claude-code.json", OVERRIDE_JSON);
        repository.reload();
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.USER_OVERRIDE);

        Files.delete(override);
        repository.reload();
        assertThat(repository.ruleSetFor(CodingAgentKind.CLAUDE_CODE).orElseThrow().source())
            .isEqualTo(AgentRuleSet.Source.BUNDLED);
        assertThat(repository.problems()).isEmpty();
    }

    @Test
    void indexMatchesResourceDirectory() throws Exception {
        URL indexUrl = getClass().getClassLoader().getResource(AgentRuleRepository.INDEX_RESOURCE);
        assertThat(indexUrl).isNotNull();
        Path indexFile = Path.of(indexUrl.toURI());
        Path resourceDir = indexFile.getParent();

        Set<String> indexed = new HashSet<>();
        for (String line : Files.readAllLines(indexFile)) {
            String fileName = line.strip();
            if (!fileName.isEmpty() && !fileName.startsWith("#")) {
                assertThat(AgentRuleRepository.VALID_FILE_NAME.matcher(fileName).matches()).isTrue();
                assertThat(indexed.add(fileName)).isTrue();
            }
        }

        Set<String> onDisk = new HashSet<>();
        try (Stream<Path> stream = Files.list(resourceDir)) {
            for (Path path : stream.toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".json")) {
                    onDisk.add(fileName);
                }
            }
        }

        // Files added but not listed (or listed but missing) must fail the build.
        assertThat(indexed).isEqualTo(onDisk);
        assertThat(indexed).isNotEmpty();
    }

    @Test
    void everyIndexedFileParsesAndIsNamedAfterItsKind() throws Exception {
        URL indexUrl = getClass().getClassLoader().getResource(AgentRuleRepository.INDEX_RESOURCE);
        assertThat(indexUrl).isNotNull();
        Path resourceDir = Path.of(indexUrl.toURI()).getParent();
        int parsed = 0;
        for (String line : Files.readAllLines(Path.of(indexUrl.toURI()))) {
            String fileName = line.strip();
            if (fileName.isEmpty() || fileName.startsWith("#")) {
                continue;
            }
            String json = Files.readString(resourceDir.resolve(fileName), StandardCharsets.UTF_8);
            AgentRuleSet set = AgentRuleSet.parse(json, AgentRuleSet.Source.BUNDLED, null);
            assertThat(set.kind().id() + ".json").isEqualTo(fileName);
            assertThat(set.version()).isEqualTo(AgentRuleSet.SCHEMA_VERSION);
            assertThat(set.comment()).contains("re-verified");
            parsed++;
        }
        assertThat(parsed).isEqualTo(3);

        // The repository must load exactly these files without a single problem.
        AgentRuleRepository repository = new AgentRuleRepository(configDir);
        assertThat(repository.problems()).isEmpty();
        assertThat(repository.ruleSets()).hasSize(parsed);
    }
}
