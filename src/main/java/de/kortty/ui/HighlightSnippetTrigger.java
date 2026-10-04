package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.KorTTYApplication;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetVariableManager;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.TerminalOutputHighlighter.LineMatch;
import de.kortty.model.HighlightRule;
import de.kortty.model.ServerConnection;
import de.kortty.model.Snippet;
import de.kortty.policy.PolicyManager;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Runs the snippet of a highlight rule whose action is {@link HighlightRule.Action#RUN_SNIPPET} when its
 * pattern appears in new output of a pane: in that pane, as Send to Terminal would, once
 * {@link HighlightSnippetTriggerGuard} agrees — triggers allowed, not the cursor line, no mirrored keys,
 * agent, full-screen program or paced paste at work, outside the cooldown and the loop guard, and confirmed
 * for the connection.
 *
 * <ul>
 *   <li>The first time a rule wants to run a snippet in a connection, a confirmation names the rule, the
 *       snippet and the connection and shows the snippet's text; the answer holds until korTTY quits.</li>
 *   <li>The snippet is resolved with the stored values of its variables. It cannot ask for a value, so a
 *       snippet with a declared variable that has none does not run, and the pane says so — once per rule,
 *       as for a snippet that no longer exists.</li>
 *   <li>It goes to the matching pane only: broadcast mode and multi-exec never mirror it.</li>
 *   <li>A tab the user is not looking at gets its mark, naming the snippet that ran.</li>
 * </ul>
 *
 * <p>The log names rules and snippets by their ids only. JavaFX thread.
 */
final class HighlightSnippetTrigger {

    private static final Logger logger = LoggerFactory.getLogger(HighlightSnippetTrigger.class);

    static final String CONFIRM_TITLE_KEY = "highlight.trigger.snippet.confirm.title";

    /** {0} the rule's name, {1} the snippet's name, {2} the connection's name. */
    static final String CONFIRM_TEXT_KEY = "highlight.trigger.snippet.confirm.text";

    static final String CONFIRM_DETAILS_KEY = "highlight.trigger.snippet.confirm.details";

    static final String CONFIRM_PREVIEW_KEY = "highlight.trigger.snippet.confirm.preview";

    static final String CONFIRM_ALLOW_KEY = "highlight.trigger.snippet.confirm.allow";

    static final String CONFIRM_DENY_KEY = "highlight.trigger.snippet.confirm.deny";

    /** The tab's tooltip after a run: {0} the snippet's name. */
    static final String RAN_KEY = "highlight.trigger.snippet.ran";

    /** {0} how many runs in a row stop a pane, {1} how many minutes without a match lift the stop. */
    static final String LOOP_STOPPED_KEY = "highlight.trigger.snippet.loopStopped";

    /** {0} the rule's name. */
    static final String MISSING_KEY = "highlight.trigger.snippet.missing";

    /** {0} the snippet's name, {1} the variable, as {@code ${name}}. */
    static final String NEEDS_VALUE_KEY = "highlight.trigger.snippet.needsValue";

    /** {0} the snippet's name. */
    static final String UNSUPPORTED_KEY = "highlight.trigger.snippet.unsupported";

    /** The banner Send to Terminal prints before a snippet: {0} its name. */
    static final String BANNER_KEY = "snippets.insertTerminal.banner";

    static final String UNNAMED_SNIPPET_KEY = "snippets.insertTerminal.unnamed";

    /** Every key of this class's own texts, for the i18n coverage test. */
    static final List<String> KEYS = List.of(CONFIRM_TITLE_KEY, CONFIRM_TEXT_KEY, CONFIRM_DETAILS_KEY,
        CONFIRM_PREVIEW_KEY, CONFIRM_ALLOW_KEY, CONFIRM_DENY_KEY, RAN_KEY, LOOP_STOPPED_KEY, MISSING_KEY,
        NEEDS_VALUE_KEY, UNSUPPORTED_KEY);

    /** What korTTY's own lines in a pane start with. */
    static final String PANE_MESSAGE_PREFIX = "[korTTY] ";

    /** At most this many characters of a snippet's or connection's name go into a text. */
    static final int MAX_NAME_CHARS = 80;

    /** At most this many lines and characters of the snippet the confirmation shows. */
    static final int PREVIEW_LINES = 12;

    static final int PREVIEW_CHARS = 1_500;

    /**
     * A snippet made ready to type, or why it cannot run.
     *
     * @param line              what to type (a newline follows), or {@code null} when it cannot run
     * @param generatedOneLiner whether {@code line} is a generated one-liner that an SSH session gets
     *                          without the remote echo of its base64 text
     * @param problemKey        why it cannot run, one of {@link #MISSING_KEY}, {@link #NEEDS_VALUE_KEY},
     *                          {@link #UNSUPPORTED_KEY}; {@code null} when it can
     * @param problem           the translated text of {@code problemKey}
     */
    record Preparation(@Nullable String line, boolean generatedOneLiner, @Nullable String problemKey,
            @Nullable String problem) {

        boolean ready() {
            return line != null;
        }

        static Preparation problem(String key, String text) {
            return new Preparation(null, false, key, text);
        }
    }

    private static @Nullable HighlightSnippetTrigger shared;

    private final HighlightSnippetTriggerGuard guard;

    private final Supplier<SnippetManager> snippets;

    private final Supplier<SnippetVariableManager> variables;

    HighlightSnippetTrigger(HighlightSnippetTriggerGuard guard, Supplier<SnippetManager> snippets,
            Supplier<SnippetVariableManager> variables) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        this.variables = Objects.requireNonNull(variables, "variables");
    }

    /** The runner of the running application, for every window. JavaFX thread. */
    static HighlightSnippetTrigger shared() {
        HighlightSnippetTrigger trigger = shared;
        if (trigger == null) {
            trigger = new HighlightSnippetTrigger(
                new HighlightSnippetTriggerGuard(() -> System.nanoTime() / 1_000_000L,
                    () -> HighlightTriggerDispatcher.triggersAllowed(
                        TerminalAttentionNotifier.currentSettings(), PolicyManager.effective())),
                HighlightSnippetTrigger::snippetManager, HighlightSnippetTrigger::variableManager);
            shared = trigger;
        }
        return trigger;
    }

    /**
     * Highlight rules matched new output in {@code widget}, a pane of {@code tab}; those that run a snippet
     * run it there when the guard agrees, after asking the user the first time for the connection.
     */
    void onHighlightTrigger(TerminalTab tab, SithTermFxWidget widget, List<LineMatch> matches) {
        if (tab == null || widget == null || matches == null || tab.getTerminalView() == null) {
            return;
        }
        for (LineMatch match : matches) {
            CompiledHighlightSet.Rule rule = match != null ? match.rule() : null;
            if (rule == null || rule.action() != HighlightRule.Action.RUN_SNIPPET || rule.snippetId() == null) {
                continue;
            }
            HighlightSnippetTriggerGuard.Request request = new HighlightSnippetTriggerGuard.Request(widget,
                connectionKey(tab, widget), rule.ruleId(), rule.snippetId(), match.cursorLine(),
                mirroredInput(widget), agentBusy(tab, widget), inputBusy(tab, widget));
            handle(tab, widget, rule, request, guard.decide(request));
        }
    }

    private void handle(TerminalTab tab, SithTermFxWidget widget, CompiledHighlightSet.Rule rule,
            HighlightSnippetTriggerGuard.Request request, HighlightSnippetTriggerGuard.Verdict verdict) {
        switch (verdict) {
            case RUN -> run(tab, widget, rule);
            case ASK -> confirm(tab, widget, rule, request);
            case LOOP_STOPPED -> {
                logger.info("Highlight rules ran snippets {} times in a row in a terminal pane; stopped them there "
                    + "(rule {})", HighlightSnippetTriggerGuard.MAX_RUNS_IN_A_ROW, rule.ruleId());
                tell(tab, widget, loopStoppedText(I18n::get));
            }
            default -> logger.debug("Highlight rule {} did not run its snippet: {}", rule.ruleId(), verdict);
        }
    }

    /** Types the rule's snippet into the pane, or says once why it cannot. */
    private void run(TerminalTab tab, SithTermFxWidget widget, CompiledHighlightSet.Rule rule) {
        SnippetManager manager = snippets.get();
        Snippet snippet = manager != null ? manager.findById(rule.snippetId()).orElse(null) : null;
        Preparation preparation = prepare(snippet, HighlightTriggerDispatcher.label(rule, I18n::get), manager,
            variables.get(), I18n::get);
        if (!preparation.ready()) {
            if (guard.tellOnce(widget, rule.ruleId() + '\u0000' + preparation.problemKey())) {
                tell(tab, widget, preparation.problem());
            }
            return;
        }
        TerminalView view = tab.getTerminalView();
        if (view == null || !view.sendInputLineToPane(widget, preparation.line(), preparation.generatedOneLiner())) {
            return;
        }
        guard.ran(widget, rule.ruleId());
        // Not counted as a use of the snippet: the library's counter is about what the user sends.
        logger.info("Highlight rule {} ran snippet {} in a terminal pane", rule.ruleId(), rule.snippetId());
        if (!PaneSeenOracle.isSeen(tab)) {
            tab.markAttention(I18n.get(RAN_KEY, snippetName(snippet, I18n::get)));
        }
    }

    /**
     * Asks whether the rule may run its snippet in the pane's connection. Not modal for the toolkit: the
     * answer arrives when the dialog closes, and closing it without a choice refuses.
     *
     * <p>The question appears when the server prints the pattern, possibly while the user types into the
     * terminal, so <b>Don't allow</b> is the default button and has the focus: an Enter or a space meant for
     * the shell refuses rather than allows. After an allow the pane is looked at again, as it may have changed
     * while the question was open.
     */
    private void confirm(TerminalTab tab, SithTermFxWidget widget, CompiledHighlightSet.Rule rule,
            HighlightSnippetTriggerGuard.Request request) {
        SnippetManager manager = snippets.get();
        Snippet snippet = manager != null ? manager.findById(rule.snippetId()).orElse(null) : null;
        if (snippet == null) {
            guard.withdraw(request);
            run(tab, widget, rule); // says that the snippet no longer exists
            return;
        }
        try {
            ButtonType allow = new ButtonType(I18n.get(CONFIRM_ALLOW_KEY), ButtonBar.ButtonData.OK_DONE);
            ButtonType deny = new ButtonType(I18n.get(CONFIRM_DENY_KEY), ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", allow, deny);
            DialogThemeHelper.applyTheme(alert);
            Button denyButton = refuseByDefault(alert, allow, deny);
            alert.setTitle(I18n.get(CONFIRM_TITLE_KEY));
            alert.setHeaderText(I18n.get(CONFIRM_TEXT_KEY, HighlightTriggerDispatcher.label(rule, I18n::get),
                snippetName(snippet, I18n::get), connectionName(tab, widget)));
            Label details = new Label(confirmDetails(I18n::get));
            details.setWrapText(true);
            TextArea preview = new TextArea(preview(snippet.getContent()));
            preview.setEditable(false);
            preview.setWrapText(false);
            preview.setPrefRowCount(Math.min(PREVIEW_LINES, Math.max(3, preview.getText().split("\n", -1).length)));
            preview.setStyle("-fx-font-family: monospace;");
            VBox content = new VBox(8, details, new Label(I18n.get(CONFIRM_PREVIEW_KEY)), preview);
            content.setPadding(new Insets(4, 0, 0, 0));
            content.setPrefWidth(520);
            alert.getDialogPane().setContent(content);
            Window owner = windowOf(tab);
            if (owner != null) {
                alert.initOwner(owner);
            }
            alert.setOnHidden(event -> {
                boolean allowed = alert.getResult() == allow;
                guard.answer(request, allowed);
                logger.info("Highlight rule {} {} to run snippet {} in a connection", rule.ruleId(),
                    allowed ? "allowed" : "not allowed", rule.snippetId());
                if (allowed) {
                    // The question may have been open for a while: what holds for the pane now decides.
                    HighlightSnippetTriggerGuard.Request now = request.withPaneFacts(mirroredInput(widget),
                        agentBusy(tab, widget), inputBusy(tab, widget));
                    handle(tab, widget, rule, now, guard.decide(now));
                }
            });
            if (denyButton != null) {
                alert.setOnShown(event -> denyButton.requestFocus());
            }
            alert.show();
        } catch (RuntimeException e) {
            guard.withdraw(request);
            logger.warn("Could not ask whether highlight rule {} may run its snippet: {}", rule.ruleId(), e.toString());
        }
    }

    /**
     * Makes {@code deny} the confirmation's default button and {@code allow} an ordinary one, so the key that
     * confirms a dialog refuses. Returns the deny button, or {@code null} when the dialog has none.
     */
    private static @Nullable Button refuseByDefault(Alert alert, ButtonType allow, ButtonType deny) {
        if (alert.getDialogPane().lookupButton(allow) instanceof Button allowButton) {
            allowButton.setDefaultButton(false);
        }
        if (alert.getDialogPane().lookupButton(deny) instanceof Button denyButton) {
            denyButton.setDefaultButton(true);
            return denyButton;
        }
        return null;
    }

    /** korTTY's own line in the pane, and the tab's mark when the user is not looking at it. */
    private static void tell(TerminalTab tab, SithTermFxWidget widget, @Nullable String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        TerminalView view = tab.getTerminalView();
        if (view != null) {
            view.showMessageInPane(widget, PANE_MESSAGE_PREFIX + message);
        }
        if (!PaneSeenOracle.isSeen(tab)) {
            tab.markAttention(message);
        }
    }

    /**
     * Makes {@code snippet} ready to type as Send to Terminal would — resolved with the stored values of its
     * variables, then as a one-liner where its language allows — or says why it cannot run: it no longer
     * exists, a declared variable has no stored value (a rule cannot ask), or it is empty or cannot be put on
     * a command line. Variables korTTY does not know stay as written, for the shell, as with Send to Terminal.
     *
     * @param ruleName the rule's name as shown ({@link HighlightTriggerDispatcher#label})
     * @param i18n     the translations, {@code I18n::get}
     */
    static Preparation prepare(@Nullable Snippet snippet, String ruleName, @Nullable SnippetManager manager,
            @Nullable SnippetVariableManager variables, BiFunction<String, Object[], String> i18n) {
        if (snippet == null || manager == null) {
            return Preparation.problem(MISSING_KEY, i18n.apply(MISSING_KEY, new Object[] {ruleName}));
        }
        String name = snippetName(snippet, i18n);
        String content = snippet.getContent() != null ? snippet.getContent() : "";
        for (String variable : manager.declaredVariables(content, variables)) {
            if (variables == null || variables.getValue(variable) == null) {
                return Preparation.problem(NEEDS_VALUE_KEY,
                    i18n.apply(NEEDS_VALUE_KEY, new Object[] {name, "${" + variable + "}"}));
            }
        }
        String resolved = manager.resolve(content, variables, Map.of()).text();
        String banner = i18n.apply(BANNER_KEY, new Object[] {name});
        String line = resolved == null || resolved.isBlank() ? null
            : SnippetTerminalSend.buildOneLinerPayloadForTerminal(resolved, snippet.getLanguage(), banner);
        if (line == null || line.isBlank()) {
            return Preparation.problem(UNSUPPORTED_KEY, i18n.apply(UNSUPPORTED_KEY, new Object[] {name}));
        }
        return new Preparation(line, SnippetOneLiner.isEmbeddedSupported(snippet.getLanguage()), null, null);
    }

    /** The snippet's name as shown: cleaned and shortened, or "(unnamed)". */
    static String snippetName(@Nullable Snippet snippet, BiFunction<String, Object[], String> i18n) {
        String name = DisplayTextSanitizer.sanitize(snippet != null ? snippet.getName() : null, MAX_NAME_CHARS);
        return name.isEmpty() ? i18n.apply(UNNAMED_SNIPPET_KEY, new Object[0]) : name;
    }

    /** What the confirmation says about the limits: the cooldown, the loop guard and how long it holds. */
    static String confirmDetails(BiFunction<String, Object[], String> i18n) {
        return i18n.apply(CONFIRM_DETAILS_KEY, new Object[] {HighlightSnippetTriggerGuard.COOLDOWN_MILLIS / 1_000L,
            HighlightSnippetTriggerGuard.MAX_RUNS_IN_A_ROW});
    }

    /** What the pane says when the loop guard stopped its snippet runs. */
    static String loopStoppedText(BiFunction<String, Object[], String> i18n) {
        return i18n.apply(LOOP_STOPPED_KEY, new Object[] {HighlightSnippetTriggerGuard.MAX_RUNS_IN_A_ROW,
            HighlightSnippetTriggerGuard.LOOP_WINDOW_MILLIS / 60_000L});
    }

    /** The start of the snippet for the confirmation: at most {@link #PREVIEW_LINES} lines and {@link #PREVIEW_CHARS} characters. */
    static String preview(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.length && i < PREVIEW_LINES; i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(lines[i]);
        }
        boolean cut = lines.length > PREVIEW_LINES;
        if (text.length() > PREVIEW_CHARS) {
            text.setLength(PREVIEW_CHARS);
            cut = true;
        }
        if (cut) {
            text.append("\n…");
        }
        return text.toString();
    }

    /** The connection the confirmation is remembered for: the saved connection, else what names the session. */
    private static String connectionKey(TerminalTab tab, SithTermFxWidget widget) {
        ServerConnection connection = connectionOf(tab, widget);
        String fallback = connection != null ? connection.getDisplayName() : tab.getEffectiveTitle();
        return HighlightSnippetTriggerGuard.connectionKey(connection != null ? connection.getId() : null, fallback);
    }

    private static String connectionName(TerminalTab tab, SithTermFxWidget widget) {
        ServerConnection connection = connectionOf(tab, widget);
        String name = DisplayTextSanitizer.sanitize(
            connection != null ? connection.getDisplayName() : tab.getEffectiveTitle(), MAX_NAME_CHARS);
        return name.isEmpty() ? TerminalAttentionNotifier.APP_NAME : name;
    }

    private static @Nullable ServerConnection connectionOf(TerminalTab tab, SithTermFxWidget widget) {
        try {
            TerminalView view = tab.getTerminalView();
            return view != null ? view.connectionOfPane(widget) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether broadcast mode or multi-exec wrote mirrored keys into the pane moments ago. */
    private static boolean mirroredInput(SithTermFxWidget widget) {
        try {
            return TerminalAttentionNotifier.receivedMirroredInput(widget);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a korTTY terminal-agent run or a detected coding agent works in the pane. */
    private static boolean agentBusy(TerminalTab tab, SithTermFxWidget widget) {
        try {
            TerminalView view = tab.getTerminalView();
            return (view != null && view.terminalAgentRunCount(widget) > 0)
                || TerminalAttentionNotifier.shared().codingAgentIn(tab, widget);
        } catch (RuntimeException e) {
            // Unknown counts as busy: typing into an agent's prompt is worse than a skipped run.
            return true;
        }
    }

    /**
     * Whether a full-screen program has the pane or a paste is being sent into it line by line, so a snippet
     * typed now would become the program's keystrokes or land inside the paste.
     */
    private static boolean inputBusy(TerminalTab tab, SithTermFxWidget widget) {
        try {
            TerminalView view = tab.getTerminalView();
            return view != null && view.isPaneBusyWithInput(widget);
        } catch (RuntimeException e) {
            // Unknown counts as busy, as for agents.
            return true;
        }
    }

    private static @Nullable Window windowOf(TerminalTab tab) {
        TabPane pane = tab.getTabPane();
        Scene scene = pane != null ? pane.getScene() : null;
        return scene != null ? scene.getWindow() : null;
    }

    private static @Nullable SnippetManager snippetManager() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getSnippetManager() : null;
    }

    private static @Nullable SnippetVariableManager variableManager() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getSnippetVariableManager() : null;
    }
}
