package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentCommandSupportTest {

    @Test
    void parseExecuteShortcutSupportsInlineOptions() {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut(
                "agent(profile=ops,root=true,ask=yes) install updates",
                "agent");

        assertNotNull(invocation);
        assertEquals(TerminalAgentCommandSupport.InvocationKind.EXECUTE, invocation.kind());
        assertEquals("ops", invocation.profileName());
        assertTrue(invocation.autoApproveRootCommands());
        assertTrue(invocation.askConfirmationBeforeEveryCommand());
        assertEquals("install updates", invocation.userPrompt());
    }

    @Test
    void parseAskAndPlanShortcutsRespectCustomCommandName() {
        TerminalAgentCommandSupport.Invocation askInvocation =
            TerminalAgentCommandSupport.parseShortcut("susi-ask: what failed?", "susi");
        TerminalAgentCommandSupport.Invocation planInvocation =
            TerminalAgentCommandSupport.parseShortcut("susi-plan(profile=db) install postgres", "susi");

        assertNotNull(askInvocation);
        assertEquals(TerminalAgentCommandSupport.InvocationKind.ASK, askInvocation.kind());
        assertEquals("what failed?", askInvocation.userPrompt());

        assertNotNull(planInvocation);
        assertEquals(TerminalAgentCommandSupport.InvocationKind.PLAN, planInvocation.kind());
        assertEquals("db", planInvocation.profileName());
        assertEquals("install postgres", planInvocation.userPrompt());
    }

    @Test
    void parseShortcutCanUseCaseInsensitiveCommandNamesWhenEnabled() {
        assertNull(TerminalAgentCommandSupport.parseShortcut("Agent install tmux", "agent"));

        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut("Agent install tmux", "agent", true);

        assertNotNull(invocation);
        assertEquals(TerminalAgentCommandSupport.InvocationKind.EXECUTE, invocation.kind());
        assertEquals("install tmux", invocation.userPrompt());
    }

    @Test
    void invalidOrBlankCommandNamesFallBackOrValidate() {
        assertEquals("agent", TerminalAgentCommandSupport.normalizeCommandName(" "));
        assertNull(TerminalAgentCommandSupport.validateCommandName("agent_2"));
        assertNotNull(TerminalAgentCommandSupport.validateCommandName("2bad name"));
    }

    @Test
    void unrelatedCommandDoesNotParse() {
        assertNull(TerminalAgentCommandSupport.parseShortcut("ls -la", "agent"));
        assertFalse(TerminalAgentCommandSupport.buildUsageText("agent").isBlank());
    }
}
