package de.kortty.core;

import de.kortty.core.TerminalAgentCompletionSupport.TabContext;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TerminalAgentCompletionSupportTest {

    @Test
    void sanitizeStripsTrailingShellNotFoundOutput() {
        // The exact pollution seen in the field: a prompt with the shell's "command not found"
        // error captured from the visible terminal appended to it.
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            "show me the 5 biggest files with type txt file bash: agent: Befehl nicht gefunden"))
            .isEqualTo("show me the 5 biggest files with type txt file");
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            "list services zsh: agent-ask: command not found"))
            .isEqualTo("list services");
    }

    @Test
    void sanitizeDoesNotClipColonsThatAreNotShellErrors() {
        // A "<shell>: <token>: <text>" shape that is NOT a not-found error must be left intact.
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            "deploy and watch for ksh: x: y in the logs"))
            .isEqualTo("deploy and watch for ksh: x: y in the logs");
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            "write a script that prints bash: hello: world"))
            .isEqualTo("write a script that prints bash: hello: world");
    }

    @Test
    void sanitizeDropsCapturedCompletionListings() {
        // The polluted ".ansible/ .bashrc .config/ ..." first-row entry from a captured TAB listing.
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            ".ansible/ .bashrc .config/ lion .npm/ Scripts/ .viminfo .bash_history")).isNull();
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt("   ")).isNull();
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(null)).isNull();
    }

    @Test
    void sanitizeKeepsOrdinaryPrompts() {
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            "migriere das script find_biggest_files.pl nach ansible-playbook"))
            .isEqualTo("migriere das script find_biggest_files.pl nach ansible-playbook");
        // Incidental dotfiles in a natural prompt must NOT trigger the listing heuristic.
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt("update .bashrc to add an alias"))
            .isEqualTo("update .bashrc to add an alias");
        // Even a prompt that lists several dotfiles is a legitimate task, not a captured listing.
        assertThat(TerminalAgentCompletionSupport.sanitizeHistoryPrompt(
            "compare .bashrc .zshrc .profile .vimrc"))
            .isEqualTo("compare .bashrc .zshrc .profile .vimrc");
        assertThat(TerminalAgentCompletionSupport.looksLikeCompletionListing("show me the 5 biggest files"))
            .isFalse();
        assertThat(TerminalAgentCompletionSupport.looksLikeCompletionListing(
            ".ansible/ .bashrc .config/ .npm/ Scripts/")).isTrue();
    }

    @Test
    void commandOptionsListsTheThreeVariants() {
        assertThat(TerminalAgentCompletionSupport.commandOptions("agent"))
            .containsExactly("agent", "agent-ask", "agent-plan").inOrder();
        assertThat(TerminalAgentCompletionSupport.commandOptions("ki"))
            .containsExactly("ki", "ki-ask", "ki-plan").inOrder();
    }

    @Test
    void classifyDetectsCommandContextWhileTypingTheName() {
        assertThat(TerminalAgentCompletionSupport.classify("agent", "agent", false)).isEqualTo(TabContext.COMMAND);
        assertThat(TerminalAgentCompletionSupport.classify("agent-", "agent", false)).isEqualTo(TabContext.COMMAND);
        assertThat(TerminalAgentCompletionSupport.classify("agent-pl", "agent", false)).isEqualTo(TabContext.COMMAND);
        assertThat(TerminalAgentCompletionSupport.classify("  agent", "agent", false)).isEqualTo(TabContext.COMMAND);
    }

    @Test
    void classifyDetectsHistoryContextAfterCommandAndSpace() {
        assertThat(TerminalAgentCompletionSupport.classify("agent ", "agent", false)).isEqualTo(TabContext.HISTORY);
        assertThat(TerminalAgentCompletionSupport.classify("agent-ask ", "agent", false)).isEqualTo(TabContext.HISTORY);
        assertThat(TerminalAgentCompletionSupport.classify("agent-plan show me", "agent", false)).isEqualTo(TabContext.HISTORY);
    }

    @Test
    void classifyReturnsNoneForNonAgentInput() {
        assertThat(TerminalAgentCompletionSupport.classify("ls -la", "agent", false)).isEqualTo(TabContext.NONE);
        assertThat(TerminalAgentCompletionSupport.classify("agentx", "agent", false)).isEqualTo(TabContext.NONE);
        assertThat(TerminalAgentCompletionSupport.classify("agentfoo bar", "agent", false)).isEqualTo(TabContext.NONE);
        assertThat(TerminalAgentCompletionSupport.classify("", "agent", false)).isEqualTo(TabContext.NONE);
        assertThat(TerminalAgentCompletionSupport.classify(null, "agent", false)).isEqualTo(TabContext.NONE);
    }

    @Test
    void classifyIsCaseInsensitiveWhenConfigured() {
        assertThat(TerminalAgentCompletionSupport.classify("AGENT", "agent", true)).isEqualTo(TabContext.COMMAND);
        assertThat(TerminalAgentCompletionSupport.classify("AGENT ", "agent", true)).isEqualTo(TabContext.HISTORY);
        assertThat(TerminalAgentCompletionSupport.classify("AGENT", "agent", false)).isEqualTo(TabContext.NONE);
    }

    @Test
    void completionSuffixReturnsMissingCharacters() {
        assertThat(TerminalAgentCompletionSupport.completionSuffix("agent", "agent-ask")).isEqualTo("-ask");
        assertThat(TerminalAgentCompletionSupport.completionSuffix("agent-", "agent-plan")).isEqualTo("plan");
        assertThat(TerminalAgentCompletionSupport.completionSuffix("agent-ask", "agent-ask")).isEmpty();
        assertThat(TerminalAgentCompletionSupport.completionSuffix("  agent ", "agent")).isEmpty();
    }

    @Test
    void promptFromRawExtractsThePromptPart() {
        assertThat(TerminalAgentCompletionSupport.promptFromRaw("agent show me the files", "agent", false))
            .isEqualTo("show me the files");
        assertThat(TerminalAgentCompletionSupport.promptFromRaw("agent-ask what is up", "agent", false))
            .isEqualTo("what is up");
        assertThat(TerminalAgentCompletionSupport.promptFromRaw("ls -la", "agent", false)).isEmpty();
    }
}
