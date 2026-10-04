package com.sithtermfx.ui.split;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalOutputStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.TerminalActionProvider;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.TerminalWidgetListener;
import com.sithtermfx.ui.settings.SettingsProvider;
import javafx.application.Platform;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import de.kortty.core.KorttyClipboard;
import de.kortty.ui.I18n;
import de.kortty.ui.KorttyTermWidget;
import de.kortty.ui.MirroredInputWriter;
import de.kortty.ui.PaneNavigator;
import de.kortty.ui.TerminalLinkContextMenu;
import de.kortty.ui.TerminalNavigationKeys;
import de.kortty.ui.TerminalPaneActions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javafx.scene.layout.HBox;

/**
 * Pane that supports splitting terminal widgets horizontally or vertically.
 * Supports nested splits (e.g. 2x2 grid). Each split has its own session (TtyConnector).
 * Extends StackPane so children automatically fill the available space when the window is resized.
 */
public class TerminalSplitPane extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(TerminalSplitPane.class);
    private static final String TRANSPARENT_BACKGROUND_STYLE = "-fx-background-color: transparent;";

    private static final DataFormat DRAG_TERMINAL_FORMAT = new DataFormat("application/x-sithtermfx-terminal-widget");

    /** Drop placement when moving a split terminal. */
    public enum Placement {
        ABOVE, BELOW, LEFT_OF, RIGHT_OF
    }

    /**
     * The layers korTTY draws over one pane, from bottom to top, each one a {@link Pane} that
     * {@link #paneOverlay} adds to the pane's wrapper on first use. Below every layer, at view
     * order 0, are the wrapper's own children: the terminal with its timestamp gutter and agent
     * panel, the effect overlays and the close button. A layer is unmanaged, so the wrapper never
     * lays it out and the terminal never resizes because of it, but it always has the wrapper's
     * size; it is mouse-transparent, so every click, hover and drag still reaches the terminal and
     * the wrapper's own handlers. Its {@link Node#getViewOrder() view order} keeps it above the
     * wrapper's children and the layers declared before it, whatever was added to the wrapper
     * later. It goes away with the pane.
     */
    public enum PaneOverlayLayer {
        /** The underline under a hovered terminal link, and quick select's boxes and labels. */
        LINKS,
        /**
         * The ring around the pane the keyboard is in (with two or more panes), a zoomed pane's badge,
         * and the outline and badge of a pane whose typing goes to other panes (multi-exec or
         * broadcast mode).
         */
        DECORATION,
        /** The drop zones shown while a pane is dragged onto this one to move it. */
        DROP_ZONES;

        /** Below 0, and lower for a later layer, because JavaFX draws a lower view order on top. */
        public double viewOrder() {
            return -1.0 - ordinal();
        }
    }

    /** Style class of every pane's wrapper, the node {@code :focus-within} is checked on. */
    static final String PANE_CELL_STYLE_CLASS = "kortty-pane-cell";

    /** Style class of the focus ring in a pane's {@link PaneOverlayLayer#DECORATION} layer. */
    static final String FOCUS_RING_STYLE_CLASS = "kortty-pane-focus-ring";

    /**
     * Set on the wrapper of {@link #getFocusedWidget()}: the pane the menu commands act on keeps a
     * dimmer ring while the keyboard is elsewhere, for example in another window.
     */
    static final PseudoClass LAST_FOCUSED = PseudoClass.getPseudoClass("last-focused");

    /** A pane's accessible name with two or more panes: "Pane {0} of {1}". */
    static final String PANE_ACCESSIBLE_NAME_KEY = "terminal.pane.accessibleName";

    /** The zoom badge while no hidden pane gets the keys typed: "Zoomed · hidden panes: {0}". */
    static final String ZOOMED_BADGE_KEY = "terminal.pane.zoomedBadge";

    /**
     * The zoom badge while broadcast mode sends the keys typed in the zoomed pane on to hidden panes:
     * "Zoomed · hidden panes: {0}, receiving your input: {1}".
     */
    static final String ZOOMED_MIRROR_BADGE_KEY = "terminal.pane.zoomedMirrorBadge";

    /**
     * The note below <b>Broadcast Mode</b> in the context menu while the mirror guard holds panes:
     * "Panes left out right now: {0} of {1}".
     */
    static final String BROADCAST_HELD_KEY = "terminal.contextMenu.broadcastHeld";

    /** Style class of the badge in a zoomed pane's {@link PaneOverlayLayer#DECORATION} layer. */
    static final String ZOOM_BADGE_STYLE_CLASS = "kortty-pane-zoom-badge";

    /** Style class of the badge's icon, four corners pointing out. */
    static final String ZOOM_BADGE_ICON_STYLE_CLASS = "kortty-pane-zoom-badge-icon";

    /** Style class of the empty region that keeps a zoomed pane's place in its split control. */
    static final String ZOOM_PLACEHOLDER_STYLE_CLASS = "kortty-pane-zoom-placeholder";

    /** Set on the zoom badge while hidden panes receive the keys typed in the zoomed pane. */
    static final PseudoClass MIRRORING = PseudoClass.getPseudoClass("mirroring");

    /** The badge of a pane that takes part in multi-exec: "Multi-exec"; the dashboard's mark reads it too. */
    public static final String MULTI_EXEC_BADGE_KEY = "terminal.pane.multiExecBadge";

    /** The badge of each pane of a tab in broadcast mode: "Broadcast". */
    static final String BROADCAST_BADGE_KEY = "terminal.pane.broadcastBadge";

    /** Style class of the badge of a pane whose typing goes to other panes, in its DECORATION layer. */
    static final String MIRROR_BADGE_STYLE_CLASS = "kortty-pane-mirror-badge";

    /** Style class of that badge's icon. */
    static final String MIRROR_BADGE_ICON_STYLE_CLASS = "kortty-pane-mirror-badge-icon";

    /** Style class of the amber outline of a pane whose typing goes to other panes. */
    static final String MIRROR_OUTLINE_STYLE_CLASS = "kortty-pane-mirror-outline";

    /**
     * A block that forks into three lines to its right, 10 by 10: what you type in one pane goes on to
     * others. The pane badge, the tab marker, the dashboard and the status bar of multi-exec show it,
     * so none of them is text and colour alone.
     */
    public static final String MIRROR_ICON_PATH =
        "M0 3.5H3V6.5H0Z M3 4.4H5V5.6H3Z M5 0.8H6.2V9.2H5Z M6.2 0.8H10V2H6.2Z M6.2 4.4H10V5.6H6.2Z M6.2 8H10V9.2H6.2Z";

    /** The gap between the mirror badge and a zoomed pane's badge right of it. */
    private static final double MIRROR_BADGE_GAP = 6;

    /** Four corners pointing out, 10 by 10: the badge's icon, so the badge is not text and colour alone. */
    private static final String ZOOM_BADGE_ICON_PATH =
        "M0 0H4V1.5H1.5V4H0Z M6 0H10V4H8.5V1.5H6Z M0 6H1.5V8.5H4V10H0Z M8.5 6H10V10H6V8.5H8.5Z";

    /** The badge's distance from the right edge: it stays left of the pane's 18 px × and its 4 px margin. */
    private static final double ZOOM_BADGE_RIGHT_INSET = 28;
    private static final double ZOOM_BADGE_TOP_INSET = 4;

    /** Reads and writes a JavaFX split control's dividers for {@link PaneZoom}. */
    private static final PaneZoom.Dividers<SplitPane> SPLIT_PANE_DIVIDERS = new PaneZoom.Dividers<>() {
        @Override
        public double @NotNull [] positions(@NotNull SplitPane split) {
            return split.getDividerPositions();
        }

        @Override
        public void setPositions(@NotNull SplitPane split, double @NotNull [] positions) {
            split.setDividerPositions(positions);
        }
    };

    private static final class ExtractResult {
        final SplitCell extracted;
        final SplitCell replacement;

        ExtractResult(SplitCell extracted, SplitCell replacement) {
            this.extracted = extracted;
            this.replacement = replacement;
        }
    }

    // Produces a fresh SettingsProvider per widget so each pane can carry its own appearance override.
    private final Supplier<SettingsProvider> settingsProviderFactory;
    private final SplitConnectorFactory connectorFactory;
    private final Consumer<SithTermFxWidget> widgetConfigurator;
    private final Function<SithTermFxWidget, Region> leftPanelFactory;
    private final Function<SithTermFxWidget, Region> bottomPanelFactory;
    private final BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator;

    private SplitCell rootCell;
    private SithTermFxWidget focusedWidget;
    private boolean broadcastMode = false;
    // The zoomed pane and how to show the others again; both null while no pane is zoomed.
    private @Nullable PaneZoom<Node, SplitPane> zoom;
    private @Nullable SithTermFxWidget zoomedWidget;
    // The badge in the zoomed pane's DECORATION layer, reused from one zoom to the next.
    private @Nullable Label zoomBadge;
    // JavaFX creates a new themed SplitPane for every split level. Remember see-through mode so both
    // existing and future nested controls stay transparent instead of restoring the opaque theme.
    private boolean backgroundTransparent = false;

    // Optional left-side panels (e.g. timestamp gutters) per widget
    private final Map<SithTermFxWidget, Region> widgetLeftPanels = new HashMap<>();
    private final Map<SithTermFxWidget, Region> widgetBottomPanels = new HashMap<>();
    // Per-widget VBox that hosts the bottom panel; kept so the panel can be detached/re-attached at
    // runtime when the AI-agent panel is docked to the side instead of the bottom.
    private final Map<SithTermFxWidget, VBox> widgetBottomHosts = new HashMap<>();
    private boolean bottomPanelsDetached = false;
    private final Map<SithTermFxWidget, Button> widgetCloseButtons = new HashMap<>();
    // Per-widget StackPane wrapper (the leaf cell's root node) used as the mount point for a per-pane
    // effect overlay. Reused by reference across split/close/move, so an overlay follows its pane.
    private final Map<SithTermFxWidget, StackPane> widgetOverlayHosts = new HashMap<>();

    // Track the currently showing context menu so we can hide it properly
    private ContextMenu activeContextMenu;

    // Optional supplier of extra menu items to add to the context menu (e.g. timestamp toggle)
    private Function<SithTermFxWidget, List<MenuItem>> extraMenuItemsFactory;

    // Optional supplier of the items below Broadcast Mode in the context menu's Extras submenu
    // (the host app's multi-exec toggle).
    private Function<SithTermFxWidget, List<MenuItem>> mirrorMenuItemsFactory;

    // Optional hook invoked when a widget is closed (split close or close-all) so owners can release
    // per-widget resources such as terminal-agent runs/panels.
    private Consumer<SithTermFxWidget> onWidgetClosed;

    // Optional hook invoked after a new widget is created by splitting an existing pane, carrying the
    // originating SplitRequest (whose parentWidget is the source pane) so owners can inherit state.
    private BiConsumer<SithTermFxWidget, SplitRequest> onWidgetSplitCreated;

    // Optional hook invoked when the LAST remaining pane's session ends (e.g. Ctrl+D / exit), so the
    // owner can close the whole tab. Splits with more than one pane just auto-close the single pane.
    private Runnable onLastWidgetSessionEnded;

    // Optional hook invoked when broadcast mode actually changes state (host-app telemetry).
    private Consumer<Boolean> onBroadcastModeChanged;

    // Strips the host app's connector decorators so key routing sees the real connector type.
    private UnaryOperator<TtyConnector> connectorUnwrapper = UnaryOperator.identity();

    // Mirror guard: decides whether a pane may receive broadcast input; the host app skips panes
    // that must not get keys from other panes, such as one that is sending a paced paste or one an
    // AI agent drives.
    private Predicate<SithTermFxWidget> mirrorTargetGuard = widget -> true;

    // Mirror input rule: for one key typed in a pane, which of the panes the guard accepts may get
    // it; the host app sends a key typed at a password prompt only to the panes at one too.
    private Function<SithTermFxWidget, Predicate<SithTermFxWidget>> mirrorInputRule = source -> widget -> true;

    // Input mirror: mirrors the keys typed in its member panes into its other members, in this tab
    // and in other tabs and windows (multi-exec); null while there is none.
    private @Nullable InputMirror inputMirror;

    // The pane the last key was pressed in, and what the character of that key's KEY_TYPED may still
    // mirror (BroadcastTargets.TypedMirror): set by routeKeyPressed, refined once the terminal saw
    // the key, used up by the KEY_TYPED. One key at a time on the FX thread.
    private @Nullable SithTermFxWidget typedMirrorPane;
    private BroadcastTargets.TypedMirror typedMirror = BroadcastTargets.TypedMirror.PRINTABLE_ONLY;

    /** If set, called when user chooses "Reset" font size in context menu (e.g. to reset to connection/global default). */
    private Runnable resetZoomCallback;

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory) {
        this(settingsProviderFactory, connectorFactory, w -> {}, null);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator) {
        this(settingsProviderFactory, connectorFactory, widgetConfigurator, null);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory) {
        this(settingsProviderFactory, connectorFactory, widgetConfigurator, leftPanelFactory, (widget, connector) -> connector);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory,
                             @NotNull BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator) {
        this(settingsProviderFactory, connectorFactory, widgetConfigurator, leftPanelFactory, null, connectorDecorator);
    }

    public TerminalSplitPane(@NotNull Supplier<SettingsProvider> settingsProviderFactory,
                             @NotNull SplitConnectorFactory connectorFactory,
                             @NotNull Consumer<SithTermFxWidget> widgetConfigurator,
                             @Nullable Function<SithTermFxWidget, Region> leftPanelFactory,
                             @Nullable Function<SithTermFxWidget, Region> bottomPanelFactory,
                             @NotNull BiFunction<SithTermFxWidget, TtyConnector, TtyConnector> connectorDecorator) {
        this.settingsProviderFactory = settingsProviderFactory;
        this.connectorFactory = connectorFactory;
        this.widgetConfigurator = widgetConfigurator;
        this.leftPanelFactory = leftPanelFactory;
        this.bottomPanelFactory = bottomPanelFactory;
        this.connectorDecorator = connectorDecorator;
        this.rootCell = createInitialCell();
        getChildren().add(rootCell.getNode());
        refreshSplitCloseButtons();
        VBox.setVgrow(this, Priority.ALWAYS);
        // Only allow pane-move drag with Shift+Alt/Option. A pane move needs the other panes to drop
        // onto, so it shows a zoomed tab's panes again first.
        addEventFilter(MouseEvent.DRAG_DETECTED, event -> {
            if (rootCell == null || rootCell.countWidgets() <= 1) return;
            if (!(event.isShiftDown() && event.isAltDown())) {
                event.consume();
                return;
            }
            unzoom();
        });
        refreshDragAndDrop();
    }
    
    /**
     * Mirrors input to the panes that receive it ({@link #mirrorReceivers}): in broadcast mode the
     * tab's other panes, and for a member of the {@link InputMirror} its other members, also in other
     * tabs and windows. The writes are queued on {@link MirroredInputWriter}, so a pane whose
     * connection stalls never blocks the FX thread.
     */
    private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {
        if (!isMirroring(sourceWidget)) return;

        for (SithTermFxWidget widget : mirrorReceivers(sourceWidget)) {
            TtyConnector connector = widget.getTtyConnector();
            if (connector != null) {
                MirroredInputWriter.shared().write(connector, data);
            }
        }
    }
    
    /**
     * Mirrors input that each pane encodes for itself, e.g. an arrow key that one pane's
     * application wants as {@code ESC O A} and another's as {@code ESC [ A}. A pane for which
     * {@code bytesFor} returns {@code null} gets nothing. The bytes are encoded here on the FX
     * thread, from each pane's state at the moment of the key press, and then queued on
     * {@link MirroredInputWriter} in the same per-pane queue as the typed characters of
     * {@link #broadcastToOthers(SithTermFxWidget, String)}, so every pane receives keys and
     * characters in the order they were pressed.
     */
    private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget,
                                   @NotNull Function<SithTermFxWidget, byte[]> bytesFor) {
        if (!isMirroring(sourceWidget)) return;

        for (SithTermFxWidget widget : mirrorReceivers(sourceWidget)) {
            TtyConnector connector = widget.getTtyConnector();
            if (connector == null) {
                continue;
            }
            byte[] bytes = bytesFor.apply(widget);
            if (bytes == null) {
                continue;
            }
            MirroredInputWriter.shared().write(connector, bytes);
        }
    }

    /**
     * Whether the keys typed in {@code widget} go to other panes as well: while this tab's broadcast
     * mode is on, and while the pane is a member of the {@link InputMirror}.
     */
    private boolean isMirroring(@NotNull SithTermFxWidget widget) {
        return broadcastMode || isMirrorMember(widget);
    }

    /**
     * The panes that get a key typed in {@code source} now: {@link #mirrorTargetsOf its targets},
     * less those the source's mirror input rule ({@link #setMirrorInputRule}) keeps this key from.
     * The rule is asked once per key, and only when there is a pane to send to.
     */
    private @NotNull List<SithTermFxWidget> mirrorReceivers(@NotNull SithTermFxWidget source) {
        return BroadcastTargets.admit(mirrorTargetsOf(source), () -> {
            Predicate<SithTermFxWidget> admitted = mirrorInputRuleFor(source);
            return target -> admitsMirroredInput(admitted, target);
        });
    }

    /**
     * The panes the keys typed in {@code source} go to before any key rule ({@link BroadcastTargets}):
     * the tab's other panes while broadcast mode is on, then the input mirror's other members while
     * the source is one, each once, if it is connected and the guard of the split pane that holds it
     * accepts it ({@link #setMirrorTargetGuard}).
     */
    private @NotNull List<SithTermFxWidget> mirrorTargetsOf(@NotNull SithTermFxWidget source) {
        return BroadcastTargets.resolve(source, broadcastMode ? getAllWidgets() : List.of(),
            mirrorMembersBesides(source), TerminalSplitPane::isConnected, this::acceptedByOwner);
    }

    /** Whether {@code widget} is a member of the input mirror; a failing mirror says no. */
    private boolean isMirrorMember(@NotNull SithTermFxWidget widget) {
        InputMirror mirror = inputMirror;
        if (mirror == null) {
            return false;
        }
        try {
            return mirror.isMember(widget);
        } catch (RuntimeException e) {
            logger.debug("Input mirror failed, the pane's keys are not mirrored: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The input mirror's members besides {@code source} while the source is one, else none; a failing
     * mirror names none.
     */
    private @NotNull List<SithTermFxWidget> mirrorMembersBesides(@NotNull SithTermFxWidget source) {
        InputMirror mirror = inputMirror;
        if (mirror == null || !isMirrorMember(source)) {
            return List.of();
        }
        try {
            List<SithTermFxWidget> members = mirror.otherMembers(source);
            return members != null ? members : List.of();
        } catch (RuntimeException e) {
            logger.debug("Input mirror failed, the key goes to no other member: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The split pane that holds {@code widget}: this one for its own panes, else the one the input
     * mirror names for a pane of another tab or window, or {@code null} when neither knows the pane.
     */
    private @Nullable TerminalSplitPane ownerOf(@NotNull SithTermFxWidget widget) {
        if (holdsWidget(widget)) {
            return this;
        }
        InputMirror mirror = inputMirror;
        if (mirror == null) {
            return null;
        }
        try {
            return mirror.ownerOf(widget);
        } catch (RuntimeException e) {
            logger.debug("Input mirror failed, the pane's split pane is unknown: {}", e.getMessage());
            return null;
        }
    }

    /** Whether {@code widget} is one of this split pane's panes, by reference. */
    private boolean holdsWidget(@NotNull SithTermFxWidget widget) {
        for (SithTermFxWidget pane : getAllWidgets()) {
            if (pane == widget) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the mirror guard of the split pane that holds {@code widget} lets it receive mirrored
     * input, so a pane of another tab is held back by its own tab's guard, such as while it paces a
     * paste. A pane no split pane is known to hold gets nothing.
     */
    private boolean acceptedByOwner(@NotNull SithTermFxWidget widget) {
        TerminalSplitPane owner = ownerOf(widget);
        return owner != null && owner.acceptsMirroredInput(widget);
    }

    /** Whether the mirror guard lets {@code widget} receive broadcast input; a failing guard says no. */
    private boolean acceptsMirroredInput(@NotNull SithTermFxWidget widget) {
        try {
            return mirrorTargetGuard.test(widget);
        } catch (RuntimeException e) {
            logger.debug("Mirror guard failed, the pane gets no broadcast input: {}", e.getMessage());
            return false;
        }
    }

    /** The mirror input rule's answer for one key typed in {@code source}; a failing rule admits no pane. */
    private @NotNull Predicate<SithTermFxWidget> mirrorInputRuleFor(@NotNull SithTermFxWidget source) {
        try {
            Predicate<SithTermFxWidget> admitted = mirrorInputRule.apply(source);
            return admitted != null ? admitted : widget -> false;
        } catch (RuntimeException e) {
            logger.debug("Mirror input rule failed, the key goes to no other pane: {}", e.getMessage());
            return widget -> false;
        }
    }

    /** Whether {@code admitted} lets the key reach {@code target}; a failing answer says no. */
    private static boolean admitsMirroredInput(@NotNull Predicate<SithTermFxWidget> admitted,
                                               @NotNull SithTermFxWidget target) {
        try {
            return admitted.test(target);
        } catch (RuntimeException e) {
            logger.debug("Mirror input rule failed, the pane gets no broadcast input: {}", e.getMessage());
            return false;
        }
    }

    /**
     * How many panes of this tab broadcast mode leaves out now because the mirror guard holds them,
     * such as a pane an AI agent drives; disconnected panes are not counted.
     */
    public int countHeldMirrorTargets() {
        return BroadcastTargets.countHeld(getAllWidgets(), TerminalSplitPane::isConnected, this::acceptsMirroredInput);
    }

    /**
     * Whether the mirror guard holds back {@code widget}, one of this split pane's panes, now: it is
     * connected but gets no keys from other panes, as a pane does that paces a paste. False for a
     * pane of another split pane. Multi-exec counts its members this way for the status bar.
     */
    public boolean isHeldMirrorTarget(@NotNull SithTermFxWidget widget) {
        return holdsWidget(widget) && isConnected(widget) && !acceptsMirroredInput(widget);
    }

    private static boolean isConnected(@NotNull SithTermFxWidget widget) {
        TtyConnector connector = widget.getTtyConnector();
        return connector != null && connector.isConnected();
    }

    /** Broadcast bytes of the keys that are neither typed characters nor navigation keys. */
    private @Nullable String getControlSequence(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER: return "\r";
            case BACK_SPACE: return "\u007F";
            case ESCAPE: return "\u001B";
            default: return null;
        }
    }

    /**
     * Routes a navigation key (see {@link TerminalNavigationKeys#isNavigationKey}) pressed in a pane.
     *
     * <p>korTTY sends these keys itself instead of leaving them to the terminal canvas, so they also
     * reach the shell while the pane or its scroll bar has the focus, and broadcast mode can mirror
     * them. Each pane gets them encoded for its own state: with their modifiers, and in application
     * cursor mode ({@code ESC[?1h}, used by mc and vim) as {@code ESC O A} instead of {@code ESC [ A}.
     *
     * <ul>
     *   <li>Keys aimed at the find bar's text field, or any other control inside the pane, are left
     *       alone and never broadcast.</li>
     *   <li>SithTermFX's own scroll keys (Shift+Page Up/Down, Ctrl+Up/Down, Cmd+Up/Down on macOS)
     *       scroll the pane's scrollback while it shows the normal screen and are never broadcast.
     *       The alternate screen of vim, less or mc has no scrollback, so there they go to the
     *       application.</li>
     *   <li>korTTY's own key actions of the pane, Previous Prompt and Next Prompt, likewise act in the
     *       pane and are never broadcast while they can run, in every emulation shell integration
     *       reads, SCO ANSI included; an emulation that keeps the fixed key sequences sends
     *       SithTermFX's scroll keys to the application ({@link TerminalNavigationKeys#mayKeepKeyLocal}).</li>
     *   <li>Ctrl+Tab and Meta (Cmd) chords are shortcuts and are not sent.</li>
     * </ul>
     */
    private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        // Until this key is known to reach the pane's program, its KEY_TYPED mirrors no control character.
        typedMirrorPane = widget;
        typedMirror = BroadcastTargets.TypedMirror.PRINTABLE_ONLY;
        if (event.isConsumed()) {
            return;
        }
        if (!isTerminalKeyTarget(widget, event.getTarget())) {
            // The find bar handles its own keys; Enter there must not run a command in the other panes.
            return;
        }
        if (!TerminalNavigationKeys.isNavigationKey(event.getCode())) {
            String sequence = getControlSequence(event);
            if (sequence != null) {
                // Enter, Backspace and Esc are mirrored here; whatever their KEY_TYPED carries is not.
                typedMirror = BroadcastTargets.TypedMirror.NONE;
                if (isMirroring(widget)) {
                    broadcastToOthers(widget, sequence);
                }
            } else if (isMirroring(widget)) {
                // Copy and paste run in this pane only: the control character their KEY_TYPED still
                // carries on Windows and Linux must not reach the other panes.
                typedMirror = BroadcastTargets.TypedMirror.ofPress(runsPaneAction(widget, event));
            }
            return;
        }
        typedMirror = BroadcastTargets.TypedMirror.NONE;
        if (performsLocalScrollAction(widget, event)) {
            return;
        }
        byte[] bytes = encodeKeyFor(widget, event);
        if (bytes == null || !sendToPane(widget, bytes)) {
            return;
        }
        broadcastToOthers(widget, target -> encodeKeyFor(target, event));
        event.consume();
    }

    /**
     * Records what the terminal did with a key pressed in {@code widget}. Runs on the canvas after
     * SithTermFX's own key filter, which consumes a key it sent to the program (or ran an action on),
     * so the key's KEY_TYPED mirrors a control character only when the pane sent one itself.
     */
    private void noteTerminalHandledKey(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        if (typedMirrorPane == widget) {
            typedMirror = typedMirror.afterTerminal(event.isConsumed());
        }
    }

    /**
     * Whether the character of a KEY_TYPED event in {@code widget} goes to the panes that mirror it
     * ({@link BroadcastTargets.TypedMirror}); the decision for the key pressed before it is used up.
     */
    private boolean mirrorsTypedCharacter(@NotNull SithTermFxWidget widget, char character) {
        BroadcastTargets.TypedMirror decision = typedMirrorPane == widget
            ? typedMirror : BroadcastTargets.TypedMirror.PRINTABLE_ONLY;
        typedMirrorPane = null;
        typedMirror = BroadcastTargets.TypedMirror.PRINTABLE_ONLY;
        return decision.mirrors(character);
    }

    /**
     * Whether SithTermFX runs an action of the pane on this key instead of sending it, such as copy or
     * paste: the first action whose key combination matches decides, as in
     * {@code TerminalAction.processEvent}, and it runs when it is enabled.
     */
    private static boolean runsPaneAction(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        try {
            TerminalPanel panel = widget.getTerminalPanel();
            TerminalAction action = panel != null ? firstMatchingAction(panel, event) : null;
            return action != null && action.isEnabled(event);
        } catch (RuntimeException e) {
            logger.debug("Could not look up the pane action of a key: {}", e.getMessage());
            return false;
        }
    }

    /**
     * True when the key event is aimed at the terminal itself: its canvas, the panes around it, or its
     * scroll bar. The find bar's text field, buttons and check box are not, so editing the search
     * text never types into the shell.
     */
    private static boolean isTerminalKeyTarget(@NotNull SithTermFxWidget widget, @Nullable Object target) {
        if (target == null) {
            return false;
        }
        if (target == widget.getPane() || target instanceof ScrollBar) {
            return true;
        }
        TerminalPanel panel = widget.getTerminalPanel();
        return panel != null && (target == panel.getCanvas() || target == panel.getPane());
    }

    /**
     * Mirrors SithTermFX's key-action lookup ({@code TerminalAction.processEvent}): the first action
     * whose key combination matches decides. An enabled one (scrolling the scrollback) runs locally
     * and the key is neither sent nor mirrored ({@link BroadcastTargets#routeOf}). On the canvas
     * SithTermFX's own key filter runs it; for a key aimed at the pane or the scroll bar that filter
     * never runs, so it is performed here. In an emulation that keeps korTTY's fixed key sequences,
     * such as SCO ANSI, only korTTY's own actions (the prompt jumps) may keep a key local, and
     * SithTermFX's scrollback keys still go to the program there
     * ({@link TerminalNavigationKeys#mayKeepKeyLocal}).
     *
     * @return true when the key was used for a local action and must not reach the application
     */
    private static boolean performsLocalScrollAction(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        TerminalPanel panel = widget.getTerminalPanel();
        if (panel == null) {
            return false;
        }
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        boolean alternateScreen = buffer != null && buffer.isUsingAlternateBuffer();
        TerminalAction action = alternateScreen ? null : firstMatchingAction(panel, event);
        if (action != null && !TerminalNavigationKeys.mayKeepKeyLocal(widget.getEmulationType(),
            widget instanceof KorttyTermWidget kortty && kortty.isLeadingTerminalAction(action))) {
            return false;
        }
        BooleanSupplier paneAction = action != null ? () -> action.isEnabled(event) : null;
        if (BroadcastTargets.routeOf(alternateScreen, paneAction) != BroadcastTargets.KeyRoute.LOCAL_ACTION) {
            return false;
        }
        if (event.getTarget() == panel.getCanvas()) {
            return true;
        }
        if (action.actionPerformed(event)) {
            event.consume();
            return true;
        }
        return false;
    }

    private static @Nullable TerminalAction firstMatchingAction(@NotNull TerminalActionProvider first,
                                                                @NotNull KeyEvent event) {
        int depth = 0;
        for (TerminalActionProvider provider = first; provider != null && depth < 32;
             provider = provider.getNextProvider(), depth++) {
            List<TerminalAction> actions = provider.getActions();
            if (actions == null) {
                continue;
            }
            for (TerminalAction action : actions) {
                if (action.matches(event)) {
                    return action;
                }
            }
        }
        return null;
    }

    /**
     * The bytes a navigation key sends to {@code target}, encoded for that pane's own program: from
     * its emulation, its cursor-key mode and its connector, so an arrow reaches vim in application
     * cursor mode as {@code ESC O A} and a shell as {@code ESC [ A}. A pane of another tab or window,
     * which the {@link InputMirror} mirrors keys into, is encoded by the split pane that holds it,
     * because only that one knows the decorators around its connector.
     *
     * @return the bytes, or {@code null} when the key is not sent to the pane, also for a pane that
     *     neither this split pane nor the input mirror knows
     */
    public byte @Nullable [] encodeKeyFor(@NotNull SithTermFxWidget target, @NotNull KeyEvent event) {
        TerminalSplitPane owner = ownerOf(target);
        return owner != null ? owner.encodeOwnPaneKey(target, event) : null;
    }

    /** {@link #encodeKeyFor} for one of this split pane's own panes, with its connector unwrapper. */
    private byte @Nullable [] encodeOwnPaneKey(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {
        KeyCode code = event.getCode();
        if (!TerminalNavigationKeys.isKorttyEncoded(widget.getEmulationType())) {
            return TerminalNavigationKeys.legacySequence(code);
        }
        Terminal terminal = widget.getTerminal();
        if (terminal == null) {
            return null;
        }
        IntFunction<byte[]> base = vk -> terminal.getCodeForKey(vk, 0);
        if (TerminalNavigationKeys.prefersSs3Arrows(connectorUnwrapper.apply(widget.getTtyConnector()))) {
            base = TerminalNavigationKeys.ss3ArrowBase(base);
        }
        return TerminalNavigationKeys.encode(code, event.isShiftDown(), event.isControlDown(), event.isAltDown(),
            event.isMetaDown(), com.sithtermfx.core.util.Platform.isMacOS(), base);
    }

    /**
     * Sends bytes to a pane through its terminal starter (the panel's output stream), the executor
     * that typed characters use as well, so a key never overtakes the characters typed before it.
     *
     * @return false when the pane has no live connection; the key is then left to SithTermFX
     */
    private static boolean sendToPane(@NotNull SithTermFxWidget widget, byte @NotNull [] bytes) {
        TtyConnector connector = widget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            return false;
        }
        TerminalPanel panel = widget.getTerminalPanel();
        TerminalOutputStream starter = panel != null ? panel.getTerminalOutputStream() : null;
        if (starter != null) {
            starter.sendBytes(bytes, true);
            return true;
        }
        try {
            connector.write(bytes);
            return true;
        } catch (IOException e) {
            logger.debug("Failed to send key sequence: {}", e.getMessage());
            return false;
        }
    }

    private @NotNull SplitCell createInitialCell() {
        SithTermFxWidget widget = createWidget(null);
        setupWidget(widget);
        applyLeftPanel(widget);
        applyBottomPanel(widget);
        return new SplitCell(widget);
    }

    private @NotNull SithTermFxWidget createWidget(@Nullable SplitRequest request) {
        return createWidget(request, null);
    }

    /**
     * @param preparedConnector an already-connected connector to adopt; when it is non-null the
     *     {@link SplitConnectorFactory} is <strong>not consulted at all</strong>, which is what keeps
     *     a caller that prepared its own connector off the FX thread out of the factory's modal
     *     connect dialog
     */
    private @NotNull SithTermFxWidget createWidget(@Nullable SplitRequest request,
                                                   @Nullable TtyConnector preparedConnector) {
        // KorttyTermWidget routes terminal copy/paste through the policy-aware clipboard handler
        // (enterprise internal-clipboard mode).
        de.kortty.ui.KorttyTermWidget korttyWidget =
            new de.kortty.ui.KorttyTermWidget(80, 24, settingsProviderFactory.get());
        SithTermFxWidget widget = korttyWidget;
        // The hover underline of a link is drawn in the pane's own LINKS layer, created on first hover
        // (the pane's wrapper does not exist yet).
        korttyWidget.setLinkOverlay(() -> paneOverlay(widget, PaneOverlayLayer.LINKS));
        widgetConfigurator.accept(widget);
        TtyConnector connector = preparedConnector != null
            ? preparedConnector
            : connectorFactory.createConnectorForSplit(request);
        if (connector != null) {
            TtyConnector decoratedConnector = connectorDecorator.apply(widget, connector);
            widget.setTtyConnector(decoratedConnector != null ? decoratedConnector : connector);
            widget.start();
        }
        return widget;
    }

    private void setupWidget(@NotNull SithTermFxWidget widget) {
        setFocusedWidgetInternal(widget);
        widget.getPane().setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                setFocusedWidgetInternal(widget);
                requestWidgetFocus(widget);
            }
        });
        widget.getPreferredFocusableNode().focusedProperty().addListener((obs, oldV, newV) -> {
            if (Boolean.TRUE.equals(newV)) {
                setFocusedWidgetInternal(widget);
            }
        });
        // The keystrokes go to the canvas inside that node, and the node's own focused property
        // stays false while the canvas holds the focus. Follow the canvas as well, or focus that
        // reaches a pane without a primary click on it (a middle click, the window regaining focus
        // after a dialog, a focus request from code) leaves getFocusedWidget() on the old pane.
        TerminalPanel panel = widget.getTerminalPanel();
        if (panel != null && panel.getCanvas() != null) {
            panel.getCanvas().focusedProperty().addListener((obs, oldV, newV) -> {
                if (Boolean.TRUE.equals(newV)) {
                    setFocusedWidgetInternal(widget);
                }
            });
        }
        // Auto-close split when session ends (e.g. Ctrl+D / exit)
        widget.addListener(new TerminalWidgetListener() {
            @Override
            public void allSessionsClosed(com.sithtermfx.ui.TerminalWidget w) {
                Platform.runLater(() -> {
                    if (rootCell != null && rootCell.countWidgets() > 1) {
                        logger.info("Session closed in split widget, auto-closing split pane");
                        closeSplit(widget);
                    } else if (onLastWidgetSessionEnded != null) {
                        logger.info("Session closed in the last pane, requesting tab close");
                        onLastWidgetSessionEnded.run();
                    }
                });
            }
        });
        
        setupContextMenu(widget);
        
        var widgetPane = widget.getPane();
        
        widgetPane.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            String character = event.getCharacter();
            // Used up by every KEY_TYPED, mirrored or not, so it never applies to a later key.
            boolean typedByPane = character != null && !character.isEmpty()
                && mirrorsTypedCharacter(widget, character.charAt(0));
            if (!isMirroring(widget)) return;
            // Meta/Cmd chords are shortcuts, not text (menu accelerators such as Cmd+Shift+D only
            // consume KEY_PRESSED; macOS still delivers the paired KEY_TYPED character here).
            if (event.isMetaDown()) return;
            // Search text typed into the find bar is not shell input.
            if (!isTerminalKeyTarget(widget, event.getTarget())) return;
            // Only what this pane sends to its own program: not the control character that copy,
            // paste or a menu shortcut leaves in the KEY_TYPED (BroadcastTargets.TypedMirror).
            if (!typedByPane) return;
            char c = character.charAt(0);
            if (c == '\r' || c == '\t' || c == '\u001B' || c == '\u007F') {
                return;
            }
            broadcastToOthers(widget, character);
        });

        widgetPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> routeKeyPressed(widget, event));
        if (panel != null && panel.getCanvas() != null) {
            // After SithTermFX's key filter on the canvas, added when the widget was built.
            panel.getCanvas().addEventFilter(KeyEvent.KEY_PRESSED, event -> noteTerminalHandledKey(widget, event));
        }
    }

    private void requestWidgetFocus(@NotNull SithTermFxWidget widget) {
        if (widget.getTerminalPanel() != null && widget.getTerminalPanel().getCanvas() != null) {
            widget.getTerminalPanel().getCanvas().requestFocus();
            return;
        }
        Node focusTarget = widget.getPreferredFocusableNode();
        if (focusTarget != null) {
            focusTarget.requestFocus();
        } else {
            widget.getPane().requestFocus();
        }
    }

    public void setExtraMenuItemsFactory(@Nullable Function<SithTermFxWidget, List<MenuItem>> factory) {
        this.extraMenuItemsFactory = factory;
    }

    /**
     * Sets the items the context menu's <i>Extras</i> submenu shows below <b>Broadcast Mode</b> for a
     * pane, built each time the menu opens; korTTY's multi-exec toggle goes there.
     */
    public void setMirrorMenuItemsFactory(@Nullable Function<SithTermFxWidget, List<MenuItem>> factory) {
        this.mirrorMenuItemsFactory = factory;
    }

    /** Sets a hook invoked for each widget being closed (split close or close-all). */
    public void setOnWidgetClosed(@Nullable Consumer<SithTermFxWidget> onWidgetClosed) {
        this.onWidgetClosed = onWidgetClosed;
    }

    /** Sets a hook invoked after a split creates a new widget; receives (newWidget, originating request). */
    public void setOnWidgetSplitCreated(@Nullable BiConsumer<SithTermFxWidget, SplitRequest> onWidgetSplitCreated) {
        this.onWidgetSplitCreated = onWidgetSplitCreated;
    }

    /** Sets a hook invoked when the last remaining pane's session ends, so the owner can close the tab. */
    public void setOnLastWidgetSessionEnded(@Nullable Runnable onLastWidgetSessionEnded) {
        this.onLastWidgetSessionEnded = onLastWidgetSessionEnded;
    }

    /** Returns the per-pane StackPane wrapper used as the mount point for a per-pane effect overlay. */
    public @Nullable StackPane getWidgetOverlayHost(@Nullable SithTermFxWidget widget) {
        return widget != null ? widgetOverlayHosts.get(widget) : null;
    }

    /**
     * The given overlay layer of a pane, created in the pane's wrapper on first use; see
     * {@link PaneOverlayLayer}. Its origin is the wrapper's, which also holds the timestamp gutter
     * and the agent panel, so place nodes through scene coordinates
     * ({@code layer.sceneToLocal(canvas.localToScene(x, y))}).
     *
     * @return the layer, or {@code null} for a widget that is not (or no longer) a pane here
     */
    public @Nullable Pane paneOverlay(@Nullable SithTermFxWidget widget, @NotNull PaneOverlayLayer layer) {
        StackPane host = wrapperOf(widget);
        return host != null ? overlayLayerOf(host, layer) : null;
    }

    /** The layer if the pane already has it; never creates one. */
    private @Nullable Pane existingPaneOverlay(@Nullable SithTermFxWidget widget, @NotNull PaneOverlayLayer layer) {
        StackPane host = wrapperOf(widget);
        return host != null ? existingOverlayLayer(host, layer) : null;
    }

    /**
     * The layer in {@code host}, a pane's wrapper, added on first use. It follows the wrapper's size,
     * so a node of the layer can fill the pane; it is unmanaged, so that never resizes the terminal.
     */
    static @NotNull Pane overlayLayerOf(@NotNull StackPane host, @NotNull PaneOverlayLayer layer) {
        Pane existing = existingOverlayLayer(host, layer);
        if (existing != null) {
            return existing;
        }
        Pane created = createOverlayLayer(layer);
        Bounds bounds = host.getLayoutBounds();
        created.resize(bounds.getWidth(), bounds.getHeight());
        host.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) ->
            created.resize(newBounds.getWidth(), newBounds.getHeight()));
        host.getProperties().put(layer, created);
        host.getChildren().add(created);
        return created;
    }

    private static @Nullable Pane existingOverlayLayer(@NotNull StackPane host, @NotNull PaneOverlayLayer layer) {
        return host.getProperties().get(layer) instanceof Pane existing && existing.getParent() == host
            ? existing : null;
    }

    /**
     * The wrapper of a pane: from {@link #getWidgetOverlayHost}, or else the {@link StackPane} above
     * the widget's node whose user data is the widget. The map can miss a pane, because a leaf cell
     * prunes it against the panes of the tree before the cell itself is part of that tree.
     */
    private @Nullable StackPane wrapperOf(@Nullable SithTermFxWidget widget) {
        StackPane host = getWidgetOverlayHost(widget);
        if (host != null || widget == null || widget.getPane() == null) {
            return host;
        }
        for (Node node = widget.getPane().getParent(); node != null; node = node.getParent()) {
            if (node instanceof StackPane wrapper && wrapper.getUserData() == widget) {
                return wrapper;
            }
        }
        return null;
    }

    /** An empty overlay layer: unmanaged, mouse-transparent, not focusable, at the layer's view order. */
    static @NotNull Pane createOverlayLayer(@NotNull PaneOverlayLayer layer) {
        Pane pane = new Pane();
        pane.setManaged(false);
        pane.setMouseTransparent(true);
        pane.setPickOnBounds(false);
        pane.setFocusTraversable(false);
        pane.setViewOrder(layer.viewOrder());
        pane.getStyleClass().add("terminal-pane-overlay");
        return pane;
    }

    /**
     * The focus ring in a pane's {@link PaneOverlayLayer#DECORATION} layer, added on first use: a
     * region as large as the layer, so as large as the pane, whose border the stylesheets draw while
     * the pane has the keyboard focus ({@code .kortty-pane-cell:focus-within}) or is the pane the
     * menu commands act on ({@code :last-focused}). Unmanaged and without padding, so showing it
     * never resizes the terminal; mouse-transparent and not focusable.
     */
    static @NotNull Region focusRingOf(@NotNull Pane decorationLayer) {
        return layerFillingRegion(decorationLayer, FOCUS_RING_STYLE_CLASS);
    }

    /** The focus ring of a decoration layer, or {@code null} while it has none. */
    static @Nullable Region findFocusRing(@NotNull Pane decorationLayer) {
        return findStyledRegion(decorationLayer, FOCUS_RING_STYLE_CLASS);
    }

    /**
     * The amber outline in a pane's {@link PaneOverlayLayer#DECORATION} layer, added on first use:
     * shown while what you type in the pane goes to other panes, through multi-exec or broadcast
     * mode. Like the focus ring it is as large as the pane, unmanaged, without padding,
     * mouse-transparent and not focusable; the stylesheets draw it just inside the ring, so both show.
     */
    static @NotNull Region mirrorOutlineOf(@NotNull Pane decorationLayer) {
        return layerFillingRegion(decorationLayer, MIRROR_OUTLINE_STYLE_CLASS);
    }

    /** The mirror outline of a decoration layer, or {@code null} while it has none. */
    static @Nullable Region findMirrorOutline(@NotNull Pane decorationLayer) {
        return findStyledRegion(decorationLayer, MIRROR_OUTLINE_STYLE_CLASS);
    }

    /**
     * The region with {@code styleClass} in {@code layer}, added on first use: as large as the layer
     * and following its size, unmanaged, without padding, mouse-transparent and not focusable, so
     * the stylesheets can draw a border over the pane's edge that never resizes the terminal.
     */
    private static @NotNull Region layerFillingRegion(@NotNull Pane layer, @NotNull String styleClass) {
        Region existing = findStyledRegion(layer, styleClass);
        if (existing != null) {
            return existing;
        }
        Region region = new Region();
        region.getStyleClass().add(styleClass);
        region.setManaged(false);
        region.setMouseTransparent(true);
        region.setPickOnBounds(false);
        region.setFocusTraversable(false);
        region.resize(layer.getWidth(), layer.getHeight());
        ChangeListener<Number> fit = (obs, oldSize, newSize) -> region.resize(layer.getWidth(), layer.getHeight());
        layer.widthProperty().addListener(fit);
        layer.heightProperty().addListener(fit);
        layer.getChildren().add(region);
        return region;
    }

    private static @Nullable Region findStyledRegion(@NotNull Pane layer, @NotNull String styleClass) {
        for (Node child : layer.getChildren()) {
            if (child instanceof Region region && region.getStyleClass().contains(styleClass)) {
                return region;
            }
        }
        return null;
    }

    /**
     * A pane's accessible name: "Pane 2 of 3" with two or more panes, numbered in
     * {@link #getAllWidgets()} order as the dashboard numbers them; {@code null} for a single pane.
     *
     * @param index the pane's position, from 0
     */
    static @Nullable String paneAccessibleName(int index, int paneCount) {
        return paneCount > 1 ? I18n.get(PANE_ACCESSIBLE_NAME_KEY, index + 1, paneCount) : null;
    }

    /**
     * Brings every pane's focus ring and accessible name up to date with the number of panes: both
     * only with two or more panes, where they tell the panes apart. A zoomed pane fills the tab alone,
     * so no pane shows a ring, the zoomed one shows the zoom badge instead, and its name says it is
     * zoomed. A pane whose typing goes to other panes, as a member of the input mirror (multi-exec)
     * or in a tab whose broadcast mode is on, shows an amber outline and a badge that says which, and
     * its name says it too. Runs after every change to the tree, to the zoom, to broadcast mode and
     * to the input mirror's members ({@link #refreshMirrorMarkers}), so a pane that joined gets them,
     * the numbers follow a moved pane and the badges count the right panes.
     */
    private void refreshPaneDecorations() {
        List<SithTermFxWidget> panes = getAllWidgets();
        boolean several = panes.size() > 1;
        int hiddenReceivers = zoomedWidget != null ? hiddenMirrorReceivers(zoomedWidget, panes) : 0;
        String badgeText = zoomedWidget != null ? zoomBadgeText(panes.size() - 1, hiddenReceivers) : null;
        List<String> mirrorTexts = new ArrayList<>(panes.size());
        for (int i = 0; i < panes.size(); i++) {
            SithTermFxWidget pane = panes.get(i);
            String mirrorText = mirrorBadgeText(isMirrorMember(pane), broadcastMode && several);
            mirrorTexts.add(mirrorText);
            Pane decoration = several
                ? paneOverlay(pane, PaneOverlayLayer.DECORATION)
                : existingPaneOverlay(pane, PaneOverlayLayer.DECORATION);
            Region ring = decoration == null ? null
                : several ? focusRingOf(decoration) : findFocusRing(decoration);
            if (ring != null) {
                ring.setVisible(several && zoomedWidget == null);
            }
            TerminalPanel panel = pane.getTerminalPanel();
            if (panel != null && panel.getCanvas() != null) {
                String name = paneAccessibleName(i, panes.size());
                String text = pane == zoomedWidget && name != null ? name + ", " + badgeText : name;
                panel.getCanvas().setAccessibleText(joinAccessibleText(text, mirrorText));
            }
        }
        refreshLastFocusedMarks();
        refreshZoomBadge(badgeText, hiddenReceivers > 0);
        // After the zoom badge, so a zoomed pane's mirror badge can sit left of it.
        for (int i = 0; i < panes.size(); i++) {
            refreshMirrorMarker(panes.get(i), mirrorTexts.get(i));
        }
    }

    /**
     * Redraws the outline and badge of every pane whose typing goes to other panes, and the zoom
     * badge's count; the input mirror calls it when its members changed.
     */
    public void refreshMirrorMarkers() {
        refreshPaneDecorations();
    }

    /**
     * The badge of a pane whose typing goes to other panes: "Multi-exec" for a member of the input
     * mirror, else "Broadcast" in a tab whose broadcast mode reaches other panes; {@code null} for a
     * pane whose typing stays in it.
     */
    static @Nullable String mirrorBadgeText(boolean multiExecMember, boolean broadcasting) {
        if (multiExecMember) {
            return I18n.get(MULTI_EXEC_BADGE_KEY);
        }
        return broadcasting ? I18n.get(BROADCAST_BADGE_KEY) : null;
    }

    /** A pane's accessible text: its name, then its mirror badge, each left out when there is none. */
    static @Nullable String joinAccessibleText(@Nullable String name, @Nullable String mirrorText) {
        if (mirrorText == null) {
            return name;
        }
        return name != null ? name + ", " + mirrorText : mirrorText;
    }

    /**
     * Shows the outline and the badge with {@code text} on {@code pane}, or hides them for a
     * {@code null} text. The badge sits at the top right, left of the pane's × or, on a zoomed pane,
     * left of the zoom badge. Like the ring both are mouse-transparent and never resize the terminal.
     */
    private void refreshMirrorMarker(@NotNull SithTermFxWidget pane, @Nullable String text) {
        Pane decoration = text != null
            ? paneOverlay(pane, PaneOverlayLayer.DECORATION)
            : existingPaneOverlay(pane, PaneOverlayLayer.DECORATION);
        if (decoration == null) {
            return;
        }
        Region outline = text != null ? mirrorOutlineOf(decoration) : findMirrorOutline(decoration);
        if (outline != null) {
            outline.setVisible(text != null);
        }
        Label badge = findMirrorBadge(decoration);
        if (text == null) {
            if (badge != null) {
                badge.setVisible(false);
            }
            return;
        }
        if (badge == null) {
            badge = createMirrorBadge();
            decoration.getChildren().add(badge);
        }
        badge.setText(text);
        badge.setVisible(true);
        Label zoomed = pane == zoomedWidget ? zoomBadge : null;
        DoubleBinding right = zoomed != null && zoomed.getParent() == decoration
            ? zoomed.layoutXProperty().subtract(MIRROR_BADGE_GAP)
            : decoration.widthProperty().subtract(ZOOM_BADGE_RIGHT_INSET);
        badge.layoutXProperty().bind(right.subtract(badge.widthProperty()));
    }

    /** The mirror badge of a decoration layer, or {@code null} while it has none. */
    private static @Nullable Label findMirrorBadge(@NotNull Pane decorationLayer) {
        for (Node child : decorationLayer.getChildren()) {
            if (child instanceof Label label && label.getStyleClass().contains(MIRROR_BADGE_STYLE_CLASS)) {
                return label;
            }
        }
        return null;
    }

    /** The mirror badge: an icon and a text, mouse-transparent and not focusable, styled by the stylesheets. */
    private static @NotNull Label createMirrorBadge() {
        SVGPath icon = new SVGPath();
        icon.setContent(MIRROR_ICON_PATH);
        icon.getStyleClass().add(MIRROR_BADGE_ICON_STYLE_CLASS);
        Label badge = new Label();
        badge.setGraphic(icon);
        badge.getStyleClass().add(MIRROR_BADGE_STYLE_CLASS);
        badge.setMouseTransparent(true);
        badge.setFocusTraversable(false);
        badge.setLayoutY(ZOOM_BADGE_TOP_INSET);
        return badge;
    }

    /**
     * The zoom badge's text: "Zoomed · hidden panes: 2", or while hidden panes receive the keys
     * typed in the zoomed pane "Zoomed · hidden panes: 2, receiving your input: 2", so typing that
     * reaches panes you cannot see never goes unnoticed.
     */
    static @NotNull String zoomBadgeText(int hiddenPanes, int hiddenReceivers) {
        return hiddenReceivers > 0
            ? I18n.get(ZOOMED_MIRROR_BADGE_KEY, hiddenPanes, hiddenReceivers)
            : I18n.get(ZOOMED_BADGE_KEY, hiddenPanes);
    }

    /**
     * How many of this tab's {@code panes} get the keys typed in the {@code zoomed} pane now, all of
     * them hidden behind it: in broadcast mode, and as members of the input mirror while the zoomed
     * pane is one ({@link #mirrorTargetsOf}). Panes the guard holds back are not counted, nor the
     * mirror's members in other tabs, which the badge does not speak of.
     */
    private int hiddenMirrorReceivers(@NotNull SithTermFxWidget zoomed, @NotNull List<SithTermFxWidget> panes) {
        int receivers = 0;
        for (SithTermFxWidget target : mirrorTargetsOf(zoomed)) {
            if (panes.contains(target)) {
                receivers++;
            }
        }
        return receivers;
    }

    /**
     * Shows the zoom badge with {@code text} at the top right of the zoomed pane, left of its ×, or
     * removes it while no pane is zoomed. Like the ring it is mouse-transparent and never resizes
     * the terminal: the DECORATION layer is unmanaged.
     *
     * @param mirroring whether hidden panes receive the keys typed in the zoomed pane, which the
     *     stylesheets mark with the {@link #MIRRORING} pseudo-class
     */
    private void refreshZoomBadge(@Nullable String text, boolean mirroring) {
        Label badge = zoomBadge;
        Pane decoration = zoomedWidget != null && text != null
            ? paneOverlay(zoomedWidget, PaneOverlayLayer.DECORATION)
            : null;
        if (badge != null && badge.getParent() instanceof Pane parent && parent != decoration) {
            badge.layoutXProperty().unbind();
            parent.getChildren().remove(badge);
        }
        if (decoration == null) {
            return;
        }
        if (badge == null) {
            badge = createZoomBadge();
            zoomBadge = badge;
        }
        badge.setText(text);
        badge.pseudoClassStateChanged(MIRRORING, mirroring);
        if (badge.getParent() != decoration) {
            badge.layoutXProperty().bind(decoration.widthProperty().subtract(badge.widthProperty())
                .subtract(ZOOM_BADGE_RIGHT_INSET));
            decoration.getChildren().add(badge);
        }
    }

    /** The zoom badge: an icon and a text, mouse-transparent and not focusable, styled by the stylesheets. */
    private static @NotNull Label createZoomBadge() {
        SVGPath icon = new SVGPath();
        icon.setContent(ZOOM_BADGE_ICON_PATH);
        icon.getStyleClass().add(ZOOM_BADGE_ICON_STYLE_CLASS);
        Label badge = new Label();
        badge.setGraphic(icon);
        badge.getStyleClass().add(ZOOM_BADGE_STYLE_CLASS);
        badge.setMouseTransparent(true);
        badge.setFocusTraversable(false);
        badge.setLayoutY(ZOOM_BADGE_TOP_INSET);
        return badge;
    }

    /** Marks the wrapper of {@link #focusedWidget}, and only that one, {@link #LAST_FOCUSED}. */
    private void refreshLastFocusedMarks() {
        for (SithTermFxWidget pane : getAllWidgets()) {
            StackPane wrapper = wrapperOf(pane);
            if (wrapper != null) {
                wrapper.pseudoClassStateChanged(LAST_FOCUSED, pane == focusedWidget);
            }
        }
    }

    private void notifyWidgetSplitCreated(@Nullable SithTermFxWidget widget, @NotNull SplitRequest request) {
        if (onWidgetSplitCreated != null && widget != null) {
            try {
                onWidgetSplitCreated.accept(widget, request);
            } catch (RuntimeException e) {
                logger.debug("onWidgetSplitCreated hook failed: {}", e.getMessage());
            }
        }
    }

    public void setOnBroadcastModeChanged(@Nullable Consumer<Boolean> onBroadcastModeChanged) {
        this.onBroadcastModeChanged = onBroadcastModeChanged;
    }

    /**
     * Sets how to strip the decorators the host app wraps around a pane's connector (the
     * constructor's {@code connectorDecorator}), so navigation keys are encoded for the real
     * connector type. Defaults to no unwrapping.
     */
    public void setConnectorUnwrapper(@Nullable UnaryOperator<TtyConnector> connectorUnwrapper) {
        this.connectorUnwrapper = connectorUnwrapper != null ? connectorUnwrapper : UnaryOperator.identity();
    }

    /**
     * Sets the mirror guard: broadcast mode sends a pane's keys only to the other panes for which
     * {@code guard} returns true, read at every key. Null lets every pane receive them, the default.
     */
    public void setMirrorTargetGuard(@Nullable Predicate<SithTermFxWidget> guard) {
        this.mirrorTargetGuard = guard != null ? guard : widget -> true;
    }

    /**
     * Sets the mirror input rule: for a key typed in a pane, {@code rule} returns which of the other
     * panes the mirror guard accepts may get that key. It is asked once per key, on the FX thread,
     * before the key reaches the pane it was typed in. Null lets every such pane get every key, the
     * default.
     */
    public void setMirrorInputRule(@Nullable Function<SithTermFxWidget, Predicate<SithTermFxWidget>> rule) {
        this.mirrorInputRule = rule != null ? rule : source -> widget -> true;
    }

    /**
     * Sets the input mirror: the keys typed in one of its members go to its other members as well,
     * in this tab and in other tabs and windows, through the same guards, key rule and per-pane
     * encoding as broadcast mode. Null, the default, mirrors nothing beyond broadcast mode.
     */
    public void setInputMirror(@Nullable InputMirror mirror) {
        this.inputMirror = mirror;
        if (zoomedWidget != null) {
            // The zoom badge says whether the hidden panes get the keys typed in the zoomed one.
            refreshPaneDecorations();
        }
    }

    /**
     * Makes this container and every nested JavaFX split control transparent. The state is retained so
     * split controls created later inherit it as well; clearing it restores the active theme background.
     */
    public void setBackgroundTransparent(boolean transparent) {
        backgroundTransparent = transparent;
        applyBackgroundStyle(this);
        if (rootCell != null) {
            rootCell.refreshBackgroundStyle();
        }
    }

    private void applyBackgroundStyle(@NotNull Region region) {
        region.setStyle(backgroundTransparent ? TRANSPARENT_BACKGROUND_STYLE : null);
    }

    private void notifyWidgetClosed(@Nullable SithTermFxWidget widget) {
        if (onWidgetClosed != null && widget != null) {
            try {
                onWidgetClosed.accept(widget);
            } catch (Exception e) {
                logger.debug("onWidgetClosed hook failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Sets a callback to run when the user chooses "Reset" font size in the context menu.
     * If set, this is used instead of the widget's default reset (so e.g. zoom can reset to connection/global font size).
     */
    public void setResetZoomCallback(@Nullable Runnable resetZoomCallback) {
        this.resetZoomCallback = resetZoomCallback;
    }

    private void setupContextMenu(@NotNull SithTermFxWidget widget) {
        var terminalPanel = widget.getTerminalPanel();
        var canvas = terminalPanel.getCanvas();
        
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (activeContextMenu != null && activeContextMenu.isShowing()) {
                activeContextMenu.hide();
                activeContextMenu = null;
                if (event.getButton() != MouseButton.SECONDARY) {
                    return;
                }
            }
        });
        
        canvas.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                setFocusedWidgetInternal(widget);
                ContextMenu menu = createFullContextMenu(widget);
                activeContextMenu = menu;
                menu.setOnHidden(e -> {
                    if (activeContextMenu == menu) {
                        activeContextMenu = null;
                    }
                });
                menu.show(canvas, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
    }
    
    /** One entry of the terminal context menu: the i18n key of its label and the command it runs. */
    record TerminalMenuAction(@NotNull String i18nKey, @NotNull Runnable action) {
    }

    /**
     * The edit entries at the top of the terminal context menu, in menu order. Free of JavaFX so a
     * unit test can fire every entry and check that it reaches the pane.
     */
    static @NotNull List<TerminalMenuAction> editActions(@NotNull TerminalPaneActions actions) {
        return List.of(
            new TerminalMenuAction("terminal.contextMenu.copy", actions::copySelection),
            new TerminalMenuAction("terminal.contextMenu.paste", actions::paste),
            new TerminalMenuAction("terminal.contextMenu.clearBuffer", actions::clearBuffer),
            new TerminalMenuAction("terminal.contextMenu.find", actions::showFind));
    }

    /**
     * The entries of <b>Extras &gt; Font Size</b>, in menu order.
     *
     * @param reset what <b>Reset</b> runs: the tab's reset-zoom callback or the widget's own reset
     */
    static @NotNull List<TerminalMenuAction> fontSizeActions(@NotNull TerminalPaneActions actions,
                                                             @NotNull Runnable reset) {
        return List.of(
            new TerminalMenuAction("terminal.contextMenu.increase", actions::increaseFontSize),
            new TerminalMenuAction("terminal.contextMenu.decrease", actions::decreaseFontSize),
            new TerminalMenuAction("terminal.contextMenu.reset", reset));
    }

    private static @NotNull List<MenuItem> toMenuItems(@NotNull List<TerminalMenuAction> actions) {
        List<MenuItem> items = new ArrayList<>(actions.size());
        for (TerminalMenuAction action : actions) {
            MenuItem item = new MenuItem(I18n.get(action.i18nKey()));
            item.setOnAction(e -> action.action().run());
            items.add(item);
        }
        return items;
    }

    /** Every widget this pane creates is a {@code KorttyTermWidget}; anything else gets no pane commands. */
    private static @Nullable TerminalPaneActions paneActionsOf(@NotNull SithTermFxWidget widget) {
        return widget instanceof TerminalPaneActions actions ? actions : null;
    }

    /**
     * <b>Open Link</b> and <b>Copy Link Address</b> (or <b>Open File in Snippet Editor</b> and
     * <b>Copy Path</b> for a file) when the menu was opened on a link korTTY opens,
     * none otherwise. Copy goes through {@link KorttyClipboard}, so the enterprise policy's internal
     * clipboard keeps the address inside korTTY.
     */
    static @NotNull List<TerminalMenuAction> linkActions(@NotNull SithTermFxWidget widget) {
        if (!(widget instanceof KorttyTermWidget korttyWidget)) {
            return List.of();
        }
        List<TerminalMenuAction> actions = new ArrayList<>(2);
        for (TerminalLinkContextMenu.Entry entry : TerminalLinkContextMenu.entries(
                korttyWidget.contextMenuLink(), korttyWidget::openLink, KorttyClipboard::setText)) {
            actions.add(new TerminalMenuAction(entry.i18nKey(), entry.action()));
        }
        return actions;
    }

    private @NotNull ContextMenu createFullContextMenu(@NotNull SithTermFxWidget widget) {
        ContextMenu menu = new ContextMenu();
        // Right-clicked on a link: the link's own entries come first, as in other terminals.
        List<MenuItem> linkItems = toMenuItems(linkActions(widget));
        if (!linkItems.isEmpty()) {
            menu.getItems().addAll(linkItems);
            menu.getItems().add(new SeparatorMenuItem());
        }
        TerminalPaneActions actions = paneActionsOf(widget);
        if (actions != null) {
            List<MenuItem> editItems = toMenuItems(editActions(actions));
            // Copy without a selection would do nothing, so it is greyed out.
            editItems.get(0).setDisable(widget.getTerminalPanel().getSelection() == null);
            menu.getItems().addAll(editItems);
        }

        if (extraMenuItemsFactory != null) {
            List<MenuItem> extraItems = extraMenuItemsFactory.apply(widget);
            if (extraItems != null && !extraItems.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
                menu.getItems().addAll(extraItems);
            }
        }

        menu.getItems().add(new SeparatorMenuItem());
        Menu extrasMenu = createExtrasSubmenu(widget);
        menu.getItems().add(extrasMenu);
        return menu;
    }
    
    private @NotNull Menu createExtrasSubmenu(@NotNull SithTermFxWidget widget) {
        Menu extrasMenu = new Menu(I18n.get("terminal.contextMenu.extras"));
        Menu fontMenu = new Menu(I18n.get("terminal.contextMenu.fontSize"));
        Runnable resetFont = () -> {
            if (resetZoomCallback != null) {
                resetZoomCallback.run();
            } else {
                widget.resetFontSize();
            }
        };
        TerminalPaneActions actions = paneActionsOf(widget);
        fontMenu.getItems().addAll(toMenuItems(actions != null
            ? fontSizeActions(actions, resetFont)
            : List.of(new TerminalMenuAction("terminal.contextMenu.reset", resetFont))));
        
        Menu splitMenu = new Menu(I18n.get("terminal.contextMenu.splitTerminal"));
        MenuItem splitRightSame = new MenuItem(I18n.get("terminal.contextMenu.splitRightSame"));
        splitRightSame.setOnAction(e -> split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL));
        MenuItem splitRightNew = new MenuItem(I18n.get("terminal.contextMenu.splitRightNew"));
        splitRightNew.setOnAction(e -> split(SplitRequest.SplitMode.NEW_CONNECTION, Orientation.HORIZONTAL));
        MenuItem splitDownSame = new MenuItem(I18n.get("terminal.contextMenu.splitDownSame"));
        splitDownSame.setOnAction(e -> split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL));
        MenuItem splitDownNew = new MenuItem(I18n.get("terminal.contextMenu.splitDownNew"));
        splitDownNew.setOnAction(e -> split(SplitRequest.SplitMode.NEW_CONNECTION, Orientation.VERTICAL));
        MenuItem closeSplit = new MenuItem(I18n.get("terminal.contextMenu.closeSplit"));
        closeSplit.setOnAction(e -> closeSplit(widget));
        closeSplit.setDisable(rootCell.countWidgets() <= 1);
        splitMenu.getItems().addAll(splitRightSame, splitRightNew, new SeparatorMenuItem(),
                                    splitDownSame, splitDownNew, new SeparatorMenuItem(), closeSplit);
        
        CheckMenuItem broadcastToggle = new CheckMenuItem(I18n.get("terminal.contextMenu.broadcastMode"));
        broadcastToggle.setSelected(broadcastMode);
        broadcastToggle.setOnAction(e -> setBroadcastMode(broadcastToggle.isSelected()));
        broadcastToggle.setDisable(rootCell.countWidgets() <= 1);
        extrasMenu.getItems().addAll(splitMenu, fontMenu, new SeparatorMenuItem(), broadcastToggle);
        String held = broadcastMode ? heldMirrorTargetsText(countHeldMirrorTargets(), getWidgetCount()) : null;
        if (held != null) {
            // Information, not a command: why what you type does not show up in some panes.
            MenuItem heldInfo = new MenuItem(held);
            heldInfo.setDisable(true);
            extrasMenu.getItems().add(heldInfo);
        }
        if (mirrorMenuItemsFactory != null) {
            try {
                List<MenuItem> mirrorItems = mirrorMenuItemsFactory.apply(widget);
                if (mirrorItems != null) {
                    extrasMenu.getItems().addAll(mirrorItems);
                }
            } catch (RuntimeException e) {
                logger.debug("Mirror menu items failed: {}", e.getMessage());
            }
        }
        return extrasMenu;
    }

    /**
     * The note below <b>Broadcast Mode</b> while broadcast mode leaves panes out, such as
     * "Panes left out right now: 1 of 3", or null while it leaves none out.
     */
    static @Nullable String heldMirrorTargetsText(int heldPanes, int paneCount) {
        return heldPanes > 0 ? I18n.get(BROADCAST_HELD_KEY, heldPanes, paneCount) : null;
    }
    
    public void split(@NotNull SplitRequest.SplitMode mode, @NotNull Orientation orientation) {
        SithTermFxWidget parent = getFocusedWidget();
        if (parent == null) {
            logger.warn("No focused widget to split");
            return;
        }
        splitWidget(parent, mode, orientation);
    }

    public void splitHorizontally(@Nullable SplitRequest.SplitMode mode) {
        split(mode != null ? mode : SplitRequest.SplitMode.NEW_CONNECTION, Orientation.HORIZONTAL);
    }

    public void splitVertically(@Nullable SplitRequest.SplitMode mode) {
        split(mode != null ? mode : SplitRequest.SplitMode.NEW_CONNECTION, Orientation.VERTICAL);
    }

    private void splitWidget(@NotNull SithTermFxWidget widget, @NotNull SplitRequest.SplitMode mode,
                            @NotNull Orientation orientation) {
        splitWidget(widget, mode, orientation, null);
    }

    /**
     * Splits {@code widget}, optionally adopting a connector the caller has already built and
     * connected.
     *
     * <p>A non-null {@code preparedConnector} bypasses the {@link SplitConnectorFactory} entirely.
     * That is the whole point of the overload: korTTY answers a same-server split by showing an
     * {@code APPLICATION_MODAL} progress stage and running a nested JavaFX event loop with a
     * two-minute await, which a programmatic caller on the FX thread would sit inside. Preparing the
     * connector off the FX thread and handing it in here keeps the layout mutation to a single
     * non-blocking hop.
     *
     * <p>Unlike the menu path this reports the outcome instead of aborting silently: a connector that
     * is null or not connected leaves the tree untouched and answers null, so a programmatic caller
     * can surface the failure rather than watch nothing happen.
     *
     * @param widget the pane to split; it stays open beside the new one. A widget that is not a pane
     *     of this split pane answers null before any pane is built, so the caller still owns
     *     {@code preparedConnector}
     * @param mode always pass a mode explicitly — {@code splitHorizontally(null)} and
     *     {@code splitVertically(null)} fall back to {@link SplitRequest.SplitMode#NEW_CONNECTION},
     *     which asks the user for a new connection
     * @param orientation where the new pane goes
     * @param preparedConnector an already-connected connector, or null to use the factory
     * @return the new pane, or null when the split did not happen
     */
    public @Nullable SithTermFxWidget splitWidget(@NotNull SithTermFxWidget widget,
                                                  @NotNull SplitRequest.SplitMode mode,
                                                  @NotNull Orientation orientation,
                                                  @Nullable TtyConnector preparedConnector) {
        if (!getAllWidgets().contains(widget)) {
            return null;
        }
        SplitRequest request = new SplitRequest(mode, widget);
        SithTermFxWidget newWidget = createWidget(request, preparedConnector);
        TtyConnector connector = newWidget.getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            releaseUnattachedWidget(newWidget);
            return null;
        }
        // The new pane goes beside its source, so a zoomed tab shows all its panes again first. A
        // split that failed or was cancelled above leaves the zoom alone.
        unzoom();
        setupWidget(newWidget);
        applyLeftPanel(newWidget);
        applyBottomPanel(newWidget);
        SplitCell newCell = new SplitCell(newWidget);
        SplitCell replacement = rootCell.replaceWidget(widget, newCell, orientation);
        if (replacement == null) {
            releaseUnattachedWidget(newWidget);
            return null;
        }
        getChildren().clear();
        rootCell = replacement;
        getChildren().add(rootCell.getNode());
        VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
        refreshDragAndDrop();
        refreshSplitCloseButtons();
        notifyWidgetSplitCreated(newWidget, request);
        return newWidget;
    }

    /**
     * Releases a pane built for a split that never joined the tree. The widget configurator (and,
     * with a connector, the decorator) already ran for it, so per-widget registrations exist: fire the
     * close hook exactly as closeSplit does, or those registrations leak for the tab's life.
     */
    private void releaseUnattachedWidget(@NotNull SithTermFxWidget widget) {
        notifyWidgetClosed(widget);
        try {
            widget.close();
        } catch (Exception ignored) {
        }
        forgetWidget(widget);
    }

    /** Drops every per-widget entry of a pane that has left, or never joined, the tree. */
    private void forgetWidget(@NotNull SithTermFxWidget widget) {
        widgetLeftPanels.remove(widget);
        widgetBottomPanels.remove(widget);
        widgetBottomHosts.remove(widget);
        widgetCloseButtons.remove(widget);
        widgetOverlayHosts.remove(widget);
    }

    /**
     * Closes one split pane, refusing the tab's last one.
     *
     * <p>The refusal is the contract, not a convenience: {@code SplitCell.removeWidget} answers null
     * for a root leaf, so closing the only pane clears the children and leaves an empty terminal area
     * inside a still-open tab. The user-facing "Close split" menu item is disabled in exactly this
     * case; a programmatic caller gets the same protection here.
     *
     * @param widget a pane of this split pane
     * @return true when the pane was closed, false when it was the last one or does not belong here
     */
    public boolean closeSplitPane(@NotNull SithTermFxWidget widget) {
        if (getWidgetCount() <= 1 || !getAllWidgets().contains(widget)) {
            return false;
        }
        closeSplit(widget);
        return true;
    }

    private void closeSplit(@NotNull SithTermFxWidget widget) {
        // The tree is rebuilt around the gap, with every pane in its place: no pane stays zoomed.
        unzoom();
        notifyWidgetClosed(widget);
        try {
            widget.close();
        } catch (Exception e) {
            logger.debug("Error closing widget: {}", e.getMessage());
        }
        forgetWidget(widget);
        SplitCell replacement = rootCell.removeWidget(widget);
        if (replacement != rootCell) {
            getChildren().clear();
            rootCell = replacement;
            if (rootCell != null) {
                getChildren().add(rootCell.getNode());
                VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
            }
            setFocusedWidgetInternal(paneFocusedAfterClose(focusedWidget, widget, getAllWidgets()));
            refreshDragAndDrop();
            refreshSplitCloseButtons();
        }
    }

    /**
     * The focused pane once {@code closed} has left the tree. Closing another pane (its session
     * ended, its close button, the Control API) does not move the keyboard focus, so the focused pane
     * stays; only when the focused pane itself is gone does the first remaining pane take over.
     *
     * @param remaining the panes still in the tree, in {@link #getAllWidgets()} order
     */
    static <W> @Nullable W paneFocusedAfterClose(@Nullable W focused, @NotNull W closed,
                                                 @NotNull List<W> remaining) {
        if (focused != null && focused != closed) {
            for (W pane : remaining) {
                if (pane == focused) {
                    return focused;
                }
            }
        }
        return remaining.isEmpty() ? null : remaining.get(0);
    }

    public @Nullable SithTermFxWidget getFocusedWidget() {
        return focusedWidget;
    }

    /**
     * The only writer of {@link #focusedWidget}: a new pane, primary and secondary clicks, the
     * canvas and preferred-node focus listeners, {@link #focusWidget} and closing a pane all change
     * the focused pane through here, so Edit &gt; Find, Copy/Paste and the AI actions, which read
     * {@link #getFocusedWidget()}, see one notion of it. TerminalSplitPaneFocusTrackingTest pins that.
     */
    private void setFocusedWidgetInternal(@Nullable SithTermFxWidget widget) {
        focusedWidget = widget;
        refreshLastFocusedMarks();
    }

    /**
     * Focuses {@code widget} programmatically exactly as a primary click on its pane would: the
     * split pane's own notion of the focused widget is updated first, then keyboard focus is
     * requested on the node that receives the keystrokes.
     *
     * <p>Requesting focus on the pane's canvas alone is not enough while the window itself is not
     * focused: the split pane follows the canvas's {@code focused} property, which stays false until
     * the window gets the focus, so {@link #getFocusedWidget()} would stay on the previous pane.
     *
     * @param widget a widget of this split pane; widgets that do not belong to it are ignored
     */
    public void focusWidget(@NotNull SithTermFxWidget widget) {
        if (!getAllWidgets().contains(widget)) {
            logger.debug("focusWidget ignored a widget that does not belong to this split pane");
            return;
        }
        // A hidden pane is out of the scene and cannot take the keyboard: show the panes again first
        // (the Control API's pane.focus and the Coding Agents panel come through here as well).
        if (zoomedWidget != null && widget != zoomedWidget) {
            unzoom();
        }
        setFocusedWidgetInternal(widget);
        requestWidgetFocus(widget);
    }

    /**
     * Moves the keyboard focus to the pane on {@code direction}'s side of the focused pane, as
     * Cmd+Option / Ctrl+Alt with an arrow key and <i>View → Panes</i> do. The panes are measured in
     * scene coordinates, the wrapper with its timestamp gutter and agent panel being the pane; see
     * {@link PaneNavigator} for how a neighbour is chosen. A zoomed tab shows all its panes again
     * first, laid out at once so they can be measured.
     *
     * @return true when the focus moved; false at the edge, with a single pane, or before layout
     */
    public boolean focusNeighbor(@NotNull PaneNavigator.PaneDirection direction) {
        List<SithTermFxWidget> panes = getAllWidgets();
        if (panes.size() < 2) {
            return false;
        }
        if (unzoom()) {
            applyCss();
            layout();
        }
        SithTermFxWidget origin = panes.contains(focusedWidget) ? focusedWidget : panes.get(0);
        Optional<SithTermFxWidget> target = PaneNavigator.neighbor(origin, panes, this::sceneBoundsOf, direction);
        target.ifPresent(this::focusWidget);
        return target.isPresent();
    }

    /**
     * Moves the keyboard focus to the next ({@code forward}) or the previous pane in
     * {@link #getAllWidgets()} order, wrapping around, as <i>View → Panes → Next Pane</i> and
     * <i>Previous Pane</i> do.
     *
     * @return true when the focus moved; false with a single pane
     */
    public boolean focusNext(boolean forward) {
        if (getWidgetCount() > 1) {
            unzoom();
        }
        Optional<SithTermFxWidget> target = PaneNavigator.next(getAllWidgets(), focusedWidget, forward);
        target.ifPresent(this::focusWidget);
        return target.isPresent();
    }

    /** Whether a pane is zoomed: it fills the tab alone and the others are hidden. */
    public boolean isZoomed() {
        return zoomedWidget != null;
    }

    /** The zoomed pane, or {@code null} while every pane shows. */
    public @Nullable SithTermFxWidget getZoomedWidget() {
        return zoomedWidget;
    }

    /**
     * Zooms the focused pane, or shows every pane again while one is zoomed, as Cmd/Ctrl+Shift+Enter
     * and <i>View → Panes → Zoom Pane</i> do.
     *
     * @return whether a pane is zoomed afterwards
     */
    public boolean toggleZoom() {
        if (zoomedWidget != null) {
            unzoom();
            return false;
        }
        return focusedWidget != null && zoomWidget(focusedWidget);
    }

    /**
     * Zooms {@code widget}: it fills the tab alone until {@link #unzoom()}, which every change to the
     * panes, every move of the focus to another pane and a pane drag run first. The other panes leave
     * the scene with the split controls that hold them and keep their size, so their programs get no
     * resize; they keep running, and in broadcast mode they still receive the keys typed in the
     * zoomed pane, which its badge counts. No split control is rebuilt, and the divider positions
     * come back as they were. Quick select and the hover underline of a link end, as they do when a
     * pane changes size. The zoomed pane gets the keyboard focus.
     *
     * @param widget a pane of this split pane
     * @return true when {@code widget} is zoomed now; false with a single pane or a widget that is
     *     not a pane here
     */
    public boolean zoomWidget(@NotNull SithTermFxWidget widget) {
        if (widget == zoomedWidget) {
            return true;
        }
        if (getWidgetCount() < 2 || !getAllWidgets().contains(widget)) {
            return false;
        }
        unzoom();
        SplitCell parent = rootCell.parentOf(widget);
        StackPane wrapper = wrapperOf(widget);
        if (parent == null || parent.splitPane == null || wrapper == null) {
            return false;
        }
        List<SplitPane> splits = new ArrayList<>();
        rootCell.collectSplitPanes(splits);
        PaneZoom<Node, SplitPane> zoomed = PaneZoom.zoom(wrapper, parent.splitPane.getItems(), getChildren(),
            createZoomPlaceholder(), splits, SPLIT_PANE_DIVIDERS);
        if (zoomed == null) {
            return false;
        }
        zoom = zoomed;
        zoomedWidget = widget;
        setFocusedWidgetInternal(widget);
        refreshPaneDecorations();
        requestWidgetFocus(widget);
        return true;
    }

    /**
     * Shows every pane again if one is zoomed: the zoomed pane goes back to its place and every split
     * control gets back its divider positions, once now and once more after the layout passes that
     * follow its return to the scene. The keyboard stays in the zoomed pane if it was there.
     *
     * @return whether a pane was zoomed
     */
    public boolean unzoom() {
        PaneZoom<Node, SplitPane> zoomed = zoom;
        if (zoomed == null) {
            return false;
        }
        SithTermFxWidget widget = zoomedWidget;
        boolean keyboardInPane = isFocusWithin(zoomed.pane());
        zoom = null;
        zoomedWidget = null;
        zoomed.restore();
        SplitCell tree = rootCell;
        // Two pulses later: after the split controls' first layout back in the scene and after the
        // divider reset any SplitCell built meanwhile schedules; skipped once the tree changed.
        Platform.runLater(() -> Platform.runLater(() -> {
            if (rootCell == tree && zoom == null) {
                zoomed.reapplyDividers();
            }
        }));
        refreshPaneDecorations();
        if (keyboardInPane && widget != null && getAllWidgets().contains(widget)) {
            requestWidgetFocus(widget);
        }
        return true;
    }

    /**
     * The divider positions of one of this split pane's split controls as they are with every pane
     * shown: while a pane is zoomed, the positions from before the zoom, which the control itself may
     * have reset when the zoomed pane left it. Saving a project while a pane is zoomed thus stores the
     * layout the tab returns to.
     */
    public double @NotNull [] dividerPositionsOf(@NotNull SplitPane control) {
        double[] saved = zoom != null ? zoom.savedPositions(control) : null;
        return saved != null ? saved : control.getDividerPositions();
    }

    /** An empty region that keeps a zoomed pane's place in its split control. */
    static @NotNull Region createZoomPlaceholder() {
        Region placeholder = new Region();
        placeholder.getStyleClass().add(ZOOM_PLACEHOLDER_STYLE_CLASS);
        placeholder.setMinSize(0, 0);
        placeholder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        placeholder.setFocusTraversable(false);
        return placeholder;
    }

    /** Whether the scene's keyboard focus is in {@code node} or one of its descendants. */
    private boolean isFocusWithin(@NotNull Node node) {
        Node owner = getScene() != null ? getScene().getFocusOwner() : null;
        for (Node current = owner; current != null; current = current.getParent()) {
            if (current == node) {
                return true;
            }
        }
        return false;
    }

    /** A pane's bounds in scene coordinates, or {@code null} while it is not laid out in a scene. */
    private @Nullable PaneNavigator.Rect sceneBoundsOf(@NotNull SithTermFxWidget widget) {
        StackPane host = wrapperOf(widget);
        if (host == null || host.getScene() == null) {
            return null;
        }
        Bounds bounds = host.localToScene(host.getLayoutBounds());
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        return new PaneNavigator.Rect(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
    }

    public @NotNull List<SithTermFxWidget> getAllWidgets() {
        List<SithTermFxWidget> widgets = new ArrayList<>();
        collectWidgets(rootCell, widgets);
        return widgets;
    }
    
    private void collectWidgets(@Nullable SplitCell cell, @NotNull List<SithTermFxWidget> widgets) {
        if (cell == null) return;
        if (cell.widget != null) {
            widgets.add(cell.widget);
        }
        collectWidgets(cell.leftCell, widgets);
        collectWidgets(cell.rightCell, widgets);
    }
    
    public int getWidgetCount() {
        return rootCell != null ? rootCell.countWidgets() : 0;
    }
    
    public boolean isBroadcastMode() {
        return broadcastMode;
    }
    
    public void setBroadcastMode(boolean enabled) {
        boolean changed = this.broadcastMode != enabled;
        this.broadcastMode = enabled;
        logger.info("Broadcast mode {}", enabled ? "enabled" : "disabled");
        if (changed && zoomedWidget != null) {
            // The zoom badge says whether the hidden panes get the keys typed in the zoomed one.
            refreshPaneDecorations();
        }
        if (changed && onBroadcastModeChanged != null) {
            try {
                onBroadcastModeChanged.accept(enabled);
            } catch (RuntimeException e) {
                logger.debug("onBroadcastModeChanged hook failed: {}", e.getMessage());
            }
        }
    }
    
    public void toggleBroadcastMode() {
        setBroadcastMode(!broadcastMode);
    }

    public void moveWidget(@NotNull SithTermFxWidget source, @NotNull SithTermFxWidget target,
                           @NotNull Placement placement) {
        if (rootCell == null || getWidgetCount() <= 1 || source == target) {
            return;
        }
        unzoom();
        ExtractResult er = rootCell.extractWidget(source);
        if (er == null || er.replacement == null) {
            return;
        }
        Orientation orientation;
        boolean newCellFirst;
        switch (placement) {
            case ABOVE:   orientation = Orientation.VERTICAL;   newCellFirst = true;  break;
            case BELOW:   orientation = Orientation.VERTICAL;   newCellFirst = false; break;
            case LEFT_OF: orientation = Orientation.HORIZONTAL; newCellFirst = true;  break;
            case RIGHT_OF: orientation = Orientation.HORIZONTAL; newCellFirst = false; break;
            default: return;
        }
        SplitCell newRoot = er.replacement.replaceWidget(target, er.extracted, orientation, newCellFirst);
        if (newRoot == null) {
            return;
        }
        getChildren().clear();
        rootCell = newRoot;
        getChildren().add(rootCell.getNode());
        VBox.setVgrow(rootCell.getNode(), Priority.ALWAYS);
        refreshDragAndDrop();
        // The panes are numbered in tree order, which the move changed.
        refreshPaneDecorations();
    }

    private void forEachLeafCell(@Nullable SplitCell cell, @NotNull java.util.function.Consumer<SplitCell> action) {
        if (cell == null) return;
        if (cell.widget != null) {
            action.accept(cell);
            return;
        }
        forEachLeafCell(cell.leftCell, action);
        forEachLeafCell(cell.rightCell, action);
    }

    private void refreshDragAndDrop() {
        forEachLeafCell(rootCell, this::attachDragAndDropToCell);
    }

    private @Nullable SithTermFxWidget findWidgetByIdentity(@NotNull String idString) {
        int id;
        try {
            id = Integer.parseInt(idString);
        } catch (NumberFormatException e) {
            return null;
        }
        for (SithTermFxWidget w : getAllWidgets()) {
            if (System.identityHashCode(w) == id) return w;
        }
        return null;
    }

    private void attachDragAndDropToCell(@NotNull SplitCell cell) {
        Region node = cell.getNode();
        SithTermFxWidget widget = cell.widget;
        if (widget == null) return;

        node.setOnDragDetected(event -> {
            if (getWidgetCount() <= 1) return;
            if (event.isConsumed()) return;
            if (!(event.isShiftDown() && event.isAltDown())) return;
            Dragboard db = node.startDragAndDrop(TransferMode.ANY);
            db.setContent(Map.of(DRAG_TERMINAL_FORMAT, String.valueOf(System.identityHashCode(widget))));
            event.consume();
        });

        node.addEventFilter(DragEvent.DRAG_ENTERED, event -> {
            if (!event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) return;
            String sourceId = (String) event.getDragboard().getContent(DRAG_TERMINAL_FORMAT);
            if (sourceId.equals(String.valueOf(System.identityHashCode(widget)))) return;
            SithTermFxWidget source = findWidgetByIdentity(sourceId);
            if (source == null) return;
            DropZoneOverlay.show(node, widget, sourceId, this);
            event.consume();
        });

        node.addEventFilter(DragEvent.DRAG_EXITED, event -> {
            if (event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                DropZoneOverlay.hide(node);
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) {
                event.acceptTransferModes(TransferMode.ANY);
                DropZoneOverlay.updatePlacement(node, event.getX(), event.getY());
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_DROPPED, event -> {
            if (!event.getDragboard().hasContent(DRAG_TERMINAL_FORMAT)) return;
            boolean done = DropZoneOverlay.tryDrop(node, this);
            event.setDropCompleted(done);
            event.consume();
        });
    }

    private void applyLeftPanel(@NotNull SithTermFxWidget widget) {
        if (leftPanelFactory != null) {
            Region leftPanel = leftPanelFactory.apply(widget);
            if (leftPanel != null) {
                widgetLeftPanels.put(widget, leftPanel);
            }
        }
    }

    public void setWidgetLeftPanel(@NotNull SithTermFxWidget widget, @NotNull Region panel) {
        widgetLeftPanels.put(widget, panel);
    }

    public void removeWidgetLeftPanel(@NotNull SithTermFxWidget widget) {
        widgetLeftPanels.remove(widget);
    }

    private void applyBottomPanel(@NotNull SithTermFxWidget widget) {
        if (bottomPanelFactory != null) {
            Region bottomPanel = bottomPanelFactory.apply(widget);
            if (bottomPanel != null) {
                widgetBottomPanels.put(widget, bottomPanel);
            }
        }
    }

    public void setWidgetBottomPanel(@NotNull SithTermFxWidget widget, @NotNull Region panel) {
        widgetBottomPanels.put(widget, panel);
    }

    public void removeWidgetBottomPanel(@NotNull SithTermFxWidget widget) {
        widgetBottomPanels.remove(widget);
    }

    /**
     * Detaches (true) or re-attaches (false) every widget's bottom panel. While detached, new split
     * cells also skip embedding the bottom panel, so the AI-agent panel can be hosted in a side dock.
     */
    public void setBottomPanelsDetached(boolean detached) {
        if (this.bottomPanelsDetached == detached) {
            return;
        }
        // A side dock shows the agent panels of the panes, which a zoom would hide.
        unzoom();
        this.bottomPanelsDetached = detached;
        for (SithTermFxWidget widget : getAllWidgets()) {
            if (detached) {
                detachBottomPanel(widget);
            } else {
                reattachBottomPanel(widget, widgetBottomPanels.get(widget));
            }
        }
    }

    public boolean isBottomPanelsDetached() {
        return bottomPanelsDetached;
    }

    /** Removes a widget's bottom panel from its split cell so it can be hosted elsewhere (side dock). */
    public void detachBottomPanel(@NotNull SithTermFxWidget widget) {
        Region panel = widgetBottomPanels.get(widget);
        VBox host = widgetBottomHosts.get(widget);
        if (panel != null && host != null) {
            host.getChildren().remove(panel);
        }
    }

    /** Re-inserts a widget's bottom panel into its split cell (no-op if already there or unknown). */
    public void reattachBottomPanel(@NotNull SithTermFxWidget widget, @Nullable Region panel) {
        Region target = panel != null ? panel : widgetBottomPanels.get(widget);
        VBox host = widgetBottomHosts.get(widget);
        if (target != null && host != null && !host.getChildren().contains(target)) {
            host.getChildren().add(target);
        }
    }

    public void closeAll() {
        unzoom();
        for (SithTermFxWidget widget : getAllWidgets()) {
            notifyWidgetClosed(widget);
        }
        if (rootCell != null) {
            rootCell.closeAll();
        }
        widgetLeftPanels.clear();
        widgetBottomPanels.clear();
        widgetBottomHosts.clear();
        widgetCloseButtons.clear();
        widgetOverlayHosts.clear();
    }

    private void refreshSplitCloseButtons() {
        boolean showButtons = rootCell != null && rootCell.countWidgets() > 1;
        List<SithTermFxWidget> activeWidgets = getAllWidgets();
        widgetCloseButtons.entrySet().removeIf(entry -> !activeWidgets.contains(entry.getKey()));
        widgetOverlayHosts.keySet().removeIf(w -> !activeWidgets.contains(w));
        for (Button button : widgetCloseButtons.values()) {
            button.setVisible(showButtons);
            button.setManaged(showButtons);
        }
        refreshPaneDecorations();
    }

    private class SplitCell {
        private final @Nullable SithTermFxWidget widget;
        private final @Nullable SplitPane splitPane;
        private final @Nullable SplitCell leftCell;
        private final @Nullable SplitCell rightCell;
        private final Region node;

        SplitCell(@NotNull SithTermFxWidget widget) {
            this.widget = widget;
            this.splitPane = null;
            this.leftCell = null;
            this.rightCell = null;
            Region leftPanel = widgetLeftPanels.get(widget);
            Region bottomPanel = widgetBottomPanels.get(widget);
            Region terminalContent;
            if (leftPanel != null) {
                HBox hbox = new HBox(leftPanel, widget.getPane());
                HBox.setHgrow(widget.getPane(), Priority.ALWAYS);
                hbox.setMinSize(0, 0);
                hbox.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                terminalContent = hbox;
            } else {
                terminalContent = widget.getPane();
            }
            // Always wrap in a VBox host so the bottom panel can be detached/re-attached at runtime
            // (the AI-agent panel can be docked to the side instead of the bottom). The bottom panel is
            // only embedded here when not currently detached.
            VBox bottomHost = new VBox(terminalContent);
            VBox.setVgrow(terminalContent, Priority.ALWAYS);
            bottomHost.setMinSize(0, 0);
            bottomHost.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            if (bottomPanel != null && !bottomPanelsDetached) {
                bottomHost.getChildren().add(bottomPanel);
            }
            widgetBottomHosts.put(widget, bottomHost);
            Region wrapperContent = bottomHost;
            StackPane wrapper = new StackPane(wrapperContent);
            wrapper.setMinSize(0, 0);
            wrapper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            VBox.setVgrow(wrapper, Priority.ALWAYS);
            wrapper.setUserData(widget);
            // The stylesheets draw the focus ring from this cell's :focus-within and :last-focused.
            wrapper.getStyleClass().add(PANE_CELL_STYLE_CLASS);
            widgetOverlayHosts.put(widget, wrapper);
            Button closeButton = new Button("x");
            closeButton.getStyleClass().add("split-close-button");
            closeButton.setFocusTraversable(false);
            closeButton.setMinSize(18, 18);
            closeButton.setPrefSize(18, 18);
            closeButton.setMaxSize(18, 18);
            closeButton.setStyle("-fx-font-size: 0.7692em; -fx-padding: 0; -fx-background-radius: 9;");
            closeButton.setOnAction(e -> {
                e.consume();
                closeSplit(widget);
            });
            StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
            StackPane.setMargin(closeButton, new javafx.geometry.Insets(4, 4, 0, 0));
            wrapper.getChildren().add(closeButton);
            widgetCloseButtons.put(widget, closeButton);
            this.node = wrapper;
            // No refreshSplitCloseButtons() here: it prunes both maps against the tree, which this
            // cell has not joined yet, so it would drop the entries just added. Whoever puts the cell
            // into rootCell refreshes once it is there.
        }

        SplitCell(@NotNull SplitCell left, @NotNull SplitCell right, @NotNull Orientation orientation) {
            this.widget = null;
            this.leftCell = left;
            this.rightCell = right;
            Region leftNode = left.getNode();
            Region rightNode = right.getNode();
            leftNode.setMinWidth(0);
            leftNode.setMinHeight(0);
            leftNode.setMaxWidth(Double.MAX_VALUE);
            leftNode.setMaxHeight(Double.MAX_VALUE);
            rightNode.setMinWidth(0);
            rightNode.setMinHeight(0);
            rightNode.setMaxWidth(Double.MAX_VALUE);
            rightNode.setMaxHeight(Double.MAX_VALUE);
            this.splitPane = new SplitPane(leftNode, rightNode);
            splitPane.setOrientation(orientation);
            splitPane.setDividerPositions(0.5);
            splitPane.setMinSize(0, 0);
            splitPane.setMaxWidth(Double.MAX_VALUE);
            splitPane.setMaxHeight(Double.MAX_VALUE);
            applyBackgroundStyle(splitPane);
            this.node = splitPane;
            Platform.runLater(() -> splitPane.setDividerPositions(0.5));
        }

        Region getNode() {
            return node;
        }

        @Nullable
        SplitCell replaceWidget(@NotNull SithTermFxWidget target, @NotNull SplitCell newCell,
                               @NotNull Orientation orientation) {
            return replaceWidget(target, newCell, orientation, false);
        }

        @Nullable
        SplitCell replaceWidget(@NotNull SithTermFxWidget target, @NotNull SplitCell newCell,
                               @NotNull Orientation orientation, boolean newCellFirst) {
            if (widget == target) {
                return newCellFirst
                        ? new SplitCell(newCell, this, orientation)
                        : new SplitCell(this, newCell, orientation);
            }
            if (leftCell != null && rightCell != null) {
                SplitCell newLeft = leftCell.replaceWidget(target, newCell, orientation, newCellFirst);
                if (newLeft != null) {
                    return new SplitCell(newLeft, rightCell, splitPane.getOrientation());
                }
                SplitCell newRight = rightCell.replaceWidget(target, newCell, orientation, newCellFirst);
                if (newRight != null) {
                    return new SplitCell(leftCell, newRight, splitPane.getOrientation());
                }
            }
            return null;
        }

        @Nullable
        ExtractResult extractWidget(@NotNull SithTermFxWidget target) {
            if (widget == target) {
                return new ExtractResult(this, null);
            }
            if (leftCell != null && rightCell != null) {
                ExtractResult leftResult = leftCell.extractWidget(target);
                if (leftResult != null) {
                    SplitCell newLeft = leftResult.replacement;
                    if (newLeft == null) {
                        return new ExtractResult(leftResult.extracted, rightCell);
                    }
                    return new ExtractResult(leftResult.extracted, new SplitCell(newLeft, rightCell, splitPane.getOrientation()));
                }
                ExtractResult rightResult = rightCell.extractWidget(target);
                if (rightResult != null) {
                    SplitCell newRight = rightResult.replacement;
                    if (newRight == null) {
                        return new ExtractResult(rightResult.extracted, leftCell);
                    }
                    return new ExtractResult(rightResult.extracted, new SplitCell(leftCell, newRight, splitPane.getOrientation()));
                }
            }
            return null;
        }

        @Nullable
        SplitCell removeWidget(@NotNull SithTermFxWidget target) {
            if (widget == target) {
                return null;
            }
            if (leftCell != null && rightCell != null) {
                SplitCell newLeft = leftCell.removeWidget(target);
                if (newLeft != leftCell) {
                    if (newLeft == null) {
                        return rightCell;
                    }
                    return new SplitCell(newLeft, rightCell, splitPane.getOrientation());
                }
                SplitCell newRight = rightCell.removeWidget(target);
                if (newRight != rightCell) {
                    if (newRight == null) {
                        return leftCell;
                    }
                    return new SplitCell(leftCell, newRight, splitPane.getOrientation());
                }
            }
            return this;
        }

        /** The branch cell whose split control holds {@code target}'s leaf, or {@code null}. */
        @Nullable
        SplitCell parentOf(@NotNull SithTermFxWidget target) {
            if (leftCell == null || rightCell == null) {
                return null;
            }
            if (leftCell.widget == target || rightCell.widget == target) {
                return this;
            }
            SplitCell found = leftCell.parentOf(target);
            return found != null ? found : rightCell.parentOf(target);
        }

        /** Adds the split control of this cell and of every cell below it to {@code splits}. */
        void collectSplitPanes(@NotNull List<SplitPane> splits) {
            if (splitPane != null) {
                splits.add(splitPane);
            }
            if (leftCell != null) leftCell.collectSplitPanes(splits);
            if (rightCell != null) rightCell.collectSplitPanes(splits);
        }

        int countWidgets() {
            if (widget != null) return 1;
            return (leftCell != null ? leftCell.countWidgets() : 0)
                    + (rightCell != null ? rightCell.countWidgets() : 0);
        }

        void refreshBackgroundStyle() {
            if (splitPane != null) {
                applyBackgroundStyle(splitPane);
            }
            if (leftCell != null) leftCell.refreshBackgroundStyle();
            if (rightCell != null) rightCell.refreshBackgroundStyle();
        }

        void closeAll() {
            if (widget != null) {
                try {
                    widget.close();
                } catch (Exception e) {
                    logger.debug("Error closing widget: {}", e.getMessage());
                }
            }
            if (leftCell != null) leftCell.closeAll();
            if (rightCell != null) rightCell.closeAll();
        }
    }

    private static final String DROP_ZONE_OVERLAY_KEY = "sithtermfx.dropZoneOverlay";

    /**
     * The four drop zones shown while a pane is dragged onto another one, in the target pane's
     * {@link PaneOverlayLayer#DROP_ZONES} layer, above its focus ring. Like the layer it is
     * mouse-transparent: the wrapper's own drag filters (see {@code attachDragAndDropToCell}) accept
     * the drag, track the placement and drop the pane.
     */
    private static final class DropZoneOverlay {
        private final Pane pane;
        private final Pane layer;
        private final Region wrapper;
        private final SithTermFxWidget targetWidget;
        private final String sourceId;
        private final TerminalSplitPane splitPane;
        private final ChangeListener<Number> fitToLayer;
        private Placement currentPlacement;
        private final Region zoneAbove;
        private final Region zoneBelow;
        private final Region zoneLeft;
        private final Region zoneRight;

        private static final String STYLE_ZONE = "-fx-background-color: rgba(64,128,255,0.25);";
        private static final String STYLE_ZONE_HIGHLIGHT = "-fx-background-color: rgba(64,128,255,0.5);";

        DropZoneOverlay(Region wrapper, Pane layer, SithTermFxWidget targetWidget, String sourceId,
                        TerminalSplitPane splitPane) {
            this.wrapper = wrapper;
            this.layer = layer;
            this.targetWidget = targetWidget;
            this.sourceId = sourceId;
            this.splitPane = splitPane;
            this.pane = new Pane();
            pane.setManaged(false);
            pane.setMouseTransparent(true);
            pane.setPickOnBounds(false);
            zoneAbove = new Region();
            zoneBelow = new Region();
            zoneLeft = new Region();
            zoneRight = new Region();
            zoneAbove.setStyle(STYLE_ZONE);
            zoneBelow.setStyle(STYLE_ZONE);
            zoneLeft.setStyle(STYLE_ZONE);
            zoneRight.setStyle(STYLE_ZONE);
            pane.getChildren().addAll(zoneAbove, zoneBelow, zoneLeft, zoneRight);
            currentPlacement = null;
            pane.widthProperty().addListener((o, a, b) -> layoutZones());
            pane.heightProperty().addListener((o, a, b) -> layoutZones());
            fitToLayer = (o, a, b) -> fit();
        }

        /** Adds the zones to the layer, as large as the layer and so as the pane. */
        void attach() {
            layer.widthProperty().addListener(fitToLayer);
            layer.heightProperty().addListener(fitToLayer);
            layer.getChildren().add(pane);
            fit();
        }

        /** Removes the zones; the layer stays with the pane for the next drag. */
        void detach() {
            layer.widthProperty().removeListener(fitToLayer);
            layer.heightProperty().removeListener(fitToLayer);
            layer.getChildren().remove(pane);
        }

        private void fit() {
            pane.resize(layer.getWidth(), layer.getHeight());
            layoutZones();
        }

        private void layoutZones() {
            double w = pane.getWidth();
            double h = pane.getHeight();
            if (w <= 0 || h <= 0) return;
            double topH = h * 0.25;
            double bottomH = h * 0.25;
            double leftW = w * 0.25;
            double rightW = w * 0.25;
            double midH = h - topH - bottomH;
            zoneAbove.resizeRelocate(0, 0, w, topH);
            zoneBelow.resizeRelocate(0, h - bottomH, w, bottomH);
            zoneLeft.resizeRelocate(0, topH, leftW, midH);
            zoneRight.resizeRelocate(w - rightW, topH, rightW, midH);
        }

        void updatePlacement(double x, double y) {
            double w = wrapper.getWidth();
            double h = wrapper.getHeight();
            if (w <= 0 || h <= 0) return;
            Placement next = null;
            if (y < h * 0.25) next = Placement.ABOVE;
            else if (y > h * 0.75) next = Placement.BELOW;
            else if (x < w * 0.25) next = Placement.LEFT_OF;
            else if (x > w * 0.75) next = Placement.RIGHT_OF;
            if (next != currentPlacement) {
                currentPlacement = next;
                zoneAbove.setStyle(next == Placement.ABOVE ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
                zoneBelow.setStyle(next == Placement.BELOW ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
                zoneLeft.setStyle(next == Placement.LEFT_OF ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
                zoneRight.setStyle(next == Placement.RIGHT_OF ? STYLE_ZONE_HIGHLIGHT : STYLE_ZONE);
            }
        }

        boolean tryDrop() {
            if (currentPlacement == null) return false;
            SithTermFxWidget source = splitPane.findWidgetByIdentity(sourceId);
            if (source == null) return false;
            splitPane.moveWidget(source, targetWidget, currentPlacement);
            return true;
        }

        static void show(Region wrapper, SithTermFxWidget targetWidget, String sourceId, TerminalSplitPane splitPane) {
            hide(wrapper);
            Pane layer = splitPane.paneOverlay(targetWidget, PaneOverlayLayer.DROP_ZONES);
            if (layer == null) {
                return;
            }
            DropZoneOverlay overlay = new DropZoneOverlay(wrapper, layer, targetWidget, sourceId, splitPane);
            wrapper.getProperties().put(DROP_ZONE_OVERLAY_KEY, overlay);
            overlay.attach();
        }

        static void hide(Region wrapper) {
            Object old = wrapper.getProperties().remove(DROP_ZONE_OVERLAY_KEY);
            if (old instanceof DropZoneOverlay overlay) {
                overlay.detach();
            }
        }

        static void updatePlacement(Region wrapper, double x, double y) {
            Object o = wrapper.getProperties().get(DROP_ZONE_OVERLAY_KEY);
            if (o instanceof DropZoneOverlay) {
                ((DropZoneOverlay) o).updatePlacement(x, y);
            }
        }

        static boolean tryDrop(Region wrapper, TerminalSplitPane splitPane) {
            Object o = wrapper.getProperties().get(DROP_ZONE_OVERLAY_KEY);
            if (o instanceof DropZoneOverlay) {
                DropZoneOverlay overlay = (DropZoneOverlay) o;
                boolean ok = overlay.tryDrop();
                hide(wrapper);
                return ok;
            }
            return false;
        }
    }
}
