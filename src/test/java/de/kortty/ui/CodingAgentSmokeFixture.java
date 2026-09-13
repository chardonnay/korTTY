package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentNavigator;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.CodingAgentTestHarness;
import de.kortty.codingagent.FakePaneAccess;
import de.kortty.codingagent.PaneAccess;
import de.kortty.codingagent.PaneLocation;
import de.kortty.codingagent.PaneLocator;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RecordingTtyConnector;
import de.kortty.codingagent.TerminalScreenCapture;
import de.kortty.core.LanguageManager;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The demo scenario shared by {@link CodingAgentUiSmoke} and {@link CodingAgentScreenshotGenerator}:
 * three agents across two tabs — Claude Code BLOCKED for 2:14 on a permission prompt, Codex WORKING
 * for 0:47 and Gemini CLI DONE-until-seen for 0:12 — fed through a real
 * {@link CodingAgentTestHarness}, with a scripted {@link PaneAccess} (one
 * {@link RecordingTtyConnector} per pane), a scripted {@link PaneLocator} (window / tab / pane names
 * and demo working directories under {@code ~/proj}) and a recording
 * {@link CodingAgentNavigator.PaneFocuser}.
 *
 * <p>The harness clock is aligned with the wall clock before the agents are added, so the panel
 * (registry clock) and the dashboard ({@code System.currentTimeMillis()}) show the same durations.
 */
final class CodingAgentSmokeFixture implements PaneLocator, PaneAccess, CodingAgentNavigator.PaneFocuser {

    static final String TAB_API = "api";
    static final String TAB_WEB = "web";
    static final String CLAUDE_EVIDENCE = "Do you want to proceed? (y/n)";
    static final String CODEX_EVIDENCE = "Running tests: 42 passed, 3 pending";
    static final String GEMINI_EVIDENCE = "Done. 3 files changed.";
    static final long CLAUDE_BLOCKED_SECONDS = 134;
    static final long CODEX_WORKING_SECONDS = 47;
    static final long GEMINI_DONE_SECONDS = 12;

    /** The pane identities of the three demo agents. */
    record PaneIds(PaneRef claude, PaneRef gemini, PaneRef codex) {
        /** Synthetic ids for the panel/strip scenario (no TerminalTab involved). */
        static PaneIds synthetic() {
            return new PaneIds(new PaneRef("tab-api", "pane-claude"), new PaneRef("tab-api", "pane-gemini"),
                new PaneRef("tab-web", "pane-codex"));
        }
    }

    private final CodingAgentTestHarness harness;
    private final FakePaneAccess panes = new FakePaneAccess();
    private final Map<PaneRef, RecordingTtyConnector> connectors = new LinkedHashMap<>();
    private final Map<PaneRef, PaneLocation> locations = new LinkedHashMap<>();
    private final List<PaneRef> focused = new ArrayList<>();
    private final CodingAgentActions actions;
    private final CodingAgentNavigator navigator;
    private PaneIds ids;
    private PaneRef currentPane;

    CodingAgentSmokeFixture(CodingAgentTestHarness harness) {
        this.harness = Objects.requireNonNull(harness, "harness");
        this.actions = new CodingAgentActions(harness.registry(), this, null);
        this.navigator = new CodingAgentNavigator(harness.registry(), this, this);
        panes.setHostShortcutCommandName("agent");
        panes.setIntercept(line -> line != null && (line.equals("agent") || line.startsWith("agent ")));
    }

    /** English UI text; must run before any I18n-backed node is built. */
    static void initI18n() {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);
    }

    // ---- scenario -------------------------------------------------------------------------------

    /**
     * Adds the three agents under the given pane ids and connects a recording connector to each.
     * Claude Code (window 1, tab api, pane 1) is BLOCKED on a permission prompt, Gemini CLI (window 1,
     * tab api, pane 2) is DONE-until-seen, Codex (window 2, tab web) is WORKING.
     */
    void populate(PaneIds ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
        String home = System.getProperty("user.home");
        locations.put(ids.claude(), new PaneLocation(0, 2, TAB_API, 0, 2, home + "/proj/api"));
        locations.put(ids.gemini(), new PaneLocation(0, 2, TAB_API, 1, 2, home + "/proj/api"));
        locations.put(ids.codex(), new PaneLocation(1, 2, TAB_WEB, 0, 1, home + "/proj/web"));

        long now = System.currentTimeMillis();
        harness.focus().setAnyWindowFocused(true);
        setClock(now - CLAUDE_BLOCKED_SECONDS * 1000L);
        harness.addAgent(ids.claude().tabId(), ids.claude().paneId(), CodingAgentKind.CLAUDE_CODE,
            CodingAgentState.BLOCKED, CLAUDE_EVIDENCE);
        setClock(now - 120_000L);
        harness.addAgent(ids.gemini().tabId(), ids.gemini().paneId(), CodingAgentKind.GEMINI_CLI,
            CodingAgentState.WORKING, "Refactoring src/app.ts");
        setClock(now - CODEX_WORKING_SECONDS * 1000L);
        harness.addAgent(ids.codex().tabId(), ids.codex().paneId(), CodingAgentKind.CODEX,
            CodingAgentState.WORKING, CODEX_EVIDENCE);
        // Gemini leaves WORKING while nobody looks at its pane: the registry synthesises DONE-until-seen.
        setClock(now - GEMINI_DONE_SECONDS * 1000L);
        harness.focus().setSeen(ids.gemini(), false);
        harness.setState(ids.gemini(), CodingAgentState.IDLE, GEMINI_EVIDENCE);
        setClock(now);

        for (PaneRef pane : List.of(ids.claude(), ids.gemini(), ids.codex())) {
            connectors.put(pane, panes.connect(pane));
        }
        currentPane = ids.codex();
    }

    private void setClock(long millis) {
        harness.advance(millis - harness.nowMillis());
    }

    /**
     * Builds a never-connected LOCAL_SHELL {@link TerminalTab} named {@code displayName} and adds it to
     * {@code tabPane}. Its TerminalView id is the tab id of the panes hosted there.
     */
    TerminalTab addTab(TabPane tabPane, String displayName) {
        ServerConnection connection = new ServerConnection();
        connection.setName(displayName);
        connection.setHost("localhost");
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellWorkingDirectory(System.getProperty("user.home") + "/proj/" + displayName);
        TerminalTab tab = new TerminalTab(connection, null);
        tabPane.getTabs().add(tab);
        return tab;
    }

    /**
     * The pane reference of the {@code index}-th widget of {@code tab}, or — when the tab has fewer
     * widgets — a synthetic pane id under the tab's TerminalView id (the dashboard tolerates an entry
     * whose widget cannot be resolved).
     */
    static PaneRef paneOf(TerminalTab tab, int index) {
        TerminalView view = tab.getTerminalView();
        List<SithTermFxWidget> widgets = view.getOrderedWidgets();
        if (index < widgets.size() && widgets.get(index) != null) {
            return new PaneRef(view.getTerminalViewId(), TerminalScreenCapture.paneIdOf(widgets.get(index)));
        }
        return new PaneRef(view.getTerminalViewId(), "synthetic-pane-" + (index + 1));
    }

    // ---- snapshot helpers -----------------------------------------------------------------------

    /**
     * Puts {@code scene} on a Stage that is never shown (the pulse timers stay off; frames come from
     * {@code renderFrameForTest}) and forces one layout pass via {@code scene.snapshot(null)}.
     */
    static Stage stageFor(Scene scene) {
        Stage stage = new Stage();
        stage.setScene(scene);
        scene.snapshot(null);
        return stage;
    }

    /** Snapshots the scene root at {@code scale} (2 = HiDPI-friendly docs screenshots) over {@code fill}. */
    static WritableImage snapshot(Scene scene, double scale, Color fill) {
        scene.snapshot(null); // force layout of everything added since the last pass
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(scale, scale));
        parameters.setFill(fill != null ? fill : Color.TRANSPARENT);
        return scene.getRoot().snapshot(parameters, null);
    }

    static void writePng(Image image, File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        if (!ImageIO.write(buffered, "png", file)) {
            throw new IOException("No PNG writer for " + file);
        }
        System.out.println("Snapshot written: " + file.getAbsolutePath());
    }

    // ---- accessors ------------------------------------------------------------------------------

    CodingAgentTestHarness harness() {
        return harness;
    }

    CodingAgentRegistry registry() {
        return harness.registry();
    }

    CodingAgentActions actions() {
        return actions;
    }

    CodingAgentNavigator navigator() {
        return navigator;
    }

    PaneIds ids() {
        return ids;
    }

    RecordingTtyConnector connector(PaneRef pane) {
        return connectors.get(pane);
    }

    /** Every pane handed to {@link #focus(PaneRef)} so far, in order. */
    List<PaneRef> focused() {
        return new ArrayList<>(focused);
    }

    void setCurrentPane(PaneRef pane) {
        this.currentPane = pane;
    }

    /** Detaches every agent (PANE_DETACHED removals) so the registry becomes empty. */
    void removeAllAgents() {
        if (ids == null) {
            return;
        }
        for (PaneRef pane : List.of(ids.claude(), ids.gemini(), ids.codex())) {
            harness.remove(pane);
        }
    }

    // ---- PaneLocator ----------------------------------------------------------------------------

    @Override
    public Optional<PaneLocation> locate(PaneRef pane) {
        return Optional.ofNullable(locations.get(pane));
    }

    // ---- PaneAccess -----------------------------------------------------------------------------

    @Override
    public Optional<TtyConnector> connectorFor(PaneRef pane) {
        return panes.connectorFor(pane);
    }

    @Override
    public boolean isBracketedPasteEnabled(PaneRef pane) {
        return panes.isBracketedPasteEnabled(pane);
    }

    @Override
    public boolean wouldHostShortcutIntercept(String firstLine) {
        return panes.wouldHostShortcutIntercept(firstLine);
    }

    @Override
    public String hostShortcutCommandName() {
        return panes.hostShortcutCommandName();
    }

    // ---- PaneFocuser ----------------------------------------------------------------------------

    @Override
    public boolean focus(PaneRef pane) {
        if (pane == null || harness.registry().entry(pane).isEmpty()) {
            return false;
        }
        focused.add(pane);
        currentPane = pane;
        return true;
    }

    @Override
    public Optional<PaneRef> currentPane() {
        return Optional.ofNullable(currentPane);
    }
}
