package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.KorTTYApplication;
import de.kortty.codingagent.AgentSummary;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentNavigator;
import de.kortty.codingagent.CodingAgentNotificationCoordinator;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.FocusOracle;
import de.kortty.codingagent.PaneAccess;
import de.kortty.codingagent.PaneLocation;
import de.kortty.codingagent.PaneLocator;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RegistryChange;
import de.kortty.codingagent.desktop.AppBadgeService;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.StageIconPresenter;
import de.kortty.codingagent.desktop.TitleBadgePresenter;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.model.GlobalSettings;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The one place where the JavaFX-free coding-agent core (registry, actions, navigator,
 * notification coordinator) and the desktop services (badge, notifier) meet the windows: every
 * port they need — {@link FocusOracle}, {@link PaneLocator}, {@link PaneAccess},
 * {@link CodingAgentNavigator.PaneFocuser}, {@link StageIconPresenter}, {@link TitleBadgePresenter}
 * — is answered here by walking {@code MainWindow.getOpenWindows()} on the FX thread. Nothing is
 * cached: a tab can move between windows and a pane can close at any time, so every call resolves
 * window, tab and widget afresh.
 *
 * <p>The bridge is also the registry's single fan-out listener: it updates the badge, forwards the
 * change to the notification coordinator and asks every window to refresh its tab titles and status
 * strip, and it runs the 1-second timeline that settles pending notifications and ticks the panel
 * durations while at least one agent is known.
 */
public final class CodingAgentUiBridge implements FocusOracle, PaneLocator, PaneAccess,
    CodingAgentNavigator.PaneFocuser, StageIconPresenter, TitleBadgePresenter, CodingAgentRegistry.Listener {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentUiBridge.class);
    private static final String PLAIN_ICON_RESOURCE = "/icon/kortty_icon.png";
    private static final int NOTIFICATION_EVIDENCE_CHARS = 120;

    /** Title and body of one desktop notification. */
    record Notification(String title, String body) {}

    private final CodingAgentRegistry registry;
    private final AppBadgeService badge;
    private final CodingAgentNotificationCoordinator coordinator;
    private final DesktopNotifier notifier;
    private final Supplier<List<MainWindow>> windows;
    private final Supplier<GlobalSettings> settings;
    private final BiFunction<String, Object[], String> messages;
    private AutoCloseable registryHandle;
    private Timeline tickTimeline;
    private Image plainIcon;
    private boolean started;

    /**
     * @param registry the application registry (listened to from {@link #start()})
     * @param badge the app-icon badge service, may be {@code null} (badge disabled)
     * @param coordinator the notification policy, may be {@code null} (notifications disabled)
     * @param notifier the desktop notifier, may be {@code null}
     * @param windows the live open-window list (never cached)
     * @param settings the current global settings
     */
    public CodingAgentUiBridge(CodingAgentRegistry registry, AppBadgeService badge,
                               CodingAgentNotificationCoordinator coordinator, DesktopNotifier notifier,
                               Supplier<List<MainWindow>> windows, Supplier<GlobalSettings> settings) {
        this(registry, badge, coordinator, notifier, windows, settings, I18n::get);
    }

    CodingAgentUiBridge(CodingAgentRegistry registry, AppBadgeService badge,
                        CodingAgentNotificationCoordinator coordinator, DesktopNotifier notifier,
                        Supplier<List<MainWindow>> windows, Supplier<GlobalSettings> settings,
                        BiFunction<String, Object[], String> messages) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.badge = badge;
        this.coordinator = coordinator;
        this.notifier = notifier;
        this.windows = Objects.requireNonNull(windows, "windows");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Installs this bridge as the registry's focus oracle and fan-out listener; FX thread. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        registry.setFocusOracle(this);
        registryHandle = registry.addListener(this);
        updateTickTimer();
    }

    /** Unsubscribes from the registry and stops the timeline; idempotent. */
    public void stop() {
        started = false;
        if (registryHandle != null) {
            try {
                registryHandle.close();
            } catch (Exception e) {
                logger.debug("Registry listener could not be closed: {}", e.toString());
            }
            registryHandle = null;
        }
        if (tickTimeline != null) {
            tickTimeline.stop();
            tickTimeline = null;
        }
    }

    /** Re-evaluates done-until-seen for every entry (tab switch, pane focus, window focus). */
    public void reconcileSeen() {
        if (Platform.isFxApplicationThread()) {
            registry.reconcileSeen();
        } else {
            Platform.runLater(registry::reconcileSeen);
        }
    }

    /** The sink that turns a coordinator decision into a desktop notification. */
    public CodingAgentNotificationCoordinator.Sink notificationSink() {
        return (entry, state, anyWindowFocused) -> {
            if (entry == null) {
                return;
            }
            Notification notification = buildNotification(entry, state, locate(entry.pane()).orElse(null), messages);
            if (notifier != null) {
                notifier.notify(notification.title(), notification.body());
            }
        };
    }

    // ---- registry fan-out ---------------------------------------------------------------------

    @Override
    public void onRegistryChanged(RegistryChange change) {
        if (change == null) {
            return;
        }
        AgentSummary summary = registry.summary();
        if (badge != null) {
            try {
                boolean urgent = change.entered(CodingAgentState.BLOCKED) && !isAnyWindowFocused();
                badge.update(summary != null ? summary.blocked() : 0, urgent);
            } catch (RuntimeException e) {
                logger.debug("App badge update failed: {}", e.toString());
            }
        }
        if (coordinator != null) {
            coordinator.onRegistryChanged(change);
        }
        for (MainWindow window : snapshotWindows()) {
            try {
                window.onCodingAgentsChanged();
            } catch (RuntimeException e) {
                logger.debug("Window refresh after a coding-agent change failed: {}", e.toString());
            }
        }
        updateTickTimer();
    }

    private void tick() {
        if (coordinator != null) {
            coordinator.tick();
        }
        for (MainWindow window : snapshotWindows()) {
            CodingAgentPanel panel = window.getCodingAgentPanel();
            if (panel != null) {
                panel.tick();
            }
        }
        updateTickTimer();
    }

    private void updateTickTimer() {
        if (!started) {
            return;
        }
        AgentSummary summary = registry.summary();
        boolean needed = summary != null && !summary.isEmpty();
        if (needed && tickTimeline == null) {
            tickTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
            tickTimeline.setCycleCount(Animation.INDEFINITE);
            tickTimeline.play();
        } else if (!needed && tickTimeline != null) {
            tickTimeline.stop();
            tickTimeline = null;
        }
    }

    /** True while the 1-second settle/tick timeline runs (smoke assertion hook). */
    public boolean isTickTimerRunning() {
        return tickTimeline != null;
    }

    // ---- FocusOracle --------------------------------------------------------------------------

    @Override
    public boolean isSeen(PaneRef pane) {
        if (pane == null) {
            return false;
        }
        for (MainWindow window : snapshotWindows()) {
            if (!window.isForegroundWindow()) {
                continue;
            }
            TerminalTab active = window.getActiveTerminalTab();
            TerminalView view = active != null ? active.getTerminalView() : null;
            if (view == null || !pane.tabId().equals(view.getTerminalViewId())) {
                continue;
            }
            SithTermFxWidget widget = view.codingAgentWidgetFor(pane).orElse(null);
            boolean focused = widget != null && widget == view.getFocusedWidget();
            return seenFor(true, true, focused);
        }
        return false;
    }

    @Override
    public boolean isAnyWindowFocused() {
        for (MainWindow window : snapshotWindows()) {
            if (window.isForegroundWindow()) {
                return true;
            }
        }
        return false;
    }

    /** A pane is seen when its window is in the foreground, its tab is selected and its widget is the focused one. */
    static boolean seenFor(boolean foregroundWindow, boolean tabSelected, boolean widgetFocused) {
        return foregroundWindow && tabSelected && widgetFocused;
    }

    // ---- PaneLocator --------------------------------------------------------------------------

    @Override
    public Optional<PaneLocation> locate(PaneRef pane) {
        if (pane == null) {
            return Optional.empty();
        }
        List<MainWindow> open = snapshotWindows();
        for (int windowIndex = 0; windowIndex < open.size(); windowIndex++) {
            MainWindow window = open.get(windowIndex);
            TerminalTab tab = window.findTerminalTabByViewId(pane.tabId()).orElse(null);
            TerminalView view = tab != null ? tab.getTerminalView() : null;
            if (view == null) {
                continue;
            }
            SithTermFxWidget widget = view.codingAgentWidgetFor(pane).orElse(null);
            if (widget == null) {
                return Optional.empty();
            }
            List<SithTermFxWidget> widgets = view.getOrderedWidgets();
            int paneIndex = Math.max(0, widgets.indexOf(widget));
            int paneCount = Math.max(1, widgets.size());
            return Optional.of(new PaneLocation(windowIndex, open.size(), tabTitleOf(tab), paneIndex, paneCount,
                view.workingDirectoryOf(widget)));
        }
        return Optional.empty();
    }

    /** The tab title without the agent glyph prefix: the connection's display name or user@host. */
    static String tabTitleOf(TerminalTab tab) {
        if (tab == null) {
            return "";
        }
        try {
            var connection = tab.getConnection();
            if (connection != null) {
                String displayName = connection.getDisplayName();
                if (displayName != null && !displayName.isBlank()) {
                    return displayName;
                }
                if (connection.getHost() != null) {
                    return connection.getUsername() + "@" + connection.getHost();
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Tab title could not be resolved: {}", e.toString());
        }
        String text = tab.getText();
        return text != null ? text : "";
    }

    // ---- PaneAccess ---------------------------------------------------------------------------

    @Override
    public Optional<TtyConnector> connectorFor(PaneRef pane) {
        return widgetFor(pane).map(SithTermFxWidget::getTtyConnector);
    }

    @Override
    public boolean isBracketedPasteEnabled(PaneRef pane) {
        if (pane == null) {
            return false;
        }
        for (MainWindow window : snapshotWindows()) {
            TerminalTab tab = window.findTerminalTabByViewId(pane.tabId()).orElse(null);
            TerminalView view = tab != null ? tab.getTerminalView() : null;
            if (view == null) {
                continue;
            }
            SithTermFxWidget widget = view.codingAgentWidgetFor(pane).orElse(null);
            return widget != null && view.isBracketedPasteEnabled(widget);
        }
        return false;
    }

    @Override
    public boolean wouldHostShortcutIntercept(String firstLine) {
        TerminalView view = anyTerminalView();
        return view != null && view.wouldAgentShortcutIntercept(firstLine);
    }

    @Override
    public String hostShortcutCommandName() {
        try {
            GlobalSettings current = settings.get();
            return TerminalAgentCommandSupport.normalizeCommandName(
                current != null ? current.getTerminalAgentCommandName() : null);
        } catch (RuntimeException e) {
            return TerminalAgentCommandSupport.DEFAULT_COMMAND_NAME;
        }
    }

    // ---- PaneFocuser --------------------------------------------------------------------------

    @Override
    public boolean focus(PaneRef pane) {
        if (pane == null) {
            return false;
        }
        for (MainWindow window : snapshotWindows()) {
            TerminalTab tab = window.findTerminalTabByViewId(pane.tabId()).orElse(null);
            TerminalView view = tab != null ? tab.getTerminalView() : null;
            if (view == null) {
                continue;
            }
            SithTermFxWidget widget = view.codingAgentWidgetFor(pane).orElse(null);
            if (widget == null) {
                return false;
            }
            Stage stage = window.getStage();
            if (stage != null) {
                if (!stage.isShowing()) {
                    stage.show();
                }
                if (stage.isIconified()) {
                    stage.setIconified(false);
                }
                stage.toFront();
                stage.requestFocus();
            }
            if (tab.getTabPane() != null) {
                tab.getTabPane().getSelectionModel().select(tab);
            }
            // The tab-selection listener focuses the tab's current widget one FX pulse later; land
            // on the agent's pane after that so the specific split pane wins.
            Platform.runLater(() -> Platform.runLater(() -> view.focusWidget(widget)));
            return true;
        }
        return false;
    }

    @Override
    public Optional<PaneRef> currentPane() {
        MainWindow window = MainWindow.getFocusedWindow().orElse(null);
        if (window == null) {
            return Optional.empty();
        }
        TerminalTab active = window.getActiveTerminalTab();
        TerminalView view = active != null ? active.getTerminalView() : null;
        if (view == null) {
            return Optional.empty();
        }
        return view.paneRefOf(view.getFocusedWidget());
    }

    // ---- StageIconPresenter / TitleBadgePresenter ---------------------------------------------

    @Override
    public void applyBadgedIcon(Image icon) {
        if (icon == null) {
            restorePlainIcon();
            return;
        }
        for (MainWindow window : snapshotWindows()) {
            window.applyStageIcon(icon);
        }
    }

    @Override
    public void restorePlainIcon() {
        Image plain = plainIcon();
        if (plain == null) {
            return;
        }
        for (MainWindow window : snapshotWindows()) {
            window.applyStageIcon(plain);
        }
    }

    @Override
    public void applyTitleCount(int blockedCount) {
        for (MainWindow window : snapshotWindows()) {
            window.applyWindowTitleBadge(blockedCount);
        }
    }

    /** "(n) KorTTY" for a positive count, the plain application name otherwise. */
    static String titleFor(int blockedCount, String appName, BiFunction<String, Object[], String> messages) {
        String name = appName != null ? appName : KorTTYApplication.getAppName();
        if (blockedCount <= 0) {
            return name;
        }
        return messages.apply("codingAgent.title.badge", new Object[] {blockedCount, name});
    }

    /**
     * Builds the notification text: the title names the agent and whether it needs a decision or
     * finished; the body carries "tab › Pane n › cwd" and, on a second line, the evidence.
     */
    static Notification buildNotification(CodingAgentEntry entry, CodingAgentState state, PaneLocation location,
                                          BiFunction<String, Object[], String> messages) {
        String titleKey = state == CodingAgentState.BLOCKED
            ? "codingAgent.notify.blocked.title" : "codingAgent.notify.done.title";
        String title = messages.apply(titleKey, new Object[] {entry.displayName()});
        String where = location != null ? location.shortText() : "";
        String cwd = location != null && location.workingDirectory() != null ? location.workingDirectory() : "";
        String body;
        if (!where.isBlank() && !cwd.isBlank()) {
            body = messages.apply("codingAgent.notify.body", new Object[] {where, cwd});
        } else {
            body = !where.isBlank() ? where : cwd;
        }
        String evidence = CodingAgentGlyphs.evidenceLine(entry.detection(), NOTIFICATION_EVIDENCE_CHARS);
        if (!evidence.isBlank()) {
            body = body.isBlank() ? evidence : body + "\n" + evidence;
        }
        return new Notification(title, body);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private List<MainWindow> snapshotWindows() {
        List<MainWindow> open = windows.get();
        return open == null ? List.of() : List.copyOf(open);
    }

    private Optional<SithTermFxWidget> widgetFor(PaneRef pane) {
        if (pane == null) {
            return Optional.empty();
        }
        for (MainWindow window : snapshotWindows()) {
            TerminalTab tab = window.findTerminalTabByViewId(pane.tabId()).orElse(null);
            TerminalView view = tab != null ? tab.getTerminalView() : null;
            if (view != null) {
                return view.codingAgentWidgetFor(pane);
            }
        }
        return Optional.empty();
    }

    private TerminalView anyTerminalView() {
        MainWindow focused = MainWindow.getFocusedWindow().orElse(null);
        if (focused != null) {
            TerminalTab active = focused.getActiveTerminalTab();
            if (active != null && active.getTerminalView() != null) {
                return active.getTerminalView();
            }
        }
        for (MainWindow window : snapshotWindows()) {
            for (TerminalTab tab : window.getOpenTerminalTabs()) {
                if (tab.getTerminalView() != null) {
                    return tab.getTerminalView();
                }
            }
        }
        return null;
    }

    private Image plainIcon() {
        if (plainIcon == null) {
            try {
                var url = CodingAgentUiBridge.class.getResource(PLAIN_ICON_RESOURCE);
                if (url != null) {
                    plainIcon = new Image(url.toExternalForm());
                }
            } catch (RuntimeException e) {
                logger.debug("Plain application icon could not be loaded: {}", e.toString());
            }
        }
        return plainIcon;
    }
}
