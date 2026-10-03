package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.KorTTYApplication;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import de.kortty.shellintegration.TerminalNotificationPolicy.Kind;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import de.kortty.shellintegration.TerminalNotificationPolicy.Toggles;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Turns a terminal pane's request for attention into what {@link TerminalNotificationPolicy}
 * decides: the attention mark on the pane's tab ({@link TerminalTab#markAttention}) and a desktop
 * notification through the application's {@link DesktopNotifier}.
 *
 * <p>The notification is titled {@code korTTY · <tab>}, as the Control API's notifications are, so
 * a program in a terminal can never make it look like a message from another application. It never
 * carries terminal text. Everything here runs on the JavaFX thread; the notifier delivers in the
 * background.
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
        GlobalSettings current = settings.get();
        Toggles toggles = new Toggles(current != null && current.isTerminalBellNotificationsEnabled(),
            current == null || current.isCodingAgentNotificationsEnabled());
        PaneState state = new PaneState(seen.test(tab), hasCodingAgent(tab, widget));
        Decision decision = policy.decide(Kind.BELL, widget, state, toggles);
        if (decision.badge()) {
            tab.markAttention(I18n.get("terminal.notify.bell.tooltip"));
        }
        if (decision.toast()) {
            show(toastTitle(tab.getEffectiveTitle()), I18n.get("terminal.notify.bell.body"));
        }
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
