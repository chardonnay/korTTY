package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

class CodingAgentKindTest {

    @Test
    void forExecutableRecognisesKnownAgentsCaseInsensitively() {
        assertThat(CodingAgentKind.forExecutable("claude")).hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(CodingAgentKind.forExecutable("CLAUDE")).hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(CodingAgentKind.forExecutable("claude-code")).hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(CodingAgentKind.forExecutable("codex")).hasValue(CodingAgentKind.CODEX);
        assertThat(CodingAgentKind.forExecutable("gemini")).hasValue(CodingAgentKind.GEMINI_CLI);
    }

    @Test
    void forExecutableIsEmptyForShellsBlankAndNull() {
        assertThat(CodingAgentKind.forExecutable("bash")).isEmpty();
        assertThat(CodingAgentKind.forExecutable("node")).isEmpty();
        assertThat(CodingAgentKind.forExecutable("")).isEmpty();
        assertThat(CodingAgentKind.forExecutable("   ")).isEmpty();
        assertThat(CodingAgentKind.forExecutable(null)).isEmpty();
    }

    @Test
    void forExecutableDoesNotStripDirectoriesOrExtensions() {
        // Callers normalise the basename first (LocalProcessInspector); the enum matches exact names only.
        assertThat(CodingAgentKind.forExecutable("/usr/local/bin/claude")).isEmpty();
        assertThat(CodingAgentKind.forExecutable("claude.exe")).isEmpty();
    }

    @Test
    void forIdMapsRuleFileBaseNames() {
        assertThat(CodingAgentKind.forId("claude-code")).hasValue(CodingAgentKind.CLAUDE_CODE);
        assertThat(CodingAgentKind.forId("codex")).hasValue(CodingAgentKind.CODEX);
        assertThat(CodingAgentKind.forId("gemini-cli")).hasValue(CodingAgentKind.GEMINI_CLI);
        assertThat(CodingAgentKind.forId("unknown")).isEmpty();
        assertThat(CodingAgentKind.forId(null)).isEmpty();
    }

    @Test
    void unknownHasNoIdAndNoExecutables() {
        assertThat(CodingAgentKind.UNKNOWN.id()).isNull();
        assertThat(CodingAgentKind.UNKNOWN.executableNames()).isEmpty();
        assertThat(CodingAgentKind.UNKNOWN.displayName()).isEqualTo("Unknown");
    }

    @Test
    void everyKnownKindHasUniqueIdAndLowerCaseExecutables() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (CodingAgentKind kind : CodingAgentKind.values()) {
            if (kind == CodingAgentKind.UNKNOWN) {
                continue;
            }
            assertThat(ids.add(kind.id())).isTrue();
            for (String executable : kind.executableNames()) {
                assertThat(executable).isEqualTo(executable.toLowerCase(java.util.Locale.ROOT));
            }
        }
    }
}
