package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.KorTTYApplication;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.CommandStatus;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import de.kortty.shellintegration.TerminalNotificationPolicy.Kind;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import de.kortty.shellintegration.TerminalNotificationPolicy.Toggles;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Turns a terminal pane's request for attention into what {@link TerminalNotificationPolicy}
 * decides: the attention mark on the pane's tab ({@link TerminalTab#markAttention}) and a desktop
 * notification through the application's {@link DesktopNotifier}. The requests are a bell
 * ({@link #onBell}) and a long command the shell marked finished ({@link #onCommandFinished}).
 *
 * <p>The notification is titled {@code korTTY · <tab>}, as the Control API's notifications are, so
 * a program in a terminal can never make it look like a message from another application. It never
 * carries terminal text, and the one for a finished command never names the command. Everything
 * here runs on the JavaFX thread; the notifier delivers in the background.
 */
public final class TerminalAttentionNotifier {

    private static final Logger logger = LoggerFactory.getLogger(TerminalAttentionNotifier.class);

    /** The title of a terminal notification for a tab without a visible name. */
    static final String APP_NAME = "korTTY";

    /** The prefix of every other terminal notification's title. */
    static final String TITLE_PREFIX = APP_NAME + " · ";

    /** At most this many characters of the tab's name go into a notification's title. */
    static final int MAX_TAB_NAME_CHARS = 80;

    private static @Nullable TerminalAttentionNotifier shared;

    private final TerminalNotificationPolicy policy;

    private final Predicate<TerminalTab> seen;

    private final Supplier<GlobalSettings> settings;

    private final Supplier<DesktopNotifier> notifier;

    private final Supplier<CodingAgentRegistry> codingAgents;

    TerminalAttentionNotifier(TerminalNotificationPolicy policy, Predicate<TerminalTab> seen,
            Supplier<GlobalSettings> settings, Supplier<DesktopNotifier> notifier,
            Supplier<CodingAgentRegistry> codingAgents) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.codingAgents = Objects.requireNonNull(codingAgents, "codingAgents");
    }

    /** The notifier of the running application, for every window. JavaFX thread. */
    static TerminalAttentionNotifier shared() {
        TerminalAttentionNotifier notifier = shared;
        if (notifier == null) {
            notifier = new TerminalAttentionNotifier(new TerminalNotificationPolicy(), PaneSeenOracle::isSeen,
                TerminalAttentionNotifier::currentSettings, TerminalAttentionNotifier::desktopNotifier,
                TerminalAttentionNotifier::codingAgentRegistry);
            shared = notifier;
        }
        return notifier;
    }

    /**
     * A program in {@code widget}, a pane of {@code tab}, rang the bell, once or several times since
     * the last call. JavaFX thread.
     */
    public void onBell(TerminalTab tab, SithTermFxWidget widget) {
        if (tab == null || widget == null) {
            return;
        }
        Toggles toggles = toggles(settings.get());
        PaneState state = new PaneState(seen.test(tab), hasCodingAgent(tab, widget), false);
        Decision decision = policy.decide(Kind.BELL, widget, state, toggles);
        if (decision.badge()) {
            tab.markAttention(I18n.get("terminal.notify.bell.tooltip"));
        }
        if (decision.toast()) {
            show(toastTitle(tab.getEffectiveTitle()), I18n.get("terminal.notify.bell.body"));
        }
    }

    /**
     * A command the shell marked with OSC 133 finished in {@code widget}, a pane of {@code tab}, with
     * {@code status}. When it ran at least the threshold of the settings in a tab the user is not
     * looking at, the tab gets its mark and, with the setting on, a desktop notification says how the
     * command ended and how long it ran; a command a terminal-agent run typed leads to nothing. The
     * text never contains the command: command lines can hold passwords and tokens, and a
     * notification can show on the lock screen. JavaFX thread.
     */
    public void onCommandFinished(TerminalTab tab, SithTermFxWidget widget, CommandStatus status) {
        Duration runtime = status != null ? status.runtime() : null;
        if (tab == null || widget == null || runtime == null) {
            return;
        }
        // Not consulted for a finished command: a coding agent is itself the command that ended.
        boolean codingAgentPane = false;
        PaneState state = new PaneState(seen.test(tab), codingAgentPane, agentRunIn(tab, widget));
        Decision decision = policy.decideCommandFinished(tab, runtime, state, toggles(settings.get()));
        if (!decision.badge() && !decision.toast()) {
            return;
        }
        String text = commandFinishedText(status.exitStatus(),
            TimestampGutter.currentFormats().verboseRuntime(runtime), I18n::get);
        if (decision.badge()) {
            tab.markAttention(text);
        }
        if (decision.toast()) {
            show(toastTitle(tab.getEffectiveTitle()), text);
        }
    }

    /**
     * The text of a finished command's notification and tab tooltip: how it ended and how long it
     * ran, for example {@code Command failed (exit 1) after 2 min 14 sec.} Nothing else: no command,
     * no output.
     *
     * @param exitStatus the status the shell reported, or {@code null} when it reported none
     * @param runtime    the runtime, already worded ({@link TimestampGutterFormats#verboseRuntime})
     * @param i18n       the translations, {@code I18n::get}
     */
    static String commandFinishedText(@Nullable Integer exitStatus, String runtime,
            BiFunction<String, Object[], String> i18n) {
        if (exitStatus == null) {
            return i18n.apply("terminal.notify.commandFinished.noStatus", new Object[] {runtime});
        }
        String key = exitStatus == 0 ? "terminal.notify.commandFinished.succeeded"
            : "terminal.notify.commandFinished.failed";
        return i18n.apply(key, new Object[] {exitStatus, runtime});
    }

    /**
     * The settings the policy needs, from {@code current}; a fresh installation's when they cannot
     * be read.
     */
    static Toggles toggles(@Nullable GlobalSettings current) {
        GlobalSettings effective = current != null ? current : new GlobalSettings();
        return new Toggles(effective.isTerminalBellNotificationsEnabled(),
            effective.isCodingAgentNotificationsEnabled(), effective.isCommandFinishedNotificationsEnabled(),
            effective.getCommandFinishedNotificationSeconds());
    }

    /**
     * The title of a notification for a tab: {@link #TITLE_PREFIX} and the tab's name without
     * control or bidi characters, at most {@link #MAX_TAB_NAME_CHARS} of them; {@link #APP_NAME}
     * alone when the name has nothing visible.
     */
    static String toastTitle(@Nullable String tabName) {
        String name = DisplayTextSanitizer.sanitize(tabName, MAX_TAB_NAME_CHARS);
        return name.isEmpty() ? APP_NAME : TITLE_PREFIX + name;
    }

    private void show(String title, String body) {
        DesktopNotifier desktop = notifier.get();
        if (desktop != null) {
            desktop.notify(title, body);
        }
    }

    /** Whether a coding agent was detected in the pane, so its own notifications speak for it. */
    private boolean hasCodingAgent(TerminalTab tab, SithTermFxWidget widget) {
        CodingAgentRegistry registry = codingAgents.get();
        TerminalView view = tab.getTerminalView();
        if (registry == null || view == null) {
            return false;
        }
        try {
            Optional<PaneRef> pane = view.paneRefOf(widget);
            return pane.isPresent() && registry.entry(pane.get()).isPresent();
        } catch (RuntimeException e) {
            logger.debug("Coding-agent lookup for a bell failed: {}", e.toString());
            return false;
        }
    }

    /** Whether a korTTY terminal-agent run drives the pane, typing the commands that run there. */
    private boolean agentRunIn(TerminalTab tab, SithTermFxWidget widget) {
        TerminalView view = tab.getTerminalView();
        try {
            return view != null && view.terminalAgentRunCount(widget) > 0;
        } catch (RuntimeException e) {
            logger.debug("Terminal-agent lookup for a finished command failed: {}", e.toString());
            return false;
        }
    }

    private static @Nullable GlobalSettings currentSettings() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
            return manager != null ? manager.getSettings() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable DesktopNotifier desktopNotifier() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getDesktopNotifier() : null;
    }

    private static @Nullable CodingAgentRegistry codingAgentRegistry() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getCodingAgentRegistry() : null;
    }
}
