package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.telemetry.TelemetryProps;
import de.kortty.telemetry.TerminalUxTelemetry.RestoreTrigger;
import de.kortty.ui.I18n;
import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.shellintegration.PromptNavigator;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.ui.actions.ActionIds;
import de.kortty.ui.actions.ActionPaletteSource;
import de.kortty.ui.actions.ActionRegistry;
import de.kortty.ui.actions.AppAction;
import de.kortty.ui.actions.MenuActionHarvester;
import de.kortty.ui.actions.MenuStateRefresh;
import de.kortty.ui.actions.TabMruTracker;
import de.kortty.ui.actions.TabPaletteSource;
import de.kortty.ui.actions.TerminalPaletteActions;
import de.kortty.core.AgentDashboardStatus;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.codingagent.CodingAgentActionException;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentNavigator;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.KeyChord;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.TabRollup;
import de.kortty.codingagent.desktop.AppBadgeService;
import de.kortty.core.AtomicFileWriter;
import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.AiAction;
import de.kortty.core.AiCliArgumentTemplate;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiProfileSelectionSupport;
import de.kortty.core.AiPromptService;
import de.kortty.core.highlight.HighlightTelemetry;
import de.kortty.core.KeymapOverrides;
import de.kortty.core.highlight.HighlightToggle;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.core.swarm.SwarmCallback;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.core.swarm.SwarmOrchestrator;
import de.kortty.core.swarm.SwarmTarget;
import de.kortty.core.AiFileAttachment;
import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.RedactionResult;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.core.AiRequest;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.AiTokenCounter;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.FailingAiService;
import de.kortty.core.LanguageManager;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.ProjectLeafFieldSanitizer;
import de.kortty.core.ProjectManager;
import de.kortty.core.RecentConnections;
import de.kortty.core.RecentProjects;
import de.kortty.core.SessionRestoreDecision;
import de.kortty.core.SessionSnapshotStore;
import de.kortty.core.RemoteTextFileSelectionSupport;
import de.kortty.core.SftpFileTransferService;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.core.LocalShellTtyConnector;
import de.kortty.core.ObservableTtyConnector;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentCommandRunners;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.core.TerminalAgentService;
import de.kortty.jobscheduler.ActiveJobSummary;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.model.*;
import de.kortty.persistence.importer.ConnectionImporter;
import de.kortty.persistence.importer.MTPuTTYImporter;
import de.kortty.persistence.importer.MobaXTermImporter;
import de.kortty.persistence.importer.PuTTYCMImporter;
import de.kortty.persistence.exporter.ConnectionExporter;
import de.kortty.persistence.exporter.KorTTYExporter;
import de.kortty.persistence.exporter.MTPuTTYExporter;
import de.kortty.persistence.exporter.MobaXTermExporter;
import de.kortty.platform.FlatpakSupport;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.security.PasswordVault;
import de.kortty.update.AvailableUpdate;
import de.kortty.update.DownloadDirectoryResolver;
import de.kortty.update.DownloadException;
import de.kortty.update.UpdateAssetDownloader;
import de.kortty.update.UpdateCheckResult;
import de.kortty.update.UpdateCheckService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.concurrent.Task;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.control.*;
import javafx.scene.control.skin.MenuBarSkin;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.event.Event;
import javafx.geometry.Side;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.awt.Desktop;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Main application window with TabPane for SSH terminals.
 */
public class MainWindow {
    
    private static MainWindow instance;  // Singleton instance for global access
    
    public static MainWindow getInstance() {
        return instance;
    }
    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    private static final int DEFAULT_MAX_AI_SELECTION_CHARS = 1_000_000;
    private static final KeyCombination PASTE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination MENU_BAR_TOGGLE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination RECORDING_TOGGLE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination JOB_SCHEDULER_ACCELERATOR =
        new KeyCodeCombination(KeyCode.J, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination VIDEO_MANAGER_ACCELERATOR =
        new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination AI_AGENT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination AI_PLANNING_ACCELERATOR =
        new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination SESSION_JOURNAL_MANAGER_ACCELERATOR =
        new KeyCodeCombination(KeyCode.J, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination SESSION_JOURNAL_TOGGLE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination SESSION_JOURNAL_SCREENSHOT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    // Configuration > Security > Credentials. It was Shortcut+Shift+P, which is kept free for a
    // command palette; Shortcut+M (Manage Connections) is a different chord.
    private static final KeyCombination CREDENTIALS_ACCELERATOR =
        new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    // File > Reopen Closed Tab. Shortcut+Shift+T toggles the command timestamps and Shortcut+Alt+T
    // the session journal, so the browsers' Shortcut+Shift+T takes Alt as well.
    private static final KeyCombination REOPEN_CLOSED_TAB_ACCELERATOR = new KeyCodeCombination(
        KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN);
    // F11 is intercepted system-wide by macOS ("Show Desktop") and F12 is used for regular OS
    // fullscreen, so terminal-only fullscreen uses a modifier combo instead of a bare function key.
    private static final KeyCombination TERMINAL_ONLY_FULLSCREEN_ACCELERATOR =
        new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    // Keyword highlighting on/off for the focused pane. Never a plain Ctrl+letter: on Windows and
    // Linux plain Ctrl+H is the shell's backspace and must keep reaching it.
    private static final KeyCombination HIGHLIGHTING_TOGGLE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    // Edit > Quick Select. Not a plain Ctrl+letter, so on Windows/Linux Ctrl+L/F/D/P/R stay with the
    // shell; SithTermFX encodes Ctrl+Space (NUL) only without Shift.
    private static final KeyCombination QUICK_SELECT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.SPACE, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    /** What Cmd/Ctrl+Shift+Space can still type once korTTY took it: a space, or NUL for Ctrl+Space. */
    private static final Residue QUICK_SELECT_RESIDUE = Residue.of(" ", "\u0000");
    // Edit > Previous Prompt / Next Prompt: jump between the prompts that shell integration (OSC 133)
    // marks. The pane's own key actions use these constants (ShellIntegrationController) and act only
    // while the pane has prompt marks on its normal screen; otherwise the key reaches the program as
    // before. Not a plain Ctrl+letter, and SithTermFX's line scroll is Shortcut+Up/Down without Shift.
    private static final KeyCombination PREVIOUS_PROMPT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.UP, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination NEXT_PROMPT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.DOWN, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    // View > Command Palette, the chord Credentials gave up (it is Shortcut+Shift+M now). Not a plain
    // Ctrl+letter, so on Windows/Linux Ctrl+P (the shell's previous-history key) stays with the shell.
    private static final KeyCombination COMMAND_PALETTE_ACCELERATOR =
        new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    // View > Panes > Focus Pane Left/Right/Up/Down: Cmd+Option or Ctrl+Alt with an arrow key, routed
    // only while the selected terminal tab has two or more panes, so with one pane the arrows still
    // reach the shell as xterm sends them. No plain Ctrl+letter, and arrows type no AltGr character.
    private static final KeyCombination PANE_FOCUS_LEFT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination PANE_FOCUS_RIGHT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination PANE_FOCUS_UP_ACCELERATOR =
        new KeyCodeCombination(KeyCode.UP, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    private static final KeyCombination PANE_FOCUS_DOWN_ACCELERATOR =
        new KeyCodeCombination(KeyCode.DOWN, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
    // View > Panes > Split Pane: Cmd/Ctrl+Shift+O (as in Terminator) splits the focused pane on its own
    // server, routed while the keyboard is in a terminal tab; only Cmd/Ctrl+O (Open Project) shares the O.
    private static final KeyCombination PANE_SPLIT_ACCELERATOR =
        new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    // View > Panes > Zoom Pane: Cmd/Ctrl+Shift+Enter (iTerm2's Maximize Active Pane) lets the focused
    // pane fill the tab, routed while the keyboard is in a terminal tab with two or more panes; with
    // one pane the shell still gets it as Enter.
    private static final KeyCombination PANE_ZOOM_ACCELERATOR =
        new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final PaneShortcuts PANE_SHORTCUTS = new PaneShortcuts(Map.of(
        PaneShortcuts.PaneAction.FOCUS_LEFT, PANE_FOCUS_LEFT_ACCELERATOR,
        PaneShortcuts.PaneAction.FOCUS_RIGHT, PANE_FOCUS_RIGHT_ACCELERATOR,
        PaneShortcuts.PaneAction.FOCUS_UP, PANE_FOCUS_UP_ACCELERATOR,
        PaneShortcuts.PaneAction.FOCUS_DOWN, PANE_FOCUS_DOWN_ACCELERATOR,
        PaneShortcuts.PaneAction.SPLIT, PANE_SPLIT_ACCELERATOR,
        PaneShortcuts.PaneAction.ZOOM, PANE_ZOOM_ACCELERATOR));
    private static final String MENU_BAR_TOGGLE_SHORTCUT_LABEL = "Cmd/Ctrl+Shift+L";
    // The keymap in effect in every window (the defaults with the user's shortcut overrides), for the
    // terminal views' quick select; null until the first window applied it (applyKeymap).
    private static volatile KeymapOverrides.Resolution sharedKeymap;
    // What the log last said about overrides that are not in effect, so each window does not repeat it.
    private static volatile List<String> loggedKeymapRejections = List.of();
    private static final int JOB_SCHEDULER_QUEUE_LIMIT = 5;
    private static final int MAX_CONCURRENT_TERMINAL_AGENT_RUNS = 5;
    private static final int JOB_SCHEDULER_STATUS_LEFT_PADDING = 14;
    private static final String PROJECT_URL = "https://github.com/chardonnay/korTTY";
    private static final String JOB_SCHEDULER_STATUS_SPACED_TEXT_PROPERTY = "kortty.jobscheduler.status.spacedText";
    private static final String JOB_SCHEDULER_QUEUE_JOB_NAME_PROPERTY = "kortty.jobscheduler.queue.jobName";
    private static final String JOB_SCHEDULER_QUEUE_NEXT_RUN_PROPERTY = "kortty.jobscheduler.queue.nextRun";
    private static final DateTimeFormatter JOB_SCHEDULER_MENU_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    // The scene shortcut router's chords the user can rebind: each knows its action (the i18n key of
    // the menu item that shows it), its default (the constant above) and the residue that default
    // types; applyKeymap() puts it on the user's shortcut override. The router reads them per key.
    private final RoutedChord commandPaletteChord =
        new RoutedChord("menu.view.commandPalette", COMMAND_PALETTE_ACCELERATOR, PaletteKeys.RESIDUE);
    // View > Snippet Palette: the palette opened on its snippets ('$'). Without a default chord, since
    // Cmd/Ctrl+Shift+J (the one asked for) is the JobScheduler's; the user can bind one under
    // Settings > Keyboard, and the router then takes it also while a terminal has the focus.
    private final RoutedChord snippetPaletteChord = RoutedChord.unbound("menu.view.snippetPalette");
    private final RoutedChord menuBarToggleChord =
        new RoutedChord("menu.view.menuBar", MENU_BAR_TOGGLE_ACCELERATOR, Residue.ofLetter('L'));
    private final RoutedChord terminalOnlyFullscreenChord = new RoutedChord(
        "menu.view.terminalOnlyFullscreen", TERMINAL_ONLY_FULLSCREEN_ACCELERATOR, Residue.ofLetter('F'));
    // Its default residue includes U+0008, which Ctrl turns H into: a backspace for the shell.
    private final RoutedChord highlightingToggleChord =
        new RoutedChord(HighlightMenuSupport.TOGGLE_KEY, HIGHLIGHTING_TOGGLE_ACCELERATOR, Residue.ofLetter('H'));
    private final RoutedChord credentialsChord =
        new RoutedChord("menu.security.credentials", CREDENTIALS_ACCELERATOR, Residue.ofLetter('M'));
    // Ctrl+Alt+Shift+T is AltGr+Shift+T on Windows, which types a character on some layouts: whatever
    // it types is swallowed.
    private final RoutedChord reopenClosedTabChord =
        new RoutedChord("menu.file.reopenClosedTab", REOPEN_CLOSED_TAB_ACCELERATOR, Residue.anyCharacter());
    private final RoutedChord quickSelectChord =
        new RoutedChord("menu.edit.quickSelect", QUICK_SELECT_ACCELERATOR, QUICK_SELECT_RESIDUE);
    private final Map<PaneShortcuts.PaneAction, RoutedChord> paneChords = createPaneChords();
    // The other menu commands whose shortcut the user chose, by that chord (applyKeymap), which the
    // router runs while the keyboard is in a terminal; and the one the current key press matched.
    private Map<KeyCombination, MenuItem> reboundMenuChords = Map.of();
    private MenuItem pressedReboundMenuItem;

    private final Stage stage;
    private final BorderPane root;
    // Scene root: hosts `root` and, in terminal-only fullscreen, centers it on an empty backdrop.
    private StackPane sceneRoot;
    private final TabPane tabPane;
    private final Label statusLabel;
    private VBox statusBar;
    private final HBox mainContentBox;
    private final boolean unifiedTitleBarEnabled;
    // True when the window runs borderless (StageStyle.TRANSPARENT) so the terminal background can be
    // see-through. Decided once at startup from the persisted transparency setting; toggling needs a
    // restart because JavaFX locks the stage style before the window is shown.
    private final boolean transparentWindowMode;
    private MenuBar menuBar;
    private MenuBar systemMenuBar;
    // Created on first use: the window's actions for the command palette, and the palette itself.
    private ActionRegistry actionRegistry;
    private CommandPalettePopup commandPalette;
    // The order this window's tabs were last selected in, the most recent first; the command
    // palette lists the tabs in it, and so does Ctrl+Tab when the Window setting asks for it.
    private final TabMruTracker<Tab> tabMru = new TabMruTracker<>();
    // Set while tabs are removed and re-added in bulk (see reorganizeTabs), so the selection
    // passing over them does not count as using them.
    private boolean reorganizingTabs;
    // Set while a step of a Ctrl+Tab cycle in most-recently-used order selects its tab: only the
    // tab the cycle stops at counts as used (see switchTabFromKeyboard).
    private boolean steppingTabCycle;
    private GuideTranslationIndicator guideTranslationIndicator;
    private String dynamicThemeStylesheetUrl;
    private DashboardView dashboardView;
    private boolean dashboardVisible = false;
    private LocalFileBrowser localFileBrowser;
    private LocalFileBrowserManager fileBrowserManager;
    private CheckMenuItem showDashboardMenuItem;
    private CheckMenuItem showFileBrowserLeftMenuItem;
    private CheckMenuItem showFileBrowserRightMenuItem;
    private CheckMenuItem systemShowFileBrowserLeftMenuItem;
    private CheckMenuItem systemShowFileBrowserRightMenuItem;
    private ResizableDivider fileBrowserDivider;
    // AI-agent activity panel docking (bottom by default, or docked left/right like the file browser).
    private AiAgentSidePanel aiAgentSidePanel;
    private AiAgentPanelDockManager aiAgentDockManager;
    private ResizableDivider aiAgentSideDivider;
    private java.util.function.Consumer<AiAgentPanelDockManager.Placement> aiAgentPlacementListener;
    private CheckMenuItem showAiAgentBottomMenuItem;
    private CheckMenuItem showAiAgentLeftMenuItem;
    private CheckMenuItem showAiAgentRightMenuItem;
    private CheckMenuItem systemShowAiAgentBottomMenuItem;
    private CheckMenuItem systemShowAiAgentLeftMenuItem;
    private CheckMenuItem systemShowAiAgentRightMenuItem;
    // Live session-journal panel docking (hidden by default, or docked left/right).
    private SessionJournalLivePanel journalLivePanel;
    private SessionJournalLivePanelDockManager journalLiveDockManager;
    private ResizableDivider journalLiveDivider;
    private java.util.function.Consumer<SessionJournalLivePanelDockManager.Placement> journalLivePlacementListener;
    private javafx.animation.PauseTransition windowGeometrySaveDelay;
    /**
     * The bounds this window last had while it was neither maximized, in fullscreen nor minimized,
     * which a project saves for a window that is maximized at the time; null until known.
     */
    private WindowGeometry lastNormalGeometry;
    /** The latest restore of this window's tabs from a project; see {@link WindowRestore}. */
    private WindowRestore activeRestore;
    /**
     * The tabs of that restore that did not open right away, which the restore bar lists; see
     * {@link #restoreOrDeferSavedTab}.
     */
    private final RestoreAttention<DeferredTab> restoreAttention = new RestoreAttention<>();
    /** The restore bar above the status line; hidden while no tab waits. */
    private RestoreAttentionBar restoreAttentionBar;
    /** Whether Connect… on the restore bar is asking about a waiting tab right now. */
    private boolean connectingDeferredTabs;
    /**
     * The startup offer of the previous session above the status line (see
     * {@link #startSessionRestore}); {@code null} in every window it was never offered in.
     */
    private SessionRestoreOfferBar sessionRestoreOfferBar;
    private javafx.animation.PauseTransition journalLiveWidthSaveDelay;
    private CheckMenuItem showJournalLiveLeftMenuItem;
    private CheckMenuItem showJournalLiveRightMenuItem;
    private CheckMenuItem systemShowJournalLiveLeftMenuItem;
    private CheckMenuItem systemShowJournalLiveRightMenuItem;
    // Coding Agents panel docking (hidden by default, or docked left/right) and the status-bar strip.
    private CodingAgentPanel codingAgentPanel;
    private CodingAgentPanelDockManager codingAgentDockManager;
    private ResizableDivider codingAgentDivider;
    private java.util.function.Consumer<CodingAgentPanelDockManager.Placement> codingAgentPlacementListener;
    private javafx.animation.PauseTransition codingAgentWidthSaveDelay;
    private CodingAgentStatusStrip codingAgentStatusStrip;
    private CheckMenuItem showCodingAgentLeftMenuItem;
    private CheckMenuItem showCodingAgentRightMenuItem;
    private CheckMenuItem systemShowCodingAgentLeftMenuItem;
    private CheckMenuItem systemShowCodingAgentRightMenuItem;
    /** The status bar's single row; the coding-agent strip sits right-aligned inside it. */
    private HBox statusRow;
    // 1s tick that refreshes the per-tab AI-agent status badge (✋/⚡/⏸/✓) in tab titles.
    private javafx.animation.Timeline agentStatusIndicatorTimer;
    private CheckMenuItem systemShowDashboardMenuItem;
    private MenuItem cutMenuItem;
    private MenuItem systemCutMenuItem;
    private MenuItem quickSelectMenuItem;
    private MenuItem systemQuickSelectMenuItem;
    private CheckMenuItem showMenuBarMenuItem;
    private CheckMenuItem systemShowMenuBarMenuItem;
    private CheckMenuItem terminalOnlyFullscreenMenuItem;
    private CheckMenuItem systemTerminalOnlyFullscreenMenuItem;
    private CheckMenuItem highlightingToggleMenuItem;
    private CheckMenuItem systemHighlightingToggleMenuItem;
    // View > Panes of the in-window and the macOS system menu bar, synced from the active tab.
    private PaneMenuSupport.PaneMenu paneMenu;
    private PaneMenuSupport.PaneMenu systemPaneMenu;
    // View > Multi-exec of both menu bars, synced from the active tab and the members.
    private MultiExecMenuSupport.MultiExecMenu multiExecMenu;
    private MultiExecMenuSupport.MultiExecMenu systemMultiExecMenu;
    // The multi-exec chip in the status bar, and this window's subscription to multi-exec changes.
    private MultiExecStatusBar multiExecStatusBar;
    private AutoCloseable multiExecListener;
    private CheckMenuItem hideFullscreenScrollbarsMenuItem;
    private CheckMenuItem systemHideFullscreenScrollbarsMenuItem;
    private CheckMenuItem showTimestampsMenuItem;
    private CheckMenuItem systemShowTimestampsMenuItem;
    private Menu jobSchedulerStatusMenu;
    private Menu systemJobSchedulerStatusMenu;
    private Timeline jobSchedulerStatusTimeline;
    private Runnable jobSchedulerStatusListener;
    private long lastTerminalPasteShortcutAtNanos = -1L;

    /** Window + system menu bar: AI Manager / Agent / Planning items (disable when AI is turned off). */
    private final List<MenuItem> toolsAiMenuItems = new ArrayList<>(6);
    private final List<MenuItem> toolsAiAgentExecutionMenuItems = new ArrayList<>(2);
    
    private final KorTTYApplication app;
    private final ProjectManager projectManager;
    private final TerminalAgentService terminalAgentService = new TerminalAgentService();
    private final List<ConnectionImporter> importers;
    
    private static final List<MainWindow> openWindows = new ArrayList<>();
    /** The window that had the focus last; stands in for the frontmost one (see {@link #getFrontmostOpenWindow}). */
    private static MainWindow lastFocusedWindow;

    /** The terminal tabs the user closed in this session, across every window; see {@link ClosedTabHistory}. */
    private static final ClosedTabHistory closedTabHistory = new ClosedTabHistory();
    private static final RecentlyClosedRecorder recentlyClosedRecorder = new RecentlyClosedRecorder(closedTabHistory);
    /** How many tab names a Recently Closed entry of several tabs shows before "+N" counts the rest. */
    private static final int RECENTLY_CLOSED_NAMES = 3;

    /**
     * Keeps the session snapshot current (see {@link #startSessionAutosave}); {@code null} until
     * korTTY started it, so every hook is a no-op in a window built without the application. FX thread.
     */
    private static SessionAutosaveCoordinator sessionAutosave;
    /** Saves each terminal pane's output for the session snapshot when the setting is on; null before startup. */
    private static SessionScrollbackCoordinator sessionScrollback;
    /** The name of the project a session snapshot holds; shown nowhere, but a project needs one. */
    private static final String SESSION_PROJECT_NAME = "Session";

    /**
     * The projects File › Open Recent lists, newest first, as {@link #refreshRecentProjects} found them
     * last. Read and written on the FX thread only.
     */
    private static List<Path> recentProjects = List.of();
    /**
     * Counts the changes to the remembered projects (opened, saved, cleared), so a refresh that started
     * before one of them cannot put back what it changed. FX thread only.
     */
    private static long recentProjectsGeneration;
    /**
     * Looks for the recent project files off the FX thread: a remembered file can sit on a network
     * share that takes long to answer, which must not freeze the File menu. One daemon thread, made
     * when the first refresh runs.
     */
    private static final java.util.concurrent.ExecutorService RECENT_PROJECTS_LOOKUP =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-open-recent");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Mints {@link #windowId}. A counter rather than the list position: a window id that renumbered
     * itself whenever an earlier window closed would silently re-point every id a script is holding.
     */
    private static final java.util.concurrent.atomic.AtomicLong windowIdSequence =
        new java.util.concurrent.atomic.AtomicLong();

    /** This window's stable id for the lifetime of the window; see {@link #getWindowId()}. */
    private final String windowId = "w" + windowIdSequence.incrementAndGet();
    private static final Set<MainWindow> applicationQuitApprovedWindows =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<CheckMenuItem> preventSleepMenuItems = new ArrayList<>();
    /** "Unlock Vault…" in every menu bar of this window (window menu bar and system menu bar). */
    private final List<MenuItem> unlockVaultMenuItems = new ArrayList<>();
    /** File › Reopen Closed Tab in every menu bar of this window. */
    private final List<MenuItem> reopenClosedTabMenuItems = new ArrayList<>();
    /**
     * Edit › Previous Prompt, Next Prompt, Select Last Output and Copy Last Output in every menu bar
     * of this window; terminal tabs only.
     */
    private final List<MenuItem> shellIntegrationMenuItems = new ArrayList<>();
    /** File › Recently Closed in every menu bar of this window, rebuilt whenever the history changes. */
    private final List<Menu> recentlyClosedMenus = new ArrayList<>();
    /** File › Open Recent in every menu bar of this window, rebuilt whenever the File menu opens. */
    private final List<Menu> openRecentMenus = new ArrayList<>();
    /** File › Restore Previous Session in every menu bar of this window. */
    private final List<MenuItem> restorePreviousSessionMenuItems = new ArrayList<>();
    private Runnable powerManagementStateListener;
    private static volatile boolean applicationQuitRequested = false;
    private static volatile boolean schedulerDrainApproved = false;
    private static volatile boolean schedulerDrainInProgress = false;
    /**
     * Set while {@link #requestApplicationQuit()} asks every window: each window then guards only
     * the snippet editors it owns, and the quit path asks the unowned ones once, after all windows.
     */
    private static boolean applicationQuitConfirmationInProgress = false;

    /** DataFormat for drag-and-drop of tabs between KorTTY windows (value: transfer ID). */
    private static final DataFormat KORTTY_TAB_TRANSFER_FORMAT = new DataFormat("application/x-kortty-tab-transfer");
    /** Pending tab transfer: transferId -> (source window, tab). Cleared after drop or drag done. */
    private static final Map<String, TabTransfer> pendingTabTransfers = new HashMap<>();

    private record TabTransfer(MainWindow sourceWindow, Tab tab) {}
    private enum MenuBarTarget { WINDOW, SYSTEM }

    private final Map<String, AiResultTab> openSavedAiChatTabs = new HashMap<>();
    private final Map<String, SwarmAgentTab> openSavedSwarmChatTabs = new HashMap<>();
    private AiManagerDialog aiManagerDialog;
    private SavedChatsDialog savedChatsDialog;
    private SessionJournalManagerDialog sessionJournalManagerDialog;
    /** Open journal viewers keyed by normalized journal directory (one viewer per journal). */
    private final Map<java.nio.file.Path, SessionJournalViewerDialog> sessionJournalViewers = new HashMap<>();

    private volatile boolean quickConnectDialogOpen = false;
    private volatile boolean startupComplete = false; // Prevent QuickConnect during startup
    private boolean terminalOnlyFullscreenActive = false;
    private boolean terminalOnlyPreviousFullScreen = false;
    // "Terminal-only fullscreen" keeps the whole korTTY window at this size, centered on an
    // otherwise empty fullscreen background, so other windows/the desktop stop being a distraction.
    private double terminalOnlyContentWidth = -1;
    private double terminalOnlyContentHeight = -1;
    /** Consumer reference for file browser position listener, stored so it can be removed on close. */
    private Consumer<LocalFileBrowserManager.Position> fileBrowserPositionListener;
    
    public MainWindow(Stage stage) {
        instance = this;  // Set singleton instance
        this.stage = stage;
        this.app = KorTTYApplication.getInstance();
        this.projectManager = new ProjectManager(KorTTYApplication.getConfigDirectory());
        this.transparentWindowMode = shouldUseTransparentWindow();
        this.unifiedTitleBarEnabled = configureWindowChrome(stage, transparentWindowMode);
        
        // Initialize importers
        this.importers = List.of(new MTPuTTYImporter(), new MobaXTermImporter(), new PuTTYCMImporter());
        
        // Create UI components
        this.root = new BorderPane();
        this.tabPane = new TabPane();
        this.statusLabel = new Label(I18n.get("app.ready"));
        this.mainContentBox = new HBox();
        
        setupUI();
        setupMenuBar();
        installTransparentWindowChrome();
        installForegroundActivityLifecycle();
        WindowCloseShortcutSupport.installForMainWindow(stage, openWindows.isEmpty(), this::fireCloseRequest);
        
        openWindows.add(this);
        markSessionDirty();
        Telemetry.track(TelemetryEvents.WINDOW_OPENED, Map.of("open_windows", openWindows.size()));
        // A window created later receives the current app badge / "(n) KorTTY" title right away.
        AppBadgeService appBadge = app.getAppBadgeService();
        if (appBadge != null) {
            try {
                appBadge.refresh();
            } catch (RuntimeException e) {
                logger.debug("App badge refresh for the new window failed: {}", e.getMessage());
            }
        }
        // OS fullscreen can be entered via F12, the menu, or the macOS window button —
        // the stage property is the single funnel for all of them.
        stage.fullScreenProperty().addListener((obs, wasFullScreen, isFullScreen) -> {
            if (Boolean.TRUE.equals(isFullScreen)) {
                Telemetry.track(TelemetryEvents.FULLSCREEN_ENTERED, Map.of("mode", "os"));
            }
        });
    }

    /**
     * Whether the window should start in see-through (borderless {@code StageStyle.TRANSPARENT}) mode.
     * Driven by the persisted terminal background transparency: any value &gt; 0 opts in, provided the
     * platform supports transparent windows. Decided before {@code stage.show()} and never changes at
     * runtime — the transparency slider persists the new value and asks the user to restart.
     */
    private boolean shouldUseTransparentWindow() {
        try {
            GlobalSettings gs = app.getGlobalSettingsManager().getSettings();
            return gs != null
                && gs.getTerminalBackgroundTransparency() > 0
                && Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW);
        } catch (Exception e) {
            logger.debug("Could not read transparency setting: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Applies the initial stage style. Returns whether the macOS unified title bar is active (never in
     * transparent mode, where custom chrome replaces the native decorations).
     */
    private boolean configureWindowChrome(Stage stage, boolean transparentMode) {
        if (transparentMode) {
            try {
                stage.initStyle(StageStyle.TRANSPARENT);
            } catch (IllegalStateException e) {
                logger.debug("Could not enable transparent window decorations: {}", e.getMessage());
            }
            return false;
        }
        try {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!osName.contains("mac") || !Platform.isSupported(ConditionalFeature.UNIFIED_WINDOW)) {
                return false;
            }

            stage.initStyle(StageStyle.UNIFIED);
            return true;
        } catch (IllegalStateException e) {
            logger.debug("Could not enable unified window decorations: {}", e.getMessage());
            return false;
        }
    }

    /**
     * In borderless see-through mode, adds a custom title strip (drag + window buttons) above the
     * menu bar and installs edge resizing, since {@code StageStyle.TRANSPARENT} has no native chrome.
     * No-op in the normal decorated/unified window modes.
     */
    private void installTransparentWindowChrome() {
        if (!transparentWindowMode) {
            return;
        }
        Region titleBar = TransparentWindowChrome.buildTitleBar(
            stage, KorTTYApplication.getAppName(), this::fireCloseRequest);
        javafx.scene.Node currentTop = root.getTop();
        VBox topStack = new VBox();
        if (currentTop != null) {
            topStack.getChildren().addAll(titleBar, currentTop);
        } else {
            topStack.getChildren().add(titleBar);
        }
        root.setTop(topStack);
        if (stage.getScene() != null) {
            TransparentWindowChrome.installResize(stage, stage.getScene());
        }
    }

    private void setupUI() {
        // Tab pane configuration
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        root.getStyleClass().add("kortty-main-root");
        mainContentBox.getStyleClass().add("main-content");
        tabPane.getStyleClass().add("main-tab-pane");
        
        // Auto-focus terminal when tab is selected; tell Mosh connector when this tab is active so it does not show false "interrupted"
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (oldTab instanceof TerminalTab oldTerminalTab) {
                oldTerminalTab.getTerminalView().setTerminalActive(false);
            }
            if (newTab != null && !reorganizingTabs && !steppingTabCycle) {
                // Chosen some other way (the mouse, a closed tab, the palette): a Ctrl+Tab cycle
                // still running ends here, and the tab counts as used.
                tabMru.commit(newTab);
            }
            if (newTab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().setTerminalActive(true);
                Platform.runLater(() -> terminalTab.getTerminalView().focusTerminal());
                lastSelectedTerminalTab = terminalTab;
                // The user looks at the tab now: its bell mark has done its job.
                clearAttentionOfSeenTab();
            } else if (newTab instanceof FileEditorTab fileEditorTab) {
                lastSelectedFileEditorTab = fileEditorTab;
            }
            updateEditMenuItemsForSelection();
            syncPaneMenuItems();
            // The session snapshot keeps each window's active tab.
            markSessionDirty();
            // See-through mode: only a terminal tab reveals the desktop; other/empty tabs stay opaque.
            refreshTransparentModeContainers();
            // When the agent panel is docked to the side, swap it to show only the now-active tab.
            Platform.runLater(this::rebindAiAgentSidePanelToActiveTab);
            // The live journal panel follows only tabs that have a running journal.
            Platform.runLater(this::rebindJournalLivePanelToActiveTab);
            // Done-until-seen: the newly selected tab's agent may now be seen; the panel's current-row
            // accent follows the focus as well.
            Platform.runLater(this::onCodingAgentFocusContextChanged);
        });
        
        // Listen for tab removals to update dashboard and clear per-terminal AI state.
        tabPane.getTabs().addListener((javafx.collections.ListChangeListener.Change<? extends Tab> change) -> {
            // Opened, closed, moved, regrouped or dragged to another window: the session snapshot follows.
            markSessionDirty();
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Tab addedTab : change.getAddedSubList()) {
                        if (addedTab instanceof TerminalTab terminalTab) {
                            applyTerminalScrollbarVisibility(terminalTab);
                            applyBackgroundTransparencyToTab(terminalTab);
                        }
                    }
                }
                if (change.wasRemoved()) {
                    for (Tab removedTab : change.getRemoved()) {
                        if (!reorganizingTabs) {
                            tabMru.remove(removedTab);
                        }
                        // Closed or dragged into another window: no longer an insert target here.
                        if (removedTab == lastSelectedTerminalTab) {
                            lastSelectedTerminalTab = null;
                        }
                        if (removedTab == lastSelectedFileEditorTab) {
                            lastSelectedFileEditorTab = null;
                        }
                        if (removedTab instanceof TerminalTab terminalTab) {
                            terminalAgentService.clearCachedSudoPassword(terminalTab.getAiSessionId());
                            if (journalLivePanel != null && journalLivePanel.getBoundTab() == terminalTab) {
                                journalLivePanel.notifyBoundTabClosed();
                            }
                        }
                    }
                    Platform.runLater(() -> {
                        updateDashboard();
                    });
                }
            }
        });
        
        // Handle double-click on tab for retry
        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2) {
                Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                if (selectedTab instanceof TerminalTab terminalTab) {
                    // Check if tab is in failed state (dark red)
                    String style = selectedTab.getStyle();
                    if (style != null && style.contains("#8B0000")) {
                        // Retry connection
                        terminalTab.retryConnection();
                        event.consume();
                    }
                }
            }
        });

        // Tab drag-and-drop: only when drag starts in the tab bar (not in tab content) so text selection works
        final double tabBarHeightPx = 36;
        tabPane.addEventFilter(MouseEvent.DRAG_DETECTED, event -> {
            Point2D inTabPane = tabPane.sceneToLocal(event.getSceneX(), event.getSceneY());
            if (inTabPane.getY() < 0 || inTabPane.getY() >= tabBarHeightPx) {
                return; // drag started in content area: allow text selection / split-pane drag
            }
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected == null || !selected.isClosable()) {
                return;
            }
            String transferId = UUID.randomUUID().toString();
            pendingTabTransfers.put(transferId, new TabTransfer(this, selected));
            Dragboard db = tabPane.startDragAndDrop(TransferMode.MOVE);
            db.setContent(Map.of(KORTTY_TAB_TRANSFER_FORMAT, transferId));
            event.consume();
        });
        tabPane.setOnDragOver(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) {
                return;
            }
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            TabTransfer xfer = pendingTabTransfers.get(transferId);
            if (xfer == null) {
                return;
            }
            // Allow drop on any tab pane (same or other window); same-window drop reorders
            event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });
        tabPane.setOnDragDropped(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) {
                event.setDropCompleted(false);
                return;
            }
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            TabTransfer xfer = pendingTabTransfers.remove(transferId);
            if (xfer == null) {
                event.setDropCompleted(false);
                return;
            }
            Tab tab = xfer.tab();
            MainWindow sourceWindow = xfer.sourceWindow();
            javafx.scene.control.TabPane sourcePane = sourceWindow.tabPane;
            if (!sourcePane.getTabs().contains(tab)) {
                event.setDropCompleted(false);
                return;
            }
            reorganizeTabs(() -> {
                sourcePane.getTabs().remove(tab);
                // Insert index: approximate position from drop X for reorder.
                int insertIndex = (int) ((event.getX() / Math.max(1, tabPane.getWidth())) * (tabPane.getTabs().size()));
                insertIndex = Math.max(0, Math.min(insertIndex, tabPane.getTabs().size()));
                tabPane.getTabs().add(insertIndex, tab);
                tabPane.getSelectionModel().select(tab);
            });
            if (tab instanceof TerminalTab tt) {
                installAiSelectionHandler(tt);
                // Re-bind the per-tab hooks to this window (the creation-time lambdas captured the source).
                registerTerminalTabForAiAgentDock(tt);
                Platform.runLater(() -> tt.getTerminalView().requestFocus());
            }
            event.setDropCompleted(true);
            event.consume();
            if (sourceWindow != this) {
                sourceWindow.updateDashboard();
                updateDashboard();
                updateAllTabContextMenus();
                sourceWindow.updateAllTabContextMenus();
                // The tab's panes keep their multi-exec membership; the window counts changed.
                MultiExecCoordinator.shared().refreshMarkers();
            } else {
                updateDashboard();
                updateAllTabContextMenus();
            }
        });
        tabPane.setOnDragDone(event -> {
            if (!event.isDropCompleted()) {
                pendingTabTransfers.entrySet().removeIf(entry -> entry.getValue().sourceWindow() == this);
            }
        });

        // Accept tab drops anywhere in this window (bubbling handlers so split-terminal DnD is not affected)
        root.setOnDragOver(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) return;
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            if (pendingTabTransfers.get(transferId) == null) return;
            event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });
        root.setOnDragDropped(event -> {
            if (!event.getDragboard().hasContent(KORTTY_TAB_TRANSFER_FORMAT)) return;
            String transferId = (String) event.getDragboard().getContent(KORTTY_TAB_TRANSFER_FORMAT);
            TabTransfer xfer = pendingTabTransfers.remove(transferId);
            if (xfer == null) return;
            Tab tab = xfer.tab();
            MainWindow sourceWindow = xfer.sourceWindow();
            javafx.scene.control.TabPane sourcePane = sourceWindow.tabPane;
            if (!sourcePane.getTabs().contains(tab)) return;
            reorganizeTabs(() -> {
                sourcePane.getTabs().remove(tab);
                tabPane.getTabs().add(tabPane.getTabs().size(), tab);
                tabPane.getSelectionModel().select(tab);
            });
            if (tab instanceof TerminalTab tt) {
                installAiSelectionHandler(tt);
                // Re-bind the per-tab hooks to this window (the creation-time lambdas captured the source).
                registerTerminalTabForAiAgentDock(tt);
                Platform.runLater(() -> tt.getTerminalView().requestFocus());
            }
            event.setDropCompleted(true);
            event.consume();
            if (sourceWindow != this) {
                sourceWindow.updateDashboard();
                updateDashboard();
                updateAllTabContextMenus();
                sourceWindow.updateAllTabContextMenus();
                // The tab's panes keep their multi-exec membership; the window counts changed.
                MultiExecCoordinator.shared().refreshMarkers();
            } else {
                updateDashboard();
                updateAllTabContextMenus();
            }
        });
        
        // Status bar
        Region appDesignCursor = new Region();
        appDesignCursor.getStyleClass().add("app-design-cursor");
        appDesignCursor.setVisible(false);
        appDesignCursor.setManaged(false);
        Region statusSpacer = new Region();
        statusRow = new HBox(8, statusLabel, statusSpacer, appDesignCursor);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        installCodingAgentStatusStrip(statusSpacer);
        installMultiExecStatusBar(statusSpacer);
        statusBar = new VBox(statusRow);
        installRestoreAttentionBar();
        statusBar.getStyleClass().add("status-bar");
        statusLabel.getStyleClass().add("status-label");
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        statusLabel.setStyle("-fx-text-fill: #cccccc;");
        AppDesignAnimator.registerCursor(appDesignCursor);

        // While the menu bar is hidden, a right-click on the status bar offers to restore it.
        ContextMenu statusBarContextMenu = new ContextMenu();
        MenuItem statusBarShowMenuBarItem = new MenuItem(I18n.get("menu.view.menuBar"));
        statusBarShowMenuBarItem.setOnAction(e -> toggleMenuBarVisibility(true));
        statusBarContextMenu.getItems().add(statusBarShowMenuBarItem);
        statusBar.setOnContextMenuRequested(e -> {
            if (menuBar != null && !menuBar.isVisible()) {
                statusBarContextMenu.show(statusBar, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        
        // HBox for dashboard (fixed width) + tab pane (grows with window)
        mainContentBox.getChildren().add(tabPane);
        HBox.setHgrow(tabPane, Priority.ALWAYS);
        
        root.setCenter(mainContentBox);
        root.setBottom(statusBar);
        applyMainWindowThemeFromGlobalSettings();
        
        // Scene setup. `root` is wrapped in a StackPane so terminal-only fullscreen can shrink it to
        // its previous window size and let the StackPane center it on an empty backdrop; in the
        // normal case the wrapper is fully transparent and root fills it edge to edge as before.
        sceneRoot = new StackPane(root);
        Scene scene = new Scene(sceneRoot, scaledDefaultSceneWidth(), scaledDefaultSceneHeight());
        if (unifiedTitleBarEnabled || transparentWindowMode) {
            // Unified title bar: let the themed root background flow into the macOS title bar area.
            // Transparent mode: the scene fill must be clear so the desktop shows through the terminal.
            scene.setFill(Color.TRANSPARENT);
        }
        // The theme was applied before sceneRoot existed; give the wrapper its see-through style now.
        refreshTransparentModeContainers();
        
        // Mark this as a korTTY base-themed surface. The helper keeps terminal.css for Modena and
        // existing designs, but swaps it for component-only CSS when AtlantaFX owns native controls.
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
        
        // Window-wide keyboard shortcuts (menu bar, fullscreen, zoom, tab switching) go through one
        // ordered scene router; it also swallows the KEY_TYPED residue of every chord it consumes.
        createSceneShortcutRouter().install(scene);
        // A Ctrl+Tab cycle in most-recently-used order also ends when the window loses the focus;
        // the router ends it on the Ctrl release and on any other key.
        stage.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) {
                commitTabCycle();
            }
        });

        stage.setScene(scene);
        AppDesignStyleSupport.installGlobalWindowStyler();
        // Apply theme again now that scene exists (first call in setupUI had scene == null)
        applyMainWindowThemeFromGlobalSettings();
        stage.setTitle(KorTTYApplication.getAppName());
        
        // Set window icon (e.g. Windows taskbar and title bar)
        try {
            var iconUrl = getClass().getResource("/icon/kortty_icon.png");
            if (iconUrl != null) {
                stage.getIcons().add(new Image(iconUrl.toExternalForm()));
            }
        } catch (Exception e) {
            logger.warn("Could not set window icon", e);
        }
        
        // Handle fullscreen changes - resize terminal properly
        stage.fullScreenProperty().addListener((obs, wasFullscreen, isFullscreen) -> {
            if (!isFullscreen && terminalOnlyFullscreenActive) {
                setTerminalOnlyFullscreen(false);
            }
            // Fullscreen is intentionally solid. Keep the persisted percentage untouched and
            // restore it across all panes as soon as the window leaves fullscreen again.
            applyBackgroundTransparencyToAllTabs();
            refreshTransparentModeContainers();
            applyTerminalScrollbarVisibilityForOpenTabs();
            Platform.runLater(() -> {
                // Give layout time to update, then resize terminal
                Platform.runLater(() -> {
                    Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                    if (selectedTab instanceof TerminalTab terminalTab) {
                        // Request focus to trigger proper resize
                        terminalTab.getTerminalView().requestFocus();
                    }
                });
            });
        });
        
        // Window close handling
        stage.setOnCloseRequest(e -> {
            // Save window geometry on close if enabled (not when using fixed geometry)
            GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
            
            // Always save last geometry (for next session) unless fixed geometry is used.
            // One path for both close and live persistence, so the maximized handling matches.
            persistWindowGeometry();
            
            // Save dashboard state on close if enabled
            if (globalSettings.isRememberDashboardState()) {
                globalSettings.setDashboardVisible(dashboardVisible);
            }

            // Save file-browser placement + width on close
            if (fileBrowserManager != null) {
                globalSettings.setFileBrowserPosition(fileBrowserManager.getPosition().name());
                globalSettings.setFileBrowserWidth(fileBrowserManager.getPreferredWidth());
            }

            // Save live journal panel placement + width on close
            if (journalLiveDockManager != null) {
                globalSettings.setJournalLivePanelPlacement(journalLiveDockManager.getPlacement().name());
                globalSettings.setJournalLivePanelWidth(journalLiveDockManager.getPreferredWidth());
            }

            // Save Coding Agents panel placement + width on close
            if (codingAgentDockManager != null) {
                globalSettings.setCodingAgentPanelPlacement(codingAgentDockManager.getPlacement().name());
                globalSettings.setCodingAgentPanelWidth(codingAgentDockManager.getPreferredWidth());
            }

            // Save settings BEFORE confirmClose (which might exit the app)
            try {
                app.getGlobalSettingsManager().save();
                logger.info("Window settings saved successfully");
            } catch (Exception ex) {
                logger.error("Failed to save window settings", ex);
            }
            
            boolean closeConfirmed = applicationQuitApprovedWindows.remove(this) || confirmClose();
            if (!closeConfirmed) {
                clearApplicationQuitState();
                e.consume();
            } else {
                if (willCloseApplication()) {
                    // korTTY ends with this window (Quit, or the last window on Windows and Linux):
                    // the session snapshot is written and sealed while the tabs are still open.
                    sealSessionSnapshotForExit();
                }
                stopJobSchedulerStatusUpdates();
                stopAgentStatusIndicatorTimer();
                if (guideTranslationIndicator != null) {
                    guideTranslationIndicator.dispose();
                }
                // Every prompt passed (confirmClose or the quit approval): only now close the
                // snippet workspace window and this window's standalone editors without asking.
                closeSnippetEditorsWithoutPrompt();
                // Before the tabs are released: the window's terminal tabs become one Recently Closed
                // entry, unless korTTY ends with this window (Quit, or the last window on Windows and Linux).
                recordClosedWindow(willCloseApplication());
                closeAllTabs();
                // Its panes left multi-exec with the tabs; the other windows' chips have counted that.
                releaseMultiExecListener();
                // Deregister file browser manager listener to prevent memory leaks and stale callbacks
                if (fileBrowserManager != null && fileBrowserPositionListener != null) {
                    fileBrowserManager.removePositionListener(fileBrowserPositionListener);
                    fileBrowserPositionListener = null;
                }
                // Deregister the AI-agent placement listener (per-window manager dies with the window).
                if (aiAgentDockManager != null && aiAgentPlacementListener != null) {
                    aiAgentDockManager.removePlacementListener(aiAgentPlacementListener);
                    aiAgentPlacementListener = null;
                }
                // Detach the live journal panel's feed and its placement listener.
                if (journalLivePanel != null) {
                    journalLivePanel.unbind();
                }
                if (journalLiveDockManager != null && journalLivePlacementListener != null) {
                    journalLiveDockManager.removePlacementListener(journalLivePlacementListener);
                    journalLivePlacementListener = null;
                }
                // Coding agents: unsubscribe the panel, strip and dashboard from the registry and
                // stop their timers; the placement listener dies with the per-window manager.
                if (codingAgentDockManager != null && codingAgentPlacementListener != null) {
                    codingAgentDockManager.removePlacementListener(codingAgentPlacementListener);
                    codingAgentPlacementListener = null;
                }
                if (codingAgentWidthSaveDelay != null) {
                    codingAgentWidthSaveDelay.stop();
                }
                if (codingAgentPanel != null) {
                    codingAgentPanel.dispose();
                }
                if (codingAgentStatusStrip != null) {
                    codingAgentStatusStrip.dispose();
                }
                if (dashboardView != null) {
                    dashboardView.dispose();
                }
                if (powerManagementStateListener != null && app.getPowerManagementCoordinator() != null) {
                    app.getPowerManagementCoordinator().removeListener(powerManagementStateListener);
                    powerManagementStateListener = null;
                }
                openWindows.remove(this);
                if (lastFocusedWindow == this) {
                    lastFocusedWindow = null;
                }
                // A window closed while korTTY keeps running leaves the session snapshot.
                markSessionDirty();

                // On macOS the application stays alive after the last window closes so the
                // dock icon can reopen a new window without restarting the process.
                if (openWindows.isEmpty()) {
                    if (applicationQuitRequested) {
                        logger.info("Application quit requested, exiting");
                        clearApplicationQuitState();
                        app.shutdownAndExit();
                        return;
                    }

                    if (app.shouldKeepRunningAfterLastWindowClosed()) {
                        logger.info("Last macOS window closed, keeping application alive");
                        return;
                    }

                    logger.info("Last window closed, exiting application");
                    app.shutdownAndExit();
                }
            }
        });
    }
    
    /**
     * The unscaled default window size, used on first launch when no geometry has been remembered.
     * It grows with the UI font scale so a scaled-up chrome does not arrive in a window sized for
     * the unscaled one.
     */
    private static final double DEFAULT_SCENE_WIDTH = 1000;
    private static final double DEFAULT_SCENE_HEIGHT = 700;

    private static double scaledDefaultSceneWidth() {
        return UiFontScaleSupport.scaleDimension(DEFAULT_SCENE_WIDTH, true);
    }

    private static double scaledDefaultSceneHeight() {
        return UiFontScaleSupport.scaleDimension(DEFAULT_SCENE_HEIGHT, false);
    }

    private void setupMenuBar() {
        menuBar = createApplicationMenuBar(MenuBarTarget.WINDOW);
        // The menu bar goes into a row so a right-aligned indicator can sit on it. It must be
        // a SIBLING of the bar, not a menu inside it: applyMenuBarVisibility hides the bar itself,
        // and a running translation has to stay visible even then.
        // The bar fills the row so its themed background covers the full window width — it is the
        // only node here that paints one. The indicator is laid OVER that background rather than
        // beside it; as a neighbour it would leave an unpainted strip in window colour next to it.
        guideTranslationIndicator = new GuideTranslationIndicator();
        Region indicatorNode = guideTranslationIndicator.getNode();
        menuBar.setMaxWidth(Double.MAX_VALUE);
        // Without this the StackPane would stretch the indicator across the whole row too.
        indicatorNode.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane menuRow = new StackPane(menuBar, indicatorNode);
        StackPane.setAlignment(indicatorNode, Pos.CENTER_RIGHT);
        if (isMacOs()) {
            systemMenuBar = createApplicationMenuBar(MenuBarTarget.SYSTEM);
            systemMenuBar.setUseSystemMenuBar(true);
            systemMenuBar.setManaged(false);
            systemMenuBar.setVisible(false);
            // The visible in-window menu bar already registers its accelerators in the scene. The
            // native companion bar would register the SAME accelerators with the system menu, so
            // every shortcut fired twice. Strip accelerators from the companion bar; the in-window
            // bar remains the single owner (and still shows the shortcut labels).
            clearMenuBarAccelerators(systemMenuBar);
            // Keep a hidden companion menu bar attached to the scene so macOS can
            // continue to show the application menu even when the in-window bar is hidden.
            VBox menuContainer = new VBox(systemMenuBar, menuRow);
            root.setTop(menuContainer);
            MenuBarSkin.setDefaultSystemMenuBar(systemMenuBar);
        } else {
            root.setTop(menuRow);
        }
        // macOS keeps showing this window's menu bar after it closed; its items then act in an open
        // window, or in a new one (ClosedWindowMenuRouter).
        ClosedWindowMenuRouter.install(this, window -> window.menuBar.getMenus(), MENU_BAR_WINDOWS);
        if (systemMenuBar != null) {
            ClosedWindowMenuRouter.install(this, MainWindow::systemMenus, MENU_BAR_WINDOWS);
        }
        // The user's shortcut overrides: on the in-window menu bar and the scene shortcut router.
        applyKeymap();
        // The menu bar always starts visible; hiding it is session-only and never persisted.
        applyMenuBarVisibility(true);
        syncDashboardMenuItems(shouldRestoreDashboardOnStartup());
        syncTimestampMenuItems(false);
        syncPaneMenuItems();
        // The history belongs to the application: a new window offers what other windows closed.
        syncRecentlyClosedMenus();
        syncOpenRecentMenus();
        refreshRecentProjects();
        syncRestorePreviousSessionMenuItems();
        applyMainWindowThemeFromGlobalSettings();
        syncAiFeaturesMenuItemsEnabled();
        startJobSchedulerStatusUpdates();
    }

    /** Removes all keyboard accelerators from a menu bar (used for the macOS companion system bar). */
    private static void clearMenuBarAccelerators(MenuBar bar) {
        if (bar == null) {
            return;
        }
        for (Menu menu : bar.getMenus()) {
            clearMenuAccelerators(menu);
        }
    }

    private static void clearMenuAccelerators(Menu menu) {
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu submenu) {
                clearMenuAccelerators(submenu);
            } else if (item != null) {
                item.setAccelerator(null);
            }
        }
    }

    /** The open windows as the menu bar of a closed window sees them (see {@link ClosedWindowMenuRouter}). */
    private static final ClosedWindowMenuRouter.Windows<MainWindow> MENU_BAR_WINDOWS =
        new ClosedWindowMenuRouter.Windows<>() {
            @Override
            public boolean isOpen(MainWindow window) {
                return openWindows.contains(window);
            }

            @Override
            public MainWindow frontmostOpen() {
                return getFrontmostOpenWindow();
            }

            @Override
            public MainWindow openNew() {
                reopenOrCreateWindow();
                return getFrontmostOpenWindow();
            }

            @Override
            public void bringToFront(MainWindow window) {
                window.bringToFront();
            }
        };

    private static List<Menu> systemMenus(MainWindow window) {
        return window.systemMenuBar != null ? window.systemMenuBar.getMenus() : List.of();
    }

    /**
     * The focused window, else the one that had the focus last, else the one opened last; {@code null}
     * when no window is open.
     */
    private static MainWindow getFrontmostOpenWindow() {
        return ClosedWindowMenuRouter.frontmost(openWindows, window -> window.stage.isFocused(), lastFocusedWindow);
    }

    /** Shows this window in front of the others, restoring it from the Dock first when it is minimized. */
    private void bringToFront() {
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private void syncAiFeaturesMenuItemsEnabled() {
        boolean enabled = false;
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            if (gs != null) {
                enabled = gs.isAiFeaturesEnabled();
            }
        } catch (Exception e) {
            logger.debug("syncAiFeaturesMenuItemsEnabled: {}", e.getMessage());
        }
        boolean agentExecutionEnabled = enabled && isTerminalAgentExecutionEnabled();
        for (MenuItem menuItem : toolsAiMenuItems) {
            menuItem.setDisable(!enabled);
        }
        for (MenuItem menuItem : toolsAiAgentExecutionMenuItems) {
            menuItem.setDisable(!agentExecutionEnabled);
        }
    }

    private boolean isAiFeaturesEnabled() {
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            return gs != null && gs.isAiFeaturesEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTerminalAgentExecutionEnabled() {
        if (!isAiFeaturesEnabled()) {
            return false;
        }
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            return gs == null || gs.isTerminalAgentExecutionEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldConfirmTerminalAgentMutatingCommandSets() {
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            return gs == null || gs.isTerminalAgentConfirmMutatingCommandSets();
        } catch (Exception e) {
            logger.warn("Could not read terminal agent confirmation settings; requiring mutating command confirmation.", e);
            return true;
        }
    }

    private void startJobSchedulerStatusUpdates() {
        if (!isForegroundWindow() || jobSchedulerStatusTimeline != null) {
            return;
        }
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService != null) {
            jobSchedulerStatusListener = () -> Platform.runLater(() -> {
                if (isForegroundWindow()) {
                    updateJobSchedulerStatusMenus();
                }
            });
            schedulerService.addListener(jobSchedulerStatusListener);
        }
        jobSchedulerStatusTimeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> updateJobSchedulerStatusMenus()));
        jobSchedulerStatusTimeline.setCycleCount(Timeline.INDEFINITE);
        jobSchedulerStatusTimeline.play();
        updateJobSchedulerStatusMenus();
    }

    private void stopJobSchedulerStatusUpdates() {
        if (jobSchedulerStatusTimeline != null) {
            jobSchedulerStatusTimeline.stop();
            jobSchedulerStatusTimeline = null;
        }
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService != null && jobSchedulerStatusListener != null) {
            schedulerService.removeListener(jobSchedulerStatusListener);
        }
        jobSchedulerStatusListener = null;
    }

    private void installForegroundActivityLifecycle() {
        stage.showingProperty().addListener((observable, oldValue, newValue) -> updateForegroundActivity());
        stage.focusedProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) {
                lastFocusedWindow = this;
            }
            updateForegroundActivity();
        });
        stage.iconifiedProperty().addListener((observable, oldValue, newValue) -> updateForegroundActivity());
    }

    private void updateForegroundActivity() {
        boolean foreground = isForegroundWindow();
        if (foreground) {
            // The window came to the front: its selected tab is seen again.
            clearAttentionOfSeenTab();
            startJobSchedulerStatusUpdates();
            if (!terminalTabs().isEmpty()) {
                startAgentStatusIndicatorTimer();
                refreshAgentStatusIndicators();
            }
            updateJobSchedulerStatusMenus();
        } else {
            stopJobSchedulerStatusUpdates();
            stopAgentStatusIndicatorTimer();
        }
        // Coding agents: the BLOCKED pulse only runs in the foreground window, and a window coming
        // to the front may now "see" a DONE agent.
        if (codingAgentStatusStrip != null) {
            codingAgentStatusStrip.setWindowActive(foreground);
        }
        if (codingAgentPanel != null) {
            codingAgentPanel.setWindowActive(foreground);
        }
        onCodingAgentFocusContextChanged();
        AppDesignAnimator.refreshAll();
    }

    /**
     * Removes the attention mark of the tab the user is looking at: the selected terminal tab of this
     * window while it is in front (see {@link PaneSeenOracle}). Called on tab selection and when the
     * window comes to the front.
     */
    private void clearAttentionOfSeenTab() {
        TerminalTab seen = isForegroundWindow() ? getActiveTerminalTab() : null;
        if (seen != null) {
            seen.clearAttention();
        }
    }

    /** True while this window is showing, not iconified and focused (the foreground window). */
    public boolean isForegroundWindow() {
        return shouldRunForegroundPolling(stage.isShowing(), stage.isIconified(), stage.isFocused());
    }

    static boolean shouldRunForegroundPolling(boolean showing, boolean iconified, boolean focused) {
        return showing && !iconified && focused;
    }

    private void updateJobSchedulerStatusMenus() {
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        String text = buildJobSchedulerMenuTitle();
        updateJobSchedulerStatusMenu(jobSchedulerStatusMenu, schedulerService, text);
        updateJobSchedulerStatusMenu(systemJobSchedulerStatusMenu, schedulerService, text);
    }

    private void updateJobSchedulerStatusMenu(Menu menu, JobSchedulerService schedulerService, String text) {
        if (menu == null) {
            return;
        }
        boolean visible = shouldShowJobSchedulerStatusMenu(schedulerService);
        menu.setVisible(visible);
        if (!visible) {
            menu.getItems().clear();
            return;
        }
        if (menu.getGraphic() instanceof Label label) {
            label.setText(text);
        }
        if (menu.getGraphic() == null) {
            boolean spacedText = Boolean.TRUE.equals(menu.getProperties().get(JOB_SCHEDULER_STATUS_SPACED_TEXT_PROPERTY));
            menu.setText(spacedText ? "  " + text : text);
        } else {
            menu.setText("");
        }
        updateVisibleJobSchedulerQueueItemTimers(menu);
    }

    private boolean shouldShowJobSchedulerStatusMenu(JobSchedulerService schedulerService) {
        if (!isJobSchedulerMenuStatusEnabled() || schedulerService == null) {
            return false;
        }
        if (!schedulerService.getActiveJobSummaries().isEmpty()) {
            return true;
        }
        return schedulerService.getJobs().stream().anyMatch(this::isActiveSchedulerEntry);
    }

    private boolean isJobSchedulerMenuStatusEnabled() {
        try {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            return settings == null || settings.isJobSchedulerMenuStatusEnabled();
        } catch (Exception e) {
            logger.debug("Could not read JobScheduler menu status setting", e);
            return true;
        }
    }

    private boolean isActiveSchedulerEntry(ScheduledJob job) {
        return job != null && job.isEnabled() && job.getSchedule().isEnabled();
    }

    private String buildJobSchedulerMenuTitle() {
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService == null) {
            return I18n.get("jobscheduler.menu.noJobs");
        }
        List<ActiveJobSummary> activeJobs = schedulerService.getActiveJobSummaries();
        if (!activeJobs.isEmpty()) {
            long cancelling = activeJobs.stream().filter(ActiveJobSummary::cancellationRequested).count();
            if (activeJobs.size() == 1) {
                ActiveJobSummary active = activeJobs.get(0);
                return I18n.get(
                    active.cancellationRequested() ? "jobscheduler.menu.cancellingOne" : "jobscheduler.menu.runningOne",
                    nonBlank(active.jobName(), active.jobId()));
            }
            return cancelling > 0
                ? I18n.get("jobscheduler.menu.cancellingMany", cancelling, activeJobs.size())
                : I18n.get("jobscheduler.menu.runningMany", activeJobs.size());
        }

        List<NextScheduledJob> queuedJobs = nextScheduledJobs(schedulerService);
        if (!queuedJobs.isEmpty()) {
            NextScheduledJob scheduled = queuedJobs.get(0);
            return I18n.get(
                "jobscheduler.menu.next",
                displayJobName(scheduled.job()),
                formatRemainingTime(scheduled.nextRun()));
        }

        return schedulerService.getJobs().isEmpty()
            ? I18n.get("jobscheduler.menu.noJobs")
            : I18n.get("jobscheduler.menu.noNext");
    }

    private void rebuildJobSchedulerStatusMenuItems(Menu menu) {
        if (menu == null) {
            return;
        }
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        menu.getItems().clear();

        if (!shouldShowJobSchedulerStatusMenu(schedulerService)) {
            return;
        }

        MenuItem openScheduler = new MenuItem(I18n.get("jobscheduler.menu.open"));
        openScheduler.setOnAction(event -> showJobScheduler());
        menu.getItems().add(openScheduler);

        if (schedulerService == null) {
            return;
        }

        List<ActiveJobSummary> activeJobs = schedulerService.getActiveJobSummaries();
        if (!activeJobs.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem runningHeader = new MenuItem(I18n.get("jobscheduler.menu.runningHeader"));
            runningHeader.setDisable(true);
            menu.getItems().add(runningHeader);
            for (ActiveJobSummary active : activeJobs) {
                String jobName = nonBlank(active.jobName(), active.jobId());
                MenuItem status = new MenuItem(active.cancellationRequested()
                    ? I18n.get("jobscheduler.menu.cancellingItem", jobName)
                    : I18n.get("jobscheduler.menu.runningItem", jobName));
                status.setDisable(true);
                menu.getItems().add(status);
                MenuItem cancel = new MenuItem(I18n.get("jobscheduler.menu.cancel", jobName));
                cancel.setDisable(active.cancellationRequested());
                cancel.setOnAction(event -> schedulerService.cancelJob(active.jobId()));
                ClosedWindowMenuRouter.noWindowNeeded(cancel);
                menu.getItems().add(cancel);
            }
        }

        List<NextScheduledJob> queuedJobs = nextScheduledJobs(schedulerService);
        if (!queuedJobs.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem queueHeader = new MenuItem(I18n.get("jobscheduler.menu.queueHeader"));
            queueHeader.setDisable(true);
            menu.getItems().add(queueHeader);
            for (NextScheduledJob scheduled : queuedJobs) {
                menu.getItems().add(createQueuedJobMenuItem(scheduled));
            }
        }
    }

    private MenuItem createQueuedJobMenuItem(NextScheduledJob scheduled) {
        MenuItem item = new MenuItem();
        item.setDisable(true);
        item.getProperties().put(JOB_SCHEDULER_QUEUE_JOB_NAME_PROPERTY, displayJobName(scheduled.job()));
        item.getProperties().put(JOB_SCHEDULER_QUEUE_NEXT_RUN_PROPERTY, scheduled.nextRun());
        updateQueuedJobMenuItem(item);
        return item;
    }

    private void updateVisibleJobSchedulerQueueItemTimers(Menu menu) {
        if (menu == null || !menu.isShowing()) {
            return;
        }
        for (MenuItem item : menu.getItems()) {
            updateQueuedJobMenuItem(item);
        }
    }

    private void updateQueuedJobMenuItem(MenuItem item) {
        Object jobName = item.getProperties().get(JOB_SCHEDULER_QUEUE_JOB_NAME_PROPERTY);
        Object nextRun = item.getProperties().get(JOB_SCHEDULER_QUEUE_NEXT_RUN_PROPERTY);
        if (jobName instanceof String name && nextRun instanceof ZonedDateTime runAt) {
            item.setText(I18n.get(
                "jobscheduler.menu.queueItem",
                name,
                formatMenuStartTime(runAt),
                formatRemainingTime(runAt)));
        }
    }

    private void showJobSchedulerStatusContextMenu(Label label) {
        ContextMenu contextMenu = new ContextMenu();
        JobSchedulerService schedulerService = app.getJobSchedulerService();
        if (schedulerService != null) {
            for (ActiveJobSummary active : schedulerService.getActiveJobSummaries()) {
                String jobName = nonBlank(active.jobName(), active.jobId());
                MenuItem cancel = new MenuItem(I18n.get("jobscheduler.menu.cancel", jobName));
                cancel.setDisable(active.cancellationRequested());
                cancel.setOnAction(event -> schedulerService.cancelJob(active.jobId()));
                contextMenu.getItems().add(cancel);
            }
        }
        if (!contextMenu.getItems().isEmpty()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem openScheduler = new MenuItem(I18n.get("jobscheduler.menu.open"));
        openScheduler.setOnAction(event -> showJobScheduler());
        contextMenu.getItems().add(openScheduler);
        contextMenu.show(label, Side.BOTTOM, 0, 0);
    }

    private List<NextScheduledJob> nextScheduledJobs(JobSchedulerService schedulerService) {
        ZonedDateTime now = ZonedDateTime.now();
        return schedulerService.getJobs().stream()
            .filter(job -> job.isEnabled() && job.getNextRunAt() != null && !job.getNextRunAt().isBlank())
            .map(job -> parseJobNextRun(job)
                .filter(nextRun -> nextRun.isAfter(now))
                .map(nextRun -> new NextScheduledJob(job, nextRun)))
            .flatMap(Optional::stream)
            .sorted((left, right) -> left.nextRun().compareTo(right.nextRun()))
            .limit(JOB_SCHEDULER_QUEUE_LIMIT)
            .toList();
    }

    private Optional<ZonedDateTime> parseJobNextRun(ScheduledJob job) {
        try {
            return Optional.of(ZonedDateTime.parse(job.getNextRunAt()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String formatRemainingTime(ZonedDateTime nextRun) {
        long seconds = Math.max(0, java.time.Duration.between(ZonedDateTime.now(), nextRun).getSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %02d:%02d:%02d", days, hours, minutes, secs);
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }

    private String formatMenuStartTime(ZonedDateTime nextRun) {
        return nextRun.format(JOB_SCHEDULER_MENU_TIME_FORMAT);
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String displayJobName(ScheduledJob job) {
        return job != null ? nonBlank(job.getName(), job.getId()) : "";
    }

    private record NextScheduledJob(ScheduledJob job, ZonedDateTime nextRun) {
    }

    private MenuBar createApplicationMenuBar(MenuBarTarget target) {
        MenuBar createdMenuBar = new MenuBar();
        createdMenuBar.getMenus().addAll(
            createFileMenu(),
            createEditMenu(target),
            createConnectionsMenu(),
            createConfigurationMenu(),
            createToolsMenu(),
            createAiMenu(),
            createTeamworkMenu(),
            createPluginsMenu(),
            createViewMenu(target),
            createHelpMenu(),
            createJobSchedulerStatusMenu(target)
        );
        return createdMenuBar;
    }

    /**
     * A menu-bar item labelled {@code I18n.get(key)} whose stable action id ({@link ActionIds#tag})
     * is that key, so the command palette, its recently-used list and later key bindings find the
     * item whatever the UI language. Every leaf item of the create*Menu builders comes from here or
     * from {@link #checkMenuItem}, on both macOS menu bars; an item whose label is computed is tagged
     * explicitly (About, Prevent Sleep). Pinned by MainWindowActionIdsTest.
     */
    static MenuItem menuItem(String key) {
        return ActionIds.tag(new MenuItem(I18n.get(key)), key);
    }

    /** {@link #menuItem} for an item with an on/off state. */
    static CheckMenuItem checkMenuItem(String key) {
        return ActionIds.tag(new CheckMenuItem(I18n.get(key)), key);
    }

    /**
     * Disables a menu-bar item the organization's policy denies for good, and marks it as such
     * ({@link ActionIds#markPolicyLocked}), so the command palette can say why it does not run. Such
     * items stay out of the enable-sync lists, which would switch them back on.
     */
    static void lockByPolicy(MenuItem item) {
        item.setDisable(true);
        ActionIds.markPolicyLocked(item);
    }

    private Menu createFileMenu() {
        Menu fileMenu = new Menu(I18n.get("menu.file"));

        MenuItem newTab = menuItem("menu.file.newTab");
        newTab.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        newTab.setOnAction(e -> showQuickConnect());

        // No shortcut: F2 and the other free keys belong to the program in the terminal.
        MenuItem renameTab = menuItem("menu.file.renameTab");
        renameTab.setOnAction(e -> {
            if (tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab) {
                promptRenameTab(terminalTab);
            }
        });

        MenuItem closeTab = menuItem("menu.file.closeTab");
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> closeCurrentTab());
        ClosedWindowMenuRouter.ownWindowOnly(closeTab);

        // Both act around the selected tab of any kind; no shortcut either.
        MenuItem closeOthers = menuItem("menu.file.closeOtherTabs");
        closeOthers.setOnAction(e -> closeOtherTabs(tabPane.getSelectionModel().getSelectedItem()));
        ClosedWindowMenuRouter.ownWindowOnly(closeOthers);

        MenuItem closeToRight = menuItem("menu.file.closeTabsToRight");
        closeToRight.setOnAction(e -> closeTabsToTheRight(tabPane.getSelectionModel().getSelectedItem()));
        ClosedWindowMenuRouter.ownWindowOnly(closeToRight);

        MenuItem closeAllTabs = menuItem("menu.file.closeAllTabs");
        closeAllTabs.setOnAction(e -> confirmAndCloseAllTabs());
        ClosedWindowMenuRouter.ownWindowOnly(closeAllTabs);

        MenuItem reopenClosedTab = menuItem("menu.file.reopenClosedTab");
        // Shown here; the scene shortcut router handles the key, also while a terminal has the focus.
        reopenClosedTab.setAccelerator(REOPEN_CLOSED_TAB_ACCELERATOR);
        reopenClosedTab.setOnAction(e -> reopenClosedTab());
        reopenClosedTabMenuItems.add(reopenClosedTab);

        // Rebuilt from the application-wide history whenever it changes (syncRecentlyClosedMenus), so it
        // stays out of the action harvest, where its entries would go stale.
        Menu recentlyClosed = ActionIds.exclude(new Menu(I18n.get("menu.file.recentlyClosed")));
        recentlyClosedMenus.add(recentlyClosed);

        MenuItem newWindow = menuItem("menu.file.newWindow");
        newWindow.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        newWindow.setOnAction(e -> openNewWindow());
        ClosedWindowMenuRouter.noWindowNeeded(newWindow);

        MenuItem closeWindow = menuItem("menu.file.closeWindow");
        closeWindow.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        closeWindow.setOnAction(e -> fireCloseRequest());
        ClosedWindowMenuRouter.ownWindowOnly(closeWindow);

        MenuItem openProject = menuItem("menu.file.openProject");
        openProject.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        openProject.setOnAction(e -> openProject());

        // Rebuilt from the connections used last and the recent projects whenever the File menu opens
        // (syncOpenRecentMenus), so it stays out of the action harvest, where its entries would go stale.
        Menu openRecent = ActionIds.exclude(new Menu(I18n.get("menu.file.openRecent")));
        openRecentMenus.add(openRecent);

        MenuItem saveProject = menuItem("menu.file.saveProject");
        saveProject.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveProject.setOnAction(e -> saveProject());

        // Opens the windows and tabs of the session before this start; enabled while there is one.
        // In a closed macOS window it acts in the frontmost open window, else a new one, like Open Project.
        MenuItem restorePreviousSession = menuItem("menu.file.restorePreviousSession");
        restorePreviousSession.setOnAction(e -> restorePreviousSession(RestoreTrigger.MENU));
        restorePreviousSessionMenuItems.add(restorePreviousSession);

        MenuItem createBackup = menuItem("menu.edit.createBackup");
        createBackup.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        createBackup.setOnAction(e -> createBackup());

        MenuItem importBackup = menuItem("menu.edit.importBackup");
        importBackup.setOnAction(e -> importBackup());

        MenuItem quit = menuItem("menu.file.quit");
        quit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        quit.setOnAction(e -> requestApplicationQuit());
        ClosedWindowMenuRouter.noWindowNeeded(quit);

        // Both macOS menu bars come from this factory, so each one sets its own state when it opens.
        fileMenu.setOnShowing(e -> {
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            renameTab.setDisable(!(selected instanceof TerminalTab));
            syncTabCloseItems(selected, closeOthers, closeToRight);
            syncRecentlyClosedMenus();
            // From what is known now; the project files are looked up in the background, and the
            // menu follows if they changed.
            syncOpenRecentMenus();
            refreshRecentProjects();
            syncRestorePreviousSessionMenuItems();
        });

        fileMenu.getItems().addAll(
            newTab, renameTab, closeTab, closeOthers, closeToRight, closeAllTabs,
            reopenClosedTab, recentlyClosed, new SeparatorMenuItem(),
            newWindow, closeWindow, new SeparatorMenuItem(),
            openProject, openRecent, saveProject, restorePreviousSession, new SeparatorMenuItem(),
            createBackup, importBackup, new SeparatorMenuItem(), quit);
        return fileMenu;
    }

    private Menu createEditMenu(MenuBarTarget target) {
        Menu editMenu = new Menu(I18n.get("menu.edit"));

        MenuItem cut = menuItem("menu.edit.cut");
        cut.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN));
        cut.setOnAction(e -> cutFromCurrentContext());
        ClosedWindowMenuRouter.ownWindowOnly(cut);
        if (target == MenuBarTarget.WINDOW) {
            cutMenuItem = cut;
        } else {
            systemCutMenuItem = cut;
        }
        updateEditMenuItemsForSelection();

        MenuItem copy = menuItem("menu.edit.copy");
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copy.setOnAction(e -> copyFromTerminal());
        ClosedWindowMenuRouter.ownWindowOnly(copy);

        MenuItem paste = menuItem("menu.edit.paste");
        paste.setAccelerator(PASTE_ACCELERATOR);
        paste.setOnAction(e -> pasteToTerminal());
        ClosedWindowMenuRouter.ownWindowOnly(paste);

        MenuItem find = menuItem("menu.edit.find");
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        find.setOnAction(e -> findInCurrentTab());

        MenuItem quickSelect = menuItem("menu.edit.quickSelect");
        quickSelect.setAccelerator(QUICK_SELECT_ACCELERATOR);
        quickSelect.setOnAction(e -> quickSelectInCurrentTab());
        if (target == MenuBarTarget.WINDOW) {
            quickSelectMenuItem = quickSelect;
        } else {
            systemQuickSelectMenuItem = quickSelect;
        }

        // Shown here; while a terminal has the focus its pane's own key actions take the keys.
        MenuItem previousPrompt = ActionIds.tag(new MenuItem(I18n.get("menu.edit.previousPrompt")),
            "menu.edit.previousPrompt");
        previousPrompt.setAccelerator(PREVIOUS_PROMPT_ACCELERATOR);
        previousPrompt.setOnAction(e -> jumpToPromptInCurrentTab(PromptNavigator.Direction.PREVIOUS));
        MenuItem nextPrompt = ActionIds.tag(new MenuItem(I18n.get("menu.edit.nextPrompt")), "menu.edit.nextPrompt");
        nextPrompt.setAccelerator(NEXT_PROMPT_ACCELERATOR);
        nextPrompt.setOnAction(e -> jumpToPromptInCurrentTab(PromptNavigator.Direction.NEXT));
        // The output of the newest command the shell marked as finished; no key of their own.
        MenuItem selectLastOutput = ActionIds.tag(new MenuItem(I18n.get("menu.edit.selectLastOutput")),
            "menu.edit.selectLastOutput");
        selectLastOutput.setOnAction(e -> lastOutputInCurrentTab(ShellIntegrationController.LastOutputAction.SELECT));
        MenuItem copyLastOutput = ActionIds.tag(new MenuItem(I18n.get("menu.edit.copyLastOutput")),
            "menu.edit.copyLastOutput");
        copyLastOutput.setOnAction(e -> lastOutputInCurrentTab(ShellIntegrationController.LastOutputAction.COPY));
        shellIntegrationMenuItems.addAll(List.of(previousPrompt, nextPrompt, selectLastOutput, copyLastOutput));
        updateEditMenuItemsForSelection();

        editMenu.getItems().addAll(cut, copy, paste, new SeparatorMenuItem(), find, quickSelect,
            new SeparatorMenuItem(), previousPrompt, nextPrompt, selectLastOutput, copyLastOutput);
        return editMenu;
    }

    private Menu createConnectionsMenu() {
        Menu connectionsMenu = new Menu(I18n.get("menu.connections"));

        MenuItem quickConnect = menuItem("menu.connections.quickConnect");
        quickConnect.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN));
        quickConnect.setOnAction(e -> showQuickConnect());

        MenuItem manageConnections = menuItem("menu.connections.manage");
        manageConnections.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));
        manageConnections.setOnAction(e -> showConnectionManager());

        MenuItem importConnections = menuItem("menu.connections.import");
        importConnections.setOnAction(e -> importConnections());

        MenuItem exportConnections = menuItem("menu.connections.export");
        exportConnections.setOnAction(e -> exportConnections());

        MenuItem sftpClient = menuItem("menu.connections.sftpClient");
        sftpClient.setAccelerator(new KeyCodeCombination(KeyCode.U, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        sftpClient.setOnAction(e -> showSFTPManager());

        // SFTP on the focused pane's own session, in its folder; acts on this window's terminal.
        MenuItem sftpHere = menuItem("menu.connections.sftpHere");
        sftpHere.setOnAction(e -> {
            if (tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab
                    && terminalTab.isConnected()) {
                openSftpHere(terminalTab, null);
            } else {
                updateStatus(I18n.get("sftp.openHere.noTerminal"));
            }
        });
        ClosedWindowMenuRouter.ownWindowOnly(sftpHere);

        connectionsMenu.getItems().addAll(quickConnect, manageConnections,
            new SeparatorMenuItem(), importConnections, exportConnections,
            new SeparatorMenuItem(), sftpClient, sftpHere);
        return connectionsMenu;
    }

    private Menu createConfigurationMenu() {
        Menu configurationMenu = new Menu(I18n.get("menu.configuration"));

        Menu securityMenu = new Menu(I18n.get("menu.security"));

        // Enabled only while a master password exists but was not entered this session.
        MenuItem unlockVault = menuItem("menu.security.unlockVault");
        unlockVault.setOnAction(e -> unlockVaultFromMenu());
        unlockVaultMenuItems.add(unlockVault);
        securityMenu.setOnShowing(e -> syncUnlockVaultMenuItems());
        syncUnlockVaultMenuItems();

        MenuItem manageCredentials = menuItem("menu.security.credentials");
        // Shown here; the scene shortcut router handles the key, also while a terminal has the focus.
        manageCredentials.setAccelerator(CREDENTIALS_ACCELERATOR);
        manageCredentials.setOnAction(e -> showCredentialManagement());

        MenuItem manageGPGKeys = menuItem("menu.security.gpgKeys");
        manageGPGKeys.setAccelerator(new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        manageGPGKeys.setOnAction(e -> showGPGKeyManagement());

        MenuItem manageSSHKeys = menuItem("menu.security.sshKeys");
        manageSSHKeys.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        manageSSHKeys.setOnAction(e -> showSSHKeyManagement());

        MenuItem knownHosts = menuItem("menu.security.knownHosts");
        knownHosts.setOnAction(e -> showKnownHosts());

        securityMenu.getItems().addAll(unlockVault, new SeparatorMenuItem(),
            manageCredentials, manageGPGKeys, manageSSHKeys, knownHosts);

        MenuItem settings = menuItem("menu.settings.global");
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(e -> showSettings());

        // The label says whether this system supports it (syncPreventSleepMenuItems), so the id is given here.
        CheckMenuItem preventSleep = ActionIds.tag(new CheckMenuItem(), "menu.configuration.preventSleep");
        preventSleep.setOnAction(e -> setManualSleepPrevention(preventSleep.isSelected()));
        ClosedWindowMenuRouter.noWindowNeeded(preventSleep);
        preventSleepMenuItems.add(preventSleep);
        installPowerManagementStateListener();
        syncPreventSleepMenuItems();

        configurationMenu.getItems().addAll(
            securityMenu,
            new SeparatorMenuItem(),
            settings,
            new SeparatorMenuItem(),
            preventSleep);
        return configurationMenu;
    }

    private void syncUnlockVaultMenuItems() {
        boolean locked = VaultUnlockSupport.isLocked(app.getMasterPasswordManager());
        for (MenuItem item : unlockVaultMenuItems) {
            item.setDisable(!locked);
        }
    }

    /**
     * Configuration › Security › Unlock Vault…. On the macOS system menu bar the enable-sync on
     * showing is not guaranteed, so this is also a harmless no-op when the vault is already open.
     */
    private void unlockVaultFromMenu() {
        if (!VaultUnlockSupport.isLocked(app.getMasterPasswordManager())) {
            syncUnlockVaultMenuItems();
            return;
        }
        // Success reaches this window through KorTTYApplication.onVaultUnlocked().
        VaultUnlockSupport.unlock(stage, app.getMasterPasswordManager());
    }

    /**
     * Called by {@link de.kortty.KorTTYApplication#onVaultUnlocked()} for every open window once the
     * vault was unlocked after startup. The project tabs this window's restore bar lists as waiting
     * for the vault open now (see {@link #retryTabsWaitingForVault}).
     */
    public void onVaultUnlocked() {
        syncUnlockVaultMenuItems();
        updateDashboard();
        updateStatus(I18n.get("status.vaultUnlocked"));
        retryTabsWaitingForVault();
    }

    private void installPowerManagementStateListener() {
        if (powerManagementStateListener != null || app.getPowerManagementCoordinator() == null) {
            return;
        }
        powerManagementStateListener = () -> Platform.runLater(this::syncPreventSleepMenuItems);
        app.getPowerManagementCoordinator().addListener(powerManagementStateListener);
    }

    private void syncPreventSleepMenuItems() {
        var coordinator = app.getPowerManagementCoordinator();
        boolean supported = coordinator != null && coordinator.supportsSystemSleepPrevention();
        boolean selected = supported && coordinator.isManualSleepPreventionEnabled();
        String label = I18n.get(supported
            ? "menu.configuration.preventSleep"
            : "menu.configuration.preventSleep.unsupported");
        for (CheckMenuItem item : preventSleepMenuItems) {
            item.setText(label);
            item.setDisable(!supported);
            item.setSelected(selected);
        }
    }

    private void setManualSleepPrevention(boolean enabled) {
        var coordinator = app.getPowerManagementCoordinator();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        boolean previous = coordinator != null && coordinator.isManualSleepPreventionEnabled();
        if (coordinator == null || !coordinator.setManualSleepPrevention(enabled)) {
            syncPreventSleepMenuItems();
            showPowerManagementError();
            return;
        }
        settings.setPreventSystemSleep(enabled);
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.warn("Could not persist system-sleep prevention setting", e);
            settings.setPreventSystemSleep(previous);
            coordinator.setManualSleepPrevention(previous);
            showPowerManagementError();
        }
        syncPreventSleepMenuItems();
    }

    private void showPowerManagementError() {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("menu.configuration.preventSleep.error.title"));
        alert.setHeaderText(I18n.get("menu.configuration.preventSleep.error.header"));
        alert.setContentText(I18n.get("menu.configuration.preventSleep.error.content"));
        alert.showAndWait();
    }

    private Menu createToolsMenu() {
        Menu toolsMenu = new Menu(I18n.get("menu.tools"));

        MenuItem snippetManager = menuItem("menu.tools.snippets");
        snippetManager.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        snippetManager.setOnAction(e -> showSnippetManager());

        MenuItem jobScheduler = menuItem("menu.tools.jobScheduler");
        jobScheduler.setAccelerator(JOB_SCHEDULER_ACCELERATOR);
        jobScheduler.setOnAction(e -> showJobScheduler());

        MenuItem videoManager = menuItem("menu.tools.videoManager");
        videoManager.setAccelerator(VIDEO_MANAGER_ACCELERATOR);
        videoManager.setOnAction(e -> showTerminalRecordingManager());

        MenuItem toggleRecording = menuItem("menu.tools.toggleRecording");
        toggleRecording.setAccelerator(RECORDING_TOGGLE_ACCELERATOR);
        toggleRecording.setOnAction(e -> toggleTerminalRecording());

        MenuItem sessionJournals = menuItem("menu.tools.sessionJournals");
        sessionJournals.setAccelerator(SESSION_JOURNAL_MANAGER_ACCELERATOR);
        sessionJournals.setOnAction(e -> showSessionJournalManager());

        MenuItem toggleSessionJournal = menuItem("menu.tools.toggleSessionJournal");
        toggleSessionJournal.setAccelerator(SESSION_JOURNAL_TOGGLE_ACCELERATOR);
        toggleSessionJournal.setOnAction(e -> toggleSessionJournal());

        MenuItem journalScreenshot = menuItem("menu.tools.journalScreenshot");
        journalScreenshot.setAccelerator(SESSION_JOURNAL_SCREENSHOT_ACCELERATOR);
        journalScreenshot.setOnAction(e -> takeSessionJournalScreenshot());

        if (!de.kortty.policy.PolicyManager.effective().sessionJournalAllowed()) {
            lockByPolicy(sessionJournals);
            lockByPolicy(toggleSessionJournal);
            lockByPolicy(journalScreenshot);
        }

        MenuItem asciiArt = menuItem("menu.tools.asciiArt");
        asciiArt.setAccelerator(new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        asciiArt.setOnAction(e -> showAsciiArtBanner());

        toolsMenu.getItems().addAll(
            snippetManager,
            new SeparatorMenuItem(),
            jobScheduler,
            new SeparatorMenuItem(),
            videoManager,
            toggleRecording,
            new SeparatorMenuItem(),
            sessionJournals,
            toggleSessionJournal,
            journalScreenshot,
            new SeparatorMenuItem(),
            asciiArt);
        return toolsMenu;
    }

    private Menu createAiMenu() {
        Menu aiMenu = new Menu(I18n.get("menu.ai"));

        MenuItem aiManager = menuItem("menu.tools.aiManager");
        aiManager.setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        aiManager.setOnAction(e -> showAiManager());

        MenuItem savedChats = menuItem("menu.tools.savedChats");
        savedChats.setOnAction(e -> showSavedChats());

        MenuItem aiAgent = menuItem("menu.tools.aiAgent");
        aiAgent.setAccelerator(AI_AGENT_ACCELERATOR);
        aiAgent.setOnAction(e -> showAiAgent());

        MenuItem aiPlanning = menuItem("menu.tools.aiPlanning");
        aiPlanning.setAccelerator(AI_PLANNING_ACCELERATOR);
        aiPlanning.setOnAction(e -> showAiPlanning());

        MenuItem aiSwarm = menuItem("menu.tools.aiSwarm");
        // Shortcut+Alt+S — Shortcut+Shift+S is taken by the Snippet-Manager
        aiSwarm.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        aiSwarm.setOnAction(e -> showAiSwarm());

        // Policy-denied AI features stay permanently disabled: they are excluded from the sync
        // lists (which would re-enable them) and locked with the managed-by-organization hint.
        de.kortty.policy.EffectivePolicy policy = de.kortty.policy.PolicyManager.effective();
        toolsAiMenuItems.add(aiManager);
        if (policy.aiChatAllowed()) {
            toolsAiMenuItems.add(savedChats);
        } else {
            lockByPolicy(savedChats);
        }
        if (policy.aiAgentAllowed()) {
            toolsAiMenuItems.add(aiAgent);
            toolsAiAgentExecutionMenuItems.add(aiAgent);
        } else {
            lockByPolicy(aiAgent);
        }
        if (policy.aiPlanningAllowed()) {
            toolsAiMenuItems.add(aiPlanning);
        } else {
            lockByPolicy(aiPlanning);
        }
        if (policy.aiSwarmAllowed()) {
            toolsAiMenuItems.add(aiSwarm);
        } else {
            lockByPolicy(aiSwarm);
        }

        aiMenu.getItems().addAll(aiManager, savedChats, aiAgent, aiPlanning, aiSwarm);
        return aiMenu;
    }

    private Menu createTeamworkMenu() {
        Menu teamworkMenu = new Menu(I18n.get("menu.teamwork"));

        MenuItem teamworkSettings = menuItem("menu.teamwork.settings");
        teamworkSettings.setOnAction(e -> showTeamworkSettings());
        if (!de.kortty.policy.PolicyManager.effective().teamworkAllowed()) {
            lockByPolicy(teamworkSettings);
        }

        teamworkMenu.getItems().add(teamworkSettings);
        return teamworkMenu;
    }

    private Menu createPluginsMenu() {
        Menu pluginsMenu = new Menu(I18n.get("menu.plugins"));
        MenuItem terminalEffects = menuItem("menu.plugins.terminalEffects");
        terminalEffects.setOnAction(event -> showTerminalEffectPluginManager());
        if (!de.kortty.policy.PolicyManager.effective().pluginsAllowed()) {
            lockByPolicy(terminalEffects);
        }
        pluginsMenu.getItems().add(terminalEffects);
        return pluginsMenu;
    }

    private Menu createJobSchedulerStatusMenu(MenuBarTarget target) {
        // Filled each time it opens (rebuildJobSchedulerStatusMenuItems), so it stays out of the action harvest.
        Menu jobsMenu = ActionIds.exclude(new Menu(I18n.get("jobscheduler.menu.noJobs")));
        if (target == MenuBarTarget.WINDOW) {
            jobSchedulerStatusMenu = jobsMenu;
            Label label = new Label(I18n.get("jobscheduler.menu.noJobs"));
            label.setPadding(new Insets(0, 0, 0, JOB_SCHEDULER_STATUS_LEFT_PADDING));
            label.setOnContextMenuRequested(event -> {
                showJobSchedulerStatusContextMenu(label);
                event.consume();
            });
            label.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    showJobSchedulerStatusContextMenu(label);
                    event.consume();
                }
            });
            jobsMenu.setGraphic(label);
            jobsMenu.setText("");
        } else {
            systemJobSchedulerStatusMenu = jobsMenu;
            jobsMenu.getProperties().put(JOB_SCHEDULER_STATUS_SPACED_TEXT_PROPERTY, Boolean.TRUE);
        }
        jobsMenu.setOnShowing(event -> {
            updateJobSchedulerStatusMenus();
            rebuildJobSchedulerStatusMenuItems(jobsMenu);
        });
        return jobsMenu;
    }

    private Menu createViewMenu(MenuBarTarget target) {
        Menu viewMenu = new Menu(I18n.get("menu.view"));
        boolean restoreDashboard = shouldRestoreDashboardOnStartup();

        MenuItem commandPalette = menuItem("menu.view.commandPalette");
        // Shown here; the scene shortcut router handles the key, also while a terminal has the focus.
        commandPalette.setAccelerator(COMMAND_PALETTE_ACCELERATOR);
        // After the menu has closed, so the palette takes the keyboard. From the menu bar of a closed
        // macOS window it opens in the frontmost open window (ClosedWindowMenuRouter's default).
        commandPalette.setOnAction(e -> Platform.runLater(this::showCommandPalette));
        // Not a command of the palette itself.
        ActionIds.exclude(commandPalette);

        // The palette on its snippets alone, '$' already typed, so they run in the focused pane. No
        // default shortcut (see snippetPaletteChord); a chord bound under Settings > Keyboard shows here.
        // Like the command palette it opens in the frontmost open window from a closed window's menu bar.
        MenuItem snippetPalette = menuItem("menu.view.snippetPalette");
        snippetPalette.setOnAction(e -> Platform.runLater(this::showSnippetPalette));

        CheckMenuItem dashboardItem = checkMenuItem("menu.view.dashboard");
        dashboardItem.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        dashboardItem.setSelected(restoreDashboard);
        dashboardItem.setOnAction(e -> toggleDashboard(dashboardItem.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            showDashboardMenuItem = dashboardItem;
        } else {
            systemShowDashboardMenuItem = dashboardItem;
        }

        CheckMenuItem timestampsItem = checkMenuItem("menu.view.timestamps");
        timestampsItem.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        timestampsItem.setOnAction(e -> toggleTimestampsInCurrentTab(timestampsItem));
        if (target == MenuBarTarget.WINDOW) {
            showTimestampsMenuItem = timestampsItem;
        } else {
            systemShowTimestampsMenuItem = timestampsItem;
        }

        CheckMenuItem menuBarItem = checkMenuItem("menu.view.menuBar");
        menuBarItem.setAccelerator(MENU_BAR_TOGGLE_ACCELERATOR);
        menuBarItem.setSelected(menuBar == null || menuBar.isVisible());
        menuBarItem.setOnAction(e -> toggleMenuBarVisibility(menuBarItem.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            showMenuBarMenuItem = menuBarItem;
        } else {
            systemShowMenuBarMenuItem = menuBarItem;
        }

        // File Browser submenu
        Menu fileBrowserMenu = new Menu(I18n.get("menu.view.fileBrowser"));
        CheckMenuItem fileBrowserLeftItem = checkMenuItem("menu.view.fileBrowser.left");
        // Not Shift+B: that is the File menu's "Create Backup...", which is registered first and
        // would swallow this accelerator (JavaFX keeps only the first match per scene).
        fileBrowserLeftItem.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        fileBrowserLeftItem.setOnAction(e -> {
            if (fileBrowserLeftItem.isSelected()) {
                toggleFileBrowser(LocalFileBrowserManager.Position.LEFT);
            } else {
                fileBrowserManager.hide();
            }
        });
        CheckMenuItem fileBrowserRightItem = checkMenuItem("menu.view.fileBrowser.right");
        fileBrowserRightItem.setAccelerator(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        fileBrowserRightItem.setOnAction(e -> {
            if (fileBrowserRightItem.isSelected()) {
                toggleFileBrowser(LocalFileBrowserManager.Position.RIGHT);
            } else {
                fileBrowserManager.hide();
            }
        });
        // Store references for both window and system menus so syncFileBrowserMenuItems can update both
        if (target == MenuBarTarget.WINDOW) {
            showFileBrowserLeftMenuItem = fileBrowserLeftItem;
            showFileBrowserRightMenuItem = fileBrowserRightItem;
        } else {
            systemShowFileBrowserLeftMenuItem = fileBrowserLeftItem;
            systemShowFileBrowserRightMenuItem = fileBrowserRightItem;
        }
        fileBrowserMenu.getItems().addAll(fileBrowserLeftItem, fileBrowserRightItem);

        // AI Agent Panel placement: at the bottom of the terminal split (default), or docked left/right.
        Menu aiAgentPanelMenu = new Menu(I18n.get("menu.view.aiAgentPanel"));
        CheckMenuItem aiAgentBottomItem = checkMenuItem("menu.view.aiAgentPanel.bottom");
        aiAgentBottomItem.setSelected(true);
        aiAgentBottomItem.setOnAction(e -> {
            setAiAgentPlacement(AiAgentPanelDockManager.Placement.BOTTOM);
            syncAiAgentMenuItems(aiAgentDockManager.getPlacement());
        });
        CheckMenuItem aiAgentLeftItem = checkMenuItem("menu.view.aiAgentPanel.left");
        aiAgentLeftItem.setOnAction(e -> {
            setAiAgentPlacement(AiAgentPanelDockManager.Placement.LEFT);
            syncAiAgentMenuItems(aiAgentDockManager.getPlacement());
        });
        CheckMenuItem aiAgentRightItem = checkMenuItem("menu.view.aiAgentPanel.right");
        aiAgentRightItem.setOnAction(e -> {
            setAiAgentPlacement(AiAgentPanelDockManager.Placement.RIGHT);
            syncAiAgentMenuItems(aiAgentDockManager.getPlacement());
        });
        if (target == MenuBarTarget.WINDOW) {
            showAiAgentBottomMenuItem = aiAgentBottomItem;
            showAiAgentLeftMenuItem = aiAgentLeftItem;
            showAiAgentRightMenuItem = aiAgentRightItem;
        } else {
            systemShowAiAgentBottomMenuItem = aiAgentBottomItem;
            systemShowAiAgentLeftMenuItem = aiAgentLeftItem;
            systemShowAiAgentRightMenuItem = aiAgentRightItem;
        }
        aiAgentPanelMenu.getItems().addAll(aiAgentBottomItem, aiAgentLeftItem, aiAgentRightItem);

        // Live journal panel: hidden by default, dockable left/right beside the terminal tabs.
        Menu journalLivePanelMenu = new Menu(I18n.get("menu.view.journalPanel"));
        CheckMenuItem journalLiveLeftItem = checkMenuItem("menu.view.journalPanel.left");
        journalLiveLeftItem.setOnAction(e ->
            setJournalLivePanelPlacement(SessionJournalLivePanelDockManager.Placement.LEFT));
        CheckMenuItem journalLiveRightItem = checkMenuItem("menu.view.journalPanel.right");
        journalLiveRightItem.setOnAction(e ->
            setJournalLivePanelPlacement(SessionJournalLivePanelDockManager.Placement.RIGHT));
        MenuItem journalLiveToggleItem = menuItem("menu.view.journalPanel.toggle");
        // Cmd/Ctrl+Alt+L: free next to the journal family (Alt+J/T/C) and the file browser (Shift+K/R).
        journalLiveToggleItem.setAccelerator(
            new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        journalLiveToggleItem.setOnAction(e -> toggleJournalLivePanelVisible());
        if (target == MenuBarTarget.WINDOW) {
            showJournalLiveLeftMenuItem = journalLiveLeftItem;
            showJournalLiveRightMenuItem = journalLiveRightItem;
        } else {
            systemShowJournalLiveLeftMenuItem = journalLiveLeftItem;
            systemShowJournalLiveRightMenuItem = journalLiveRightItem;
        }
        journalLivePanelMenu.getItems().addAll(journalLiveLeftItem, journalLiveRightItem,
            new SeparatorMenuItem(), journalLiveToggleItem);

        // Remote files sidebar (D10): hidden by default, docked right or left in every terminal tab.
        // A global setting, so it needs no particular window; offered while the active tab has an
        // SSH pane or the sidebar is on (so it can always be turned off again).
        Menu remoteSidebarMenu = new Menu(I18n.get("menu.view.remoteSidebar"));
        CheckMenuItem remoteSidebarLeft = checkMenuItem("menu.view.remoteSidebar.left");
        remoteSidebarLeft.setOnAction(e -> setTerminalRemoteSidebarPosition(remoteSidebarLeft.isSelected()
            ? TerminalRemoteSidebarPosition.LEFT : TerminalRemoteSidebarPosition.HIDDEN));
        CheckMenuItem remoteSidebarRight = checkMenuItem("menu.view.remoteSidebar.right");
        remoteSidebarRight.setOnAction(e -> setTerminalRemoteSidebarPosition(remoteSidebarRight.isSelected()
            ? TerminalRemoteSidebarPosition.RIGHT : TerminalRemoteSidebarPosition.HIDDEN));
        MenuItem remoteSidebarToggle = menuItem("menu.view.remoteSidebar.toggle");
        remoteSidebarToggle.setOnAction(e -> toggleTerminalRemoteSidebar());
        ClosedWindowMenuRouter.noWindowNeeded(remoteSidebarLeft);
        ClosedWindowMenuRouter.noWindowNeeded(remoteSidebarRight);
        ClosedWindowMenuRouter.noWindowNeeded(remoteSidebarToggle);
        remoteSidebarMenu.getItems().addAll(remoteSidebarLeft, remoteSidebarRight,
            new SeparatorMenuItem(), remoteSidebarToggle);
        remoteSidebarMenu.setOnShowing(e -> {
            TerminalRemoteSidebarPosition position = terminalRemoteSidebarPosition();
            remoteSidebarLeft.setSelected(position == TerminalRemoteSidebarPosition.LEFT);
            remoteSidebarRight.setSelected(position == TerminalRemoteSidebarPosition.RIGHT);
        });
        viewMenu.setOnShowing(e -> remoteSidebarMenu.setVisible(remoteSidebarMenuOffered()));

        // Coding Agents panel: hidden by default, dockable left/right beside the terminal tabs, plus
        // the cross-window "next blocked agent" jump.
        Menu codingAgentPanelMenu = new Menu(I18n.get("menu.codingAgent.panel"));
        CheckMenuItem codingAgentLeftItem = checkMenuItem("menu.codingAgent.panel.left");
        codingAgentLeftItem.setOnAction(e ->
            setCodingAgentPanelPlacement(CodingAgentPanelDockManager.Placement.LEFT));
        CheckMenuItem codingAgentRightItem = checkMenuItem("menu.codingAgent.panel.right");
        codingAgentRightItem.setOnAction(e ->
            setCodingAgentPanelPlacement(CodingAgentPanelDockManager.Placement.RIGHT));
        MenuItem codingAgentToggleItem = menuItem("menu.codingAgent.panel.toggle");
        MenuItem codingAgentNextBlockedItem = menuItem("menu.codingAgent.nextBlocked");
        // Cmd/Ctrl+Alt+G ("aGents") and Cmd/Ctrl+Alt+N ("Next") are free: Shortcut+Alt already binds
        // A (AI agent), C, J, L (journal family), P, S and T; Shortcut+Shift+A/B are ASCII Art and
        // Create Backup, so the Shift chords the feature plan first suggested would be swallowed.
        codingAgentToggleItem.setAccelerator(
            new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        codingAgentToggleItem.setOnAction(e -> toggleCodingAgentPanelVisible());
        codingAgentNextBlockedItem.setAccelerator(
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        codingAgentNextBlockedItem.setOnAction(e -> focusNextBlockedCodingAgent());
        if (target == MenuBarTarget.WINDOW) {
            showCodingAgentLeftMenuItem = codingAgentLeftItem;
            showCodingAgentRightMenuItem = codingAgentRightItem;
        } else {
            systemShowCodingAgentLeftMenuItem = codingAgentLeftItem;
            systemShowCodingAgentRightMenuItem = codingAgentRightItem;
        }
        codingAgentPanelMenu.getItems().addAll(codingAgentLeftItem, codingAgentRightItem,
            new SeparatorMenuItem(), codingAgentToggleItem, codingAgentNextBlockedItem);

        MenuItem zoomIn = menuItem("menu.view.zoomIn");
        zoomIn.setAccelerator(new KeyCodeCombination(KeyCode.PLUS, KeyCombination.ALT_DOWN));
        zoomIn.setOnAction(e -> zoomTerminal(1));

        MenuItem zoomOut = menuItem("menu.view.zoomOut");
        zoomOut.setAccelerator(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.ALT_DOWN));
        zoomOut.setOnAction(e -> zoomTerminal(-1));

        MenuItem resetZoom = menuItem("menu.view.resetZoom");
        resetZoom.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.ALT_DOWN));
        resetZoom.setOnAction(e -> resetTerminalZoom());

        // The animation-speed slider is a CustomMenuItem; macOS refuses to render a native system
        // menu bar that contains one, so it is omitted from the SYSTEM menu bar (it stays available
        // in the in-window menu bar and the terminal context menu).
        boolean includeEffectSpeedControl = target != MenuBarTarget.SYSTEM;
        // Rebuilt each time it opens, for the active tab, so it stays out of the action harvest.
        Menu terminalEffectMenu = ActionIds.exclude(createTerminalEffectMenu(null, includeEffectSpeedControl));
        terminalEffectMenu.setOnShowing(event ->
                rebuildTerminalEffectMenu(terminalEffectMenu, getActiveTerminalTab(), includeEffectSpeedControl));
        Menu highlightingMenu = createHighlightingMenu(target);
        Menu panesMenu = createPanesMenu(target);
        Menu multiExecMenu = createMultiExecMenu(target);

        MenuItem fullscreen = menuItem("menu.view.fullscreen");
        // F12 lives on the in-window bar; the macOS companion system bar has all accelerators
        // stripped (see setupMenuBar), which also avoids the Cocoa NSEventModifierFlagFunction
        // warning for the F12 function key. F12 also works via the global key handler. F11 is not
        // used here because macOS reserves it system-wide for "Show Desktop".
        fullscreen.setAccelerator(new KeyCodeCombination(KeyCode.F12));
        fullscreen.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));

        CheckMenuItem terminalOnlyFullscreen = checkMenuItem("menu.view.terminalOnlyFullscreen");
        terminalOnlyFullscreen.setAccelerator(TERMINAL_ONLY_FULLSCREEN_ACCELERATOR);
        terminalOnlyFullscreen.setSelected(terminalOnlyFullscreenActive);
        terminalOnlyFullscreen.setOnAction(e -> setTerminalOnlyFullscreen(terminalOnlyFullscreen.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            terminalOnlyFullscreenMenuItem = terminalOnlyFullscreen;
        } else {
            systemTerminalOnlyFullscreenMenuItem = terminalOnlyFullscreen;
        }

        CheckMenuItem hideFullscreenScrollbars = checkMenuItem("menu.view.hideTerminalScrollbarsFullscreen");
        hideFullscreenScrollbars.setSelected(isHideTerminalScrollbarsInFullscreenPreference());
        hideFullscreenScrollbars.setOnAction(e ->
            setHideTerminalScrollbarsInFullscreen(hideFullscreenScrollbars.isSelected()));
        if (target == MenuBarTarget.WINDOW) {
            hideFullscreenScrollbarsMenuItem = hideFullscreenScrollbars;
        } else {
            systemHideFullscreenScrollbarsMenuItem = hideFullscreenScrollbars;
        }

        viewMenu.getItems().addAll(commandPalette, snippetPalette, new SeparatorMenuItem(),
            dashboardItem, timestampsItem, menuBarItem, fileBrowserMenu, aiAgentPanelMenu,
            journalLivePanelMenu, codingAgentPanelMenu, remoteSidebarMenu,
            new SeparatorMenuItem(),
            zoomIn, zoomOut, resetZoom);
        // The background-transparency slider is a CustomMenuItem, which the macOS native system menu
        // bar refuses to render (same limitation as the effect-speed slider), so it goes into the
        // in-window menu bar only. Its own separator visually groups it under the zoom controls.
        if (target != MenuBarTarget.SYSTEM) {
            viewMenu.getItems().addAll(new SeparatorMenuItem(), buildBackgroundTransparencyMenuItem());
        }
        viewMenu.getItems().addAll(new SeparatorMenuItem(), panesMenu, multiExecMenu, highlightingMenu,
            terminalEffectMenu, new SeparatorMenuItem(), fullscreen, terminalOnlyFullscreen, hideFullscreenScrollbars);
        return viewMenu;
    }

    /**
     * View → Panes: Split Pane, the focus items and Zoom Pane carry the pane chords for display (the
     * scene shortcut router handles the keys themselves while the keyboard is in a terminal tab, with
     * two or more panes for the focus and zoom keys), Split Right, Split Down, Close Pane, Next Pane and
     * Previous Pane have none, and the broadcast item switches the active tab's broadcast mode. The
     * items are synced from the active tab while the menu opens and before an accelerator or the menu
     * bar of a closed macOS window runs one of them.
     */
    private Menu createPanesMenu(MenuBarTarget target) {
        PaneMenuSupport.PaneMenu panes = PaneMenuSupport.create(new PaneMenuSupport.Commands() {
            @Override
            public void split(SplitOrientationChooser.SplitSide side) {
                splitPaneInActiveTerminal(side);
            }

            @Override
            public void closePane() {
                closePaneInActiveTerminal();
            }

            @Override
            public void focus(PaneNavigator.PaneDirection direction) {
                focusPaneInActiveTerminal(direction);
            }

            @Override
            public void cycle(boolean forward) {
                cyclePaneInActiveTerminal(forward);
            }

            @Override
            public void toggleZoom() {
                toggleZoomInActiveTerminal();
            }

            @Override
            public void toggleBroadcast() {
                toggleBroadcastInActiveTerminal();
            }
        });
        panes.split().setAccelerator(PANE_SPLIT_ACCELERATOR);
        panes.zoom().setAccelerator(PANE_ZOOM_ACCELERATOR);
        panes.focusItem(PaneNavigator.PaneDirection.LEFT).setAccelerator(PANE_FOCUS_LEFT_ACCELERATOR);
        panes.focusItem(PaneNavigator.PaneDirection.RIGHT).setAccelerator(PANE_FOCUS_RIGHT_ACCELERATOR);
        panes.focusItem(PaneNavigator.PaneDirection.UP).setAccelerator(PANE_FOCUS_UP_ACCELERATOR);
        panes.focusItem(PaneNavigator.PaneDirection.DOWN).setAccelerator(PANE_FOCUS_DOWN_ACCELERATOR);
        if (!de.kortty.policy.PolicyManager.effective().multiExecAllowed()) {
            // Broadcast mode types into several panes at once, which the policy denies with multi-exec.
            lockByPolicy(panes.broadcast());
        }
        panes.menu().setOnShowing(event -> syncPaneMenuItems());
        panes.menu().setOnMenuValidation(event -> syncPaneMenuItems());
        if (target == MenuBarTarget.WINDOW) {
            paneMenu = panes;
        } else {
            systemPaneMenu = panes;
        }
        return panes.menu();
    }

    /** The View → Panes state of the active tab: the number of its panes, its broadcast mode and zoom. */
    private PaneMenuSupport.State activePaneMenuState() {
        TerminalTab terminalTab = activeTerminalTab();
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        if (view == null) {
            return PaneMenuSupport.State.NO_TERMINAL;
        }
        return new PaneMenuSupport.State(true, view.getTerminalPaneCount(), view.isBroadcastMode(),
            view.isPaneZoomed());
    }

    /** Shows the active tab's panes, broadcast mode and zoom on View → Panes of both menu bars. */
    private void syncPaneMenuItems() {
        PaneMenuSupport.State state = activePaneMenuState();
        PaneMenuSupport.sync(paneMenu, state);
        PaneMenuSupport.sync(systemPaneMenu, state);
        // View > Multi-exec follows the same events: the active tab, its panes and its focused pane.
        syncMultiExecMenuItems();
    }

    /** The number of panes of the selected terminal tab, 0 when no terminal tab is selected. */
    private int activeTerminalPaneCount() {
        TerminalTab terminalTab = activeTerminalTab();
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        return view != null ? view.getTerminalPaneCount() : 0;
    }

    /**
     * What a pane shortcut does once the scene shortcut router took its chord. The split runs after
     * the key event, as its connect dialog runs a nested event loop: the router swallows the chord's
     * KEY_TYPED first, so no O reaches the old or the new pane. The zoom runs at once; the router
     * swallows the carriage return its KEY_TYPED can carry.
     */
    private void runPaneShortcut(PaneShortcuts.PaneAction action) {
        switch (action) {
            case FOCUS_LEFT, FOCUS_RIGHT, FOCUS_UP, FOCUS_DOWN -> focusPaneInActiveTerminal(action.direction());
            case SPLIT -> Platform.runLater(() -> splitPaneInActiveTerminal(null));
            case ZOOM -> toggleZoomInActiveTerminal();
        }
    }

    /**
     * Splits the focused pane of the active terminal tab on that pane's own server, putting the new
     * pane on {@code side}, or on the side that suits the pane's shape when {@code side} is null.
     */
    private void splitPaneInActiveTerminal(SplitOrientationChooser.SplitSide side) {
        TerminalTab terminalTab = activeTerminalTab();
        if (terminalTab != null && terminalTab.getTerminalView() != null) {
            terminalTab.getTerminalView().splitFocusedPane(side);
        }
        syncPaneMenuItems();
    }

    /** Closes the focused pane of the active terminal tab; the tab's last pane stays. */
    private void closePaneInActiveTerminal() {
        TerminalTab terminalTab = activeTerminalTab();
        if (terminalTab != null && terminalTab.getTerminalView() != null) {
            terminalTab.getTerminalView().closeFocusedPane();
        }
        syncPaneMenuItems();
    }

    /** Moves the keyboard focus to the neighbouring pane of the active terminal tab; nothing at the edge. */
    private void focusPaneInActiveTerminal(PaneNavigator.PaneDirection direction) {
        TerminalTab terminalTab = activeTerminalTab();
        if (terminalTab != null && terminalTab.getTerminalView() != null) {
            terminalTab.getTerminalView().focusPane(direction);
        }
        // Moving the focus shows a zoomed tab's panes again.
        syncPaneMenuItems();
    }

    /** Moves the keyboard focus to the next or previous pane of the active terminal tab, wrapping around. */
    private void cyclePaneInActiveTerminal(boolean forward) {
        TerminalTab terminalTab = activeTerminalTab();
        if (terminalTab != null && terminalTab.getTerminalView() != null) {
            terminalTab.getTerminalView().focusNextPane(forward);
        }
        syncPaneMenuItems();
    }

    /**
     * View → Panes → Zoom Pane: lets the focused pane of the active terminal tab fill the tab alone,
     * or shows every pane again while one is zoomed. The tab's zoom decides, never the check item,
     * which JavaFX has already flipped when this runs; the items are re-synced afterwards.
     */
    private void toggleZoomInActiveTerminal() {
        TerminalTab terminalTab = activeTerminalTab();
        if (terminalTab != null && terminalTab.getTerminalView() != null) {
            terminalTab.getTerminalView().toggleZoomPane();
        }
        syncPaneMenuItems();
    }

    /**
     * View → Panes → Broadcast: switches the active tab's broadcast mode. The decision comes from the
     * tab's mode, never from the check item, which JavaFX has already flipped when this runs; the
     * items are re-synced afterwards. Switching it on needs a second pane, as in the context menu.
     */
    private void toggleBroadcastInActiveTerminal() {
        TerminalTab terminalTab = activeTerminalTab();
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        if (view != null) {
            boolean on = view.isBroadcastMode();
            if (on || view.getTerminalPaneCount() >= 2) {
                view.setBroadcastMode(!on);
            }
        }
        syncPaneMenuItems();
    }

    /**
     * Builds the Ansicht → Zoom background-transparency slider (0–100 %). Dragging applies live to all
     * terminals; the value is persisted (debounced). Because JavaFX locks the window style before the
     * window is shown, switching the see-through mode on or off only takes full effect after a restart,
     * so the status bar shows a restart hint when the slider crosses the enable/disable boundary.
     */
    private CustomMenuItem buildBackgroundTransparencyMenuItem() {
        int initial = currentBackgroundTransparencyPercent();

        Label caption = new Label(I18n.get("menu.view.backgroundTransparency"));
        Label valueLabel = new Label(initial + " %");
        valueLabel.setMinWidth(42);

        Slider slider = new Slider(0, 100, initial);
        slider.setPrefWidth(200);
        slider.setBlockIncrement(5);
        slider.setMajorTickUnit(25);
        slider.setMinorTickCount(4);
        slider.setShowTickMarks(true);

        HBox row = new HBox(8, slider, valueLabel);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox content = new VBox(4, caption, row);
        content.setPadding(new javafx.geometry.Insets(4, 8, 6, 8));

        // Persist is debounced so dragging the slider doesn't rewrite the settings file every pixel.
        javafx.animation.PauseTransition saveDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
        saveDebounce.setOnFinished(e -> persistBackgroundTransparency());

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int percent = (int) Math.round(newVal.doubleValue());
            valueLabel.setText(percent + " %");
            GlobalSettings gs = app.getGlobalSettingsManager().getSettings();
            if (gs != null) {
                gs.setTerminalBackgroundTransparency(percent);
            }
            applyBackgroundTransparencyToAllTabs();
            saveDebounce.playFromStart();
            if ((percent > 0) != transparentWindowMode) {
                // Enabling/disabling see-through mode changes the stage style, which needs a restart.
                updateStatus(I18n.get("menu.view.backgroundTransparency.restart"));
            } else if (percent > 0 && !isBackgroundTransparencyVisible()) {
                // Without an active terminal tab, or in fullscreen, the window stays solid on purpose;
                // say so, otherwise the slider looks broken.
                updateStatus(I18n.get("menu.view.backgroundTransparency.terminalOnly"));
            }
        });

        CustomMenuItem item = new CustomMenuItem(content);
        item.setHideOnClick(false);
        return item;
    }

    private void persistBackgroundTransparency() {
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.warn("Could not persist background transparency setting", e);
        }
    }

    /**
     * View → Highlighting: the Highlighting On item, which carries {@link #HIGHLIGHTING_TOGGLE_ACCELERATOR}
     * for display (the scene shortcut router handles the key itself while a terminal tab is selected),
     * the rule-set list, rebuilt each time the menu opens, and Manage Rule Sets… below it. The first two
     * act on the focused pane of the active terminal tab, for this session only.
     */
    private Menu createHighlightingMenu(MenuBarTarget target) {
        CheckMenuItem toggle = HighlightMenuSupport.createToggleItem(
            () -> toggleHighlightingInActiveTerminal(HighlightTelemetry.SOURCE_MENU));
        toggle.setAccelerator(HIGHLIGHTING_TOGGLE_ACCELERATOR);
        ActionIds.tag(toggle, HighlightMenuSupport.TOGGLE_KEY);
        if (target == MenuBarTarget.WINDOW) {
            highlightingToggleMenuItem = toggle;
        } else {
            systemHighlightingToggleMenuItem = toggle;
        }
        MenuItem manage = HighlightMenuSupport.createManageItem(this::openHighlightRulesEditor);
        ActionIds.tag(manage, HighlightMenuSupport.MANAGE_KEY);
        Menu menu = HighlightMenuSupport.createViewMenu(toggle, manage, activeHighlightMenuState(),
            this::chooseHighlightSetInActiveTerminal);
        menu.setOnShowing(event -> HighlightMenuSupport.refresh(menu, toggle, activeHighlightMenuState(),
            this::chooseHighlightSetInActiveTerminal));
        return menu;
    }

    /** The highlighting menus' state for the focused pane of the active terminal tab. */
    private HighlightMenuSupport.State activeHighlightMenuState() {
        TerminalTab terminalTab = getActiveTerminalTab();
        if (terminalTab == null) {
            return HighlightMenuSupport.state(app.getTerminalHighlightService(), false, null);
        }
        TerminalView view = terminalTab.getTerminalView();
        return view.getHighlightMenuState(view.getFocusedWidget());
    }

    /**
     * Cmd/Ctrl+Shift+H and View → Highlighting → Highlighting On: switches the focused pane's
     * highlighting off or back on. The decision comes from the pane's state, never from the check
     * item, which JavaFX has already flipped when this runs; the items are re-synced afterwards.
     */
    private void toggleHighlightingInActiveTerminal(String source) {
        TerminalTab terminalTab = getActiveTerminalTab();
        TerminalHighlightService service = app.getTerminalHighlightService();
        if (terminalTab != null && service != null && !service.isClosed()) {
            if (!service.isEnabled()) {
                updateStatus(I18n.get(HighlightMenuSupport.DISABLED_KEY));
            } else {
                TerminalView view = terminalTab.getTerminalView();
                Optional<HighlightToggle.Choice> choice = view.toggleHighlighting(view.getFocusedWidget(), source);
                choice.ifPresent(decided ->
                    updateStatus(HighlightMenuSupport.statusMessage(decided, service::userSetName)));
            }
        }
        syncHighlightingToggleItems();
    }

    /** A set picked in View → Highlighting, for the focused pane of the active terminal tab. */
    private void chooseHighlightSetInActiveTerminal(String setId) {
        TerminalTab terminalTab = getActiveTerminalTab();
        if (terminalTab != null) {
            TerminalView view = terminalTab.getTerminalView();
            view.chooseHighlightSet(view.getFocusedWidget(), setId, HighlightTelemetry.SOURCE_MENU);
        }
        syncHighlightingToggleItems();
    }

    /**
     * View → Highlighting → Manage Rule Sets…: the rule-set editor, opened on the set the focused pane
     * shows. Saving writes the settings and reloads the highlighting, so every pane follows at once.
     */
    private void openHighlightRulesEditor() {
        HighlightRulesDialog.showAndSave(stage, app, activeHighlightMenuState().shownSetId());
        syncHighlightingToggleItems();
    }

    private void syncHighlightingToggleItems() {
        HighlightMenuSupport.State state = activeHighlightMenuState();
        HighlightMenuSupport.syncToggle(highlightingToggleMenuItem, state);
        HighlightMenuSupport.syncToggle(systemHighlightingToggleMenuItem, state);
    }

    /**
     * After the Connection Manager saved: a connection's rule set may have changed, so every pane in every
     * window that inherits from its connection moves to the set it resolves to now (the service is shared
     * by all windows). A failure here must never break saving connections.
     */
    private void refreshHighlightingAfterConnectionsSaved() {
        TerminalHighlightService service = app.getTerminalHighlightService();
        if (service != null && !service.isClosed()) {
            try {
                service.refreshAll();
            } catch (RuntimeException e) {
                logger.warn("Keyword highlighting could not follow the saved connections: {}", e.toString());
            }
        }
        syncHighlightingToggleItems();
    }

    private Menu createTerminalEffectMenu(TerminalTab terminalTab) {
        return createTerminalEffectMenu(terminalTab, true);
    }

    private Menu createTerminalEffectMenu(TerminalTab terminalTab, boolean includeSpeedControl) {
        Menu menu = new Menu(I18n.get("plugin.terminalEffect"));
        rebuildTerminalEffectMenu(menu, terminalTab, includeSpeedControl);
        return menu;
    }

    private void rebuildTerminalEffectMenu(Menu menu, TerminalTab terminalTab) {
        rebuildTerminalEffectMenu(menu, terminalTab, true);
    }

    private void rebuildTerminalEffectMenu(Menu menu, TerminalTab terminalTab, boolean includeSpeedControl) {
        menu.getItems().clear();
        if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            menu.setDisable(true);
            return;
        }
        menu.setDisable(false);
        ToggleGroup group = new ToggleGroup();
        String activePluginId = terminalTab != null ? terminalTab.getTerminalView().getTerminalEffectPluginId() : null;

        RadioMenuItem noneItem = new RadioMenuItem(I18n.get("plugin.none"));
        noneItem.setToggleGroup(group);
        noneItem.setSelected(activePluginId == null);
        noneItem.setDisable(terminalTab == null);
        noneItem.setOnAction(event -> {
            if (terminalTab != null) {
                terminalTab.getTerminalView().setTerminalEffectPluginId(null);
                rememberTerminalEffectPluginId(terminalTab, null);
                updateAllTabContextMenus();
            }
        });
        menu.getItems().add(noneItem);

        var manager = app.getTerminalEffectPluginManager();
        if (manager == null || manager.getPlugins().isEmpty()) {
            menu.setDisable(terminalTab == null);
            return;
        }

        menu.getItems().add(new SeparatorMenuItem());
        for (var plugin : manager.getPlugins()) {
            RadioMenuItem pluginItem = new RadioMenuItem(plugin.displayName());
            pluginItem.setToggleGroup(group);
            pluginItem.setSelected(plugin.id().equals(activePluginId));
            pluginItem.setDisable(terminalTab == null);
            pluginItem.setOnAction(event -> {
                if (terminalTab != null) {
                    terminalTab.getTerminalView().setTerminalEffectPluginId(plugin.id());
                    rememberTerminalEffectPluginId(terminalTab, plugin.id());
                    updateAllTabContextMenus();
                }
            });
            menu.getItems().add(pluginItem);
        }
        if (includeSpeedControl) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(createTerminalEffectAnimationSpeedMenuItem(terminalTab, activePluginId != null));
        }
        menu.setDisable(false);
    }

    private CustomMenuItem createTerminalEffectAnimationSpeedMenuItem(TerminalTab terminalTab, boolean enabled) {
        double currentSpeed = terminalTab != null
                ? terminalTab.getTerminalView().getTerminalEffectAnimationSpeed()
                : TerminalEffectAnimationSpeed.DEFAULT;
        TerminalEffectUiSupport.AnimationSpeedControls speedControls =
                TerminalEffectUiSupport.createAnimationSpeedControls(currentSpeed);
        speedControls.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (terminalTab != null) {
                terminalTab.getTerminalView().setTerminalEffectAnimationSpeed(newValue.doubleValue());
                rememberTerminalEffectAnimationSpeed(terminalTab, newValue.doubleValue());
            }
        });

        VBox content = speedControls.root();
        content.setPadding(new Insets(4, 8, 6, 8));
        CustomMenuItem item = new CustomMenuItem(content);
        item.setHideOnClick(false);
        item.setDisable(terminalTab == null || !enabled);
        return item;
    }

    private void rememberTerminalEffectAnimationSpeed(TerminalTab terminalTab, double speed) {
        if (terminalTab == null || terminalTab.getTerminalView() == null) {
            return;
        }
        String pluginId = terminalTab.getTerminalView().getTerminalEffectPluginId();
        Double speedForStorage = TerminalEffectUiSupport.animationSpeedForStorage(pluginId, speed);
        ServerConnection connection = terminalTab.getConnection();
        if (connection != null) {
            connection.setTerminalEffectAnimationSpeed(speedForStorage);
        }

        if (rememberSavedConnectionTerminalEffectAnimationSpeed(connection, speedForStorage)) {
            return;
        }
        rememberQuickConnectTerminalEffectAnimationSpeed(speedForStorage);
    }

    private boolean rememberSavedConnectionTerminalEffectAnimationSpeed(
            ServerConnection connection,
            Double speedForStorage) {
        if (connection == null || connection.getId() == null || app == null || app.getConfigManager() == null) {
            return false;
        }
        ServerConnection stored = app.getConfigManager().getConnectionById(connection.getId());
        if (stored == null) {
            return false;
        }
        stored.setTerminalEffectAnimationSpeed(speedForStorage);
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.warn("Could not persist terminal effect animation speed for '{}': {}",
                    stored.getDisplayName(), e.getMessage());
        }
        return true;
    }

    private void rememberQuickConnectTerminalEffectAnimationSpeed(Double speedForStorage) {
        if (app == null || app.getGlobalSettingsManager() == null) {
            return;
        }
        try {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            if (settings != null) {
                settings.setLastQuickConnectTerminalEffectAnimationSpeed(speedForStorage);
                app.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.warn("Could not persist QuickConnect terminal effect animation speed: {}", e.getMessage());
        }
    }

    private void rememberTerminalEffectPluginId(TerminalTab terminalTab, String pluginId) {
        if (terminalTab == null) {
            return;
        }
        ServerConnection connection = terminalTab.getConnection();
        if (connection != null) {
            connection.setTerminalEffectPluginId(pluginId);
        }
        rememberSavedConnectionTerminalEffectPluginId(connection, pluginId);
    }

    private void rememberSavedConnectionTerminalEffectPluginId(ServerConnection connection, String pluginId) {
        if (connection == null || connection.getId() == null || app == null || app.getConfigManager() == null) {
            return;
        }
        ServerConnection stored = app.getConfigManager().getConnectionById(connection.getId());
        if (stored == null) {
            return;
        }
        stored.setTerminalEffectPluginId(pluginId);
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.warn("Could not persist terminal effect plugin for '{}': {}",
                    stored.getDisplayName(), e.getMessage());
        }
    }

    private Menu createHelpMenu() {
        Menu helpMenu = new Menu(I18n.get("menu.help"));
        MenuItem guide = menuItem("menu.help.guide");
        guide.setAccelerator(new KeyCodeCombination(KeyCode.F1));
        guide.setOnAction(e -> openGuide());
        // The label names the application, so the id is given here.
        MenuItem about = ActionIds.tag(
            new MenuItem(I18n.get("menu.help.about") + " " + KorTTYApplication.getAppName()), "menu.help.about");
        about.setOnAction(e -> showAbout());
        helpMenu.getItems().addAll(guide, new SeparatorMenuItem(), about);
        return helpMenu;
    }

    /** Opens (or focuses) the in-app guide viewer; shows an error dialog if it cannot be opened. */
    private void openGuide() {
        try {
            GuideViewer.show(app, stage);
        } catch (Exception e) {
            logger.warn("Could not open the guide viewer", e);
            showError(I18n.get("error.title"), I18n.get("error.guideOpenFailed", e.getMessage()));
        }
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private boolean shouldRestoreDashboardOnStartup() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        return globalSettings != null && globalSettings.isRememberDashboardState() && globalSettings.isDashboardVisible();
    }
    
    /**
     * The window's scene shortcuts, in the order they are tried; the first consuming entry whose
     * chord and scope hold wins (observers run and let the search go on). Every new window-wide
     * chord registers here, with its KeyCodeCombination constant declared at the top of this class
     * and also set on its menu item. A chord the user can rebind goes through its {@link RoutedChord}
     * field, which {@link #applyKeymap()} puts on the user's override; the fixed ones (Paste, F12,
     * the zoom keys, Ctrl+Tab and Cmd/Ctrl+1..9) cannot be given to another action.
     */
    private SceneShortcutRouter createSceneShortcutRouter() {
        BooleanSupplier terminalSelected =
            () -> tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab;
        SceneShortcutRouter router = new SceneShortcutRouter(isMacOs())
            // Any other key ends a Ctrl+Tab cycle in most-recently-used order first, so the key acts
            // on the tab the cycle stopped at, which then counts as used. Not consumed.
            .observe(SceneShortcutKeys::endsTabCycle, tabMru::isCycling, this::commitTabCycle)
            // The command palette, in every tab and over a focused terminal. Pressed while the palette
            // shows, the chord gets past its key firewall and closes it here. Shown at once, so the
            // chord's KEY_TYPED goes to the palette, which drops it; on closing, the guard swallows it.
            .consume(commandPaletteChord::matches, SceneShortcutRouter.ALWAYS,
                this::toggleCommandPalette, commandPaletteChord::residue)
            // The snippet palette, once the user gave it a chord, as the command palette: pressed
            // while the palette shows, it closes it.
            .consume(snippetPaletteChord::matches, SceneShortcutRouter.ALWAYS,
                this::toggleSnippetPalette, snippetPaletteChord::residue)
            .consume(menuBarToggleChord::matches, SceneShortcutRouter.ALWAYS,
                () -> toggleMenuBarVisibility(menuBar == null || !menuBar.isVisible()), menuBarToggleChord::residue)
            .consume(terminalOnlyFullscreenChord::matches, SceneShortcutRouter.ALWAYS,
                this::toggleTerminalOnlyFullscreen, terminalOnlyFullscreenChord::residue)
            // Highlighting acts on the focused pane, so only while a terminal tab is selected.
            .consume(highlightingToggleChord::matches, terminalSelected,
                () -> toggleHighlightingInActiveTerminal(HighlightTelemetry.SOURCE_SHORTCUT),
                highlightingToggleChord::residue)
            // Routed so a focused terminal cannot take Ctrl+Shift+M (a carriage return) on Windows
            // and Linux. Opened after the key event, since the dialog may run a nested event loop.
            .consume(credentialsChord::matches, SceneShortcutRouter.ALWAYS,
                () -> Platform.runLater(this::showCredentialManagement), credentialsChord::residue)
            // Also with the menu bar hidden, the history empty (a status line says so) and a terminal
            // focused. Reopened after the key event, as it may ask for a password.
            .consume(reopenClosedTabChord::matches, SceneShortcutRouter.ALWAYS,
                () -> Platform.runLater(this::reopenClosedTab), reopenClosedTabChord::residue)
            // Quick select only while the keyboard is in the selected terminal tab: a side panel
            // that uses the chord keeps it, and the Edit menu item still starts quick select there.
            .consume(quickSelectChord::matches, this::isKeyboardInSelectedTerminal,
                this::quickSelectInCurrentTab, quickSelectChord::residue)
            // A shortcut the user chose for any other menu command runs it while the keyboard is in
            // the terminal, which would otherwise encode the key for the shell (a function key's
            // sequence, a control character) before the menu accelerator sees it; elsewhere the
            // accelerator runs it. After the key event, as the command may open a dialog; whatever
            // the chord types is swallowed.
            .consume(this::matchReboundMenuChord, this::isKeyboardInSelectedTerminal,
                this::runReboundMenuChord, Residue.anyCharacter())
            // Not consumed: the terminal pastes on its own, and the timestamp keeps the Paste menu
            // accelerator from pasting a second time (wasTriggeredByTerminalPasteShortcut).
            .observe(press -> press.matches(PASTE_ACCELERATOR), terminalSelected,
                () -> lastTerminalPasteShortcutAtNanos = System.nanoTime())
            .consume(SceneShortcutKeys::isFullscreenToggle, SceneShortcutRouter.ALWAYS,
                this::toggleFullscreenFromKeyboard, Residue.NONE)
            // Terminal zoom only while a terminal tab is selected: other tabs (hosted snippet
            // editors, file editors) get their own zoom keys, and AltGr+'+' (reported as Ctrl+Alt
            // on Windows) must reach them as the '~' it types.
            .consume(SceneShortcutKeys::isZoomIn, terminalSelected,
                () -> zoomTerminal(1), SceneShortcutKeys.ZOOM_RESIDUE)
            .consume(SceneShortcutKeys::isZoomOut, terminalSelected,
                () -> zoomTerminal(-1), SceneShortcutKeys.ZOOM_RESIDUE)
            .consume(SceneShortcutKeys::isZoomReset, terminalSelected,
                this::resetTerminalZoom, SceneShortcutKeys.ZOOM_RESIDUE)
            // Ctrl+Tab switches the tab even while a terminal has the focus (the terminal would
            // otherwise take it as a Tab key).
            .consume(SceneShortcutKeys::isNextTab, SceneShortcutRouter.ALWAYS,
                () -> switchTabFromKeyboard(false), SceneShortcutKeys.TAB_RESIDUE)
            .consume(SceneShortcutKeys::isPreviousTab, SceneShortcutRouter.ALWAYS,
                () -> switchTabFromKeyboard(true), SceneShortcutKeys.TAB_RESIDUE)
            // Releasing Ctrl ends a Ctrl+Tab cycle; the release is not consumed.
            .observeRelease(SceneShortcutKeys::endsTabCycleOnRelease, tabMru::isCycling, this::commitTabCycle);
        // Cmd/Ctrl+1..9 jump to a tab in every tab, the terminal included (exactly Ctrl on Windows
        // and Linux, so AltGr and Ctrl+Shift+6 still reach it). Registered after the zoom keys, which
        // win where a layout puts Plus or Minus on a digit key. No menu item carries a digit
        // accelerator (MainWindowAcceleratorUniquenessTest), so these chords have no constant.
        for (int slot = 1; slot <= TabKeyboardShortcuts.SLOT_COUNT; slot++) {
            int jumpSlot = slot;
            router.consume(press -> TabKeyboardShortcuts.slotOf(press) == jumpSlot, SceneShortcutRouter.ALWAYS,
                () -> selectTabBySlot(jumpSlot), TabKeyboardShortcuts.JUMP_RESIDUE);
        }
        // Cmd+Option / Ctrl+Alt with an arrow key move the focus between the panes of the selected
        // terminal tab, only while the keyboard is in that tab and it has two or more panes; otherwise
        // the key reaches the shell as xterm sends it. Arrow keys type nothing, so no residue.
        // Cmd/Ctrl+Shift+O splits the focused pane while the keyboard is in the tab; the O, or the
        // U+000F Ctrl turns it into, is swallowed. Cmd/Ctrl+Shift+Enter zooms the focused pane with two
        // or more panes; the carriage return or line feed it can still type is swallowed.
        for (PaneShortcuts.PaneAction paneAction : PaneShortcuts.PaneAction.values()) {
            RoutedChord paneChord = paneChords.get(paneAction);
            router.consume(paneChord::matches,
                () -> isKeyboardInSelectedTerminal() && PaneShortcuts.applies(paneAction, activeTerminalPaneCount()),
                () -> runPaneShortcut(paneAction), paneChord::residue);
        }
        return router;
    }

    /** The router chords of the pane actions, on the defaults of {@link #PANE_SHORTCUTS}. */
    private static Map<PaneShortcuts.PaneAction, RoutedChord> createPaneChords() {
        Map<PaneShortcuts.PaneAction, RoutedChord> chords = new EnumMap<>(PaneShortcuts.PaneAction.class);
        for (PaneShortcuts.PaneAction action : PaneShortcuts.PaneAction.values()) {
            chords.put(action, new RoutedChord(action.actionId(), PANE_SHORTCUTS.chordOf(action), action.residue()));
        }
        return chords;
    }

    /** Whether {@code press} is a chord the user chose for a menu command without a router entry of its own. */
    private boolean matchReboundMenuChord(SceneShortcutRouter.KeyPress press) {
        pressedReboundMenuItem = KeymapSupport.reboundItemFor(reboundMenuChords, press);
        return pressedReboundMenuItem != null;
    }

    /** Runs the menu command {@link #matchReboundMenuChord} found, the way its accelerator does, after the key event. */
    private void runReboundMenuChord() {
        MenuItem item = pressedReboundMenuItem;
        pressedReboundMenuItem = null;
        if (item != null) {
            Platform.runLater(() -> de.kortty.ui.actions.MenuItemActivation.activate(item));
        }
    }

    /** Every rebindable chord of this window's scene shortcut router. */
    private List<RoutedChord> routedChords() {
        List<RoutedChord> chords = new ArrayList<>(List.of(commandPaletteChord, snippetPaletteChord, menuBarToggleChord,
            terminalOnlyFullscreenChord, highlightingToggleChord, credentialsChord, reopenClosedTabChord,
            quickSelectChord));
        chords.addAll(paneChords.values());
        return chords;
    }

    /**
     * Puts this window's shortcuts on the keymap in effect: the default chords of the in-window menu
     * bar's actions with the user's overrides from the global settings applied
     * ({@link KeymapOverrides#resolve}, which drops an override that breaks a rule or takes another
     * action's chord). The menu items show the chords, so the command palette does too, and the
     * scene shortcut router's {@link RoutedChord}s follow them. The macOS system menu bar shows no
     * accelerators (see {@link #setupMenuBar()}), so it is left alone.
     */
    void applyKeymap() {
        if (menuBar == null) {
            return;
        }
        // Not imported: de.kortty.codingagent.KeyChord is a different class.
        de.kortty.core.KeyChord.Os os = de.kortty.core.KeyChord.Os.current();
        KeymapSupport.Defaults defaults = KeymapSupport.defaults(menuBar.getMenus(), os);
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        KeymapOverrides overrides = settings != null
            ? KeymapOverrides.parse(settings.getKeyBindingOverrides()) : KeymapOverrides.empty();
        KeymapOverrides.Resolution keymap = overrides.resolve(defaults.rebindable(),
            KeymapSupport.rules(os, defaults.fixed()));
        List<MenuItem> rechorded = KeymapSupport.applyToMenus(menuBar.getMenus(), keymap);
        // In a window that is already shown, JavaFX loses the actions of menu items whose chord
        // changed (a new chord, a swap), so they are put back; at startup there is no scene yet.
        Scene menuBarScene = menuBar.getScene();
        if (menuBarScene != null) {
            KeymapSupport.reinstallAccelerators(menuBarScene.getAccelerators(), rechorded);
        }
        java.util.Set<String> routedIds = new java.util.HashSet<>();
        for (RoutedChord chord : routedChords()) {
            chord.bind(keymap);
            routedIds.add(chord.actionId());
        }
        reboundMenuChords = KeymapSupport.reboundItems(menuBar.getMenus(), routedIds);
        sharedKeymap = keymap;
        List<String> rejections = KeymapSupport.describeRejections(keymap);
        if (!rejections.isEmpty() && !rejections.equals(loggedKeymapRejections)) {
            logger.info("Shortcut overrides not in effect: {}", rejections);
        }
        loggedKeymapRejections = rejections;
    }

    /**
     * The actions of this window's in-window menu bar for the Settings → Keyboard page, with their
     * default chords and the rules on this platform; {@code null} before the menu bar is built.
     */
    @Nullable KeyboardSettingsModel.Catalog keymapCatalog() {
        if (menuBar == null) {
            return null;
        }
        // Not imported: de.kortty.codingagent.KeyChord is a different class.
        return KeymapSupport.catalog(menuBar.getMenus(), de.kortty.core.KeyChord.Os.current());
    }

    /** {@link #applyKeymap()} in every open window, after the shortcut overrides changed; no restart needed. */
    static void refreshKeymapInAllWindows() {
        for (MainWindow window : List.copyOf(openWindows)) {
            window.applyKeymap();
        }
    }

    /** The chord that starts quick select by default, for tests and the docs. */
    static KeyCombination quickSelectAccelerator() {
        return QUICK_SELECT_ACCELERATOR;
    }

    /**
     * The chord that starts quick select in effect, which the user may have rebound; {@code null}
     * without one. For the terminal view, whose quick select ignores the chord while it runs.
     */
    static @Nullable KeyCombination effectiveQuickSelectAccelerator() {
        return effectiveAccelerator("menu.edit.quickSelect", QUICK_SELECT_ACCELERATOR);
    }

    /**
     * The chord of {@code actionId} in effect in every window, which the user may have rebound, for
     * places outside the menu bar that show or match it: {@code defaultChord} until a window applied
     * the keymap or when the keymap does not know the action, {@code null} when the user removed
     * the action's shortcut.
     */
    static @Nullable KeyCombination effectiveAccelerator(String actionId, KeyCombination defaultChord) {
        KeymapOverrides.Resolution keymap = sharedKeymap;
        if (keymap == null || !keymap.knows(actionId)) {
            return defaultChord;
        }
        KeyCombination chord = KeymapSupport.combinationOf(keymap.chord(actionId));
        return chord != null && chord.equals(defaultChord) ? defaultChord : chord;
    }

    /** The key of Previous Prompt, for the terminal panes' own key action (ShellIntegrationController). */
    static KeyCombination previousPromptAccelerator() {
        return PREVIOUS_PROMPT_ACCELERATOR;
    }

    /** The key of Next Prompt, for the terminal panes' own key action (ShellIntegrationController). */
    static KeyCombination nextPromptAccelerator() {
        return NEXT_PROMPT_ACCELERATOR;
    }

    /** The chord that opens and closes the command palette by default. */
    static KeyCombination commandPaletteAccelerator() {
        return COMMAND_PALETTE_ACCELERATOR;
    }

    /** Cmd/Ctrl+Shift+P: opens the command palette, or closes it while it shows. */
    private void toggleCommandPalette() {
        if (commandPalette != null && commandPalette.isShowing()) {
            commandPalette.hide();
        } else {
            showCommandPalette();
        }
    }

    /** The snippet palette's chord, once the user bound one: opens it, or closes the palette while it shows. */
    private void toggleSnippetPalette() {
        if (commandPalette != null && commandPalette.isShowing()) {
            commandPalette.hide();
        } else {
            showSnippetPalette();
        }
    }

    /** View → Snippet Palette…: the command palette with {@code $} typed, listing only the snippets. */
    private void showSnippetPalette() {
        showCommandPalette(de.kortty.ui.actions.PaletteEntry.Kind.SNIPPET);
    }

    /** View → Command Palette… and Cmd/Ctrl+Shift+P: the whole palette, see {@link #showCommandPalette(de.kortty.ui.actions.PaletteEntry.Kind)}. */
    private void showCommandPalette() {
        showCommandPalette(null);
    }

    /**
     * Brings the menu items' states up to date, then shows the palette over this window's commands,
     * the open tabs, the saved and teamwork connections and the snippets, centred at the top of the
     * window; with {@code scope} its scope prefix is already typed and only that kind is listed. A
     * connection opens like Connect in the Connection Manager and counts as a use of it; a snippet
     * runs like Send to Terminal in the Snippet Manager, in the pane its row names: the focused pane
     * of the terminal tab Send to Terminal picks.
     */
    private void showCommandPalette(de.kortty.ui.actions.PaletteEntry.@Nullable Kind scope) {
        if (sceneRoot == null || sceneRoot.getScene() == null || sceneRoot.getScene().getWindow() == null) {
            return;
        }
        refreshActionStates();
        if (commandPalette == null) {
            commandPalette = new CommandPalettePopup(
                List.of(new ActionPaletteSource(actionRegistry(), KeyCombination::getDisplayText,
                        de.kortty.policy.PolicyUiSupport::managedByOrganizationText,
                        () -> I18n.get("palette.disabled")),
                    new TabPaletteSource(this::paletteOwnTabs, this::paletteOtherWindowTabs,
                        TabPaletteRows::currentTabNote),
                    ConnectionPaletteRows.source(app,
                        connection -> connectSavedConnection(connection, true, tab -> { })),
                    SnippetPaletteRows.source(app, this)),
                PaletteKeys.passThrough(commandPaletteChord::chord, isMacOs())
                    .or(PaletteKeys.passThrough(snippetPaletteChord::chord, isMacOs())));
        }
        commandPalette.show(sceneRoot, scope);
    }

    /** This window's tabs for the palette, the most recently used first, and the one it shows. */
    private TabPaletteSource.WindowTabs paletteOwnTabs() {
        List<TabPaletteSource.TabRow> rows = new ArrayList<>();
        for (Tab tab : tabMru.order(tabPane.getTabs())) {
            rows.add(TabPaletteRows.row(tab, () -> selectTabFromPalette(tab)));
        }
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return new TabPaletteSource.WindowTabs(null, rows, selected != null ? TabPaletteRows.tabId(selected) : null);
    }

    /**
     * The terminal tabs of the other open windows for the palette, window by window in the order
     * they opened, each window's most recently used first, named by the window's place in that order.
     */
    private List<TabPaletteSource.WindowTabs> paletteOtherWindowTabs() {
        List<MainWindow> windows = List.copyOf(openWindows);
        List<TabPaletteSource.WindowTabs> result = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            MainWindow window = windows.get(i);
            if (window == this) {
                continue;
            }
            List<TabPaletteSource.TabRow> rows = new ArrayList<>();
            for (Tab tab : window.tabMru.order(window.tabPane.getTabs())) {
                if (tab instanceof TerminalTab) {
                    rows.add(TabPaletteRows.row(tab, () -> selectTabFromPalette(tab)));
                }
            }
            if (!rows.isEmpty()) {
                result.add(new TabPaletteSource.WindowTabs(TabPaletteRows.windowLabel(i + 1), rows, null));
            }
        }
        return result;
    }

    /**
     * A tab row of the palette was chosen: selects the tab in whichever window holds it now, and
     * brings that window to the front when it is not this one. Nothing happens when the tab closed
     * in the meantime.
     */
    private void selectTabFromPalette(Tab tab) {
        for (MainWindow window : List.copyOf(openWindows)) {
            if (window.tabPane.getTabs().contains(tab)) {
                if (window != this) {
                    WindowRaiser.raise(window.stage);
                }
                window.tabPane.getSelectionModel().select(tab);
                return;
            }
        }
    }

    /**
     * The actions of this window: every item of the in-window menu bar, harvested afresh each time
     * the palette opens (the menus that are rebuilt while they open are excluded), among them
     * <i>View → Panes</i> (the splits, the pane focus, Zoom Pane and broadcast mode) and <i>View →
     * Multi-exec</i>; then the tab actions that have no menu item, and then the right-click commands
     * of the selected terminal tab that have none either (Clear Buffer of its focused pane, Duplicate,
     * Reconnect and the switches Monitor for Activity and Monitor for Silence), enabled only while a
     * terminal tab is selected.
     */
    private ActionRegistry actionRegistry() {
        if (actionRegistry == null) {
            ActionRegistry registry = new ActionRegistry();
            registry.addContributor(() -> menuBar != null ? MenuActionHarvester.harvest(menuBar.getMenus()) : List.of());
            List<AppAction> tabActions = List.of(
                tabAction("palette.action.nextTab", new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
                    () -> switchTabOnce(false)),
                tabAction("palette.action.previousTab",
                    new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                    () -> switchTabOnce(true)));
            registry.addContributor(() -> tabActions);
            List<KeyCombination> clearBufferChords =
                TerminalView.clearBufferActionPresentation(isMacOs()).getKeyCombinations();
            List<AppAction> terminalActions = TerminalPaletteActions.actions(
                () -> tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab
                    ? new TerminalPaletteTarget(terminalTab, this::duplicateTab) : null,
                I18n::get, clearBufferChords.isEmpty() ? null : clearBufferChords.get(0));
            registry.addContributor(() -> terminalActions);
            actionRegistry = registry;
        }
        return actionRegistry;
    }

    /** A tab action labelled and identified by {@code key}, shown with the Ctrl+Tab chord the router handles. */
    private AppAction tabAction(String key, KeyCombination shownChord, Runnable run) {
        return new AppAction(key, I18n.get(key), I18n.get("palette.category.tab"), shownChord, List.of(),
            () -> tabPane.getTabs().size() > 1, null, run, true, false);
    }

    /**
     * Brings the enabled and checked state of the in-window menu bar's items up to date before the
     * command palette reads them. Some items are synced only when their menu opens, so every menu's
     * opening handler runs here (File: Rename Tab and the close items; Security: Unlock Vault;
     * Highlighting), and the syncs that otherwise run on other events are called directly. A feature
     * whose items are synced in neither way adds its sync method here.
     */
    private void refreshActionStates() {
        syncUnlockVaultMenuItems();
        syncAiFeaturesMenuItemsEnabled();
        syncPreventSleepMenuItems();
        updateEditMenuItemsForSelection();
        syncHighlightingToggleItems();
        // Show Command Timestamps is a setting of each terminal tab, but its check mark is synced only
        // when a tab toggles it, so after switching tabs it showed the other tab's state.
        if (tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab active) {
            syncTimestampMenuItems(active.isTimestampGuttersVisible());
        }
        if (menuBar != null) {
            MenuStateRefresh.refresh(menuBar.getMenus());
        }
    }

    /** A terminal tab is selected and the keyboard focus is inside it, or nowhere. */
    private boolean isKeyboardInSelectedTerminal() {
        if (!(tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab)) {
            return false;
        }
        Scene scene = stage.getScene();
        Node focusOwner = scene != null ? scene.getFocusOwner() : null;
        Node terminalRoot = terminalTab.getContent();
        return focusOwner == null || terminalRoot == null || isNodeUnderRoot(focusOwner, terminalRoot);
    }

    /** F12: toggles fullscreen and gives the selected terminal the focus back once the layout settled. */
    private void toggleFullscreenFromKeyboard() {
        boolean goFullscreen = !stage.isFullScreen();
        stage.setFullScreen(goFullscreen);
        // Force terminal resize after fullscreen change
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                // Double runLater to ensure layout is complete
                Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                if (selectedTab instanceof TerminalTab terminalTab) {
                    terminalTab.getTerminalView().requestFocus();
                }
            });
        });
    }
    
    public void show() {
        show(null, true);
    }

    /**
     * Shows the window.
     *
     * @param projectGeometry the bounds a project saved for this window, or {@code null} for the
     *     fixed or remembered window geometry of the settings
     * @param dashboardFromSettings whether the dashboard opens as the settings remember it; a window
     *     a project opens gets the project's dashboard state instead
     */
    private void show(WindowGeometry projectGeometry, boolean dashboardFromSettings) {
        // Restore window geometry if enabled
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        
        // Determine which geometry to use
        WindowGeometry geoToUse = null;
        
        if (projectGeometry != null) {
            geoToUse = projectGeometry;
        } else if (globalSettings.isUseFixedWindowGeometry() && globalSettings.getFixedWindowGeometry() != null) {
            // Use fixed geometry
            geoToUse = globalSettings.getFixedWindowGeometry();
        } else if (globalSettings.isRememberWindowGeometry() && globalSettings.getLastWindowGeometry() != null) {
            // Use last geometry
            geoToUse = globalSettings.getLastWindowGeometry();
        }
        
        MainWindowGeometrySupport.RestorePlan geometryRestore =
            MainWindowGeometrySupport.plan(geoToUse, unifiedTitleBarEnabled);
        MainWindowGeometrySupport.apply(stage, geometryRestore.geometry(), true);
        
        stage.show();
        installWindowGeometryPersistence();
        rememberNormalGeometry(geometryRestore.geometry());

        if (geometryRestore.reapplyAfterShow()) {
            // On macOS, attaching the native unified title bar during show() can move the stage
            // after the pre-show bounds were accepted. Reapply them once that native pass settled.
            Platform.runLater(() -> {
                if (stage.isShowing() && !stage.isMaximized() && !stage.isFullScreen()) {
                    MainWindowGeometrySupport.apply(stage, geometryRestore.geometry(), false);
                }
            });
        }

        // Restore the persisted AI-agent panel placement (bottom/left/right) once the window is shown.
        applyPersistedAiAgentPlacement();
        // Restore the persisted file-browser placement (hidden/left/right) and width.
        applyPersistedFileBrowser();
        // Restore the persisted live journal panel placement (hidden/left/right) and width.
        applyPersistedJournalLivePanel();
        // Restore the persisted Coding Agents panel placement (hidden/left/right) and width.
        applyPersistedCodingAgentPanel();
        updateForegroundActivity();
        // The first WebView of the session (AI Manager, snippet editor, guide, reports) would
        // otherwise freeze the FX thread for ~1 s while libjfxwebkit is extracted and loaded.
        WebKitPreloader.start();

        // Mark startup as complete after a short delay to allow UI to settle
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                startupComplete = true;
            });
        });
        
        // Restore dashboard state if enabled
        if (dashboardFromSettings && shouldRestoreDashboardOnStartup()) {
            Platform.runLater(() -> toggleDashboard(true));
        }

        scheduleOutdatedGuideTranslationCheck();
    }
    
    /**
     * Keeps the remembered window geometry current while the window is used. The close handler
     * alone is not enough: a quit routed through the platform (or a crash) skips it, and the size
     * the user just set would be lost. Debounced, and skipped while iconified or in fullscreen so
     * the restored "normal" geometry survives those states.
     */
    private void installWindowGeometryPersistence() {
        windowGeometrySaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(900));
        windowGeometrySaveDelay.setOnFinished(event -> {
            rememberNormalGeometry(null);
            persistWindowGeometry();
            // The session snapshot keeps every window's bounds.
            markSessionDirty();
        });
        javafx.beans.value.ChangeListener<Object> onGeometryChanged = (obs, oldValue, newValue) -> {
            if (stage.isShowing()) {
                windowGeometrySaveDelay.playFromStart();
            }
        };
        stage.widthProperty().addListener(onGeometryChanged);
        stage.heightProperty().addListener(onGeometryChanged);
        stage.xProperty().addListener(onGeometryChanged);
        stage.yProperty().addListener(onGeometryChanged);
        stage.maximizedProperty().addListener(onGeometryChanged);
    }

    /**
     * Notes the window's current bounds as its normal bounds while it is neither maximized, in
     * fullscreen nor minimized; otherwise keeps the known ones, or takes {@code applied}, the bounds
     * just applied to a window that opened maximized.
     */
    private void rememberNormalGeometry(WindowGeometry applied) {
        if (stage.isShowing() && !stage.isMaximized() && !stage.isFullScreen() && !stage.isIconified()) {
            lastNormalGeometry = new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        } else if (applied != null && applied.getWidth() > 0) {
            lastNormalGeometry = new WindowGeometry(applied.getX(), applied.getY(), applied.getWidth(), applied.getHeight());
        }
    }

    private void persistWindowGeometry() {
        try {
            var settingsManager = app.getGlobalSettingsManager();
            GlobalSettings settings = settingsManager != null ? settingsManager.getSettings() : null;
            if (settings == null || settings.isUseFixedWindowGeometry()) {
                return;
            }
            if (!stage.isShowing() || stage.isIconified() || stage.isFullScreen()) {
                return;
            }
            WindowGeometry geometry;
            WindowGeometry previous = settings.getLastWindowGeometry();
            if (stage.isMaximized() && previous != null && previous.getWidth() > 0) {
                // While maximized the stage reports the screen's size. Recording that as the
                // remembered geometry would destroy the normal size, so keep the last known one
                // and only carry the flag — un-maximizing then returns to a sensible window.
                geometry = new WindowGeometry(
                    previous.getX(), previous.getY(), previous.getWidth(), previous.getHeight());
            } else {
                geometry = new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            }
            geometry.setMaximized(stage.isMaximized());
            settings.setLastWindowGeometry(geometry);
            settingsManager.save();
        } catch (Exception e) {
            logger.debug("Could not persist the window geometry: {}", e.getMessage());
        }
    }

    /**
     * Kept for tab classes that notify the main window before closing.
     */
    public static void suppressNextQuickConnect() {
    }
    
    /**
     * Opens a new SSH connection in a new tab.
     */
    public void openConnection(ServerConnection connection, String password) {
        openConnection(connection, password, null, null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore.
     */
    public void openConnection(ServerConnection connection, String password, String historyToRestore) {
        openConnection(connection, password, historyToRestore, null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore and temporary SSH key.
     */
    public void openConnection(ServerConnection connection, String password, String historyToRestore, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        openConnectionAndReturnTab(
                connection,
                password,
                historyToRestore,
                null,
                temporarySSHKey,
                connection != null ? connection.getTerminalEffectPluginId() : null,
                connection != null ? connection.getTerminalEffectAnimationSpeed() : null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore and returns the tab.
     * The tab starts with NO group (independent from connection group).
     */
    private TerminalTab openConnectionAndReturnTab(ServerConnection connection, String password, String historyToRestore, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        return openConnectionAndReturnTab(
                connection,
                password,
                historyToRestore,
                null,
                temporarySSHKey,
                connection != null ? connection.getTerminalEffectPluginId() : null,
                connection != null ? connection.getTerminalEffectAnimationSpeed() : null);
    }

    /**
     * @param historyToRestore screen text a project saved for this tab; shown locally, dimmed,
     *     above the new session — never sent to the server
     * @param historySavedAt when that text was saved (for the header row), {@code null} if unknown
     */
    private TerminalTab openConnectionAndReturnTab(
            ServerConnection connection,
            String password,
            String historyToRestore,
            java.time.LocalDateTime historySavedAt,
            de.kortty.model.TemporarySSHKey temporarySSHKey,
            String terminalEffectPluginId,
            Double terminalEffectAnimationSpeed) {
        return openConnectionAndReturnTab(connection, password, historyToRestore, historySavedAt, temporarySSHKey,
                terminalEffectPluginId, terminalEffectAnimationSpeed, null, null);
    }

    /**
     * {@link #openConnectionAndReturnTab(ServerConnection, String, String, java.time.LocalDateTime,
     * de.kortty.model.TemporarySSHKey, String, Double)} for a tab restored from the session snapshot.
     *
     * @param restoredWorkingDirectory the directory the session snapshot saved for the tab's local
     *     shell, which starts there while it still exists; ignored by a remote tab, null for none
     * @param beforeConnect runs with the new tab's view on the FX thread before it connects (the
     *     saved output to restore), null for nothing
     */
    private TerminalTab openConnectionAndReturnTab(
            ServerConnection connection,
            String password,
            String historyToRestore,
            java.time.LocalDateTime historySavedAt,
            de.kortty.model.TemporarySSHKey temporarySSHKey,
            String terminalEffectPluginId,
            Double terminalEffectAnimationSpeed,
            String restoredWorkingDirectory,
            java.util.function.Consumer<TerminalView> beforeConnect) {
        // Central UI gate for the enterprise server policy — covers saved connections, session
        // restore, teamwork-shared connections and multi/swarm opens. Group opens and Duplicate
        // check it themselves; TerminalView.connect re-checks before every (re)connect attempt,
        // because the connection editor changes a saved connection in place. SFTPSession and
        // JobSchedulerRemoteSession do too, as non-UI backstops for their own paths.
        java.util.Optional<String> blockedTarget =
            de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(connection);
        if (blockedTarget.isPresent()) {
            de.kortty.policy.PolicyUiSupport.showBlockedServerDialog(blockedTarget.get());
            return null;
        }
        try {
            if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                terminalEffectPluginId = null;
                terminalEffectAnimationSpeed = null;
            }
            // Resolve temporary SSH key when connection was configured with one
            de.kortty.model.TemporarySSHKey keyToUse = temporarySSHKey;
            if (keyToUse == null && connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
                de.kortty.core.TemporarySSHKeyManager keyManager = de.kortty.core.TemporarySSHKeyManager.getInstance();
                Long expirationMinutes = connection.getTemporaryKeyExpirationMinutes();
                if (expirationMinutes == null || expirationMinutes <= 0) {
                    expirationMinutes = 15L;
                }
                de.kortty.model.TemporarySSHKey existingKey = keyManager.getTemporaryKey(connection.getTemporaryKeyContent());
                if (existingKey != null && existingKey.isValid()) {
                    keyToUse = existingKey;
                } else {
                    keyToUse = keyManager.storeTemporaryKey(connection.getTemporaryKeyContent(), expirationMinutes);
                }
                connection.setPrivateKeyPath("TEMPORARY:" + connection.getTemporaryKeyContent());
                connection.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
            } else if (keyToUse == null && connection.isTemporaryKeyPermanent() && 
                connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
                // Legacy path: permanent temporary key
                Long expirationMinutes = connection.getTemporaryKeyExpirationMinutes();
                if (expirationMinutes != null && expirationMinutes > 0) {
                    de.kortty.core.TemporarySSHKeyManager keyManager = de.kortty.core.TemporarySSHKeyManager.getInstance();
                    de.kortty.model.TemporarySSHKey existingKey = keyManager.getTemporaryKey(connection.getTemporaryKeyContent());
                    if (existingKey != null && existingKey.isValid()) {
                        keyToUse = existingKey;
                    } else {
                        keyToUse = keyManager.storeTemporaryKey(connection.getTemporaryKeyContent(), expirationMinutes);
                    }
                    connection.setPrivateKeyPath("TEMPORARY:" + connection.getTemporaryKeyContent());
                    connection.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
                }
            }
            
            // Create terminal tab with SithTermFX
            // Note: Tab starts with NO group (tabGroup = null), even if connection has a group
            TerminalTab terminalTab = new TerminalTab(connection, password, keyToUse);
            if (historyToRestore != null && !historyToRestore.isBlank()) {
                // Queued before connect(): the view writes it into the emulator before the
                // emulator starts reading the connection, so it never races the login output.
                terminalTab.getTerminalView().setPendingRestoredHistory(historyToRestore, historySavedAt);
            }
            if (restoredWorkingDirectory != null
                    && connection.getProtocol() == de.kortty.model.ConnectionProtocol.LOCAL_SHELL) {
                // Also before connect(): the local shell starts there. A remote tab never gets a cd.
                terminalTab.getTerminalView().setRestoredWorkingDirectory(restoredWorkingDirectory);
            }
            if (beforeConnect != null) {
                beforeConnect.accept(terminalTab.getTerminalView());
            }
            registerTerminalTabForAiAgentDock(terminalTab);
            if (terminalEffectAnimationSpeed != null) {
                terminalTab.getTerminalView().setTerminalEffectAnimationSpeed(terminalEffectAnimationSpeed);
            }
            terminalTab.getTerminalView().setTerminalEffectPluginId(terminalEffectPluginId);
            
            // Set callback for "Split with new connection" feature
            terminalTab.getTerminalView().setNewConnectionCallback(this::requestNewConnectionForSplit);
            installAiSelectionHandler(terminalTab);
            
            // Register timestamp toggle listener so context menu toggle updates the View menu
            terminalTab.setTimestampToggleListener(() -> {
                Platform.runLater(() -> {
                    Tab activeTab = tabPane.getSelectionModel().getSelectedItem();
                    if (activeTab instanceof TerminalTab active) {
                        syncTimestampMenuItems(active.isTimestampGuttersVisible());
                    }
                });
            });
            
            // Its close button remembers it for Recently Closed (moves between windows keep this).
            terminalTab.setOnUserCloseApproved(MainWindow::recordClosedByButton);
            applyConnectionColor(terminalTab);
            terminalTab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus(); // Update context menus when tab closes
            });
            
            // Setup context menu for group assignment (before group assignment)
            setupTabContextMenu(terminalTab);
            
            // Assign group from connection if present (for initial assignment)
            // This allows connection groups to be used as default for new tabs
            // Groups are automatically created if they don't exist yet
            if (connection.getGroup() != null && !connection.getGroup().trim().isEmpty()) {
                terminalTab.setGroup(connection.getGroup().trim());
            }
            
            // Insert new terminal tabs in group order.
            insertTabInGroupOrder(terminalTab);
            tabPane.getSelectionModel().select(terminalTab);
            
            // Update dashboard and context menus after group assignment
            updateDashboard();
            updateAllTabContextMenus();
            
            // Connect in background
            Thread connectThread = new Thread(() -> {
                try {
                    terminalTab.connect();
                    
                    // Set callback AFTER connect() to update dashboard when connection succeeds.
                    // This goes through TerminalTab's own callback slot (additive: it runs
                    // alongside TerminalTab's internal callback, not instead of it) rather than
                    // TerminalView's directly — that used to overwrite the tab's own registration
                    // (tab title/color, the disconnected-status banner, and the journal bar's
                    // refresh after an auto-started journal), silently dropping all of it whenever
                    // this dashboard/status update also needed to run.
                    terminalTab.setOnConnectedCallback(() -> {
                        updateStatus(I18n.get("status.connectedToWithHostAndProtocol",
                                connection.getDisplayName(), connection.getHost(), getProtocolLabel(connection.getProtocol())));
                        updateDashboard(); // Update dashboard when connection succeeds
                    });
                } catch (Exception ex) {
                    logger.error("Connection failed", ex);
                    Platform.runLater(() -> {
                        terminalTab.onConnectionFailed(ex.getMessage());
                        updateStatus(I18n.get("status.connectionFailed", ex.getMessage()));
                        updateDashboard(); // Update dashboard on failure too
                    });
                }
            });
            connectThread.setDaemon(true);
            connectThread.start();
            
            // Don't update dashboard immediately - wait for connection to establish
            // Dashboard will be updated after connection succeeds/fails
            Telemetry.track(TelemetryEvents.TERMINAL_TAB_OPENED, Map.of(
                "protocol", connection.getProtocol().name().toLowerCase(Locale.ROOT),
                "open_tabs", countTerminalTabsInWindow()));
            return terminalTab;
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            showError(I18n.get("error.connectionError"), I18n.get("status.sessionCreationFailed", e.getMessage()));
            return null;
        }
    }
    
    private void showQuickConnect() {
        // Prevent double-opening
        if (quickConnectDialogOpen) {
            return;
        }
        
        quickConnectDialogOpen = true;
        Telemetry.track(TelemetryEvents.CONNECT_UI_OPENED, Map.of("ui", "quick_connect"));

        try {
            // Create password vault for retrieving stored passwords
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            // Pass saved connections and vault to the dialog
            QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault, 
                    app.getCredentialManager(), app.getSSHKeyManager(), 
                    app.getMasterPasswordManager().getMasterPassword(), 10);
            dialog.showAndWait().ifPresent(result -> {
            // Handle load project request
            if (result.isLoadProject()) {
                openProject();
                return;
            }
            
            // Handle group connection
            if (result.isGroupConnection()) {
                openGroupConnections(result.groupName());
                return;
            }
            
            if (result.connection() == null) {
                return;
            }

            // Enterprise server policy: reject a blocked target before prompting for a password
            // or persisting the connection (openConnectionAndReturnTab would catch it anyway).
            java.util.Optional<String> quickConnectBlocked =
                de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(result.connection());
            if (quickConnectBlocked.isPresent()) {
                de.kortty.policy.PolicyUiSupport.showBlockedServerDialog(quickConnectBlocked.get());
                return;
            }

            String password = result.password();
            String finalPassword = ensurePasswordForConnection(result.connection(), password);
            if (!result.connection().isLocalShell()
                    && result.connection().getAuthMethod() != de.kortty.model.AuthMethod.PUBLIC_KEY
                    && (finalPassword == null || finalPassword.isBlank())) {
                return; // User cancelled password prompt or no valid password available
            }
            
            // Increment usage count for existing saved connection (update stored connection so "last used" is correct)
            if (result.existingSaved()) {
                ServerConnection stored = app.getConfigManager().getConnectionById(result.connection().getId());
                if (stored != null) {
                    stored.incrementUsageCount();
                    if (result.connection().getSettings() != null) {
                        stored.setSettings(new ConnectionSettings(result.connection().getSettings()));
                    }
                    stored.setTerminalEffectPluginId(result.connection().getTerminalEffectPluginId());
                    stored.setTerminalEffectAnimationSpeed(result.connection().getTerminalEffectAnimationSpeed());
                    stored.setTerminalEmulationType(result.connection().getTerminalEmulationType());
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                }
            }
            
            // Save connection if requested (for new connections)
            if (result.save() && !result.existingSaved()) {
                // Store password encrypted
                if (finalPassword != null && !finalPassword.isEmpty()) {
                    vault.storePassword(result.connection(), finalPassword);
                }
                app.getConfigManager().addConnection(result.connection());
                try {
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                    logger.info("Connection saved: {}", result.connection().getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to save connection", e);
                }
            }
            // Pass temporary SSH key if available
            openConnection(result.connection(), finalPassword, null, result.temporarySSHKey());
            });
        } finally {
            quickConnectDialogOpen = false;
        }
    }
    
    /**
     * Opens QuickConnect dialog for split terminal and returns the connection result.
     * Used by TerminalView when user requests "Split with new connection".
     */
    private TerminalView.ConnectionResult requestNewConnectionForSplit() {
        logger.info("requestNewConnectionForSplit() called - Opening QuickConnect for split");
        
        try {
            // Create password vault for retrieving stored passwords
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            // Open QuickConnect dialog
            QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault,
                    app.getCredentialManager(), app.getSSHKeyManager(),
                    app.getMasterPasswordManager().getMasterPassword(), 10);
            
            var optResult = dialog.showAndWait();
            if (optResult.isEmpty()) {
                return null; // User cancelled
            }
            
            var result = optResult.get();
            
            // Don't handle load project or group connection for splits
            if (result.isLoadProject() || result.isGroupConnection()) {
                return null;
            }
            
            if (result.connection() == null) {
                return null;
            }

            // Enterprise server policy, as for Quick Connect: refuse a blocked target or jump host
            // before prompting for a password or persisting the connection. null reads as
            // "cancelled" in TerminalView, so no connector is built for it.
            java.util.Optional<String> splitBlocked = SplitConnectionPolicy.blockedTarget(result.connection());
            if (splitBlocked.isPresent()) {
                de.kortty.policy.PolicyUiSupport.showBlockedServerDialog(splitBlocked.get());
                return null;
            }

            String password = result.password();
            String finalPassword = ensurePasswordForConnection(result.connection(), password);
            if (!result.connection().isLocalShell()
                    && result.connection().getAuthMethod() != de.kortty.model.AuthMethod.PUBLIC_KEY
                    && (finalPassword == null || finalPassword.isBlank())) {
                return null;
            }
            
            // Increment usage count for existing saved connection (update stored connection so "last used" is correct)
            if (result.existingSaved()) {
                ServerConnection stored = app.getConfigManager().getConnectionById(result.connection().getId());
                if (stored != null) {
                    stored.incrementUsageCount();
                    if (result.connection().getSettings() != null) {
                        stored.setSettings(new ConnectionSettings(result.connection().getSettings()));
                    }
                    stored.setTerminalEffectPluginId(result.connection().getTerminalEffectPluginId());
                    stored.setTerminalEffectAnimationSpeed(result.connection().getTerminalEffectAnimationSpeed());
                    stored.setTerminalEmulationType(result.connection().getTerminalEmulationType());
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                }
            }
            
            // Save connection if requested (for new connections)
            if (result.save() && !result.existingSaved()) {
                if (finalPassword != null && !finalPassword.isEmpty()) {
                    vault.storePassword(result.connection(), finalPassword);
                }
                app.getConfigManager().addConnection(result.connection());
                try {
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                    logger.info("Connection saved: {}", result.connection().getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to save connection", e);
                }
            }
            
            return new TerminalView.ConnectionResult(result.connection(), finalPassword, result.temporarySSHKey());
        } catch (Exception e) {
            logger.error("Failed to request new connection for split", e);
            return null;
        }
    }

    private String ensurePasswordForConnection(ServerConnection connection, String candidatePassword) {
        // Local shells run a local process with no authentication; never prompt for a password.
        if (connection == null || connection.isLocalShell()
                || connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
            return candidatePassword;
        }

        if (candidatePassword != null && !candidatePassword.isBlank()) {
            return candidatePassword;
        }

        String stored = getConnectionPassword(connection);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return promptForConnectionPassword(connection);
    }

    /**
     * Asks for the password of {@code connection}, in a dialog owned by this window whose OK stays
     * disabled while the field is empty.
     *
     * @return the password, or {@code null} when the user cancelled
     */
    private String promptForConnectionPassword(ServerConnection connection) {
        Dialog<String> pwDialog = new Dialog<>();
        DialogThemeHelper.applyTheme(pwDialog);
        pwDialog.initOwner(stage);
        pwDialog.setTitle(I18n.get("dialog.passwordRequired"));
        pwDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
        pwDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField pwField = new PasswordField();
        pwField.setPromptText(I18n.get("dialog.enterPassword"));
        VBox content = new VBox(10);
        content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), pwField);
        content.setPadding(new javafx.geometry.Insets(20));
        pwDialog.getDialogPane().setContent(content);
        Button okButton = (Button) pwDialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.disableProperty().bind(pwField.textProperty().isEmpty());
        pwField.requestFocus();
        pwDialog.setResultConverter(bt -> bt == ButtonType.OK ? pwField.getText() : null);
        return pwDialog.showAndWait().orElse(null);
    }
    
    private void showConnectionManager() {
        Telemetry.track(TelemetryEvents.CONNECT_UI_OPENED, Map.of("ui", "connection_manager"));
        logger.info("showConnectionManager() called - Opening Connection Manager");
        ConnectionManagerDialog dialog = new ConnectionManagerDialog(stage, app);
        dialog.setOnConnectionsSavedCallback(this::refreshAllTerminalTabsConnectionSettings);
        dialog.showAndWait().ifPresent(connection -> connectSavedConnection(connection, false, tab -> { }));
    }

    /**
     * Opens a tab for a saved (or teamwork-shared) connection through the one sign-in flow of
     * {@link ConnectionAuthResolver}: the server policy is checked before anything is asked, then
     * the teamwork default authentication, a temporary SSH key (reused while valid, asked for when
     * expired), a local shell or SSH key, the stored password — offering to unlock a locked vault —
     * or a password prompt. The tab opens through {@code openConnectionAndReturnTab}, which keeps the
     * central policy gate, the connection's terminal effect and the telemetry.
     *
     * @param recordUsage whether to count this as a use of the saved connection ("last used")
     * @param onOpened    runs with the new tab once it is in the window
     * @return {@link ConnectionAuthResolver.Status#READY} when a tab opened;
     *         {@link ConnectionAuthResolver.Status#BLOCKED} when the policy refused the target or the
     *         tab could not be created (the user has seen why); otherwise the step the user cancelled,
     *         or {@link ConnectionAuthResolver.Status#MISSING} for no connection
     */
    private ConnectionAuthResolver.Status connectSavedConnection(
            ServerConnection connection, boolean recordUsage, java.util.function.Consumer<TerminalTab> onOpened) {
        ConnectionAuthResolver.Resolution auth = resolveConnectionAuthInteractively(connection);
        if (!auth.isReady()) {
            return auth.status();
        }
        ServerConnection resolved = auth.connection();
        TerminalTab tab = openConnectionAndReturnTab(
                resolved,
                auth.password(),
                null,
                null,
                auth.temporaryKey(),
                resolved.getTerminalEffectPluginId(),
                resolved.getTerminalEffectAnimationSpeed());
        if (tab == null) {
            return ConnectionAuthResolver.Status.BLOCKED;
        }
        if (recordUsage) {
            recordConnectionUsage(resolved);
        }
        onOpened.accept(tab);
        return ConnectionAuthResolver.Status.READY;
    }

    /**
     * {@link ConnectionAuthResolver#resolve resolves} sign-in for {@code connection}, asking for what
     * is missing, and shows the policy message when the server policy blocks the target. The policy
     * is checked before any prompt.
     */
    private ConnectionAuthResolver.Resolution resolveConnectionAuthInteractively(ServerConnection connection) {
        ConnectionAuthResolver.Resolution auth = connectionAuthResolver().resolve(connection, true);
        if (auth.status() == ConnectionAuthResolver.Status.BLOCKED) {
            de.kortty.policy.PolicyUiSupport.showBlockedServerDialog(auth.blockedTarget());
        }
        return auth;
    }

    /** The sign-in resolver over the running application, asking its questions in this window. */
    private ConnectionAuthResolver connectionAuthResolver() {
        return new ConnectionAuthResolver(ConnectionAuthResolver.forApplication(app), new ConnectionAuthResolver.Prompts() {
            @Override
            public String password(ServerConnection connection) {
                return promptForConnectionPassword(connection);
            }

            @Override
            public de.kortty.model.TemporarySSHKey temporaryKey(ServerConnection connection) {
                return requestNewTemporarySSHKey(connection);
            }

            @Override
            public boolean unlockVault(ServerConnection connection) {
                return VaultUnlockSupport.offerUnlock(stage, app.getMasterPasswordManager(),
                        I18n.get("dialog.passwordVaultLocked", connection.getDisplayName()));
            }
        });
    }

    /**
     * Counts a use of the saved connection behind {@code connection}; teamwork and unsaved connections
     * are skipped. A teamwork connection is skipped by its source, not only by its id: a shared file
     * may reuse the id of a saved connection, whose use it must not count.
     */
    private void recordConnectionUsage(ServerConnection connection) {
        ServerConnection stored = connection.getId() != null && !connection.isTeamworkConnection()
                ? app.getConfigManager().getConnectionById(connection.getId())
                : null;
        if (stored == null) {
            return;
        }
        stored.incrementUsageCount();
        app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
    }
    
    /**
     * Applies the current saved connection settings (font, etc.) to all open terminal tabs.
     * Called when connections are saved in Connection Manager so changes take effect immediately.
     */
    private void refreshAllTerminalTabsConnectionSettings() {
        refreshHighlightingAfterConnectionsSaved();
        boolean groupChanged = false;
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                ServerConnection conn = terminalTab.getConnection();
                if (conn != null && conn.getId() != null) {
                    ServerConnection stored = app.getConfigManager().getConnectionById(conn.getId());
                    if (stored != null) {
                        // The tab's connection may be a copy holding the settings object it was
                        // opened with; give it the saved one, so the live global refresh and the
                        // next reconnect see whether the connection now uses its own settings.
                        if (conn != stored && stored.getSettings() != null) {
                            conn.setSettings(stored.getSettings());
                        }
                        // Own settings apply as saved; a connection switched back to the global
                        // settings (or without settings) gets the global defaults again.
                        terminalTab.applyConnectionSettings(stored.getSettings());
                        // Propagate the connection's group to the open tab only when it
                        // actually changed since the tab last saw it (baseline snapshot) —
                        // a manually assigned tab group must survive unrelated saves.
                        String storedGroup = stored.getGroup() != null && !stored.getGroup().trim().isEmpty()
                                ? stored.getGroup().trim() : null;
                        if (!java.util.Objects.equals(storedGroup, terminalTab.getConnectionGroupBaseline())) {
                            terminalTab.setConnectionGroupBaseline(storedGroup);
                            if (!java.util.Objects.equals(storedGroup, terminalTab.getGroup())) {
                                terminalTab.setGroup(storedGroup);
                                groupChanged = true;
                            }
                        }
                        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                            terminalTab.getTerminalView().setTerminalEffectAnimationSpeed(
                                    stored.getTerminalEffectAnimationSpeed() != null
                                            ? stored.getTerminalEffectAnimationSpeed()
                                            : TerminalEffectAnimationSpeed.DEFAULT);
                            terminalTab.getTerminalView().setTerminalEffectPluginId(stored.getTerminalEffectPluginId());
                        } else {
                            terminalTab.getTerminalView().setTerminalEffectPluginId(null);
                        }
                    }
                }
            }
        }
        if (groupChanged) {
            // organizeTabsByGroup() already rebuilds every tab's context menu.
            organizeTabsByGroup();
            updateDashboard();
        }
        applyTunnelSettingsToOpenTabs();
        refreshConnectionColorsInAllWindows();
    }

    /**
     * Shows the tab color of {@code tab}'s connection on the tab: the saved connection's, so edits
     * in the Connection Manager apply, else the tab's own, else the color of the connection's group,
     * else the color of the environment of the stored credential the tab signed in with (see
     * {@link #effectiveTabColor}), with the frame around the terminal unless the Window settings
     * switch it off. A split pane that runs another connection whose color, resolved the same way,
     * differs from the tab's gets a frame of its own color (see {@link PaneConnectionColors}).
     */
    private void applyConnectionColor(TerminalTab tab) {
        ConnectionColorSupport.TabColor color = effectiveTabColor(tab.getConnection());
        boolean showFrame = TabColorPresentation.frameEnabled(app.getGlobalSettingsManager().getSettings());
        tab.applyConnectionColor(color != null ? color.hex() : null,
                color != null ? color.source() : null, colorSourceName(color), showFrame);
        tab.applyPaneConnectionColors(color != null ? color.hex() : null, showFrame, paneConnection -> {
            ConnectionColorSupport.TabColor paneColor = effectiveTabColor(paneConnection);
            return paneColor != null ? paneColor.hex() : null;
        });
    }

    /**
     * The color a tab or split pane of {@code connection} shows, and where it comes from: the
     * connection's own color, then its group's, then its credential environment's (see
     * {@link ConnectionColorSupport#effectiveTabColor}).
     */
    private ConnectionColorSupport.TabColor effectiveTabColor(ServerConnection connection) {
        return ConnectionColorSupport.effectiveTabColor(connection, app.getConfigManager()::getConnectionById,
                this::groupColor, this::credentialEnvironmentId, this::environmentColor);
    }

    /**
     * What a tab's tooltip names as the source of {@code color}: the credential environment or the
     * group it comes from, cleaned for display; {@code null} for a color set on the connection.
     */
    private String colorSourceName(ConnectionColorSupport.TabColor color) {
        if (color == null) {
            return null;
        }
        return switch (color.source()) {
            case ENVIRONMENT -> TabColorPresentation.environmentLabel(
                    app.getEnvironmentManager().getDisplayName(color.environmentId()), color.environmentId());
            case GROUP -> TabColorPresentation.groupLabel(color.groupPath());
            case CONNECTION -> null;
        };
    }

    /** The tab color the connection group {@code groupPath} has of its own, or null (see {@link GlobalSettings#getConnectionGroupColor}). */
    private String groupColor(String groupPath) {
        GlobalSettings settings = app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null;
        return settings != null ? settings.getConnectionGroupColor(groupPath) : null;
    }

    /** The environment id of the stored credential {@code credentialId}, or null when there is no such credential. */
    private String credentialEnvironmentId(String credentialId) {
        if (app.getCredentialManager() == null) {
            return null;
        }
        return app.getCredentialManager().findCredentialById(credentialId)
                .map(StoredCredential::getEnvironmentId)
                .orElse(null);
    }

    /** The tab color of the credential environment {@code environmentId}, or null when it has none. */
    private String environmentColor(String environmentId) {
        return app.getEnvironmentManager() != null ? app.getEnvironmentManager().getColor(environmentId) : null;
    }

    /**
     * Re-applies the connection colors of every open terminal tab, in every window, after connections,
     * credentials, environments, group colors or the global settings were saved: a color set, changed
     * or removed in the Connection Manager (on a connection or a group) or the Environments dialog, a
     * connection moved to another group, a credential moved to another environment, and the frame
     * switched on or off in the Window settings, show at once. FX thread only.
     */
    static void refreshConnectionColorsInAllWindows() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            for (TerminalTab terminalTab : window.terminalTabs()) {
                window.applyConnectionColor(terminalTab);
            }
        }
    }

    /**
     * Lets every open terminal tab, in every window, show or drop the title its shell set, after the
     * Window setting for it was saved. FX thread only.
     */
    private static void refreshShellTitlesInAllWindows() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            for (TerminalTab terminalTab : window.terminalTabs()) {
                terminalTab.refreshShellTitle();
            }
        }
    }

    /**
     * Lets every open terminal tab, in every window, apply its connection's saved SSH tunnels
     * right away: tunnels switched off or removed in the connection editor stop, changed or added
     * ones open (after the one-time confirmation for a new tunnel set). A tab whose tunnels did
     * not change keeps them running. FX thread only.
     */
    private static void applyTunnelSettingsToOpenTabs() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            for (TerminalTab terminalTab : window.terminalTabs()) {
                if (terminalTab.getTerminalView() != null) {
                    terminalTab.getTerminalView().applyTunnelSettings();
                }
            }
        }
    }

    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(stage, app, app.getConfigManager(),
                app.getGlobalSettingsManager().getSettings(),
                app.getCredentialManager(), app.getGpgKeyManager());
        // The Keyboard page lists the actions of this window's menu bar.
        dialog.setKeymapCatalogSource(this::keymapCatalog);

        // Add listener to apply settings changes immediately to all open terminals
        dialog.addChangeListener(() -> {
            logger.info("Settings changed, updating all terminal views");
            Platform.runLater(() -> {
                // Must precede the refresh: it re-reads the screen so an auto-mode change takes
                // effect in the same pass that re-applies the stylesheets.
                UiFontScaleSupport.invalidateAutoCache();
                refreshAppDesignForOpenWindows();
                syncHideFullscreenScrollbarsMenuItems();
                // The master switch or the default set may have changed what the focused pane shows.
                syncHighlightingToggleItems();
                applyTerminalScrollbarVisibilityForOpenTabs();
                syncAiFeaturesMenuItemsEnabled();
                refreshTerminalTabsUsingGlobalDefaults();
                refreshConnectionColorsInAllWindows();
                refreshShellTitlesInAllWindows();
                applyRemoteSidebarToAllWindows();
                refreshTerminalRecordingControlsVisibility();
                refreshOpenChatColorProfiles();
                // The shortcut overrides may have changed: menu accelerators and router chords.
                refreshKeymapInAllWindows();
                if (menuBar != null && !menuBar.isVisible()) {
                    updateStatus(I18n.get("menu.view.menuBar.hiddenHint", menuBarToggleShortcutLabel()));
                } else {
                    updateStatus(I18n.get("status.globalSettingsSaved"));
                }
            });
        });
        
        dialog.showAndWait();
    }

    /** Re-applies the selected chat color profile to every open AI/swarm chat tab. */
    void refreshOpenChatColorProfiles() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof AiResultTab aiResultTab) {
                aiResultTab.refreshChatColorProfile();
            } else if (tab instanceof SwarmAgentTab swarmAgentTab) {
                swarmAgentTab.refreshChatColorProfile();
            }
        }
    }

    /** The font size a tab of a connection with {@code connSettings} opens with (own or global). */
    private int sessionFontSizeBaseline(ConnectionSettings connSettings) {
        ConnectionSettings globalDefaults = null;
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            globalDefaults = gs != null ? gs.getDefaultTerminalSettings() : null;
        } catch (Exception e) {
            logger.debug("Could not read global defaults for the session font baseline: {}", e.getMessage());
        }
        return de.kortty.core.ConnectionSettingsSupport.effectiveTerminalSettings(connSettings, globalDefaults).getFontSize();
    }

    private void refreshTerminalTabsUsingGlobalDefaults() {
        ConnectionSettings globalDefaults = null;
        try {
            var gs = app.getGlobalSettingsManager().getSettings();
            if (gs != null && gs.getDefaultTerminalSettings() != null) {
                globalDefaults = new ConnectionSettings(gs.getDefaultTerminalSettings());
            }
        } catch (Exception e) {
            logger.debug("Could not load global defaults for live refresh: {}", e.getMessage());
        }
        if (globalDefaults == null) {
            return;
        }

        for (Tab tab : tabPane.getTabs()) {
            if (!(tab instanceof TerminalTab terminalTab)) {
                continue;
            }
            ServerConnection conn = terminalTab.getConnection();
            ConnectionSettings connSettings = conn != null ? conn.getSettings() : null;
            if (connSettings == null || connSettings.isUseGlobalSettings()) {
                terminalTab.applyConnectionSettings(globalDefaults);
            } else {
                terminalTab.applyConnectionSettings(connSettings);
            }
        }
    }

    private void applyMainWindowThemeFromGlobalSettings() {
        try {
            boolean customAppDesign = AppDesignStyleSupport.isCustomAppDesignActive();
            ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(app);
            String bg = colors != null ? colors.backgroundColor() : null;
            String fg = colors != null ? colors.foregroundColor() : null;

            if (customAppDesign) {
                clearMainWindowInlineThemeStyles();
                if (transparentWindowMode) {
                    applyTransparentModeContainerBackgrounds(AppDesignStyleSupport.activeBackgroundColor());
                }
            } else if (transparentWindowMode) {
                // See-through mode: punch the content chain through to the desktop ONLY when a terminal
                // is the active tab; otherwise keep it opaque-themed so there is no see-through hole.
                // The status bar chrome always stays opaque (fall back to the default dark if unthemed).
                applyTransparentModeContainerBackgrounds(bg);
                String statusBg = (bg != null && !bg.isEmpty()) ? bg : "#2d2d2d";
                statusBar.setStyle("-fx-padding: 5; -fx-background-color: " + statusBg + ";");
                if (stage.getScene() != null) {
                    stage.getScene().setFill(Color.TRANSPARENT);
                }
            } else if (bg != null && !bg.isEmpty()) {
                String bgStyle = "-fx-background-color: " + bg + ";";
                root.setStyle(bgStyle);
                mainContentBox.setStyle(bgStyle);
                tabPane.setStyle(bgStyle + " -fx-control-inner-background: " + bg + ";");
                statusBar.setStyle("-fx-padding: 5; " + bgStyle);
                if (stage.getScene() != null) {
                    stage.getScene().setFill(unifiedTitleBarEnabled ? Color.TRANSPARENT : Color.web(bg));
                }
            }
            if (!customAppDesign && fg != null && !fg.isEmpty()) {
                statusLabel.setStyle("-fx-text-fill: " + fg + ";");
            }
            if (dashboardView != null) {
                dashboardView.applyTheme(bg, fg);
            }
            if (localFileBrowser != null) {
                localFileBrowser.applyTheme(bg, fg);
            }
            if (journalLivePanel != null) {
                journalLivePanel.applyTheme(bg, fg);
            }
            if (codingAgentPanel != null) {
                codingAgentPanel.applyTheme(bg, fg);
            }
            if (codingAgentStatusStrip != null) {
                codingAgentStatusStrip.applyTheme(bg, fg);
            }
            if (customAppDesign) {
                // The app design fully owns the chrome; the terminal-theme dynamic stylesheet would
                // override its menu/button/label colours, so strip it while a custom design is active.
                removeDynamicThemeStylesheet();
            } else if (bg != null && !bg.isEmpty()) {
                updateDynamicThemeStylesheet(bg, fg);
            }
            if (stage.getScene() != null) {
                AppDesignStyleSupport.applyToScene(stage.getScene());
                if (customAppDesign) {
                    stage.getScene().setFill(unifiedTitleBarEnabled || transparentWindowMode
                        ? Color.TRANSPARENT
                        : Color.web(AppDesignStyleSupport.activeBackgroundColor()));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not apply main window theme from global settings: {}", e.getMessage());
        }
    }

    /**
     * In transparent (see-through) window mode, clears the background of the terminal content chain
     * (root, mainContentBox, tabPane content) so the desktop shows through the translucent terminal.
     * The title bar, menu bar and status bar keep their own opaque backgrounds and stay readable.
     */
    /**
     * In see-through mode, chooses the content-chain background depending on whether a terminal is the
     * active tab. With a terminal visible the chain (root/mainContentBox/tabPane content) is transparent
     * so the desktop shows through the translucent terminal; otherwise it stays opaque-themed so an empty
     * or non-terminal tab doesn't become a see-through hole. Reacts to tab switches via
     * {@link #refreshTransparentModeContainers()}.
     */
    private void applyTransparentModeContainerBackgrounds(String bg) {
        Tab active = tabPane.getSelectionModel().getSelectedItem();
        if (active instanceof TerminalTab && !stage.isFullScreen()) {
            // The scene root wrapper matches the design's `.root` rule, which paints an opaque
            // background behind the whole window and would hide the desktop again.
            if (sceneRoot != null) {
                sceneRoot.setStyle("-fx-background-color: transparent;");
            }
            root.setStyle("-fx-background-color: transparent;");
            mainContentBox.setStyle("-fx-background-color: transparent;");
            tabPane.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        } else {
            String c = (bg != null && !bg.isEmpty()) ? bg : "#1e1e1e";
            String bgStyle = "-fx-background-color: " + c + ";";
            if (sceneRoot != null) {
                // Back to the stylesheet, so terminal-only fullscreen keeps its backdrop colour.
                sceneRoot.setStyle(null);
            }
            root.setStyle(bgStyle);
            mainContentBox.setStyle(bgStyle);
            tabPane.setStyle(bgStyle + " -fx-control-inner-background: " + c + ";");
        }
    }

    /** Re-evaluates just the see-through container backgrounds when the active tab changes. */
    private void refreshTransparentModeContainers() {
        if (!transparentWindowMode) {
            return;
        }
        try {
            if (AppDesignStyleSupport.isCustomAppDesignActive()) {
                applyTransparentModeContainerBackgrounds(AppDesignStyleSupport.activeBackgroundColor());
            } else {
                ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(app);
                applyTransparentModeContainerBackgrounds(colors != null ? colors.backgroundColor() : null);
            }
        } catch (Exception e) {
            logger.debug("Could not refresh see-through containers: {}", e.getMessage());
        }
    }

    /** Reads the persisted terminal background transparency (0..100), defaulting to 0 on error. */
    private int currentBackgroundTransparencyPercent() {
        try {
            GlobalSettings gs = app.getGlobalSettingsManager().getSettings();
            if (gs != null) {
                return gs.getTerminalBackgroundTransparency();
            }
        } catch (Exception e) {
            logger.debug("Could not read transparency setting: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Applies the current background transparency to one terminal tab. The translucent alpha is only
     * used when the window actually runs in see-through mode outside fullscreen; otherwise the terminal
     * stays opaque so a lingering setting can't dim the terminal over an opaque window. The persisted
     * percentage is never changed by this temporary fullscreen suppression.
     */
    private void applyBackgroundTransparencyToTab(TerminalTab terminalTab) {
        if (terminalTab == null) {
            return;
        }
        TerminalView view = terminalTab.getTerminalView();
        if (view == null) {
            return;
        }
        boolean transparencyActive = transparentWindowMode && !stage.isFullScreen();
        view.setBackgroundTransparent(transparencyActive);
        view.setBackgroundTransparency(transparencyActive ? currentBackgroundTransparencyPercent() : 0);
    }

    /** Whether the see-through window currently shows the terminal background transparency at all. */
    private boolean isBackgroundTransparencyVisible() {
        return tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab && !stage.isFullScreen();
    }

    /** Live-applies the current background transparency to every open terminal tab (used by the slider). */
    private void applyBackgroundTransparencyToAllTabs() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                applyBackgroundTransparencyToTab(terminalTab);
            }
        }
    }

    private void clearMainWindowInlineThemeStyles() {
        root.setStyle(null);
        mainContentBox.setStyle(null);
        tabPane.setStyle(null);
        if (statusBar != null) {
            statusBar.setStyle("-fx-padding: 5;");
        }
        statusLabel.setStyle(null);
        if (codingAgentPanel != null) {
            codingAgentPanel.applyTheme(null, null);
        }
        if (codingAgentStatusStrip != null) {
            codingAgentStatusStrip.applyTheme(null, null);
        }
    }

    private void updateDynamicThemeStylesheet(String bg, String fg) {
        if (stage.getScene() == null || bg == null || bg.isEmpty()) {
            return;
        }
        try {
            String stylesheetUrl = ThemeCssSupport.getDynamicStylesheetUrl(bg, fg);
            if (stylesheetUrl == null) {
                return;
            }
            var sheets = stage.getScene().getStylesheets();
            if (dynamicThemeStylesheetUrl != null && !dynamicThemeStylesheetUrl.equals(stylesheetUrl)) {
                sheets.remove(dynamicThemeStylesheetUrl);
            }
            dynamicThemeStylesheetUrl = stylesheetUrl;
            if (!sheets.contains(dynamicThemeStylesheetUrl)) {
                sheets.add(dynamicThemeStylesheetUrl);
            }
        } catch (Exception e) {
            logger.debug("Could not update dynamic theme stylesheet: {}", e.getMessage());
        }
    }

    private void removeDynamicThemeStylesheet() {
        if (stage.getScene() != null && dynamicThemeStylesheetUrl != null) {
            stage.getScene().getStylesheets().remove(dynamicThemeStylesheetUrl);
        }
        dynamicThemeStylesheetUrl = null;
    }

    private static void refreshAppDesignForOpenWindows() {
        AppDesignStyleSupport.applyUserAgentStylesheet(AppDesignStyleSupport.activeDesign());
        for (MainWindow window : new ArrayList<>(openWindows)) {
            window.applyMainWindowThemeFromGlobalSettings();
        }
        AppDesignStyleSupport.applyToOpenWindows();
        AppDesignAnimator.refreshAll();
    }
    
    private void openNewWindow() {
        Stage newStage = new Stage();
        MainWindow newWindow = new MainWindow(newStage);
        newWindow.show();
    }

    public static void reopenOrCreateWindow() {
        MainWindow targetWindow = getFocusedOrLastOpenWindow();
        if (targetWindow != null) {
            targetWindow.stage.show();
            targetWindow.stage.toFront();
            targetWindow.stage.requestFocus();
            return;
        }

        Stage newStage = new Stage();
        MainWindow newWindow = new MainWindow(newStage);
        newWindow.show();
    }

    public static boolean hasOpenWindows() {
        return !openWindows.isEmpty();
    }

    /** Number of currently open main windows (telemetry gauge; FX thread only). */
    public static int getOpenWindowCount() {
        return openWindows.size();
    }

    /** Open terminal tabs in this window, excluding AI tabs (telemetry gauge). */
    private int countTerminalTabsInWindow() {
        int count = 0;
        for (javafx.scene.control.Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab) {
                count++;
            }
        }
        return count;
    }

    /** Open terminal tabs across all windows, excluding AI tabs (telemetry gauge; FX thread only). */
    public static int getOpenTerminalTabCount() {
        int count = 0;
        for (MainWindow window : new ArrayList<>(openWindows)) {
            for (javafx.scene.control.Tab tab : window.tabPane.getTabs()) {
                if (tab instanceof TerminalTab) {
                    count++;
                }
            }
        }
        return count;
    }

    public static void requestApplicationQuit() {
        MainWindow promptWindow = getFocusedOrLastOpenWindow();
        if (maybeHandleSchedulerDrainBeforeExit(promptWindow, MainWindow::requestApplicationQuit)) {
            return;
        }

        List<MainWindow> windowsToClose = new ArrayList<>(openWindows);
        if (windowsToClose.isEmpty()) {
            // macOS keep-alive without a window: standalone snippet editors (e.g. the swarm's
            // "save as snippet" editor) may still hold unsaved work — shutdownAndExit() halts.
            if (!HostedCloseGuards.confirmEditors(HostedCloseGuards.standaloneEditorsOutside(List.of()))) {
                clearApplicationQuitState();
                return;
            }
            applicationQuitRequested = true;
            KorTTYApplication.getInstance().shutdownAndExit();
            return;
        }

        applicationQuitApprovedWindows.clear();
        applicationQuitConfirmationInProgress = true;
        try {
            for (MainWindow window : windowsToClose) {
                if (!window.confirmClose()) {
                    clearApplicationQuitState();
                    return;
                }
                applicationQuitApprovedWindows.add(window);
            }
        } finally {
            applicationQuitConfirmationInProgress = false;
        }
        // Last: snippet editors no window owns (asked once, after every window agreed).
        List<Window> windowStages = windowsToClose.stream().map(window -> (Window) window.stage).toList();
        if (!HostedCloseGuards.confirmEditors(HostedCloseGuards.standaloneEditorsOutside(windowStages))) {
            clearApplicationQuitState();
            return;
        }

        applicationQuitRequested = true;
        // Every window agreed: the session snapshot is written and sealed before the first window
        // closes its tabs, so the windows closing one after another cannot shrink it.
        sealSessionSnapshotForExit();
        for (MainWindow window : windowsToClose) {
            if (openWindows.contains(window)) {
                window.fireCloseRequest();
            }
        }
    }

    /**
     * Fires the window close request so the same confirmation and cleanup logic runs as when closing via the window button.
     */
    private void fireCloseRequest() {
        Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private static MainWindow getFocusedOrLastOpenWindow() {
        for (MainWindow window : openWindows) {
            if (window.stage.isFocused()) {
                return window;
            }
        }

        if (openWindows.isEmpty()) {
            return null;
        }

        return openWindows.get(openWindows.size() - 1);
    }

    /** The focused main window, else the most recently opened one; empty when no window is open. */
    public static Optional<MainWindow> getFocusedWindow() {
        return Optional.ofNullable(getFocusedOrLastOpenWindow());
    }

    /** The open window that currently hosts the terminal tab with {@code terminalViewId}. */
    public static Optional<MainWindow> findWindowOwning(String terminalViewId) {
        if (terminalViewId == null) {
            return Optional.empty();
        }
        for (MainWindow window : new ArrayList<>(openWindows)) {
            if (window.findTerminalTabByViewId(terminalViewId).isPresent()) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }

    /** Actions invokable from the macOS Dock icon menu (see {@link MacDockMenu}). */
    public enum DockAction { NEW_WINDOW, NEW_TAB, CONNECTION_MANAGER, OPEN_PROJECT, GUIDE, ABOUT, QUIT }

    /**
     * Runs a Dock-menu action against the focused (or last) window, marshalling
     * onto the JavaFX thread. Opens a window first if none is currently open.
     */
    public static void runDockAction(DockAction action) {
        Platform.runLater(() -> {
            // Quit must work even when no window is open (the packaged app keeps
            // running in the background for the JobScheduler); don't reopen a window
            // just to quit it.
            if (action == DockAction.QUIT) {
                requestApplicationQuit();
                return;
            }
            MainWindow window = getFocusedOrLastOpenWindow();
            if (window == null) {
                reopenOrCreateWindow();
                window = getFocusedOrLastOpenWindow();
                if (window == null || action == DockAction.NEW_WINDOW) {
                    return; // just opened a fresh window — that satisfies "New Window"
                }
            }
            switch (action) {
                case NEW_WINDOW -> window.openNewWindow();
                case NEW_TAB -> window.showQuickConnect();
                case CONNECTION_MANAGER -> window.showConnectionManager();
                case OPEN_PROJECT -> window.openProject();
                case GUIDE -> window.openGuide();
                case ABOUT -> window.showAbout();
            }
        });
    }

    private static void clearApplicationQuitState() {
        applicationQuitRequested = false;
        applicationQuitApprovedWindows.clear();
        applicationQuitConfirmationInProgress = false;
        if (!schedulerDrainInProgress) {
            schedulerDrainApproved = false;
        }
    }

    private void closeCurrentTab() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab != null) {
            closeTabsByUser(List.of(currentTab), CloseCause.CLOSE_TAB_COMMAND);
        }
    }

    /** Which command closed tabs through {@link #closeTabsByUser}. */
    private enum CloseCause {
        /** File → Close Tab, Cmd/Ctrl+W. */
        CLOSE_TAB_COMMAND,
        /** Close in a Dashboard connection's context menu. */
        DASHBOARD,
        /** Close Other Tabs, in the tab context menu or the File menu. */
        CLOSE_OTHERS,
        /** Close Tabs to the Right, in the tab context menu or the File menu. */
        CLOSE_TO_RIGHT,
        /** File → Close All Tabs. */
        CLOSE_ALL,
        /** A terminal tab's own close button; see {@link TerminalTab#setOnUserCloseApproved}. */
        CLOSE_BUTTON
    }

    /** {@link #closeTabsByUser(List, Tab, CloseCause)} for a command that keeps no tab selected. */
    private boolean closeTabsByUser(List<Tab> tabs, CloseCause cause) {
        return closeTabsByUser(tabs, null, cause);
    }

    /**
     * Closes tabs on the user's request from a command other than the tab's own close button.
     * Removing a tab from the list fires none of its close events, so this asks what the close
     * button would ask first (a busy terminal, a hosted snippet editor with unsaved changes; for
     * several tabs one question covers the terminals, see {@link #confirmUserCloseAll}); a single
     * Cancel keeps every tab open. Then it records, disposes and removes them in one go. Tabs that
     * close on their own (a session that ended), moves between windows, regrouping and window
     * teardown do not come here.
     *
     * @param keepSelected the tab the command acts around (Close Other Tabs, Close Tabs to the
     *     Right), or {@code null}: it is selected before the others go, so the selection does not
     *     wander through the closing tabs
     * @return {@code true} when the tabs were closed
     */
    private boolean closeTabsByUser(List<Tab> tabs, Tab keepSelected, CloseCause cause) {
        List<Tab> targets = new ArrayList<>();
        for (Tab tab : tabs) {
            // A stale Dashboard row may still name a tab that moved to another window.
            if (tab != null && tab.isClosable() && tabPane.getTabs().contains(tab) && !targets.contains(tab)) {
                targets.add(tab);
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        if (!confirmUserCloseAll(targets)) {
            return false;
        }
        // A session that ended while its question was open closed its tab itself and released it.
        targets.removeIf(tab -> !tabPane.getTabs().contains(tab));
        if (keepSelected != null && !targets.contains(keepSelected) && tabPane.getTabs().contains(keepSelected)) {
            tabPane.getSelectionModel().select(keepSelected);
        }
        recordUserClosedTabs(targets, cause);
        for (Tab tab : targets) {
            disposeTabContent(tab);
        }
        reorganizeTabs(() -> tabPane.getTabs().removeAll(targets));
        return true;
    }

    /**
     * What closing {@code targets} asks first; {@code true} when they may all close. A single tab
     * asks what its close button would ask. Several tabs ask one question for all the terminals
     * among them that would ask on their own, then each hosted editor with unsaved work, selected so
     * the user sees which one asks; the editors go last because their Save choice already saves.
     */
    private boolean confirmUserCloseAll(List<Tab> targets) {
        if (targets.size() == 1) {
            return confirmUserClose(targets.get(0));
        }
        return confirmBusyTerminalsClose(targets)
            && HostedCloseGuards.confirmTabs(targets, tab -> tabPane.getSelectionModel().select(tab));
    }

    /**
     * One question instead of one per terminal: how many tabs close, and in how many of them the
     * close button would have asked ({@link TerminalTab#needsCloseConfirmation()}: split panes or a
     * command still running). Idle terminals and other tabs alone ask nothing, and neither does
     * anything when the setting to close active terminals without confirmation is on.
     */
    private boolean confirmBusyTerminalsClose(List<Tab> targets) {
        int busyTerminals = 0;
        for (Tab tab : targets) {
            if (tab instanceof TerminalTab terminalTab && terminalTab.needsCloseConfirmation()) {
                busyTerminals++;
            }
        }
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        boolean closeActiveWithoutConfirmation = globalSettings != null
            && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation();
        if (!TabCloseTargets.needsSummaryConfirmation(busyTerminals, closeActiveWithoutConfirmation)) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(stage);
        alert.setTitle(I18n.get("dialog.closeTabs.title"));
        alert.setHeaderText(I18n.get("dialog.closeTabs.header", targets.size()));
        alert.setContentText(I18n.get("dialog.closeTabs.content", busyTerminals));
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Close Other Tabs: every closable tab of this window except {@code anchor}, which stays selected. */
    private void closeOtherTabs(Tab anchor) {
        closeTabsAround(anchor, TabCloseTargets.others(tabPane.getTabs(), anchor), CloseCause.CLOSE_OTHERS);
    }

    /** Close Tabs to the Right: the closable tabs after {@code anchor}, which stays selected. */
    private void closeTabsToTheRight(Tab anchor) {
        closeTabsAround(anchor, TabCloseTargets.toTheRight(tabPane.getTabs(), anchor), CloseCause.CLOSE_TO_RIGHT);
    }

    private void closeTabsAround(Tab anchor, List<Tab> targets, CloseCause cause) {
        if (closeTabsByUser(targets, anchor, cause)) {
            updateDashboard();
            // The group lists of the remaining tabs' menus no longer offer groups that just closed.
            updateAllTabContextMenus();
        }
    }

    /**
     * Disables Close Other Tabs and Close Tabs to the Right while they would close nothing around
     * {@code anchor}. Called when their menu opens: tabs open, close and move in the meantime.
     */
    private void syncTabCloseItems(Tab anchor, MenuItem closeOthers, MenuItem closeToRight) {
        closeOthers.setDisable(TabCloseTargets.others(tabPane.getTabs(), anchor).isEmpty());
        closeToRight.setDisable(TabCloseTargets.toTheRight(tabPane.getTabs(), anchor).isEmpty());
    }

    /** The question the tab's close button would ask; {@code true} when it may close. */
    private static boolean confirmUserClose(Tab tab) {
        if (tab instanceof TerminalTab terminalTab) {
            return terminalTab.confirmUserClose();
        }
        if (tab instanceof DialogHostTab hostTab) {
            // A hosted snippet editor/workspace asks about unsaved changes (Save / Discard / Cancel).
            return hostTab.confirmClose();
        }
        // A file editor with unsaved changes asks the same (Save / Discard / Cancel).
        return HostedCloseGuards.confirmTab(tab);
    }

    /**
     * Remembers the terminal tabs among {@code tabs} for Recently Closed, as one entry: called once
     * the user agreed to close them and before they release anything, so their group, name and effect
     * are still there. Other kinds of tab are not remembered.
     */
    private void recordUserClosedTabs(List<Tab> tabs, CloseCause cause) {
        recordClosedTabs(tabs, cause);
    }

    /** A terminal tab's close button, after the user agreed to any question; the tab may be in any window. */
    private static void recordClosedByButton(TerminalTab tab) {
        recordClosedTabs(List.of(tab), CloseCause.CLOSE_BUTTON);
    }

    private static void recordClosedTabs(List<? extends Tab> tabs, CloseCause cause) {
        try {
            List<ClosedTabHistory.ClosedTab> closed = captureClosedTabs(tabs);
            if (closed.isEmpty()) {
                return;
            }
            recentlyClosedRecorder.tabsClosed(closed);
            logger.debug("Remembered {} closed terminal tab(s) ({})", closed.size(), cause);
        } catch (RuntimeException e) {
            // Remembering is a convenience; it must never stop a close the user asked for.
            logger.warn("Could not remember the closed tabs", e);
        }
        syncRecentlyClosedMenusInAllWindows();
    }

    /**
     * What reopening each terminal tab among {@code tabs} needs, read while the tab still holds it: the
     * connection (sanitised in {@link ClosedTabHistory.ClosedTab#capture}), the tab group, the name the
     * user gave it and the terminal effect of its first pane.
     */
    private static List<ClosedTabHistory.ClosedTab> captureClosedTabs(List<? extends Tab> tabs) {
        List<ClosedTabHistory.ClosedTab> closed = new ArrayList<>();
        for (Tab tab : tabs) {
            if (!(tab instanceof TerminalTab terminalTab) || !tab.isClosable() || terminalTab.getConnection() == null) {
                continue;
            }
            TerminalView view = terminalTab.getTerminalView();
            String effectId = view != null ? view.getTerminalEffectPluginId() : null;
            closed.add(ClosedTabHistory.ClosedTab.capture(
                terminalTab.getConnection(),
                terminalTab.getTemporarySSHKey() != null,
                terminalTab.getGroup(),
                terminalTab.getCustomTitle(),
                effectId,
                effectId != null ? view.getTerminalEffectAnimationSpeed() : null));
        }
        return closed;
    }

    /**
     * The user closed this window: its terminal tabs become one Recently Closed entry, unless
     * {@code endsApplication}, which takes the history with it. Called before the tabs are released.
     */
    private void recordClosedWindow(boolean endsApplication) {
        try {
            recentlyClosedRecorder.windowClosed(captureClosedTabs(tabPane.getTabs()), endsApplication);
        } catch (RuntimeException e) {
            logger.warn("Could not remember the closed window's tabs", e);
        }
        syncRecentlyClosedMenusInAllWindows();
    }

    /**
     * File → Reopen Closed Tab, the tab menu's Reopen Closed Tab and Cmd+Opt+Shift+T / Ctrl+Alt+Shift+T:
     * brings back what the newest Recently Closed entry holds. With nothing to reopen the status line
     * says so (the key works with the menu bar hidden, where a greyed-out item cannot show it).
     */
    private void reopenClosedTab() {
        Optional<ClosedTabHistory.Entry> latest = closedTabHistory.latest();
        if (latest.isEmpty()) {
            updateStatus(I18n.get("status.noClosedTab"));
            syncRecentlyClosedMenus();
            return;
        }
        reopenClosedEntry(latest.get());
    }

    /**
     * Reopens the tabs of {@code entry}, each with a new session. The tabs of a closed window open in
     * a new window, or in this one while it has no tabs of its own (on macOS, the window you open after
     * closing the last one). Each tab signs in through {@link #connectSavedConnection}, which checks
     * the server policy first. A tab whose password, temporary key or vault question the user cancels
     * stays in the history; the others leave it.
     */
    private void reopenClosedEntry(ClosedTabHistory.Entry entry) {
        MainWindow asked = reopenWindow();
        if (asked != this) {
            // This window is closed: on macOS its menu bar outlives it once the last window closed.
            if (asked != null) {
                asked.reopenClosedEntry(entry);
            }
            return;
        }
        int position = closedTabHistory.take(entry);
        if (position < 0) {
            // A menu built before another window reopened it.
            syncRecentlyClosedMenusInAllWindows();
            return;
        }
        MainWindow target = this;
        boolean newWindow = entry.window() && hasClosableTabs();
        if (newWindow) {
            target = new MainWindow(new Stage());
            target.show();
        }
        List<ClosedTabHistory.ClosedTab> remaining = new ArrayList<>();
        for (ClosedTabHistory.ClosedTab closed : entry.tabs()) {
            if (target.reopenClosedTabHere(closed)) {
                remaining.add(closed);
            }
        }
        if (!remaining.isEmpty()) {
            closedTabHistory.putBack(position, entry.withTabs(remaining));
        }
        syncRecentlyClosedMenusInAllWindows();
        if (newWindow && !target.hasClosableTabs()) {
            // Every tab was cancelled or refused: do not leave an empty window behind.
            target.fireCloseRequest();
        }
    }

    /**
     * Opens one remembered tab in this window: the saved connection (or the remembered one, when it was
     * never saved), with the tab group, the name and the terminal effect it had. A Quick Connect session
     * that used a temporary SSH key asks for a new key; the remembered connection never holds one.
     *
     * @return whether the tab stays in the history (the user cancelled a sign-in question)
     */
    private boolean reopenClosedTabHere(ClosedTabHistory.ClosedTab closed) {
        ClosedTabHistory.Target target =
            ClosedTabHistory.resolveConnection(closed, id -> app.getConfigManager().getConnectionById(id));
        ServerConnection connection = target.connection();
        if (target.needsNewTemporaryKey()) {
            // The policy comes before any question, as everywhere else.
            Optional<String> blocked = de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(connection);
            if (blocked.isPresent()) {
                de.kortty.policy.PolicyUiSupport.showBlockedServerDialog(blocked.get());
                return false;
            }
            // Sets the new key on the copy, where the sign-in below finds it registered and valid.
            if (requestNewTemporarySSHKey(connection) == null) {
                return true;
            }
        }
        ConnectionAuthResolver.Status status =
            connectSavedConnection(connection, false, tab -> restoreClosedTabState(tab, closed));
        return ClosedTabHistory.keepsEntry(status);
    }

    /** Gives a reopened tab the group, name and terminal effect it had when it was closed. */
    private void restoreClosedTabState(TerminalTab tab, ClosedTabHistory.ClosedTab closed) {
        // The tab opened in its connection's group; the group it had may differ, or be none.
        String openedGroup = tab.getGroup();
        tab.setGroup(closed.tabGroup());
        tab.setCustomTitle(closed.customTitle());
        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            TerminalView view = tab.getTerminalView();
            if (closed.terminalEffectSpeed() != null) {
                view.setTerminalEffectAnimationSpeed(closed.terminalEffectSpeed());
            }
            view.setTerminalEffectPluginId(closed.terminalEffectPluginId());
        }
        if (!java.util.Objects.equals(openedGroup, tab.getGroup())) {
            // Also rebuilds every tab's context menu.
            organizeTabsByGroup();
            updateDashboard();
        }
    }

    private boolean hasClosableTabs() {
        return tabPane.getTabs().stream().anyMatch(Tab::isClosable);
    }

    /**
     * The window this window's Reopen Closed Tab and Recently Closed act in: this one while it is
     * open, else the focused or last open window, else a new one (see
     * {@link RecentlyClosedRecorder#reopenWindow}).
     */
    private MainWindow reopenWindow() {
        return RecentlyClosedRecorder.reopenWindow(this, openWindows::contains,
            MainWindow::getFocusedOrLastOpenWindow, () -> {
                reopenOrCreateWindow();
                return getFocusedOrLastOpenWindow();
            });
    }

    /** File → Recently Closed → Clear List: forgets every closed tab. */
    private void clearRecentlyClosed() {
        closedTabHistory.clear();
        syncRecentlyClosedMenusInAllWindows();
    }

    /** {@link #syncRecentlyClosedMenus()} for every window: the history is shared, the menus are not. */
    private static void syncRecentlyClosedMenusInAllWindows() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            window.syncRecentlyClosedMenus();
        }
        // Called whenever the history changed: the session snapshot keeps it across a restart.
        markSessionDirty();
    }

    /**
     * Greys out Reopen Closed Tab while there is nothing to reopen and rebuilds Recently Closed from the
     * history, newest first, in every menu bar of this window.
     */
    private void syncRecentlyClosedMenus() {
        boolean empty = closedTabHistory.isEmpty();
        for (MenuItem item : reopenClosedTabMenuItems) {
            item.setDisable(empty);
        }
        for (Menu menu : recentlyClosedMenus) {
            menu.getItems().setAll(recentlyClosedMenuItems());
        }
    }

    private List<MenuItem> recentlyClosedMenuItems() {
        List<MenuItem> items = new ArrayList<>();
        List<ClosedTabHistory.Entry> entries = closedTabHistory.entries();
        if (entries.isEmpty()) {
            MenuItem none = new MenuItem(I18n.get("menu.file.recentlyClosed.empty"));
            none.setDisable(true);
            items.add(none);
            return items;
        }
        for (ClosedTabHistory.Entry entry : entries) {
            MenuItem item = new MenuItem(recentlyClosedLabel(entry));
            // Tab and connection names are shown as they are: an underscore is no mnemonic.
            item.setMnemonicParsing(false);
            item.setOnAction(e -> reopenClosedEntry(entry));
            items.add(item);
        }
        items.add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem(I18n.get("menu.file.recentlyClosed.clear"));
        clear.setOnAction(e -> clearRecentlyClosed());
        items.add(clear);
        return items;
    }

    /** A tab's name; the names of the tabs one command closed together; or "Window:" and its tabs' names. */
    private static String recentlyClosedLabel(ClosedTabHistory.Entry entry) {
        String names = entry.names(RECENTLY_CLOSED_NAMES);
        return entry.window() ? I18n.get("menu.file.recentlyClosed.window", names) : names;
    }

    // ---- File › Open Recent -------------------------------------------------------------------

    /** {@link #syncOpenRecentMenus()} for every window: the lists are shared, the menus are not. */
    private static void syncOpenRecentMenusInAllWindows() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            window.syncOpenRecentMenus();
        }
    }

    /**
     * Rebuilds File › Open Recent in every menu bar of this window: the saved connections used last
     * and the recent projects as {@link #refreshRecentProjects} found them last. Reads no file.
     */
    private void syncOpenRecentMenus() {
        if (openRecentMenus.isEmpty()) {
            return;
        }
        List<ServerConnection> connections = List.of();
        if (app != null && app.getConfigManager() != null && app.getGlobalSettingsManager() != null) {
            connections = RecentConnections.top(app.getConfigManager().getConnections(),
                OpenRecentMenuSupport.CONNECTION_COUNT,
                app.getGlobalSettingsManager().getSettings().getOpenRecentClearedAt());
        }
        Path home = userHome();
        for (Menu menu : openRecentMenus) {
            menu.getItems().setAll(OpenRecentMenuSupport.items(connections, recentProjects, home,
                new OpenRecentMenuSupport.Commands() {
                    @Override
                    public void connect(ServerConnection connection) {
                        openRecentConnection(connection);
                    }

                    @Override
                    public void openProject(Path project) {
                        openProjectFile(project);
                    }

                    @Override
                    public void clear() {
                        clearOpenRecent();
                    }
                }));
        }
    }

    private static Path userHome() {
        String home = System.getProperty("user.home");
        try {
            return home == null || home.isBlank() ? null : Path.of(home).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /**
     * Looks up the recent projects off the FX thread ({@link RecentProjects#list}): the remembered
     * project files that still exist, then those of the project folder. When the result differs from
     * what the menus show, every window's File › Open Recent follows.
     */
    private void refreshRecentProjects() {
        if (app == null || app.getGlobalSettingsManager() == null || projectManager == null) {
            return;
        }
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        List<String> remembered = settings.getRecentProjectPaths();
        long clearedAt = settings.getOpenRecentClearedAt();
        long generation = recentProjectsGeneration;
        ProjectManager projects = projectManager;
        try {
            RECENT_PROJECTS_LOOKUP.execute(() -> {
                List<Path> folder;
                try {
                    folder = projects.listProjects();
                } catch (IOException | RuntimeException e) {
                    logger.debug("Could not list the project folder for Open Recent: {}", e.getMessage());
                    folder = List.of();
                }
                List<Path> found = RecentProjects.list(remembered, folder, clearedAt, RecentProjects.MAX_ENTRIES);
                Platform.runLater(() -> {
                    if (generation == recentProjectsGeneration && !found.equals(recentProjects)) {
                        recentProjects = found;
                        syncOpenRecentMenusInAllWindows();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("Open Recent lookup rejected: {}", e.getMessage());
        }
    }

    /**
     * Remembers {@code project} as the project opened or saved last for File › Open Recent, and saves
     * the settings in the background.
     */
    private void rememberRecentProject(Path project) {
        if (app == null || app.getGlobalSettingsManager() == null || project == null) {
            return;
        }
        try {
            de.kortty.core.GlobalSettingsManager manager = app.getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            settings.setRecentProjectPaths(RecentProjects.remember(settings.getRecentProjectPaths(), project));
            manager.scheduleSave();
        } catch (RuntimeException e) {
            // The project opened or was saved all the same; only the menu entry is missing.
            logger.warn("Could not remember the recent project {}: {}", project.getFileName(), e.getMessage());
        }
        recentProjectsGeneration++;
        refreshRecentProjects();
    }

    /**
     * File › Open Recent › a connection: opens a tab for the saved connection as it is now, signing in
     * like Connect in the Connection Manager (the server policy first), and counts it as a use. A
     * connection deleted since the menu was built is dropped from the menu instead.
     */
    private void openRecentConnection(ServerConnection connection) {
        ServerConnection current = connection.getId() != null
            ? app.getConfigManager().getConnectionById(connection.getId())
            : null;
        if (current == null) {
            syncOpenRecentMenus();
            return;
        }
        connectSavedConnection(current, true, tab -> { });
    }

    /**
     * File › Open Recent › Clear List: forgets the remembered projects and hides the connections used
     * and the project-folder files changed until now. Saved connections and project files stay.
     */
    private void clearOpenRecent() {
        if (app == null || app.getGlobalSettingsManager() == null) {
            return;
        }
        de.kortty.core.GlobalSettingsManager manager = app.getGlobalSettingsManager();
        GlobalSettings settings = manager.getSettings();
        settings.setRecentProjectPaths(List.of());
        settings.setOpenRecentClearedAt(System.currentTimeMillis());
        manager.scheduleSave();
        recentProjectsGeneration++;
        recentProjects = List.of();
        syncOpenRecentMenusInAllWindows();
    }

    /**
     * Releases a tab's native and timer resources on the programmatic close paths (Cmd+W,
     * close-all, dashboard, opening a project, closing the window), where JavaFX fires neither
     * onCloseRequest nor onClosed — without it, Monaco/WebView engines and terminal buffers
     * survive the tab, and an AI chat request or a swarm run goes on working for a tab that is
     * gone. All of these methods are idempotent, so a user-initiated close that already ran the
     * tab's own close handlers is unaffected.
     */
    private void disposeTabContent(Tab tab) {
        if (tab instanceof TerminalTab terminalTab) {
            // What its close button releases, the auto-reconnect and status-bar timers included.
            terminalTab.releaseResources();
        } else if (tab instanceof FileEditorTab editorTab) {
            editorTab.dispose();
        } else if (tab instanceof AiResultTab aiResultTab) {
            // What its close button stops first: a running request or open-terminal broadcast.
            aiResultTab.cancelForClose();
            unregisterSavedChatTab(aiResultTab.getSavedChatId());
            aiResultTab.disposeRenderedContent();
        } else if (tab instanceof SwarmAgentTab swarmTab) {
            // Likewise a running swarm, whose agents would go on running commands on the servers.
            swarmTab.cancelForClose();
            swarmTab.handleTabClosed();
        } else if (tab instanceof DialogHostTab hostTab) {
            // Runs the hosted dialog's DIALOG_HIDDEN cleanup (Monaco/WebView disposal, listener
            // deregistration) that a user-initiated tab close would have triggered.
            hostTab.disposeOnWindowClose();
        } else if (tab instanceof SFTPManagerTab sftpTab) {
            // What its close request does: without it the session and the auto-close timer outlive
            // the tab, e.g. every SFTP tab of a project replaced by opening another one.
            sftpTab.cleanup();
        }
        // A restored remote editor or image tab closes the SFTP session it opened for itself.
        SftpSessionRestoreSupport.closeOwnedSession(tab);
    }
    
    /**
     * Asks the user for confirmation and then closes all tabs (without further prompts).
     */
    private void confirmAndCloseAllTabs() {
        long closableCount = tabPane.getTabs().stream().filter(Tab::isClosable).count();
        if (closableCount == 0) return;
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        boolean skipConfirmation = globalSettings != null
            && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation();
        if (skipConfirmation) {
            closeAllTabsGuarded();
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("dialog.closeAllTabs.title"));
        alert.setHeaderText(I18n.get("dialog.closeAllTabs.header"));
        alert.setContentText(I18n.get("dialog.closeAllTabs.content"));
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        closeAllTabsGuarded();
    }

    /**
     * Closes all closable tabs once every hosted snippet editor/workspace and file editor agreed
     * (unsaved changes: Save / Discard / Cancel). A single Cancel keeps every tab open.
     *
     * @return {@code true} when the tabs were closed
     */
    private boolean closeAllTabsGuarded() {
        if (!confirmHostedTabsClose()) {
            return false;
        }
        // The user's Close All Tabs, unlike the other callers of closeAllTabs: one Recently Closed entry.
        recordUserClosedTabs(new ArrayList<>(tabPane.getTabs()), CloseCause.CLOSE_ALL);
        closeAllTabs();
        return true;
    }

    /**
     * Asks every hosted snippet editor/workspace tab and every file editor tab with unsaved changes
     * of this window; see {@link HostedCloseGuards}.
     */
    private boolean confirmHostedTabsClose() {
        return HostedCloseGuards.confirmTabs(tabPane.getTabs(), tab -> tabPane.getSelectionModel().select(tab));
    }

    /**
     * The snippet part of closing this window, asked last so it follows every other prompt: hosted
     * tabs, the windowed snippet workspace, then standalone editors this window owns — plus, when
     * the application ends with this window, the editors no window owns.
     */
    private boolean confirmSnippetEditorsClose(boolean includeUnownedEditors) {
        if (!confirmHostedTabsClose()) {
            return false;
        }
        SnippetWorkspaceDialog workspace = snippetWorkspace;
        if (workspace != null && workspace.needsCloseConfirmation()) {
            bringDialogToFront(workspace);
            if (!workspace.confirmHostedClose()) {
                return false;
            }
        }
        List<SnippetEditorRegistry.OpenEditor> editors = includeUnownedEditors
            ? HostedCloseGuards.standaloneEditorsOutside(List.of())
            : HostedCloseGuards.standaloneEditorsOwnedBy(stage);
        return HostedCloseGuards.confirmEditors(editors);
    }

    /** After an approved window close: closes the snippet windows that belong to this window. */
    private void closeSnippetEditorsWithoutPrompt() {
        SnippetWorkspaceDialog workspace = snippetWorkspace;
        if (workspace != null) {
            workspace.closeWithoutPrompt();
        }
        for (SnippetEditorRegistry.OpenEditor editor : HostedCloseGuards.standaloneEditorsOwnedBy(stage)) {
            editor.closeWithoutPrompt();
        }
    }

    private void closeAllTabs() {
        List<Tab> tabsToClose = new ArrayList<>(tabPane.getTabs());
        for (Tab tab : tabsToClose) {
            if (!tab.isClosable()) continue;
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.setOnCloseRequest(null);
            }
            disposeTabContent(tab);
            tabPane.getTabs().remove(tab);
        }
    }
    
    /**
     * Asks everything closing this window needs to ask, and closes nothing: the caller disposes the
     * window only when this returns {@code true}, i.e. after every prompt passed. The snippet
     * editors are asked last, since their prompt may save.
     */
    private boolean confirmClose() {
        boolean closesApplication = willCloseApplication();
        if (closesApplication && maybeHandleSchedulerDrainBeforeExit(this, this::fireCloseRequest)) {
            return false;
        }
        if (closesApplication && !confirmQuitWhileTranslatingGuide()) {
            return false;
        }
        if (!confirmActiveConnectionsClose()) {
            return false;
        }
        return confirmSnippetEditorsClose(closesApplication && !applicationQuitConfirmationInProgress);
    }

    private boolean confirmActiveConnectionsClose() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        if (globalSettings != null && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation()) {
            return true;
        }

        // Count only active (connected) sessions
        long activeConnections = tabPane.getTabs().stream()
                .filter(t -> t instanceof TerminalTab)
                .map(t -> (TerminalTab) t)
                .filter(TerminalTab::isConnected)
                .count();
        
        // No confirmation needed if no active connections
        if (activeConnections == 0) {
            return true;
        }
        
        // Show confirmation for active connections
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("dialog.closeWindow"));
        alert.setHeaderText(I18n.get("dialog.activeConnections"));
        alert.setContentText(I18n.get("dialog.activeConnectionsMessage", activeConnections));
        
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Asks before quitting while the guide is being translated.
     *
     * <p>Worth interrupting for: the run is hours long and invisible once the window is gone, so
     * quitting silently would look like the work simply vanished. It has not — the translation
     * memory is checkpointed, so the next start continues from here, and the dialog says so
     * rather than presenting the choice as losing progress.
     *
     * @return true to proceed with quitting
     */
    private boolean confirmQuitWhileTranslatingGuide() {
        de.kortty.core.GuideTranslationJob job = de.kortty.core.GuideTranslationJob.getInstance();
        if (!job.isRunning()) {
            return true;
        }
        de.kortty.core.GuideTranslationJob.Snapshot snapshot = job.snapshot();
        String language = snapshot.language() != null
            ? java.util.Locale.forLanguageTag(snapshot.language()).getDisplayLanguage() : "";
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("guide.translation.quit.title"));
        alert.setHeaderText(I18n.get("guide.translation.quit.header", language, snapshot.percent()));
        alert.setContentText(I18n.get("guide.translation.quit.message"));
        alert.getButtonTypes().setAll(
            new ButtonType(I18n.get("guide.translation.quit.pauseAndQuit"), ButtonBar.ButtonData.OK_DONE),
            new ButtonType(I18n.get("guide.translation.quit.keepRunning"), ButtonBar.ButtonData.CANCEL_CLOSE));
        ButtonType choice = alert.showAndWait().orElse(null);
        boolean quit = choice != null && choice.getButtonData() == ButtonBar.ButtonData.OK_DONE;
        if (quit) {
            // Stop at the next batch boundary so the checkpoint on disk is consistent.
            job.cancel();
        }
        return quit;
    }

    /** Shown at most once per run of the application, not once per window. */
    private static boolean guideTranslationUpdatePrompted;
    /** The "your snippets file was moved aside" notice is shown once per run, not once per window. */
    private static boolean snippetLoadFailureNoticeShown;
    /** This window's snippet workspace while it is open as a window (window mode). */
    private SnippetWorkspaceDialog snippetWorkspace;
    /** Last selected terminal/file-editor tab still in this window: the snippet insert target. */
    private TerminalTab lastSelectedTerminalTab;
    private FileEditorTab lastSelectedFileEditorTab;

    /**
     * After a release that changed the guide, offers to refresh a locally translated one.
     *
     * <p>Cheap to accept and cheaper the less the guide moved: the translation memory is keyed by
     * the source text, so every sentence that survived the release is reused and only genuinely
     * new or edited text reaches the model. Deferred a few seconds so it never competes with
     * startup, and asked once per application run rather than once per window.
     */
    private void scheduleOutdatedGuideTranslationCheck() {
        if (guideTranslationUpdatePrompted) {
            return;
        }
        javafx.animation.PauseTransition delay =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(6));
        delay.setOnFinished(event -> {
            // Foreground is checked at fire time, not schedule time: show() runs before the stage
            // reliably reports focus, and after the delay the state is meaningful. With several
            // windows only the focused one prompts, and the static flag keeps it to once per run.
            if (!shouldPromptGuideTranslationUpdate(guideTranslationUpdatePrompted,
                isForegroundWindow(), de.kortty.core.GuideTranslationJob.getInstance().isRunning())) {
                return;
            }
            java.util.List<String> outdated = de.kortty.core.GuideTranslationJob.outdatedLanguages(
                KorTTYApplication.getConfigDirectory(), KorTTYApplication.getAppVersion());
            if (outdated.isEmpty()) {
                return;
            }
            guideTranslationUpdatePrompted = true;
            promptGuideTranslationUpdate(outdated.getFirst());
        });
        delay.play();
    }

    /** Fire-time guard for the outdated-guide prompt; static so the policy is unit-testable. */
    static boolean shouldPromptGuideTranslationUpdate(
            boolean alreadyPrompted, boolean foregroundWindow, boolean translationRunning) {
        return !alreadyPrompted && foregroundWindow && !translationRunning;
    }

    private void promptGuideTranslationUpdate(String lang) {
        String language = java.util.Locale.forLanguageTag(lang).getDisplayLanguage();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(stage);
        alert.setTitle(I18n.get("guide.translation.outdated.title"));
        alert.setHeaderText(I18n.get("guide.translation.outdated.header"));
        alert.setContentText(I18n.get("guide.translation.outdated.message", language));
        alert.getButtonTypes().setAll(
            new ButtonType(I18n.get("guide.translation.outdated.update"), ButtonBar.ButtonData.OK_DONE),
            new ButtonType(I18n.get("guide.translation.outdated.later"), ButtonBar.ButtonData.CANCEL_CLOSE));
        ButtonType choice = alert.showAndWait().orElse(null);
        if (choice == null || choice.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            return;
        }
        // The settings dialog owns the provider and profile choice, so send the user there rather
        // than guessing which service should spend the next few hours.
        showSettings();
    }

    private boolean willCloseApplication() {
        return applicationQuitRequested || (openWindows.size() <= 1 && !app.shouldKeepRunningAfterLastWindowClosed());
    }

    private static boolean maybeHandleSchedulerDrainBeforeExit(MainWindow promptWindow, Runnable afterDrain) {
        if (schedulerDrainApproved || schedulerDrainInProgress) {
            return schedulerDrainInProgress;
        }
        KorTTYApplication application = promptWindow != null ? promptWindow.app : KorTTYApplication.getInstance();
        if (application == null || application.getJobSchedulerService() == null) {
            return false;
        }
        List<ActiveJobSummary> activeJobs = application.getJobSchedulerService().getActiveJobSummaries();
        if (activeJobs.isEmpty()) {
            return false;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        if (promptWindow != null) {
            alert.initOwner(promptWindow.stage);
        }
        alert.setTitle(I18n.get("jobscheduler.quit.title"));
        alert.setHeaderText(I18n.get("jobscheduler.quit.header", activeJobs.size()));
        alert.setContentText(I18n.get("jobscheduler.quit.content", formatActiveJobNames(activeJobs)));
        ButtonType waitAndQuit = new ButtonType(I18n.get("jobscheduler.quit.wait"), ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(waitAndQuit, ButtonType.CANCEL);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != waitAndQuit) {
            schedulerDrainApproved = false;
            schedulerDrainInProgress = false;
            return true;
        }

        schedulerDrainInProgress = true;
        application.getJobSchedulerService().beginDrainForShutdown();
        showSchedulerDrainDialog(promptWindow, application, afterDrain);
        return true;
    }

    private static void showSchedulerDrainDialog(
        MainWindow promptWindow,
        KorTTYApplication application,
        Runnable afterDrain) {

        Dialog<Void> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        if (promptWindow != null) {
            dialog.initOwner(promptWindow.stage);
        }
        dialog.setTitle(I18n.get("jobscheduler.quit.waiting.title"));
        dialog.setHeaderText(I18n.get("jobscheduler.quit.waiting.header"));
        VBox content = new VBox(10,
            new ProgressIndicator(),
            new Label(I18n.get("jobscheduler.quit.waiting.content")));
        content.setPadding(new Insets(20));
        dialog.getDialogPane().setContent(content);
        // A "Force quit now" button so the drain wait is always escapable — awaitDrain()
        // has no timeout, and a wedged job must never make the app unquittable (the user
        // would otherwise have to kill the process). Closing the dialog via this button
        // exits immediately regardless of drain state.
        ButtonType forceQuit = new ButtonType(I18n.get("jobscheduler.quit.force"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(forceQuit);
        dialog.setOnCloseRequest(e -> {
            logger.info("JobScheduler drain force-quit requested by user");
            schedulerDrainInProgress = false;
            application.shutdownAndExit();
        });
        dialog.show();

        Thread waiter = new Thread(() -> {
            boolean drainCompleted = false;
            try {
                application.getJobSchedulerService().awaitDrain();
                drainCompleted = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean completed = drainCompleted;
            Platform.runLater(() -> {
                schedulerDrainApproved = completed;
                schedulerDrainInProgress = false;
                dialog.setOnCloseRequest(null);
                dialog.close();
                if (completed) {
                    afterDrain.run();
                }
            });
        }, "JobScheduler-Shutdown-Drain-Waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static String formatActiveJobNames(List<ActiveJobSummary> activeJobs) {
        return activeJobs.stream()
            .map(ActiveJobSummary::jobName)
            .filter(name -> name != null && !name.isBlank())
            .limit(8)
            .reduce((left, right) -> left + "\n- " + right)
            .map(text -> "- " + text)
            .orElse("");
    }
    
    /**
     * Ctrl+Tab and Ctrl+Shift+Tab: the next or previous tab of the tab bar or, with "Ctrl+Tab
     * switches tabs in the order they were last used" on (Window settings), one step of a cycle
     * through the tabs in that order. The cycle ends, and only the tab it stopped at counts as used,
     * when Ctrl is released, another key is pressed, a tab is chosen some other way or the window
     * loses the focus.
     */
    private void switchTabFromKeyboard(boolean backwards) {
        if (!isTabSwitchMostRecentFirst()) {
            if (backwards) {
                selectPreviousTab();
            } else {
                selectNextTab();
            }
            return;
        }
        Tab next = tabMru.advance(tabPane.getTabs(), tabPane.getSelectionModel().getSelectedItem(), backwards);
        if (next == null) {
            return;
        }
        steppingTabCycle = true;
        try {
            tabPane.getSelectionModel().select(next);
        } finally {
            steppingTabCycle = false;
        }
    }

    /** The palette's Next Tab and Previous Tab: one step like Ctrl+Tab, counted at once, as no Ctrl key is held. */
    private void switchTabOnce(boolean backwards) {
        switchTabFromKeyboard(backwards);
        commitTabCycle();
    }

    /** Ends a running Ctrl+Tab cycle; the tab it stopped at becomes the most recently used one. */
    private void commitTabCycle() {
        if (tabMru.isCycling()) {
            tabMru.commit(tabPane.getSelectionModel().getSelectedItem());
        }
    }

    /** Whether Ctrl+Tab follows the most-recently-used order (Window settings); off when unknown. */
    private boolean isTabSwitchMostRecentFirst() {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        return settings != null && settings.isTabSwitchMostRecentFirst();
    }

    private void selectNextTab() {
        if (tabPane.getTabs().isEmpty()) {
            return;
        }
        int current = tabPane.getSelectionModel().getSelectedIndex();
        int next = (current + 1) % tabPane.getTabs().size();
        tabPane.getSelectionModel().select(next);
    }
    
    private void selectPreviousTab() {
        if (tabPane.getTabs().isEmpty()) {
            return;
        }
        int current = tabPane.getSelectionModel().getSelectedIndex();
        int prev = current - 1;
        if (prev < 0) prev = tabPane.getTabs().size() - 1;
        tabPane.getSelectionModel().select(prev);
    }

    /** Cmd/Ctrl+1..8 select the tab at that position, Cmd/Ctrl+9 the last tab; a slot with no tab does nothing. */
    private void selectTabBySlot(int slot) {
        int index = TabKeyboardShortcuts.indexForSlot(slot, tabPane.getTabs().size());
        if (index != TabKeyboardShortcuts.NO_TAB) {
            tabPane.getSelectionModel().select(index);
        }
    }

    private void copyFromTerminal() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.copySelection();
        }
    }

    private void cutFromCurrentContext() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab) {
            return;
        } else if (currentTab instanceof FileEditorTab editorTab) {
            editorTab.cut();
            return;
        }

        Scene scene = stage.getScene();
        if (scene == null) {
            return;
        }

        Node focusOwner = scene.getFocusOwner();
        if (focusOwner instanceof TextInputControl textInputControl) {
            textInputControl.cut();
            return;
        }
        if (focusOwner != null) {
            if (invokeCutMethodIfPresent(focusOwner)) {
                return;
            }
            focusOwner.fireEvent(new KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                KeyCode.X,
                false,
                !isMacOs(),
                false,
                isMacOs()
            ));
        }
    }

    private void updateEditMenuItemsForSelection() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        boolean disableCut = currentTab instanceof TerminalTab;
        if (cutMenuItem != null) {
            cutMenuItem.setDisable(disableCut);
        }
        if (systemCutMenuItem != null) {
            systemCutMenuItem.setDisable(disableCut);
        }
        // Quick select reads a terminal screen; disabled elsewhere, so an editor tab keeps the chord.
        boolean disableQuickSelect = !(currentTab instanceof TerminalTab);
        if (quickSelectMenuItem != null) {
            quickSelectMenuItem.setDisable(disableQuickSelect);
        }
        if (systemQuickSelectMenuItem != null) {
            systemQuickSelectMenuItem.setDisable(disableQuickSelect);
        }
        // Prompts and command output live in a terminal's scrollback; elsewhere the keys stay with the tab.
        for (MenuItem item : shellIntegrationMenuItems) {
            item.setDisable(!(currentTab instanceof TerminalTab));
        }
    }

    private boolean invokeCutMethodIfPresent(Node focusOwner) {
        try {
            var cutMethod = focusOwner.getClass().getMethod("cut");
            if (cutMethod.getParameterCount() == 0) {
                cutMethod.invoke(focusOwner);
                return true;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to firing the standard shortcut event below.
        }
        return false;
    }
    
    private void pasteToTerminal() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (!(currentTab instanceof TerminalTab terminalTab)) {
            return;
        }
        Scene mainScene = tabPane.getScene();
        if (mainScene == null) {
            return;
        }
        Window hostWindow = mainScene.getWindow();
        if (hostWindow == null) {
            return;
        }
        if (!hostWindow.isFocused()) {
            return;
        }
        Node focusOwner = mainScene.getFocusOwner();
        Node terminalRoot = terminalTab.getContent();
        if (terminalRoot != null && focusOwner != null
                && !isNodeUnderRoot(focusOwner, terminalRoot)) {
            return;
        }
        if (wasTriggeredByTerminalPasteShortcut()) {
            return;
        }
        terminalTab.paste();
    }

    private static boolean isNodeUnderRoot(Node node, Node root) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == root) {
                return true;
            }
        }
        return false;
    }

    private boolean wasTriggeredByTerminalPasteShortcut() {
        long shortcutAt = lastTerminalPasteShortcutAtNanos;
        lastTerminalPasteShortcutAtNanos = -1L;
        return shortcutAt > 0 && (System.nanoTime() - shortcutAt) < 1_000_000_000L;
    }
    
    private void findInCurrentTab() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.showFind();
        } else if (currentTab instanceof FileEditorTab editorTab) {
            editorTab.showFind();
        }
    }

    /**
     * Edit &gt; Previous Prompt / Next Prompt: scrolls the focused terminal pane to a prompt its shell
     * marked. When it cannot, the status line says why: no marks (no shell integration in that shell),
     * a full-screen program, or shell integration switched off.
     */
    private void jumpToPromptInCurrentTab(PromptNavigator.Direction direction) {
        if (!(tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab)) {
            return;
        }
        String status = switch (terminalTab.jumpToPrompt(direction)) {
            case JUMPED, NO_TARGET -> null;
            case NO_PROMPTS -> I18n.get("terminal.shellIntegration.status.noPrompts");
            case FULL_SCREEN -> I18n.get("terminal.shellIntegration.status.fullScreen");
            case DISABLED -> I18n.get("terminal.shellIntegration.status.disabled");
        };
        if (status != null) {
            updateStatus(status);
        }
    }

    /**
     * Edit &gt; Select Last Output / Copy Last Output: selects or copies what the newest finished
     * command in the focused terminal pane printed. The status line says what happened, including
     * why nothing could be selected.
     */
    private void lastOutputInCurrentTab(ShellIntegrationController.LastOutputAction action) {
        if (tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab) {
            updateStatus(I18n.get(terminalTab.lastOutput(action).statusKey()));
        }
    }

    /** Shows {@code message} in this window's status bar, for a terminal tab's own menu entries. */
    void showStatusMessage(String message) {
        updateStatus(message);
    }

    /** Edit &gt; Quick Select: labels what the focused terminal pane shows; nothing in other tabs. */
    private void quickSelectInCurrentTab() {
        if (tabPane.getSelectionModel().getSelectedItem() instanceof TerminalTab terminalTab) {
            terminalTab.startQuickSelect();
        }
    }
    
    /**
     * Toggles the timestamp gutter in the currently active terminal tab.
     * Updates the CheckMenuItem state to reflect the current visibility.
     */
    private void toggleTimestampsInCurrentTab(CheckMenuItem menuItem) {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            boolean nowVisible = terminalTab.toggleTimestampGutters();
            syncTimestampMenuItems(nowVisible);
        } else if (menuItem != null) {
            syncTimestampMenuItems(menuItem.isSelected());
        }
    }

    private void applyMenuBarVisibility(boolean visible) {
        if (menuBar == null) {
            return;
        }
        menuBar.setVisible(visible);
        menuBar.setManaged(visible);
        syncMenuBarToggleMenuItems(visible);
    }

    /** Session-only toggle: the hidden state is intentionally never persisted. */
    private void toggleMenuBarVisibility(boolean visible) {
        applyMenuBarVisibility(visible);

        if (visible) {
            updateStatus(I18n.get("menu.view.menuBar.shown"));
        } else {
            updateStatus(I18n.get("menu.view.menuBar.hiddenHint", menuBarToggleShortcutLabel()));
        }
    }

    /** The menu bar toggle's chord for the status hint: Cmd/Ctrl+Shift+L, or the one the user chose. */
    private String menuBarToggleShortcutLabel() {
        KeyCombination chord = menuBarToggleChord.chord();
        return chord == null || chord.equals(MENU_BAR_TOGGLE_ACCELERATOR)
            ? MENU_BAR_TOGGLE_SHORTCUT_LABEL : chord.getDisplayText();
    }
    
    private void zoomTerminal(int delta) {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.zoom(delta);
        }
    }
    
    private void resetTerminalZoom() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.resetZoom();
        }
    }
    
    private static final int FILE_BROWSER_DEFAULT_WIDTH = 220;
    private static final int FILE_BROWSER_MIN_WIDTH = 160;
    private static final int FILE_BROWSER_MAX_WIDTH = 420;

    private void syncDashboardMenuItems(boolean visible) {
        if (showDashboardMenuItem != null && showDashboardMenuItem.isSelected() != visible) {
            showDashboardMenuItem.setSelected(visible);
        }
        if (systemShowDashboardMenuItem != null && systemShowDashboardMenuItem.isSelected() != visible) {
            systemShowDashboardMenuItem.setSelected(visible);
        }
    }

    private void toggleFileBrowser(LocalFileBrowserManager.Position position) {
        ensureFileBrowserManager();
        fileBrowserManager.toggle(position);
        LocalFileBrowserManager.Position current = fileBrowserManager.getPosition();
        if (current != LocalFileBrowserManager.Position.HIDDEN) {
            Telemetry.track(TelemetryEvents.FILE_BROWSER_TOGGLED,
                Map.of("position", current.name().toLowerCase(Locale.ROOT)));
        }
        persistFileBrowserState();
    }

    private void onFileBrowserPositionChanged(LocalFileBrowserManager.Position position) {
        // Lazy-create the file browser and divider when first shown
        if (position != LocalFileBrowserManager.Position.HIDDEN && localFileBrowser == null) {
            localFileBrowser = new LocalFileBrowser(this);
            localFileBrowser.setMinWidth(FILE_BROWSER_MIN_WIDTH);
            localFileBrowser.setPrefWidth(FILE_BROWSER_DEFAULT_WIDTH);
            localFileBrowser.setMaxWidth(FILE_BROWSER_MAX_WIDTH);

            fileBrowserDivider = new ResizableDivider(Orientation.VERTICAL);
            fileBrowserDivider.setResizeListener(delta -> {
                double currentWidth = localFileBrowser.getPrefWidth();
                double directionalDelta =
                    fileBrowserManager.getPosition() == LocalFileBrowserManager.Position.RIGHT ? -delta : delta;
                double newWidth = currentWidth + directionalDelta;
                newWidth = Math.max(FILE_BROWSER_MIN_WIDTH, Math.min(FILE_BROWSER_MAX_WIDTH, newWidth));
                localFileBrowser.setPrefWidth(newWidth);
                fileBrowserManager.setPreferredWidth(newWidth);
                return newWidth;
            });
        }

        // Remove from current position if visible
        if (localFileBrowser != null && mainContentBox.getChildren().contains(localFileBrowser)) {
            mainContentBox.getChildren().remove(localFileBrowser);
        }
        if (fileBrowserDivider != null && mainContentBox.getChildren().contains(fileBrowserDivider)) {
            mainContentBox.getChildren().remove(fileBrowserDivider);
        }

        if (position == LocalFileBrowserManager.Position.HIDDEN) {
            // Hidden
            syncFileBrowserMenuItems(LocalFileBrowserManager.Position.HIDDEN);
        } else if (position == LocalFileBrowserManager.Position.LEFT) {
            // Restore saved width
            restoreFileBrowserWidth();
            // Insert browser first, then divider so the sidebar is flush with the window edge.
            int insertIndex = 0;
            mainContentBox.getChildren().add(insertIndex, localFileBrowser);
            mainContentBox.getChildren().add(insertIndex + 1, fileBrowserDivider);
            syncFileBrowserMenuItems(LocalFileBrowserManager.Position.LEFT);
            applyMainWindowThemeFromGlobalSettings();
        } else if (position == LocalFileBrowserManager.Position.RIGHT) {
            // Restore saved width
            restoreFileBrowserWidth();
            // Insert divider first, then browser so the sidebar is flush with the window edge.
            mainContentBox.getChildren().add(fileBrowserDivider);
            mainContentBox.getChildren().add(localFileBrowser);
            syncFileBrowserMenuItems(LocalFileBrowserManager.Position.RIGHT);
            applyMainWindowThemeFromGlobalSettings();
        }
    }

    private void restoreFileBrowserWidth() {
        double clampedWidth = Math.max(
            FILE_BROWSER_MIN_WIDTH,
            Math.min(FILE_BROWSER_MAX_WIDTH, fileBrowserManager.getPreferredWidth()));
        localFileBrowser.setPrefWidth(clampedWidth);
        fileBrowserManager.setPreferredWidth(clampedWidth);
    }

    private void applyPersistedFileBrowser() {
        try {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            if (settings == null) {
                return;
            }
            ensureFileBrowserManager();
            fileBrowserManager.setPreferredWidth(settings.getFileBrowserWidth());
            LocalFileBrowserManager.Position position =
                parseFileBrowserPosition(settings.getFileBrowserPosition());
            if (position != LocalFileBrowserManager.Position.HIDDEN) {
                fileBrowserManager.show(position); // fires onFileBrowserPositionChanged → docks
            } else {
                syncFileBrowserMenuItems(LocalFileBrowserManager.Position.HIDDEN);
            }
        } catch (Exception e) {
            logger.debug("Could not apply persisted file browser state: {}", e.getMessage());
        }
    }

    private static LocalFileBrowserManager.Position parseFileBrowserPosition(String value) {
        if (value != null) {
            try {
                return LocalFileBrowserManager.Position.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // fall back to hidden
            }
        }
        return LocalFileBrowserManager.Position.HIDDEN;
    }

    private void persistFileBrowserState() {
        if (fileBrowserManager == null) {
            return;
        }
        try {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            settings.setFileBrowserPosition(fileBrowserManager.getPosition().name());
            settings.setFileBrowserWidth(fileBrowserManager.getPreferredWidth());
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not persist file browser state: {}", e.getMessage());
        }
    }

    private void syncFileBrowserMenuItems(LocalFileBrowserManager.Position position) {
        if (showFileBrowserLeftMenuItem != null) {
            showFileBrowserLeftMenuItem.setSelected(position == LocalFileBrowserManager.Position.LEFT);
        }
        if (showFileBrowserRightMenuItem != null) {
            showFileBrowserRightMenuItem.setSelected(position == LocalFileBrowserManager.Position.RIGHT);
        }
        if (systemShowFileBrowserLeftMenuItem != null) {
            systemShowFileBrowserLeftMenuItem.setSelected(position == LocalFileBrowserManager.Position.LEFT);
        }
        if (systemShowFileBrowserRightMenuItem != null) {
            systemShowFileBrowserRightMenuItem.setSelected(position == LocalFileBrowserManager.Position.RIGHT);
        }
    }

    // ---------------------------------------------------------------- AI-agent panel docking

    private void ensureAiAgentDockManager() {
        if (aiAgentDockManager == null) {
            // Per-window (not a singleton): each window docks independently and is GC'd with its manager.
            aiAgentDockManager = new AiAgentPanelDockManager();
            aiAgentPlacementListener = placement -> onAiAgentPlacementChanged(placement);
            aiAgentDockManager.addPlacementListener(aiAgentPlacementListener);
        }
    }

    private void setAiAgentPlacement(AiAgentPanelDockManager.Placement placement) {
        ensureAiAgentDockManager();
        aiAgentDockManager.setPlacement(placement);
    }

    private TerminalTab activeTerminalTab() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return selected instanceof TerminalTab terminalTab ? terminalTab : null;
    }

    private java.util.List<TerminalTab> terminalTabs() {
        java.util.List<TerminalTab> tabs = new java.util.ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                tabs.add(terminalTab);
            }
        }
        return tabs;
    }

    /** Open terminal tabs in this window (used by the AI swarm target collector). */
    public java.util.List<TerminalTab> getOpenTerminalTabs() {
        return terminalTabs();
    }

    private void onAiAgentPlacementChanged(AiAgentPanelDockManager.Placement placement) {
        ensureAiAgentDockManager();
        // Lazily create the side panel + resizable divider on first dock.
        if (placement != AiAgentPanelDockManager.Placement.BOTTOM && aiAgentSidePanel == null) {
            aiAgentSidePanel = new AiAgentSidePanel();
            aiAgentSidePanel.setMinWidth(AiAgentPanelDockManager.MIN_WIDTH);
            aiAgentSidePanel.setPrefWidth(aiAgentDockManager.getPreferredWidth());
            aiAgentSidePanel.setMaxWidth(AiAgentPanelDockManager.MAX_WIDTH);
            aiAgentSideDivider = new ResizableDivider(Orientation.VERTICAL);
            aiAgentSideDivider.setResizeListener(delta -> {
                double current = aiAgentSidePanel.getPrefWidth();
                double directional =
                    aiAgentDockManager.getPlacement() == AiAgentPanelDockManager.Placement.RIGHT ? -delta : delta;
                double newWidth = AiAgentPanelDockManager.clampWidth(current + directional);
                aiAgentSidePanel.setPrefWidth(newWidth);
                aiAgentDockManager.setPreferredWidth(newWidth);
                persistAiAgentDockSettings();
                return newWidth;
            });
        }
        // Remove the side panel + divider from the layout if currently present.
        if (aiAgentSidePanel != null) {
            mainContentBox.getChildren().remove(aiAgentSidePanel);
        }
        if (aiAgentSideDivider != null) {
            mainContentBox.getChildren().remove(aiAgentSideDivider);
        }

        if (placement == AiAgentPanelDockManager.Placement.BOTTOM) {
            if (aiAgentSidePanel != null) {
                aiAgentSidePanel.unbind(); // re-attaches the bound tab's panels to their split bottoms
            }
            // Make sure no tab is left detached.
            for (TerminalTab tab : terminalTabs()) {
                if (tab.getTerminalView() != null) {
                    tab.getTerminalView().setBottomPanelsDetached(false);
                }
            }
            syncAiAgentMenuItems(placement);
        } else {
            double width = AiAgentPanelDockManager.clampWidth(aiAgentDockManager.getPreferredWidth());
            aiAgentSidePanel.setPrefWidth(width);
            // Dock immediately adjacent to the terminal tabPane, computing the index relative to it so
            // the layout is independent of action order and of whether the file browser is docked.
            int tabIndex = Math.max(0, mainContentBox.getChildren().indexOf(tabPane));
            if (placement == AiAgentPanelDockManager.Placement.LEFT) {
                // Result order: [ ... ][ panel ][ divider ][ tabPane ][ ... ]
                mainContentBox.getChildren().add(tabIndex, aiAgentSideDivider);
                mainContentBox.getChildren().add(tabIndex, aiAgentSidePanel);
            } else {
                // Result order: [ ... ][ tabPane ][ divider ][ panel ][ ... ]
                mainContentBox.getChildren().add(tabIndex + 1, aiAgentSideDivider);
                mainContentBox.getChildren().add(tabIndex + 2, aiAgentSidePanel);
            }
            aiAgentSidePanel.bindToTerminalTab(activeTerminalTab());
            syncAiAgentMenuItems(placement);
            applyMainWindowThemeFromGlobalSettings();
        }
        persistAiAgentDockSettings();
    }

    /** Wires a freshly created terminal tab so split open/close rebuilds its side-dock outer tabs. */
    private void registerTerminalTabForAiAgentDock(TerminalTab terminalTab) {
        if (terminalTab == null || terminalTab.getTerminalView() == null) {
            return;
        }
        terminalTab.getTerminalView().setOnWidgetSetChanged(() -> onTerminalWidgetSetChanged(terminalTab));
        // A local shell's cd: the session snapshot keeps the directory it restarts in.
        terminalTab.getTerminalView().setOnSessionStateChanged(MainWindow::markSessionDirty);
        terminalTab.setJournalStateListener(() -> onTabJournalStateChanged(terminalTab));
        // Pane focus → done-until-seen; idempotent, and the listener resolves the owning window
        // itself, so a tab dragged to another window keeps working without re-registration.
        terminalTab.getTerminalView().addFocusedWidgetListener(CODING_AGENT_PANE_FOCUS_LISTENER);
    }

    /** Shared per-pane focus hook: resolves the window from the widget's scene, never captures one. */
    private static final Consumer<SithTermFxWidget> CODING_AGENT_PANE_FOCUS_LISTENER = widget -> {
        MainWindow window = null;
        try {
            if (widget != null && widget.getPane() != null && widget.getPane().getScene() != null) {
                window = findByStage(widget.getPane().getScene().getWindow());
            }
        } catch (RuntimeException e) {
            window = null;
        }
        if (window != null) {
            window.onCodingAgentFocusContextChanged();
        } else {
            CodingAgentUiBridge bridge = KorTTYApplication.getInstance() != null
                ? KorTTYApplication.getInstance().getCodingAgentUiBridge() : null;
            if (bridge != null) {
                bridge.reconcileSeen();
            }
        }
    };

    /** Tab selection, pane focus or window focus changed: re-evaluate done-until-seen, follow the focus in the panel. */
    private void onCodingAgentFocusContextChanged() {
        CodingAgentUiBridge bridge = app.getCodingAgentUiBridge();
        if (bridge != null) {
            bridge.reconcileSeen();
        }
        if (codingAgentPanel != null && codingAgentPanel.isBound()) {
            codingAgentPanel.refresh();
        }
    }

    /**
     * Updates each terminal tab's title badge: the korTTY AI-agent status merged with the most urgent
     * coding-agent state of the tab (DONE-until-seen outranks WORKING so title, chip and rollup agree).
     */
    private void refreshAgentStatusIndicators() {
        CodingAgentRegistry registry = app.getCodingAgentRegistry();
        for (TerminalTab tab : terminalTabs()) {
            TerminalView view = tab.getTerminalView();
            String badge = "";
            if (view != null) {
                AgentDashboardStatus.State legacy = AgentDashboardStatus.aggregate(view.aggregateTerminalAgentRunCounts());
                CodingAgentState coding = null;
                if (registry != null) {
                    TabRollup rollup = registry.rollupFor(view.getTerminalViewId());
                    coding = rollup != null && rollup.hasAgents() ? rollup.mostUrgent() : null;
                }
                badge = CodingAgentGlyphs.tabBadge(legacy, coding);
            }
            tab.setAgentStatusBadge(badge);
        }
        // Runs every second in the foreground window: an AI agent run that started or ended in a
        // member pane changes how many members multi-exec leaves out.
        refreshMultiExecStatus();
    }

    /**
     * Called by the UI bridge on every registry change (background windows included, where the
     * foreground-gated title timer is off): refreshes the tab glyphs and the status strip.
     */
    public void onCodingAgentsChanged() {
        refreshAgentStatusIndicators();
        if (codingAgentStatusStrip != null) {
            codingAgentStatusStrip.refresh();
        }
        // A coding agent that waits for a decision gets no mirrored keys: the chip counts it.
        refreshMultiExecStatus();
    }

    private void startAgentStatusIndicatorTimer() {
        if (!isForegroundWindow() || terminalTabs().isEmpty() || agentStatusIndicatorTimer != null) {
            return;
        }
        agentStatusIndicatorTimer = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                e -> refreshAgentStatusIndicators()));
        agentStatusIndicatorTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        agentStatusIndicatorTimer.play();
    }

    private void stopAgentStatusIndicatorTimer() {
        if (agentStatusIndicatorTimer != null) {
            agentStatusIndicatorTimer.stop();
            agentStatusIndicatorTimer = null;
        }
    }

    private void onTerminalWidgetSetChanged(TerminalTab terminalTab) {
        if (aiAgentDockManager != null && aiAgentDockManager.isDocked()
            && aiAgentSidePanel != null && aiAgentSidePanel.getBoundTab() == terminalTab) {
            aiAgentSidePanel.refreshBinding();
        }
        // Splitting or closing a pane changes the dashboard's tree shape — a split tab carries one
        // PANE row per pane — and nothing else in the tab's lifecycle fires afterwards, so without
        // this the new pane stays missing from the dashboard until some unrelated event happens to
        // rebuild it.
        updateDashboard();
        // View > Panes needs two or more panes to move the focus.
        syncPaneMenuItems();
        // The tab marker counts the tab's panes.
        refreshMirrorTabMarkers();
        // The session snapshot keeps the tab's split panes.
        markSessionDirty();
    }

    /** Re-binds the docked side panel to the currently active terminal tab (spotlight model). */
    private void rebindAiAgentSidePanelToActiveTab() {
        if (aiAgentDockManager != null && aiAgentDockManager.isDocked() && aiAgentSidePanel != null) {
            aiAgentSidePanel.bindToTerminalTab(activeTerminalTab());
        }
    }

    private void syncAiAgentMenuItems(AiAgentPanelDockManager.Placement placement) {
        boolean bottom = placement == AiAgentPanelDockManager.Placement.BOTTOM;
        boolean left = placement == AiAgentPanelDockManager.Placement.LEFT;
        boolean right = placement == AiAgentPanelDockManager.Placement.RIGHT;
        if (showAiAgentBottomMenuItem != null) {
            showAiAgentBottomMenuItem.setSelected(bottom);
        }
        if (showAiAgentLeftMenuItem != null) {
            showAiAgentLeftMenuItem.setSelected(left);
        }
        if (showAiAgentRightMenuItem != null) {
            showAiAgentRightMenuItem.setSelected(right);
        }
        if (systemShowAiAgentBottomMenuItem != null) {
            systemShowAiAgentBottomMenuItem.setSelected(bottom);
        }
        if (systemShowAiAgentLeftMenuItem != null) {
            systemShowAiAgentLeftMenuItem.setSelected(left);
        }
        if (systemShowAiAgentRightMenuItem != null) {
            systemShowAiAgentRightMenuItem.setSelected(right);
        }
    }

    private void applyPersistedAiAgentPlacement() {
        try {
            ensureAiAgentDockManager();
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            if (settings == null) {
                return;
            }
            aiAgentDockManager.setPreferredWidth(settings.getAiAgentPanelSideWidth());
            AiAgentPanelDockManager.Placement placement =
                AiAgentPanelDockManager.parsePlacement(settings.getAiAgentPanelPlacement());
            if (placement == AiAgentPanelDockManager.Placement.BOTTOM) {
                syncAiAgentMenuItems(AiAgentPanelDockManager.Placement.BOTTOM);
            } else {
                aiAgentDockManager.setPlacement(placement); // fires onAiAgentPlacementChanged → docks
            }
        } catch (Exception e) {
            logger.debug("Could not apply persisted AI agent placement: {}", e.getMessage());
        }
    }

    private void persistAiAgentDockSettings() {
        try {
            var gsm = app.getGlobalSettingsManager();
            GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null || aiAgentDockManager == null) {
                return;
            }
            settings.setAiAgentPanelPlacement(aiAgentDockManager.getPlacement().name());
            settings.setAiAgentPanelSideWidth(aiAgentDockManager.getPreferredWidth());
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not persist AI agent dock settings: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- live journal panel docking

    private void ensureJournalLiveDockManager() {
        if (journalLiveDockManager == null) {
            // Per-window (not a singleton): each window docks independently and is GC'd with its manager.
            journalLiveDockManager = new SessionJournalLivePanelDockManager();
            journalLivePlacementListener = placement -> onJournalLivePanelPlacementChanged(placement);
            journalLiveDockManager.addPlacementListener(journalLivePlacementListener);
        }
    }

    private void setJournalLivePanelPlacement(SessionJournalLivePanelDockManager.Placement placement) {
        ensureJournalLiveDockManager();
        journalLiveDockManager.toggle(placement);
    }

    private void toggleJournalLivePanelVisible() {
        ensureJournalLiveDockManager();
        journalLiveDockManager.toggleVisible();
    }

    private void onJournalLivePanelPlacementChanged(SessionJournalLivePanelDockManager.Placement placement) {
        ensureJournalLiveDockManager();
        // Lazily create the panel + resizable divider on first dock.
        if (placement != SessionJournalLivePanelDockManager.Placement.HIDDEN && journalLivePanel == null) {
            journalLivePanel = new SessionJournalLivePanel(this, this::openSessionJournalForSession);
            journalLivePanel.setMinWidth(SessionJournalLivePanelDockManager.MIN_WIDTH);
            journalLivePanel.setPrefWidth(journalLiveDockManager.getPreferredWidth());
            journalLivePanel.setMaxWidth(SessionJournalLivePanelDockManager.MAX_WIDTH);
            journalLiveDivider = new ResizableDivider(Orientation.VERTICAL);
            journalLiveDivider.setResizeListener(delta -> {
                double current = journalLivePanel.getPrefWidth();
                double directional = journalLiveDockManager.getPlacement()
                    == SessionJournalLivePanelDockManager.Placement.RIGHT ? -delta : delta;
                double newWidth = SessionJournalLivePanelDockManager.clampWidth(current + directional);
                journalLivePanel.setPrefWidth(newWidth);
                journalLiveDockManager.setPreferredWidth(newWidth);
                // Debounced: the resize listener fires per drag delta, and the settings file is
                // large enough that writing it on every pixel would stutter the drag.
                if (journalLiveWidthSaveDelay == null) {
                    journalLiveWidthSaveDelay =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(350));
                    journalLiveWidthSaveDelay.setOnFinished(event -> persistJournalLivePanelSettings());
                }
                journalLiveWidthSaveDelay.playFromStart();
                return newWidth;
            });
        }
        // Remove the panel + divider from the layout if currently present.
        if (journalLivePanel != null) {
            mainContentBox.getChildren().remove(journalLivePanel);
        }
        if (journalLiveDivider != null) {
            mainContentBox.getChildren().remove(journalLiveDivider);
        }

        if (placement == SessionJournalLivePanelDockManager.Placement.HIDDEN) {
            if (journalLivePanel != null) {
                journalLivePanel.unbind(); // re-showing re-backfills; the log is the source of truth
            }
            syncJournalLivePanelMenuItems(placement);
        } else {
            double width = SessionJournalLivePanelDockManager.clampWidth(
                journalLiveDockManager.getPreferredWidth());
            journalLivePanel.setPrefWidth(width);
            // Dock immediately adjacent to the terminal tabPane, computing the index relative to it so
            // the layout is independent of action order and of the other docked side panels.
            int tabIndex = Math.max(0, mainContentBox.getChildren().indexOf(tabPane));
            if (placement == SessionJournalLivePanelDockManager.Placement.LEFT) {
                // Result order: [ ... ][ panel ][ divider ][ tabPane ][ ... ]
                mainContentBox.getChildren().add(tabIndex, journalLiveDivider);
                mainContentBox.getChildren().add(tabIndex, journalLivePanel);
            } else {
                // Result order: [ ... ][ tabPane ][ divider ][ panel ][ ... ]
                mainContentBox.getChildren().add(tabIndex + 1, journalLiveDivider);
                mainContentBox.getChildren().add(tabIndex + 2, journalLivePanel);
            }
            TerminalTab active = activeTerminalTab();
            if (active != null && active.getTerminalView() != null
                    && active.getTerminalView().isSessionJournalActive()) {
                journalLivePanel.bindToTab(active);
            } else {
                journalLivePanel.showNoJournalPlaceholder();
            }
            syncJournalLivePanelMenuItems(placement);
            applyMainWindowThemeFromGlobalSettings();
        }
        persistJournalLivePanelSettings();
        Telemetry.track(TelemetryEvents.JOURNAL_LIVE_PANEL_TOGGLED,
            java.util.Map.of("placement", placement.name()));
    }

    /**
     * Writes a terminal-agent run (prompt as title, final answer as text) into the tab's active
     * session journal as an AGENT timeline card. No-op without a running journal.
     */
    private void appendAgentJournalEntry(TerminalTab terminalTab, String prompt, String answer,
                                         String modelText, long durationMillis, long totalTokens) {
        if (terminalTab == null || terminalTab.getTerminalView() == null
            || answer == null || answer.isBlank()) {
            return;
        }
        de.kortty.core.SessionJournalSession session =
            terminalTab.getTerminalView().getSessionJournalSession();
        if (session == null || !session.isActive()) {
            return;
        }
        de.kortty.model.SessionJournalEntry entry = new de.kortty.model.SessionJournalEntry();
        entry.setKind(de.kortty.model.SessionJournalEntryKind.AGENT);
        String title = prompt != null && !prompt.isBlank() ? prompt.strip() : I18n.get("ai.agent.title");
        entry.setTitle(title.length() > 140 ? title.substring(0, 140) + "…" : title);
        entry.setText(answer.strip());
        if (modelText != null && !modelText.isBlank()) {
            entry.setAgentModel(modelText.strip());
        }
        if (durationMillis > 0) {
            entry.setAgentDurationMillis(durationMillis);
        }
        if (totalTokens > 0) {
            entry.setAgentTokens(totalTokens);
        }
        long seq = session.getLastSequence();
        if (seq > 0) {
            entry.setLogStartSeq(seq);
            entry.setLogEndSeq(seq);
        }
        Thread saver = new Thread(() -> {
            try {
                app.getSessionJournalService().appendEntry(session.getDirectory(), entry);
            } catch (Exception e) {
                logger.warn("Could not journal the agent result: {}", e.getMessage());
            }
        }, "SessionJournal-AgentEntry");
        saver.setDaemon(true);
        saver.start();
    }

    /** Opens the full journal viewer for a session shown in the live panel. */
    private void openSessionJournalForSession(de.kortty.core.SessionJournalSession session) {
        if (session == null) {
            return;
        }
        de.kortty.model.SessionJournalMeta meta = session.getMetaSnapshot();
        meta.setDirectory(session.getDirectory());
        openSessionJournal(meta);
    }

    /**
     * Spotlight follow with a memory: the panel only rebinds when the newly selected tab has a live
     * journal; otherwise it keeps showing the journal it is already bound to.
     */
    private void rebindJournalLivePanelToActiveTab() {
        if (journalLiveDockManager == null || !journalLiveDockManager.isDocked() || journalLivePanel == null) {
            return;
        }
        TerminalTab active = activeTerminalTab();
        if (active != null && active.getTerminalView() != null
                && active.getTerminalView().isSessionJournalActive()) {
            journalLivePanel.bindToTab(active);
        }
    }

    /** Reacts to journal start/stop in a tab (fired by the tab's journal bar state funnel). */
    private void onTabJournalStateChanged(TerminalTab tab) {
        if (journalLiveDockManager == null || !journalLiveDockManager.isDocked() || journalLivePanel == null) {
            return;
        }
        boolean active = tab.getTerminalView() != null
            && tab.getTerminalView().isSessionJournalActive();
        if (active && tab == activeTerminalTab()) {
            // Covers manual start, auto-start on connect and a restart in the bound tab
            // (bindToTab sees a new session object and restarts the feed).
            journalLivePanel.bindToTab(tab);
        } else if (!active && tab == journalLivePanel.getBoundTab()) {
            journalLivePanel.notifyBoundJournalStopped();
        }
    }

    private void syncJournalLivePanelMenuItems(SessionJournalLivePanelDockManager.Placement placement) {
        boolean left = placement == SessionJournalLivePanelDockManager.Placement.LEFT;
        boolean right = placement == SessionJournalLivePanelDockManager.Placement.RIGHT;
        if (showJournalLiveLeftMenuItem != null) {
            showJournalLiveLeftMenuItem.setSelected(left);
        }
        if (showJournalLiveRightMenuItem != null) {
            showJournalLiveRightMenuItem.setSelected(right);
        }
        if (systemShowJournalLiveLeftMenuItem != null) {
            systemShowJournalLiveLeftMenuItem.setSelected(left);
        }
        if (systemShowJournalLiveRightMenuItem != null) {
            systemShowJournalLiveRightMenuItem.setSelected(right);
        }
    }

    private void applyPersistedJournalLivePanel() {
        try {
            ensureJournalLiveDockManager();
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            if (settings == null) {
                return;
            }
            journalLiveDockManager.setPreferredWidth(settings.getJournalLivePanelWidth());
            SessionJournalLivePanelDockManager.Placement placement =
                SessionJournalLivePanelDockManager.parsePlacement(settings.getJournalLivePanelPlacement());
            if (placement == SessionJournalLivePanelDockManager.Placement.HIDDEN) {
                syncJournalLivePanelMenuItems(placement);
            } else {
                journalLiveDockManager.setPlacement(placement); // fires the placement listener → docks
            }
        } catch (Exception e) {
            logger.debug("Could not apply persisted live journal panel placement: {}", e.getMessage());
        }
    }

    private void persistJournalLivePanelSettings() {
        try {
            var gsm = app.getGlobalSettingsManager();
            GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null || journalLiveDockManager == null) {
                return;
            }
            settings.setJournalLivePanelPlacement(journalLiveDockManager.getPlacement().name());
            settings.setJournalLivePanelWidth(journalLiveDockManager.getPreferredWidth());
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not persist live journal panel settings: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Coding Agents panel docking

    /** Inserts the coding-agent status strip right-aligned into the status row (after the spacer). */
    private void installCodingAgentStatusStrip(Region statusSpacer) {
        CodingAgentRegistry registry = app.getCodingAgentRegistry();
        if (registry == null || statusRow == null) {
            return;
        }
        try {
            codingAgentStatusStrip = new CodingAgentStatusStrip(registry);
            codingAgentStatusStrip.setOnActivate(this::focusNextBlockedCodingAgent);
            codingAgentStatusStrip.setOnShowPanel(this::toggleCodingAgentPanelVisible);
            codingAgentStatusStrip.setOnNextBlocked(this::focusNextBlockedCodingAgent);
            int spacerIndex = statusRow.getChildren().indexOf(statusSpacer);
            statusRow.getChildren().add(spacerIndex < 0 ? statusRow.getChildren().size() : spacerIndex + 1,
                codingAgentStatusStrip);
            codingAgentStatusStrip.attach();
        } catch (RuntimeException e) {
            logger.warn("Coding-agent status strip could not be installed: {}", e.toString());
            codingAgentStatusStrip = null;
        }
    }

    /**
     * Puts the multi-exec chip into the status bar, right of the spacer, and subscribes this window
     * to multi-exec, so the chip, the tab markers, View → Multi-exec and the dashboard follow every
     * change in any window. The subscription ends when the window closes.
     */
    private void installMultiExecStatusBar(Region statusSpacer) {
        if (statusRow == null) {
            return;
        }
        multiExecStatusBar = new MultiExecStatusBar();
        multiExecStatusBar.setOnStop(() -> MultiExecCoordinator.shared().stop());
        int spacerIndex = statusRow.getChildren().indexOf(statusSpacer);
        statusRow.getChildren().add(spacerIndex < 0 ? statusRow.getChildren().size() : spacerIndex + 1,
            multiExecStatusBar);
        multiExecListener = MultiExecCoordinator.shared().addListener(this::onMultiExecChanged);
        refreshMultiExecStatus();
    }

    /** Ends this window's multi-exec subscription; the window closed. */
    private void releaseMultiExecListener() {
        AutoCloseable listener = multiExecListener;
        multiExecListener = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception e) {
                logger.debug("Could not unsubscribe from multi-exec: {}", e.toString());
            }
        }
    }

    /**
     * Multi-exec changed, here or in another window, or a tab's broadcast mode was switched: the
     * status chip, the markers of this window's tabs, View → Multi-exec and the dashboard rows.
     */
    private void onMultiExecChanged() {
        refreshMultiExecStatus();
        refreshMirrorTabMarkers();
        syncMultiExecMenuItems();
        if (dashboardView != null && dashboardVisible) {
            dashboardView.refreshRows();
        }
    }

    /** Updates the status chip: how many panes take part, in how many tabs and windows, and how many are left out. */
    private void refreshMultiExecStatus() {
        if (multiExecStatusBar == null) {
            return;
        }
        MultiExecCoordinator multiExec = MultiExecCoordinator.shared();
        if (multiExec.memberCount() == 0) {
            multiExecStatusBar.update(MultiExecMembership.Counts.NONE, 0);
            return;
        }
        multiExecStatusBar.update(multiExec.counts(MainWindow::windowHoldingSplitPane), multiExec.countHeld());
    }

    /** The open window whose terminal tab holds {@code splitPane}, or {@code null}. */
    private static MainWindow windowHoldingSplitPane(TerminalSplitPane splitPane) {
        for (MainWindow window : openWindows) {
            for (TerminalTab tab : window.terminalTabs()) {
                TerminalView view = tab.getTerminalView();
                if (view != null && view.holdsSplitPane(splitPane)) {
                    return window;
                }
            }
        }
        return null;
    }

    /** Marks each terminal tab of this window whose typing goes to other panes (multi-exec or broadcast mode). */
    private void refreshMirrorTabMarkers() {
        for (TerminalTab tab : terminalTabs()) {
            TerminalView view = tab.getTerminalView();
            if (view == null) {
                tab.setMirrorMarker(null);
                continue;
            }
            tab.setMirrorMarker(MultiExecMarkers.tabMarkerText(view.multiExecMemberCount(),
                view.getTerminalPaneCount(), view.isBroadcastMode()));
        }
    }

    /** The focused pane of the active terminal tab, or {@code null}. */
    private SithTermFxWidget activeFocusedPane() {
        TerminalTab terminalTab = activeTerminalTab();
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        return view != null ? view.getFocusedWidget() : null;
    }

    /** Every pane of every terminal tab of this window. */
    private List<SithTermFxWidget> windowPanes() {
        List<SithTermFxWidget> panes = new ArrayList<>();
        for (TerminalTab tab : terminalTabs()) {
            if (tab.getTerminalView() != null) {
                panes.addAll(tab.getTerminalView().getOrderedWidgets());
            }
        }
        return panes;
    }

    /**
     * View → Multi-exec: the focused pane, all panes of the active tab or of the window join or leave
     * multi-exec, or it stops. Every command decides from the members, never from a check item.
     */
    private Menu createMultiExecMenu(MenuBarTarget target) {
        MultiExecMenuSupport.MultiExecMenu menu = MultiExecMenuSupport.create(new MultiExecMenuSupport.Commands() {
            @Override
            public void togglePane() {
                MultiExecCoordinator.shared().togglePane(activeFocusedPane());
                syncMultiExecMenuItems();
            }

            @Override
            public void toggleTab() {
                TerminalTab terminalTab = activeTerminalTab();
                if (terminalTab != null && terminalTab.getTerminalView() != null) {
                    MultiExecCoordinator.shared().toggleAll(terminalTab.getTerminalView().getOrderedWidgets());
                }
                syncMultiExecMenuItems();
            }

            @Override
            public void includeWindow() {
                MultiExecCoordinator.shared().setPanes(windowPanes(), true);
                syncMultiExecMenuItems();
            }

            @Override
            public void stop() {
                MultiExecCoordinator.shared().stop();
                syncMultiExecMenuItems();
            }
        });
        if (!de.kortty.policy.PolicyManager.effective().multiExecAllowed()) {
            MultiExecMenuSupport.lockByPolicy(menu);
        }
        menu.menu().setOnShowing(event -> syncMultiExecMenuItems());
        menu.menu().setOnMenuValidation(event -> syncMultiExecMenuItems());
        if (target == MenuBarTarget.WINDOW) {
            multiExecMenu = menu;
        } else {
            systemMultiExecMenu = menu;
        }
        return menu.menu();
    }

    /** Shows the active tab's and the window's multi-exec state on View → Multi-exec of both menu bars. */
    private void syncMultiExecMenuItems() {
        MultiExecCoordinator multiExec = MultiExecCoordinator.shared();
        TerminalTab terminalTab = activeTerminalTab();
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        SithTermFxWidget focused = view != null ? view.getFocusedWidget() : null;
        MultiExecMenuSupport.State state = new MultiExecMenuSupport.State(view != null,
            focused != null && multiExec.isMember(focused),
            view != null && multiExec.includesAll(view.getOrderedWidgets()),
            !terminalTabs().isEmpty(), multiExec.memberCount());
        MultiExecMenuSupport.sync(multiExecMenu, state);
        MultiExecMenuSupport.sync(systemMultiExecMenu, state);
    }

    /** This window's docked Coding Agents panel, or null while it was never shown. */
    CodingAgentPanel getCodingAgentPanel() {
        return codingAgentPanel;
    }

    /** The per-window dock manager of the Coding Agents panel (created on first use). */
    public CodingAgentPanelDockManager getCodingAgentDockManager() {
        ensureCodingAgentDockManager();
        return codingAgentDockManager;
    }

    private void ensureCodingAgentDockManager() {
        if (codingAgentDockManager == null) {
            // Per-window (not a singleton): each window docks independently and is GC'd with its manager.
            codingAgentDockManager = new CodingAgentPanelDockManager();
            codingAgentPlacementListener = placement -> onCodingAgentPanelPlacementChanged(placement);
            codingAgentDockManager.addPlacementListener(codingAgentPlacementListener);
        }
    }

    private void setCodingAgentPanelPlacement(CodingAgentPanelDockManager.Placement placement) {
        ensureCodingAgentDockManager();
        codingAgentDockManager.toggle(placement);
    }

    /** Shows the Coding Agents panel on its last-used side, or hides it when visible (Shortcut+Alt+G). */
    public void toggleCodingAgentPanelVisible() {
        ensureCodingAgentDockManager();
        codingAgentDockManager.toggleVisible();
    }

    /** Docks the Coding Agents panel (last side) if hidden and selects the row of {@code pane}. */
    public void showCodingAgentPanelFor(PaneRef pane) {
        ensureCodingAgentDockManager();
        if (!codingAgentDockManager.isDocked()) {
            codingAgentDockManager.setPlacement(codingAgentDockManager.getLastDockedSide());
        }
        if (codingAgentPanel != null && pane != null) {
            codingAgentPanel.selectPane(pane);
        }
    }

    /**
     * Brings the next coding agent that waits for a decision to the front (across windows); when
     * none is blocked, focuses the first agent in display order so a click always lands somewhere.
     */
    public void focusNextBlockedCodingAgent() {
        CodingAgentNavigator navigator = app.getCodingAgentNavigator();
        if (navigator == null) {
            return;
        }
        try {
            if (navigator.focusNextBlocked().isPresent()) {
                Telemetry.track(TelemetryEvents.CODING_AGENT_ACTION, Map.of("action", "next_blocked"));
                return;
            }
            List<CodingAgentEntry> ordered = navigator.orderedEntries();
            if (!ordered.isEmpty() && navigator.focus(ordered.get(0).pane())) {
                Telemetry.track(TelemetryEvents.CODING_AGENT_ACTION, Map.of("action", "focus_first"));
            }
        } catch (RuntimeException e) {
            logger.debug("Next blocked coding agent could not be focused: {}", e.getMessage());
        }
    }

    private void onCodingAgentPanelPlacementChanged(CodingAgentPanelDockManager.Placement placement) {
        ensureCodingAgentDockManager();
        // Lazily create the panel + resizable divider on first dock.
        if (placement != CodingAgentPanelDockManager.Placement.HIDDEN && codingAgentPanel == null) {
            CodingAgentRegistry registry = app.getCodingAgentRegistry();
            CodingAgentActions actions = app.getCodingAgentActions();
            CodingAgentNavigator navigator = app.getCodingAgentNavigator();
            CodingAgentUiBridge bridge = app.getCodingAgentUiBridge();
            if (registry == null || actions == null || navigator == null || bridge == null) {
                logger.debug("Coding Agents panel unavailable: the coding-agent services are not initialised");
                syncCodingAgentPanelMenuItems(CodingAgentPanelDockManager.Placement.HIDDEN);
                return;
            }
            codingAgentPanel = new CodingAgentPanel(registry, actions, navigator, bridge, System::currentTimeMillis);
            codingAgentPanel.setOnDockRequest(requested -> codingAgentDockManager.setPlacement(requested));
            codingAgentPanel.setOnNextBlocked(this::focusNextBlockedCodingAgent);
            codingAgentPanel.setWindowActive(isForegroundWindow());
            codingAgentPanel.setMinWidth(CodingAgentPanelDockManager.MIN_WIDTH);
            codingAgentPanel.setPrefWidth(codingAgentDockManager.getPreferredWidth());
            codingAgentPanel.setMaxWidth(CodingAgentPanelDockManager.MAX_WIDTH);
            codingAgentDivider = new ResizableDivider(Orientation.VERTICAL);
            codingAgentDivider.setResizeListener(delta -> {
                double current = codingAgentPanel.getPrefWidth();
                double directional = codingAgentDockManager.getPlacement()
                    == CodingAgentPanelDockManager.Placement.RIGHT ? -delta : delta;
                double newWidth = CodingAgentPanelDockManager.clampWidth(current + directional);
                codingAgentPanel.setPrefWidth(newWidth);
                codingAgentDockManager.setPreferredWidth(newWidth);
                // Debounced: the resize listener fires per drag delta, and the settings file is
                // large enough that writing it on every pixel would stutter the drag.
                if (codingAgentWidthSaveDelay == null) {
                    codingAgentWidthSaveDelay =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(350));
                    codingAgentWidthSaveDelay.setOnFinished(event -> persistCodingAgentPanelSettings());
                }
                codingAgentWidthSaveDelay.playFromStart();
                return newWidth;
            });
        }
        // Remove the panel + divider from the layout if currently present.
        if (codingAgentPanel != null) {
            mainContentBox.getChildren().remove(codingAgentPanel);
        }
        if (codingAgentDivider != null) {
            mainContentBox.getChildren().remove(codingAgentDivider);
        }

        if (placement == CodingAgentPanelDockManager.Placement.HIDDEN) {
            if (codingAgentPanel != null) {
                codingAgentPanel.unbind(); // re-showing re-subscribes; the registry is the source of truth
            }
            syncCodingAgentPanelMenuItems(placement);
        } else {
            double width = CodingAgentPanelDockManager.clampWidth(codingAgentDockManager.getPreferredWidth());
            codingAgentPanel.setPrefWidth(width);
            // Dock immediately adjacent to the terminal tabPane, computing the index relative to it so
            // the layout is independent of action order and of the other docked side panels.
            int tabIndex = Math.max(0, mainContentBox.getChildren().indexOf(tabPane));
            if (placement == CodingAgentPanelDockManager.Placement.LEFT) {
                // Result order: [ ... ][ panel ][ divider ][ tabPane ][ ... ]
                mainContentBox.getChildren().add(tabIndex, codingAgentDivider);
                mainContentBox.getChildren().add(tabIndex, codingAgentPanel);
            } else {
                // Result order: [ ... ][ tabPane ][ divider ][ panel ][ ... ]
                mainContentBox.getChildren().add(tabIndex + 1, codingAgentDivider);
                mainContentBox.getChildren().add(tabIndex + 2, codingAgentPanel);
            }
            codingAgentPanel.bind();
            syncCodingAgentPanelMenuItems(placement);
            applyMainWindowThemeFromGlobalSettings();
        }
        persistCodingAgentPanelSettings();
        Telemetry.track(TelemetryEvents.CODING_AGENT_PANEL_TOGGLED,
            java.util.Map.of("placement", placement.name()));
    }

    private void syncCodingAgentPanelMenuItems(CodingAgentPanelDockManager.Placement placement) {
        boolean left = placement == CodingAgentPanelDockManager.Placement.LEFT;
        boolean right = placement == CodingAgentPanelDockManager.Placement.RIGHT;
        if (showCodingAgentLeftMenuItem != null) {
            showCodingAgentLeftMenuItem.setSelected(left);
        }
        if (showCodingAgentRightMenuItem != null) {
            showCodingAgentRightMenuItem.setSelected(right);
        }
        if (systemShowCodingAgentLeftMenuItem != null) {
            systemShowCodingAgentLeftMenuItem.setSelected(left);
        }
        if (systemShowCodingAgentRightMenuItem != null) {
            systemShowCodingAgentRightMenuItem.setSelected(right);
        }
    }

    private void applyPersistedCodingAgentPanel() {
        try {
            ensureCodingAgentDockManager();
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            if (settings == null) {
                return;
            }
            codingAgentDockManager.setPreferredWidth(settings.getCodingAgentPanelWidth());
            CodingAgentPanelDockManager.Placement placement =
                CodingAgentPanelDockManager.parsePlacement(settings.getCodingAgentPanelPlacement());
            if (placement == CodingAgentPanelDockManager.Placement.HIDDEN) {
                syncCodingAgentPanelMenuItems(placement);
            } else {
                codingAgentDockManager.setPlacement(placement); // fires the placement listener → docks
            }
        } catch (Exception e) {
            logger.debug("Could not apply persisted Coding Agents panel placement: {}", e.getMessage());
        }
    }

    private void persistCodingAgentPanelSettings() {
        try {
            var gsm = app.getGlobalSettingsManager();
            GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null || codingAgentDockManager == null) {
                return;
            }
            settings.setCodingAgentPanelPlacement(codingAgentDockManager.getPlacement().name());
            settings.setCodingAgentPanelWidth(codingAgentDockManager.getPreferredWidth());
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not persist Coding Agents panel settings: {}", e.getMessage());
        }
    }

    /** The terminal tab of this window whose view carries {@code terminalViewId}. */
    public Optional<TerminalTab> findTerminalTabByViewId(String terminalViewId) {
        if (terminalViewId == null) {
            return Optional.empty();
        }
        for (TerminalTab tab : terminalTabs()) {
            TerminalView view = tab.getTerminalView();
            if (view != null && terminalViewId.equals(view.getTerminalViewId())) {
                return Optional.of(tab);
            }
        }
        return Optional.empty();
    }

    /** Window-title fallback of the app badge: "(n) KorTTY" for n &gt; 0, the plain name otherwise. */
    public void applyWindowTitleBadge(int blockedCount) {
        String title = CodingAgentUiBridge.titleFor(blockedCount, KorTTYApplication.getAppName(), I18n::get);
        if (!title.equals(stage.getTitle())) {
            stage.setTitle(title);
        }
    }

    /** Replaces this window's stage icons (Windows taskbar badge / plain icon). */
    public void applyStageIcon(Image icon) {
        if (icon == null) {
            return;
        }
        try {
            stage.getIcons().setAll(icon);
        } catch (RuntimeException e) {
            logger.debug("Stage icon could not be applied: {}", e.getMessage());
        }
    }

    private void syncTimestampMenuItems(boolean visible) {
        if (showTimestampsMenuItem != null && showTimestampsMenuItem.isSelected() != visible) {
            showTimestampsMenuItem.setSelected(visible);
        }
        if (systemShowTimestampsMenuItem != null && systemShowTimestampsMenuItem.isSelected() != visible) {
            systemShowTimestampsMenuItem.setSelected(visible);
        }
    }

    private void syncMenuBarToggleMenuItems(boolean visible) {
        if (showMenuBarMenuItem != null && showMenuBarMenuItem.isSelected() != visible) {
            showMenuBarMenuItem.setSelected(visible);
        }
        if (systemShowMenuBarMenuItem != null && systemShowMenuBarMenuItem.isSelected() != visible) {
            systemShowMenuBarMenuItem.setSelected(visible);
        }
    }

    private void syncTerminalOnlyFullscreenMenuItems() {
        if (terminalOnlyFullscreenMenuItem != null
            && terminalOnlyFullscreenMenuItem.isSelected() != terminalOnlyFullscreenActive) {
            terminalOnlyFullscreenMenuItem.setSelected(terminalOnlyFullscreenActive);
        }
        if (systemTerminalOnlyFullscreenMenuItem != null
            && systemTerminalOnlyFullscreenMenuItem.isSelected() != terminalOnlyFullscreenActive) {
            systemTerminalOnlyFullscreenMenuItem.setSelected(terminalOnlyFullscreenActive);
        }
    }

    private void syncHideFullscreenScrollbarsMenuItems() {
        boolean hide = isHideTerminalScrollbarsInFullscreenPreference();
        if (hideFullscreenScrollbarsMenuItem != null
            && hideFullscreenScrollbarsMenuItem.isSelected() != hide) {
            hideFullscreenScrollbarsMenuItem.setSelected(hide);
        }
        if (systemHideFullscreenScrollbarsMenuItem != null
            && systemHideFullscreenScrollbarsMenuItem.isSelected() != hide) {
            systemHideFullscreenScrollbarsMenuItem.setSelected(hide);
        }
    }

    private boolean isHideTerminalScrollbarsInFullscreenPreference() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        return globalSettings != null && globalSettings.isHideTerminalScrollbarsInFullscreen();
    }

    private void setHideTerminalScrollbarsInFullscreen(boolean hide) {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        if (globalSettings != null) {
            globalSettings.setHideTerminalScrollbarsInFullscreen(hide);
            try {
                app.getGlobalSettingsManager().save();
            } catch (Exception e) {
                logger.warn("Could not persist fullscreen scrollbar visibility preference", e);
            }
        }
        syncHideFullscreenScrollbarsMenuItems();
        applyTerminalScrollbarVisibilityForOpenTabs();
        updateStatus(I18n.get("status.globalSettingsSaved"));
    }

    private void toggleTerminalOnlyFullscreen() {
        setTerminalOnlyFullscreen(!terminalOnlyFullscreenActive);
    }

    private void setTerminalOnlyFullscreen(boolean active) {
        if (active == terminalOnlyFullscreenActive) {
            syncTerminalOnlyFullscreenMenuItems();
            return;
        }

        if (active) {
            terminalOnlyPreviousFullScreen = stage.isFullScreen();
            captureTerminalOnlyContentSize();

            terminalOnlyFullscreenActive = true;
            Telemetry.track(TelemetryEvents.FULLSCREEN_ENTERED, Map.of("mode", "terminal_only"));
            if (!stage.isFullScreen()) {
                stage.setFullScreen(true);
            }
            applyTerminalOnlyCenteredLayout(true);
        } else {
            terminalOnlyFullscreenActive = false;
            applyTerminalOnlyCenteredLayout(false);
            stage.setFullScreen(terminalOnlyPreviousFullScreen);
        }

        syncTerminalOnlyFullscreenMenuItems();
        applyTerminalScrollbarVisibilityForOpenTabs();
        Platform.runLater(() -> {
            Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
            if (selectedTab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().focusTerminal();
            }
        });
    }

    /**
     * Remembers the size the whole korTTY window should keep while terminal-only fullscreen is
     * active. Normally that is the live size of the window content right before entering
     * fullscreen; when the window is already fullscreen (so no windowed size is on screen), the
     * configured window geometry from the global settings is used instead.
     */
    private void captureTerminalOnlyContentSize() {
        double width = root.getWidth();
        double height = root.getHeight();
        if (stage.isFullScreen() || width <= 0 || height <= 0) {
            WindowGeometry geo = resolveConfiguredWindowGeometry();
            if (geo != null && geo.getWidth() > 0 && geo.getHeight() > 0) {
                width = geo.getWidth();
                height = geo.getHeight();
            }
        }
        terminalOnlyContentWidth = width;
        terminalOnlyContentHeight = height;
    }

    private WindowGeometry resolveConfiguredWindowGeometry() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        if (globalSettings == null) {
            return null;
        }
        if (globalSettings.isUseFixedWindowGeometry() && globalSettings.getFixedWindowGeometry() != null) {
            return globalSettings.getFixedWindowGeometry();
        }
        if (globalSettings.isRememberWindowGeometry() && globalSettings.getLastWindowGeometry() != null) {
            return globalSettings.getLastWindowGeometry();
        }
        return null;
    }

    private static final String TERMINAL_ONLY_BACKDROP_STYLE_CLASS = "terminal-only-fullscreen-backdrop";

    /**
     * Terminal-only fullscreen must not stretch the korTTY window across the whole screen: the
     * whole application window (menu, tabs, status bar included) keeps its captured size and is
     * centered on an otherwise empty fullscreen background, so the user can focus on a single
     * window without other windows or the desktop competing for attention.
     */
    private void applyTerminalOnlyCenteredLayout(boolean active) {
        if (active && terminalOnlyContentWidth > 0 && terminalOnlyContentHeight > 0) {
            root.setPrefSize(terminalOnlyContentWidth, terminalOnlyContentHeight);
            root.setMaxSize(terminalOnlyContentWidth, terminalOnlyContentHeight);
            if (!sceneRoot.getStyleClass().contains(TERMINAL_ONLY_BACKDROP_STYLE_CLASS)) {
                sceneRoot.getStyleClass().add(TERMINAL_ONLY_BACKDROP_STYLE_CLASS);
            }
        } else {
            root.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            root.setMaxSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            sceneRoot.getStyleClass().remove(TERMINAL_ONLY_BACKDROP_STYLE_CLASS);
        }
    }

    private void applyTerminalScrollbarVisibilityForOpenTabs() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                applyTerminalScrollbarVisibility(terminalTab);
            }
        }
    }

    private void applyTerminalScrollbarVisibility(TerminalTab terminalTab) {
        if (terminalTab == null) {
            return;
        }
        terminalTab.getTerminalView().setTerminalScrollbarsVisible(shouldShowTerminalScrollbars());
    }

    private boolean shouldShowTerminalScrollbars() {
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        boolean showTerminalScrollbar = globalSettings == null || globalSettings.isShowTerminalScrollbar();
        boolean hideForFullscreen = globalSettings != null
            && globalSettings.isHideTerminalScrollbarsInFullscreen()
            && stage.isFullScreen();
        return showTerminalScrollbar && !hideForFullscreen;
    }

    private void applyStatusBarVisibility(boolean visible) {
        if (statusBar == null) {
            return;
        }
        statusBar.setVisible(visible);
        statusBar.setManaged(visible);
    }

    private void ensureFileBrowserManager() {
        if (fileBrowserManager == null) {
            fileBrowserManager = LocalFileBrowserManager.getInstance();
            fileBrowserPositionListener = pos -> onFileBrowserPositionChanged(pos);
            fileBrowserManager.addPositionListener(fileBrowserPositionListener);
        }
    }
    
    private void toggleDashboard(boolean show) {
        if (show && !dashboardVisible) {
            boolean created = dashboardView == null;
            if (created) {
                // Content-sized width: the view measures its entries and animates itself.
                dashboardView = new DashboardView(tabPane, this::handleDashboardAction,
                        this::resolveDashboardEnvironmentName);
                // Coding-agent marks: chips, accents, rollups and PANE rows come from the registry.
                dashboardView.setCodingAgentRegistry(app.getCodingAgentRegistry());
                dashboardView.setPaneActionHandler(this::handleDashboardPaneAction);
                if (app.getCodingAgentUiBridge() != null) {
                    dashboardView.setPaneLocator(app.getCodingAgentUiBridge());
                }
            }
            if (!mainContentBox.getChildren().contains(dashboardView)) {
                mainContentBox.getChildren().add(0, dashboardView);
            }
            dashboardVisible = true;
            applyMainWindowThemeFromGlobalSettings();
            if (!created) {
                // The constructor already built the tree; only re-sync on re-show.
                dashboardView.refresh();
            }
            dashboardView.playShowAnimation();
            Telemetry.track(TelemetryEvents.DASHBOARD_TOGGLED, Map.of("visible", true));
        } else if (!show && dashboardVisible) {
            dashboardVisible = false;
            dashboardView.playHideAnimation(() -> mainContentBox.getChildren().remove(dashboardView));
            Telemetry.track(TelemetryEvents.DASHBOARD_TOGGLED, Map.of("visible", false));
        }
        syncDashboardMenuItems(dashboardVisible);
        markSessionDirty();
    }
    
    private void updateDashboard() {
        if (dashboardView != null && dashboardVisible) {
            dashboardView.refresh();
        }
    }

    /**
     * Resolves a connection's credential environment display name for the dashboard
     * tree (e.g. "Production"), or null when the connection has no stored credential.
     */
    private String resolveDashboardEnvironmentName(ServerConnection connection) {
        try {
            if (connection == null || connection.getCredentialId() == null || connection.getCredentialId().isEmpty()
                    || app.getCredentialManager() == null || app.getEnvironmentManager() == null) {
                return null;
            }
            return app.getCredentialManager().findCredentialById(connection.getCredentialId())
                    .map(credential -> app.getEnvironmentManager().getDisplayName(credential.getEnvironmentId()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    private void handleDashboardAction(TerminalTab terminalTab, DashboardView.DashboardAction action) {
        if (action == DashboardView.DashboardAction.TOGGLE_MULTI_EXEC) {
            // Reported as multi_exec_changed by the coordinator, not counted as a dashboard action.
            if (terminalTab != null && terminalTab.getTerminalView() != null) {
                MultiExecCoordinator.shared().toggleAll(terminalTab.getTerminalView().getOrderedWidgets());
            }
            return;
        }
        Telemetry.track(TelemetryEvents.DASHBOARD_ACTION,
            Map.of("action", action.name().toLowerCase(Locale.ROOT)));
        switch (action) {
            case FOCUS:
                // Focus the tab
                tabPane.getSelectionModel().select(terminalTab);
                break;
                
            case CLOSE:
                // Asks like the tab's close button when the terminal is busy.
                if (closeTabsByUser(List.of(terminalTab), CloseCause.DASHBOARD)) {
                    updateDashboard();
                    updateStatus(I18n.get("status.tabClosed", terminalTab.getConnection().getDisplayName()));
                }
                break;
                
            case RECONNECT:
                Platform.runLater(() -> {
                    try {
                        terminalTab.triggerReconnect();
                    } catch (Exception e) {
                        logger.error("Reconnect failed", e);
                        updateStatus(I18n.get("status.reconnectionFailed", e.getMessage()));
                    }
                });
                break;
                
            case SFTP_MANAGER:
                // Open SFTP Manager for this connection
                if (terminalTab.isConnected()) {
                    openSftpHere(terminalTab, null);
                } else {
                    showError(I18n.get("error.notConnected"), I18n.get("error.notConnectedMessage"));
                }
                break;
                
            case DUPLICATE:
                // Duplicate the tab
                duplicateTab(terminalTab);
                break;
        }
    }

    /** Pane-level dashboard actions of coding-agent rows: focus the pane, open the panel, send a key. */
    private void handleDashboardPaneAction(TerminalTab terminalTab, SithTermFxWidget widget, PaneRef pane,
                                           DashboardView.PaneAction action) {
        if (action == null) {
            return;
        }
        if (action == DashboardView.PaneAction.TOGGLE_MULTI_EXEC) {
            // Any pane of any window joins or leaves multi-exec, without switching to it; not a
            // coding-agent action, so not counted as one.
            MultiExecCoordinator.shared().togglePane(widget);
            return;
        }
        Telemetry.track(TelemetryEvents.CODING_AGENT_ACTION,
            Map.of("action", "dashboard_" + action.name().toLowerCase(Locale.ROOT)));
        switch (action) {
            case FOCUS -> {
                CodingAgentNavigator navigator = app.getCodingAgentNavigator();
                if (pane != null && navigator != null && navigator.focus(pane)) {
                    return;
                }
                if (terminalTab != null) {
                    tabPane.getSelectionModel().select(terminalTab);
                    if (widget != null && terminalTab.getTerminalView() != null) {
                        Platform.runLater(() -> terminalTab.getTerminalView().focusWidget(widget));
                    }
                    stage.toFront();
                    stage.requestFocus();
                }
            }
            case OPEN_PANEL -> showCodingAgentPanelFor(pane);
            case SEND_ENTER -> sendCodingAgentKey(pane, KeyChord.ENTER);
            case SEND_ESC -> sendCodingAgentKey(pane, KeyChord.ESC);
            case INTERRUPT -> sendCodingAgentKey(pane, KeyChord.CTRL_C);
        }
    }

    private void sendCodingAgentKey(PaneRef pane, KeyChord chord) {
        CodingAgentActions actions = app.getCodingAgentActions();
        if (actions == null || pane == null) {
            return;
        }
        try {
            actions.sendKey(pane, chord);
        } catch (CodingAgentActionException e) {
            String message = switch (e.code()) {
                case PANE_NOT_FOUND -> I18n.get("codingAgent.panel.error.paneNotFound");
                case NOT_CONNECTED -> I18n.get("codingAgent.panel.error.notConnected");
                case WRITE_FAILED -> I18n.get("codingAgent.panel.error.writeFailed", e.getMessage());
                case AGENT_BLOCKED -> I18n.get("codingAgent.panel.prompt.blocked");
                case HOST_SHORTCUT_CONFLICT -> I18n.get("codingAgent.panel.prompt.hostShortcut", e.getMessage());
                case EMPTY_INPUT -> null;
            };
            if (message != null) {
                updateStatus(message);
            }
        }
    }
    
    private void openProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.file.openProject"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            openProjectFile(file.toPath());
        }
    }

    /**
     * Opens the project file {@code path} in this window, replacing its tabs (unsaved editors ask
     * first): File › Open Project… after the file dialog, and File › Open Recent. A project that
     * opened is remembered for File › Open Recent.
     */
    private void openProjectFile(Path path) {
        try {
            Project project = projectManager.loadProject(path);
            // Loading replaces every tab: hosted snippet editors and file editors ask about
            // unsaved work first.
            if (!confirmHostedTabsClose()) {
                return;
            }
            restoreProject(project, this);
            rememberRecentProject(path);
            Telemetry.track(TelemetryEvents.PROJECT_ACTION, Map.of("action", "open"));
            updateStatus(I18n.get("status.projectLoaded", project.getName()));
        } catch (Exception e) {
            logger.error("Failed to load project", e);
            showError(I18n.get("error.title"), I18n.get("error.projectLoadFailed", e.getMessage()));
            // A file gone since the menu was built leaves File › Open Recent.
            refreshRecentProjects();
        }
    }
    
    private void saveProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.file.saveProject"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            String path = file.getPath();
            if (!path.endsWith(ProjectManager.getProjectExtension())) {
                path += ProjectManager.getProjectExtension();
                file = new File(path);
            }
            
            try {
                Project project = captureAllWindows(this, CaptureOptions.PROJECT);
                
                // Show project settings dialog
                ProjectSettingsDialog dialog = new ProjectSettingsDialog(stage, project);
                Optional<Project> result = dialog.showAndWait();
                
                if (result.isPresent()) {
                    projectManager.saveProject(result.get(), file.toPath());
                    rememberRecentProject(file.toPath());
                    Telemetry.track(TelemetryEvents.PROJECT_ACTION, Map.of("action", "save"));
                    updateStatus(I18n.get("status.projectSaved", file.getName()));
                }
            } catch (Exception e) {
                logger.error("Failed to save project", e);
                showError(I18n.get("error.title"), I18n.get("error.projectSaveFailed", e.getMessage()));
            }
        }
    }
    
    // ---- Session snapshot ---------------------------------------------------------------------

    /**
     * Starts keeping the session snapshot (see {@link SessionAutosaveCoordinator}): loads what the
     * last run left and makes it the previous session ({@link SessionSnapshotStore#startUp}), starts
     * the Recently Closed list with the list it kept, and from now on writes the open windows and tabs
     * whenever they change. {@code KorTTYApplication} calls it once, before the first window. FX thread.
     *
     * @param writesAllowed whether korTTY may write its files now; not while a restored backup with
     *     another master password waits for the restart
     * @return the coordinator, whose {@link SessionAutosaveCoordinator#sealOnShutdown} the shutdown runs
     */
    public static SessionAutosaveCoordinator startSessionAutosave(SessionSnapshotStore store,
                                                                  java.util.function.BooleanSupplier writesAllowed) {
        java.util.Objects.requireNonNull(store, "store");
        if (sessionAutosave != null) {
            return sessionAutosave;
        }
        SessionSnapshotStore.StartupState startup = store.startUp();
        KorTTYApplication application = KorTTYApplication.getInstance();
        if (application != null && application.getConfigManager() != null) {
            // Ids only: each closed tab comes back with its saved connection as it is now.
            closedTabHistory.seed(ClosedTabSnapshots.fromSnapshot(startup.recentlyClosed(),
                    id -> application.getConfigManager().getConnectionById(id)));
        }
        java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-session-autosave");
            thread.setDaemon(true);
            return thread;
        });
        sessionAutosave = new SessionAutosaveCoordinator(store, startup,
                new SessionAutosaveCoordinator.Environment(MainWindow::captureSession, writesAllowed,
                        Platform::isFxApplicationThread, System::currentTimeMillis, KorTTYApplication.getAppVersion()),
                writer, MainWindow::sessionAutosaveTimers);
        logger.info("Session snapshot started (previous session available: {}, saving: {})",
                store.isPreviousAvailable(), store.canWrite());
        startSessionScrollback(store, startup, writesAllowed);
        return sessionAutosave;
    }

    /**
     * Starts saving each terminal pane's output for the session snapshot (Settings › Window › Session
     * Restore, off by default) and removes the files no saved session names any more. FX thread.
     */
    private static void startSessionScrollback(SessionSnapshotStore store, SessionSnapshotStore.StartupState startup,
                                               java.util.function.BooleanSupplier writesAllowed) {
        KorTTYApplication application = KorTTYApplication.getInstance();
        if (application == null || sessionScrollback != null) {
            return;
        }
        java.util.Set<String> startupRefs = new java.util.HashSet<>();
        if (startup.last() != null) {
            startupRefs.addAll(de.kortty.core.SessionScrollbackStore.referencedBy(startup.last().getProject()));
        }
        if (!startup.rotated() && store.isPreviousAvailable()) {
            store.loadPrevious().ifPresent(previous ->
                    startupRefs.addAll(de.kortty.core.SessionScrollbackStore.referencedBy(previous.getProject())));
        }
        sessionScrollback = new SessionScrollbackCoordinator(
                new de.kortty.core.SessionScrollbackStore(KorTTYApplication.getConfigDirectory()),
                new SessionScrollbackCoordinator.Environment(
                        () -> scrollbackSettings(application) != null
                                && scrollbackSettings(application).isSessionRestoreScrollback(),
                        () -> scrollbackSettings(application) != null
                                ? scrollbackSettings(application).getSessionRestoreScrollbackLines()
                                : de.kortty.core.ScrollbackSnapshotCodec.DEFAULT_LINES,
                        () -> application.getMasterPasswordManager() != null
                                ? application.getMasterPasswordManager().getDerivedKey() : null,
                        // Only the korTTY that writes the session snapshot writes or deletes its output files.
                        () -> writesAllowed.getAsBoolean() && store.canWrite(),
                        MainWindow::openTerminalViews,
                        () -> {
                            SessionSnapshot saved = sessionAutosave != null ? sessionAutosave.savedSnapshot() : null;
                            return saved != null
                                    ? de.kortty.core.SessionScrollbackStore.referencedBy(saved.getProject())
                                    : java.util.Set.of();
                        },
                        MainWindow::markSessionDirty,
                        Platform::runLater),
                startupRefs);
        sessionScrollback.start();
    }

    private static de.kortty.model.GlobalSettings scrollbackSettings(KorTTYApplication application) {
        return application.getGlobalSettingsManager() != null ? application.getGlobalSettingsManager().getSettings() : null;
    }

    /**
     * Adds {@code text} as a note to the session journal of every capturing terminal on the
     * connection {@code connectionId} (the SFTP manager's "sudo-edit <path>" line). Terminals that
     * do not capture get nothing. FX thread.
     */
    static void noteInConnectionJournals(String connectionId, String text) {
        if (connectionId == null || text == null) {
            return;
        }
        for (TerminalView view : openTerminalViews()) {
            ServerConnection viewConnection = view != null ? view.getConnection() : null;
            if (viewConnection == null || !connectionId.equals(viewConnection.getId())) {
                continue;
            }
            de.kortty.core.SessionJournalSession journal = view.getSessionJournalSession();
            if (journal != null && journal.isActive()) {
                journal.appendUserNote(text);
            }
        }
    }

    /** The views of every terminal tab in every open window. FX thread. */
    private static List<TerminalView> openTerminalViews() {
        List<TerminalView> views = new ArrayList<>();
        for (MainWindow window : List.copyOf(openWindows)) {
            for (Tab tab : window.tabPane.getTabs()) {
                if (tab instanceof TerminalTab terminalTab) {
                    views.add(terminalTab.getTerminalView());
                }
            }
        }
        return views;
    }

    /**
     * Settings › Window › Session Restore › restoring the output was turned on or off; off deletes
     * every saved output file at once. FX thread.
     */
    public static void sessionRestoreScrollbackChanged(boolean enabled) {
        if (sessionScrollback != null) {
            sessionScrollback.settingChanged(enabled);
        }
        markSessionDirty();
    }

    /**
     * Restores the output the session snapshot saved for a restored terminal tab's panes, when the
     * setting is on. With the vault locked nothing is read, and the status bar says so. FX thread,
     * before the tab connects.
     */
    private void restoreSavedScrollback(TerminalView view, SessionState sessionState) {
        try {
            java.util.Set<String> refs = de.kortty.core.SessionScrollbackStore.referencedBy(sessionState);
            if (refs.isEmpty() || sessionScrollback == null) {
                return;
            }
            java.util.function.Function<String, java.util.Optional<de.kortty.core.ScrollbackSnapshotCodec.Decoded>> loader =
                    sessionScrollback.loader();
            if (loader == null) {
                KorTTYApplication application = KorTTYApplication.getInstance();
                de.kortty.model.GlobalSettings settings = application != null ? scrollbackSettings(application) : null;
                if (settings != null && settings.isSessionRestoreScrollback()) {
                    logger.info("The saved terminal output is not restored: the master-password vault is locked");
                    updateStatus(I18n.get("status.sessionScrollback.vaultLocked"));
                }
                return;
            }
            view.setRestoredScrollback(sessionState.getScrollbackRef(), loader);
        } catch (RuntimeException e) {
            // The saved output is a convenience; the tab opens without it.
            logger.warn("The saved terminal output could not be prepared: {}", e.toString());
        }
    }

    /**
     * The quiet-time timer of the session autosave. Only a short pause runs, and only after a change:
     * an animation that ran all the time would keep JavaFX drawing frames while korTTY sits idle.
     */
    private static SessionAutosaveCoordinator.Timers sessionAutosaveTimers(Runnable due) {
        javafx.animation.PauseTransition quiet = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(SessionAutosaveCoordinator.DEBOUNCE_MILLIS));
        quiet.setOnFinished(event -> due.run());
        return new SessionAutosaveCoordinator.Timers() {
            @Override
            public void restartDebounce() {
                quiet.playFromStart();
            }

            @Override
            public void runSoon() {
                quiet.stop();
                Platform.runLater(due);
            }

            @Override
            public void stop() {
                quiet.stop();
            }
        };
    }

    /**
     * The open session as the session snapshot keeps it: every open window that has a tab to keep, in
     * the order the windows were opened, without screen text, with the tabs that still wait in a
     * restore bar, and the Recently Closed list as ids. FX thread.
     */
    private static SessionAutosaveCoordinator.Capture captureSession() {
        Project project = new Project(SESSION_PROJECT_NAME);
        project.setAutoReconnect(true);
        for (MainWindow window : List.copyOf(openWindows)) {
            WindowState windowState = window.captureWindowState(CaptureOptions.SESSION);
            if (!windowState.getTabs().isEmpty()) {
                project.addWindow(windowState);
            }
        }
        return new SessionAutosaveCoordinator.Capture(project, ClosedTabSnapshots.toSnapshot(closedTabHistory.entries()));
    }

    /** The windows or tabs changed: the session snapshot follows after a short quiet time. FX thread. */
    private static void markSessionDirty() {
        if (sessionAutosave != null) {
            sessionAutosave.markDirty();
        }
    }

    /**
     * korTTY is about to end: writes the session snapshot it quits with while every tab is still open,
     * and seals it, so the windows that close afterwards cannot change it. FX thread.
     */
    private static void sealSessionSnapshotForExit() {
        if (sessionAutosave != null) {
            sessionAutosave.saveAndSeal();
        }
        // After the snapshot: it named the files, now the panes' last output goes into them.
        if (sessionScrollback != null) {
            sessionScrollback.flushForExit();
        }
    }

    /**
     * The saved tabs this window's restore has not opened yet, by saved position: the tabs waiting in
     * the restore bar and the remote tabs still downloading.
     */
    private java.util.SortedMap<Integer, SessionState> waitingSavedTabs() {
        java.util.SortedMap<Integer, SessionState> waiting = new java.util.TreeMap<>();
        if (activeRestore == null) {
            return waiting;
        }
        for (RestoreAttention.Item<DeferredTab> item : restoreAttention.items()) {
            DeferredTab deferred = item.tab();
            if (deferred.restore() == activeRestore && deferred.state() != null) {
                waiting.putIfAbsent(deferred.index(), deferred.state());
            }
        }
        activeRestore.pendingTabs().forEach(waiting::putIfAbsent);
        return waiting;
    }

    /** Greys out File › Restore Previous Session while there is no previous session to open. */
    private void syncRestorePreviousSessionMenuItems() {
        boolean available = sessionAutosave != null && sessionAutosave.canRestorePrevious();
        for (MenuItem item : restorePreviousSessionMenuItems) {
            item.setDisable(!available);
        }
    }

    /**
     * File › Restore Previous Session: opens the windows and tabs of the session before this start,
     * which korTTY saved while it ran (see {@link SessionSnapshotStore}). Its first window goes into
     * this window when this window has no tab, else into a new window; every further window opens in
     * a new window, and the windows already open keep their tabs. The tabs open like those of a
     * project with Auto-Reconnect, asking nothing: what needs a password, a new temporary SSH key or
     * the locked vault waits in the restore bar. Offered once per run. {@code trigger} says what
     * started it, for the anonymous {@code session_restored} event only.
     */
    private void restorePreviousSession(RestoreTrigger trigger) {
        if (sessionAutosave == null || !sessionAutosave.canRestorePrevious()) {
            updateStatus(I18n.get("session.restore.previous.none"));
            syncRestorePreviousSessionMenuItems();
            return;
        }
        Project project = sessionAutosave.loadPrevious().map(SessionSnapshot::getProject).orElse(null);
        List<WindowState> windows = ProjectRestoreOrder.windowsToRestore(project);
        if (windows.isEmpty() || SessionSnapshotStore.restorableTabs(project) == 0) {
            updateStatus(I18n.get("session.restore.previous.none"));
            syncRestorePreviousSessionMenuItems();
            return;
        }
        // Once per run: a second restore would open every tab a second time.
        sessionAutosave.markPreviousRestored();
        hideSessionRestoreOffers();
        // On disk before the first tab opens: a crash during the restore is seen at the next start.
        sessionAutosave.beginSessionRestore();
        MainWindow target = this;
        if (!tabPane.getTabs().isEmpty()) {
            // This window keeps its tabs; the previous session's first window gets a window of its own.
            WindowState first = windows.get(0);
            target = new MainWindow(new Stage());
            target.show(first.getGeometry(), first.getDashboardVisible() == null);
        }
        logger.info("Restoring the previous session: {} window(s), {} tab(s)",
                windows.size(), SessionSnapshotStore.restorableTabs(project));
        restoreProject(project, target);
        reportSessionRestored(trigger, windows.size(), SessionSnapshotStore.restorableTabs(project));
        target.updateStatus(I18n.get("session.restore.previous.done"));
        for (MainWindow window : new ArrayList<>(openWindows)) {
            window.syncRestorePreviousSessionMenuItems();
        }
        markSessionStableLater();
    }

    /**
     * The anonymous {@code session_restored} event: the Session Restore setting, what started the
     * restore, and how many windows and tabs it opened in coarse buckets. Never a host or a title.
     */
    private void reportSessionRestored(RestoreTrigger trigger, int windows, int tabs) {
        try {
            de.kortty.model.SessionRestoreMode mode = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings().getSessionRestoreMode() : null;
            Telemetry.track(TelemetryEvents.SESSION_RESTORED,
                de.kortty.telemetry.TerminalUxTelemetry.sessionRestored(mode, trigger, windows, tabs));
        } catch (RuntimeException e) {
            logger.debug("Session restore could not be reported: {}", e.toString());
        }
    }

    /**
     * A minute after a restore of the previous session, the session snapshot marks it stable: from
     * then on, korTTY ending is no longer taken for a crash the restore caused.
     */
    private static void markSessionStableLater() {
        javafx.animation.PauseTransition stableAfter = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(SessionAutosaveCoordinator.STABLE_AFTER_MILLIS));
        stableAfter.setOnFinished(event -> {
            if (sessionAutosave != null) {
                sessionAutosave.markStable();
            }
        });
        stableAfter.play();
    }

    /**
     * The startup half of the session restore, by Settings › Window › Session Restore: offers the
     * previous session in a bar above this window's status line ({@code ask}), restores it once no
     * modal dialog is open ({@code auto}, which offers instead after korTTY ended unexpectedly right
     * after the last restore), or does nothing ({@code off}); see {@link SessionRestoreDecision} and
     * {@link SessionRestoreCoordinator}. Only the session the last run left is offered, and only by
     * the korTTY that holds the snapshot lock. {@code KorTTYApplication} posts it once, after the
     * telemetry consent prompt. FX thread.
     */
    public void startSessionRestore(de.kortty.model.SessionRestoreMode mode) {
        if (sessionAutosave == null) {
            return;
        }
        SessionSnapshotStore.StartupState startup = sessionAutosave.startup();
        SessionRestoreDecision.Facts facts = SessionRestoreDecision.Facts.of(startup);
        SessionRestoreDecision.Decision decision = SessionRestoreDecision.decide(mode, facts);
        logger.info("Session restore at startup: mode {}, {}{} (previous session: {} window(s), {} tab(s))",
                mode != null ? mode.id() : "default", decision.action(), decision.afterCrash() ? " after a crash" : "",
                facts.windows(), facts.tabs());
        SessionSnapshot previous = startup != null ? startup.last() : null;
        new SessionRestoreCoordinator(new SessionRestoreCoordinator.Host() {
            @Override
            public boolean modalShowing() {
                return isModalDialogShowing();
            }

            @Override
            public boolean previousRestorable() {
                return sessionAutosave != null && sessionAutosave.canRestorePrevious();
            }

            @Override
            public void keepPreviousSession() {
                sessionAutosave.carryForward(previous);
            }

            @Override
            public void offer(String text) {
                if (openWindows.contains(MainWindow.this)) {
                    showSessionRestoreOffer(text);
                }
            }

            @Override
            public void restore() {
                if (openWindows.contains(MainWindow.this)) {
                    logger.info("Restoring the previous session automatically");
                    restorePreviousSession(RestoreTrigger.AUTO);
                }
            }

            @Override
            public void schedule(long delayMillis, Runnable task) {
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(delayMillis));
                delay.setOnFinished(event -> task.run());
                delay.play();
            }
        }, decision, facts, I18n::get).start();
    }

    /** Whether a modal dialog (the consent prompt, a confirmation, a password question) is showing. */
    private static boolean isModalDialogShowing() {
        for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows())) {
            if (window.isShowing() && window instanceof Stage stage
                    && stage.getModality() != javafx.stage.Modality.NONE) {
                return true;
            }
        }
        return false;
    }

    /** Shows the startup offer above the status line of this window. */
    private void showSessionRestoreOffer(String text) {
        if (sessionRestoreOfferBar == null) {
            sessionRestoreOfferBar = new SessionRestoreOfferBar();
            sessionRestoreOfferBar.setOnRestore(() -> {
                sessionRestoreOfferBar.hideOffer();
                restorePreviousSession(RestoreTrigger.OFFER);
            });
            sessionRestoreOfferBar.setOnDismiss(() -> {
                sessionRestoreOfferBar.hideOffer();
                // The previous session is not offered again at the next start; the File menu keeps it.
                if (sessionAutosave != null) {
                    sessionAutosave.dropCarriedForward();
                }
            });
            VBox.setMargin(sessionRestoreOfferBar, new javafx.geometry.Insets(0, 0, 4, 0));
            statusBar.getChildren().add(0, sessionRestoreOfferBar);
        }
        sessionRestoreOfferBar.showOffer(text);
    }

    /** The previous session was restored: no window offers it any more. */
    private static void hideSessionRestoreOffers() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            if (window.sessionRestoreOfferBar != null) {
                window.sessionRestoreOfferBar.hideOffer();
            }
        }
    }

    /**
     * What a window capture takes. A project keeps the last visible screen of each terminal tab
     * (written to {@code history/}, never into the project file); a capture without the screen keeps
     * the layout only.
     *
     * @param includeScreen      the last visible screen and the command timestamps of each terminal tab
     * @param includeWaitingTabs the saved tabs a restore has not opened yet, at their saved places
     *                           (see {@link ProjectRestoreOrder#withWaitingTabs})
     * @param includeWorkingDirectories the working directory of each local-shell pane, a field only
     *                           the session snapshot of this device keeps; a project file never does
     */
    record CaptureOptions(boolean includeScreen, boolean includeWaitingTabs, boolean includeWorkingDirectories) {
        /** File › Save Project: the layout and the last visible screen of each terminal tab. */
        static final CaptureOptions PROJECT = new CaptureOptions(true, false, false);
        /**
         * The session snapshot: the layout without any screen text, and with the tabs that still wait
         * in the restore bar or for their download, so a restart in the middle of a restore keeps them.
         */
        static final CaptureOptions SESSION = new CaptureOptions(false, true, true);
    }

    /**
     * Every open window as one project: {@code first}, the window Save Project was chosen in, then
     * the other windows in the order they were opened (see {@link ProjectRestoreOrder#captureOrder}).
     * A window other than {@code first} without a tab a project keeps is left out. FX thread.
     */
    static Project captureAllWindows(MainWindow first, CaptureOptions options) {
        Project project = new Project("New Project");
        for (MainWindow window : ProjectRestoreOrder.captureOrder(first, openWindows)) {
            WindowState windowState = window.captureWindowState(options);
            if (ProjectRestoreOrder.savesWindow(window == first, windowState.getTabs().size())) {
                project.addWindow(windowState);
            }
        }
        return project;
    }

    /**
     * This window as a project keeps it: its normal bounds with the maximized flag, the dashboard,
     * every terminal, SFTP Manager, file editor and image viewer tab in tab order, each with a session
     * id, and the active tab by that id (with its index for older korTTY versions). FX thread.
     */
    private WindowState captureWindowState(CaptureOptions options) {
        WindowState windowState = new WindowState(UUID.randomUUID().toString());
        windowState.setGeometry(MainWindowGeometrySupport.capture(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                stage.isMaximized(), stage.isFullScreen(), stage.isIconified(), lastNormalGeometry));
        
        // Save dashboard state (visibility)
        windowState.setDashboardVisible(dashboardVisible);
        
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        SessionState active = null;
        List<Integer> savedPositions = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            SessionState sessionState = captureTabState(tab, options);
            if (sessionState == null) {
                // AI result tabs and tool tabs stay in this session only.
                continue;
            }
            if (sessionState.getSessionId() == null || sessionState.getSessionId().isBlank()) {
                // Every saved tab has an id, so opening the project can find the active one by it.
                sessionState.setSessionId(UUID.randomUUID().toString());
            }
            windowState.addTab(sessionState);
            savedPositions.add(activeRestore != null ? activeRestore.savedIndex(tab) : null);
            if (tab == selected) {
                active = sessionState;
            }
        }
        if (options.includeWaitingTabs()) {
            windowState.setTabs(new ArrayList<>(ProjectRestoreOrder.withWaitingTabs(
                    windowState.getTabs(), savedPositions, waitingSavedTabs())));
        }
        
        windowState.setActiveSessionId(active != null ? active.getSessionId() : null);
        windowState.setActiveTabIndex(active != null ? windowState.getTabs().indexOf(active) : -1);
        return windowState;
    }

    /** The saved state of one tab, or {@code null} for a tab a project does not keep. */
    private SessionState captureTabState(Tab tab, CaptureOptions options) {
        SessionState sessionState = null;
        if (tab instanceof TerminalTab terminalTab) {
            ServerConnection connection = terminalTab.getConnection();
            sessionState = new SessionState(
                    java.util.UUID.randomUUID().toString(),
                    connection.getId()
            );
            sessionState.setTabType(SessionState.TabType.TERMINAL);
            // A copy: the project must not share, and later write out, the tab's live settings.
            sessionState.setSettings(connection.getSettings() != null
                    ? new ConnectionSettings(connection.getSettings())
                    : null);
            if (options.includeScreen()) {
                sessionState.setTerminalHistory(terminalTab.getTerminalView().getTerminalHistory());
                sessionState.setTerminalTimestamps(terminalTab.getTerminalView().getPrimaryTimestampEntries());
            }
            sessionState.setTerminalEffectPluginId(terminalTab.getTerminalView().getTerminalEffectPluginId());
            double terminalEffectAnimationSpeed = terminalTab.getTerminalView().getTerminalEffectAnimationSpeed();
            if (Double.compare(terminalEffectAnimationSpeed, TerminalEffectAnimationSpeed.DEFAULT) != 0) {
                sessionState.setTerminalEffectAnimationSpeed(terminalEffectAnimationSpeed);
            }
            sessionState.setGroup(terminalTab.getGroup()); // Save tab group (not connection group)
            // The name the user gave the tab; null keeps following the connection's name.
            sessionState.setTabTitle(terminalTab.getCustomTitle());
            // Save current font size (zoom level) - may differ from settings when user zoomed
            int currentFontSize = terminalTab.getTerminalView().getCurrentFontSize();
            // Compared with the size the connection opens with: one that follows the global settings
            // may still hold stale values of its own, which would pin today's global size as an
            // override and ignore a later change of the global font size.
            if (connection.getSettings() == null
                    || currentFontSize != sessionFontSizeBaseline(connection.getSettings())) {
                sessionState.setFontSizeOverride(currentFontSize);
            }
            // Save split pane structure (if terminal has splits). Only the session snapshot keeps
            // where each local shell is; neither the live read (lsof) nor anything else blocks here.
            // The file each pane's saved output goes to; only the session snapshot names them, and only
            // while Settings › Window › Session Restore keeps the output and the vault is open.
            TerminalView view = terminalTab.getTerminalView();
            java.util.function.Function<com.sithtermfx.ui.SithTermFxWidget, String> scrollbackRefOf =
                    pane -> sessionScrollback != null ? sessionScrollback.refOf(view, pane) : null;
            de.kortty.model.SplitPaneState splitState = options.includeWorkingDirectories()
                    ? view.getSessionSplitState(scrollbackRefOf)
                    : view.getSplitState();
            if ((splitState == null || !splitState.isSplit()) && terminalTab.getPendingSplitLayout() != null) {
                // A restored tab that has not connected yet has not reopened its split panes: it keeps
                // the layout it is waiting to rebuild, so saving now does not lose it. A copy: saving
                // a project strips the session-only fields from what it saves, never from the layout
                // the tab still rebuilds.
                splitState = terminalTab.getPendingSplitLayout().deepCopy();
                if (!options.includeWorkingDirectories()) {
                    ProjectLeafFieldSanitizer.sanitize(splitState, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);
                }
            }
            if (options.includeWorkingDirectories()) {
                // The first pane's local shell directory; a remote tab's directory is never saved.
                sessionState.setCurrentDirectory(terminalTab.getTerminalView().getSessionWorkingDirectory());
                sessionState.setScrollbackRef(view.getSessionScrollbackRef(scrollbackRefOf));
            }
            if (splitState != null) {
                sessionState.setSplitPaneState(splitState);
                logCapturedTab(options, "Saving split structure for tab: {}", connection.getDisplayName());
            }
        } else if (tab instanceof SFTPManagerTab sftpTab) {
            sessionState = sftpTab.createSessionState();
            logCapturedTab(options, "Saving SFTP Manager tab: {}", sftpTab.getText());
        } else if (tab instanceof FileEditorTab editorTab) {
            sessionState = editorTab.createSessionState();
            // The connection the remote file was opened over, not merely the first SFTP tab's.
            if (editorTab.isRemote()) {
                sessionState.setConnectionId(SftpSessionRestoreSupport.savedConnectionId(editorTab.getSftpSession()));
            }
            logCapturedTab(options, "Saving File Editor tab: {}", editorTab.getText());
        } else if (tab instanceof ImageViewerTab viewerTab) {
            sessionState = viewerTab.createSessionState();
            // The connection the remote image was opened over, not merely the first SFTP tab's.
            if (viewerTab.isRemote()) {
                sessionState.setConnectionId(SftpSessionRestoreSupport.savedConnectionId(viewerTab.getSftpSession()));
            }
            logCapturedTab(options, "Saving Image Viewer tab: {}", viewerTab.getText());
        }
        return sessionState;
    }
    
    /**
     * Logs what a capture saves: at INFO for Save Project, at DEBUG for the session snapshot, which
     * captures every few seconds and would otherwise fill the log.
     */
    private static void logCapturedTab(CaptureOptions options, String message, Object detail) {
        if (options.includeScreen()) {
            logger.info(message, detail);
        } else {
            logger.debug(message, detail);
        }
    }

    /**
     * Tells the status bar when a restored tab could not reopen all its split panes, and why; a
     * complete restore says nothing.
     */
    private void reportSplitLayoutRestore(String tabName, SplitLayoutRestorePlan.Summary summary) {
        if (summary.missingPanes() <= 0) {
            return;
        }
        updateStatus(I18n.get("project.splitRestore.incomplete", tabName, summary.missingPanes(),
                summary.plannedPanes(), summary.reasonsText(I18n::get)));
    }

    /**
     * Opens {@code project}: its first window in {@code firstWindow}, whose tabs it replaces, and every
     * further window in a new window. Windows that are already open keep their tabs. The caller asked
     * about unsaved editors in {@code firstWindow} first. FX thread.
     */
    static void restoreProject(Project project, MainWindow firstWindow) {
        // The session snapshot waits until the windows have their tabs; a capture now would hold
        // only some of them.
        if (sessionAutosave != null) {
            sessionAutosave.beginRestore();
        }
        try {
            List<WindowState> windows = ProjectRestoreOrder.windowsToRestore(project);
            if (project.getWindows() != null && project.getWindows().size() > ProjectRestoreOrder.MAX_WINDOWS) {
                logger.warn("Project {} has {} windows; opening the first {}", project.getName(),
                        project.getWindows().size(), ProjectRestoreOrder.MAX_WINDOWS);
            }
            if (windows.isEmpty()) {
                firstWindow.activeRestore = null;
                firstWindow.closeAllTabs();
                firstWindow.clearRestoreAttention();
                return;
            }
            firstWindow.restoreWindowState(windows.get(0), project, true);
            for (WindowState windowState : windows.subList(1, windows.size())) {
                MainWindow window = new MainWindow(new Stage());
                // Opens at the saved bounds right away; a window without a saved dashboard state
                // follows the settings like any new window.
                window.show(windowState.getGeometry(), windowState.getDashboardVisible() == null);
                window.restoreWindowState(windowState, project, false);
            }
            if (windows.size() > 1 && firstWindow.stage.isShowing()) {
                // The window the project was opened from stays in front of the windows it opened.
                firstWindow.stage.toFront();
                firstWindow.stage.requestFocus();
            }
        } finally {
            if (sessionAutosave != null) {
                sessionAutosave.endRestore();
            }
        }
    }

    /**
     * Gives this window the saved {@code windowState} of {@code project}: replaces its tabs, reopens
     * the saved tabs in their saved order, sorts them into their tab groups once, sets the dashboard
     * and selects the tab that was active, once it is there (see {@link WindowRestore}). Tabs that
     * cannot open without the user are listed in the restore bar (see {@link #restoreOrDeferSavedTab}).
     * FX thread.
     *
     * @param moveWindow whether to move this window to the saved bounds; a window opened for the
     *     project was shown there already
     */
    private void restoreWindowState(WindowState windowState, Project project, boolean moveWindow) {
        // Close existing tabs
        closeAllTabs();
        // The tabs an earlier project left waiting go with its tabs.
        restoreAttention.clear();
        if (moveWindow) {
            applyProjectGeometry(windowState.getGeometry());
        }

        List<SessionState> tabs = windowState.getTabs() != null ? windowState.getTabs() : List.of();
        List<String> keys = ProjectRestoreOrder.tabKeys(tabs);
        WindowRestore restore = new WindowRestore(keys, ProjectRestoreOrder.activeTabKey(windowState, keys), tabs);
        activeRestore = restore;
        for (int index = 0; index < tabs.size(); index++) {
            SessionState sessionState = tabs.get(index);
            if (sessionState != null) {
                restoreOrDeferSavedTab(sessionState, index, project, restore);
            }
        }
        restore.syncTabsDone();
        refreshRestoreAttentionBar();

        // Restore dashboard state from project (visibility)
        if (windowState.getDashboardVisible() != null) {
            toggleDashboard(windowState.getDashboardVisible());
        }
    }

    /**
     * Moves this shown window to bounds a project saved, re-centred on the main screen when the screen
     * they were on is gone (see {@link MainWindowGeometrySupport#plan}). A window in fullscreen stays
     * in it.
     */
    private void applyProjectGeometry(WindowGeometry stored) {
        if (stored == null || stage.isFullScreen()) {
            return;
        }
        WindowGeometry geometry = MainWindowGeometrySupport.plan(stored, unifiedTitleBarEnabled).geometry();
        if (geometry == null) {
            return;
        }
        if (stage.isMaximized() && !geometry.isMaximized()) {
            stage.setMaximized(false);
        }
        MainWindowGeometrySupport.apply(stage, geometry, true);
        lastNormalGeometry = new WindowGeometry(geometry.getX(), geometry.getY(), geometry.getWidth(), geometry.getHeight());
    }

    /**
     * A tab of an opened project that did not open right away and waits in the restore bar: what
     * {@link #restoreSavedTab} needs to open it later, with the restore it belongs to.
     */
    private record DeferredTab(SessionState state, int index, Project project, WindowRestore restore) {
    }

    /**
     * Reopens the tab saved at {@code index} in this window when it can open without asking anything,
     * and lists it in the restore bar otherwise (see {@link TabRestoreTriage}): a local shell, SSH key
     * auth, a stored password, a valid temporary key and an existing local file open right away; a
     * password that is not stored, an expired temporary key, a password in the locked vault, a server
     * the policy blocks and a connection or file that is gone wait in the bar, instead of being
     * skipped silently or asked about in a dialog per tab. Without Auto-Reconnect, tabs that open
     * over a connection are skipped, as before.
     */
    private void restoreOrDeferSavedTab(SessionState sessionState, int index, Project project, WindowRestore restore) {
        if (!project.isAutoReconnect() && TabRestoreTriage.opensOverConnection(sessionState)) {
            // TODO: Create read-only tab with history display only (no connection)
            logger.info("Auto-reconnect disabled, skipping the {} tab at position {}",
                    TabRestoreTriage.tabType(sessionState), index);
            return;
        }
        TabRestoreTriage.Outcome outcome = classifySavedTab(sessionState);
        if (outcome == null) {
            return;
        }
        if (outcome.isReady()) {
            restoreSavedTab(sessionState, index, project, restore, outcome.auth());
            return;
        }
        ServerConnection connection = outcome.connection();
        String label = TabRestoreTriage.label(sessionState, connection, index, I18n::get);
        logger.info("The {} tab at position {} waits in the restore bar: {}",
                TabRestoreTriage.tabType(sessionState), index, outcome);
        restoreAttention.add(new RestoreAttention.Item<>(new DeferredTab(sessionState, index, project, restore),
                index, label, connection != null ? connection.getId() : null, outcome));
    }

    /**
     * How a saved tab can come back, decided without asking anything: the server policy first, then
     * the sign-in of {@link ConnectionAuthResolver} (non-interactive), or for a local file whether it
     * still exists.
     *
     * @return the outcome, or {@code null} for a file tab that names no file
     */
    private TabRestoreTriage.Outcome classifySavedTab(SessionState sessionState) {
        return TabRestoreTriage.classify(sessionState, this::savedConnectionOf, connectionAuthResolver(),
                MainWindow::localFileExists);
    }

    /**
     * The saved connection a restored tab opens over, or {@code null} when it no longer exists. An
     * SFTP Manager tab of an older project names its connection by name (see
     * {@link SftpSessionRestoreSupport#findConnection}).
     */
    private ServerConnection savedConnectionOf(SessionState sessionState) {
        String connectionId = sessionState.getConnectionId();
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        if (TabRestoreTriage.tabType(sessionState) == SessionState.TabType.SFTP_MANAGER) {
            // By id; projects saved before stored the connection's name.
            return SftpSessionRestoreSupport.findConnection(
                    connectionId,
                    app.getConfigManager()::getConnectionById,
                    app.getConfigManager().getConnections());
        }
        return app.getConfigManager().getConnectionById(connectionId);
    }

    private static boolean localFileExists(String filePath) {
        try {
            return filePath != null && java.nio.file.Files.exists(java.nio.file.Paths.get(filePath));
        } catch (RuntimeException e) {
            // A path this system cannot even express is as gone as a deleted file.
            return false;
        }
    }

    /**
     * Reopens the tab saved at {@code index} in this window with the sign-in {@code auth} that
     * {@link #restoreOrDeferSavedTab} found, or that the user completed from the restore bar. Tabs that
     * open right away are handed to {@code restore} at once; a remote file editor or image viewer tab
     * is announced as pending and arrives once its file has been downloaded, or never.
     *
     * @param auth the connection, password and temporary key to open with; {@code null} for a local
     *     file editor or image viewer tab
     */
    private void restoreSavedTab(SessionState sessionState, int index, Project project, WindowRestore restore,
            ConnectionAuthResolver.Resolution auth) {
        SessionState.TabType tabType = TabRestoreTriage.tabType(sessionState);
        boolean remote = TabRestoreTriage.opensOverConnection(sessionState);
        if (remote && (auth == null || !auth.isReady())) {
            logger.warn("Not restoring the {} tab at position {}: it is not signed in", tabType, index);
            return;
        }
        ServerConnection connection = remote ? auth.connection() : null;
        String password = remote ? auth.password() : null;
        de.kortty.model.TemporarySSHKey temporaryKey = remote ? auth.temporaryKey() : null;

        switch (tabType) {
            case TERMINAL -> {
                // Reconnect with the saved screen shown locally above the new session.
                String history = sessionState.getTerminalHistory();
                TerminalTab restoredTab = openConnectionAndReturnTab(
                        connection,
                        password,
                        history,
                        project.getLastModified(),
                        temporaryKey,
                        sessionState.getTerminalEffectPluginId(),
                        sessionState.getTerminalEffectAnimationSpeed(),
                        // Only the session snapshot has it: a project file's is removed on load.
                        sessionState.getCurrentDirectory(),
                        // The saved output of the tab's panes, also session snapshot only.
                        view -> restoreSavedScrollback(view, sessionState));
                if (restoredTab == null) {
                    // Blocked by the enterprise server policy.
                    return;
                }
                restore.opened(restoredTab, index);
                restoredTab.getTerminalView().restorePrimaryTimestampEntries(
                        sessionState.getTerminalTimestamps());
                // Restore tab group (not connection group); the window sorts the tabs
                // into their groups once, after the last tab (WindowRestore.syncTabsDone).
                if (sessionState.getGroup() != null && !sessionState.getGroup().trim().isEmpty()) {
                    restoredTab.setGroup(sessionState.getGroup());
                }
                // A renamed tab keeps its name; the setter cleans what the file holds.
                if (sessionState.getTabTitle() != null) {
                    restoredTab.setCustomTitle(sessionState.getTabTitle());
                }
                // Restore font size (zoom level) if saved
                Integer fontSizeOverride = sessionState.getFontSizeOverride();
                if (fontSizeOverride != null && fontSizeOverride > 0) {
                    restoredTab.getTerminalView().setFontSize(fontSizeOverride);
                }
                // The split panes come back once the tab's own session is up, and
                // only then: never again after an automatic reconnect.
                de.kortty.model.SplitPaneState splitState = sessionState.getSplitPaneState();
                if (splitState != null && splitState.isSplit()) {
                    String tabName = connection.getDisplayName();
                    logger.info("Restoring the split panes of tab {} once it is connected", tabName);
                    if (!SplitLayoutRestorePlan.plan(splitState).steps().isEmpty()) {
                        // Until the panes are back, a save keeps the layout they come back in.
                        restoredTab.setPendingSplitLayout(splitState);
                    }
                    restoredTab.addOnFirstConnected(() -> restoredTab.getTerminalView()
                            .restoreSplitLayout(splitState, summary -> {
                                restoredTab.setPendingSplitLayout(null);
                                markSessionDirty();
                                reportSplitLayoutRestore(tabName, summary);
                            }));
                }
                logger.info("Restoring tab for {} with {} chars of history",
                        connection.getDisplayName(),
                        history != null ? history.length() : 0);
            }
            case SFTP_MANAGER -> {
                Integer timeout = sessionState.getSftpAutoCloseTimeout();
                int timeoutMinutes = (timeout != null && timeout > 0) ? timeout : 0;

                // Starts in the saved folders (or the home folders when they are gone).
                SFTPManagerTab sftpTab = new SFTPManagerTab(app, connection, password, temporaryKey,
                        timeoutMinutes, this,
                        sessionState.getSftpLocalPath(), sessionState.getSftpRemotePath());
                tabPane.getTabs().add(sftpTab);
                restore.opened(sftpTab, index);

                logger.info("Restored SFTP Manager tab for {}", connection.getDisplayName());
            }
            case FILE_EDITOR -> {
                String filePath = sessionState.getEditorFilePath();

                if (filePath != null) {
                    if (remote) {
                        restore.pending(index);
                        // Open an SFTP session of its own and download the file;
                        // the session closes with the tab.
                        new Thread(() -> {
                            de.kortty.core.SFTPSession sftpSession = null;
                            try {
                                sftpSession = openOwnedSftpSession(connection, password, temporaryKey);

                                byte[] content = sftpSession.downloadFileBytes(filePath);
                                String filename = java.nio.file.Paths.get(filePath).getFileName().toString();

                                de.kortty.core.SFTPSession owned = sftpSession;
                                Platform.runLater(() -> restore.lateTabReady(owned, index, () -> {
                                    FileEditorTab editorTab = new FileEditorTab(filename, filePath, owned, content);
                                    logger.info("Restored remote file editor: {}", filePath);
                                    return editorTab;
                                }));
                            } catch (Exception e) {
                                logger.error("Failed to restore remote file editor", e);
                                if (sftpSession != null) {
                                    closeOwnedSftpSession(sftpSession);
                                }
                                Platform.runLater(() -> restore.lateTabDone(index));
                            }
                        }, "SFTP-Restore-Editor").start();
                    } else {
                        // Local file
                        try {
                            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                            if (java.nio.file.Files.exists(path)) {
                                FileEditorTab editorTab = new FileEditorTab(path);
                                tabPane.getTabs().add(editorTab);
                                restore.opened(editorTab, index);
                                logger.info("Restored local file editor: {}", filePath);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to restore local file editor", e);
                        }
                    }
                }
            }
            case IMAGE_VIEWER -> {
                String filePath = sessionState.getImageFilePath();

                if (filePath != null) {
                    if (remote) {
                        restore.pending(index);
                        // Open an SFTP session of its own and download the image;
                        // the session closes with the tab.
                        new Thread(() -> {
                            de.kortty.core.SFTPSession sftpSession = null;
                            try {
                                sftpSession = openOwnedSftpSession(connection, password, temporaryKey);

                                byte[] imageData = sftpSession.downloadFileBytes(filePath);
                                String filename = java.nio.file.Paths.get(filePath).getFileName().toString();

                                de.kortty.core.SFTPSession owned = sftpSession;
                                Platform.runLater(() -> restore.lateTabReady(owned, index, () -> {
                                    ImageViewerTab viewerTab = new ImageViewerTab(filename, filePath, owned, imageData);
                                    logger.info("Restored remote image viewer: {}", filePath);
                                    return viewerTab;
                                }));
                            } catch (Exception e) {
                                logger.error("Failed to restore remote image viewer", e);
                                if (sftpSession != null) {
                                    closeOwnedSftpSession(sftpSession);
                                }
                                Platform.runLater(() -> restore.lateTabDone(index));
                            }
                        }, "SFTP-Restore-Image").start();
                    } else {
                        // Local image
                        try {
                            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                            if (java.nio.file.Files.exists(path)) {
                                ImageViewerTab viewerTab = new ImageViewerTab(path);
                                tabPane.getTabs().add(viewerTab);
                                restore.opened(viewerTab, index);
                                logger.info("Restored local image viewer: {}", filePath);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to restore local image viewer", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Puts the restore bar into the status bar, above the status line. It stays hidden until a project
     * leaves tabs waiting.
     */
    private void installRestoreAttentionBar() {
        restoreAttentionBar = new RestoreAttentionBar();
        restoreAttentionBar.setOnConnect(() -> connectDeferredTabs(null));
        restoreAttentionBar.setOnUnlock(this::unlockVaultForDeferredTabs);
        restoreAttentionBar.setOnDismiss(this::clearRestoreAttention);
        VBox.setMargin(restoreAttentionBar, new javafx.geometry.Insets(0, 0, 4, 0));
        statusBar.getChildren().add(0, restoreAttentionBar);
    }

    /** Shows the restore bar with the tabs that wait now, or hides it when none does. */
    private void refreshRestoreAttentionBar() {
        // The tabs waiting in the bar stay part of the session snapshot until they open or are dismissed.
        markSessionDirty();
        if (restoreAttentionBar == null) {
            return;
        }
        if (restoreAttention.isEmpty()) {
            restoreAttentionBar.hideBar();
            return;
        }
        List<RestoreAttentionBar.Line> lines = new ArrayList<>();
        for (RestoreAttention.Item<DeferredTab> item : restoreAttention.items()) {
            lines.add(new RestoreAttentionBar.Line(restoreAttention.itemText(item, I18n::get),
                    item.classification().waitsForUser() ? () -> connectDeferredTabs(item) : null));
        }
        boolean vaultLocked = VaultUnlockSupport.isLocked(app.getMasterPasswordManager());
        restoreAttentionBar.showBar(restoreAttention.summary(I18n::get), restoreAttention.offersConnect(),
                restoreAttention.offersUnlock(vaultLocked), lines, connectingDeferredTabs);
    }

    /** Dismiss on the restore bar, or another project in this window: forgets the waiting tabs. */
    private void clearRestoreAttention() {
        restoreAttention.clear();
        refreshRestoreAttentionBar();
    }

    /**
     * Connect… on the restore bar: asks for each waiting tab's password, new temporary SSH key or
     * vault unlock in turn — once per connection: the other tabs of that connection open with what
     * the user gave — and opens it. The server policy is checked before every question; a server it
     * now blocks, or a connection deleted meanwhile, moves to that part of the list without a
     * dialog. Cancelling a question stops; that tab and the ones after it keep waiting.
     *
     * @param only the one tab chosen in <b>Details</b>, or {@code null} for every waiting tab
     */
    private void connectDeferredTabs(RestoreAttention.Item<DeferredTab> only) {
        if (connectingDeferredTabs) {
            return;
        }
        connectingDeferredTabs = true;
        refreshRestoreAttentionBar();
        try {
            reopenDeferredTabs(() -> {
                if (only != null) {
                    connectDeferredTab(only);
                    return;
                }
                Optional<RestoreAttention.Item<DeferredTab>> next;
                while ((next = restoreAttention.nextToConnect()).isPresent()) {
                    if (!connectDeferredTab(next.get())) {
                        break;
                    }
                }
            });
        } finally {
            connectingDeferredTabs = false;
            refreshRestoreAttentionBar();
        }
    }

    /**
     * Asks for what one waiting tab needs and opens it, with the other tabs of its connection.
     *
     * @return {@code false} when the user cancelled the question; the tab keeps waiting
     */
    private boolean connectDeferredTab(RestoreAttention.Item<DeferredTab> item) {
        // Out of the list before any question: an unlock from the question retries the tabs waiting
        // for the vault, and must not open this one a second time.
        if (!restoreAttention.remove(item)) {
            return true;
        }
        DeferredTab tab = item.tab();
        if (tab.restore() != activeRestore) {
            return true;
        }
        ConnectionAuthResolver.Resolution auth =
                connectionAuthResolver().resolve(savedConnectionOf(tab.state()), true);
        TabRestoreTriage.Outcome outcome = TabRestoreTriage.Outcome.of(auth);
        if (!outcome.isReady()) {
            restoreAttention.add(item.with(outcome));
            // A blocked or deleted connection is no question the user cancelled: go on with the next.
            return !outcome.classification().waitsForUser();
        }
        List<RestoreAttention.Item<DeferredTab>> sameConnection = restoreAttention.sameConnection(item);
        openDeferredTab(tab, auth);
        for (RestoreAttention.Item<DeferredTab> other : sameConnection) {
            if (restoreAttention.remove(other)) {
                openDeferredTab(other.tab(), auth);
            }
        }
        return true;
    }

    /**
     * After the vault was unlocked: the tabs waiting for it are sorted again without asking, and those
     * that are ready now open; the others stay listed with what they need now, for instance a
     * password that was not in the vault after all.
     */
    private void retryTabsWaitingForVault() {
        List<RestoreAttention.Item<DeferredTab>> waiting =
                restoreAttention.waiting(TabRestoreTriage.Classification.NEEDS_UNLOCK);
        if (waiting.isEmpty()) {
            refreshRestoreAttentionBar();
            return;
        }
        reopenDeferredTabs(() -> {
            for (RestoreAttention.Item<DeferredTab> item : waiting) {
                if (!restoreAttention.remove(item)) {
                    continue;
                }
                TabRestoreTriage.Outcome outcome = classifySavedTab(item.tab().state());
                if (outcome == null) {
                    continue;
                }
                if (outcome.isReady()) {
                    openDeferredTab(item.tab(), outcome.auth());
                } else {
                    restoreAttention.add(item.with(outcome));
                }
            }
        });
    }

    /**
     * Unlock Vault… on the restore bar. Success reaches every window through
     * {@link de.kortty.KorTTYApplication#onVaultUnlocked()}, which opens the tabs waiting for it.
     */
    private void unlockVaultForDeferredTabs() {
        if (!VaultUnlockSupport.isLocked(app.getMasterPasswordManager())) {
            retryTabsWaitingForVault();
            return;
        }
        VaultUnlockSupport.unlock(stage, app.getMasterPasswordManager());
        refreshRestoreAttentionBar();
    }

    /** Opens a waiting tab like the restore would have, unless another project replaced that restore. */
    private void openDeferredTab(DeferredTab tab, ConnectionAuthResolver.Resolution auth) {
        if (tab.restore() != activeRestore) {
            return;
        }
        restoreSavedTab(tab.state(), tab.index(), tab.project(), tab.restore(), auth);
    }

    /**
     * Runs {@code reopen}, which opens waiting tabs, then sorts the tab groups once (a terminal tab
     * opens in its connection's group and only then gets its saved tab group) and selects the tab
     * that was active when the project was saved, if it came back now.
     */
    private void reopenDeferredTabs(Runnable reopen) {
        WindowRestore restore = activeRestore;
        Tab activeBefore = restore != null ? restore.activeTab() : null;
        int tabsBefore = tabPane.getTabs().size();
        // A tab leaves the bar before its question is asked: the session snapshot waits until it is
        // open (or back in the bar), so a crash during the question cannot lose it.
        if (sessionAutosave != null) {
            sessionAutosave.beginRestore();
        }
        try {
            reopen.run();
        } finally {
            if (sessionAutosave != null) {
                sessionAutosave.endRestore();
            }
            if (tabPane.getTabs().size() != tabsBefore) {
                organizeTabsByGroup();
            }
            Tab active = restore != null ? restore.activeTab() : null;
            if (active != null && active != activeBefore && tabPane.getTabs().contains(active)) {
                tabPane.getSelectionModel().select(active);
            }
            refreshRestoreAttentionBar();
        }
    }

    /**
     * One restore of this window's tabs from a project ({@link #restoreWindowState}): which tab came
     * from which saved position, the remote tabs still being downloaded, and the tab that was active,
     * which the window selects once it is there. The decisions are {@link ProjectRestoreOrder}'s; this
     * class applies them to the tab pane. FX thread only.
     */
    private final class WindowRestore {
        private final List<String> keys;
        private final String activeKey;
        /** The saved tabs, by saved position; the session snapshot keeps the ones that have not opened yet. */
        private final List<SessionState> savedTabs;
        /** The saved position of every tab this restore opened. */
        private final Map<Tab, Integer> savedIndexes = new IdentityHashMap<>();
        private final Set<String> pendingKeys = new HashSet<>();
        /** The saved positions of the remote tabs still downloading. */
        private final Set<Integer> pendingIndexes = new HashSet<>();
        private boolean syncTabsDone;
        private boolean selectionDone;
        private boolean timedOut;
        private javafx.animation.PauseTransition activeTabDeadline;
        /** The tab shown when the window started waiting for its active tab; null before. */
        private Tab selectedWhileWaiting;
        /** The tab opened for the saved active tab, right away or later from the restore bar; null before. */
        private Tab activeTab;

        WindowRestore(List<String> keys, String activeKey, List<SessionState> savedTabs) {
            this.keys = keys;
            this.activeKey = activeKey;
            this.savedTabs = savedTabs;
        }

        /** The saved position of {@code tab} when this restore opened it, else {@code null}. */
        Integer savedIndex(Tab tab) {
            return savedIndexes.get(tab);
        }

        /** The saved remote tabs still downloading, by saved position. */
        Map<Integer, SessionState> pendingTabs() {
            Map<Integer, SessionState> pending = new java.util.TreeMap<>();
            for (Integer index : pendingIndexes) {
                if (index >= 0 && index < savedTabs.size() && savedTabs.get(index) != null) {
                    pending.put(index, savedTabs.get(index));
                }
            }
            return pending;
        }

        /**
         * A tab the restore opened from saved position {@code index}: right away, or later from the
         * restore bar.
         */
        void opened(Tab tab, int index) {
            savedIndexes.put(tab, index);
            if (activeKey != null && activeKey.equals(keys.get(index))) {
                activeTab = tab;
            }
        }

        /** The tab opened for the saved active tab so far, or {@code null}. */
        Tab activeTab() {
            return activeTab;
        }

        /** The tab from saved position {@code index} opens later, once its file has been downloaded. */
        void pending(int index) {
            pendingKeys.add(keys.get(index));
            pendingIndexes.add(index);
        }

        /**
         * The file of the late tab from saved position {@code index} has been downloaded: adds the tab
         * {@code createTab} builds, which owns {@code session}, at its saved place. When another
         * project has been opened in this window meanwhile, the tab is not added and the session closes.
         */
        void lateTabReady(de.kortty.core.SFTPSession session, int index,
                          java.util.function.Supplier<? extends Tab> createTab) {
            if (activeRestore == this) {
                addTabOwningSftpSession(session, createTab, tab -> placeLateTab(tab, index));
            } else {
                closeOwnedSftpSessionInBackground(session);
            }
            lateTabDone(index);
        }

        private void placeLateTab(Tab tab, int index) {
            List<Integer> liveIndexes = new ArrayList<>();
            for (Tab live : tabPane.getTabs()) {
                // Tab groups sort the terminal tabs ahead of all others; only the others count here.
                liveIndexes.add(live instanceof TerminalTab ? null : savedIndexes.get(live));
            }
            tabPane.getTabs().add(ProjectRestoreOrder.lateInsertionIndex(liveIndexes, index), tab);
            savedIndexes.put(tab, index);
        }

        /** The late tab from saved position {@code index} has arrived, or will not. */
        void lateTabDone(int index) {
            pendingKeys.remove(keys.get(index));
            pendingIndexes.remove(index);
            // Arrived or failed: the session snapshot no longer keeps it as waiting.
            markSessionDirty();
            selectActiveTab();
        }

        /**
         * Every tab that opens right away is in: puts the restored tabs in their saved order, sorts the
         * terminal tabs into their tab groups (once, not after every tab) and selects the active tab.
         */
        void syncTabsDone() {
            syncTabsDone = true;
            reorganizeTabs(() -> sortTabsByGroup(ProjectRestoreOrder.savedOrder(
                    new ArrayList<>(tabPane.getTabs()), tab -> savedIndexes.getOrDefault(tab, -1))));
            selectActiveTab();
        }

        private void selectActiveTab() {
            if (!syncTabsDone || selectionDone) {
                return;
            }
            if (activeRestore != this) {
                // Another project has been opened in this window since.
                finishSelection();
                return;
            }
            List<String> liveKeys = new ArrayList<>();
            for (Tab tab : tabPane.getTabs()) {
                Integer index = savedIndexes.get(tab);
                liveKeys.add(index != null ? keys.get(index) : null);
            }
            boolean userChangedSelection = selectedWhileWaiting != null
                    && tabPane.getSelectionModel().getSelectedItem() != selectedWhileWaiting;
            ProjectRestoreOrder.Selection selection = ProjectRestoreOrder.selectActive(
                    activeKey, liveKeys, pendingKeys, timedOut, userChangedSelection);
            switch (selection.step()) {
                case SELECT -> {
                    tabPane.getSelectionModel().select(selection.index());
                    finishSelection();
                }
                case KEEP -> finishSelection();
                case WAIT -> waitForActiveTab();
            }
        }

        private void waitForActiveTab() {
            if (activeTabDeadline != null) {
                return;
            }
            selectedWhileWaiting = tabPane.getSelectionModel().getSelectedItem();
            activeTabDeadline = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(ProjectRestoreOrder.ACTIVE_TAB_WAIT_MILLIS));
            activeTabDeadline.setOnFinished(event -> {
                timedOut = true;
                selectActiveTab();
            });
            activeTabDeadline.play();
        }

        private void finishSelection() {
            selectionDone = true;
            if (activeTabDeadline != null) {
                activeTabDeadline.stop();
            }
        }
    }

    /**
     * Opens the SFTP session a restored remote editor or image tab uses on its own, prepared like
     * an SFTP tab's session: the vault (managed SSH keys, and the master password that also
     * decrypts a jump server's stored password) is handed over before connecting. A failed connect
     * closes the half-opened session. Runs off the FX thread.
     *
     * @param temporaryKey the still valid temporary SSH key the sign-in found, or {@code null}
     */
    private de.kortty.core.SFTPSession openOwnedSftpSession(ServerConnection connection, String password,
            de.kortty.model.TemporarySSHKey temporaryKey) throws Exception {
        de.kortty.core.SFTPSession session = new de.kortty.core.SFTPSession(
                SftpConnectionSupport.connectionForSftp(connection, temporaryKey), password);
        char[] masterPassword = app.getMasterPasswordManager() != null
                ? app.getMasterPasswordManager().getMasterPassword()
                : null;
        SftpConnectionSupport.configureVault(session, app.getSSHKeyManager(), masterPassword, null);
        try {
            session.connect();
        } catch (Exception e) {
            closeOwnedSftpSession(session);
            throw e;
        }
        return session;
    }

    /**
     * Adds the restored tab {@code createTab} builds and makes it the owner of {@code session}: the
     * session closes with the tab, however the tab is closed. If the window closed while the file
     * was downloading, or the tab cannot be built, the session is closed right away. Only these
     * restored tabs own their session; an image tab opened from an SFTP tab shares that tab's
     * session. {@code placeTab} adds the tab to the tab pane, at its saved place. FX thread.
     */
    private void addTabOwningSftpSession(
            de.kortty.core.SFTPSession session,
            java.util.function.Supplier<? extends Tab> createTab,
            Consumer<Tab> placeTab) {
        if (!stage.isShowing()) {
            // Its tabs were already closed; a tab added now would keep the session open for good.
            closeOwnedSftpSessionInBackground(session);
            return;
        }
        Tab tab;
        try {
            tab = createTab.get();
        } catch (RuntimeException e) {
            logger.error("Failed to open a restored remote tab", e);
            closeOwnedSftpSessionInBackground(session);
            return;
        }
        SftpSessionRestoreSupport.closeWithTab(tab, () -> closeOwnedSftpSessionInBackground(session));
        placeTab.accept(tab);
    }

    private static void closeOwnedSftpSession(de.kortty.core.SFTPSession session) {
        try {
            session.close();
        } catch (Exception e) {
            logger.warn("Error closing the SFTP session of a restored tab", e);
        }
    }

    /** Closes off the FX thread, since closing waits for the server. */
    private static void closeOwnedSftpSessionInBackground(de.kortty.core.SFTPSession session) {
        Thread closer = new Thread(() -> closeOwnedSftpSession(session), "SFTP-Close");
        closer.setDaemon(true);
        closer.start();
    }

    private void importConnections() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.connections.import"));
        
        // Add filters for each importer
        for (ConnectionImporter importer : importers) {
            List<String> extensions = new ArrayList<>();
            for (String ext : importer.getSupportedExtensions()) {
                extensions.add("*." + ext);
            }
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(importer.getFileDescription(), extensions)
            );
        }
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Alle Dateien", "*.*")
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            for (ConnectionImporter importer : importers) {
                if (importer.canImport(file.toPath())) {
                    try {
                        List<ServerConnection> imported = importer.importConnections(file.toPath());
                        for (ServerConnection conn : imported) {
                            app.getConfigManager().addConnection(conn);
                        }
                        app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                        
                        showInfo(I18n.get("info.importSuccessful", imported.size(), importer.getName()), "");
                        Telemetry.track(TelemetryEvents.CONNECTION_TRANSFER, Map.of(
                            "direction", "import",
                            "format", importer.getClass().getSimpleName().toLowerCase(Locale.ROOT),
                            "count", imported.size()));
                        return;
                    } catch (Exception e) {
                        logger.error("Import failed", e);
                        showError(I18n.get("error.importFailed"), e.getMessage());
                        return;
                    }
                }
            }
            showError(I18n.get("error.importFailed"), I18n.get("error.importUnsupportedFormat"));
        }
    }
    
    private void exportConnections() {
        List<ServerConnection> allConnections = app.getConfigManager().getConnections();
        
        if (allConnections.isEmpty()) {
            showInfo(I18n.get("error.noConnectionsToExport"), "");
            return;
        }
        
        // Show export dialog
        ExportDialog dialog = new ExportDialog(stage, allConnections, app.getGpgKeyManager());
        Optional<ExportDialog.ExportResult> result = dialog.showAndWait();
        
        if (result.isEmpty() || result.get() == null) {
            return;
        }
        
        ExportDialog.ExportResult exportResult = result.get();
        
        // File chooser
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.connections.export") + " " + I18n.get("dialog.as") + " " + exportResult.exporter.getName());
        
        if (exportResult.encryptionType == ExportDialog.EncryptionType.PASSWORD) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("backup.encryption.password") + " (*.zip)", "*.zip")
            );
            fileChooser.setInitialFileName("connections.zip");
        } else if (exportResult.encryptionType == ExportDialog.EncryptionType.GPG) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("backup.encryption.gpg") + " (*.gpg)", "*.gpg")
            );
            fileChooser.setInitialFileName("connections." + exportResult.exporter.getFileExtension() + ".gpg");
        } else {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(exportResult.exporter.getFileDescription(), 
                            "*." + exportResult.exporter.getFileExtension())
            );
            fileChooser.setInitialFileName("connections." + exportResult.exporter.getFileExtension());
        }
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                if (exportResult.encrypted()) {
                    exportAsEncryptedZip(exportResult, file.toPath());
                } else {
                    exportResult.exporter.exportConnections(exportResult.connections, file.toPath());
                }

                Telemetry.track(TelemetryEvents.CONNECTION_TRANSFER, Map.of(
                    "direction", "export",
                    "format", exportResult.exporter.getClass().getSimpleName().toLowerCase(Locale.ROOT),
                    "count", exportResult.connections.size()));
                showInfo(I18n.get("info.exportSuccessful",
                        exportResult.connections.size(), file.getName(),
                        "\n\nFormat: " + exportResult.exporter.getName() +
                        (exportResult.encrypted() ? "\n" + I18n.get("info.encrypted") : "")), "");
            } catch (Exception e) {
                logger.error("Export failed", e);
                showError(I18n.get("error.exportFailed"), e.getMessage());
            }
        }
    }
    
    private void exportAsEncryptedZip(ExportDialog.ExportResult exportResult, Path zipFile) throws Exception {
        // Create temporary file for export
        Path tempFile = Files.createTempFile("kortty-export", "." + exportResult.exporter.getFileExtension());
        
        try {
            // Export to temp file
            exportResult.exporter.exportConnections(exportResult.connections, tempFile);
            
            // Create encrypted ZIP
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                // Set password if provided (Note: Standard Java ZIP doesn't support encryption)
                // We'll use a workaround with encrypted content
                
                ZipEntry entry = new ZipEntry("connections." + exportResult.exporter.getFileExtension());
                zos.putNextEntry(entry);
                
                // Read and encrypt content
                byte[] content = Files.readAllBytes(tempFile);
                byte[] encrypted = encryptContent(content, exportResult.password);
                zos.write(encrypted);
                
                zos.closeEntry();
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    
    private void exportAsGPGEncrypted(ExportDialog.ExportResult exportResult, Path gpgFile) throws Exception {
        // Create temporary file for export
        Path tempFile = Files.createTempFile("kortty-export", "." + exportResult.exporter.getFileExtension());
        
        try {
            // Export to temp file
            exportResult.exporter.exportConnections(exportResult.connections, tempFile);
            
            // Encrypt with GPG
            String keyId = exportResult.gpgKey.getKeyId();
            
            ProcessBuilder pb = new ProcessBuilder(
                "gpg",
                "--encrypt",
                "--recipient", keyId,
                "--trust-model", "always",  // Trust the key automatically
                "--output", gpgFile.toString(),
                tempFile.toString()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                throw new Exception(I18n.get("error.gpgEncryptionFailed", exitCode, output.toString(), keyId));
            }
            
            logger.info("File encrypted with GPG using key {}: {}", keyId, gpgFile);
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    private byte[] encryptContent(byte[] data, String password) throws Exception {
        // Use AES encryption
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        
        // Derive key from password
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(key, "AES");
        
        // Generate IV
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
        
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
        byte[] encrypted = cipher.doFinal(data);
        
        // Prepend IV to encrypted data
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        
        return result;
    }
    
    private void showAbout() {
        Dialog<Void> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(I18n.get("dialog.about") + " " + KorTTYApplication.getAppName());
        dialog.setHeaderText(KorTTYApplication.getAppName() + " v" + KorTTYApplication.getAppVersion());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label aboutText = new Label(I18n.get("dialog.aboutText"));
        aboutText.setWrapText(true);
        Label developedBy = new Label(I18n.get("dialog.aboutDeveloped"));
        Label jmxLabel = new Label(I18n.get("dialog.aboutJMX") + "\n" + "de.kortty:type=SSHClient");
        Hyperlink projectLink = new Hyperlink(PROJECT_URL);
        projectLink.setOnAction(event -> openProjectPage());

        Button manualUpdateCheckButton = new Button(I18n.get("updates.checkNow"));
        ProgressIndicator updateProgress = new ProgressIndicator();
        updateProgress.setPrefSize(18, 18);
        updateProgress.setVisible(false);
        updateProgress.setManaged(false);
        Label updateStatusLabel = new Label();
        updateStatusLabel.setWrapText(true);
        updateStatusLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        manualUpdateCheckButton.setOnAction(event ->
            runManualUpdateCheck(manualUpdateCheckButton, updateProgress, updateStatusLabel));
        de.kortty.policy.EffectivePolicy updatePolicy = de.kortty.policy.PolicyManager.effective();
        if (!updatePolicy.updatesEnabled()) {
            manualUpdateCheckButton.setDisable(true);
            manualUpdateCheckButton.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
        } else if (updatePolicy.updateFeedUrl().isPresent()) {
            updateStatusLabel.setText(I18n.get("policy.update.managedFeed"));
        }

        HBox updateCheckBox = new HBox(10, manualUpdateCheckButton, updateProgress);
        updateCheckBox.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10);
        content.setPadding(new Insets(4, 0, 0, 0));
        content.setPrefWidth(640);

        var iconUrl = getClass().getResource("/icon/kortty_logo.png");
        if (iconUrl == null) {
            iconUrl = getClass().getResource("/icon/kortty_icon.png");
        }
        if (iconUrl != null) {
            ImageView iconView = new ImageView(new Image(iconUrl.toExternalForm()));
            iconView.setFitWidth(560);
            iconView.setPreserveRatio(true);
            iconView.setSmooth(true);
            HBox logoBox = new HBox(iconView);
            logoBox.setAlignment(Pos.CENTER);
            content.getChildren().addAll(logoBox, new Separator());
        }
        content.getChildren().addAll(
            aboutText,
            developedBy,
            new Label(I18n.get("dialog.aboutProject")),
            projectLink,
            jmxLabel,
            new Separator(),
            updateCheckBox,
            updateStatusLabel);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void openProjectPage() {
        try {
            app.getHostServices().showDocument(PROJECT_URL);
        } catch (Exception e) {
            logger.warn("Could not open project page", e);
            showError(I18n.get("error.title"), I18n.get("updates.project.openFailed"));
        }
    }

    private void runManualUpdateCheck(
        Button manualUpdateCheckButton,
        ProgressIndicator updateProgress,
        Label updateStatusLabel
    ) {
        Telemetry.track(TelemetryEvents.UPDATE_CHECK_CLICKED);
        UpdateCheckService service = ensureUpdateCheckService();
        if (service == null) {
            updateStatusLabel.setText(I18n.get("updates.checkFailed"));
            return;
        }
        manualUpdateCheckButton.setDisable(true);
        updateProgress.setVisible(true);
        updateProgress.setManaged(true);
        updateStatusLabel.setText(I18n.get("updates.checking"));

        Task<UpdateCheckResult> task = new Task<>() {
            @Override
            protected UpdateCheckResult call() {
                return service.checkManually();
            }
        };
        task.setOnSucceeded(event -> {
            manualUpdateCheckButton.setDisable(false);
            updateProgress.setVisible(false);
            updateProgress.setManaged(false);
            handleManualUpdateCheckResult(task.getValue(), updateStatusLabel);
        });
        task.setOnFailed(event -> {
            manualUpdateCheckButton.setDisable(false);
            updateProgress.setVisible(false);
            updateProgress.setManaged(false);
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("updates.checkFailed");
            updateStatusLabel.setText(message);
        });
        Thread thread = new Thread(task, "kortty-manual-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleManualUpdateCheckResult(UpdateCheckResult result, Label updateStatusLabel) {
        if (result == null) {
            updateStatusLabel.setText(I18n.get("updates.checkFailed"));
            return;
        }
        switch (result.status()) {
            case UPDATE_AVAILABLE -> {
                updateStatusLabel.setText(I18n.get("updates.available.short", result.update().versionLabel()));
                showUpdateAvailableDialog(result.update(), true);
            }
            case NO_UPDATE -> updateStatusLabel.setText(I18n.get("updates.manual.current"));
            case NO_COMPATIBLE_ASSET -> updateStatusLabel.setText(I18n.get("updates.noCompatibleAsset"));
            case FAILED -> updateStatusLabel.setText(I18n.get("updates.checkFailed.detail", result.message()));
        }
    }

    private void showUpdateAvailableDialog(AvailableUpdate update, boolean manual) {
        if (update == null) {
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(I18n.get("updates.dialog.title"));
        dialog.setHeaderText(I18n.get("updates.dialog.header", update.versionLabel()));

        ButtonType downloadButton = new ButtonType(I18n.get("updates.download"), ButtonBar.ButtonData.OK_DONE);
        ButtonType remindButton = new ButtonType(I18n.get("updates.remindTomorrow"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType skipButton = new ButtonType(I18n.get("updates.skipVersion"), ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(downloadButton, remindButton, skipButton);

        Label content = new Label(I18n.get(
            "updates.dialog.content",
            KorTTYApplication.getAppVersion(),
            update.versionLabel(),
            update.asset().name(),
            DownloadDirectoryResolver.resolveDefaultDownloadsDirectory()));
        content.setWrapText(true);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);

        Optional<ButtonType> result = dialog.showAndWait();
        UpdateCheckService service = ensureUpdateCheckService();
        if (result.isPresent() && result.get() == downloadButton) {
            downloadUpdate(update);
        } else if (service != null && result.isPresent() && result.get() == skipButton) {
            service.ignoreVersion(update.versionLabel());
        } else if (service != null && (!manual || (result.isPresent() && result.get() == remindButton))) {
            service.snoozeUntilTomorrow(update.versionLabel());
        }
    }

    private void downloadUpdate(AvailableUpdate update) {
        Dialog<Void> progressDialog = new Dialog<>();
        DialogThemeHelper.applyTheme(progressDialog);
        progressDialog.initOwner(stage);
        progressDialog.setTitle(I18n.get("updates.download.title"));
        progressDialog.setHeaderText(I18n.get("updates.download.header", update.versionLabel()));
        ButtonType cancelButtonType = ButtonType.CANCEL;
        progressDialog.getDialogPane().getButtonTypes().add(cancelButtonType);

        ProgressIndicator progressIndicator = new ProgressIndicator();
        Label status = new Label(I18n.get("updates.download.running", update.asset().name()));
        status.setWrapText(true);
        VBox content = new VBox(12, progressIndicator, status);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefWidth(420);
        progressDialog.getDialogPane().setContent(content);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return new UpdateAssetDownloader().download(
                    update.asset(),
                    DownloadDirectoryResolver.resolveDefaultDownloadsDirectory());
            }
        };
        Button cancelButton = (Button) progressDialog.getDialogPane().lookupButton(cancelButtonType);
        cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            task.cancel(true);
            progressDialog.close();
        });
        task.setOnSucceeded(event -> {
            progressDialog.close();
            UpdateCheckService service = ensureUpdateCheckService();
            if (service != null) {
                service.recordDownloadedVersion(update.versionLabel());
            }
            // Defer to the next pulse so the progress dialog's modal loop fully unwinds first. Showing a
            // modal from inside another modal's showAndWait() teardown renders it blank/unthemed on macOS
            // Glass — that is the reported white, empty "Download complete" window.
            Path downloadedFile = task.getValue();
            Platform.runLater(() -> showDownloadCompleteDialog(update, downloadedFile));
        });
        task.setOnFailed(event -> {
            progressDialog.close();
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("updates.download.failed");
            if (error instanceof DownloadException && error.getCause() != null && error.getCause().getMessage() != null) {
                message = error.getCause().getMessage();
            }
            // Defer for the same reason as the success path: a modal shown during the progress dialog's
            // showAndWait() teardown renders blank/unthemed on macOS Glass.
            String finalMessage = message;
            Platform.runLater(() -> showError(I18n.get("updates.download.failed.title"), finalMessage));
        });
        Thread thread = new Thread(task, "kortty-update-download");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    /** Bundled guide page that documents the update / download-and-install flow. */
    private static final String UPDATE_GUIDE_LOCATION = "reference/settings/updates.html";

    /**
     * Shown once an update asset finishes downloading: a themed dialog that names where the file was
     * saved and offers two actions — open the downloaded installer and jump to the matching guide page.
     */
    private void showDownloadCompleteDialog(AvailableUpdate update, Path downloadedFile) {
        buildDownloadCompleteDialog(app, stage, update, downloadedFile).showAndWait();
    }

    /**
     * Builds the "download complete" dialog. Replaces the plain {@link Alert} that rendered unthemed
     * (white on the dark app); a {@link Dialog} themed through {@link DialogThemeHelper} matches the
     * rest of the chrome. Static + owner-parameterised so it can be rendered headless in a smoke test.
     */
    static Dialog<Void> buildDownloadCompleteDialog(KorTTYApplication app, Window owner,
            AvailableUpdate update, Path downloadedFile) {
        Dialog<Void> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.get("updates.download.complete.title"));
        dialog.setHeaderText(I18n.get("updates.download.complete.header", update.versionLabel()));

        ButtonType closeButton = new ButtonType(I18n.get("dialog.close"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().add(closeButton);

        boolean flatpakBundle = update != null
            && update.asset() != null
            && update.asset().name().toLowerCase(java.util.Locale.ROOT).endsWith(".flatpak");
        Label intro = new Label(I18n.get(flatpakBundle
            ? "updates.download.complete.flatpakIntro"
            : "updates.download.complete.intro"));
        intro.setWrapText(true);

        Label locationCaption = new Label(I18n.get("updates.download.complete.location"));

        TextField pathField = new TextField(downloadedFile != null ? downloadedFile.toString() : "");
        pathField.setEditable(false);
        pathField.setFocusTraversable(false);

        Button openButton = new Button(I18n.get("updates.download.complete.open"));
        openButton.setDisable(downloadedFile == null);
        openButton.setOnAction(event -> openDownloadedUpdate(downloadedFile));

        Button guideButton = new Button(I18n.get("updates.download.complete.guide"));
        guideButton.setOnAction(event -> GuideViewer.show(app, owner, UPDATE_GUIDE_LOCATION));

        HBox actions = new HBox(10, openButton, guideButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, intro, locationCaption, pathField);
        if (flatpakBundle && downloadedFile != null) {
            Label commandCaption = new Label(I18n.get("updates.download.complete.flatpakCommand"));
            TextField commandField = new TextField(FlatpakSupport.installCommand(downloadedFile));
            commandField.setEditable(false);
            content.getChildren().addAll(commandCaption, commandField);
        }
        content.getChildren().add(actions);
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);

        return dialog;
    }

    /**
     * Opens the freshly downloaded update with the OS default handler (mounts the {@code .dmg} /
     * launches the installer). Falls back to revealing the containing folder when the file itself
     * cannot be opened, so the user can always reach the download.
     */
    private static void openDownloadedUpdate(Path file) {
        if (file == null || !Desktop.isDesktopSupported()) {
            logger.warn("Cannot open downloaded update (file={}, desktopSupported={})",
                file, Desktop.isDesktopSupported());
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        try {
            if (Files.exists(file)) {
                desktop.open(file.toFile());
                return;
            }
        } catch (Exception e) {
            logger.debug("Could not open downloaded update {}, falling back to its folder", file, e);
        }
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            if (Files.exists(parent)) {
                desktop.open(parent.toFile());
            }
        } catch (Exception e) {
            logger.warn("Could not open download location {}", parent, e);
        }
    }

    private UpdateCheckService ensureUpdateCheckService() {
        UpdateCheckService service = app.getUpdateCheckService();
        if (service == null) {
            app.restartUpdateCheckService();
            service = app.getUpdateCheckService();
        }
        return service;
    }
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
    }

    /**
     * Summarize Recent Output: the pane's recent output, read by {@link TerminalRecentOutputSource}
     * on the FX thread, goes through the same masking, preview and masked count as a selection.
     */
    private void handleAiRecentOutputAction(
        TerminalTab terminalTab,
        AiProfile profile,
        TerminalRecentOutputSource.RecentOutput output,
        TerminalView.TerminalAgentRunContext runContext) {
        handleAiTextAction(terminalTab, AiAction.SUMMARIZE, profile, output.text(),
            AiTextActionInput.Origin.RECENT_OUTPUT, runContext);
    }

    /**
     * A terminal AI action (Summarize, Solve, Ask) on {@code text} from {@code origin}: a selection
     * or the pane's recent output. The text is checked and masked by
     * {@link AiTextActionInput#prepare} before the preview, so both origins send only masked text.
     */
    private void handleAiTextAction(
        TerminalTab terminalTab,
        AiAction action,
        AiProfile profile,
        String selectedText,
        AiTextActionInput.Origin origin,
        TerminalView.TerminalAgentRunContext runContext) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        AiProfile effectiveProfile = profile != null
            ? profile
            : resolveAiProfileForConnection(
                terminalTab != null ? terminalTab.getConnection() : null,
                action.workload());
        int maxSelectionChars = getMaxAiSelectionChars(effectiveProfile);
        // Mask secrets before the preview, so the user reviews exactly what leaves the computer.
        // The file name below is resolved from the raw selection; only the outbound text is masked.
        SessionJournalRedactor knownSecrets = aiSecretRedactor(terminalTab);
        AiTextActionInput.Prepared maskedSelection =
            AiTextActionInput.prepare(origin, effectiveProfile, selectedText, maxSelectionChars, knownSecrets);
        switch (maskedSelection.status()) {
            case SKIP -> {
                return;
            }
            case NO_OUTPUT -> {
                updateStatus(I18n.get("ai.recentOutput.none"));
                return;
            }
            case TOO_LARGE -> {
                showError(I18n.get("ai.error.title"), I18n.get("ai.error.selectionTooLarge", maxSelectionChars));
                return;
            }
            case READY -> {
                // Sent below.
            }
        }
        String effectiveModel = effectiveProfile != null
            && effectiveProfile.getConnectionMode().isEmbedded()
            ? effectiveProfile.getEmbeddedModelId()
            : effectiveProfile != null ? effectiveProfile.getModel() : null;
        if (effectiveProfile != null
            && requiresModelForAiProfile(effectiveProfile)
            && (effectiveModel == null || effectiveModel.isBlank())) {
            showError(I18n.get("ai.error.title"), I18n.get("settings.ai.error.noModel"));
            return;
        }

        AiService aiService = createAiService(effectiveProfile, terminalTab != null ? terminalTab.getConnection() : null);
        if (aiService == null) {
            showAiConfigurationDialog();
            return;
        }
        if (aiService instanceof FailingAiService failingService) {
            showError(I18n.get("ai.error.title"), failingService.message());
            return;
        }
        String connectionName = terminalTab.getConnection() != null ? terminalTab.getConnection().getDisplayName() : null;
        String languageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        // When the selection looks like a file name in the pane's current directory, the file's
        // content can travel with the request as an attachment. The candidate is only an offer;
        // existence, readability, text-ness and size are verified on the target before anything
        // is attached (see loadAiAttachmentAsync). Recent output is never a file name.
        AiAttachmentCandidate attachmentCandidate = origin == AiTextActionInput.Origin.SELECTION
            ? resolveAiAttachmentCandidate(terminalTab, runContext, selectedText, maxSelectionChars)
            : null;
        String outboundText = maskedSelection.outboundText();
        // The pane the selection came from, read now: the chat's code blocks go back to it.
        TerminalPaneRef sourcePane = aiSourcePane(terminalTab, runContext);
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        boolean confirmBeforeSend = action == AiAction.ASK || settings == null || settings.isAiConfirmBeforeSend();
        if (confirmBeforeSend) {
            confirmAiRequest(action, effectiveProfile, outboundText, connectionName, languageCode, attachmentCandidate,
                maxSelectionChars, maskedSelection.count())
                .ifPresent(draft -> startAiSelectionRequest(
                    action, effectiveProfile, aiService, draft, connectionName, languageCode, maxSelectionChars,
                    knownSecrets, 0, sourcePane));
            return;
        }
        if (attachmentCandidate == null) {
            startAiSelectionRequest(action, effectiveProfile, aiService,
                new AiRequestDraft(outboundText, null, null), connectionName, languageCode, maxSelectionChars,
                knownSecrets, maskedSelection.count(), sourcePane);
            return;
        }
        // No confirmation dialog: attach the file when it validates, otherwise send the selection
        // alone and say why in the status bar.
        updateStatus(I18n.get("ai.confirm.attachment.checking", attachmentCandidate.fileName()));
        loadAiAttachmentAsync(attachmentCandidate, maxSelectionChars, outcome -> {
            if (outcome.attachment() == null) {
                updateStatus(I18n.get("ai.attachment.skipped", attachmentCandidate.fileName(), outcome.failureText()));
            }
            startAiSelectionRequest(action, effectiveProfile, aiService,
                new AiRequestDraft(outboundText, null, outcome.attachment()), connectionName, languageCode,
                maxSelectionChars, knownSecrets, maskedSelection.count(), sourcePane);
        });
    }

    /**
     * The pane an AI request from {@code terminalTab} came from, for the chat's code blocks: the run
     * context's pane, else the tab's focused pane; {@code null} without a terminal pane. FX thread.
     */
    private static @Nullable TerminalPaneRef aiSourcePane(@Nullable TerminalTab terminalTab,
                                                          @Nullable TerminalView.TerminalAgentRunContext runContext) {
        if (terminalTab == null) {
            return null;
        }
        TerminalPaneRef fromContext = runContext != null ? TerminalPaneRef.of(terminalTab, runContext.widget()) : null;
        return fromContext != null ? fromContext : TerminalPaneRef.focusedPaneOf(terminalTab);
    }

    /** The tab's known secrets (connection password, policy rules) for masking AI-bound text. */
    private static @Nullable SessionJournalRedactor aiSecretRedactor(@Nullable TerminalTab terminalTab) {
        return terminalTab != null && terminalTab.getTerminalView() != null
            ? terminalTab.getTerminalView().createSecretRedactor()
            : null;
    }

    /**
     * Shows {@code status} in the status bar, followed by how many secrets were masked when no
     * preview showed that (confirmation turned off, Ask Agent, or an attachment, which is never
     * previewed).
     */
    private void updateStatusWithMaskedSecrets(@Nullable String status, int maskedCount) {
        String notice = maskedCount > 0 ? I18n.get("ai.redaction.notice", maskedCount) : null;
        if (status == null && notice == null) {
            return;
        }
        updateStatus(status == null ? notice : notice == null ? status : status + " " + notice);
    }

    /**
     * Sends the reviewed selection (already masked) with its optional attachment and opens the
     * result tab.
     *
     * @param knownSecrets    the tab's known secrets, for masking the attachment
     * @param unreportedMasks secrets already masked in the selection that no preview dialog showed;
     *                        the status bar reports them together with the attachment's
     * @param sourcePane      the pane the selection came from, where the chat's code blocks go
     */
    private void startAiSelectionRequest(
        AiAction action,
        AiProfile effectiveProfile,
        AiService aiService,
        AiRequestDraft draft,
        String connectionName,
        String languageCode,
        int maxSelectionChars,
        @Nullable SessionJournalRedactor knownSecrets,
        int unreportedMasks,
        @Nullable TerminalPaneRef sourcePane) {
        String requestText = draft.selectedText();
        if (requestText.trim().isEmpty()) {
            return;
        }
        if (requestText.length() > maxSelectionChars) {
            showError(I18n.get("ai.error.title"), I18n.get("ai.error.selectionTooLarge", maxSelectionChars));
            return;
        }
        // The attachment is not part of the preview, so its content is masked here.
        AiOutboundRedaction.MaskedAttachment maskedAttachment =
            AiOutboundRedaction.redactAttachmentFor(effectiveProfile, draft.fileAttachment(), knownSecrets);
        AiFileAttachment fileAttachment = maskedAttachment.attachment();
        if (fileAttachment != null && !fileAttachment.fitsWithin(requestText, maxSelectionChars)) {
            // The preview is editable: the selection may have grown after the file was validated.
            showError(I18n.get("ai.error.title"),
                I18n.get("ai.attachment.tooLarge", maxSelectionChars, fileAttachment.sourcePath()));
            return;
        }

        AiRequest request = new AiRequest(action, requestText, connectionName, languageCode, draft.userPrompt())
            .withFileAttachment(fileAttachment);
        Map<String, Object> aiChatProps = new java.util.LinkedHashMap<>(TelemetryProps.aiProfileProps(effectiveProfile));
        aiChatProps.put("first", true);
        aiChatProps.put("broadcast", false);
        aiChatProps.put("action", action.name().toLowerCase(Locale.ROOT));
        aiChatProps.put("attachment", fileAttachment != null);
        Telemetry.track(TelemetryEvents.AI_CHAT_MESSAGE, aiChatProps);
        String tabTitle = I18n.get("ai.tab.title", getAiActionLabel(action));
        AiResultTab resultTab = new AiResultTab(
            this,
            tabTitle,
            effectiveProfile,
            requestText,
            connectionName,
            languageCode,
            null,
            false);
        // Follow-ups may go to another profile; the tab masks them again with these secrets.
        resultTab.setOutboundSecrets(knownSecrets);
        resultTab.setSourcePane(sourcePane);
        if (fileAttachment != null) {
            resultTab.setFileAttachment(fileAttachment);
        }
        if (action == AiAction.ASK && draft.userPrompt() != null && !draft.userPrompt().isBlank()) {
            resultTab.appendUserMessage(draft.userPrompt());
        }
        insertTemporaryTab(resultTab);
        updateStatusWithMaskedSecrets(
            I18n.get("ai.status.running", getAiActionLabel(action)), unreportedMasks + maskedAttachment.count());

        // The chat shows the answer live while it streams; the final rendering replaces the preview.
        AiRequest streamedRequest = request.withStreamListener(resultTab.beginStreaming());
        Task<AiExecutionResult> task = new Task<>() {
            @Override
            protected AiExecutionResult call() throws Exception {
                return aiService.execute(streamedRequest);
            }
        };
        Thread thread = new Thread(task, "ai-selection-" + action.name().toLowerCase(Locale.ROOT));
        thread.setDaemon(true);
        resultTab.attachRunningTask(task, thread, I18n.get("ai.result.loading"));
        task.setOnSucceeded(e -> {
            AiExecutionResult result = task.getValue();
            resultTab.showResult(result);
            recordAiUsage(effectiveProfile, request, result);
            updateStatus(I18n.get("ai.status.finished", getAiActionLabel(action)));
        });
        task.setOnCancelled(e -> {
            resultTab.showCancelled();
            updateStatus(I18n.get("ai.status.cancelled", getAiActionLabel(action)));
        });
        task.setOnFailed(e -> {
            if (task.isCancelled()) {
                resultTab.showCancelled();
                updateStatus(I18n.get("ai.status.cancelled", getAiActionLabel(action)));
                return;
            }
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("ai.result.error");
            resultTab.showError(I18n.get("ai.result.errorMessage", message));
            updateStatus(I18n.get("ai.status.failed", getAiActionLabel(action)));
        });

        thread.start();
    }

    private boolean requiresModelForAiProfile(AiProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            return false;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            return AiCliArgumentTemplate.requiresModel(profile.getCliArgumentsTemplate());
        }
        if (profile.getConnectionMode().isEmbedded()) {
            return true;
        }
        return profile.getModelSelectionMode() == AiModelSelectionMode.MANUAL;
    }

    private int getMaxAiSelectionChars(AiProfile profile) {
        if (profile != null && profile.getMaxSelectionChars() != null && profile.getMaxSelectionChars() > 0) {
            return profile.getMaxSelectionChars();
        }
        return DEFAULT_MAX_AI_SELECTION_CHARS;
    }

    private AiService createAiService(AiProfile profile) {
        return createAiService(profile, null);
    }

    private AiService createAiService(AiProfile profile, ServerConnection connection) {
        return createAiService(profile, connection, null);
    }

    /**
     * Creates an AI service. A non-null {@code forcedSkillIds} collection activates the snippet
     * picker's explicit selection mode; an empty collection deliberately selects no normal skills.
     */
    private AiService createAiService(AiProfile profile, ServerConnection connection, java.util.Collection<String> forcedSkillIds) {
        if (profile == null) {
            return null;
        }
        String apiKey = getAiApiKeyPlain(profile);
        if (profile.getConnectionMode() == AiConnectionMode.HTTP_API
            && (profile.getApiUrl() == null || profile.getApiUrl().isBlank())) {
            return null;
        }
        java.util.Collection<String> assignedConnectionSkillIds = connection != null
            && connection.getAiSkillIds() != null
            ? connection.getAiSkillIds()
            : java.util.List.of();
        try {
            de.kortty.model.GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            AiSkillPromptSupport skillPromptSupport = forcedSkillIds == null
                ? AiSkillPromptSupport.fromSettings(
                    settings,
                    assignedConnectionSkillIds.isEmpty() ? null : assignedConnectionSkillIds)
                : AiSkillPromptSupport.fromSettingsForSnippetSelection(
                    settings,
                    forcedSkillIds,
                    assignedConnectionSkillIds);
            return AiServiceFactory.create(
                profile,
                apiKey,
                buildInternetAccessConfiguration(profile),
                skillPromptSupport);
        } catch (IllegalStateException e) {
            return new FailingAiService(e.getMessage());
        }
    }

    private String getAiApiKeyPlain(AiProfile profile) {
        String policyKey = de.kortty.policy.PolicyAiProfileSupport.apiKeyOverride(profile);
        if (policyKey != null) {
            return policyKey;
        }
        if (profile == null || profile.getEncryptedApiKey() == null || profile.getEncryptedApiKey().isBlank()) {
            return null;
        }
        try {
            char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (masterPassword == null) {
                return null;
            }
            de.kortty.security.EncryptionService encryptionService = new de.kortty.security.EncryptionService();
            String decrypted = encryptionService.decryptPassword(profile.getEncryptedApiKey(), masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            logger.warn("Could not decrypt AI API key", e);
            return null;
        }
    }

    private AiInternetAccessConfiguration buildInternetAccessConfiguration(AiProfile profile) {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        AiInternetAccessMode mode = profile != null ? profile.getInternetAccessMode() : null;
        if (settings == null || mode == null || !mode.isEnabled()) {
            return AiInternetAccessConfiguration.disabled();
        }
        String tavilyApiKey = null;
        String brightDataApiToken = null;
        String braveSearchApiKey = null;
        String searxngUrl = null;
        String tavilyMcpServerLabel = null;
        String brightDataMcpServerLabel = null;
        String braveSearchMcpPluginId = null;
        String searxngMcpPluginId = null;
        String lmStudioToolpackMcpPluginId = null;
        switch (mode) {
            case KORTTY_TAVILY_TOOL -> tavilyApiKey =
                decryptGlobalSecret(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
            case LM_STUDIO_TAVILY_MCP -> {
                tavilyApiKey = decryptGlobalSecret(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
                tavilyMcpServerLabel = settings.getAiTavilyMcpServerLabel();
            }
            case BRIGHT_DATA_WEB_MCP -> {
                brightDataApiToken =
                    decryptGlobalSecret(settings.getEncryptedAiBrightDataApiToken(), "Bright Data API token");
                brightDataMcpServerLabel = settings.getAiBrightDataMcpServerLabel();
            }
            case BRAVE_SEARCH_MCP -> {
                braveSearchApiKey =
                    decryptGlobalSecret(settings.getEncryptedAiBraveSearchApiKey(), "Brave Search API key");
                braveSearchMcpPluginId = settings.getAiBraveSearchMcpPluginId();
            }
            case SEARXNG_MCP -> {
                searxngUrl = settings.getAiSearxngUrl();
                searxngMcpPluginId = settings.getAiSearxngMcpPluginId();
            }
            case LM_STUDIO_TOOLPACK -> lmStudioToolpackMcpPluginId = settings.getAiLmStudioToolpackMcpPluginId();
            case DISABLED -> {
            }
        }
        return new AiInternetAccessConfiguration(
            mode,
            tavilyApiKey,
            brightDataApiToken,
            braveSearchApiKey,
            searxngUrl,
            tavilyMcpServerLabel,
            brightDataMcpServerLabel,
            braveSearchMcpPluginId,
            searxngMcpPluginId,
            lmStudioToolpackMcpPluginId,
            settings.isAiAgentAlwaysOfferWebTools());
    }

    private String decryptGlobalSecret(String encryptedValue, String label) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        try {
            char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (masterPassword == null) {
                throw new IllegalStateException(label + " cannot be decrypted because the password vault is locked.");
            }
            de.kortty.security.EncryptionService encryptionService = new de.kortty.security.EncryptionService();
            String decrypted = encryptionService.decryptPassword(encryptedValue, masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Could not decrypt {}", label, e);
            throw new IllegalStateException(label + " could not be decrypted.");
        }
    }

    private void showAiConfigurationDialog() {
        ButtonType openSettings = new ButtonType(I18n.get("ai.settings.open"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("ai.error.notConfigured"), openSettings, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("ai.error.title"));
        alert.setHeaderText(null);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == openSettings) {
            showSettings();
        }
    }

    private Optional<AiRequestDraft> confirmAiRequest(
        AiAction action,
        AiProfile profile,
        String selectedText,
        String connectionName,
        String languageCode,
        @Nullable AiAttachmentCandidate attachmentCandidate,
        int maxSelectionChars,
        int maskedSecretCount) {
        String model = aiModelDisplayText(profile);
        String apiUrl = profile != null && profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI
            ? de.kortty.core.AiCliProviderRegistry.find(profile.getCliProviderId())
                .map(de.kortty.core.AiCliProviderDescriptor::displayName)
                .orElse(I18n.get("settings.ai.connectionMode.local_cli"))
            : profile != null && profile.getApiUrl() != null ? profile.getApiUrl() : "";
        Dialog<AiRequestDraft> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.confirm.title"));
        dialog.setHeaderText(I18n.get("ai.confirm.header", getAiActionLabel(action)));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Label summaryLabel = new Label(buildAiConfirmSummary(action, profile, model, apiUrl, selectedText, connectionName, languageCode, promptAreaInitialValue(action), null));
        summaryLabel.setWrapText(true);
        AiQuotaBar quotaBar = new AiQuotaBar();
        quotaBar.setPrefWidth(420);

        TextField searchField = new TextField();
        searchField.setPromptText(I18n.get("editor.search.findPrompt"));
        TextField replaceField = new TextField();
        replaceField.setPromptText(I18n.get("editor.search.replacePrompt"));

        ComboBox<String> promptHistoryCombo = new ComboBox<>();
        promptHistoryCombo.setPrefWidth(420);
        Button clearPromptHistoryButton = new Button(I18n.get("ai.confirm.prompt.history.clear"));
        TextArea promptArea = new TextArea();
        promptArea.setPrefColumnCount(80);
        promptArea.setPrefRowCount(5);
        promptArea.setWrapText(true);
        promptArea.setPromptText(I18n.get("ai.confirm.prompt.input"));
        VBox promptBox = new VBox(8);
        promptBox.setVisible(action == AiAction.ASK);
        promptBox.setManaged(action == AiAction.ASK);
        if (action == AiAction.ASK) {
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            List<String> promptHistory = settings != null ? new ArrayList<>(settings.getAiPromptHistory()) : List.of();
            promptHistoryCombo.getItems().setAll(promptHistory);
            promptHistoryCombo.setPromptText(I18n.get("ai.confirm.prompt.history"));
            promptHistoryCombo.setOnAction(e -> {
                String selectedPrompt = promptHistoryCombo.getValue();
                if (selectedPrompt != null) {
                    promptArea.setText(selectedPrompt);
                }
            });
            clearPromptHistoryButton.setOnAction(e -> {
                GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
                if (globalSettings != null) {
                    globalSettings.clearAiPromptHistory();
                    try {
                        app.getGlobalSettingsManager().save();
                    } catch (Exception ex) {
                        logger.warn("Could not clear AI prompt history", ex);
                    }
                }
                promptHistoryCombo.getItems().clear();
                promptHistoryCombo.setValue(null);
            });
            promptBox.getChildren().addAll(
                new Label(I18n.get("ai.confirm.prompt.label")),
                new HBox(8, promptHistoryCombo, clearPromptHistoryButton),
                promptArea
            );
        }

        TextArea preview = new TextArea(selectedText);
        preview.setEditable(true);
        preview.setWrapText(true);
        preview.setPrefColumnCount(80);
        preview.setPrefRowCount(18);

        // The selection arrives already masked; say so, so the *** in the preview are explained.
        Label maskedSecretsLabel = new Label(
            maskedSecretCount > 0 ? I18n.get("ai.redaction.notice", maskedSecretCount) : "");
        maskedSecretsLabel.setWrapText(true);
        maskedSecretsLabel.setVisible(maskedSecretCount > 0);
        maskedSecretsLabel.setManaged(maskedSecretCount > 0);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        // Optional file attachment: offered when the selection looks like a file name in the pane's
        // directory. The file is validated in the background while the dialog is open; the user
        // keeps the last word via the checkbox, and OK waits for a pending check only while the
        // checkbox is selected.
        CheckBox attachmentCheck = new CheckBox();
        Label attachmentStatus = new Label();
        attachmentStatus.setWrapText(true);
        attachmentStatus.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        VBox attachmentBox = new VBox(4);
        attachmentBox.setVisible(attachmentCandidate != null);
        attachmentBox.setManaged(attachmentCandidate != null);
        java.util.concurrent.atomic.AtomicReference<AiFileAttachment> loadedAttachment =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean attachmentPending =
            new java.util.concurrent.atomic.AtomicBoolean(attachmentCandidate != null);
        java.util.function.Supplier<AiFileAttachment> effectiveAttachment =
            () -> attachmentCheck.isSelected() ? loadedAttachment.get() : null;

        Runnable refreshSummary = () -> {
            AiFileAttachment attachment = effectiveAttachment.get();
            long requestTokens = buildAiConfirmTokenEstimate(
                action, profile, preview.getText(), connectionName, languageCode, promptArea.getText(), attachment);
            summaryLabel.setText(buildAiConfirmSummary(
                action, profile, model, apiUrl, preview.getText(), connectionName, languageCode, promptArea.getText(), attachment));
            updateAiConfirmQuotaBar(quotaBar, profile, requestTokens);
        };
        refreshSummary.run();

        if (action == AiAction.ASK) {
            promptArea.textProperty().addListener((obs, oldValue, newValue) -> refreshSummary.run());
        }
        preview.textProperty().addListener((obs, oldValue, newValue) -> refreshSummary.run());
        attachmentCheck.selectedProperty().addListener((obs, oldValue, newValue) -> refreshSummary.run());

        Button findNextButton = new Button(I18n.get("editor.search.next"));
        findNextButton.setOnAction(e -> findNextInTextArea(preview, searchField.getText(), statusLabel));

        Button replaceButton = new Button(I18n.get("editor.search.replaceOne"));
        replaceButton.setOnAction(e -> replaceSelectionInTextArea(preview, searchField.getText(), replaceField.getText(), statusLabel));

        Button replaceAllButton = new Button(I18n.get("editor.search.replaceAll"));
        replaceAllButton.setOnAction(e -> replaceAllInTextArea(preview, searchField.getText(), replaceField.getText(), statusLabel));

        searchField.setOnAction(e -> findNextInTextArea(preview, searchField.getText(), statusLabel));
        replaceField.setOnAction(e -> replaceSelectionInTextArea(preview, searchField.getText(), replaceField.getText(), statusLabel));

        GridPane replaceGrid = new GridPane();
        replaceGrid.setHgap(8);
        replaceGrid.setVgap(8);
        replaceGrid.add(new Label(I18n.get("editor.search.find")), 0, 0);
        replaceGrid.add(searchField, 1, 0);
        replaceGrid.add(findNextButton, 2, 0);
        replaceGrid.add(new Label(I18n.get("editor.search.replace")), 0, 1);
        replaceGrid.add(replaceField, 1, 1);
        replaceGrid.add(new HBox(8, replaceButton, replaceAllButton), 2, 1);

        VBox content = new VBox(
            10, summaryLabel, quotaBar, replaceGrid, maskedSecretsLabel, preview, attachmentBox, promptBox, statusLabel);
        content.setPadding(new Insets(5, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(900);
        dialog.getDialogPane().setPrefHeight(650);
        Platform.runLater(() -> {
            if (action == AiAction.ASK) {
                promptArea.requestFocus();
                promptArea.positionCaret(promptArea.getLength());
            } else {
                searchField.requestFocus();
            }
        });

        final Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable updateOkButton = () -> {
            boolean promptMissing = action == AiAction.ASK
                && (promptArea.getText() == null || promptArea.getText().trim().isEmpty());
            boolean waitingForAttachment = attachmentCheck.isSelected() && attachmentPending.get();
            okButton.setDisable(promptMissing || waitingForAttachment);
        };
        updateOkButton.run();
        if (action == AiAction.ASK) {
            promptArea.textProperty().addListener((obs, oldValue, newValue) -> updateOkButton.run());
        }
        attachmentCheck.selectedProperty().addListener((obs, oldValue, newValue) -> updateOkButton.run());

        if (attachmentCandidate != null) {
            String fileName = attachmentCandidate.fileName();
            attachmentCheck.setText(I18n.get("ai.confirm.attachment.checkbox", fileName));
            attachmentCheck.setSelected(true);
            attachmentStatus.setText(I18n.get("ai.confirm.attachment.checking", fileName));
            attachmentBox.getChildren().addAll(attachmentCheck, attachmentStatus);
            updateOkButton.run();
            loadAiAttachmentAsync(attachmentCandidate, maxSelectionChars, outcome -> {
                attachmentPending.set(false);
                if (outcome.attachment() != null) {
                    loadedAttachment.set(outcome.attachment());
                    attachmentStatus.setText(I18n.get("ai.confirm.attachment.ready",
                        fileName, String.format(Locale.ROOT, "%,d", outcome.attachment().length())));
                } else {
                    attachmentCheck.setSelected(false);
                    attachmentCheck.setDisable(true);
                    attachmentStatus.setText(I18n.get("ai.confirm.attachment.unavailable", outcome.failureText()));
                }
                refreshSummary.run();
                updateOkButton.run();
            });
        }
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String promptText = promptArea.getText() != null && !promptArea.getText().isBlank() ? promptArea.getText().trim() : null;
            if (action == AiAction.ASK) {
                GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
                if (settings != null && promptText != null) {
                    settings.addAiPromptHistoryEntry(promptText);
                    try {
                        app.getGlobalSettingsManager().save();
                    } catch (Exception e) {
                        logger.warn("Could not persist AI prompt history", e);
                    }
                }
            }
            return new AiRequestDraft(preview.getText(), promptText, effectiveAttachment.get());
        });
        return dialog.showAndWait();
    }

    /**
     * Turns a terminal selection into an attachment offer: the selection must look like a file
     * name, the pane must have a connected SSH or local-shell session that is not suspected to be a
     * different identity (after su/ssh), and the profile's selection limit must leave room for the
     * file. Returns {@code null} when no attachment should be offered. Runs on the JavaFX thread.
     */
    private @Nullable AiAttachmentCandidate resolveAiAttachmentCandidate(
        TerminalTab terminalTab,
        @Nullable TerminalView.TerminalAgentRunContext runContext,
        String selectedText,
        int maxSelectionChars) {
        if (terminalTab == null || runContext == null
            || !RemoteTextFileSelectionSupport.isPlausibleFileName(selectedText)) {
            return null;
        }
        String fileName = RemoteTextFileSelectionSupport.normalizeSelectedFileName(selectedText);
        if (terminalTab.getTerminalView().isForeignSessionActive(runContext)) {
            return null;
        }
        // The selection itself stays in the request, so the file may only use the remaining budget.
        // Bytes bound characters for UTF-8, so checking the size against the character budget
        // before the transfer is a safe (conservative) pre-check.
        long maxBytes = (long) maxSelectionChars - selectedText.length();
        if (maxBytes <= 0) {
            return null;
        }
        Callable<TerminalRemoteTextFile> reader = createTerminalSelectionFileReader(
            terminalTab, runContext, new SelectedTerminalFile(fileName), false, maxBytes, maxBytes);
        return reader != null ? new AiAttachmentCandidate(fileName, reader) : null;
    }

    /**
     * Reads and validates the candidate file off the JavaFX thread and reports the outcome back on
     * it: either a ready attachment, or the user-facing reason why the file cannot be attached
     * (missing, not a regular file, binary / not UTF-8, too large for the context, ...).
     */
    private void loadAiAttachmentAsync(
        AiAttachmentCandidate candidate,
        int maxSelectionChars,
        Consumer<AiAttachmentOutcome> onDone) {
        Task<AiFileAttachment> task = new Task<>() {
            @Override
            protected AiFileAttachment call() throws Exception {
                TerminalRemoteTextFile file = candidate.reader().call();
                return new AiFileAttachment(file.fileName(), file.remotePath(), file.content());
            }
        };
        task.setOnSucceeded(event -> onDone.accept(new AiAttachmentOutcome(task.getValue(), null)));
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.info("Selected file '{}' was not attached to the AI request: {}",
                candidate.fileName(), failure != null ? failure.getMessage() : "unknown error");
            onDone.accept(new AiAttachmentOutcome(null, describeAiAttachmentFailure(candidate.fileName(), failure, maxSelectionChars)));
        });
        Thread thread = new Thread(task, "ai-attachment-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private String describeAiAttachmentFailure(String fileName, Throwable failure, int maxSelectionChars) {
        if (failure instanceof TerminalTextFileLoadException loadFailure
            && loadFailure.reason() == TerminalTextFileLoadFailure.TOO_LARGE) {
            return I18n.get("ai.attachment.tooLarge", maxSelectionChars, loadFailure.remotePath());
        }
        return terminalTextFileLoadFailureText(fileName, failure);
    }

    /** A selected file name that may be attached to an AI request, plus the validated reader for it. */
    private record AiAttachmentCandidate(String fileName, Callable<TerminalRemoteTextFile> reader) {
    }

    /** Result of {@link #loadAiAttachmentAsync}: exactly one of the two components is non-null. */
    private record AiAttachmentOutcome(@Nullable AiFileAttachment attachment, @Nullable String failureText) {
    }

    private String promptAreaInitialValue(AiAction action) {
        return action == AiAction.ASK ? "" : null;
    }

    private String aiModelDisplayText(AiProfile profile) {
        if (profile == null) {
            return "";
        }
        String model = profile.getModel() != null ? profile.getModel() : "";
        if (profile.getConnectionMode().isEmbedded()) {
            String embedded = profile.getEmbeddedModelId();
            return embedded != null && !embedded.isBlank()
                ? embedded
                : I18n.get("settings.ai.connectionMode."
                    + profile.getConnectionMode().name().toLowerCase(Locale.ROOT));
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            String provider = de.kortty.core.AiCliProviderRegistry.find(profile.getCliProviderId())
                .map(de.kortty.core.AiCliProviderDescriptor::displayName)
                .orElse(I18n.get("settings.ai.connectionMode.local_cli"));
            return model.isBlank() ? provider : provider + " / " + model;
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            return I18n.get("ai.model.default");
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.AUTO) {
            return model.isBlank()
                ? I18n.get("ai.model.auto")
                : I18n.get("ai.model.autoWithName", model);
        }
        return model;
    }

    private String buildAiConfirmSummary(
        AiAction action,
        AiProfile profile,
        String model,
        String apiUrl,
        String text,
        String connectionName,
        String languageCode,
        String userPrompt,
        @Nullable AiFileAttachment fileAttachment) {
        String safeText = text != null ? text : "";
        long requestTokens = buildAiConfirmTokenEstimate(action, profile, safeText, connectionName, languageCode, userPrompt, fileAttachment);
        long remainingTokens = AiTokenUsageManager.remainingAfter(profile, requestTokens);
        AiTokenWarningLevel warningLevel = AiTokenUsageManager.determineProjectedWarningLevel(profile, requestTokens);
        long characterCount = safeText.length() + (fileAttachment != null ? fileAttachment.length() : 0);
        return I18n.get(
            "ai.confirm.summary",
            getAiProfileDisplayName(profile),
            model,
            apiUrl,
            characterCount,
            requestTokens,
            formatRemainingTokens(remainingTokens),
            I18n.get("settings.ai.token.warning." + warningLevel.name().toLowerCase(Locale.ROOT)));
    }

    private long buildAiConfirmTokenEstimate(
        AiAction action,
        AiProfile profile,
        String text,
        String connectionName,
        String languageCode,
        String userPrompt,
        @Nullable AiFileAttachment fileAttachment) {
        AiRequest request = new AiRequest(action, text != null ? text : "", connectionName, languageCode, userPrompt)
            .withFileAttachment(fileAttachment);
        return countAiRequestTokens(profile, request);
    }

    private long countAiRequestTokens(AiProfile profile, AiRequest request) {
        AiTokenizerType tokenizerType = profile != null && profile.getTokenizerType() != null
            ? profile.getTokenizerType()
            : AiTokenizerType.ESTIMATE;
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        return AiTokenCounter.countRequestTokens(request, tokenizerType, AiSkillPromptSupport.fromSettings(settings));
    }

    private String formatRemainingTokens(long remainingTokens) {
        if (remainingTokens == Long.MAX_VALUE) {
            return I18n.get("settings.ai.token.unlimited");
        }
        return AiTokenUsageManager.formatCompact(remainingTokens);
    }

    private void updateAiConfirmQuotaBar(AiQuotaBar quotaBar, AiProfile profile, long requestTokens) {
        if (quotaBar == null) {
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(profile);
        long projectedUsed = snapshot.usedTotalTokens() + Math.max(0L, requestTokens);
        double usedFraction = snapshot.unlimited() || snapshot.maxTokens() <= 0
            ? 0.0
            : Math.min(1.0, projectedUsed / (double) snapshot.maxTokens());
        quotaBar.update(
            usedFraction,
            profile != null && profile.getTokenWarningYellowPercent() != null ? profile.getTokenWarningYellowPercent() : 75,
            profile != null && profile.getTokenWarningRedPercent() != null ? profile.getTokenWarningRedPercent() : 90,
            AiTokenUsageManager.determineProjectedWarningLevel(profile, requestTokens),
            snapshot.unlimited());
    }

    private void recordAiUsage(AiProfile profile, AiRequest request, AiExecutionResult result) {
        if (profile == null || profile.getId() == null) {
            return;
        }
        AiTokenUsage usage = result != null ? result.usage() : null;
        if (usage == null) {
            long promptTokens = countAiRequestTokens(profile, request);
            long completionTokens = AiTokenCounter.countTextTokens(
                result != null ? result.content() : "",
                profile.getTokenizerType() != null ? profile.getTokenizerType() : AiTokenizerType.ESTIMATE);
            usage = new AiTokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        }
        recordAiUsage(profile, usage);
    }

    private void recordAiUsage(AiProfile profile, AiTokenUsage usage) {
        if (profile == null || profile.getId() == null || usage == null) {
            return;
        }
        de.kortty.core.AiUsageRecorder recorder = app != null ? app.getAiUsageRecorder() : null;
        if (recorder != null) {
            recorder.record(profile, usage);
        }
    }

    private void findNextInTextArea(TextArea textArea, String search, Label statusLabel) {
        if (search == null || search.isEmpty()) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        String text = textArea.getText();
        int searchStart = Math.max(textArea.getSelection().getEnd(), textArea.getCaretPosition());
        int index = text.indexOf(search, searchStart);
        if (index < 0 && searchStart > 0) {
            index = text.indexOf(search);
        }
        if (index < 0) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        textArea.requestFocus();
        textArea.selectRange(index, index + search.length());
        textArea.positionCaret(index + search.length());
        statusLabel.setText(I18n.get("ai.confirm.foundAt", index + 1));
    }

    private void replaceSelectionInTextArea(TextArea textArea, String search, String replacement, Label statusLabel) {
        if (search == null || search.isEmpty()) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        String selectedText = textArea.getSelectedText();
        if (!search.equals(selectedText)) {
            findNextInTextArea(textArea, search, statusLabel);
            selectedText = textArea.getSelectedText();
            if (!search.equals(selectedText)) {
                return;
            }
        }
        textArea.replaceSelection(replacement != null ? replacement : "");
        statusLabel.setText(I18n.get("editor.status.replaced", 1));
    }

    private void replaceAllInTextArea(TextArea textArea, String search, String replacement, Label statusLabel) {
        if (search == null || search.isEmpty()) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        String text = textArea.getText();
        int replacements = countOccurrences(text, search);
        if (replacements == 0) {
            statusLabel.setText(I18n.get("editor.search.noMatches"));
            return;
        }
        textArea.setText(text.replace(search, replacement != null ? replacement : ""));
        statusLabel.setText(I18n.get("editor.status.replaced", replacements));
    }

    private int countOccurrences(String text, String search) {
        if (text == null || text.isEmpty() || search == null || search.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) >= 0) {
            count++;
            index += search.length();
        }
        return count;
    }

    private void insertTemporaryTab(Tab tab) {
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }

    private void installAiSelectionHandler(TerminalTab terminalTab) {
        // Handlers left unset for policy-denied features make the corresponding terminal
        // context-menu items disappear entirely (TerminalView builds items from handler presence).
        de.kortty.policy.EffectivePolicy policy = de.kortty.policy.PolicyManager.effective();
        if (policy.aiChatAllowed()) {
            terminalTab.getTerminalView().setAiSelectionHandler((action, profile, selectedText, runContext) ->
                handleAiTextAction(terminalTab, action, profile, selectedText, AiTextActionInput.Origin.SELECTION,
                    runContext));
            terminalTab.getTerminalView().setAiRecentOutputHandler((profile, output, runContext) ->
                handleAiRecentOutputAction(terminalTab, profile, output, runContext));
        }
        terminalTab.getTerminalView().setSftpHereHandler(pane -> openSftpHere(terminalTab, pane));
        terminalTab.getTerminalView().setSftpOpenAtHandler((pane, path) -> openSftpHere(terminalTab, pane, path));
        applyRemoteSidebar(terminalTab);
        if (policy.loadIntoSnippetEditor() != de.kortty.policy.LoadIntoEditorMode.DENY) {
            terminalTab.getTerminalView().setTerminalTextFileLoadHandler((runContext, selectedText) ->
                loadTerminalSelectionAsTextFile(terminalTab, runContext, selectedText));
            // Without it no pane finds file paths in its output and OSC 8 file: links stay plain text.
            terminalTab.getTerminalView().setTerminalPathOpenHandler((runContext, link) ->
                openTerminalPathInSnippetEditor(terminalTab, runContext, link));
        }
        if (policy.aiAgentAllowed()) {
            terminalTab.getTerminalView().setAiAgentHandler(runContext ->
                requestAiAgentForTab(terminalTab, false, null, null, false, false, runContext));
            terminalTab.getTerminalView().setAiAgentAskHandler((runContext, selectedText) ->
                requestAiAgentForTab(terminalTab, true, null, null, false, false, runContext, selectedText));
            terminalTab.getTerminalView().setTerminalAgentShortcutHandler((rawCommand, runContext) ->
                handleTerminalAgentShortcut(terminalTab, rawCommand, runContext));
        }
        if (policy.aiPlanningAllowed()) {
            terminalTab.getTerminalView().setAiPlanningHandler(runContext ->
                requestAiPlanningForTab(terminalTab, null, null, runContext));
        }
        terminalTab.getTerminalView().setMenuBarRestoreHandler(
            () -> menuBar != null && !menuBar.isVisible(),
            () -> toggleMenuBarVisibility(true));
    }

    private String getAiActionLabel(AiAction action) {
        return switch (action) {
            case SUMMARIZE -> I18n.get("terminal.contextMenu.ai.summarize");
            case SOLVE_PROBLEM -> I18n.get("terminal.contextMenu.ai.solve");
            case ASK -> I18n.get("terminal.contextMenu.ai.ask");
            case GENERATE_CHAT_TITLE -> I18n.get("ai.action.generateTitle");
            case GENERATE_SNIPPET_METADATA -> I18n.get("ai.result.saveSnippet");
            case CORRECT_SNIPPET_DESCRIPTION -> I18n.get("snippets.description.correct");
            case CORRECT_SNIPPET_SELECTION_TEXT -> I18n.get("snippets.ai.menu.correct");
            case TRANSLATE_SNIPPET_SELECTION_TEXT -> I18n.get("snippets.ai.menu.translate");
            case DESCRIBE_SNIPPET_SELECTION, DESCRIBE_SNIPPET_FULL -> I18n.get("snippets.ai.menu.describe");
            case GENERATE_SNIPPET_ALTERNATIVES -> I18n.get("snippets.ai.alternatives.context");
            case COMPLETE_SNIPPET_CODE -> I18n.get("snippets.ai.code.complete");
            case REVIEW_SNIPPET_CODE, ANALYZE_SNIPPET_CODE, APPLY_SNIPPET_IMPROVEMENTS -> I18n.get("snippets.ai.code.review");
            case IMPROVE_SNIPPET_CODE -> I18n.get("snippets.ai.code.improve.custom");
            case MIGRATE_SNIPPET_LANGUAGE -> I18n.get("snippets.ai.code.migrate");
            case ASSIST_SNIPPET_CODE -> I18n.get("snippets.ai.assistant.context");
            case SECURITY_REVIEW_SNIPPET_CODE, APPLY_SNIPPET_SECURITY_FIXES -> I18n.get("snippets.ai.security.title");
            case GENERATE_SNIPPET_ONE_LINER -> I18n.get("snippets.oneliner.compact");
            case GENERATE_SNIPPET_MERMAID -> I18n.get("snippets.ai.diagram.menu");
            case GENERATE_ASCII_ART -> I18n.get("asciiArt.ai.action");
            case ANALYZE_SNIPPET_PROJECT -> I18n.get("snippets.folder.analyze");
            case PLAN_SNIPPET_MODULARIZATION, GENERATE_SNIPPET_MODULE -> I18n.get("snippets.modularize.title");
        };
    }

    private void loadTerminalSelectionAsTextFile(
        TerminalTab terminalTab,
        TerminalView.TerminalAgentRunContext runContext,
        String selectedText
    ) {
        // Handler installation is already skipped under a deny policy; this guard covers any
        // other invocation path.
        if (de.kortty.policy.PolicyManager.effective().loadIntoSnippetEditor()
            == de.kortty.policy.LoadIntoEditorMode.DENY) {
            return;
        }
        String selectedFileName;
        try {
            selectedFileName = RemoteTextFileSelectionSupport.normalizeSelectedFileName(selectedText);
        } catch (IllegalArgumentException e) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.invalidSelection"));
            return;
        }
        Telemetry.track(TelemetryEvents.FILE_LOADED_AS_TEXT, Map.of("source", "terminal_selection"));

        TerminalView.TerminalAgentRunContext resolvedContext = runContext != null
            ? runContext
            : terminalTab.getTerminalView().captureTerminalAgentRunContext();
        ObservableTtyConnector contextConnector = resolvedContext != null ? resolvedContext.connector() : null;
        // Evaluated on the JavaFX thread (reads the screen buffer). After su/ssh inside the
        // session, both the tracked directories and the SFTP/local read identity are wrong — the
        // context-menu item is already disabled then, this guards every other invocation path.
        boolean foreignSession =
            terminalTab.getTerminalView().isForeignSessionActive(resolvedContext);
        TerminalFileRequest request = new SelectedTerminalFile(selectedFileName);
        Callable<TerminalRemoteTextFile> reader = createTerminalSelectionFileReader(
            terminalTab, resolvedContext, request, foreignSession,
            Long.MAX_VALUE, MAX_LOCAL_TEXT_FILE_LOAD_BYTES);
        if (reader == null) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.notConnected"));
            return;
        }
        if (contextConnector instanceof SshTtyConnector connector) {
            runTerminalTextFileLoadTask(request, reader,
                remoteFile -> openTerminalRemoteTextFileInSnippetEditor(terminalTab, connector, remoteFile, false));
        } else {
            runTerminalTextFileLoadTask(request, reader,
                localFile -> openTerminalLocalTextFileInSnippetEditor(terminalTab, localFile, false));
        }
    }

    /**
     * Opens the file a link in terminal output points to (a path printed as plain text or an OSC 8
     * {@code file:} target) in the Snippet Editor: read over SFTP in an SSH tab and from disk in a
     * local-shell tab, with the same guards as "Open in Snippet Editor" (the policy, the
     * foreign-session check, regular UTF-8 text files only) and a {@value #MAX_LOCAL_TEXT_FILE_LOAD_BYTES}
     * byte cap on both sides. A relative path resolves against the shell's directory now. A file an
     * OSC 8 link names opens read-only: the program that printed the link chose the path behind its
     * text. Runs on the JavaFX thread; {@code runContext} is the clicked pane's.
     */
    private void openTerminalPathInSnippetEditor(
        TerminalTab terminalTab,
        @Nullable TerminalView.TerminalAgentRunContext runContext,
        TerminalFileLink link
    ) {
        if (de.kortty.policy.PolicyManager.effective().loadIntoSnippetEditor()
            == de.kortty.policy.LoadIntoEditorMode.DENY) {
            return;
        }
        if (runContext == null) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.notConnected"));
            return;
        }
        Telemetry.track(TelemetryEvents.FILE_LOADED_AS_TEXT, Map.of("source", "terminal_selection"));
        // Evaluated on the JavaFX thread (reads the screen buffer): after su/ssh inside the session
        // the path would be read as the wrong identity, or on the wrong host.
        boolean foreignSession = terminalTab.getTerminalView().isForeignSessionActive(runContext);
        TerminalFileRequest request = new LinkedTerminalFile(link.path());
        Callable<TerminalRemoteTextFile> reader = createTerminalSelectionFileReader(
            terminalTab, runContext, request, foreignSession,
            MAX_LOCAL_TEXT_FILE_LOAD_BYTES, MAX_LOCAL_TEXT_FILE_LOAD_BYTES);
        if (reader == null) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.notConnected"));
            return;
        }
        boolean readOnly = link.fromOsc8();
        if (runContext.connector() instanceof SshTtyConnector connector) {
            runTerminalTextFileLoadTask(request, reader,
                remoteFile -> openTerminalRemoteTextFileInSnippetEditor(terminalTab, connector, remoteFile, readOnly));
        } else {
            runTerminalTextFileLoadTask(request, reader,
                localFile -> openTerminalLocalTextFileInSnippetEditor(terminalTab, localFile, readOnly));
        }
    }

    /**
     * Which file a terminal file read opens, and how to find it: a file name the user selected in
     * the shell's directory, or a path a link in the output names.
     */
    private sealed interface TerminalFileRequest permits SelectedTerminalFile, LinkedTerminalFile {
        /** The file name the editor shows. */
        String fileName();

        /** The file as messages name it. */
        String label();

        /** Whether resolving it needs the shell's working directory. */
        boolean needsWorkingDirectory();

        /** @throws IllegalArgumentException when the name cannot be resolved */
        String remotePath(@Nullable String workingDirectory, String sftpStartDirectory);

        /** @throws IllegalArgumentException when the name cannot be resolved */
        Path localPath(@Nullable String workingDirectory, String startDirectory, String homeDirectory)
            throws RemoteTextFileSelectionSupport.UnmappableWorkingDirectoryException;
    }

    /** A selected file name in the shell's working directory ("Open in Snippet Editor", AI attachments). */
    private record SelectedTerminalFile(String fileName) implements TerminalFileRequest {
        @Override
        public String label() {
            return fileName;
        }

        @Override
        public boolean needsWorkingDirectory() {
            return true;
        }

        @Override
        public String remotePath(@Nullable String workingDirectory, String sftpStartDirectory) {
            return RemoteTextFileSelectionSupport.resolveRemoteFilePath(workingDirectory, fileName, sftpStartDirectory);
        }

        @Override
        public Path localPath(@Nullable String workingDirectory, String startDirectory, String homeDirectory)
            throws RemoteTextFileSelectionSupport.UnmappableWorkingDirectoryException {
            return RemoteTextFileSelectionSupport.resolveLocalFilePath(
                workingDirectory, fileName, startDirectory, homeDirectory);
        }
    }

    /** A path a link in terminal output names, absolute, home-relative or relative to the shell's directory. */
    private record LinkedTerminalFile(String path) implements TerminalFileRequest {
        @Override
        public String fileName() {
            String trimmed = path.replaceAll("[/\\\\]+$", "");
            int separator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            String name = trimmed.substring(separator + 1);
            return name.isEmpty() ? path : name;
        }

        @Override
        public String label() {
            return path;
        }

        @Override
        public boolean needsWorkingDirectory() {
            return RemoteTextFileSelectionSupport.isWorkingDirectoryRelative(path);
        }

        @Override
        public String remotePath(@Nullable String workingDirectory, String sftpStartDirectory) {
            return RemoteTextFileSelectionSupport.resolveRemotePath(workingDirectory, path, sftpStartDirectory);
        }

        @Override
        public Path localPath(@Nullable String workingDirectory, String startDirectory, String homeDirectory)
            throws RemoteTextFileSelectionSupport.UnmappableWorkingDirectoryException {
            return RemoteTextFileSelectionSupport.resolveLocalPath(workingDirectory, path, startDirectory, homeDirectory);
        }
    }

    /**
     * Builds the background reader that resolves {@code request} against the pane's directories and
     * returns its validated UTF-8 text content — over SFTP for SSH sessions, from the local
     * filesystem for local-shell tabs. Shared by "Open in Snippet Editor", links to files and the AI
     * chat attachment. Returns {@code null} when the pane has no usable connection.
     * {@code foreignSession} must have been evaluated on the JavaFX thread beforehand; the reader
     * itself must run off it.
     *
     * @param maxRemoteBytes size limit for SFTP reads ({@code Long.MAX_VALUE} for none)
     * @param maxLocalBytes  size limit for local reads
     */
    private @Nullable Callable<TerminalRemoteTextFile> createTerminalSelectionFileReader(
        TerminalTab terminalTab,
        @Nullable TerminalView.TerminalAgentRunContext resolvedContext,
        TerminalFileRequest request,
        boolean foreignSession,
        long maxRemoteBytes,
        long maxLocalBytes
    ) {
        ObservableTtyConnector contextConnector = resolvedContext != null ? resolvedContext.connector() : null;
        if (contextConnector instanceof SshTtyConnector connector
            && connector.isConnected()
            && connector.getSession() != null) {
            String workingDirectory = resolvedContext.workingDirectory() != null && !resolvedContext.workingDirectory().isBlank()
                ? resolvedContext.workingDirectory()
                : connector.getCurrentRemoteDirectory();
            return () -> {
                if (foreignSession) {
                    throw new TerminalTextFileLoadException(
                        TerminalTextFileLoadFailure.FOREIGN_SESSION, request.label());
                }
                return readTerminalRemoteTextFile(connector, workingDirectory, request, maxRemoteBytes);
            };
        }
        if (contextConnector instanceof LocalShellTtyConnector localConnector && localConnector.isConnected()) {
            // The prompt-derived directory is the JAT-safe fallback; capture it now. The live OS-level
            // cwd query (lsof on macOS) must NOT run on the JavaFX thread, so it and the path
            // resolution happen inside the background load task below.
            String promptWorkingDirectory = resolvedContext.workingDirectory();
            String startDirectory = localConnector.getStartDirectory();
            String homeDirectory = localConnector.getHomeRemoteDirectory();
            return () -> {
                if (foreignSession) {
                    throw new TerminalTextFileLoadException(
                        TerminalTextFileLoadFailure.FOREIGN_SESSION, request.label());
                }
                // Ground truth first: the shell's live OS cwd, which reflects every cd and beats the
                // prompt-derived directory (null whenever the prompt shows only the folder basename,
                // the macOS zsh default). Native Windows shells use their absolute prompt path. A
                // previously observed directory-change command makes the start directory unsafe as a
                // fallback until either source confirms the new cwd. An absolute or home path needs
                // none of that.
                String liveWorkingDirectory = request.needsWorkingDirectory()
                    ? localConnector.refreshCurrentWorkingDirectory()
                    : null;
                String workingDirectory;
                if (!request.needsWorkingDirectory()) {
                    workingDirectory = null;
                } else if (liveWorkingDirectory != null && !liveWorkingDirectory.isBlank()) {
                    workingDirectory = liveWorkingDirectory;
                } else {
                    if (promptWorkingDirectory != null && !promptWorkingDirectory.isBlank()) {
                        String trustedPromptDirectory = LocalShellTtyConnector
                            .normalizeTrustedLocalDirectory(promptWorkingDirectory);
                        if (trustedPromptDirectory == null
                            && TerminalView.isAbsoluteWorkingDirectorySyntax(promptWorkingDirectory)) {
                            throw new TerminalTextFileLoadException(
                                TerminalTextFileLoadFailure.UNMAPPABLE_WORKING_DIRECTORY,
                                promptWorkingDirectory);
                        }
                        localConnector.updateCurrentWorkingDirectoryHint(trustedPromptDirectory);
                    }
                    if (localConnector.hasUnresolvedWorkingDirectoryChange()) {
                        throw new TerminalTextFileLoadException(
                            TerminalTextFileLoadFailure.WORKING_DIRECTORY_UNKNOWN,
                            request.label());
                    }
                    workingDirectory = localConnector.getCurrentWorkingDirectory();
                }
                Path filePath;
                try {
                    filePath = request.localPath(workingDirectory, startDirectory, homeDirectory);
                } catch (RemoteTextFileSelectionSupport.UnmappableWorkingDirectoryException e) {
                    throw new TerminalTextFileLoadException(
                        TerminalTextFileLoadFailure.UNMAPPABLE_WORKING_DIRECTORY, e.workingDirectory(), e);
                } catch (IllegalArgumentException e) {
                    throw new TerminalTextFileLoadException(
                        TerminalTextFileLoadFailure.INVALID_SELECTION, request.label(), e);
                }
                return readTerminalLocalTextFile(filePath, request.fileName(), maxLocalBytes);
            };
        }
        return null;
    }

    private void runTerminalTextFileLoadTask(
        TerminalFileRequest request,
        Callable<TerminalRemoteTextFile> reader,
        Consumer<TerminalRemoteTextFile> editorOpener
    ) {
        String selectedFileName = request.label();
        updateStatus(I18n.get("terminal.loadTextFile.loading", selectedFileName));
        Task<TerminalRemoteTextFile> task = new Task<>() {
            @Override
            protected TerminalRemoteTextFile call() throws Exception {
                return reader.call();
            }
        };
        task.setOnSucceeded(event -> editorOpener.accept(task.getValue()));
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            if (request instanceof LinkedTerminalFile) {
                logLinkedTerminalFileFailure(selectedFileName, failure);
            } else {
                logger.error("Failed to load selected terminal text as text file '{}'", selectedFileName, failure);
            }
            showTerminalTextFileLoadFailure(request, failure);
        });
        Thread thread = new Thread(task, "terminal-text-file-loader");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Logs a failed open of a file a terminal link names. Any path printed in the output can be
     * Cmd/Ctrl+clicked, so a missing, binary or oversized file is an everyday outcome that the
     * dialog already explains: it is logged at INFO by its reason only, never as an ERROR (which
     * would also count in the error telemetry), and the path, which can say more than the user
     * wants in a log, only at DEBUG. Anything unexpected stays an ERROR, still without the path.
     */
    private static void logLinkedTerminalFileFailure(String path, @Nullable Throwable failure) {
        if (failure instanceof TerminalTextFileLoadException loadFailure) {
            logger.info("Could not open the file a terminal link names: {}", loadFailure.reason());
        } else {
            logger.error("Failed to open the file a terminal link names", failure);
        }
        logger.debug("Linked terminal file that failed to open: '{}'", path, failure);
    }

    // SFTP reads are naturally bounded by transfer time; a local read has no such deterrent
    // against accidentally selecting a huge file (e.g. a multi-GB log), so guard it explicitly.
    private static final long MAX_LOCAL_TEXT_FILE_LOAD_BYTES = 10L * 1024 * 1024;

    private TerminalRemoteTextFile readTerminalLocalTextFile(Path filePath, String selectedFileName, long maxBytes)
        throws Exception {
        if (!Files.exists(filePath)) {
            throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_FOUND, filePath.toString());
        }
        if (!Files.isRegularFile(filePath)) {
            throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_REGULAR_FILE, filePath.toString());
        }
        if (Files.size(filePath) > maxBytes) {
            throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.TOO_LARGE, filePath.toString());
        }
        if (!Files.isReadable(filePath)) {
            throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_READABLE, filePath.toString());
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(filePath)) {
            // The size can grow after the check (a log being written) or be reported as 0 (/proc).
            bytes = readAtMost(input, maxBytes, filePath.toString());
        }
        String content;
        try {
            content = RemoteTextFileSelectionSupport.decodeUtf8TextFile(bytes);
        } catch (RemoteTextFileSelectionSupport.BinaryOrNonTextFileException e) {
            throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.BINARY_OR_NON_TEXT, filePath.toString(), e);
        }
        return new TerminalRemoteTextFile(selectedFileName, filePath.toString(), content);
    }

    private TerminalRemoteTextFile readTerminalRemoteTextFile(
        SshTtyConnector connector,
        String workingDirectory,
        TerminalFileRequest request,
        long maxBytes
    ) throws Exception {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connector.getSession())) {
            String sftpStartDirectory = resolveSftpStartDirectory(sftp);
            String remotePath;
            try {
                remotePath = request.remotePath(workingDirectory, sftpStartDirectory);
            } catch (IllegalArgumentException e) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.INVALID_SELECTION, request.label(), e);
            }
            SftpClient.Attributes attributes = statTerminalRemotePath(sftp, remotePath);
            if (!attributes.isRegularFile()) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_REGULAR_FILE, remotePath);
            }
            if (attributes.getSize() > maxBytes) {
                // Checked before the transfer so an oversized file never travels over the wire.
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.TOO_LARGE, remotePath);
            }
            byte[] bytes = readTerminalRemoteFileBytes(sftp, remotePath, maxBytes);
            String content;
            try {
                content = RemoteTextFileSelectionSupport.decodeUtf8TextFile(bytes);
            } catch (RemoteTextFileSelectionSupport.BinaryOrNonTextFileException e) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.BINARY_OR_NON_TEXT, remotePath, e);
            }
            return new TerminalRemoteTextFile(request.fileName(), remotePath, content);
        }
    }

    private SftpClient.Attributes statTerminalRemotePath(SftpClient sftp, String remotePath) throws Exception {
        try {
            return sftp.stat(remotePath);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_FOUND, remotePath, e);
            }
            if (e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_READABLE, remotePath, e);
            }
            throw e;
        }
    }

    private byte[] readTerminalRemoteFileBytes(SftpClient sftp, String remotePath, long maxBytes) throws Exception {
        try (InputStream input = sftp.read(remotePath)) {
            // The stat before the transfer can be outdated by a file that grows meanwhile.
            return readAtMost(input, maxBytes, remotePath);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.NOT_READABLE, remotePath, e);
            }
            throw e;
        }
    }

    /**
     * Reads {@code input} to its end, but fails with {@code TOO_LARGE} as soon as it holds more than
     * {@code maxBytes}, so a file that grows during the read or reports a wrong size still stays
     * within the cap.
     */
    private static byte[] readAtMost(InputStream input, long maxBytes, String path) throws IOException,
        TerminalTextFileLoadException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new TerminalTextFileLoadException(TerminalTextFileLoadFailure.TOO_LARGE, path);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String resolveSftpStartDirectory(SftpClient sftp) {
        try {
            String directory = sftp.canonicalPath(".");
            if (directory != null && !directory.isBlank()) {
                return directory.trim();
            }
        } catch (IOException e) {
            logger.debug("Could not resolve SFTP start directory for terminal text-file load: {}", e.getMessage());
        }
        return ".";
    }

    /**
     * @param fromOsc8Link the file was opened from an OSC 8 link: read-only, the file actions are
     *     locked in the dialog
     */
    private void openTerminalRemoteTextFileInSnippetEditor(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        TerminalRemoteTextFile remoteFile,
        boolean fromOsc8Link
    ) {
        // Policy read-only mode: the file may be loaded and saved as a snippet, but never written
        // back to the target system — the remote actions are locked in the dialog.
        boolean remoteWriteAllowed = !fromOsc8Link
            && de.kortty.policy.PolicyManager.effective().loadIntoSnippetEditor()
            == de.kortty.policy.LoadIntoEditorMode.ALLOW;
        openTerminalTextFileInSnippetEditor(terminalTab, remoteFile,
            I18n.get("sftp.snippetEditor.overwriteRemote"),
            remoteWriteAllowed
                ? draft -> overwriteTerminalRemoteTextFile(connector, remoteFile.remotePath(), draft)
                : null,
            remoteWriteAllowed
                ? draft -> saveTerminalRemoteTextFileAs(connector, remoteFile.remotePath(), remoteFile.fileName(), draft)
                : null,
            fromOsc8Link ? I18n.get("terminal.links.fileReadOnly") : null);
    }

    /**
     * @param fromOsc8Link the file was opened from an OSC 8 link: read-only, the file actions are
     *     locked in the dialog
     */
    private void openTerminalLocalTextFileInSnippetEditor(
        TerminalTab terminalTab,
        TerminalRemoteTextFile localFile,
        boolean fromOsc8Link
    ) {
        Path filePath = Path.of(localFile.remotePath());
        openTerminalTextFileInSnippetEditor(terminalTab, localFile,
            I18n.get("sftp.snippetEditor.overwriteLocal"),
            fromOsc8Link ? null : draft -> overwriteTerminalLocalTextFile(filePath, draft),
            fromOsc8Link ? null : draft -> saveTerminalLocalTextFileAs(filePath, draft),
            fromOsc8Link ? I18n.get("terminal.links.fileReadOnly") : null);
    }

    /**
     * @param lockedReason why a {@code null} overwrite or save-as action is locked, or {@code null}
     *     for the enterprise policy's read-only mode
     */
    private void openTerminalTextFileInSnippetEditor(
        TerminalTab terminalTab,
        TerminalRemoteTextFile file,
        String overwriteLabel,
        SnippetEditDialog.ExternalFileAction overwriteAction,
        SnippetEditDialog.ExternalFileAction saveAsAction,
        @Nullable String lockedReason
    ) {
        SnippetManager snippetManager = app.getSnippetManager();
        if (snippetManager == null) {
            showError(I18n.get("error.title"), I18n.get("terminal.loadTextFile.snippetManagerMissing"));
            return;
        }
        Snippet snippet = createTerminalFileSnippetDraft(file.fileName(), file.content());
        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            file.remotePath(),
            overwriteLabel,
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            overwriteAction,
            saveAsAction,
            this::saveTerminalDraftAsSnippet,
            lockedReason
        );
        List<String> categoryNames = snippetManager.getAllCategories().stream()
            .map(SnippetCategory::getName)
            .toList();
        SnippetEditDialog.AiAssist aiAssist = SnippetAiAssistFactory.create(
            this, terminalTab != null ? terminalTab.getConnection() : null);
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, categoryNames, aiAssist, config);
        dialog.initOwner(stage);
        dialog.showNonBlocking(null);
        updateStatus(I18n.get("terminal.loadTextFile.loaded", file.remotePath()));
    }

    private boolean overwriteTerminalLocalTextFile(Path filePath, Snippet draft) throws IOException {
        AtomicFileWriter.writeStringAtomically(filePath, draft.getContent() != null ? draft.getContent() : "");
        return true;
    }

    private boolean saveTerminalLocalTextFileAs(Path sourcePath, Snippet draft) throws Exception {
        File targetFile = callOnFxThread(() -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("sftp.snippetEditor.saveAs"));
            if (sourcePath.getParent() != null && Files.isDirectory(sourcePath.getParent())) {
                chooser.setInitialDirectory(sourcePath.getParent().toFile());
            }
            if (sourcePath.getFileName() != null) {
                chooser.setInitialFileName(sourcePath.getFileName().toString());
            }
            return chooser.showSaveDialog(stage);
        });
        if (targetFile == null) {
            return false;
        }
        Files.writeString(
            targetFile.toPath(),
            draft.getContent() != null ? draft.getContent() : "",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        return true;
    }

    private Snippet createTerminalFileSnippetDraft(String fileName, String content) {
        Snippet snippet = new Snippet();
        snippet.setName(fileName);
        snippet.setContent(content);
        snippet.setLanguage(SnippetLanguageSupport.detectFileLanguage(fileName, content));
        snippet.setCategory("");
        snippet.setDescription("");
        snippet.setTagsFromString("");
        return snippet;
    }

    private boolean overwriteTerminalRemoteTextFile(SshTtyConnector connector, String remotePath, Snippet draft) throws Exception {
        requireTerminalRemoteWriteAllowed();
        uploadTerminalRemoteTextFile(connector, remotePath, draft.getContent());
        return true;
    }

    /** Backstop for the policy's read-only load-into-snippet-editor mode. */
    private static void requireTerminalRemoteWriteAllowed() {
        if (de.kortty.policy.PolicyManager.effective().loadIntoSnippetEditor()
            != de.kortty.policy.LoadIntoEditorMode.ALLOW) {
            throw new de.kortty.policy.PolicyRestrictionException(
                I18n.get("policy.terminal.loadReadOnly"));
        }
    }

    private boolean saveTerminalRemoteTextFileAs(
        SshTtyConnector connector,
        String originalRemotePath,
        String originalFileName,
        Snippet draft
    ) throws Exception {
        requireTerminalRemoteWriteAllowed();
        Optional<String> response = callOnFxThread(() -> {
            TextInputDialog dialog = new TextInputDialog(originalFileName);
            DialogThemeHelper.applyTheme(dialog);
            dialog.setTitle(I18n.get("sftp.snippetEditor.remoteFileName.title"));
            dialog.setHeaderText(I18n.get("sftp.snippetEditor.remoteFileName.header"));
            dialog.setContentText(I18n.get("sftp.snippetEditor.remoteFileName.content"));
            dialog.initOwner(stage);
            return dialog.showAndWait();
        });
        if (response.isEmpty()) {
            return false;
        }

        String targetPath;
        try {
            targetPath = SftpFileTransferService.resolveSiblingRemoteFilePath(originalRemotePath, response.get());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(I18n.get("sftp.snippetEditor.invalidFileName", response.get()), e);
        }
        if (terminalRemoteFileExists(connector, targetPath) && !confirmTerminalRemoteOverwrite(targetPath)) {
            return false;
        }
        uploadTerminalRemoteTextFile(connector, targetPath, draft.getContent());
        return true;
    }

    private void uploadTerminalRemoteTextFile(SshTtyConnector connector, String remotePath, String content) throws IOException {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connector.getSession());
             OutputStream output = sftp.write(remotePath, EnumSet.of(
                 SftpClient.OpenMode.Write,
                 SftpClient.OpenMode.Create,
                 SftpClient.OpenMode.Truncate))) {
            output.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean terminalRemoteFileExists(SshTtyConnector connector, String remotePath) {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(connector.getSession())) {
            sftp.stat(remotePath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean confirmTerminalRemoteOverwrite(String targetLabel) throws Exception {
        return callOnFxThread(() -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            DialogThemeHelper.applyTheme(confirm);
            confirm.setTitle(I18n.get("sftp.snippetEditor.confirmOverwrite.title"));
            confirm.setHeaderText(I18n.get("sftp.snippetEditor.confirmOverwrite.header"));
            confirm.setContentText(I18n.get("sftp.snippetEditor.confirmOverwrite.content", targetLabel));
            confirm.initOwner(stage);
            return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        });
    }

    private boolean saveTerminalDraftAsSnippet(Snippet draft) throws Exception {
        SnippetManager snippetManager = app.getSnippetManager();
        Snippet snippet = copyTerminalSnippetForManager(draft);
        // The editor runs this on a worker thread; the SnippetManager is FX-thread state (its
        // lists are read by open dialogs and its change listeners expect FX), so mutate and save
        // there and let any failure propagate unchanged.
        return callOnFxThread(() -> {
            snippetManager.ensureCategory(snippet.getCategory());
            snippetManager.addSnippet(snippet);
            snippetManager.save();
            return true;
        });
    }

    private Snippet copyTerminalSnippetForManager(Snippet draft) {
        Snippet snippet = new Snippet();
        snippet.setName(draft.getName());
        snippet.setContent(draft.getContent());
        snippet.setLanguage(draft.getLanguage());
        snippet.setCategory(draft.getCategory());
        snippet.setDescription(draft.getDescription());
        snippet.setTags(new ArrayList<>(draft.getTags()));
        List<SnippetDiagram> diagrams = new ArrayList<>();
        for (SnippetDiagram diagram : draft.getDiagrams()) {
            if (diagram != null) {
                diagrams.add(new SnippetDiagram(diagram));
            }
        }
        snippet.setDiagrams(diagrams);
        return snippet;
    }

    private <T> T callOnFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for UI action", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void showTerminalTextFileLoadFailure(TerminalFileRequest request, Throwable failure) {
        String message = terminalTextFileLoadFailureText(request, failure);
        showError(I18n.get("error.title"), message);
        updateStatus(message);
    }

    /** The user-facing explanation for a failed terminal-selection file read. */
    private String terminalTextFileLoadFailureText(String selectedFileName, Throwable failure) {
        return terminalTextFileLoadFailureText(new SelectedTerminalFile(selectedFileName), failure);
    }

    /** The user-facing explanation for a failed terminal file read, for a selection or a link. */
    private String terminalTextFileLoadFailureText(TerminalFileRequest request, Throwable failure) {
        String selectedFileName = request.label();
        boolean linked = request instanceof LinkedTerminalFile;
        if (failure instanceof TerminalTextFileLoadException loadFailure) {
            String remotePath = loadFailure.remotePath();
            return switch (loadFailure.reason()) {
                case NOT_FOUND -> I18n.get(linked ? "terminal.loadTextFile.pathNotFound" : "terminal.loadTextFile.notFound",
                    remotePath);
                case NOT_REGULAR_FILE -> I18n.get("terminal.loadTextFile.notRegularFile", remotePath);
                case NOT_READABLE -> I18n.get("terminal.loadTextFile.notReadable", remotePath);
                case BINARY_OR_NON_TEXT -> I18n.get("terminal.loadTextFile.binary", remotePath);
                case TOO_LARGE -> I18n.get("terminal.loadTextFile.tooLarge", remotePath);
                case UNMAPPABLE_WORKING_DIRECTORY ->
                    I18n.get("terminal.loadTextFile.unmappableWorkingDirectory", remotePath);
                case WORKING_DIRECTORY_UNKNOWN -> I18n.get("localShell.workingDirectoryUnavailable");
                case FOREIGN_SESSION -> I18n.get("terminal.loadTextFile.foreignSession");
                case INVALID_SELECTION -> linked
                    ? I18n.get("terminal.loadTextFile.invalidPath", remotePath)
                    : I18n.get("terminal.loadTextFile.invalidSelection");
            };
        }
        String detail = failure != null && failure.getMessage() != null
            ? failure.getMessage()
            : I18n.get("terminal.loadTextFile.unknownError");
        return I18n.get("terminal.loadTextFile.failed", selectedFileName, detail);
    }

    private record TerminalRemoteTextFile(String fileName, String remotePath, String content) {
    }

    private enum TerminalTextFileLoadFailure {
        NOT_FOUND,
        NOT_REGULAR_FILE,
        NOT_READABLE,
        BINARY_OR_NON_TEXT,
        TOO_LARGE,
        UNMAPPABLE_WORKING_DIRECTORY,
        WORKING_DIRECTORY_UNKNOWN,
        FOREIGN_SESSION,
        INVALID_SELECTION
    }

    private static final class TerminalTextFileLoadException extends Exception {
        private final TerminalTextFileLoadFailure reason;
        private final String remotePath;

        private TerminalTextFileLoadException(TerminalTextFileLoadFailure reason, String remotePath) {
            this(reason, remotePath, null);
        }

        private TerminalTextFileLoadException(TerminalTextFileLoadFailure reason, String remotePath, Throwable cause) {
            super(remotePath, cause);
            this.reason = reason;
            this.remotePath = remotePath;
        }

        private TerminalTextFileLoadFailure reason() {
            return reason;
        }

        private String remotePath() {
            return remotePath;
        }
    }

    /** The selected terminal tab of this window, or null when none (or a non-terminal tab) is selected. */
    public TerminalTab getActiveTerminalTab() {
        Tab activeTab = getActiveTab();
        return activeTab instanceof TerminalTab terminalTab ? terminalTab : null;
    }

    private String getTerminalAgentCommandName() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return TerminalAgentCommandSupport.normalizeCommandName(
            settings != null ? settings.getTerminalAgentCommandName() : null);
    }

    private boolean isTerminalAgentCommandNameCaseInsensitive() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isTerminalAgentCommandNameCaseInsensitive();
    }

    private TerminalAgentExecutionTarget getTerminalAgentExecutionTarget() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null ? settings.getTerminalAgentExecutionTarget() : TerminalAgentExecutionTarget.TERMINAL_WINDOW;
    }

    private boolean shouldShowTerminalAgentRunDialog() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings == null || settings.isTerminalAgentShowRunDialog();
    }

    private boolean shouldShowTerminalAgentDebugMessages() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isTerminalAgentShowDebugMessages();
    }

    private boolean shouldShowTerminalAgentRuntimeMessages() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isTerminalAgentShowRuntimeMessages();
    }

    private boolean shouldMirrorTerminalAgentFinalAnswer() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings == null || settings.isTerminalAgentMirrorFinalAnswerToTerminal();
    }

    private void rememberTerminalAgentMirrorFinalAnswer(boolean mirror) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null || settings.isTerminalAgentMirrorFinalAnswerToTerminal() == mirror) {
            return;
        }
        settings.setTerminalAgentMirrorFinalAnswerToTerminal(mirror);
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.warn("Could not persist terminal-agent terminal-mirror preference: {}", e.getMessage());
        }
    }

    private String getConnectionDisplayName(TerminalTab terminalTab) {
        return terminalTab.getConnection() != null
            ? terminalTab.getConnection().getDisplayName()
            : I18n.get("ai.agent.connection.unknown");
    }

    private void showAiAgent() {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        if (!isTerminalAgentExecutionEnabled()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.executionDisabled"));
            return;
        }
        TerminalTab terminalTab = getActiveTerminalTab();
        if (terminalTab == null || !terminalTab.isConnected()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        requestAiAgentForTab(terminalTab, false, null, null, false, false);
    }

    private void showAiPlanning() {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        TerminalTab terminalTab = getActiveTerminalTab();
        if (terminalTab == null || !terminalTab.isConnected()) {
            showError(I18n.get("ai.plan.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        requestAiPlanningForTab(terminalTab, null, null);
    }

    private void requestAiAgentForTab(
        TerminalTab terminalTab,
        boolean queryOnly,
        String initialPrompt,
        String requestedProfileName,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands) {
        requestAiAgentForTab(
            terminalTab,
            queryOnly,
            initialPrompt,
            requestedProfileName,
            askConfirmationBeforeEveryCommand,
            autoApproveRootCommands,
            null);
    }

    private void requestAiAgentForTab(
        TerminalTab terminalTab,
        boolean queryOnly,
        String initialPrompt,
        String requestedProfileName,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands,
        TerminalView.TerminalAgentRunContext runContext) {
        requestAiAgentForTab(
            terminalTab,
            queryOnly,
            initialPrompt,
            requestedProfileName,
            askConfirmationBeforeEveryCommand,
            autoApproveRootCommands,
            runContext,
            null);
    }

    private void requestAiAgentForTab(
        TerminalTab terminalTab,
        boolean queryOnly,
        String initialPrompt,
        String requestedProfileName,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands,
        TerminalView.TerminalAgentRunContext runContext,
        String askSelectedText) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        if (!queryOnly && !isTerminalAgentExecutionEnabled()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.executionDisabled"));
            return;
        }
        List<AiProfile> profiles = getAvailableAiProfiles();
        if (profiles.isEmpty()) {
            suggestAiWizard(I18n.get(queryOnly ? "ai.agent.ask.title" : "ai.agent.title"));
            return;
        }
        if (!shouldShowTerminalAgentRunDialog() && initialPrompt != null && !initialPrompt.isBlank()) {
            AiProfile resolvedProfile = requestedProfileName != null && !requestedProfileName.isBlank()
                ? findAiProfileByLookup(requestedProfileName)
                : null;
            if (resolvedProfile == null) {
                resolvedProfile = resolveAiProfileForConnection(terminalTab.getConnection(), AiWorkload.CODING);
            }
            if (resolvedProfile == null) {
                showAiManager();
                showError(I18n.get(queryOnly ? "ai.agent.ask.title" : "ai.agent.title"), I18n.get("settings.ai.error.noProfilesConfigured"));
                return;
            }
            TerminalAgentModels.Request directRequest = new TerminalAgentModels.Request(
                terminalTab.getAiSessionId(),
                resolvedProfile.getId(),
                initialPrompt.trim(),
                getConnectionDisplayName(terminalTab),
                "",
                getTerminalAgentExecutionTarget(),
                shouldShowTerminalAgentDebugMessages(),
                shouldShowTerminalAgentRuntimeMessages(),
                askConfirmationBeforeEveryCommand,
                autoApproveRootCommands,
                !queryOnly && shouldConfirmTerminalAgentMutatingCommandSets(),
                queryOnly,
                shouldMirrorTerminalAgentFinalAnswer());
            launchTerminalAgent(terminalTab, directRequest, runContext, askSelectedText);
            return;
        }

        List<AiProfile> orderedProfiles = reorderProfilesForLookup(profiles, requestedProfileName, terminalTab.getConnection());
        AiAgentDialog dialog = new AiAgentDialog(
            stage,
            orderedProfiles,
            getConnectionDisplayName(terminalTab),
            getTerminalAgentExecutionTarget(),
            shouldShowTerminalAgentDebugMessages(),
            shouldShowTerminalAgentRuntimeMessages(),
            shouldMirrorTerminalAgentFinalAnswer(),
            queryOnly,
            initialPrompt);
        dialog.showAndWait().ifPresent(request -> {
            rememberTerminalAgentMirrorFinalAnswer(request.mirrorFinalAnswerToTerminal());
            TerminalAgentModels.Request enrichedRequest = new TerminalAgentModels.Request(
                terminalTab.getAiSessionId(),
                request.profileId(),
                request.userPrompt(),
                request.connectionDisplayName(),
                request.acceptedPlanContext(),
                request.executionTarget(),
                request.showDebugMessages(),
                request.showRuntimeMessages(),
                askConfirmationBeforeEveryCommand || request.askConfirmationBeforeEveryCommand(),
                autoApproveRootCommands || request.autoApproveRootCommands(),
                !request.queryOnly() && shouldConfirmTerminalAgentMutatingCommandSets(),
                request.queryOnly(),
                request.mirrorFinalAnswerToTerminal());
            launchTerminalAgent(terminalTab, enrichedRequest, runContext, askSelectedText);
        });
    }

    private void requestAiPlanningForTab(TerminalTab terminalTab, String initialPrompt, String requestedProfileName) {
        requestAiPlanningForTab(terminalTab, initialPrompt, requestedProfileName, null);
    }

    private void requestAiPlanningForTab(
        TerminalTab terminalTab,
        String initialPrompt,
        String requestedProfileName,
        TerminalView.TerminalAgentRunContext runContext) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        List<AiProfile> profiles = getAvailableAiProfiles();
        if (profiles.isEmpty()) {
            suggestAiWizard(I18n.get("ai.plan.title"));
            return;
        }
        List<AiProfile> orderedProfiles = reorderProfilesForLookup(profiles, requestedProfileName, terminalTab.getConnection());
        AiAgentPlanDialog dialog = new AiAgentPlanDialog(
            stage,
            orderedProfiles,
            terminalTab.getConnection() != null ? terminalTab.getConnection().getDisplayName() : null,
            initialPrompt);
        dialog.showAndWait().ifPresent(request -> {
            TerminalAgentModels.PlanRequest enrichedRequest = new TerminalAgentModels.PlanRequest(
                terminalTab.getAiSessionId(),
                request.profileId(),
                request.userPrompt(),
                request.connectionDisplayName());
            launchTerminalAgentPlan(terminalTab, enrichedRequest, runContext);
        });
    }

    private List<AiProfile> reorderProfilesForLookup(
        List<AiProfile> profiles,
        String requestedProfileName,
        ServerConnection connection) {
        String preferredProfileId = connection != null
            && AiProfileSelectionSupport.findById(profiles, connection.getAiProfileId()) != null
            ? connection.getAiProfileId()
            : getConfiguredWorkloadProfileId(AiWorkload.CODING);
        return AiProfileSelectionSupport.reorderByRequestedOrDefault(
            profiles,
            requestedProfileName,
            preferredProfileId);
    }

    private AiProfile findAiProfileByLookup(String lookup) {
        return AiProfileSelectionSupport.findByLookup(getAvailableAiProfiles(), lookup);
    }

    private boolean launchTerminalAgent(TerminalTab terminalTab, TerminalAgentModels.Request request) {
        return launchTerminalAgent(terminalTab, request, null);
    }

    private boolean launchTerminalAgent(
        TerminalTab terminalTab,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext) {
        return launchTerminalAgent(terminalTab, request, runContext, null);
    }

    private boolean launchTerminalAgent(
        TerminalTab terminalTab,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext,
        String askSelectedText) {
        return launchTerminalAgent(terminalTab, request, runContext, askSelectedText, null);
    }

    /**
     * Every agent run goes through here — the dialog, the {@code agent ...} shortcut, Retry and the
     * execution of an accepted plan — so the foreign-session check sits here and is evaluated when
     * the run really starts. {@code acknowledgedConnector} is the connector whose foreign-session
     * warning the user already accepted earlier in the same flow, so they are not asked twice.
     *
     * @return {@code true} when a run (or an Ask tab) was started, {@code false} when it was refused
     *     or cancelled, so a caller such as the plan tab can give its controls back
     */
    private boolean launchTerminalAgent(
        TerminalTab terminalTab,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext,
        String askSelectedText,
        @Nullable ObservableTtyConnector acknowledgedConnector) {
        AiProfile profile = findAiProfileById(request.profileId());
        if (profile == null) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.profileMissing"));
            return false;
        }
        // An Ask only sends the question to the model and runs nothing on the target, so it needs
        // no warning; every executing run does.
        ObservableTtyConnector foreignSessionAcknowledgedFor = acknowledgedConnector;
        TerminalView.TerminalAgentRunContext targetRunContext = runContext;
        if (!request.queryOnly()) {
            // Resolve the target pane once, so the pane whose identity the user confirms is the
            // pane the run then uses, not one captured again after the modal warning closed.
            targetRunContext = terminalTab != null
                ? resolveTerminalAgentRunContext(terminalTab, runContext)
                : runContext;
            AgentTargetConfirmation confirmation =
                confirmAgentTargetInForeignSession(terminalTab, targetRunContext, acknowledgedConnector);
            if (!confirmation.proceed()) {
                updateStatus(I18n.get("ai.agent.foreignSession.cancelled"));
                return false;
            }
            foreignSessionAcknowledgedFor = confirmation.acknowledgedConnector();
        }
        logger.info("Launching terminal AI agent with profile '{}' ({})", getAiProfileDisplayName(profile), profile.getId());
        Map<String, Object> agentProps = new java.util.LinkedHashMap<>(TelemetryProps.aiProfileProps(profile));
        agentProps.put("kind", request.queryOnly() ? "ask" : "execute");
        Telemetry.track(TelemetryEvents.AI_AGENT_RUN_STARTED, agentProps);
        AiService service = createAiServiceForProfile(profile, terminalTab != null ? terminalTab.getConnection() : null);
        if (!(service instanceof AiPromptService aiService)) {
            suggestAiWizard(I18n.get("ai.agent.title"));
            return false;
        }

        if (request.queryOnly()) {
            openDirectAiAskTab(
                terminalTab,
                runContext,
                profile,
                request.userPrompt(),
                askSelectedText,
                request.connectionDisplayName(),
                terminalTab != null ? terminalTab.getConnection() : null);
            return true;
        }

        TerminalView.TerminalAgentRunContext resolvedRunContext =
            resolveTerminalAgentRunContext(terminalTab, targetRunContext);
        applyTerminalAgentWorkingDirectoryHint(resolvedRunContext);

        if (request.executionTarget() == TerminalAgentExecutionTarget.CHAT_WINDOW) {
            AiAgentRunTab runTab = new AiAgentRunTab(this, I18n.get("ai.agent.run.tabTitle"));
            insertTemporaryTab(runTab);
            runTab.startRun(
                terminalAgentService,
                terminalTab,
                profile,
                aiService,
                agentRunnerFor(resolvedRunContext),
                request);
            return true;
        }

        runTerminalAgentInTerminalWindow(
            terminalTab, profile, aiService, request, resolvedRunContext, foreignSessionAcknowledgedFor);
        return true;
    }

    private void openDirectAiAskTab(
        TerminalTab terminalTab,
        @Nullable TerminalView.TerminalAgentRunContext runContext,
        AiProfile profile,
        String prompt,
        String selectedText,
        String connectionDisplayName,
        ServerConnection connection) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        int maxSelectionChars = getMaxAiSelectionChars(profile);
        if (selectedText != null && !selectedText.isBlank() && selectedText.length() > maxSelectionChars) {
            showError(I18n.get("ai.error.title"), I18n.get("ai.error.selectionTooLarge", maxSelectionChars));
            return;
        }
        // "Ask Agent…" has no preview dialog (the question was already entered in the agent
        // dialog), so a selected file name is attached automatically when it validates; when it
        // does not, the question is sent about the bare selection and the status bar says why.
        AiAttachmentCandidate attachmentCandidate =
            resolveAiAttachmentCandidate(terminalTab, runContext, selectedText, maxSelectionChars);
        // No preview here either, so the status bar says how many secrets were masked.
        SessionJournalRedactor knownSecrets = aiSecretRedactor(terminalTab);
        RedactionResult maskedSelection = AiOutboundRedaction.redactFor(profile, selectedText, knownSecrets);
        TerminalPaneRef sourcePane = aiSourcePane(terminalTab, runContext);
        if (attachmentCandidate == null) {
            openDirectAiAskTab(profile, prompt, maskedSelection.text(), connectionDisplayName, connection, null,
                knownSecrets, sourcePane);
            updateStatusWithMaskedSecrets(null, maskedSelection.count());
            return;
        }
        updateStatus(I18n.get("ai.confirm.attachment.checking", attachmentCandidate.fileName()));
        loadAiAttachmentAsync(attachmentCandidate, maxSelectionChars, outcome -> {
            String skipped = outcome.attachment() == null
                ? I18n.get("ai.attachment.skipped", attachmentCandidate.fileName(), outcome.failureText())
                : null;
            AiOutboundRedaction.MaskedAttachment maskedAttachment =
                AiOutboundRedaction.redactAttachmentFor(profile, outcome.attachment(), knownSecrets);
            openDirectAiAskTab(profile, prompt, maskedSelection.text(), connectionDisplayName, connection,
                maskedAttachment.attachment(), knownSecrets, sourcePane);
            updateStatusWithMaskedSecrets(skipped, maskedSelection.count() + maskedAttachment.count());
        });
    }

    private void openDirectAiAskTab(
        AiProfile profile,
        String prompt,
        String selectedText,
        String connectionDisplayName,
        ServerConnection connection,
        @Nullable AiFileAttachment fileAttachment,
        @Nullable SessionJournalRedactor knownSecrets,
        @Nullable TerminalPaneRef sourcePane) {
        // Answer the question about the terminal selection when one was captured; without a
        // selection the question itself stays the request text (previous behavior).
        String requestText = askRequestText(selectedText, prompt);
        String languageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        AiRequest request = new AiRequest(AiAction.ASK, requestText, connectionDisplayName, languageCode, prompt)
            .withFileAttachment(fileAttachment);
        AiResultTab resultTab = new AiResultTab(
            this,
            I18n.get("ai.agent.ask.tabTitle"),
            profile,
            requestText,
            connectionDisplayName,
            languageCode,
            null,
            false);
        // Follow-ups may go to another profile; the tab masks them again with these secrets.
        resultTab.setOutboundSecrets(knownSecrets);
        resultTab.setSourcePane(sourcePane);
        if (fileAttachment != null) {
            resultTab.setFileAttachment(fileAttachment);
        }
        resultTab.appendUserMessage(prompt);
        insertTemporaryTab(resultTab);

        AiService aiService = createAiServiceForProfile(profile, connection);
        AiRequest streamedRequest = request.withStreamListener(resultTab.beginStreaming());
        Task<AiExecutionResult> task = new Task<>() {
            @Override
            protected AiExecutionResult call() throws Exception {
                return aiService.execute(streamedRequest);
            }
        };
        Thread thread = new Thread(task, "ai-agent-ask");
        thread.setDaemon(true);
        resultTab.attachRunningTask(task, thread, I18n.get("ai.result.loading"));
        task.setOnSucceeded(event -> {
            AiExecutionResult result = task.getValue();
            resultTab.showResult(result);
            recordAiUsageForProfile(profile, request, result);
        });
        task.setOnCancelled(event -> resultTab.showCancelled());
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            resultTab.showError(error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("ai.result.error"));
        });
        thread.start();
    }

    private void runTerminalAgentInTerminalWindow(
        TerminalTab terminalTab,
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext,
        @Nullable ObservableTtyConnector foreignSessionAcknowledgedFor) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Thread> workerRef = new java.util.concurrent.atomic.AtomicReference<>();
        final Object pauseLock = new Object();
        TerminalView.TerminalAgentRunContext resolvedRunContext = resolveTerminalAgentRunContext(terminalTab, runContext);
        applyTerminalAgentWorkingDirectoryHint(resolvedRunContext);
        if (resolvedRunContext == null || resolvedRunContext.connector() == null) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        AiAgentActivityTabsPanel activityPanel = terminalTab.getTerminalView().getTerminalAgentActivityPanel(resolvedRunContext);
        if (activityPanel == null) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.noTerminal"));
            return;
        }
        int activeRuns = terminalTab.getTerminalView().terminalAgentRunCount(resolvedRunContext.widget());
        if (!TerminalView.canStartTerminalAgentRun(activeRuns, MAX_CONCURRENT_TERMINAL_AGENT_RUNS)) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.tooManyRuns"));
            return;
        }
        final String runId = java.util.UUID.randomUUID().toString();
        TerminalAgentModels.Request scopedRequest = withTerminalAgentSessionId(
            request,
            terminalTab.getTerminalView().buildTerminalAgentScopedSessionId(request.sessionId(), resolvedRunContext));
        Runnable cancelRun = () -> {
            cancelled.set(true);
            Thread workerThread = workerRef.get();
            if (workerThread != null) {
                workerThread.interrupt();
            }
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        };
        java.util.function.Consumer<Boolean> pauseToggle = value -> {
            paused.set(Boolean.TRUE.equals(value));
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        };
        // Reload must use the AI profile that is active *now*, not the one frozen into the
        // original request, so switching profiles before pressing reload takes effect. It goes
        // through the foreign-session check again (a su typed after the first run is caught),
        // carrying the acknowledgement this run already got so the same warning is not repeated.
        Runnable reloadRun = () -> relaunchTerminalAgentWithCurrentProfile(
            terminalTab, request, resolvedRunContext, foreignSessionAcknowledgedFor);
        terminalTab.getTerminalView().setTerminalAgentInputLocked(
            resolvedRunContext,
            runId,
            true,
            cancelRun,
            () -> activityPanel.toggleThinkingDetails(runId));
        String localUser = System.getProperty("user.name");
        ServerConnection runConnection = resolvedRunContext.connector() != null
            ? resolvedRunContext.connector().getConnection()
            : null;
        if (runConnection == null) {
            runConnection = terminalTab.getConnection();
        }
        String sshUser = runConnection != null ? runConnection.getUsername() : null;
        String connectionName = runConnection != null ? runConnection.getDisplayName() : null;
        AiAgentActivityPanel.RunMetadata runMetadata = new AiAgentActivityPanel.RunMetadata(
            profile.getId(),
            profile.getName(),
            aiModelDisplayText(profile),
            AiReasoningSupport.exportStatus(profile),
            localUser,
            sshUser,
            connectionName);
        activityPanel.beginRun(runId, scopedRequest.userPrompt(), cancelRun, pauseToggle, reloadRun, runMetadata);
        java.util.concurrent.atomic.AtomicBoolean agentResultJournaled =
            new java.util.concurrent.atomic.AtomicBoolean();
        long agentRunStartedNanos = System.nanoTime();
        java.util.concurrent.atomic.AtomicLong agentTokensTotal =
            new java.util.concurrent.atomic.AtomicLong();
        java.util.Set<String> agentTokenActivityIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
        String agentModelText = aiModelDisplayText(profile);
        // One desktop notification for the run's end, whichever path reports it first.
        java.util.concurrent.atomic.AtomicBoolean agentEndNotified =
            new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                terminalAgentService.runAgent(terminalTab, agentRunnerFor(resolvedRunContext), profile, aiService, scopedRequest, runId, new TerminalAgentService.RunUi() {
                    @Override
                    public void updateState(TerminalAgentModels.RunState state) {
                        if (state != null && isTerminalAgentFinalPhase(state.phase())) {
                            TerminalNotificationPolicy.AiRunEvent endEvent =
                                TerminalAttentionNotifier.agentEndEvent(state.phase());
                            if (endEvent != null && agentEndNotified.compareAndSet(false, true)) {
                                TerminalAttentionNotifier.postAiRun(terminalTab, endEvent);
                            }
                            String message = formatTerminalAgentFinalMessage(state);
                            if (message != null && !message.isBlank()) {
                                // The journal card is written regardless of terminal mirroring:
                                // the run happened either way, and the summarizer deliberately
                                // ignores agent text it finds inline in the capture log.
                                if (agentResultJournaled.compareAndSet(false, true)) {
                                    appendAgentJournalEntry(terminalTab, scopedRequest.userPrompt(), message,
                                        agentModelText,
                                        (System.nanoTime() - agentRunStartedNanos) / 1_000_000L,
                                        agentTokensTotal.get());
                                }
                                if (scopedRequest.mirrorFinalAnswerToTerminal()) {
                                    terminalTab.getTerminalView().showAgentMessage(resolvedRunContext, message);
                                }
                            }
                        }
                    }

                    @Override
                    public void appendTranscript(String text) {
                        if (scopedRequest.showDebugMessages()) {
                            activityPanel.publishActivity(runId, new TerminalAgentModels.AgentActivity(
                                "debug-" + System.nanoTime(),
                                TerminalAgentModels.AgentActivityType.MESSAGE,
                                TerminalAgentModels.AgentActivityStatus.COMPLETED,
                                I18n.get("ai.agent.activity.debug"),
                                I18n.get("ai.agent.activity.debug"),
                                text != null ? text.trim() : "",
                                TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                                0L,
                                text != null && !text.isBlank(),
                                true));
                        }
                    }

                    @Override
                    public void publishActivity(TerminalAgentModels.AgentActivity activity) {
                        activityPanel.publishActivity(runId, activity);
                        // Same accounting as the activity panel's token counter, for the
                        // journal's AGENT card meta line.
                        TerminalAgentModels.AgentActivityTokenUsage usage =
                            activity != null ? activity.tokenUsage() : null;
                        if (usage != null && usage.known() && usage.totalTokens() > 0
                            && agentTokenActivityIds.add(activity.id())) {
                            agentTokensTotal.addAndGet(usage.totalTokens());
                        }
                    }

                    @Override
                    public void recordTokenUsage(AiTokenUsage usage) {
                        if (usage == null) {
                            return;
                        }
                        recordAiUsageForProfile(profile, usage);
                    }

                    @Override
                    public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
                        TerminalAttentionNotifier.postAiRun(terminalTab,
                            TerminalNotificationPolicy.AiRunEvent.NEEDS_APPROVAL);
                        return activityPanel.requestApproval(runId, approval);
                    }

                    @Override
                    public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest passwordRequest) {
                        TerminalAttentionNotifier.postAiRun(terminalTab,
                            TerminalNotificationPolicy.AiRunEvent.NEEDS_PASSWORD);
                        return activityPanel.requestPassword(runId, passwordRequest);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void awaitIfPaused() throws InterruptedException {
                        synchronized (pauseLock) {
                            while (paused.get() && !cancelled.get()) {
                                pauseLock.wait();
                            }
                        }
                    }
                });
            } catch (Exception e) {
                if (TerminalAgentService.isCancellation(e) || cancelled.get()) {
                    activityPanel.publishActivity(runId, new TerminalAgentModels.AgentActivity(
                        "cancelled-" + System.nanoTime(),
                        TerminalAgentModels.AgentActivityType.MESSAGE,
                        TerminalAgentModels.AgentActivityStatus.CANCELLED,
                        I18n.get("ai.agent.activity.cancelled"),
                        I18n.get("ai.agent.activity.cancelled"),
                        "",
                        TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                        0L,
                        false,
                        true));
                    terminalTab.getTerminalView().showAgentMessage(resolvedRunContext, I18n.get("ai.agent.activity.cancelled"));
                    if (agentResultJournaled.compareAndSet(false, true)) {
                        appendAgentJournalEntry(terminalTab, scopedRequest.userPrompt(),
                            I18n.get("ai.agent.activity.cancelled"),
                            agentModelText,
                            (System.nanoTime() - agentRunStartedNanos) / 1_000_000L,
                            agentTokensTotal.get());
                    }
                } else {
                    if (agentEndNotified.compareAndSet(false, true)) {
                        TerminalAttentionNotifier.postAiRun(terminalTab, TerminalNotificationPolicy.AiRunEvent.FAILED);
                    }
                    activityPanel.publishActivity(runId, new TerminalAgentModels.AgentActivity(
                        "failed-" + System.nanoTime(),
                        TerminalAgentModels.AgentActivityType.ERROR,
                        TerminalAgentModels.AgentActivityStatus.FAILED,
                        I18n.get("ai.agent.run.phase.failed"),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        "",
                        TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                        0L,
                        false,
                        true));
                    Platform.runLater(() -> showError(I18n.get("ai.agent.title"),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            } finally {
                Platform.runLater(() -> {
                    activityPanel.finishRun(runId);
                    terminalTab.getTerminalView().setTerminalAgentInputLocked(resolvedRunContext, runId, false, null, null);
                });
            }
        }, "ai-agent-terminal");
        workerRef.set(worker);
        worker.setDaemon(true);
        worker.start();
    }

    private AgentCommandRunner agentRunnerFor(TerminalView.TerminalAgentRunContext runContext) {
        if (runContext == null || runContext.connector() == null) {
            return null;
        }
        return AgentCommandRunners.forConnector(runContext.connector(), runContext.workingDirectory());
    }

    private TerminalView.TerminalAgentRunContext resolveTerminalAgentRunContext(
        TerminalTab terminalTab,
        TerminalView.TerminalAgentRunContext runContext) {

        if (runContext != null) {
            return runContext;
        }
        return terminalTab.getTerminalView() != null
            ? terminalTab.getTerminalView().captureTerminalAgentRunContext()
            : null;
    }

    /**
     * Outcome of {@link #confirmAgentTargetInForeignSession}: whether the run may start, and the
     * connector whose foreign-session warning the user has accepted in this flow (or {@code null}).
     */
    private record AgentTargetConfirmation(
        boolean proceed,
        @Nullable ObservableTtyConnector acknowledgedConnector) {
    }

    /**
     * Asks before an AI agent or planning run starts in a pane whose shell is (suspected to be)
     * running as another user or host — after {@code su}, {@code sudo -i} or a nested
     * {@code ssh}. The agent never types into that shell: it runs its commands over a new exec
     * channel of the tab's original SSH session, or as local processes in a local-shell tab, so
     * it acts as the identity the tab was opened with while the prompt shows another one.
     *
     * <p>A warning already accepted for the same connector earlier in the flow
     * ({@code acknowledgedConnector}) is not repeated. Must run on the JavaFX thread: the check
     * reads the pane's screen buffer.</p>
     */
    private AgentTargetConfirmation confirmAgentTargetInForeignSession(
        TerminalTab terminalTab,
        @Nullable TerminalView.TerminalAgentRunContext runContext,
        @Nullable ObservableTtyConnector acknowledgedConnector) {
        TerminalView terminalView = terminalTab != null ? terminalTab.getTerminalView() : null;
        if (terminalView == null) {
            return new AgentTargetConfirmation(true, acknowledgedConnector);
        }
        TerminalView.TerminalAgentRunContext resolvedContext = runContext != null
            ? runContext
            : terminalView.captureTerminalAgentRunContext();
        ObservableTtyConnector connector = resolvedContext != null ? resolvedContext.connector() : null;
        boolean foreignSession = resolvedContext != null && terminalView.isForeignSessionActive(resolvedContext);
        if (connector == null
            || !TerminalAgentTargetNotice.requiresConfirmation(foreignSession, connector, acknowledgedConnector)) {
            return new AgentTargetConfirmation(true, acknowledgedConnector);
        }

        ServerConnection connection = connector.getConnection() != null
            ? connector.getConnection()
            : terminalTab.getConnection();
        boolean localShell = connector instanceof LocalShellTtyConnector
            || (connection != null && connection.isLocalShell());
        String target = TerminalAgentTargetNotice.describeTarget(
            connector.getExpectedSessionUser(),
            connector.getExpectedSessionHost(),
            connection != null ? connection.getUsername() : null,
            connection != null ? connection.getHost() : null,
            localShell);

        ButtonType continueButton = new ButtonType(
            I18n.get("ai.agent.foreignSession.continue"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(I18n.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", continueButton, cancelButton);
        DialogThemeHelper.applyTheme(alert);
        if (stage != null) {
            alert.initOwner(stage);
        }
        alert.setTitle(I18n.get("ai.agent.foreignSession.title"));
        alert.setHeaderText(I18n.get("ai.agent.foreignSession.header"));
        alert.setContentText(I18n.get("ai.agent.foreignSession.message", target));
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        // Cancel is the safe answer, so Enter must not start the run.
        if (alert.getDialogPane().lookupButton(continueButton) instanceof Button continueNode) {
            continueNode.setDefaultButton(false);
        }
        if (alert.getDialogPane().lookupButton(cancelButton) instanceof Button cancelNode) {
            cancelNode.setDefaultButton(true);
        }
        boolean confirmed = alert.showAndWait().orElse(cancelButton) == continueButton;
        logger.info("AI agent target pane runs a foreign session; the user {} the run",
            confirmed ? "continued" : "cancelled");
        return confirmed
            ? new AgentTargetConfirmation(true, connector)
            : new AgentTargetConfirmation(false, acknowledgedConnector);
    }

    private void applyTerminalAgentWorkingDirectoryHint(TerminalView.TerminalAgentRunContext runContext) {
        if (runContext == null || runContext.connector() == null) {
            return;
        }
        if (runContext.connector() instanceof LocalShellTtyConnector) {
            // Local hints are validated against the filesystem and may block. The local runner
            // receives this same run-context hint and resolves it on its worker thread instead.
            return;
        }
        runContext.connector().updateCurrentWorkingDirectoryHint(runContext.workingDirectory());
    }

    private TerminalAgentModels.Request withTerminalAgentSessionId(
        TerminalAgentModels.Request request,
        String sessionId) {
        return new TerminalAgentModels.Request(
            sessionId,
            request.profileId(),
            request.userPrompt(),
            request.connectionDisplayName(),
            request.acceptedPlanContext(),
            request.executionTarget(),
            request.showDebugMessages(),
            request.showRuntimeMessages(),
            request.askConfirmationBeforeEveryCommand(),
            request.autoApproveRootCommands(),
            request.confirmMutatingCommandSets(),
            request.queryOnly(),
            request.mirrorFinalAnswerToTerminal());
    }

    /**
     * Re-launches a terminal-agent run (the reload/"Wiederholen" button) using the AI profile that
     * is currently active, rather than the profile that was active when the original run started.
     * The profile is re-resolved exactly like a fresh launch, so a connection-pinned profile is
     * still honoured while a changed global default now takes effect.
     */
    private void relaunchTerminalAgentWithCurrentProfile(
        TerminalTab terminalTab,
        TerminalAgentModels.Request request,
        TerminalView.TerminalAgentRunContext runContext,
        @Nullable ObservableTtyConnector foreignSessionAcknowledgedFor) {
        AiProfile currentProfile = resolveAiProfileForConnection(
            terminalTab != null ? terminalTab.getConnection() : null,
            AiWorkload.CODING);
        TerminalAgentModels.Request refreshedRequest = currentProfile != null
            ? withTerminalAgentProfileId(request, currentProfile.getId())
            : request;
        launchTerminalAgent(terminalTab, refreshedRequest, runContext, null, foreignSessionAcknowledgedFor);
    }

    /**
     * Chooses the request text for a direct AI-agent "Ask": the captured terminal selection when
     * present, otherwise the question itself (legacy behavior for asks without a selection).
     */
    static String askRequestText(String selectedText, String prompt) {
        return selectedText != null && !selectedText.isBlank() ? selectedText : prompt;
    }

    static TerminalAgentModels.Request withTerminalAgentProfileId(
        TerminalAgentModels.Request request,
        String profileId) {
        return new TerminalAgentModels.Request(
            request.sessionId(),
            profileId,
            request.userPrompt(),
            request.connectionDisplayName(),
            request.acceptedPlanContext(),
            request.executionTarget(),
            request.showDebugMessages(),
            request.showRuntimeMessages(),
            request.askConfirmationBeforeEveryCommand(),
            request.autoApproveRootCommands(),
            request.confirmMutatingCommandSets(),
            request.queryOnly());
    }

    private boolean isTerminalAgentFinalPhase(TerminalAgentModels.Phase phase) {
        return phase == TerminalAgentModels.Phase.DONE
            || phase == TerminalAgentModels.Phase.BLOCKED
            || phase == TerminalAgentModels.Phase.CANCELLED
            || phase == TerminalAgentModels.Phase.FAILED;
    }

    private String formatTerminalAgentFinalMessage(TerminalAgentModels.RunState state) {
        if (state == null) {
            return "";
        }
        String userMessage = state.userMessage() != null ? state.userMessage().trim() : "";
        String summary = state.summary() != null ? state.summary().trim() : "";
        if (userMessage.isBlank()) {
            return summary;
        }
        if (summary.isBlank() || userMessage.equals(summary) || userMessage.contains(summary)) {
            return userMessage;
        }
        if (summary.contains(userMessage)) {
            return summary;
        }
        return userMessage + "\n" + summary;
    }

    private void launchTerminalAgentPlan(TerminalTab terminalTab, TerminalAgentModels.PlanRequest request) {
        launchTerminalAgentPlan(terminalTab, request, null);
    }

    private void launchTerminalAgentPlan(
        TerminalTab terminalTab,
        TerminalAgentModels.PlanRequest request,
        TerminalView.TerminalAgentRunContext runContext) {
        AiProfile profile = findAiProfileById(request.profileId());
        if (profile == null) {
            showError(I18n.get("ai.plan.title"), I18n.get("ai.agent.error.profileMissing"));
            return;
        }
        // Planning probes the target with read-only commands and ends in an executing run, so it
        // asks like the agent does. The acknowledgement is handed to the accepted plan's
        // execution, which checks again on its own when it starts (a su typed while planning is
        // caught there) but does not repeat a warning the user already accepted here.
        // The terminal-window pane is captured before the check, so the pane whose identity the
        // user confirms is the pane the planning probes then use.
        TerminalAgentExecutionTarget executionTarget = getTerminalAgentExecutionTarget();
        TerminalView.TerminalAgentRunContext resolvedRunContext = runContext;
        if (executionTarget == TerminalAgentExecutionTarget.TERMINAL_WINDOW
            && resolvedRunContext == null
            && terminalTab != null
            && terminalTab.getTerminalView() != null) {
            resolvedRunContext = terminalTab.getTerminalView().captureTerminalAgentRunContext();
        }
        AgentTargetConfirmation confirmation =
            confirmAgentTargetInForeignSession(terminalTab, resolvedRunContext, null);
        if (!confirmation.proceed()) {
            updateStatus(I18n.get("ai.agent.foreignSession.cancelled"));
            return;
        }
        ObservableTtyConnector foreignSessionAcknowledgedFor = confirmation.acknowledgedConnector();
        logger.info("Launching terminal AI planning with profile '{}' ({})", getAiProfileDisplayName(profile), profile.getId());
        Telemetry.track(TelemetryEvents.AI_PLAN_RUN_STARTED, TelemetryProps.aiProfileProps(profile));
        AiService service = createAiServiceForProfile(profile, terminalTab != null ? terminalTab.getConnection() : null);
        if (!(service instanceof AiPromptService aiService)) {
            suggestAiWizard(I18n.get("ai.plan.title"));
            return;
        }

        applyTerminalAgentWorkingDirectoryHint(resolvedRunContext);
        TerminalView.TerminalAgentRunContext planRunContext = resolvedRunContext;
        AiAgentPlanTab planTab = new AiAgentPlanTab(
            this,
            terminalAgentService,
            terminalTab,
            profile,
            aiService,
            request,
            agentRunnerFor(planRunContext),
            () -> resolveTerminalAgentPreflightSessionId(terminalTab, request.sessionId(), planRunContext),
            (planRequest, report) -> startAcceptedPlanExecution(
                terminalTab, profile, planRequest, report, planRunContext, foreignSessionAcknowledgedFor));
        insertTemporaryTab(planTab);
        planTab.start();
    }

    private String resolveTerminalAgentPreflightSessionId(
        TerminalTab terminalTab,
        String sessionId,
        TerminalView.TerminalAgentRunContext runContext) {
        if (getTerminalAgentExecutionTarget() == TerminalAgentExecutionTarget.TERMINAL_WINDOW
            && runContext != null
            && terminalTab.getTerminalView() != null) {
            return terminalTab.getTerminalView().buildTerminalAgentScopedSessionId(sessionId, runContext);
        }
        return sessionId;
    }

    private boolean startAcceptedPlanExecution(
        TerminalTab terminalTab,
        AiProfile profile,
        TerminalAgentModels.PlanRequest planRequest,
        TerminalAgentModels.PlanReport report,
        TerminalView.TerminalAgentRunContext runContext,
        @Nullable ObservableTtyConnector foreignSessionAcknowledgedFor) {
        if (!isTerminalAgentExecutionEnabled()) {
            showError(I18n.get("ai.agent.title"), I18n.get("ai.agent.error.executionDisabled"));
            return false;
        }
        TerminalAgentModels.Request request = new TerminalAgentModels.Request(
            planRequest.sessionId(),
            profile.getId(),
            planRequest.userPrompt(),
            planRequest.connectionDisplayName(),
            terminalAgentService.buildAcceptedPlanContext(report),
            getTerminalAgentExecutionTarget(),
            app.getGlobalSettingsManager().getSettings() != null && app.getGlobalSettingsManager().getSettings().isTerminalAgentShowDebugMessages(),
            app.getGlobalSettingsManager().getSettings() != null && app.getGlobalSettingsManager().getSettings().isTerminalAgentShowRuntimeMessages(),
            false,
            false,
            shouldConfirmTerminalAgentMutatingCommandSets(),
            false);
        return launchTerminalAgent(terminalTab, request, runContext, null, foreignSessionAcknowledgedFor);
    }

    private void handleTerminalAgentShortcut(
        TerminalTab terminalTab,
        String rawCommand,
        TerminalView.TerminalAgentRunContext runContext) {
        String commandName = getTerminalAgentCommandName();
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut(
                rawCommand,
                commandName,
                isTerminalAgentCommandNameCaseInsensitive());
        if (invocation == null) {
            terminalTab.getTerminalView().showError(TerminalAgentCommandSupport.buildUsageText(commandName));
            return;
        }
        switch (invocation.kind()) {
            case ASK -> requestAiAgentForTab(terminalTab, true, invocation.userPrompt(), invocation.profileName(), false, false, runContext);
            case PLAN -> requestAiPlanningForTab(terminalTab, invocation.userPrompt(), invocation.profileName(), runContext);
            case EXECUTE -> requestAiAgentForTab(
                terminalTab,
                false,
                invocation.userPrompt(),
                invocation.profileName(),
                invocation.askConfirmationBeforeEveryCommand(),
                invocation.autoApproveRootCommands(),
                runContext);
        }
    }

    private record AiRequestDraft(String selectedText, String userPrompt, @Nullable AiFileAttachment fileAttachment) {
    }

    private String getAiProfileDisplayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }

    void updateStatusMessage(String message) {
        updateStatus(message);
    }

    List<AiProfile> getAvailableAiProfiles() {
        refreshGlobalSettingsIfChangedBeforeAiProfileResolution();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null || settings.getAiProfiles() == null) {
            return List.of();
        }
        return settings.getAiProfiles().stream()
            .filter(profile -> profile != null)
            .sorted((left, right) -> getAiProfileDisplayName(left).compareToIgnoreCase(getAiProfileDisplayName(right)))
            .toList();
    }

    String getDefaultAiProfileId() {
        refreshGlobalSettingsIfChangedBeforeAiProfileResolution();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null ? settings.getDefaultAiProfileId() : null;
    }

    private void refreshGlobalSettingsIfChangedBeforeAiProfileResolution() {
        try {
            var manager = app.getGlobalSettingsManager();
            if (manager != null && manager.reloadIfChanged()) {
                logger.info("Reloaded global settings before resolving AI profile selection");
            }
        } catch (Exception e) {
            logger.warn("Could not refresh global settings before resolving AI profile selection: {}", e.getMessage());
        }
    }

    AiProfile getDefaultAiProfile() {
        return AiProfileSelectionSupport.defaultProfile(getAvailableAiProfiles(), getDefaultAiProfileId());
    }

    AiProfile getAiProfileForWorkload(AiWorkload workload) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return AiProfileSelectionSupport.workloadProfile(
            getAvailableAiProfiles(),
            workload != null ? workload : AiWorkload.TEXT,
            settings != null ? settings.getTextAiProfileId() : null,
            settings != null ? settings.getCodingAiProfileId() : null,
            settings != null ? settings.getDefaultAiProfileId() : null);
    }

    private String getConfiguredWorkloadProfileId(AiWorkload workload) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null) {
            return null;
        }
        String roleId = workload == AiWorkload.CODING
            ? settings.getCodingAiProfileId()
            : settings.getTextAiProfileId();
        return AiProfileSelectionSupport.findById(getAvailableAiProfiles(), roleId) != null
            ? roleId
            : settings.getDefaultAiProfileId();
    }

    String getSecurityCheckAiProfileId() {
        refreshGlobalSettingsIfChangedBeforeAiProfileResolution();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null ? settings.getSecurityCheckAiProfileId() : null;
    }

    /**
     * Resolves the AI profile dedicated to snippet security checks: the configured security profile
     * when it exists, otherwise the default profile.
     */
    AiProfile getSecurityCheckAiProfile() {
        List<AiProfile> profiles = getAvailableAiProfiles();
        String securityProfileId = getSecurityCheckAiProfileId();
        AiProfile chosen = securityProfileId != null
            ? AiProfileSelectionSupport.findById(profiles, securityProfileId)
            : null;
        return chosen != null
            ? chosen
            : AiProfileSelectionSupport.defaultProfile(profiles, getDefaultAiProfileId());
    }

    /**
     * Resolves the AI profile for a connection: the connection's fixed profile when it is
     * available, otherwise the default profile (until the fixed profile is available again).
     */
    AiProfile resolveAiProfileForConnection(ServerConnection connection) {
        return resolveAiProfileForConnection(connection, AiWorkload.TEXT);
    }

    AiProfile resolveAiProfileForConnection(ServerConnection connection, AiWorkload workload) {
        if (connection != null) {
            AiProfile fixedProfile = findAiProfileById(connection.getAiProfileId());
            if (fixedProfile != null) {
                return fixedProfile;
            }
        }
        return getAiProfileForWorkload(workload);
    }

    AiProfile resolveAiProfileForAction(
        ServerConnection connection,
        AiAction action,
        String explicitProfileId) {

        List<AiProfile> profiles = getAvailableAiProfiles();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null) {
            return null;
        }
        return AiProfileSelectionSupport.actionProfile(
            profiles,
            explicitProfileId,
            action != null && action.isSecurityAction(),
            settings.getSecurityCheckAiProfileId(),
            connection != null ? connection.getAiProfileId() : null,
            action != null ? action.workload() : AiWorkload.TEXT,
            settings.getTextAiProfileId(),
            settings.getCodingAiProfileId(),
            settings.getDefaultAiProfileId());
    }

    AiProfile findAiProfileById(String profileId) {
        return AiProfileSelectionSupport.findById(getAvailableAiProfiles(), profileId);
    }

    AiService createAiServiceForProfile(AiProfile profile) {
        return createAiService(profile);
    }

    /** Like {@link #createAiServiceForProfile(AiProfile)} in explicit snippet-picker mode. */
    AiService createAiServiceForProfile(AiProfile profile, java.util.Collection<String> forcedSkillIds) {
        return createAiService(profile, null, forcedSkillIds);
    }

    /** A factory that builds a fresh {@link AiPromptService} per call (one per swarm agent thread). */
    public java.util.function.Supplier<AiPromptService> aiPromptServiceFactory(AiProfile profile) {
        return () -> {
            AiService service = createAiServiceForProfile(profile);
            return service instanceof AiPromptService promptService ? promptService : null;
        };
    }

    /**
     * Starts an AI swarm on a dedicated daemon coordinator thread (reusing the shared
     * {@link TerminalAgentService}). Returns the coordinator thread.
     */
    public Thread startSwarm(
        SwarmModels.SwarmRequest request,
        java.util.List<SwarmTarget> targets,
        AiProfile profile,
        SwarmCallback callback,
        de.kortty.core.swarm.SwarmRunControl control) {
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(terminalAgentService);
        // Every agent call and the aggregation count against the profile quota, like a single agent.
        orchestrator.setUsageSink(usage -> recordAiUsage(profile, usage));
        java.util.function.Supplier<AiPromptService> factory = aiPromptServiceFactory(profile);
        Thread thread = new Thread(
            () -> orchestrator.run(request, targets, profile, factory, callback, control),
            "ai-swarm-coordinator");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Sequentially opens the given not-yet-open connections for a swarm and reports each, once its
     * session is established, as a connected {@link SwarmTarget}. Sequential so per-host auth/host-key
     * dialogs queue cleanly instead of stacking.
     */
    public void connectSwarmTargets(
        java.util.List<ServerConnection> connections,
        boolean includeLocalShell,
        java.util.function.Consumer<SwarmTarget> onConnected,
        Runnable onComplete) {
        java.util.List<ServerConnection> toOpen = new java.util.ArrayList<>();
        if (connections != null) {
            for (ServerConnection connection : connections) {
                if (connection != null && (includeLocalShell || !connection.isLocalShell())) {
                    toOpen.add(connection);
                }
            }
        }
        connectSwarmNext(toOpen.iterator(), onConnected, onComplete);
    }

    private void connectSwarmNext(
        java.util.Iterator<ServerConnection> iterator,
        java.util.function.Consumer<SwarmTarget> onConnected,
        Runnable onComplete) {
        if (!iterator.hasNext()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        ServerConnection connection = iterator.next();
        // Deliberately logs only host/port, not getDisplayName(): its local-shell fallback path
        // derives from a free-form, user-configurable command string that a static scanner can't
        // prove never carries embedded sensitive data.
        String logLabel = connection.getHost() + ":" + connection.getPort();
        TerminalTab tab;
        try {
            String password = ensurePasswordForConnection(connection, null);
            boolean requiresPassword = !connection.isLocalShell()
                && connection.getAuthMethod() != de.kortty.model.AuthMethod.PUBLIC_KEY;
            if (requiresPassword && (password == null || password.isBlank())) {
                logger.warn("Swarm skipping connection {}: no password provided", logLabel);
                connectSwarmNext(iterator, onConnected, onComplete);
                return;
            }
            tab = openConnectionAndReturnTab(connection, password, null, null);
        } catch (Exception e) {
            logger.warn("Swarm could not open connection {}", logLabel, e);
            connectSwarmNext(iterator, onConnected, onComplete);
            return;
        }
        if (tab == null) {
            logger.warn("Swarm could not open connection {}: no tab returned", logLabel);
            connectSwarmNext(iterator, onConnected, onComplete);
            return;
        }
        final TerminalTab openedTab = tab;
        final long deadline = System.currentTimeMillis() + 30_000L;
        Thread poller = new Thread(() -> {
            AgentCommandRunner runner = null;
            while (System.currentTimeMillis() < deadline) {
                var connector = openedTab != null && openedTab.getTerminalView() != null
                    ? openedTab.getTerminalView().getActiveAgentConnector()
                    : null;
                AgentCommandRunner candidate = connector != null
                    ? AgentCommandRunners.forConnector(connector)
                    : null;
                if (candidate != null && candidate.isConnected()) {
                    runner = candidate;
                    break;
                }
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final AgentCommandRunner connectedRunner = runner;
            Platform.runLater(() -> {
                if (connectedRunner != null && onConnected != null) {
                    onConnected.accept(new SwarmTarget(
                        "swarm-" + java.util.UUID.randomUUID(),
                        connection,
                        connectedRunner,
                        openedTab,
                        "swarm-" + java.util.UUID.randomUUID(),
                        SwarmTargetCollector.displayName(connection)));
                }
                connectSwarmNext(iterator, onConnected, onComplete);
            });
        }, "ai-swarm-connect");
        poller.setDaemon(true);
        poller.start();
    }

    AiService createAiServiceForProfile(AiProfile profile, ServerConnection connection) {
        return createAiService(profile, connection);
    }

    AiService createAiServiceForProfile(
        AiProfile profile,
        ServerConnection connection,
        java.util.Collection<String> forcedSkillIds) {

        return createAiService(profile, connection, forcedSkillIds);
    }

    void recordAiUsageForProfile(AiProfile profile, AiRequest request, AiExecutionResult result) {
        recordAiUsage(profile, request, result);
    }

    void recordAiUsageForProfile(AiProfile profile, AiTokenUsage usage) {
        recordAiUsage(profile, usage);
    }

    void registerSavedChatTab(AiResultTab tab) {
        if (tab == null || tab.getSavedChatId() == null || tab.getSavedChatId().isBlank()) {
            return;
        }
        openSavedAiChatTabs.put(tab.getSavedChatId(), tab);
    }

    void unregisterSavedChatTab(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        openSavedAiChatTabs.remove(chatId);
    }

    void registerSavedSwarmChatTab(SwarmAgentTab tab) {
        if (tab == null || tab.getSavedChatId() == null || tab.getSavedChatId().isBlank()) {
            return;
        }
        openSavedSwarmChatTabs.put(tab.getSavedChatId(), tab);
    }

    void unregisterSavedSwarmChatTab(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        openSavedSwarmChatTabs.remove(chatId);
    }

    /** Opens a fresh AI swarm window. */
    private void showAiSwarm() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "ai_swarm"));
        AiProfile profile = getDefaultAiProfile();
        String languageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        SwarmAgentTab tab = new SwarmAgentTab(this, I18n.get("ai.swarm.tab.title"), profile, languageCode, null, false);
        insertTemporaryTab(tab);
    }

    /** Reopens a saved swarm chat (or focuses it if already open). */
    public void openSavedSwarmChat(de.kortty.model.SavedSwarmChat chat) {
        Telemetry.track(TelemetryEvents.AI_SAVED_CHAT_OPENED, Map.of("kind", "swarm"));
        if (chat == null) {
            return;
        }
        SwarmAgentTab existing = openSavedSwarmChatTabs.get(chat.getId());
        if (existing != null && tabPane.getTabs().contains(existing)) {
            tabPane.getSelectionModel().select(existing);
            return;
        }
        openSavedSwarmChatTabs.remove(chat.getId());
        AiProfile profile = chat.getActiveAiProfileId() != null
            ? findAiProfileById(chat.getActiveAiProfileId())
            : getDefaultAiProfile();
        String languageCode = chat.getResponseLanguageCode() != null
            ? chat.getResponseLanguageCode()
            : LanguageManager.getInstance().getCurrentLanguageCode();
        String title = chat.getTitle() != null && !chat.getTitle().isBlank()
            ? chat.getTitle()
            : I18n.get("ai.swarm.tab.title");
        SwarmAgentTab tab = new SwarmAgentTab(this, title, profile, languageCode, chat, false);
        insertTemporaryTab(tab);
        registerSavedSwarmChatTab(tab);
    }

    AiResultTab findOpenSavedChatTab(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        AiResultTab tab = openSavedAiChatTabs.get(chatId);
        if (tab == null) {
            return null;
        }
        if (!tabPane.getTabs().contains(tab)) {
            openSavedAiChatTabs.remove(chatId);
            return null;
        }
        return tab;
    }

    AiResultTab openSavedAiChat(SavedAiChat chat) {
        Telemetry.track(TelemetryEvents.AI_SAVED_CHAT_OPENED, Map.of("kind", "chat"));
        if (chat == null) {
            return null;
        }

        AiResultTab existingTab = findOpenSavedChatTab(chat.getId());
        if (existingTab != null) {
            tabPane.getSelectionModel().select(existingTab);
            return existingTab;
        }

        SavedAiChat workingCopy = new SavedAiChat(chat);
        List<AiProfile> availableProfiles = getAvailableAiProfiles();
        AiProfile activeProfile = findAiProfileById(workingCopy.getActiveAiProfileId());
        boolean readOnly = false;

        if (activeProfile == null && workingCopy.getActiveAiProfileId() != null && !workingCopy.getActiveAiProfileId().isBlank()) {
            if (availableProfiles.isEmpty()) {
                readOnly = true;
            } else {
                Optional<AiProfile> replacementProfile = promptForSavedChatProfile(workingCopy, availableProfiles);
                if (replacementProfile.isEmpty()) {
                    return null;
                }
                activeProfile = replacementProfile.get();
                workingCopy.setActiveAiProfileId(activeProfile.getId());
                workingCopy.setActiveAiProfileName(getAiProfileDisplayName(activeProfile));
                try {
                    workingCopy = app.getAiChatManager().saveChat(workingCopy);
                } catch (Exception e) {
                    logger.error("Failed to persist replacement AI profile for saved chat {}", workingCopy.getId(), e);
                    showError(I18n.get("error.title"), e.getMessage());
                    return null;
                }
            }
        } else if (activeProfile == null && !availableProfiles.isEmpty()) {
            activeProfile = getDefaultAiProfile();
            workingCopy.setActiveAiProfileId(activeProfile.getId());
            workingCopy.setActiveAiProfileName(getAiProfileDisplayName(activeProfile));
            try {
                workingCopy = app.getAiChatManager().saveChat(workingCopy);
            } catch (Exception e) {
                logger.error("Failed to persist default AI profile for saved chat {}", workingCopy.getId(), e);
                showError(I18n.get("error.title"), e.getMessage());
                return null;
            }
        } else if (activeProfile == null) {
            readOnly = true;
        }

        String title = workingCopy.getTitle() != null && !workingCopy.getTitle().isBlank()
            ? workingCopy.getTitle().trim()
            : I18n.get("ai.saved.defaultTitle");
        AiResultTab resultTab = new AiResultTab(
            this,
            title,
            activeProfile,
            workingCopy.getSelectedText(),
            workingCopy.getConnectionDisplayName(),
            workingCopy.getResponseLanguageCode(),
            workingCopy,
            readOnly);
        insertTemporaryTab(resultTab);
        registerSavedChatTab(resultTab);
        updateStatus(I18n.get("ai.manager.opened", title));
        return resultTab;
    }

    boolean renameSavedAiChat(SavedAiChat chat, String newTitle) {
        if (chat == null || newTitle == null || newTitle.isBlank()) {
            return false;
        }
        SavedAiChat updatedChat = new SavedAiChat(chat);
        updatedChat.setTitle(newTitle.trim());
        try {
            SavedAiChat saved = app.getAiChatManager().saveChat(updatedChat);
            AiResultTab openTab = findOpenSavedChatTab(saved.getId());
            if (openTab != null) {
                openTab.applySavedChatTitle(saved.getTitle());
            }
            updateStatus(I18n.get("ai.manager.renamed", saved.getTitle()));
            return true;
        } catch (Exception e) {
            logger.error("Failed to rename saved AI chat {}", chat.getId(), e);
            showError(I18n.get("error.title"), e.getMessage());
            return false;
        }
    }

    boolean deleteSavedAiChat(SavedAiChat chat) {
        if (chat == null || chat.getId() == null || chat.getId().isBlank()) {
            return false;
        }
        try {
            boolean deleted = app.getAiChatManager().deleteChat(chat.getId());
            if (!deleted) {
                return false;
            }
            AiResultTab openTab = findOpenSavedChatTab(chat.getId());
            if (openTab != null) {
                openTab.closeTab();
            }
            unregisterSavedChatTab(chat.getId());
            updateStatus(I18n.get("ai.manager.deleted", chat.getTitle() != null ? chat.getTitle() : ""));
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete saved AI chat {}", chat.getId(), e);
            showError(I18n.get("error.title"), e.getMessage());
            return false;
        }
    }

    /** Closes and unregisters the chat's open swarm tab (if any) before deleting it, mirroring {@link #deleteSavedAiChat}. */
    boolean deleteSavedSwarmChat(de.kortty.model.SavedSwarmChat chat) {
        if (chat == null || chat.getId() == null || chat.getId().isBlank() || app.getSwarmChatManager() == null) {
            return false;
        }
        try {
            boolean deleted = app.getSwarmChatManager().deleteChat(chat.getId());
            if (!deleted) {
                return false;
            }
            SwarmAgentTab openTab = openSavedSwarmChatTabs.get(chat.getId());
            if (openTab != null) {
                openTab.closeTab();
            }
            unregisterSavedSwarmChatTab(chat.getId());
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete saved swarm chat {}", chat.getId(), e);
            showError(I18n.get("error.title"), e.getMessage());
            return false;
        }
    }

    private Optional<AiProfile> promptForSavedChatProfile(SavedAiChat chat, List<AiProfile> availableProfiles) {
        Dialog<AiProfile> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.profile.missing.title"));
        dialog.setHeaderText(I18n.get("ai.profile.missing.header",
            chat.getTitle() != null && !chat.getTitle().isBlank() ? chat.getTitle().trim() : I18n.get("ai.saved.defaultTitle")));
        dialog.initOwner(stage);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<AiProfile> profileBox = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(availableProfiles));
        profileBox.setPrefWidth(360);
        profileBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        profileBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        AiProfile defaultProfile = getDefaultAiProfile();
        if (defaultProfile != null) {
            profileBox.getSelectionModel().select(
                availableProfiles.stream()
                    .filter(profile -> defaultProfile.getId() != null && defaultProfile.getId().equals(profile.getId()))
                    .findFirst()
                    .orElse(availableProfiles.getFirst()));
        } else {
            profileBox.getSelectionModel().selectFirst();
        }

        VBox content = new VBox(10, new Label(I18n.get("ai.profile.missing.content")), profileBox);
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(profileBox.getSelectionModel().getSelectedItem() == null);
        profileBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
            okButton.setDisable(newValue == null));

        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? profileBox.getSelectionModel().getSelectedItem() : null);
        return dialog.showAndWait();
    }

    private static String getProtocolLabel(ConnectionProtocol protocol) {
        if (protocol == null) return "SSH";
        return switch (protocol) {
            case MOSH -> I18n.get("protocol.mosh");
            case MOSH_CLIENT -> I18n.get("protocol.moshClient");
            case LOCAL_SHELL -> I18n.get("protocol.localShell");
            case SSH_TCP -> I18n.get("protocol.sshTcp");
        };
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    /**
     * This window's stable id, {@code "w"} plus a monotonic counter minted in the constructor.
     *
     * <p>It is deliberately not the position in {@link #getOpenWindows()}: positions shift whenever
     * an earlier window closes, so a caller holding an id would start addressing a different window
     * without noticing. The id is unique within one korTTY run and does not survive a restart.
     */
    public String getWindowId() {
        return windowId;
    }

    /**
     * The open window carrying {@code windowId}; JavaFX thread, because it walks the live open-window
     * list.
     */
    public static Optional<MainWindow> findWindowById(String windowId) {
        if (windowId == null || windowId.isBlank()) {
            return Optional.empty();
        }
        for (MainWindow window : new ArrayList<>(openWindows)) {
            if (windowId.equals(window.getWindowId())) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }

    public static List<MainWindow> getOpenWindows() {
        return openWindows;
    }

    public static void showAutomaticUpdateAvailable(AvailableUpdate update) {
        MainWindow window = getFocusedOrLastOpenWindow();
        if (window != null) {
            window.showUpdateAvailableDialog(update, false);
        }
    }

    /**
     * Shows the NOTIFY-policy result after the signed stable runtime index was verified. Only
     * called for updates to an installed runtime; users without one are never notified. Serves
     * both embedded runtimes — the runtime id already identifies llama.cpp vs MLX.
     */
    public static void showRuntimeUpdateAvailable(String runtimeId) {
        MainWindow window = getFocusedOrLastOpenWindow();
        if (window == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(window.getStage());
        alert.setTitle(I18n.get("ai.local.models.runtime.notification.title"));
        alert.setHeaderText(I18n.get("ai.local.models.runtime.notification.header", runtimeId));
        alert.setContentText(I18n.get("ai.local.models.runtime.notification.update"));
        alert.show();
    }

    /** Warns that a signed withdrawal has already quarantined and stopped the local runtime. */
    public static void showRuntimeRevoked(String revokedRuntimeId, String replacementRuntimeId) {
        MainWindow window = getFocusedOrLastOpenWindow();
        if (window == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(window.getStage());
        alert.setTitle(I18n.get("ai.local.models.runtime.revoked.notification.title"));
        alert.setHeaderText(I18n.get(
            "ai.local.models.runtime.revoked.notification.header", revokedRuntimeId));
        alert.setContentText(replacementRuntimeId != null
            ? I18n.get("ai.local.models.runtime.revoked.notification.replacement", replacementRuntimeId)
            : I18n.get("ai.local.models.runtime.revoked.notification.unavailable"));
        alert.show();
    }
    
    public Stage getStage() {
        return stage;
    }
    
    /**
     * Returns the currently selected tab in the main tab pane.
     */
    public Tab getActiveTab() {
        return tabPane.getSelectionModel().getSelectedItem();
    }

    /**
     * The tab a snippet should be inserted into or sent to: the selected tab when it is a
     * {@code type}, else the last selected {@code type} tab of this window (the snippet workspace
     * itself may be the selected tab), else the only open one. {@code null} when there is none.
     * Supported types: {@link TerminalTab}, {@link FileEditorTab}.
     */
    <T extends Tab> T snippetInsertTarget(Class<T> type) {
        Tab lastOfType = type == TerminalTab.class ? lastSelectedTerminalTab
            : type == FileEditorTab.class ? lastSelectedFileEditorTab
            : null;
        return chooseInsertTarget(type, tabPane.getSelectionModel().getSelectedItem(), lastOfType, tabPane.getTabs());
    }

    /**
     * Pure choice behind {@link #snippetInsertTarget(Class)}: the selected tab if it is a
     * {@code type}; else {@code lastOfType} while it is still open; else the only open
     * {@code type} tab; else {@code null} (none, or several and no way to tell which).
     */
    static <T extends Tab> T chooseInsertTarget(Class<T> type, Tab selected, Tab lastOfType,
                                                List<? extends Tab> openTabs) {
        if (type == null || openTabs == null) {
            return null;
        }
        if (type.isInstance(selected) && openTabs.contains(selected)) {
            return type.cast(selected);
        }
        if (type.isInstance(lastOfType) && openTabs.contains(lastOfType)) {
            return type.cast(lastOfType);
        }
        T only = null;
        for (Tab tab : openTabs) {
            if (type.isInstance(tab)) {
                if (only != null) {
                    return null;
                }
                only = type.cast(tab);
            }
        }
        return only;
    }

    /** Whether {@code tab} is open in this window. */
    boolean holdsTab(Tab tab) {
        return tab != null && tabPane.getTabs().contains(tab);
    }

    /**
     * After a snippet was sent to {@code target}: shows that tab and says so in the status bar
     * (in tab mode the snippet workspace hid the terminal it just typed into).
     */
    void revealSnippetInsertTarget(Tab target) {
        if (target == null || !tabPane.getTabs().contains(target)) {
            return;
        }
        tabPane.getSelectionModel().select(target);
        String name = target.getText() != null && !target.getText().isBlank()
            ? target.getText().trim()
            : I18n.get("snippets.insertTerminal.unnamed");
        updateStatus(I18n.get("snippets.insert.sentTo", name));
    }
    
    /**
     * Opens all connections in a group as tabs.
     */
    private void openGroupConnections(String groupName) {
        List<ServerConnection> groupConnections = app.getConfigManager().getConnections().stream()
                .filter(c -> groupName.equals(c.getGroup()))
                .collect(java.util.stream.Collectors.toList());
        
        if (groupConnections.isEmpty()) {
            logger.warn("No connections found in group: {}", groupName);
            return;
        }

        // Enterprise server policy, as for a single connection: a member whose server or jump
        // server is blocked is skipped before its password is read, its usage counted or a tab
        // built, and one policy message names every blocked target. The allowed members open.
        de.kortty.policy.ServerAccessPolicy.Partition policyPartition =
            de.kortty.policy.ServerAccessPolicy.partition(groupConnections);
        if (!policyPartition.blockedTargets().isEmpty()) {
            logger.warn("Group {}: skipping {} connection(s) blocked by the enterprise policy",
                groupName, groupConnections.size() - policyPartition.allowed().size());
            de.kortty.policy.PolicyUiSupport.showBlockedServerDialog(policyPartition.blockedTargetList());
        }
        if (policyPartition.allowed().isEmpty()) {
            return;
        }

        logger.info("Opening {} connections from group: {}", policyPartition.allowed().size(), groupName);
        
        int opened = 0;
        List<String> skippedWithoutPassword = new ArrayList<>();
        for (ServerConnection conn : policyPartition.allowed()) {
            // As in Duplicate: local shells and SSH key auth open without a password; a password
            // login needs a stored one (credential store or vault) and is skipped without it.
            GroupOpenSupport.Decision decision = GroupOpenSupport.decide(conn, this::getConnectionPassword);
            if (!decision.open()) {
                logger.warn("No password found for connection: {}", conn.getDisplayName());
                skippedWithoutPassword.add(conn.getDisplayName());
                continue;
            }
            String password = decision.password();
            
            // Increment usage count
            conn.incrementUsageCount();
            
            // Open tab
            TerminalTab tab = new TerminalTab(conn, password);
            // Assign the connection's group so the tab shows under it (dashboard, tab ordering);
            // the single-connection path does the same in openConnectionInternal.
            if (conn.getGroup() != null && !conn.getGroup().trim().isEmpty()) {
                tab.setGroup(conn.getGroup().trim());
            }
            registerTerminalTabForAiAgentDock(tab);
            if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                tab.getTerminalView().setTerminalEffectAnimationSpeed(
                        conn.getTerminalEffectAnimationSpeed() != null
                                ? conn.getTerminalEffectAnimationSpeed()
                                : TerminalEffectAnimationSpeed.DEFAULT);
                tab.getTerminalView().setTerminalEffectPluginId(conn.getTerminalEffectPluginId());
            }
            installAiSelectionHandler(tab);
            tab.setTimestampToggleListener(() -> Platform.runLater(() -> {
                Tab activeTab = tabPane.getSelectionModel().getSelectedItem();
                if (activeTab instanceof TerminalTab active) {
                    syncTimestampMenuItems(active.isTimestampGuttersVisible());
                }
            }));
            tab.setOnUserCloseApproved(MainWindow::recordClosedByButton);
            applyConnectionColor(tab);
            tab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus();
            });
            setupTabContextMenu(tab);
            tabPane.getTabs().add(tab);
            tab.connect();
            opened++;
            
            // Select the first tab
            if (tabPane.getTabs().size() == 1) {
                tabPane.getSelectionModel().select(tab);
            }
        }
        
        // Save updated usage counts
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.error("Failed to save usage counts", e);
        }
        
        updateStatus(I18n.get("status.groupOpened", groupName, opened));
        updateDashboard();
        if (!skippedWithoutPassword.isEmpty()) {
            showGroupMembersSkippedWithoutPassword(groupName, skippedWithoutPassword);
        }
    }

    /**
     * Names the members of {@code groupName} that Open Group skipped because they log in with a
     * password and none is stored, so they do not just silently stay closed.
     */
    private void showGroupMembersSkippedWithoutPassword(String groupName, List<String> skipped) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(stage);
        alert.setTitle(I18n.get("quickConnect.openGroup"));
        alert.setHeaderText(I18n.get("quickConnect.groupSkipped.header", skipped.size(), groupName));
        alert.setContentText(I18n.get("quickConnect.groupSkipped.content",
            "• " + String.join("\n• ", skipped)));
        alert.show();
    }
    
    
    // ---- Tool windows as tabs -------------------------------------------------------------------

    /** Whether management tool windows should open as tabs in this window instead of own windows. */
    private boolean toolTabsEnabled() {
        GlobalSettings gs = app.getGlobalSettingsManager().getSettings();
        return gs != null && gs.isOpenToolWindowsAsTabs();
    }

    /**
     * Returns the tool tab for {@code toolId} in THIS window's tab pane after selecting it, or
     * {@code null} if the tool is not open here. Scan-based like {@link #openSFTPManagerTab}: tabs
     * can be dragged between windows, so the live tab list is the only reliable registry.
     */
    private DialogHostTab findAndSelectToolTab(String toolId) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof DialogHostTab hostTab && toolId.equals(hostTab.getToolId())) {
                tabPane.getSelectionModel().select(hostTab);
                return hostTab;
            }
        }
        return null;
    }

    /** Hosts {@code dialog} as a deduped tool tab in this window; see {@link DialogHostTab}. */
    private DialogHostTab hostToolTab(String toolId, ThemeAwareDialog<?> dialog, Runnable afterClosed) {
        return DialogHostTab.host(tabPane, toolId, dialog, afterClosed);
    }

    /**
     * Hosts a multi-instance tool (snippet editor, code analysis) as a NEW tab each time — no
     * dedupe, per the tab-mode UX: any number of editors/analyses may be open side by side.
     */
    void hostMultiInstanceToolTab(ThemeAwareDialog<?> dialog) {
        DialogHostTab.host(tabPane, null, dialog, null);
    }

    /** The open main window whose stage is {@code window}, or {@code null}. */
    static MainWindow findByStage(Window window) {
        if (window == null) {
            return null;
        }
        for (MainWindow openWindow : getOpenWindows()) {
            if (openWindow.getStage() == window) {
                return openWindow;
            }
        }
        return null;
    }

    private void showCredentialManagement() {
        Telemetry.track(TelemetryEvents.SECURITY_MANAGER_OPENED, Map.of("manager", "credentials"));
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("credentials") != null) {
                    return;
                }
                CredentialManagementDialog dialog = new CredentialManagementDialog(
                    app.getCredentialManager(),
                    app.getEnvironmentManager(),
                    app.getMasterPasswordManager().getMasterPassword()
                );
                hostToolTab("credentials", dialog, null);
                return;
            }
            CredentialManagementDialog dialog = new CredentialManagementDialog(
                app.getCredentialManager(),
                app.getEnvironmentManager(),
                app.getMasterPasswordManager().getMasterPassword()
            );
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show credential management", e);
            showError(I18n.get("error.title"), I18n.get("error.credentialManagementFailed", e.getMessage()));
        }
    }
    
    private void showGPGKeyManagement() {
        Telemetry.track(TelemetryEvents.SECURITY_MANAGER_OPENED, Map.of("manager", "gpg_keys"));
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("gpgKeys") == null) {
                    hostToolTab("gpgKeys", new GPGKeyManagementDialog(app.getGpgKeyManager()), null);
                }
                return;
            }
            GPGKeyManagementDialog dialog = new GPGKeyManagementDialog(app.getGpgKeyManager());
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show GPG key management", e);
            showError(I18n.get("error.title"), I18n.get("error.gpgKeyManagementFailed", e.getMessage()));
        }
    }
    
    private void showTeamworkSettings() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "teamwork_settings"));
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("teamwork") == null) {
                    hostToolTab("teamwork", new TeamworkSettingsDialog(stage, app), null);
                }
                return;
            }
            TeamworkSettingsDialog dialog = new TeamworkSettingsDialog(stage, app);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show teamwork settings", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void showTerminalEffectPluginManager() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "terminal_effect_manager"));
        try {
            var manager = app.getTerminalEffectPluginManager();
            if (manager == null) {
                showError(I18n.get("error.title"), I18n.get("plugin.initError"));
                return;
            }
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("terminalEffects") == null) {
                    hostToolTab("terminalEffects", new TerminalEffectPluginManagerDialog(stage, manager), () -> {
                        deactivateTerminalEffectsIfDisabled();
                        deactivateUnavailableTerminalEffects();
                        updateAllTabContextMenus();
                    });
                }
                return;
            }
            TerminalEffectPluginManagerDialog dialog =
                    new TerminalEffectPluginManagerDialog(stage, manager);
            dialog.showAndWait();
            deactivateTerminalEffectsIfDisabled();
            deactivateUnavailableTerminalEffects();
            updateAllTabContextMenus();
        } catch (Exception e) {
            logger.error("Failed to show terminal effect plugin manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void deactivateUnavailableTerminalEffects() {
        var manager = app.getTerminalEffectPluginManager();
        if (manager == null) {
            return;
        }
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String pluginId = terminalTab.getTerminalView().getTerminalEffectPluginId();
                if (pluginId != null && manager.findPlugin(pluginId).isEmpty()) {
                    terminalTab.getTerminalView().setTerminalEffectPluginId(null);
                }
            }
        }
    }

    private void deactivateTerminalEffectsIfDisabled() {
        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            return;
        }
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().setTerminalEffectPluginId(null);
            }
        }
    }
    
    private void showSSHKeyManagement() {
        Telemetry.track(TelemetryEvents.SECURITY_MANAGER_OPENED, Map.of("manager", "ssh_keys"));
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("sshKeys") == null) {
                    hostToolTab("sshKeys", new SSHKeyManagementDialog(
                        app.getSSHKeyManager(),
                        app.getMasterPasswordManager().getMasterPassword()), null);
                }
                return;
            }
            SSHKeyManagementDialog dialog = new SSHKeyManagementDialog(
                app.getSSHKeyManager(),
                app.getMasterPasswordManager().getMasterPassword()
            );
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show SSH key management", e);
            showError(I18n.get("error.title"), I18n.get("error.sshKeyManagementFailed", e.getMessage()));
        }
    }

    /** Opens the trusted SSH host keys (Configuration › Security › Known Hosts…). */
    private void showKnownHosts() {
        Telemetry.track(TelemetryEvents.SECURITY_MANAGER_OPENED, Map.of("manager", "known_hosts"));
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("knownHosts") == null) {
                    hostToolTab("knownHosts", new KnownHostsDialog(), null);
                }
                return;
            }
            KnownHostsDialog dialog = new KnownHostsDialog();
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show the known hosts", e);
            showError(I18n.get("error.title"), I18n.get("ssh.knownHosts.loadFailed", e.getMessage()));
        }
    }

    private void showAsciiArtBanner() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "ascii_art"));
        try {
            AsciiArtBannerDialog dialog = new AsciiArtBannerDialog(this);
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to open ASCII Art Banner dialog", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void showTerminalRecordingManager() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "video_manager"));
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("recordings") == null) {
                    TerminalRecordingManagerDialog dialog = new TerminalRecordingManagerDialog(
                        app.getGlobalSettingsManager(),
                        new de.kortty.core.TerminalRecordingService());
                    dialog.setOnHidden(event -> refreshTerminalRecordingControlsVisibility());
                    hostToolTab("recordings", dialog, null);
                }
                return;
            }
            TerminalRecordingManagerDialog dialog = new TerminalRecordingManagerDialog(
                app.getGlobalSettingsManager(),
                new de.kortty.core.TerminalRecordingService());
            dialog.initOwner(stage);
            dialog.setOnHidden(event -> refreshTerminalRecordingControlsVisibility());
            dialog.show();
        } catch (Exception e) {
            logger.error("Failed to open terminal recording manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void refreshTerminalRecordingControlsVisibility() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTab.refreshRecordingControlsVisibility();
            }
        }
    }

    private void toggleTerminalRecording() {
        Tab activeTab = getActiveTab();
        if (activeTab instanceof TerminalTab terminalTab) {
            terminalTab.toggleRecordingFromMenuOrShortcut();
            return;
        }
        updateStatus(I18n.get("terminal.recording.error.noTerminal"));
    }
    
    private void showSnippetManager() {
        showSnippetWorkspace(null);
    }

    /**
     * Opens the snippet workspace (Snippet Manager) of this window, or brings the open one forward
     * and focuses its search. One workspace per main window: a tool tab in tab mode, a window
     * otherwise. An open instance of either kind is reused, even after the tab setting changed.
     *
     * @param snippetIdOrNull a snippet to open pinned in the workspace, or {@code null}
     */
    void showSnippetWorkspace(String snippetIdOrNull) {
        logger.info("showSnippetWorkspace() called - Opening Snippet Manager");
        try {
            de.kortty.core.SnippetManager mgr = app.getSnippetManager();
            if (mgr == null) {
                showError(I18n.get("error.title"), "Snippet Manager not initialized");
                return;
            }
            SnippetWorkspaceDialog workspace = snippetWorkspace;
            // "tab" or "window": how the workspace is shown (an open one keeps its form).
            String mode;
            if (workspace != null) {
                mode = "window";
                bringDialogToFront(workspace);
            } else {
                DialogHostTab existing = findAndSelectToolTab(SnippetWorkspaceDialog.TOOL_ID);
                if (existing != null && existing.getHostedDialog() instanceof SnippetWorkspaceDialog hosted) {
                    mode = "tab";
                    workspace = hosted;
                } else if (toolTabsEnabled()) {
                    mode = "tab";
                    workspace = new SnippetWorkspaceDialog(mgr, this);
                    hostToolTab(SnippetWorkspaceDialog.TOOL_ID, workspace, null);
                } else {
                    mode = "window";
                    SnippetWorkspaceDialog windowed = new SnippetWorkspaceDialog(mgr, this);
                    windowed.initOwner(stage);
                    windowed.setOnTornDown(() -> {
                        if (snippetWorkspace == windowed) {
                            snippetWorkspace = null;
                        }
                    });
                    snippetWorkspace = windowed;
                    windowed.show();
                    workspace = windowed;
                }
            }
            Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "snippet_manager", "mode", mode));
            if (snippetIdOrNull != null) {
                workspace.openSnippetById(snippetIdOrNull, true);
            } else {
                workspace.focusSearch();
            }
            showSnippetLoadFailureNoticeOnce(mgr);
        } catch (Exception e) {
            logger.error("Failed to open Snippet Manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    /**
     * Tells the user once per session where an unreadable {@code snippets.xml} /
     * {@code snippet-variables.xml} was moved aside at startup (the manager they just opened looks
     * empty otherwise, with no hint that nothing was lost). Non-modal, so the manager stays usable.
     */
    private void showSnippetLoadFailureNoticeOnce(de.kortty.core.SnippetManager mgr) {
        if (snippetLoadFailureNoticeShown) {
            return;
        }
        List<java.nio.file.Path> backups = new ArrayList<>();
        mgr.getLoadFailureBackup().ifPresent(backups::add);
        de.kortty.core.SnippetVariableManager variableManager = app.getSnippetVariableManager();
        if (variableManager != null) {
            variableManager.getLoadFailureBackup().ifPresent(backups::add);
        }
        if (backups.isEmpty()) {
            return;
        }
        snippetLoadFailureNoticeShown = true;
        String paths = backups.stream().map(java.nio.file.Path::toString)
            .collect(java.util.stream.Collectors.joining("\n"));
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("snippets.loadFailed.title"));
        alert.setHeaderText(I18n.get("snippets.loadFailed.header"));
        alert.setContentText(I18n.get("snippets.loadFailed.content", paths));
        alert.initOwner(stage);
        alert.initModality(javafx.stage.Modality.NONE);
        alert.show();
    }

    /**
     * Tells the user at startup which data files could not be read: {@code movedAside} lists the
     * {@code *.corrupt-<timestamp>} copies korTTY continues without, {@code blocked} the files it
     * left in place and will not save over in this session; {@code settingsRecovery} (nullable) says
     * whether an unreadable {@code global-settings.xml} was repaired or reset and where its copy is.
     * Non-modal, so korTTY stays usable.
     */
    public void showStoreLoadFailureNotice(List<java.nio.file.Path> movedAside, List<java.nio.file.Path> blocked,
                                           de.kortty.core.GlobalSettingsManager.LoadRecovery settingsRecovery) {
        List<java.nio.file.Path> moved = movedAside != null ? movedAside : List.of();
        List<java.nio.file.Path> unwritable = blocked != null ? blocked : List.of();
        if (moved.isEmpty() && unwritable.isEmpty() && settingsRecovery == null) {
            return;
        }
        List<String> sections = new ArrayList<>();
        if (settingsRecovery != null && settingsRecovery.backup() != null) {
            sections.add(settingsRecovery.outcome() == de.kortty.core.GlobalSettingsManager.LoadRecovery.Outcome.RECOVERED
                ? I18n.get("storage.loadFailed.settingsRecovered",
                    String.valueOf(settingsRecovery.removedCharacters()), settingsRecovery.backup().toString())
                : I18n.get("storage.loadFailed.settingsReset", settingsRecovery.backup().toString()));
        }
        if (!moved.isEmpty()) {
            sections.add(I18n.get("storage.loadFailed.content", joinPaths(moved)));
        }
        if (!unwritable.isEmpty()) {
            sections.add(I18n.get("storage.loadFailed.blocked", joinPaths(unwritable)));
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("storage.loadFailed.title"));
        alert.setHeaderText(I18n.get("storage.loadFailed.header"));
        alert.setContentText(String.join("\n\n", sections));
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        alert.initOwner(stage);
        alert.initModality(javafx.stage.Modality.NONE);
        alert.show();
    }

    private static String joinPaths(List<java.nio.file.Path> paths) {
        return paths.stream().map(java.nio.file.Path::toString)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void showJobScheduler() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "job_scheduler"));
        showJobSchedulerWithDraft(null);
    }

    /** Opens the Job Scheduler with {@code jobId} selected — "open job" from a journal group. */
    void showJobSchedulerForJob(String jobId) {
        showJobScheduler(dialog -> dialog.selectJob(jobId));
    }

    /** Opens the Job Scheduler; a non-null draft (e.g. from the AI-swarm window) is preselected. */
    void showJobSchedulerWithDraft(de.kortty.jobscheduler.ScheduledJob draft) {
        showJobScheduler(draft != null ? dialog -> dialog.prefillNewJob(draft) : null);
    }

    private void showJobScheduler(java.util.function.Consumer<JobSchedulerDialog> prepare) {
        logger.info("showJobScheduler() called - Opening JobScheduler");
        try {
            if (app.getJobSchedulerService() == null) {
                showError(I18n.get("error.title"), "JobScheduler is not initialized.");
                return;
            }
            if (toolTabsEnabled()) {
                DialogHostTab existing = findAndSelectToolTab("jobScheduler");
                if (existing != null) {
                    if (prepare != null) {
                        prepare.accept((JobSchedulerDialog) existing.getHostedDialog());
                    }
                    return;
                }
                JobSchedulerDialog tabDialog = new JobSchedulerDialog(app, stage);
                if (prepare != null) {
                    prepare.accept(tabDialog);
                }
                hostToolTab("jobScheduler", tabDialog, null);
                return;
            }
            JobSchedulerDialog dialog = new JobSchedulerDialog(app, stage);
            if (prepare != null) {
                prepare.accept(dialog);
            }
            dialog.show();
        } catch (Exception e) {
            logger.error("Failed to open JobScheduler", e);
            showError(I18n.get("error.title"), "JobScheduler could not be opened: " + e.getMessage());
        }
    }

    private void showAiManager() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "ai_manager"));
        if (!isAiFeaturesEnabled()) {
            return;
        }
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("aiManager") == null) {
                    hostToolTab("aiManager", new AiManagerDialog(this), null);
                }
                return;
            }
            if (aiManagerDialog != null) {
                if (aiManagerDialog.isShowing()) {
                    bringAiManagerToFront(aiManagerDialog);
                    return;
                }
                aiManagerDialog = null;
            }

            AiManagerDialog dialog = new AiManagerDialog(this);
            dialog.initOwner(stage);
            dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
                if (aiManagerDialog == dialog) {
                    aiManagerDialog = null;
                }
            });
            aiManagerDialog = dialog;
            dialog.show();
            bringAiManagerToFront(dialog);
        } catch (Exception e) {
            if (aiManagerDialog != null && !aiManagerDialog.isShowing()) {
                aiManagerDialog = null;
            }
            logger.error("Failed to open AI Manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private static void bringAiManagerToFront(AiManagerDialog dialog) {
        Window window = dialog.getDialogPane().getScene() != null
            ? dialog.getDialogPane().getScene().getWindow()
            : null;
        if (window instanceof Stage managerStage) {
            managerStage.setIconified(false);
            managerStage.toFront();
            managerStage.requestFocus();
        }
    }

    private void showSavedChats() {
        Telemetry.track(TelemetryEvents.TOOL_OPENED, Map.of("tool", "saved_chats"));
        if (!isAiFeaturesEnabled()) {
            return;
        }
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("savedChats") == null) {
                    hostToolTab("savedChats", new SavedChatsDialog(this), null);
                }
                return;
            }
            if (savedChatsDialog != null) {
                if (savedChatsDialog.isShowing()) {
                    bringDialogToFront(savedChatsDialog);
                    return;
                }
                savedChatsDialog = null;
            }

            SavedChatsDialog dialog = new SavedChatsDialog(this);
            dialog.initOwner(stage);
            dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
                if (savedChatsDialog == dialog) {
                    savedChatsDialog = null;
                }
            });
            savedChatsDialog = dialog;
            dialog.show();
            bringDialogToFront(dialog);
        } catch (Exception e) {
            if (savedChatsDialog != null && !savedChatsDialog.isShowing()) {
                savedChatsDialog = null;
            }
            logger.error("Failed to open saved chats window", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    private void showSessionJournalManager() {
        de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.TOOL_OPENED,
            Map.of("tool", "session_journals"));
        if (!de.kortty.policy.PolicyManager.effective().sessionJournalAllowed()) {
            return;
        }
        try {
            if (toolTabsEnabled()) {
                if (findAndSelectToolTab("sessionJournals") == null) {
                    hostToolTab("sessionJournals", new SessionJournalManagerDialog(this), null);
                }
                return;
            }
            if (sessionJournalManagerDialog != null) {
                if (sessionJournalManagerDialog.isShowing()) {
                    sessionJournalManagerDialog.refresh();
                    bringDialogToFront(sessionJournalManagerDialog);
                    return;
                }
                sessionJournalManagerDialog = null;
            }
            SessionJournalManagerDialog dialog = new SessionJournalManagerDialog(this);
            dialog.initOwner(stage);
            dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
                if (sessionJournalManagerDialog == dialog) {
                    sessionJournalManagerDialog = null;
                }
            });
            sessionJournalManagerDialog = dialog;
            dialog.show();
            bringDialogToFront(dialog);
        } catch (Exception e) {
            if (sessionJournalManagerDialog != null && !sessionJournalManagerDialog.isShowing()) {
                sessionJournalManagerDialog = null;
            }
            logger.error("Failed to open session journal manager", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    /** Opens (or re-fronts) the viewer for one journal; several journals can be open side by side. */
    void openSessionJournal(de.kortty.model.SessionJournalMeta meta) {
        openSessionJournal(meta, null);
    }

    /**
     * Opens (or re-fronts) the viewer and hands the pane to {@code onReady} — the hook search
     * results use to jump to their hit. The callback runs on the FX thread, for an already-open
     * journal immediately and for a fresh one right after the viewer is created (the pane itself
     * queues jumps until its page has loaded).
     */
    void openSessionJournal(de.kortty.model.SessionJournalMeta meta,
                            java.util.function.Consumer<SessionJournalViewerPane> onReady) {
        if (meta == null || meta.getDirectory() == null) {
            return;
        }
        java.nio.file.Path key = meta.getDirectory().toAbsolutePath().normalize();
        try {
            SessionJournalViewerDialog existing = sessionJournalViewers.get(key);
            if (existing != null) {
                if (existing.isOpenAsDialogOrTab()) {
                    bringDialogToFront(existing);
                    if (onReady != null) {
                        onReady.accept(existing.getPane());
                    }
                    return;
                }
                sessionJournalViewers.remove(key);
            }
            SessionJournalViewerDialog viewer = new SessionJournalViewerDialog(this, meta);
            sessionJournalViewers.put(key, viewer);
            if (toolTabsEnabled()) {
                hostMultiInstanceToolTab(viewer);
            } else {
                viewer.initOwner(stage);
                viewer.show();
                bringDialogToFront(viewer);
            }
            if (onReady != null) {
                onReady.accept(viewer.getPane());
            }
        } catch (Exception e) {
            sessionJournalViewers.remove(key);
            logger.error("Failed to open session journal viewer", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    /** Called by the viewer when it is disposed so a later open creates a fresh instance. */
    void onSessionJournalViewerClosed(java.nio.file.Path journalDir) {
        if (journalDir != null) {
            sessionJournalViewers.remove(journalDir.toAbsolutePath().normalize());
        }
    }

    private void toggleSessionJournal() {
        TerminalTab activeTab = getActiveTerminalTab();
        if (activeTab != null) {
            activeTab.toggleJournalFromMenuOrShortcut();
            updateAllTabContextMenus();
        } else {
            updateStatus(I18n.get("terminal.journal.error.notConnected"));
        }
    }

    private void takeSessionJournalScreenshot() {
        TerminalTab activeTab = getActiveTerminalTab();
        if (activeTab != null) {
            activeTab.takeJournalScreenshot(null);
        } else {
            updateStatus(I18n.get("terminal.journal.error.notConnected"));
        }
    }

    private static void bringDialogToFront(Dialog<?> dialog) {
        Window window = dialog.getDialogPane().getScene() != null
            ? dialog.getDialogPane().getScene().getWindow()
            : null;
        if (window instanceof Stage dialogStage) {
            dialogStage.setIconified(false);
            dialogStage.toFront();
            dialogStage.requestFocus();
        }
    }

    private void showAiWizard() {
        if (!isAiFeaturesEnabled()
            || !de.kortty.policy.PolicyManager.effective().aiProfileCreateAllowed()) {
            return;
        }
        try {
            AiProfileWizardDialog dialog = new AiProfileWizardDialog(this);
            dialog.initOwner(stage);
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to open AI setup wizard", e);
            showError(I18n.get("error.title"), e.getMessage());
        }
    }

    /**
     * Offers the beginner setup wizard when no usable AI profile is available. Falls back to no
     * action when the user dismisses the prompt.
     */
    private void suggestAiWizard(String contextTitle) {
        if (!isAiFeaturesEnabled()) {
            return;
        }
        ButtonType start = new ButtonType(I18n.get("ai.wizard.suggest.start"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, I18n.get("ai.wizard.suggest.message"), start, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(contextTitle != null ? contextTitle : I18n.get("ai.wizard.suggest.title"));
        alert.setHeaderText(null);
        alert.initOwner(stage);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == start) {
            showAiWizard();
        }
    }
    
    private void showSFTPManager() {
        logger.info("showSFTPManager() called - Opening SFTP Manager");
        
        // Check if there's an active connection in the current tab
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab instanceof TerminalTab terminalTab && terminalTab.isConnected()) {
            // The focused pane's session and folder (D7); a separate login when it cannot be borrowed.
            logger.info("Using active connection: {}", terminalTab.getConnection().getDisplayName());
            openSftpHere(terminalTab, null);
            return;
        }
        
        logger.info("No active connection - showing connection selection dialog");
        
        // Show connection selection dialog
        ConnectionSelectionDialog dialog = new ConnectionSelectionDialog(
            stage, 
            app.getConfigManager().getConnections(),
            I18n.get("sftp.selectConnection")
        );
        
        dialog.showAndWait().ifPresent(connection -> {
            logger.info("Connection selected: {}", connection.getDisplayName());
            // Connection selection - do NOT use temp key (only when opened from tab with temp key)
            openSFTPManagerForConnection(connection, null);
        });
    }
    
    // ---------------------------------------------------------------- Remote files sidebar (SFTP-15)

    private javafx.animation.PauseTransition remoteSidebarWidthSaveDelay;

    /** Settings: where terminal tabs show the remote files sidebar. */
    private TerminalRemoteSidebarPosition terminalRemoteSidebarPosition() {
        var gsm = app != null ? app.getGlobalSettingsManager() : null;
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        return settings != null ? settings.getTerminalRemoteSidebarPosition() : TerminalRemoteSidebarPosition.HIDDEN;
    }

    /** Whether View › Remote Files Sidebar is offered: an SSH pane in the active tab, or the sidebar is on. */
    private boolean remoteSidebarMenuOffered() {
        if (terminalRemoteSidebarPosition() != TerminalRemoteSidebarPosition.HIDDEN) {
            return true;
        }
        TerminalTab active = getActiveTerminalTab();
        return active != null && active.getTerminalView() != null && active.getTerminalView().hasRemoteSidebarPane();
    }

    /** Docks (or removes) the remote files sidebar of {@code terminalTab} as the settings say. FX thread. */
    private void applyRemoteSidebar(TerminalTab terminalTab) {
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        if (view == null) {
            return;
        }
        var gsm = app != null ? app.getGlobalSettingsManager() : null;
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null) {
            return;
        }
        view.setRemoteSidebar(settings.getTerminalRemoteSidebarPosition(), settings.getTerminalRemoteSidebarWidth(),
            this::persistRemoteSidebarWidth,
            () -> setTerminalRemoteSidebarPosition(TerminalRemoteSidebarPosition.HIDDEN));
    }

    /** Applies the remote files sidebar setting to every terminal tab of every window. FX thread. */
    private static void applyRemoteSidebarToAllWindows() {
        for (MainWindow window : new ArrayList<>(openWindows)) {
            for (TerminalTab terminalTab : window.terminalTabs()) {
                window.applyRemoteSidebar(terminalTab);
            }
        }
    }

    /** View › Remote Files Sidebar › Show/Hide: on the right (D10) when hidden, else hidden. */
    private void toggleTerminalRemoteSidebar() {
        setTerminalRemoteSidebarPosition(terminalRemoteSidebarPosition() == TerminalRemoteSidebarPosition.HIDDEN
            ? TerminalRemoteSidebarPosition.RIGHT : TerminalRemoteSidebarPosition.HIDDEN);
    }

    /**
     * Moves the remote files sidebar of all terminal tabs and remembers it. Hiding asks first when
     * sidebar transfers are still running, since hiding closes the sidebar's SFTP channels.
     */
    private void setTerminalRemoteSidebarPosition(TerminalRemoteSidebarPosition position) {
        var gsm = app != null ? app.getGlobalSettingsManager() : null;
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null) {
            return;
        }
        if (position == TerminalRemoteSidebarPosition.HIDDEN) {
            int running = 0;
            for (MainWindow window : new ArrayList<>(openWindows)) {
                for (TerminalTab terminalTab : window.terminalTabs()) {
                    TerminalRemoteSidebar sidebar = terminalTab.getTerminalView() != null
                        ? terminalTab.getTerminalView().remoteSidebar() : null;
                    running += sidebar != null ? sidebar.activeTransferCount() : 0;
                }
            }
            if (running > 0) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    I18n.get("terminal.remoteSidebar.hideConfirm", String.valueOf(running)), ButtonType.OK, ButtonType.CANCEL);
                confirm.setHeaderText(null);
                confirm.initOwner(stage);
                if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
                    return;
                }
            }
        }
        settings.setTerminalRemoteSidebarPosition(position);
        try {
            gsm.save();
        } catch (Exception e) {
            logger.debug("Could not persist the remote files sidebar position: {}", e.getMessage());
        }
        applyRemoteSidebarToAllWindows();
    }

    /** The width the user dragged the sidebar to; saved debounced, like the live journal panel. */
    private void persistRemoteSidebarWidth(double width) {
        var gsm = app != null ? app.getGlobalSettingsManager() : null;
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null) {
            return;
        }
        settings.setTerminalRemoteSidebarWidth(width);
        if (remoteSidebarWidthSaveDelay == null) {
            remoteSidebarWidthSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(350));
            remoteSidebarWidthSaveDelay.setOnFinished(event -> {
                try {
                    gsm.save();
                } catch (Exception e) {
                    logger.debug("Could not persist the remote files sidebar width: {}", e.getMessage());
                }
            });
        }
        remoteSidebarWidthSaveDelay.playFromStart();
    }

    /**
     * "Open SFTP here": an SFTP tab on {@code pane}'s own SSH session (its {@link PaneOrigin}, not
     * the tab's connection), starting in the folder its shell is in (D7). The verdict, folder and
     * session supplier are taken on the FX thread; the SFTP channel opens off it. When the pane
     * cannot lend its session (Mosh, local shell, closed) or the server refuses SFTP on it, a
     * standalone tab with its own login for the pane's connection opens instead. One borrowed tab
     * per pane (D9): asking again selects it.
     *
     * @param pane the pane, or null for the tab's focused pane
     */
    void openSftpHere(TerminalTab terminalTab, SithTermFxWidget pane) {
        openSftpHere(terminalTab, pane, null);
    }

    /**
     * {@link #openSftpHere(TerminalTab, SithTermFxWidget)}, starting in {@code startPath} instead of
     * the shell's folder when given (the remote files sidebar's "Open in SFTP Manager").
     */
    void openSftpHere(TerminalTab terminalTab, SithTermFxWidget pane, String startPath) {
        TerminalView view = terminalTab != null ? terminalTab.getTerminalView() : null;
        if (view == null) {
            updateStatus(I18n.get("sftp.openHere.noTerminal"));
            return;
        }
        SithTermFxWidget target = pane != null ? pane : view.focusedPane();
        TerminalView.SftpOpenRequest captured = view.captureSftpOpenRequest(target);
        TerminalView.SftpOpenRequest request = captured != null && startPath != null && !startPath.isBlank()
            ? new TerminalView.SftpOpenRequest(captured.supplier(), captured.paneConnection(), captured.dedupeKey(),
                new de.kortty.ui.sftp.SftpOpenTargetResolver.OpenTarget(startPath,
                    de.kortty.ui.sftp.SftpOpenTargetResolver.Reason.TRACKED_DIRECTORY), captured.label())
            : captured;
        if (request == null) {
            if (terminalTab.isConnected()) {
                openSftpHereFallback(terminalTab, view.paneConnection(target));
            } else {
                updateStatus(I18n.get("sftp.openHere.noTerminal"));
            }
            return;
        }
        if (selectSftpTab(request.dedupeKey())) {
            return;
        }
        Thread attach = new Thread(() -> {
            try {
                de.kortty.core.SFTPSession attached = de.kortty.core.SFTPSession.attach(request.supplier(), request.paneConnection(), request.label());
                Platform.runLater(() -> showBorrowedSftpTab(terminalTab, request, attached));
            } catch (de.kortty.policy.PolicyRestrictionException e) {
                Platform.runLater(() -> showError(I18n.get("error.title"),
                    I18n.get("error.sftpManagerFailed", e.getMessage())));
            } catch (Exception e) {
                // The SFTP subsystem was refused, or the pane's session ended: log in separately.
                logger.info("SFTP could not use the terminal session ({}); opening a separate login", e.getMessage());
                Platform.runLater(() -> {
                    updateStatus(I18n.get("sftp.openHere.fallback"));
                    openSftpHereFallback(terminalTab, request.paneConnection());
                });
            }
        }, "SFTP-Attach");
        attach.setDaemon(true);
        attach.start();
    }

    /** A standalone SFTP tab for the pane's connection; the tab's temporary key only for the tab's own connection. */
    private void openSftpHereFallback(TerminalTab terminalTab, ServerConnection paneConnection) {
        ServerConnection tabConnection = terminalTab.getConnection();
        ServerConnection connection = paneConnection != null ? paneConnection : tabConnection;
        if (connection == null) {
            updateStatus(I18n.get("sftp.openHere.noTerminal"));
            return;
        }
        boolean tabsOwnConnection = connection == tabConnection || (tabConnection != null
            && connection.getId() != null && connection.getId().equals(tabConnection.getId()));
        de.kortty.model.TemporarySSHKey key = tabsOwnConnection ? terminalTab.getTemporarySSHKey() : null;
        openSFTPManagerForConnection(connection, key);
    }

    /** FX thread: shows the SFTP tab for an attached pane session, or closes it when the pane's tab opened meanwhile. */
    private void showBorrowedSftpTab(TerminalTab terminalTab, TerminalView.SftpOpenRequest request, de.kortty.core.SFTPSession attached) {
        if (selectSftpTab(request.dedupeKey())) {
            closeSftpQuietly(attached);
            return;
        }
        Telemetry.track(TelemetryEvents.SFTP_OPENED, Map.of("borrowed", true));
        ServerConnection paneConnection = request.paneConnection();
        String hintKey = request.target().hintKey();
        SFTPManagerTab sftpTab = new SFTPManagerTab(app, paneConnection, attached,
            () -> de.kortty.core.SFTPSession.attach(request.supplier(), paneConnection, request.label()),
            request.dedupeKey(), request.target().startPath(), hintKey != null ? I18n.get(hintKey) : null,
            () -> openSftpHereFallback(terminalTab, paneConnection), sftpAutoCloseMinutes(), this);
        tabPane.getTabs().add(sftpTab);
        tabPane.getSelectionModel().select(sftpTab);
        logger.info("Opened SFTP Manager tab on the terminal session of: {}", paneConnection.getDisplayName());
    }

    /** Selects the SFTP tab with {@code dedupeKey} and returns true, or returns false when there is none. */
    private boolean selectSftpTab(String dedupeKey) {
        if (dedupeKey == null) {
            return false;
        }
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof SFTPManagerTab sftpTab && dedupeKey.equals(sftpTab.getDedupeKey())) {
                tabPane.getSelectionModel().select(tab);
                return true;
            }
        }
        return false;
    }

    private static void closeSftpQuietly(de.kortty.core.SFTPSession session) {
        Thread closer = new Thread(() -> {
            try {
                session.close();
            } catch (Exception e) {
                logger.debug("Closing an unused SFTP session failed: {}", e.getMessage());
            }
        }, "SFTP-Close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Settings › SFTP Manager's auto-close timeout in minutes; 0 when disabled or unknown. */
    private int sftpAutoCloseMinutes() {
        try {
            var globalSettings = app.getGlobalSettingsManager().getSettings();
            if (globalSettings != null && globalSettings.getSftpAutoCloseMinutes() != null) {
                return globalSettings.getSftpAutoCloseMinutes();
            }
        } catch (Exception e) {
            logger.debug("Could not get SFTP timeout setting: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Opens SFTP Manager for a specific connection.
     * @param connection The connection to use
     * @param temporarySSHKey Optional temporary SSH key (only when opened from tab that used temp key)
     */
    private void openSFTPManagerForConnection(ServerConnection connection, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        Telemetry.track(TelemetryEvents.SFTP_OPENED, Map.of("borrowed", false));
        try {
            de.kortty.model.TemporarySSHKey keyToUse = temporarySSHKey;
            
            // Check if this connection was originally connected with a temporary SSH key
            boolean wasConnectedWithTempKey = (temporarySSHKey != null) || 
                (connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty());
            
            // When tab was connected with temp key: check if still valid, otherwise ask for new key
            if (wasConnectedWithTempKey) {
                if (keyToUse == null || !keyToUse.isValid()) {
                    // Ask for new temporary SSH key
                    keyToUse = requestNewTemporarySSHKey(connection);
                    if (keyToUse == null) {
                        return; // User cancelled
                    }
                }
            }
            
            // When using valid temporary SSH key or regular SSH key auth, no password needed
            String password = null;
            boolean isKeyAuth = connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY;
            if (keyToUse == null && !isKeyAuth) {
                password = getConnectionPassword(connection);
            }
            if (password == null && keyToUse == null && !isKeyAuth) {
                // Ask for password using a simple dialog (only when NOT using key auth)
                Dialog<String> pwdDialog = new Dialog<>();
                DialogThemeHelper.applyTheme(pwdDialog);
                pwdDialog.setTitle(I18n.get("dialog.passwordRequired"));
                pwdDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
                pwdDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                
                PasswordField passwordField = new PasswordField();
                passwordField.setPromptText(I18n.get("common.password"));
                VBox content = new VBox(10);
                content.setPadding(new javafx.geometry.Insets(20));
                content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), passwordField);
                pwdDialog.getDialogPane().setContent(content);
                
                pwdDialog.setResultConverter(buttonType -> {
                    if (buttonType == ButtonType.OK) {
                        return passwordField.getText();
                    }
                    return null;
                });
                
                Optional<String> result = pwdDialog.showAndWait();
                if (result.isPresent() && !result.get().isEmpty()) {
                    password = result.get();
                } else {
                    return; // User cancelled
                }
            }
            
            // Use empty password when temp key auth - SFTPSession uses key, not password
            String passwordToUse = (keyToUse != null && keyToUse.isValid()) ? "" : (password != null ? password : "");
            
            // Open SFTP Manager as a TAB instead of a dialog
            openSFTPManagerTab(connection, passwordToUse, keyToUse);
        } catch (Exception e) {
            logger.error("Failed to open SFTP manager", e);
            showError(I18n.get("error.title"), I18n.get("error.sftpManagerFailed", e.getMessage()));
        }
    }
    
    /**
     * Requests a new temporary SSH key from the user.
     * Shows a dialog where the user can enter a new valid temporary SSH key.
     * @param connection The connection that requires a temporary SSH key
     * @return The new temporary SSH key, or null if cancelled
     */
    private de.kortty.model.TemporarySSHKey requestNewTemporarySSHKey(ServerConnection connection) {
        Dialog<de.kortty.model.TemporarySSHKey> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("sftp.tempKeyRequired"));
        dialog.setHeaderText(I18n.get("sftp.tempKeyRequiredMessage"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, "sftp.temporarySshKey");
        
        // Create content
        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setPrefWidth(500);
        
        Label infoLabel = new Label(I18n.get("sftp.enterNewTempKey"));
        
        TextArea keyArea = new TextArea();
        keyArea.setPromptText("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----");
        keyArea.setPrefRowCount(8);
        keyArea.setWrapText(true);
        
        // Expiration spinner
        HBox expirationBox = new HBox(10);
        expirationBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label expirationLabel = new Label(I18n.get("quickConnect.expirationMinutes"));
        Spinner<Integer> expirationSpinner = new Spinner<>(1, 1440, 60);
        expirationSpinner.setEditable(true);
        expirationSpinner.setPrefWidth(100);
        expirationBox.getChildren().addAll(expirationLabel, expirationSpinner);
        
        content.getChildren().addAll(infoLabel, keyArea, expirationBox);
        dialog.getDialogPane().setContent(content);
        
        // Disable OK button until key is entered
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        keyArea.textProperty().addListener((obs, old, newVal) -> {
            okButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                String keyContent = keyArea.getText().trim();
                if (!keyContent.isEmpty()) {
                    long expirationMinutes = expirationSpinner.getValue();
                    de.kortty.model.TemporarySSHKey newKey = de.kortty.core.TemporarySSHKeyManager.getInstance()
                        .storeTemporaryKey(keyContent, expirationMinutes);
                    
                    // Update connection with new temporary key
                    connection.setTemporaryKeyContent(keyContent);
                    connection.setTemporaryKeyExpirationMinutes(expirationMinutes);
                    connection.setPrivateKeyPath("TEMPORARY:" + keyContent);
                    
                    return newKey;
                }
            }
            return null;
        });
        
        return dialog.showAndWait().orElse(null);
    }
    
    /**
     * Opens the SFTP Manager in a new tab.
     * @param connection The connection to use
     * @param password The password (or empty for temp key auth)
     * @param temporarySSHKey Optional temporary SSH key
     */
    private void openSFTPManagerTab(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        // One standalone tab per connection (D9): an existing one is selected. Tabs on a terminal
        // pane's session have per-pane keys and never match.
        if (selectSftpTab(de.kortty.ui.sftp.SftpTabKeys.standalone(connection))) {
            return;
        }
        int autoCloseMinutes = sftpAutoCloseMinutes();
        
        // Create new SFTP tab
        SFTPManagerTab sftpTab = new SFTPManagerTab(app, connection, password, temporarySSHKey, autoCloseMinutes, this);
        
        tabPane.getTabs().add(sftpTab);
        tabPane.getSelectionModel().select(sftpTab);
        
        logger.info("Opened SFTP Manager tab for: {}", connection.getDisplayName());
    }

    
    /**
     * The stored password of {@code connection}, from the credential store first (so a password
     * changed there applies at once), then from the connection's own encrypted password;
     * {@code null} when none is stored. The lookup is {@link ConnectionAuthResolver#storedPassword}'s.
     */
    private String getConnectionPassword(ServerConnection connection) {
        return connectionAuthResolver().storedPassword(connection);
    }

    /**
     * Duplicates a tab with the same connection details.
     * The new tab is inserted directly to the right of the source tab.
     */
    private void duplicateTab(TerminalTab sourceTab) {
        // The Connection Manager's sign-in: the enterprise server policy first, before any prompt —
        // the source tab passed it when it opened, but the connection editor changes a saved
        // connection in place, so its host or jump server may have been edited to a blocked one
        // since — then the temporary SSH key (reused while valid, asked for when expired), a local
        // shell or SSH key, the stored password or a password prompt.
        ConnectionAuthResolver.Resolution auth = resolveConnectionAuthInteractively(sourceTab.getConnection());
        if (!auth.isReady()) {
            return;
        }
        createDuplicateTab(sourceTab, auth.connection(), auth.password(), auth.temporaryKey());
    }
    
    /**
     * Creates a duplicated tab directly to the right of the source tab.
     */
    private void createDuplicateTab(TerminalTab sourceTab, ServerConnection connection, String password,
            de.kortty.model.TemporarySSHKey temporaryKey) {
        try {
            // Find the position of the source tab
            int sourceIndex = tabPane.getTabs().indexOf(sourceTab);
            if (sourceIndex == -1) {
                logger.warn("Source tab not found in tab pane");
                return;
            }
            
            // Create new tab with the same connection
            TerminalTab newTab = new TerminalTab(connection, password, temporaryKey);
            registerTerminalTabForAiAgentDock(newTab);
            installAiSelectionHandler(newTab);
            newTab.setTimestampToggleListener(() -> Platform.runLater(() -> {
                Tab activeTab = tabPane.getSelectionModel().getSelectedItem();
                if (activeTab instanceof TerminalTab active) {
                    syncTimestampMenuItems(active.isTimestampGuttersVisible());
                }
            }));
            newTab.setOnUserCloseApproved(MainWindow::recordClosedByButton);
            applyConnectionColor(newTab);
            newTab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus();
            });
            
            // Setup context menu
            setupTabContextMenu(newTab);
            
            // Take over the tab group from the source tab (if present)
            String sourceGroup = sourceTab.getGroup();
            if (sourceGroup != null && !sourceGroup.trim().isEmpty()) {
                newTab.setGroup(sourceGroup);
            }
            
            // Insert the new tab directly to the right of the source tab.
            int insertIndex = sourceIndex + 1;
            
            tabPane.getTabs().add(insertIndex, newTab);
            tabPane.getSelectionModel().select(newTab);
            
            // Update dashboard und context menus
            updateDashboard();
            updateAllTabContextMenus();
            
            newTab.setOnConnectedCallback(() -> {
                newTab.updateTabTitle();
                newTab.setStyle("");
                updateStatus(I18n.get("status.connectedToWithHostAndProtocol",
                        connection.getDisplayName(), connection.getHost(), getProtocolLabel(connection.getProtocol())));
                updateDashboard();
            });

            // Verbinde den neuen Tab
            new Thread(() -> {
                try {
                    newTab.connect();
                } catch (Exception e) {
                    logger.error("Failed to connect duplicated tab", e);
                }
            }, "Duplicate-Tab-Connect").start();
            
            logger.info("Tab duplicated: {} -> {}", sourceTab.getConnection().getDisplayName(), 
                        newTab.getConnection().getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to duplicate tab", e);
            showError(I18n.get("error.title"), I18n.get("error.tabDuplicateFailed", e.getMessage()));
        }
    }

    
    /**
     * Creates an encrypted backup of all settings.
     */
    private void createBackup() {
        // Show directory chooser
        javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
        dirChooser.setTitle(I18n.get("backup.selectDestination"));
        
        // Use last backup path as initial directory if available
        String lastPath = app.getGlobalSettingsManager().getSettings().getLastBackupPath();
        if (lastPath != null) {
            java.io.File lastDir = new java.io.File(lastPath);
            if (lastDir.exists() && lastDir.isDirectory()) {
                dirChooser.setInitialDirectory(lastDir);
            }
        } else {
            // Default to user home
            dirChooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        }
        
        java.io.File selectedDir = dirChooser.showDialog(stage);
        if (selectedDir == null) {
            return; // User cancelled
        }
        
        // Create backup in background
        javafx.concurrent.Task<java.nio.file.Path> backupTask = new javafx.concurrent.Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                updateMessage(I18n.get("backup.creating"));
                return app.getBackupManager().createBackup(
                    selectedDir.toPath(),
                    app.getCredentialManager(),
                    app.getGpgKeyManager(),
                    app.getMasterPasswordManager().getMasterPassword()
                );
            }
        };
        
        backupTask.setOnSucceeded(e -> {
            Telemetry.track(TelemetryEvents.BACKUP_ACTION, Map.of("action", "create"));
            java.nio.file.Path backupFile = backupTask.getValue();
            try {
                long fileSize = java.nio.file.Files.size(backupFile) / 1024; // KB
                
                // Determine encryption description
                String encryptionDesc = I18n.get("backup.encryption.unknown");
                if (app.getGlobalSettingsManager().getSettings().getBackupEncryptionType() 
                    == de.kortty.model.GlobalSettings.BackupEncryptionType.PASSWORD) {
                    encryptionDesc = I18n.get("backup.encryption.password");
                } else if (app.getGlobalSettingsManager().getSettings().getBackupEncryptionType() 
                           == de.kortty.model.GlobalSettings.BackupEncryptionType.GPG) {
                    encryptionDesc = I18n.get("backup.encryption.gpg");
                }
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                DialogThemeHelper.applyTheme(success);
                success.setTitle(I18n.get("backup.created"));
                success.setHeaderText(I18n.get("backup.createdSuccess"));
                // I18n fills the {0}/{1}/{2} placeholders; String.format left them as literal text.
                success.setContentText(I18n.get(
                    "backup.createdMessage",
                    backupFile.getFileName(),
                    fileSize,
                    encryptionDesc
                ));
                success.showAndWait();
                
                // Save global settings (updated by BackupManager)
                app.getGlobalSettingsManager().save();
                
                updateStatus(I18n.get("backup.createdSuccess") + ": " + backupFile.getFileName());
                
            } catch (Exception ex) {
                logger.error("Failed to get backup file size", ex);
            }
        });
        
        backupTask.setOnFailed(e -> {
            Throwable ex = backupTask.getException();
            logger.error("Backup creation failed", ex);
            
            Alert error = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(error);
            error.setTitle(I18n.get("error.title"));
            error.setHeaderText(I18n.get("backup.failed"));
            error.setContentText(I18n.get("backup.failedMessage") + "\n" + ex.getMessage());
            error.showAndWait();
        });
        
        // Update status and run task
        updateStatus(I18n.get("backup.creating"));
        
        Thread thread = new Thread(backupTask);
        thread.setDaemon(true);
        thread.start();
    }
    
    /**
     * Imports a backup from an encrypted backup file.
     */
    private void importBackup() {
        // Show file chooser
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("backup.import.selectFile"));
        
        // Add filters for backup files
        fileChooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter(
                I18n.get("backup.import.filter.backups"), "*.zip", "*.gpg")
        );
        fileChooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter(I18n.get("backup.import.filter.all"), "*.*")
        );
        
        // Use last backup path as initial directory if available
        String lastPath = app.getGlobalSettingsManager().getSettings().getLastBackupPath();
        if (lastPath != null) {
            java.io.File lastDir = new java.io.File(lastPath);
            if (lastDir.exists() && lastDir.isDirectory()) {
                fileChooser.setInitialDirectory(lastDir);
            }
        } else {
            // Default to user home
            fileChooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        }
        
        java.io.File backupFile = fileChooser.showOpenDialog(stage);
        if (backupFile == null) {
            return; // User cancelled
        }
        
        // The format comes from the file's content, not its name: GPG backups that older
        // versions saved as kortty-backup.zip must not be sent to the ZIP-password prompt.
        de.kortty.core.BackupManager.BackupFormat format;
        try {
            format = de.kortty.core.BackupManager.importFormat(backupFile.toPath());
        } catch (java.io.IOException ex) {
            logger.error("Could not read the backup file {}", backupFile, ex);
            showBackupImportError(ex.getMessage());
            return;
        }
        if (format == de.kortty.core.BackupManager.BackupFormat.UNKNOWN) {
            showBackupImportError(I18n.get("backup.import.unknownFormat", backupFile.getName()));
            return;
        }
        final String[] password = {null}; // Use array to allow modification in lambda
        
        if (format == de.kortty.core.BackupManager.BackupFormat.ZIP) {
            // Ask for password (masked input)
            Dialog<String> passwordDialog = new Dialog<>();
            DialogThemeHelper.applyTheme(passwordDialog);
            passwordDialog.setTitle(I18n.get("backup.import.password.title"));
            passwordDialog.setHeaderText(I18n.get("backup.import.password.header"));
            passwordDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            PasswordField pwField = new PasswordField();
            pwField.setPromptText(I18n.get("dialog.enterPassword"));
            VBox content = new VBox(10);
            content.getChildren().addAll(new Label(I18n.get("backup.import.password.content")), pwField);
            content.setPadding(new javafx.geometry.Insets(20));
            passwordDialog.getDialogPane().setContent(content);
            passwordDialog.setResultConverter(bt -> bt == ButtonType.OK ? pwField.getText() : null);
            java.util.Optional<String> passwordResult = passwordDialog.showAndWait();
            if (!passwordResult.isPresent() || passwordResult.get() == null || passwordResult.get().isEmpty()) {
                return; // User cancelled or entered empty password
            }
            password[0] = passwordResult.get();
        }
        
        // Ask if existing files should be overwritten
        javafx.scene.control.Alert overwriteDialog = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION
        );
        DialogThemeHelper.applyTheme(overwriteDialog);
        overwriteDialog.setTitle(I18n.get("backup.import.overwrite.title"));
        overwriteDialog.setHeaderText(I18n.get("backup.import.overwrite.header"));
        overwriteDialog.setContentText(I18n.get("backup.import.overwrite.content"));
        
        java.util.Optional<javafx.scene.control.ButtonType> overwriteResult = overwriteDialog.showAndWait();
        final boolean overwriteExisting = overwriteResult.isPresent() && 
            overwriteResult.get() == javafx.scene.control.ButtonType.OK;
        
        // Import backup in background
        final java.nio.file.Path backupFilePath = backupFile.toPath();
        javafx.concurrent.Task<de.kortty.core.BackupManager.ImportResult> importTask = new javafx.concurrent.Task<>() {
            @Override
            protected de.kortty.core.BackupManager.ImportResult call() throws Exception {
                updateMessage(I18n.get("backup.import.importing"));
                char[] current = app.getMasterPasswordManager() != null
                    ? app.getMasterPasswordManager().getMasterPassword() : null;
                char[] currentCopy = current != null ? current.clone() : null;
                try {
                    // A merge import of a backup made under another master password asks for that
                    // password and re-encrypts the backup's secrets with the current one.
                    return app.getBackupManager().restoreBackup(
                        backupFilePath,
                        password[0],
                        overwriteExisting,
                        currentCopy,
                        MainWindow.this::askBackupMasterPassword
                    );
                } finally {
                    if (currentCopy != null) {
                        java.util.Arrays.fill(currentCopy, '\0');
                    }
                }
            }
        };
        
        importTask.setOnSucceeded(e -> {
            Telemetry.track(TelemetryEvents.BACKUP_ACTION, Map.of("action", "import"));
            de.kortty.core.BackupManager.ImportResult result = importTask.getValue();
            int filesImported = result.filesImported();

            if (result.masterKeyReplaced()) {
                // The backup brought another master password. Every store in memory (and the
                // derived key the connections would be reloaded and saved with) belongs to the
                // old one, so korTTY neither reloads nor saves them: it quits and the next start
                // unlocks the restored files with the backup's master password.
                app.markRestoredBackupAwaitsRestart();
                Alert restart = new Alert(Alert.AlertType.WARNING);
                DialogThemeHelper.applyTheme(restart);
                restart.setTitle(I18n.get("backup.import.success"));
                restart.setHeaderText(I18n.get("backup.import.restartRequired.header"));
                restart.setContentText(I18n.get("backup.import.restartRequired.message", filesImported));
                restart.showAndWait();
                app.shutdownAndExit();
                return;
            }
            
            boolean secretsSkipped = !result.skippedSecretFiles().isEmpty();
            Alert success = new Alert(secretsSkipped ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION);
            DialogThemeHelper.applyTheme(success);
            success.setTitle(I18n.get("backup.import.success"));
            success.setHeaderText(I18n.get("backup.import.successHeader"));
            success.setContentText(backupImportSummary(result));
            success.showAndWait();
            
            reloadStoresAfterBackupImport();
            // Restored connections, credentials and environments can bring other tab colors.
            refreshConnectionColorsInAllWindows();
            
            updateStatus(I18n.get("backup.import.successHeader") + ": " + filesImported + " " + I18n.get("backup.import.files"));
        });
        
        importTask.setOnFailed(e -> {
            Throwable ex = importTask.getException();
            logger.error("Backup import failed", ex);
            showBackupImportError(ex.getMessage());
        });
        
        // Update status and run task
        updateStatus(I18n.get("backup.import.importing"));
        
        Thread thread = new Thread(importTask);
        thread.setDaemon(true);
        thread.start();
    }

    /** The success text, with what happened to secrets from a backup under another master password. */
    private static String backupImportSummary(de.kortty.core.BackupManager.ImportResult result) {
        StringBuilder text = new StringBuilder(I18n.get("backup.import.successMessage", result.filesImported()));
        if (result.secretsReEncrypted() > 0) {
            text.append("\n\n").append(I18n.get("backup.import.foreignKey.reEncrypted", result.secretsReEncrypted()));
        }
        if (result.secretsCleared() > 0) {
            text.append("\n\n").append(I18n.get("backup.import.foreignKey.cleared", result.secretsCleared()));
        }
        if (!result.skippedSecretFiles().isEmpty()) {
            text.append("\n\n").append(I18n.get("backup.import.foreignKey.skipped",
                String.join(", ", result.skippedSecretFiles())));
        }
        return text.toString();
    }

    /**
     * Asks for the master password a backup was made with (masked). Called by the import's worker
     * thread, which waits for the answer; the dialog itself runs on the FX thread. Returns null
     * when the user cancels.
     */
    private char[] askBackupMasterPassword(int attempt, java.util.List<String> secretFiles) {
        if (Platform.isFxApplicationThread()) {
            return showBackupMasterPasswordDialog(attempt, secretFiles);
        }
        java.util.concurrent.CompletableFuture<char[]> answer = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(showBackupMasterPasswordDialog(attempt, secretFiles));
            } catch (Throwable t) {
                answer.completeExceptionally(t);
            }
        });
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            logger.warn("Could not ask for the backup's master password", e.getCause());
            return null;
        }
    }

    private char[] showBackupMasterPasswordDialog(int attempt, java.util.List<String> secretFiles) {
        Dialog<char[]> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(I18n.get("backup.import.foreignKey.title"));
        dialog.setHeaderText(I18n.get("backup.import.foreignKey.header"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField field = new PasswordField();
        field.setPromptText(I18n.get("dialog.enterPassword"));
        Label explanation = new Label(I18n.get("backup.import.foreignKey.content", String.join(", ", secretFiles)));
        explanation.setWrapText(true);
        explanation.setMaxWidth(460);
        VBox content = new VBox(10, explanation);
        if (attempt > 1) {
            Label wrong = new Label(I18n.get("backup.import.foreignKey.wrong"));
            wrong.setStyle("-fx-font-weight: bold;");
            wrong.setWrapText(true);
            content.getChildren().add(wrong);
        }
        content.getChildren().add(field);
        content.setPadding(new javafx.geometry.Insets(20));
        dialog.getDialogPane().setContent(content);
        Platform.runLater(field::requestFocus);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? field.getText().toCharArray() : null);
        char[] result = dialog.showAndWait().orElse(null);
        field.clear();
        return result;
    }

    private void showBackupImportError(String detail) {
        Alert error = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(error);
        error.setTitle(I18n.get("error.title"));
        error.setHeaderText(I18n.get("backup.import.failed"));
        error.setContentText(I18n.get("backup.import.failedMessage") + "\n" + detail);
        error.showAndWait();
    }

    /**
     * Reloads every store a backup restores, each on its own so one unreadable file cannot keep
     * the others stale. A store left with its old in-memory state would write it over the
     * restored file on its next save. Environments come first: restored credentials and
     * connections can refer to them.
     */
    private void reloadStoresAfterBackupImport() {
        reloadAfterBackupImport("environments", () -> {
            if (app.getEnvironmentManager() != null) {
                app.getEnvironmentManager().load();
            }
        });
        reloadAfterBackupImport("connections", () ->
            app.getConfigManager().load(app.getMasterPasswordManager().getDerivedKey()));
        reloadAfterBackupImport("credentials", () -> app.getCredentialManager().load());
        reloadAfterBackupImport("SSH keys", () -> {
            if (app.getSSHKeyManager() != null) {
                app.getSSHKeyManager().load();
            }
        });
        reloadAfterBackupImport("GPG keys", () -> app.getGpgKeyManager().load());
        reloadAfterBackupImport("global settings", () -> app.getGlobalSettingsManager().load());
        // The highlighting service compiled the rule sets of the settings object just replaced: without
        // this, the menus and open panes would keep the old sets and default until the next save.
        reloadAfterBackupImport("keyword highlighting", () -> {
            TerminalHighlightService highlightService = app.getTerminalHighlightService();
            if (highlightService != null && !highlightService.isClosed()) {
                highlightService.reload(app.getGlobalSettingsManager().getSettings());
            }
        });
        reloadAfterBackupImport("themes", () -> {
            if (app.getThemeManager() != null) {
                app.getThemeManager().load();
            }
        });
        reloadAfterBackupImport("snippet variables", () -> {
            if (app.getSnippetVariableManager() != null) {
                app.getSnippetVariableManager().load();
            }
        });
        // The restored snippets.xml and snippet analyses replace what is in memory; without the
        // reload the stale snippet list and the store's cache would overwrite them on the next save.
        reloadAfterBackupImport("snippets", () -> {
            de.kortty.core.SnippetAnalysisStore analysisStore = app.getSnippetAnalysisStore();
            if (analysisStore != null) {
                analysisStore.invalidateAll();
            }
            app.getSnippetManager().load();
        });
        reloadAfterBackupImport("AI chats", () -> {
            if (app.getAiChatManager() != null) {
                app.getAiChatManager().load();
            }
        });
        reloadAfterBackupImport("swarm chats", () -> {
            if (app.getSwarmChatManager() != null) {
                app.getSwarmChatManager().load();
            }
        });
    }

    @FunctionalInterface
    private interface BackupReloadStep {
        void run() throws Exception;
    }

    private void reloadAfterBackupImport(String store, BackupReloadStep step) {
        try {
            step.run();
        } catch (Exception ex) {
            logger.error("Failed to reload {} after backup import", store, ex);
        }
    }
    
    /**
     * Sets up context menu for a terminal tab to assign/change groups.
     */
    private void setupTabContextMenu(TerminalTab terminalTab) {
        ContextMenu contextMenu = new ContextMenu();
        
        terminalTab.setOnReconnectRequested(() -> {
            updateDashboard();
            updateStatus(I18n.get("status.reconnecting", terminalTab.getConnection().getDisplayName()));
        });
        
        MenuItem renameItem = new MenuItem(I18n.get("tab.contextMenu.rename"));
        renameItem.setOnAction(e -> promptRenameTab(terminalTab));
        contextMenu.getItems().add(renameItem);

        MenuItem duplicateItem = new MenuItem(I18n.get("tab.contextMenu.duplicate"));
        duplicateItem.setOnAction(e -> duplicateTab(terminalTab));
        contextMenu.getItems().add(duplicateItem);
        
        MenuItem reconnectItem = new MenuItem(I18n.get("dashboard.reconnect"));
        reconnectItem.setOnAction(e -> {
            terminalTab.triggerReconnect();
        });
        contextMenu.getItems().add(reconnectItem);

        // SFTP on the focused pane's own session, in its folder; only while that pane runs SSH.
        MenuItem sftpHereItem = new MenuItem(I18n.get("tab.contextMenu.sftpHere"));
        sftpHereItem.setOnAction(e -> openSftpHere(terminalTab, null));
        contextMenu.getItems().add(sftpHereItem);

        if (de.kortty.policy.PolicyManager.effective().sessionJournalAllowed()) {
            Menu journalMenu = new Menu(I18n.get("tab.contextMenu.journal"));
            MenuItem journalToggleItem = new MenuItem(I18n.get(terminalTab.isJournalActive()
                ? "tab.contextMenu.journal.stop"
                : "tab.contextMenu.journal.start"));
            journalToggleItem.setOnAction(e -> {
                terminalTab.toggleJournalFromMenuOrShortcut();
                updateAllTabContextMenus();
            });
            MenuItem journalShotItem = new MenuItem(I18n.get("tab.contextMenu.journal.screenshot"));
            journalShotItem.setDisable(!terminalTab.isJournalActive());
            journalShotItem.setOnAction(e -> terminalTab.takeJournalScreenshot(null));
            MenuItem journalNoteItem = new MenuItem(I18n.get("tab.contextMenu.journal.note"));
            journalNoteItem.setDisable(!terminalTab.isJournalActive());
            journalNoteItem.setOnAction(e -> terminalTab.addJournalNote());
            journalMenu.getItems().addAll(journalToggleItem, journalShotItem, journalNoteItem);
            contextMenu.getItems().add(journalMenu);
        }

        // Multi-exec: every pane of this tab joins, or leaves when all of them take part. The members
        // decide, never the check mark, which JavaFX has already flipped when the action runs.
        CheckMenuItem multiExecItem = new CheckMenuItem(I18n.get(MultiExecMarkers.TAB_TOGGLE_KEY));
        multiExecItem.setOnAction(e -> {
            TerminalView view = terminalTab.getTerminalView();
            if (view != null) {
                MultiExecCoordinator.shared().toggleAll(view.getOrderedWidgets());
            }
        });
        contextMenu.getItems().add(multiExecItem);

        // Activity and silence monitoring: runtime switches of this tab, off until switched on. The
        // tab's state decides, never the check mark, which JavaFX has already flipped when the
        // action runs.
        contextMenu.getItems().add(new SeparatorMenuItem());
        CheckMenuItem monitorActivityItem = new CheckMenuItem(I18n.get(TerminalActivityWatcher.MONITOR_ACTIVITY_KEY));
        monitorActivityItem.setSelected(terminalTab.isMonitoringActivity());
        monitorActivityItem.setOnAction(e -> terminalTab.setMonitoringActivity(!terminalTab.isMonitoringActivity()));
        CheckMenuItem monitorSilenceItem = new CheckMenuItem(I18n.get(TerminalActivityWatcher.MONITOR_SILENCE_KEY));
        monitorSilenceItem.setSelected(terminalTab.isMonitoringSilence());
        monitorSilenceItem.setOnAction(e -> terminalTab.setMonitoringSilence(!terminalTab.isMonitoringSilence()));
        contextMenu.getItems().addAll(monitorActivityItem, monitorSilenceItem);

        contextMenu.getItems().add(new SeparatorMenuItem());
        MenuItem closeOthersItem = new MenuItem(I18n.get("tab.contextMenu.closeOthers"));
        closeOthersItem.setOnAction(e -> closeOtherTabs(terminalTab));
        MenuItem closeToRightItem = new MenuItem(I18n.get("tab.contextMenu.closeToRight"));
        closeToRightItem.setOnAction(e -> closeTabsToTheRight(terminalTab));
        MenuItem reopenClosedItem = new MenuItem(I18n.get("tab.contextMenu.reopenClosed"));
        reopenClosedItem.setOnAction(e -> reopenClosedTab());
        contextMenu.getItems().addAll(closeOthersItem, closeToRightItem, reopenClosedItem);
        // The menu is built once per tab, so whether there is anything to close (or to reopen) is
        // decided as it opens.
        contextMenu.setOnShowing(e -> {
            syncTabCloseItems(terminalTab, closeOthersItem, closeToRightItem);
            TerminalView sftpView = terminalTab.getTerminalView();
            sftpHereItem.setDisable(sftpView == null
                || sftpView.borrowedSessionSupplier(sftpView.focusedPane()) == null);
            reopenClosedItem.setDisable(closedTabHistory.isEmpty());
            TerminalView multiExecView = terminalTab.getTerminalView();
            multiExecItem.setSelected(multiExecView != null
                && MultiExecCoordinator.shared().includesAll(multiExecView.getOrderedWidgets()));
            // Denied by the organization's policy: panes that take part can still leave, none can join.
            multiExecItem.setDisable(!MultiExecCoordinator.shared().joinAllowed()
                && (multiExecView == null || MultiExecCoordinator.shared().countIn(multiExecView.getOrderedWidgets()) == 0));
            monitorActivityItem.setSelected(terminalTab.isMonitoringActivity());
            monitorSilenceItem.setSelected(terminalTab.isMonitoringSilence());
        });

        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
            Menu terminalEffectMenu = createTerminalEffectMenu(terminalTab);
            terminalEffectMenu.setOnShowing(event -> rebuildTerminalEffectMenu(terminalEffectMenu, terminalTab));
            contextMenu.getItems().add(terminalEffectMenu);
        }

        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // Get all available groups
        List<String> groups = getAllGroups();
        String currentGroup = terminalTab.getGroup();
        
        // Menu item to remove from group
        MenuItem removeGroupItem = new MenuItem(I18n.get("tab.contextMenu.noGroup"));
        removeGroupItem.setOnAction(e -> {
            terminalTab.setGroup(null);
            organizeTabsByGroup();
            updateAllTabContextMenus(); // Update all context menus to reflect changes
            updateDashboard(); // Refresh dashboard to show group changes
        });
        if (currentGroup == null || currentGroup.trim().isEmpty()) {
            removeGroupItem.setDisable(true);
        }
        contextMenu.getItems().add(removeGroupItem);
        
        // Separator
        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // Menu items for each group
        if (!groups.isEmpty()) {
            for (String group : groups) {
                MenuItem groupItem = new MenuItem(group);
                groupItem.setOnAction(e -> {
                    terminalTab.setGroup(group);
                    organizeTabsByGroup();
                    updateAllTabContextMenus(); // Update all context menus to reflect changes
                    updateDashboard(); // Refresh dashboard to show group changes
                });
                if (group.equals(currentGroup)) {
                    groupItem.setDisable(true);
                }
                contextMenu.getItems().add(groupItem);
            }
            contextMenu.getItems().add(new SeparatorMenuItem());
        }
        
        // Menu item to create new group
        MenuItem newGroupItem = new MenuItem(I18n.get("group.new"));
        newGroupItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            DialogThemeHelper.applyTheme(dialog);
            dialog.setTitle(I18n.get("group.new"));
            dialog.setHeaderText(I18n.get("group.enterName"));
            dialog.setContentText(I18n.get("group.name") + ":");
            dialog.getEditor().setPromptText(I18n.get("group.nameExample"));
            
            dialog.showAndWait().ifPresent(groupName -> {
                if (groupName != null && !groupName.trim().isEmpty()) {
                    String trimmedName = groupName.trim();
                    terminalTab.setGroup(trimmedName);
                    organizeTabsByGroup();
                    updateAllTabContextMenus(); // Update all context menus to reflect new group
                    updateDashboard(); // Refresh dashboard to show new group
                }
            });
        });
        contextMenu.getItems().add(newGroupItem);
        
        // Menu item to rename current group (if tab has a group)
        if (currentGroup != null && !currentGroup.trim().isEmpty()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
            MenuItem renameGroupItem = new MenuItem(I18n.get("group.rename"));
            renameGroupItem.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(currentGroup);
                DialogThemeHelper.applyTheme(dialog);
                dialog.setTitle(I18n.get("group.rename"));
                dialog.setHeaderText(I18n.get("group.enterNewName"));
                dialog.setContentText(I18n.get("group.newName") + ":");
                
                dialog.showAndWait().ifPresent(newGroupName -> {
                    if (newGroupName != null && !newGroupName.trim().isEmpty()) {
                        String trimmedName = newGroupName.trim();
                        if (!trimmedName.equals(currentGroup)) {
                            renameGroupForAllTabs(currentGroup, trimmedName);
                            organizeTabsByGroup();
                            updateAllTabContextMenus(); // Update all context menus
                            updateDashboard(); // Refresh dashboard to show renamed group
                        }
                    }
                });
            });
            contextMenu.getItems().add(renameGroupItem);
        }
        
        terminalTab.setContextMenu(contextMenu);
    }
    
    /**
     * Asks for a new name for {@code terminalTab}, prefilled with the name it shows now. The name
     * replaces the connection's name (or the title the shell set) in the tab title only: the agent
     * badge, the group prefix and the connection-status suffix stay. An empty name, or the name the
     * tab shows on its own, makes the tab follow the shell's title and the connection's name again.
     */
    private void promptRenameTab(TerminalTab terminalTab) {
        String automaticTitle = terminalTab.getAutomaticTitle();
        String customTitle = terminalTab.getCustomTitle();
        TextInputDialog dialog = new TextInputDialog(customTitle != null ? customTitle : automaticTitle);
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(I18n.get("dialog.renameTab.title"));
        dialog.setHeaderText(terminalTab.getShellTitle() != null
            ? I18n.get("dialog.renameTab.headerShellTitle", automaticTitle, terminalTab.getConnectionTitle())
            : I18n.get("dialog.renameTab.header", automaticTitle));
        dialog.setContentText(I18n.get("dialog.renameTab.prompt") + ":");
        dialog.getEditor().setPromptText(automaticTitle);
        dialog.showAndWait().ifPresent(input -> {
            terminalTab.setCustomTitle(TerminalTab.customTitleFromInput(input, automaticTitle));
            markSessionDirty();
        });
    }

    /**
     * Gets all unique group names from open tabs (not from connections).
     */
    private List<String> getAllGroups() {
        List<String> groups = new ArrayList<>();
        // Get groups from open tabs only (tab groups, not connection groups)
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String group = terminalTab.getGroup();
                if (group != null && !group.trim().isEmpty() && !groups.contains(group)) {
                    groups.add(group);
                }
            }
        }
        groups.sort(String::compareToIgnoreCase);
        return groups;
    }
    
    /**
     * Renames a group for all tabs that have this group.
     */
    private void renameGroupForAllTabs(String oldGroupName, String newGroupName) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String group = terminalTab.getGroup();
                if (oldGroupName.equals(group)) {
                    terminalTab.setGroup(newGroupName);
                }
            }
        }
    }
    
    /**
     * Updates context menus for all tabs to reflect current group state.
     */
    private void updateAllTabContextMenus() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                setupTabContextMenu(terminalTab);
            }
        }
    }
    
    /**
     * Inserts a tab in the correct position based on group ordering.
     * Tabs are organized: no group first, then grouped tabs alphabetically by group name.
     */
    private void insertTabInGroupOrder(TerminalTab newTab) {
        String newTabGroup = newTab.getGroup();
        if (newTabGroup == null || newTabGroup.trim().isEmpty()) {
            newTabGroup = null;
        }
        
        // Find insertion point
        int insertIndex = tabPane.getTabs().size();
        for (int i = 0; i < tabPane.getTabs().size(); i++) {
            Tab tab = tabPane.getTabs().get(i);
            if (tab instanceof TerminalTab terminalTab) {
                String tabGroup = terminalTab.getGroup();
                if (tabGroup == null || tabGroup.trim().isEmpty()) {
                    tabGroup = null;
                }
                
                // Compare groups
                if (newTabGroup == null) {
                    // New tab has no group - insert before first grouped tab
                    if (tabGroup != null) {
                        insertIndex = i;
                        break;
                    }
                } else {
                    // New tab has group - insert after last tab with same group or before first tab with larger group
                    if (tabGroup != null && tabGroup.compareToIgnoreCase(newTabGroup) > 0) {
                        insertIndex = i;
                        break;
                    } else if (tabGroup != null && tabGroup.equals(newTabGroup)) {
                        // Continue until we find the end of this group
                        insertIndex = i + 1;
                    }
                }
            }
        }
        
        tabPane.getTabs().add(insertIndex, newTab);
    }
    
    /**
     * Reorganizes all tabs by group order.
     * Tabs without group come first, then grouped tabs sorted alphabetically by group name.
     */
    private void organizeTabsByGroup() {
        reorganizeTabs(this::sortTabsByGroup);
    }

    /**
     * Runs a bulk change of the tab list that is no use of the tabs it passes over: tabs removed and
     * re-added while regrouping, several tabs closed at once, a tab dropped into place. While it runs
     * the selection changes do not count in the tabs' most-recently-used order and removed tabs stay
     * in it; afterwards the order forgets the tabs that are gone and counts the tab the window shows
     * as used. A change nested in another counts once, at the end of the outer one.
     */
    private void reorganizeTabs(Runnable change) {
        boolean outer = !reorganizingTabs;
        reorganizingTabs = true;
        try {
            change.run();
        } finally {
            if (outer) {
                reorganizingTabs = false;
            }
        }
        if (outer) {
            tabMru.retainOnly(tabPane.getTabs());
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected != null) {
                tabMru.touch(selected);
            }
        }
    }

    /** The tab order of {@link #organizeTabsByGroup}, without the guard of {@link #reorganizeTabs}. */
    private void sortTabsByGroup() {
        sortTabsByGroup(new ArrayList<>(tabPane.getTabs()));
    }

    /**
     * Puts the window's tabs into group order, starting from {@code order}, which holds the window's
     * tabs in the order to keep inside each group (and for the tabs that are not terminals).
     */
    private void sortTabsByGroup(List<Tab> order) {
        // Get all terminal tabs.
        List<TerminalTab> terminalTabs = new ArrayList<>();
        List<Tab> preservedTabs = new ArrayList<>();
        for (Tab tab : order) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTabs.add(terminalTab);
            } else {
                preservedTabs.add(tab);
            }
        }
        
        // Sort tabs: no group first, then by group name alphabetically
        terminalTabs.sort((t1, t2) -> {
            String g1 = t1.getGroup();
            String g2 = t2.getGroup();
            
            if (g1 == null || g1.trim().isEmpty()) {
                g1 = null;
            }
            if (g2 == null || g2.trim().isEmpty()) {
                g2 = null;
            }
            
            if (g1 == null && g2 == null) {
                return 0; // Keep original order for tabs without group
            }
            if (g1 == null) {
                return -1; // No group comes first
            }
            if (g2 == null) {
                return 1; // No group comes first
            }
            
            return g1.compareToIgnoreCase(g2);
        });
        
        // Clear all tabs
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        tabPane.getTabs().clear();
        
        // Re-add sorted tabs
        for (TerminalTab tab : terminalTabs) {
            tabPane.getTabs().add(tab);
            setupTabContextMenu(tab); // Re-setup context menu
        }

        tabPane.getTabs().addAll(preservedTabs);
        
        // Restore selection
        if (selectedTab != null) {
            tabPane.getSelectionModel().select(selectedTab);
        }
    }

}
