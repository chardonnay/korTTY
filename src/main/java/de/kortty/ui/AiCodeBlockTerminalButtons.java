package de.kortty.ui;

import de.kortty.model.AiChatTerminalActions;
import de.kortty.paste.PasteGuard;
import de.kortty.telemetry.AiCodeBlockTelemetry;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The <i>Insert</i> and <i>Run</i> buttons in the header of an AI chat code block (design decisions D1, D3,
 * D20). Which buttons a block offers comes from {@link AiCodeBlockTerminalAction#offers}; whether one can act
 * now comes from {@link AiCodeBlockTerminalAction#decide}, asked when the buttons are built, whenever the mouse
 * enters them, and again right before anything is sent. A button that cannot act is greyed out, and its reason
 * is the tooltip; because JavaFX shows no tooltip on a disabled control, the tooltip sits on a wrapper around
 * the button ({@link #nodes()}).
 *
 * <p>Insert types the block, without the line breaks it ends with, at the target pane's prompt through paste
 * protection ({@code PasteSource.AI}), so it never presses Enter. Run asks first, with a confirmation that
 * names the pane and shows the exact line ({@link Host#confirmRun}), then sends that one line followed by
 * Enter to that pane only.
 *
 * <p>Everything outside the decision goes through {@link Host}, so a headless smoke can build the buttons with
 * a stub policy and no terminal. FX thread.
 */
final class AiCodeBlockTerminalButtons {

    /** What the buttons need from the chat they are in. */
    interface Host {

        /** The user's setting: which actions code blocks offer. */
        AiChatTerminalActions setting();

        /** The enterprise policy in force. */
        AiCodeBlockTerminalAction.Policy policy();

        /** The pane the block would go to ({@link AiCodeBlockTerminalAction#resolveTarget}), or none. */
        @Nullable TerminalPaneRef target();

        /** A snapshot of {@code target} ({@link AiCodeBlockTerminalAction.PaneState#probe}). */
        AiCodeBlockTerminalAction.PaneState probe(TerminalPaneRef target);

        /**
         * Types {@code text} at the prompt of {@code target} through paste protection, without Enter, and
         * tells {@code outcome} how it ended: at once, or once paste protection's confirmation is answered.
         */
        void insert(TerminalPaneRef target, String text, Consumer<PasteGuard.Outcome> outcome);

        /**
         * Asks whether {@code line} should run in {@code target}, showing both and what {@code decision} adds
         * (a foreign session, an unknown prompt). Cancel is the default answer.
         */
        boolean confirmRun(TerminalPaneRef target, String line, AiCodeBlockTerminalAction.Decision decision);

        /** Sends {@code line} and Enter to {@code target} only; whether it was handed to the session. */
        boolean sendLine(TerminalPaneRef target, String line);

        /** Shows {@code message} where the chat reports what happened. */
        void status(String message);
    }

    private final String code;
    private final Host host;
    private final @Nullable Button insertButton;
    private final @Nullable Button runButton;
    private final @Nullable Tooltip insertTooltip;
    private final @Nullable Tooltip runTooltip;
    private final List<Node> nodes = new ArrayList<>();

    private AiCodeBlockTerminalButtons(String language, String code, Host host) {
        this.code = code != null ? code : "";
        this.host = Objects.requireNonNull(host, "host");
        AiChatTerminalActions setting = readSetting(host);
        if (AiCodeBlockTerminalAction.offers(AiCodeBlockTerminalAction.Action.INSERT, setting, language, this.code)) {
            insertButton = button("ai.result.terminal.insert", "ai-chat-code-insert");
            insertTooltip = new Tooltip();
            insertButton.setOnAction(event -> insert());
            nodes.add(wrap(insertButton, insertTooltip));
        } else {
            insertButton = null;
            insertTooltip = null;
        }
        if (AiCodeBlockTerminalAction.offers(AiCodeBlockTerminalAction.Action.RUN, setting, language, this.code)) {
            runButton = button("ai.result.terminal.run", "ai-chat-code-run");
            runTooltip = new Tooltip();
            runButton.setOnAction(event -> run());
            nodes.add(wrap(runButton, runTooltip));
        } else {
            runButton = null;
            runTooltip = null;
        }
        refresh();
    }

    /**
     * The buttons a block of {@code language} with {@code code} offers, or {@code null} when it offers none
     * (the setting is {@link AiChatTerminalActions#OFF}).
     */
    static @Nullable AiCodeBlockTerminalButtons create(@Nullable String language, @Nullable String code, Host host) {
        AiCodeBlockTerminalButtons buttons = new AiCodeBlockTerminalButtons(language, code, host);
        return buttons.nodes.isEmpty() ? null : buttons;
    }

    /** The nodes to put in the block's header: each button inside the wrapper that carries its tooltip. */
    List<Node> nodes() {
        return List.copyOf(nodes);
    }

    @Nullable Button insertButton() {
        return insertButton;
    }

    @Nullable Button runButton() {
        return runButton;
    }

    /** The tooltip text of Insert: what it does to which pane, or why it is greyed out. */
    @Nullable String insertTooltipText() {
        return insertTooltip != null ? insertTooltip.getText() : null;
    }

    /** The tooltip text of Run: what it does to which pane, or why it is greyed out. */
    @Nullable String runTooltipText() {
        return runTooltip != null ? runTooltip.getText() : null;
    }

    /** Asks for the verdicts again and greys out, or enables, the buttons with the reason as the tooltip. */
    void refresh() {
        TerminalPaneRef target = safeTarget();
        AiCodeBlockTerminalAction.PaneState state = target != null ? safeProbe(target) : null;
        String name = target != null ? target.displayName() : "";
        if (insertButton != null) {
            show(insertButton, insertTooltip, decide(AiCodeBlockTerminalAction.Action.INSERT, state),
                "ai.result.terminal.insert.tooltip", name);
        }
        if (runButton != null) {
            show(runButton, runTooltip, decide(AiCodeBlockTerminalAction.Action.RUN, state),
                "ai.result.terminal.run.tooltip", name);
        }
    }

    /** Insert: the block without its closing line breaks, at the target's prompt through paste protection. */
    void insert() {
        TerminalPaneRef target = safeTarget();
        AiCodeBlockTerminalAction.Decision decision = decide(AiCodeBlockTerminalAction.Action.INSERT,
            target != null ? safeProbe(target) : null);
        if (target == null || !decision.proceeds()) {
            refuse(AiCodeBlockTelemetry.Action.INSERT, decision, target);
            return;
        }
        String name = target.displayName();
        AtomicBoolean reported = new AtomicBoolean();
        host.insert(target, AiCodeBlockTerminalAction.runLine(code), outcome -> {
            if (reported.compareAndSet(false, true)) {
                inserted(outcome, name);
            }
        });
    }

    /**
     * What Insert reports once paste protection is done: only text that really reached the pane counts as
     * inserted; a declined confirmation or a paste the pane refused says that nothing was inserted.
     */
    private void inserted(@Nullable PasteGuard.Outcome outcome, String name) {
        if (outcome == PasteGuard.Outcome.SENT) {
            AiCodeBlockTelemetry.track(AiCodeBlockTelemetry.Action.INSERT, AiCodeBlockTelemetry.Outcome.SENT);
            host.status(I18n.get("ai.result.terminal.status.inserted", name));
        } else {
            AiCodeBlockTelemetry.track(AiCodeBlockTelemetry.Action.INSERT,
                outcome == PasteGuard.Outcome.CANCELLED
                    ? AiCodeBlockTelemetry.Outcome.CANCELLED : AiCodeBlockTelemetry.Outcome.REFUSED);
            host.status(I18n.get("ai.result.terminal.status.notInserted", name));
        }
        refresh();
    }

    /** Run: after the confirmation, the single line and Enter to the target only. */
    void run() {
        TerminalPaneRef target = safeTarget();
        AiCodeBlockTerminalAction.Decision decision = decide(AiCodeBlockTerminalAction.Action.RUN,
            target != null ? safeProbe(target) : null);
        if (target == null || !decision.proceeds()) {
            refuse(AiCodeBlockTelemetry.Action.RUN, decision, target);
            return;
        }
        String line = AiCodeBlockTerminalAction.runLine(code);
        if (!host.confirmRun(target, line, decision)) {
            AiCodeBlockTelemetry.track(AiCodeBlockTelemetry.Action.RUN, AiCodeBlockTelemetry.Outcome.CANCELLED);
            host.status(I18n.get("ai.result.terminal.status.cancelled"));
            refresh();
            return;
        }
        // The dialog was open for a while: the pane may have started a program, closed or been mirrored.
        AiCodeBlockTerminalAction.Decision again = decide(AiCodeBlockTerminalAction.Action.RUN,
            target.isOpen() ? safeProbe(target) : null);
        if (!again.proceeds()) {
            refuse(AiCodeBlockTelemetry.Action.RUN, again, target);
            return;
        }
        if (host.sendLine(target, line)) {
            AiCodeBlockTelemetry.track(AiCodeBlockTelemetry.Action.RUN, AiCodeBlockTelemetry.Outcome.SENT);
            host.status(I18n.get("ai.result.terminal.status.ran", target.displayName()));
        } else {
            AiCodeBlockTelemetry.track(AiCodeBlockTelemetry.Action.RUN, AiCodeBlockTelemetry.Outcome.REFUSED);
            host.status(I18n.get("ai.result.terminal.status.failed", target.displayName()));
        }
        refresh();
    }

    private AiCodeBlockTerminalAction.Decision decide(AiCodeBlockTerminalAction.Action action,
            @Nullable AiCodeBlockTerminalAction.PaneState state) {
        AiCodeBlockTerminalAction.Policy policy;
        try {
            policy = host.policy();
        } catch (RuntimeException e) {
            policy = null;
        }
        if (policy == null) {
            policy = AiCodeBlockTerminalAction.Policy.of(null);
        }
        return AiCodeBlockTerminalAction.decide(action, code, policy, state);
    }

    private void refuse(AiCodeBlockTelemetry.Action action, AiCodeBlockTerminalAction.Decision decision,
            @Nullable TerminalPaneRef target) {
        AiCodeBlockTelemetry.track(action, AiCodeBlockTelemetry.Outcome.REFUSED);
        AiCodeBlockTerminalAction.Verdict verdict = decision.proceeds()
            ? AiCodeBlockTerminalAction.Verdict.NO_TARGET : decision.verdict();
        host.status(I18n.get(verdict.messageKey(), target != null ? target.displayName() : ""));
        refresh();
    }

    private static void show(Button button, Tooltip tooltip, AiCodeBlockTerminalAction.Decision decision,
            String okKey, String name) {
        button.setDisable(!decision.proceeds());
        String text = decision.proceeds()
            ? I18n.get(okKey, name)
            : I18n.get(decision.verdict().messageKey(), name);
        if (decision.verdict() == AiCodeBlockTerminalAction.Verdict.FOREIGN_SESSION_CONFIRM) {
            text = text + "\n" + I18n.get(decision.verdict().messageKey(), name);
        }
        tooltip.setText(text);
    }

    private @Nullable TerminalPaneRef safeTarget() {
        try {
            return host.target();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private @Nullable AiCodeBlockTerminalAction.PaneState safeProbe(TerminalPaneRef target) {
        try {
            return host.probe(target);
        } catch (RuntimeException e) {
            // Unreadable: nothing to send to, so the buttons grey out instead of guessing.
            return null;
        }
    }

    private static AiChatTerminalActions readSetting(Host host) {
        try {
            AiChatTerminalActions setting = host.setting();
            return setting != null ? setting : AiChatTerminalActions.DEFAULT;
        } catch (RuntimeException e) {
            return AiChatTerminalActions.DEFAULT;
        }
    }

    private static Button button(String key, String styleClass) {
        Button button = new Button(I18n.get(key));
        button.getStyleClass().addAll("ai-chat-icon-button", styleClass);
        button.setStyle("-fx-padding: 3 10 3 10;");
        button.setFocusTraversable(false);
        return button;
    }

    private Node wrap(Button button, Tooltip tooltip) {
        StackPane wrapper = new StackPane(button);
        wrapper.setPickOnBounds(true);
        Tooltip.install(wrapper, tooltip);
        // Read the pane again when the pointer arrives: its state changes while the chat is open.
        wrapper.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> refresh());
        return wrapper;
    }
}
