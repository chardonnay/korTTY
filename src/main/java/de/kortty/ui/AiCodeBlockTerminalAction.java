package de.kortty.ui;

import de.kortty.codingagent.CodingAgentState;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.model.AiChatTerminalActions;
import de.kortty.paste.PasteInspection;
import de.kortty.policy.AgentExecutionMode;
import de.kortty.policy.EffectivePolicy;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides whether a code block of an AI chat may be inserted into a terminal pane ({@link Action#INSERT}:
 * typed at the prompt through paste protection, never followed by Enter) or run there ({@link Action#RUN}:
 * one command line, sent with Enter after a confirmation that names the pane and shows the exact text).
 *
 * <p>The decision itself ({@link #decide}) is pure, over the policy, the block and a snapshot of the pane
 * ({@link PaneState}), so every rule is unit-tested; {@link PaneState#probe} reads that snapshot from a live
 * pane with the guards korTTY already has (alternate screen and paste pacing, terminal-agent runs, detected
 * coding agents, foreign sessions, broadcast and multi-exec, shell-integration marks). The verdict is asked
 * for up front, so a button that cannot act is greyed out with the reason as its tooltip, and asked again
 * right before the text is sent.
 *
 * <p>The target ({@link #resolveTarget}, design decision D3): the pane the chat came from while it is still
 * open, otherwise the focused pane of the terminal tab a snippet would go to
 * ({@link MainWindow#snippetInsertTarget}), otherwise none.
 *
 * <p>AI text is untrusted: terminal output the model read can carry instructions. So both actions are
 * refused where the pane's input is mirrored to other panes, and for a block that contains the mask
 * placeholder ({@link SessionJournalRedactor#REPLACEMENT}), which stands for a secret masked on the way to
 * the model and would be typed as the placeholder instead.
 */
final class AiCodeBlockTerminalAction {

    private AiCodeBlockTerminalAction() {
    }

    /** What the user asks for. */
    enum Action {
        /** Type the block at the pane's prompt through paste protection, without Enter. */
        INSERT,
        /** Run the block, one command line, in the pane: typed and followed by Enter. */
        RUN
    }

    /** What {@link #decide} found. */
    enum Verdict {
        /** The action may go ahead. */
        OK,
        /** No open terminal pane to send to. */
        NO_TARGET,
        /** The enterprise policy does not allow it: AI chat is off, or the agent may not run commands. */
        POLICY_DENIED,
        /** A full-screen program has the pane, or a paste is still being sent into it line by line. */
        PANE_BUSY,
        /** A korTTY terminal-agent run works in the pane, or (for Run) a coding agent does. */
        AGENT_BUSY,
        /** A coding agent in the pane waits for an approval: the text would answer it. */
        CODING_AGENT_BLOCKED,
        /** The pane's shell may run as another user or host: Run goes ahead only after the existing warning. */
        FOREIGN_SESSION_CONFIRM,
        /** Broadcast mode or multi-exec mirrors the pane's input to other panes. */
        BROADCAST_OR_MULTI_EXEC,
        /** The block contains the placeholder of a masked secret. */
        CONTAINS_MASK_PLACEHOLDER,
        /** Run takes one command line; a block of several lines can only be inserted. */
        RUN_MULTILINE_INSERT_ONLY,
        /** Shell integration reports a command still running in the pane, so a line would go to it. */
        RUN_NOT_AT_PROMPT,
        /** The pane's session is not connected. */
        DISCONNECTED;

        /** Whether the action goes ahead: at once, or after the foreign-session warning. */
        boolean proceeds() {
            return this == OK || this == FOREIGN_SESSION_CONFIRM;
        }

        /**
         * The message key that explains the verdict, for a tooltip or the status bar. Its argument
         * {@code {0}} is the pane's name, where the text names one.
         */
        String messageKey() {
            StringBuilder key = new StringBuilder("ai.result.terminal.verdict.");
            boolean upper = false;
            for (char c : name().toLowerCase(Locale.ROOT).toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    key.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            return key.toString();
        }
    }

    /**
     * The verdict, and for a Run that may go ahead whether korTTY cannot tell if a shell prompt waits in the
     * pane (no shell-integration marks, design decision D20): the confirmation then says so.
     */
    record Decision(Verdict verdict, boolean promptUnknown) {

        Decision {
            Objects.requireNonNull(verdict, "verdict");
        }

        static Decision of(Verdict verdict) {
            return new Decision(verdict, false);
        }

        boolean proceeds() {
            return verdict.proceeds();
        }
    }

    /**
     * What the enterprise policy allows: chat code blocks need AI chat; running one also needs an agent
     * execution mode other than {@link AgentExecutionMode#READ_ONLY}.
     */
    record Policy(boolean aiChatAllowed, AgentExecutionMode agentExecution) {

        Policy {
            agentExecution = agentExecution != null ? agentExecution : AgentExecutionMode.READ_ONLY;
        }

        /** The policy in force; an unreadable one allows nothing. */
        static Policy of(@Nullable EffectivePolicy policy) {
            if (policy == null) {
                return new Policy(false, AgentExecutionMode.READ_ONLY);
            }
            try {
                return new Policy(policy.aiChatAllowed(), policy.agentExecution());
            } catch (RuntimeException e) {
                return new Policy(false, AgentExecutionMode.READ_ONLY);
            }
        }

        boolean allows(Action action) {
            return aiChatAllowed && (action != Action.RUN || agentExecution != AgentExecutionMode.READ_ONLY);
        }
    }

    /**
     * A snapshot of the target pane.
     *
     * @param connected       whether its session is connected
     * @param mirrored        whether broadcast mode or multi-exec mirrors its input to other panes
     * @param terminalAgentRunning whether a korTTY terminal-agent run works in it
     * @param codingAgent     the state of the coding agent detected in it, or {@code null} for none
     * @param inputBusy       whether a full-screen program has it (the alternate screen) or a paste is still
     *                        being sent into it line by line
     * @param foreignSession  whether its shell is (suspected to be) running as another user or host
     * @param promptState     what its shell-integration marks say about its prompt
     */
    record PaneState(boolean connected, boolean mirrored, boolean terminalAgentRunning,
            @Nullable CodingAgentState codingAgent, boolean inputBusy, boolean foreignSession,
            ShellIntegrationController.PromptState promptState) {

        PaneState {
            promptState = promptState != null ? promptState : ShellIntegrationController.PromptState.UNKNOWN;
        }

        /**
         * Reads the snapshot of {@code target} with korTTY's own guards. Whatever cannot be read counts
         * against sending: unknown mirroring counts as mirrored, an unknown agent or input state as busy.
         * FX thread (reads the screen buffer).
         */
        static PaneState probe(TerminalPaneRef target) {
            TerminalTab tab = target.tab();
            KorttyTermWidget pane = target.pane();
            TerminalView view = tab != null ? tab.getTerminalView() : null;
            if (view == null || pane == null) {
                return new PaneState(false, false, false, null, false, false,
                    ShellIntegrationController.PromptState.UNKNOWN);
            }
            boolean connected = read(() -> view.isPaneConnected(pane), false);
            boolean mirrored = read(() -> pane.pasteTarget().broadcastActive() || pane.pasteTarget().multiExecActive(),
                true);
            boolean agentRunning = read(() -> view.terminalAgentRunCount(pane) > 0, true);
            CodingAgentState codingAgent;
            try {
                codingAgent = TerminalAttentionNotifier.shared().codingAgentStateIn(tab, pane).orElse(null);
            } catch (RuntimeException e) {
                // Unknown counts as an agent waiting for an approval: typing into one is the worse mistake.
                codingAgent = CodingAgentState.BLOCKED;
            }
            boolean inputBusy = read(() -> view.isPaneBusyWithInput(pane), true);
            boolean foreign = read(() -> view.isPaneInForeignSession(pane), true);
            ShellIntegrationController.PromptState prompt;
            try {
                prompt = view.panePromptState(pane);
            } catch (RuntimeException e) {
                prompt = ShellIntegrationController.PromptState.UNKNOWN;
            }
            return new PaneState(connected, mirrored, agentRunning, codingAgent, inputBusy, foreign, prompt);
        }

        private static boolean read(Supplier<Boolean> fact, boolean whenUnreadable) {
            try {
                return fact.get();
            } catch (RuntimeException e) {
                return whenUnreadable;
            }
        }
    }

    /**
     * Whether {@code action} may act on {@code block} in the pane {@code pane} describes ({@code null}: no
     * target), in this order: the policy; the block itself (the mask placeholder; several lines for Run);
     * the target; then the pane: not connected, mirrored, a coding agent waiting for an approval, an agent at
     * work, a full-screen program or a paced paste, and for Run a command still running by the marks and a
     * foreign session. The checks on the block come before the target, so a button whose block can never
     * act is greyed out with that reason, whichever pane is current.
     *
     * <p>A coding agent that does not wait for an approval stops Run (the line would be its prompt's input
     * and be submitted), not Insert, which only types text at its prompt. Insert ignores the prompt state
     * and the foreign session: it runs nothing.
     */
    static Decision decide(Action action, String block, Policy policy, @Nullable PaneState pane) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(policy, "policy");
        String text = block != null ? block : "";
        if (!policy.allows(action)) {
            return Decision.of(Verdict.POLICY_DENIED);
        }
        if (containsMaskPlaceholder(text)) {
            return Decision.of(Verdict.CONTAINS_MASK_PLACEHOLDER);
        }
        if (action == Action.RUN && isMultiLine(runLine(text))) {
            return Decision.of(Verdict.RUN_MULTILINE_INSERT_ONLY);
        }
        if (pane == null) {
            return Decision.of(Verdict.NO_TARGET);
        }
        if (!pane.connected()) {
            return Decision.of(Verdict.DISCONNECTED);
        }
        if (pane.mirrored()) {
            return Decision.of(Verdict.BROADCAST_OR_MULTI_EXEC);
        }
        if (pane.codingAgent() == CodingAgentState.BLOCKED) {
            return Decision.of(Verdict.CODING_AGENT_BLOCKED);
        }
        if (pane.terminalAgentRunning() || (action == Action.RUN && pane.codingAgent() != null)) {
            return Decision.of(Verdict.AGENT_BUSY);
        }
        if (pane.inputBusy()) {
            return Decision.of(Verdict.PANE_BUSY);
        }
        if (action == Action.INSERT) {
            return Decision.of(Verdict.OK);
        }
        if (pane.promptState() == ShellIntegrationController.PromptState.COMMAND_RUNNING) {
            return Decision.of(Verdict.RUN_NOT_AT_PROMPT);
        }
        boolean promptUnknown = pane.promptState() == ShellIntegrationController.PromptState.UNKNOWN;
        return new Decision(pane.foreignSession() ? Verdict.FOREIGN_SESSION_CONFIRM : Verdict.OK, promptUnknown);
    }

    /** The normalized languages ({@link SnippetLanguageSupport#detectSnippetLanguage}) a pane's shell reads. */
    private static final Set<String> SHELL_LANGUAGES = Set.of("bash", "powershell");

    /**
     * Whether a code block offers {@code action} at all, before any verdict: the user setting
     * ({@link AiChatTerminalActions}) must include it, and Run is offered only for a block in a shell language
     * ({@link #isShellLanguage}) whose command line has no control characters
     * ({@link #containsControlCharacters}). A block that is offered but cannot act now (the policy, several
     * lines, the mask placeholder, the pane) shows a greyed-out button with the reason from {@link #decide}.
     */
    static boolean offers(Action action, @Nullable AiChatTerminalActions setting, @Nullable String language,
            @Nullable String block) {
        AiChatTerminalActions actions = setting != null ? setting : AiChatTerminalActions.DEFAULT;
        if (action == Action.INSERT) {
            return actions.allowsInsert();
        }
        return actions.allowsRun() && isShellLanguage(language, block)
            && !containsControlCharacters(runLine(block));
    }

    /**
     * Whether the block is in a language a pane's shell reads: its fence says {@code bash}, {@code sh},
     * {@code shell}, {@code zsh} or a PowerShell name, or an unlabelled block starts with such a shebang.
     */
    static boolean isShellLanguage(@Nullable String language, @Nullable String block) {
        return SHELL_LANGUAGES.contains(SnippetLanguageSupport.detectSnippetLanguage(language, block));
    }

    /**
     * Whether {@code text} contains a character Run must not type into a shell: a tab (it would ask the
     * shell to complete the line), any other {@link PasteInspection#isControlCharacter control character}
     * (an escape sequence, Ctrl+C) or a {@link PasteInspection#isBidiControl bidi control} (the confirmation
     * would show another order than the shell reads). Line breaks do not count here: a block of several
     * lines is offered and refused by {@link #decide} with {@link Verdict#RUN_MULTILINE_INSERT_ONLY}.
     */
    static boolean containsControlCharacters(@Nullable String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || PasteInspection.isControlCharacter(c) || PasteInspection.isBidiControl(c)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code text} contains the placeholder masking puts in place of a secret. */
    static boolean containsMaskPlaceholder(String text) {
        return text != null && text.contains(SessionJournalRedactor.REPLACEMENT);
    }

    /**
     * The line Run sends for {@code block}: the block without the line breaks a code block ends with (Run
     * adds its own Enter).
     */
    static String runLine(String block) {
        String text = block != null ? block : "";
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }

    private static boolean isMultiLine(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ') {
                return true;
            }
        }
        return false;
    }

    /**
     * The pane a code block goes to (D3): {@code bound}, the pane the chat came from, while it is still open;
     * otherwise the focused pane of the terminal tab {@code window} would insert a snippet into; otherwise
     * {@code null}. FX thread.
     */
    static @Nullable TerminalPaneRef resolveTarget(@Nullable TerminalPaneRef bound, @Nullable MainWindow window) {
        return chooseTarget(bound, TerminalPaneRef::isOpen,
            () -> window != null ? TerminalPaneRef.focusedPaneOf(window.snippetInsertTarget(TerminalTab.class)) : null);
    }

    /**
     * Pure choice behind {@link #resolveTarget}: {@code bound} while {@code open} says it is, otherwise what
     * {@code fallback} gives when that is open, otherwise {@code null}.
     */
    static <P> @Nullable P chooseTarget(@Nullable P bound, Predicate<? super P> open, Supplier<? extends P> fallback) {
        if (bound != null && open.test(bound)) {
            return bound;
        }
        P other = fallback.get();
        return other != null && open.test(other) ? other : null;
    }
}
