package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.model.TerminalModelListener;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.TerminalActionPresentation;
import com.sithtermfx.ui.TerminalPanel;
import de.kortty.core.KorttyClipboard;
import de.kortty.shellintegration.CommandBlockStore;
import de.kortty.shellintegration.PromptNavigator;
import de.kortty.shellintegration.PromptNavigator.Direction;
import de.kortty.shellintegration.ShellIntegrationEvent;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyCombination;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shell integration for the panes of one terminal tab: keeps each pane's {@code OSC 133} command
 * marks ({@link PaneCommandMarks}) and moves between its prompts.
 *
 * <ul>
 *   <li>{@link #onEvent} receives a pane's events on its emulator thread, from
 *       {@link ShellIntegrationTtyConnector}, and records the marks at the cursor.</li>
 *   <li>{@link #attach} gives a pane its marks, a model listener that keeps them in step with the
 *       scrollback, and the Previous Prompt and Next Prompt key actions, which SithTermFX tries before
 *       its own. They use the keys {@link MainWindow} declares, so the menu shows the same keys and
 *       {@code MainWindowAcceleratorUniquenessTest} sees them. They are enabled only while the pane has
 *       prompt marks, shows its normal screen and shell integration is on; otherwise the key reaches
 *       the program in the pane as before (and broadcast mode mirrors it, like any other key).</li>
 *   <li>{@link #jump} scrolls a pane to its previous or next prompt, for the keys, the Edit menu and
 *       the pane's context menu alike.</li>
 *   <li>{@link #lastOutput} selects or copies what the pane's newest finished command printed, for
 *       the Edit menu and the context menu; neither has a key.</li>
 * </ul>
 *
 * <p>The setting is read on every event and key press, so switching shell integration off stops
 * recording and navigation at once. The marks are runtime-only: never saved, never sent anywhere.
 */
final class ShellIntegrationController {

    private static final Logger logger = LoggerFactory.getLogger(ShellIntegrationController.class);

    /** What a jump did, for the status line of the Edit menu items. */
    enum JumpResult {
        /** The view moved to the prompt, or back to the bottom. */
        JUMPED,
        /** There is no prompt above the starting line. */
        NO_TARGET,
        /** The pane has no prompt marks: its shell is not set up for shell integration. */
        NO_PROMPTS,
        /** A full-screen program uses the alternate screen, which has no prompts. */
        FULL_SCREEN,
        /** Shell integration is switched off in the settings. */
        DISABLED
    }

    /** Select Last Output or Copy Last Output. */
    enum LastOutputAction {
        /** Selects the output in the pane, as if dragged with the mouse. */
        SELECT,
        /** Copies the output; the pane's own selection stays as it is. */
        COPY
    }

    /** What Select or Copy Last Output did, with the status-line text that says so. */
    enum LastOutputResult {
        /** The output is selected. */
        SELECTED("terminal.shellIntegration.status.lastOutputSelected"),
        /** What is left of the output is selected; its start had left the scrollback. */
        SELECTED_TRUNCATED("terminal.shellIntegration.status.lastOutputSelectedTruncated"),
        /** The output is on the clipboard. */
        COPIED("terminal.shellIntegration.status.lastOutputCopied"),
        /** What is left of the output is on the clipboard; its start had left the scrollback. */
        COPIED_TRUNCATED("terminal.shellIntegration.status.lastOutputCopiedTruncated"),
        /** The newest finished command printed no text. */
        NO_OUTPUT("terminal.shellIntegration.status.noOutput"),
        /** No command finished yet, or its output left the scrollback. */
        NO_COMMAND("terminal.shellIntegration.status.noFinishedCommand"),
        /** The pane has no marks: its shell is not set up for shell integration. */
        NO_PROMPTS("terminal.shellIntegration.status.noPrompts"),
        /** A full-screen program uses the alternate screen. */
        FULL_SCREEN("terminal.shellIntegration.status.lastOutputFullScreen"),
        /** Shell integration is switched off in the settings. */
        DISABLED("terminal.shellIntegration.status.disabled");

        private final String statusKey;

        LastOutputResult(String statusKey) {
            this.statusKey = statusKey;
        }

        /** The i18n key of the status-line text. */
        String statusKey() {
            return statusKey;
        }
    }

    /** What the pane's context menu offers for shell integration; see {@link #contextMenuEntries}. */
    enum ContextMenuEntries {
        /** Nothing: shell integration is off, or the pane's emulation does not read the marks. */
        NONE,
        /** Previous Prompt and Next Prompt: the pane has prompt marks. */
        NAVIGATION,
        /** Set Up Shell Integration…: the pane could have marks but has none. */
        SETUP
    }

    private final Map<SithTermFxWidget, PaneCommandMarks> panes = new ConcurrentHashMap<>();
    private final Map<SithTermFxWidget, TerminalModelListener> modelListeners = new ConcurrentHashMap<>();
    private final BooleanSupplier enabled;
    private final KeyCombination previousPromptKey;
    private final KeyCombination nextPromptKey;

    /**
     * @param enabled           whether shell integration is on; asked on the emulator thread and the
     *                          FX thread
     * @param previousPromptKey the key of Previous Prompt, {@link MainWindow}'s constant
     * @param nextPromptKey     the key of Next Prompt, {@link MainWindow}'s constant
     */
    ShellIntegrationController(@NotNull BooleanSupplier enabled, @NotNull KeyCombination previousPromptKey,
            @NotNull KeyCombination nextPromptKey) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.previousPromptKey = Objects.requireNonNull(previousPromptKey, "previousPromptKey");
        this.nextPromptKey = Objects.requireNonNull(nextPromptKey, "nextPromptKey");
    }

    /** Sets {@code widget} up for shell integration; once per pane, on the FX thread, before its session starts. */
    void attach(@NotNull SithTermFxWidget widget) {
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        if (buffer == null) {
            return;
        }
        PaneCommandMarks marks = new PaneCommandMarks(buffer);
        if (panes.putIfAbsent(widget, marks) != null) {
            return;
        }
        TerminalModelListener listener = marks::observe;
        modelListeners.put(widget, listener);
        buffer.addModelListener(listener);
        if (widget instanceof KorttyTermWidget korttyWidget) {
            korttyWidget.setLeadingTerminalActions(promptActions(previousPromptKey, nextPromptKey,
                () -> canNavigate(widget), direction -> jump(widget, direction)));
        }
    }

    /** Releases what {@link #attach} set up; the pane closed. FX thread. */
    void detach(@Nullable SithTermFxWidget widget) {
        if (widget == null) {
            return;
        }
        PaneCommandMarks marks = panes.remove(widget);
        TerminalModelListener listener = modelListeners.remove(widget);
        if (listener != null && marks != null) {
            marks.buffer().removeModelListener(listener);
        }
        if (widget instanceof KorttyTermWidget korttyWidget) {
            korttyWidget.setLeadingTerminalActions(List.of());
        }
    }

    /** Releases every pane; the tab closed. */
    void detachAll() {
        for (SithTermFxWidget widget : List.copyOf(panes.keySet())) {
            detach(widget);
        }
    }

    /**
     * A pane's event, on its emulator thread at the point of the output where it stood. Records the
     * {@code OSC 133} marks while shell integration is on; everything else is left to others.
     */
    void onEvent(@NotNull SithTermFxWidget widget, @NotNull ShellIntegrationEvent event) {
        if (!PaneCommandMarks.isMark(event) || !isEnabled()) {
            return;
        }
        PaneCommandMarks marks = panes.get(widget);
        Terminal terminal = widget.getTerminal();
        if (marks == null || terminal == null) {
            return;
        }
        marks.record(event, terminal, System.nanoTime());
    }

    /** Whether shell integration is on; a failing settings lookup counts as on, its default. */
    boolean isEnabled() {
        try {
            return enabled.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Whether {@code widget} has prompt marks in its scrollback. Lock-free, any thread. */
    boolean hasPrompts(@Nullable SithTermFxWidget widget) {
        PaneCommandMarks marks = widget != null ? panes.get(widget) : null;
        return marks != null && marks.store().hasPrompts();
    }

    /**
     * Whether the prompt keys act in {@code widget} now: shell integration is on, the pane has
     * prompt marks and shows its normal screen. Lock-free; SithTermFX asks it on every matching key.
     */
    boolean canNavigate(@Nullable SithTermFxWidget widget) {
        PaneCommandMarks marks = widget != null ? panes.get(widget) : null;
        return marks != null && marks.store().hasPrompts() && !marks.buffer().isUsingAlternateBuffer() && isEnabled();
    }

    /**
     * What the context menu of {@code widget} offers: the prompt entries while it has prompt marks,
     * otherwise the entry that explains the setup.
     */
    ContextMenuEntries contextMenuEntries(@NotNull SithTermFxWidget widget) {
        return contextMenuEntries(isEnabled(),
            ShellIntegrationTtyConnector.appliesTo(widget.getEmulationType()), hasPrompts(widget));
    }

    /**
     * What the context menu offers: nothing while shell integration is off or the pane's emulation
     * cannot carry the marks (Wyse, IBM 3270 and the like), the prompt entries while the pane has
     * prompt marks, and the setup entry otherwise.
     */
    static ContextMenuEntries contextMenuEntries(boolean enabled, boolean emulationReadsMarks, boolean hasPrompts) {
        if (!enabled || !emulationReadsMarks) {
            return ContextMenuEntries.NONE;
        }
        return hasPrompts ? ContextMenuEntries.NAVIGATION : ContextMenuEntries.SETUP;
    }

    /**
     * Scrolls {@code widget} to its previous or next prompt (see {@link PromptNavigator}): the prompt
     * shows at the top of the view, or as close to the top as the scrollback allows. Next Prompt after
     * the last prompt goes back to the bottom. FX thread.
     */
    JumpResult jump(@NotNull SithTermFxWidget widget, @NotNull Direction direction) {
        if (!isEnabled()) {
            return JumpResult.DISABLED;
        }
        PaneCommandMarks marks = panes.get(widget);
        TerminalPanel panel = widget.getTerminalPanel();
        Terminal terminal = widget.getTerminal();
        if (marks == null || panel == null || terminal == null) {
            return JumpResult.NO_PROMPTS;
        }
        TerminalTextBuffer buffer = marks.buffer();
        CommandBlockStore store = marks.store();
        ScrollBar scrollBar = panel.getScrollBar();
        int origin = panel.getScrollOrigin();
        PromptNavigator.Jump jump;
        long cursorLine;
        long targetLine = 0;
        int history;
        buffer.lock();
        try {
            if (buffer.isUsingAlternateBuffer()) {
                return JumpResult.FULL_SCREEN;
            }
            marks.syncTrims();
            if (!store.hasPrompts()) {
                return JumpResult.NO_PROMPTS;
            }
            history = buffer.getHistoryLinesCount();
            int height = Math.max(1, buffer.getHeight());
            int cursorRow = Math.max(0, Math.min(height - 1, terminal.getCursorY() - 1));
            cursorLine = store.lineId(history + cursorRow);
            int visibleOrigin = Math.max(-history, Math.min(0, origin));
            OptionalLong current = store.currentPrompt();
            PromptNavigator.View view = new PromptNavigator.View(cursorLine,
                current.isPresent() ? current.getAsLong() : null, store.lineId(history + visibleOrigin),
                visibleOrigin == 0, scrollBar.getValue());
            jump = marks.navigator().jump(direction, store, view);
            if (jump instanceof PromptNavigator.Jump.ToPrompt toPrompt) {
                targetLine = store.absoluteLine(toPrompt.line());
            }
        } finally {
            buffer.unlock();
        }
        return switch (jump) {
            case PromptNavigator.Jump.Stay stay -> JumpResult.NO_TARGET;
            case PromptNavigator.Jump.ToBottom bottom -> {
                scrollBar.setValue(scrollBar.getMax());
                marks.navigator().reset();
                yield JumpResult.JUMPED;
            }
            case PromptNavigator.Jump.ToPrompt toPrompt -> {
                int targetOrigin = TerminalScrollSupport.originForLine(targetLine, history);
                OptionalDouble value = TerminalScrollSupport.scrollValueForOrigin(targetOrigin,
                    scrollBar.getMin(), scrollBar.getMax(), scrollBar.getVisibleAmount());
                if (value.isPresent()) {
                    scrollBar.setValue(value.getAsDouble());
                }
                marks.navigator().jumped(toPrompt.line(), scrollBar.getValue(), cursorLine);
                logger.trace("Prompt jump {} to origin {}", direction, targetOrigin);
                yield JumpResult.JUMPED;
            }
        };
    }

    /**
     * Whether {@code widget} has a finished command whose output Select and Copy Last Output can
     * reach. A quick look for the context menu; {@link #lastOutput} checks again.
     */
    boolean hasFinishedCommand(@Nullable SithTermFxWidget widget) {
        PaneCommandMarks marks = widget != null ? panes.get(widget) : null;
        return marks != null && marks.store().lastFinished().isPresent();
    }

    /**
     * Select Last Output / Copy Last Output: the text the newest finished command of {@code widget}
     * printed, from its {@code OSC 133;C} mark to its {@code D} mark (see
     * {@link de.kortty.shellintegration.LastOutputRange}).
     *
     * <ul>
     *   <li>{@link LastOutputAction#SELECT} sets the pane's selection to it, so Copy and copy on
     *       select work on it as on a selection made with the mouse.</li>
     *   <li>{@link LastOutputAction#COPY} reads the text the way SithTermFX's Copy reads a selection
     *       and puts it on the clipboard through {@link KorttyClipboard}, which keeps it inside korTTY
     *       when the policy says so. The pane's own selection is left alone.</li>
     * </ul>
     *
     * When the start of the output has left the scrollback, the rest is used and the result says
     * so. FX thread.
     */
    LastOutputResult lastOutput(@NotNull SithTermFxWidget widget, @NotNull LastOutputAction action) {
        if (!isEnabled()) {
            return LastOutputResult.DISABLED;
        }
        PaneCommandMarks marks = panes.get(widget);
        TerminalPanel panel = widget.getTerminalPanel();
        if (marks == null || panel == null) {
            return LastOutputResult.NO_PROMPTS;
        }
        PaneCommandMarks.LastOutput output = marks.lastOutput(action == LastOutputAction.COPY);
        return switch (output.status()) {
            case NO_MARKS -> LastOutputResult.NO_PROMPTS;
            case NO_COMMAND -> LastOutputResult.NO_COMMAND;
            case NO_OUTPUT -> LastOutputResult.NO_OUTPUT;
            case FULL_SCREEN -> LastOutputResult.FULL_SCREEN;
            case FOUND -> {
                boolean truncated = output.range() != null && output.range().truncated();
                if (action == LastOutputAction.COPY) {
                    KorttyClipboard.setText(output.text());
                    yield truncated ? LastOutputResult.COPIED_TRUNCATED : LastOutputResult.COPIED;
                }
                panel.selectionProperty().set(output.selection());
                panel.repaint();
                yield truncated ? LastOutputResult.SELECTED_TRUNCATED : LastOutputResult.SELECTED;
            }
        };
    }

    /**
     * Previous Prompt and Next Prompt as SithTermFX key actions on {@code previousKey} and
     * {@code nextKey}. They are hidden, since korTTY builds the context menu itself, and enabled while
     * {@code available} says so; a disabled action leaves the key to the program in the pane.
     */
    static List<TerminalAction> promptActions(@NotNull KeyCombination previousKey, @NotNull KeyCombination nextKey,
            @NotNull BooleanSupplier available, @NotNull Consumer<Direction> jump) {
        TerminalAction previous = new TerminalAction(new TerminalActionPresentation("Previous Prompt", previousKey),
            event -> {
                jump.accept(Direction.PREVIOUS);
                return true;
            }).withEnabledSupplier(available::getAsBoolean).withHidden(true);
        TerminalAction next = new TerminalAction(new TerminalActionPresentation("Next Prompt", nextKey),
            event -> {
                jump.accept(Direction.NEXT);
                return true;
            }).withEnabledSupplier(available::getAsBoolean).withHidden(true);
        return List.of(previous, next);
    }
}
