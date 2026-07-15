package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class TerminalAgentCommandSupportTest {

    @Test
    void parseExecuteShortcutSupportsInlineOptions() {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut(
                "agent(profile=ops,root=true,ask=yes) install updates",
                "agent");

        assertThat(invocation).isNotNull();
        assertThat(invocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.EXECUTE);
        assertThat(invocation.profileName()).isEqualTo("ops");
        assertThat(invocation.autoApproveRootCommands()).isTrue();
        assertThat(invocation.askConfirmationBeforeEveryCommand()).isTrue();
        assertThat(invocation.userPrompt()).isEqualTo("install updates");
    }

    @Test
    void parseExecuteShortcutPreservesMultilineBracketedPastePrompt() {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut(
                "agent explain first line\nsecond ü.txt",
                "agent");

        assertThat(invocation).isNotNull();
        assertThat(invocation.userPrompt()).isEqualTo("explain first line\nsecond ü.txt");
    }

    @Test
    void parseAskAndPlanShortcutsRespectCustomCommandName() {
        TerminalAgentCommandSupport.Invocation askInvocation =
            TerminalAgentCommandSupport.parseShortcut("susi-ask: what failed?", "susi");
        TerminalAgentCommandSupport.Invocation planInvocation =
            TerminalAgentCommandSupport.parseShortcut("susi-plan(profile=db) install postgres", "susi");

        assertThat(askInvocation).isNotNull();
        assertThat(askInvocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.ASK);
        assertThat(askInvocation.userPrompt()).isEqualTo("what failed?");

        assertThat(planInvocation).isNotNull();
        assertThat(planInvocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.PLAN);
        assertThat(planInvocation.profileName()).isEqualTo("db");
        assertThat(planInvocation.userPrompt()).isEqualTo("install postgres");
    }

    @Test
    void parsePlanFlagShortcutSupportsProfileOptions() {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut("agent -plan(profile=db) install postgres", "agent");

        assertThat(invocation).isNotNull();
        assertThat(invocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.PLAN);
        assertThat(invocation.profileName()).isEqualTo("db");
        assertThat(invocation.userPrompt()).isEqualTo("install postgres");
    }

    @Test
    void parsePlanFlagShortcutCombinesCommandAndPlanOptions() {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut("agent(root=true) -plan(profile=ops) install nginx", "agent");

        assertThat(invocation).isNotNull();
        assertThat(invocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.PLAN);
        assertThat(invocation.profileName()).isEqualTo("ops");
        assertThat(invocation.autoApproveRootCommands()).isTrue();
        assertThat(invocation.userPrompt()).isEqualTo("install nginx");
    }

    @Test
    void parseShortcutCanUseCaseInsensitiveCommandNamesWhenEnabled() {
        assertThat(TerminalAgentCommandSupport.parseShortcut("Agent install tmux", "agent")).isNull();

        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut("Agent install tmux", "agent", true);

        assertThat(invocation).isNotNull();
        assertThat(invocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.EXECUTE);
        assertThat(invocation.userPrompt()).isEqualTo("install tmux");
    }

    @Test
    void parsePlanFlagShortcutCanUseCaseInsensitiveCommandNamesWhenEnabled() {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut("Agent -PLAN install tmux", "agent", true);

        assertThat(invocation).isNotNull();
        assertThat(invocation.kind()).isEqualTo(TerminalAgentCommandSupport.InvocationKind.PLAN);
        assertThat(invocation.userPrompt()).isEqualTo("install tmux");
    }

    @Test
    void invalidOrBlankCommandNamesFallBackOrValidate() {
        assertThat(TerminalAgentCommandSupport.normalizeCommandName(" ")).isEqualTo("agent");
        assertThat(TerminalAgentCommandSupport.validateCommandName("agent_2")).isNull();
        assertThat(TerminalAgentCommandSupport.validateCommandName("2bad name")).isNotNull();
    }

    @Test
    void unrelatedCommandDoesNotParse() {
        assertThat(TerminalAgentCommandSupport.parseShortcut("ls -la", "agent")).isNull();
        assertThat(TerminalAgentCommandSupport.buildUsageText("agent").isBlank()).isFalse();
    }
}
