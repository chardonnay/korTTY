package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

class AgentRuleSetTest {

    /** The Claude Code example from the rule-file schema. */
    private static final String CLAUDE_EXAMPLE = """
        {
          "kind": "CLAUDE_CODE",
          "version": 1,
          "comment": "Written against Claude Code 1.0.x (Ink TUI).",
          "fallbackState": "IDLE",
          "rules": [
            {
              "id": "permission-prompt",
              "state": "BLOCKED",
              "priority": 1000,
              "region": { "bottomNonEmptyLines": 18 },
              "anyLineRegex": [
                "Do you want to (proceed|make this edit|create|run|allow)",
                "^\\\\s*[│┃]?\\\\s*[❯>]?\\\\s*1\\\\.\\\\s*Yes\\\\b",
                "No, and tell Claude what to do differently",
                "\\\\(y/n\\\\)"
              ],
              "notContains": ["esc to interrupt"]
            },
            {
              "id": "question-picker",
              "state": "BLOCKED",
              "priority": 950,
              "region": { "bottomNonEmptyLines": 18 },
              "anyLineRegex": ["Enter to select", "Would you like to proceed", "^\\\\s*[│┃]?\\\\s*[❯>]\\\\s*\\\\d+\\\\.\\\\s+\\\\S"],
              "notContains": ["esc to interrupt"]
            },
            {
              "id": "working-spinner",
              "state": "WORKING",
              "priority": 900,
              "region": { "bottomNonEmptyLines": 8 },
              "anyLineRegex": [
                "(?i)esc to interrupt",
                "^\\\\s*[✻✳✶✽✢·⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]\\\\s+\\\\S+…"
              ]
            },
            {
              "id": "idle-prompt",
              "state": "IDLE",
              "priority": 100,
              "region": { "bottomNonEmptyLines": 6 },
              "anyLineRegex": ["^\\\\s*[│┃]?\\\\s*>\\\\s*$", "\\\\?\\\\s+for shortcuts"],
              "notContains": ["esc to interrupt"]
            }
          ]
        }
        """;

    private static AgentRuleSet parse(String json) throws IOException {
        return AgentRuleSet.parse(json, AgentRuleSet.Source.BUNDLED, null);
    }

    private static IOException parseFails(String json) {
        return expectThrows(IOException.class, () -> parse(json));
    }

    private static String minimal(String rulesJson) {
        return "{\"kind\":\"CODEX\",\"version\":1,\"rules\":[" + rulesJson + "]}";
    }

    @Test
    void parsesTheClaudeExample() throws IOException {
        AgentRuleSet set = AgentRuleSet.parse(CLAUDE_EXAMPLE, AgentRuleSet.Source.USER_OVERRIDE, Path.of("x.json"));

        assertThat(set.kind()).isEqualTo(CodingAgentKind.CLAUDE_CODE);
        assertThat(set.version()).isEqualTo(AgentRuleSet.SCHEMA_VERSION);
        assertThat(set.comment()).startsWith("Written against Claude Code");
        assertThat(set.fallbackState()).isEqualTo(CodingAgentState.IDLE);
        assertThat(set.source()).isEqualTo(AgentRuleSet.Source.USER_OVERRIDE);
        assertThat(set.sourcePath()).isEqualTo(Path.of("x.json"));
        assertThat(set.rules().stream().map(AgentRule::id).toList())
            .containsExactly("permission-prompt", "question-picker", "working-spinner", "idle-prompt").inOrder();

        AgentRule permission = set.rules().get(0);
        assertThat(permission.state()).isEqualTo(CodingAgentState.BLOCKED);
        assertThat(permission.priority()).isEqualTo(1000);
        assertThat(permission.bottomNonEmptyLines()).isEqualTo(18);
        assertThat(permission.anyLineRegex()).hasSize(4);
        assertThat(permission.anyLineRegex().get(1).pattern()).isEqualTo("^\\s*[│┃]?\\s*[❯>]?\\s*1\\.\\s*Yes\\b");
        assertThat(permission.notContains()).containsExactly("esc to interrupt");
        assertThat(permission.regex()).isNull();
        assertThat(permission.titleRegex()).isNull();
        assertThat(permission.alternateScreen()).isNull();

        ScreenSnapshot prompt = ScreenSnapshot.ofText("│ Do you want to proceed?\n│ ❯ 1. Yes", null, false);
        assertThat(permission.match(prompt)).hasValue("│ Do you want to proceed?");
    }

    @Test
    void rulesAreSortedByPriorityDescendingAndStableForTies() throws IOException {
        AgentRuleSet set = parse(minimal(
            "{\"id\":\"low\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"]},"
                + "{\"id\":\"high-a\",\"state\":\"WORKING\",\"priority\":9,\"contains\":[\"x\"]},"
                + "{\"id\":\"mid\",\"state\":\"IDLE\",\"priority\":5,\"contains\":[\"x\"]},"
                + "{\"id\":\"high-b\",\"state\":\"BLOCKED\",\"priority\":9,\"contains\":[\"x\"]},"
                + "{\"id\":\"high-c\",\"state\":\"DONE\",\"priority\":9,\"contains\":[\"x\"]}"));

        assertThat(set.rules().stream().map(AgentRule::id).toList())
            .containsExactly("high-a", "high-b", "high-c", "mid", "low").inOrder();
    }

    @Test
    void invalidJsonIsAnIoException() {
        IOException e = parseFails("{ this is not json");
        assertThat(e).hasMessageThat().contains("not valid JSON");
        assertThat(parseFails("")).hasMessageThat().isNotEmpty();
        assertThat(parseFails(null)).hasMessageThat().isNotEmpty();
    }

    @Test
    void nonObjectRootIsRejected() {
        assertThat(parseFails("[1, 2]")).hasMessageThat().contains("object");
        assertThat(parseFails("\"text\"")).hasMessageThat().contains("object");
    }

    @Test
    void unknownTopLevelKeyIsRejectedByName() {
        IOException e = parseFails("{\"kind\":\"CODEX\",\"version\":1,\"rules\":[],\"fallbakState\":\"IDLE\"}");
        assertThat(e).hasMessageThat().contains("fallbakState");
    }

    @Test
    void unknownRuleKeyIsRejectedByName() {
        IOException e = parseFails(minimal(
            "{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"],\"containz\":[\"y\"]}"));
        assertThat(e).hasMessageThat().contains("containz");

        IOException region = parseFails(minimal(
            "{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"],\"region\":{\"bottom\":3}}"));
        assertThat(region).hasMessageThat().contains("bottom");
    }

    @Test
    void missingRequiredRuleFieldsAreRejected() {
        assertThat(parseFails(minimal("{\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("id");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"priority\":1,\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("state");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("priority");
    }

    @Test
    void missingRequiredTopLevelFieldsAreRejected() {
        assertThat(parseFails("{\"version\":1,\"rules\":[]}")).hasMessageThat().contains("kind");
        assertThat(parseFails("{\"kind\":\"CODEX\",\"rules\":[]}")).hasMessageThat().contains("version");
        assertThat(parseFails("{\"kind\":\"CODEX\",\"version\":1}")).hasMessageThat().contains("rules");
        assertThat(parseFails("{\"kind\":\"CODEX\",\"version\":1,\"rules\":{}}")).hasMessageThat().contains("rules");
    }

    @Test
    void duplicateRuleIdIsRejected() {
        IOException e = parseFails(minimal(
            "{\"id\":\"same\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"]},"
                + "{\"id\":\"same\",\"state\":\"WORKING\",\"priority\":2,\"contains\":[\"y\"]}"));
        assertThat(e).hasMessageThat().contains("same");
    }

    @Test
    void ruleWithoutAnyMatcherIsRejected() {
        IOException e = parseFails(minimal("{\"id\":\"bare\",\"state\":\"IDLE\",\"priority\":1}"));
        assertThat(e).hasMessageThat().contains("bare");
        assertThat(e).hasMessageThat().contains("matcher");

        // notContains alone is not a matcher either.
        assertThat(parseFails(minimal("{\"id\":\"veto\",\"state\":\"IDLE\",\"priority\":1,\"notContains\":[\"x\"]}")))
            .hasMessageThat().contains("matcher");
    }

    @Test
    void badRegexIsRejectedNamingTheRule() {
        IOException anyLine = parseFails(minimal(
            "{\"id\":\"broken-any\",\"state\":\"IDLE\",\"priority\":1,\"anyLineRegex\":[\"(unclosed\"]}"));
        assertThat(anyLine).hasMessageThat().contains("broken-any");

        IOException regex = parseFails(minimal(
            "{\"id\":\"broken-regex\",\"state\":\"IDLE\",\"priority\":1,\"regex\":\"[z-a]\"}"));
        assertThat(regex).hasMessageThat().contains("broken-regex");

        IOException title = parseFails(minimal(
            "{\"id\":\"broken-title\",\"state\":\"IDLE\",\"priority\":1,\"title\":\"*\"}"));
        assertThat(title).hasMessageThat().contains("broken-title");
    }

    @Test
    void regexThatOverflowsTheMatcherStackOnALargeScreenIsRejected() {
        // Alternation inside a greedy loop: compiles fine, recurses per character when matched.
        IOException anyLine = parseFails(minimal(
            "{\"id\":\"boom\",\"state\":\"WORKING\",\"priority\":1,\"anyLineRegex\":[\"(\\\\w|\\\\s)*Yes\"]}"));
        assertThat(anyLine).hasMessageThat().contains("boom");
        assertThat(anyLine).hasMessageThat().contains("overflows");

        IOException regex = parseFails(minimal(
            "{\"id\":\"boom2\",\"state\":\"WORKING\",\"priority\":1,\"regex\":\"(.|\\\\n)*Yes\"}"));
        assertThat(regex).hasMessageThat().contains("boom2");
    }

    @Test
    void duplicateKeysAreRejectedNamingTheKey() {
        IOException topLevel = parseFails(
            "{\"kind\":\"CODEX\",\"version\":1,\"rules\":[],"
                + "\"rules\":[{\"id\":\"x\",\"state\":\"BLOCKED\",\"priority\":1,\"contains\":[\"never\"]}]}");
        assertThat(topLevel).hasMessageThat().contains("duplicate key 'rules'");

        IOException inRule = parseFails(minimal(
            "{\"id\":\"x\",\"state\":\"BLOCKED\",\"priority\":1,\"priority\":2,\"contains\":[\"a\"]}"));
        assertThat(inRule).hasMessageThat().contains("duplicate key 'priority'");
        assertThat(inRule).hasMessageThat().contains("rules");

        IOException inRegion = parseFails(minimal(
            "{\"id\":\"x\",\"state\":\"BLOCKED\",\"priority\":1,\"contains\":[\"a\"],"
                + "\"region\":{\"bottomNonEmptyLines\":1,\"bottomNonEmptyLines\":2}}"));
        assertThat(inRegion).hasMessageThat().contains("duplicate key 'bottomNonEmptyLines'");
    }

    @Test
    void wrongVersionIsRejected() {
        assertThat(parseFails("{\"kind\":\"CODEX\",\"version\":2,\"rules\":[]}")).hasMessageThat().contains("version");
        assertThat(parseFails("{\"kind\":\"CODEX\",\"version\":\"1\",\"rules\":[]}")).hasMessageThat()
            .contains("version");
        assertThat(parseFails("{\"kind\":\"CODEX\",\"version\":1.5,\"rules\":[]}")).hasMessageThat()
            .contains("version");
    }

    @Test
    void kindMustBeANonUnknownEnumName() throws IOException {
        assertThat(parse("{\"kind\":\"GEMINI_CLI\",\"version\":1,\"rules\":[]}").kind())
            .isEqualTo(CodingAgentKind.GEMINI_CLI);
        assertThat(parseFails("{\"kind\":\"UNKNOWN\",\"version\":1,\"rules\":[]}")).hasMessageThat().contains("kind");
        assertThat(parseFails("{\"kind\":\"claude-code\",\"version\":1,\"rules\":[]}")).hasMessageThat()
            .contains("kind");
        assertThat(parseFails("{\"kind\":7,\"version\":1,\"rules\":[]}")).hasMessageThat().contains("kind");
    }

    @Test
    void fallbackStateDefaultsToIdleAndMayBeUnknown() throws IOException {
        assertThat(parse("{\"kind\":\"CODEX\",\"version\":1,\"rules\":[]}").fallbackState())
            .isEqualTo(CodingAgentState.IDLE);
        assertThat(parse("{\"kind\":\"CODEX\",\"version\":1,\"fallbackState\":\"UNKNOWN\",\"rules\":[]}")
            .fallbackState()).isEqualTo(CodingAgentState.UNKNOWN);
        assertThat(parse("{\"kind\":\"CODEX\",\"version\":1,\"fallbackState\":\"WORKING\",\"rules\":[]}")
            .fallbackState()).isEqualTo(CodingAgentState.WORKING);
        assertThat(parseFails("{\"kind\":\"CODEX\",\"version\":1,\"fallbackState\":\"BUSY\",\"rules\":[]}"))
            .hasMessageThat().contains("fallbackState");
    }

    @Test
    void ruleStateMayNotBeUnknown() {
        IOException e = parseFails(minimal("{\"id\":\"a\",\"state\":\"UNKNOWN\",\"priority\":1,\"contains\":[\"x\"]}"));
        assertThat(e).hasMessageThat().contains("UNKNOWN");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"busy\",\"priority\":1,\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("busy");
    }

    @Test
    void commentIsOptionalAndRulesMayBeEmpty() throws IOException {
        AgentRuleSet set = parse("{\"kind\":\"CODEX\",\"version\":1,\"rules\":[]}");
        assertThat(set.comment()).isNull();
        assertThat(set.rules()).isEmpty();
        assertThat(set.source()).isEqualTo(AgentRuleSet.Source.BUNDLED);
        assertThat(set.sourcePath()).isNull();
    }

    @Test
    void bottomNonEmptyLinesDefaultsToZeroAndRejectsNegatives() throws IOException {
        AgentRule noRegion = parse(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"]}"))
            .rules().get(0);
        assertThat(noRegion.bottomNonEmptyLines()).isEqualTo(0);

        AgentRule emptyRegion = parse(minimal(
            "{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"],\"region\":{}}")).rules().get(0);
        assertThat(emptyRegion.bottomNonEmptyLines()).isEqualTo(0);

        assertThat(parseFails(minimal(
            "{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"],\"region\":{\"bottomNonEmptyLines\":-1}}")))
            .hasMessageThat().contains("bottomNonEmptyLines");
    }

    @Test
    void patternsCarryTheDocumentedFlags() throws IOException {
        AgentRule rule = parse(minimal(
            "{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"anyLineRegex\":[\"x\"],\"regex\":\"^y$\","
                + "\"title\":\"t\",\"alternateScreen\":true}")).rules().get(0);

        assertThat(rule.anyLineRegex().get(0).flags()).isEqualTo(Pattern.UNICODE_CASE);
        assertThat(rule.regex().flags()).isEqualTo(Pattern.UNICODE_CASE | Pattern.MULTILINE | Pattern.DOTALL);
        assertThat(rule.titleRegex().flags()).isEqualTo(Pattern.UNICODE_CASE);
        assertThat(rule.alternateScreen()).isTrue();
    }

    @Test
    void fieldTypesAreValidated() {
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":\"1\",\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("priority");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":\"x\"}")))
            .hasMessageThat().contains("contains");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[1]}")))
            .hasMessageThat().contains("contains");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"\"]}")))
            .hasMessageThat().contains("contains");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"alternateScreen\":\"yes\"}")))
            .hasMessageThat().contains("alternateScreen");
        assertThat(parseFails(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"region\":3,\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("region");
        assertThat(parseFails(minimal("{\"id\":\"bad id\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"]}")))
            .hasMessageThat().contains("bad id");
        assertThat(parseFails(minimal("\"not-an-object\""))).hasMessageThat().contains("object");
    }

    @Test
    void rulesListIsImmutable() throws IOException {
        AgentRuleSet set = parse(minimal("{\"id\":\"a\",\"state\":\"IDLE\",\"priority\":1,\"contains\":[\"x\"]}"));
        List<AgentRule> rules = set.rules();
        expectThrows(UnsupportedOperationException.class, () -> rules.add(rules.get(0)));
    }
}
