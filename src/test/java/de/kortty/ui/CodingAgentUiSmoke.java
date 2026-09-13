package de.kortty.ui;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.CodingAgentTestHarness;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.BadgeIconRenderer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke for the Stage-2 coding-agents UI: builds the real {@link CodingAgentPanel},
 * {@link CodingAgentStatusStrip}, {@link CodingAgentPanelDockManager} and {@link DashboardView}
 * (over two never-connected LOCAL_SHELL {@link TerminalTab}s) against the
 * {@link CodingAgentSmokeFixture} scenario — no MainWindow, no KorTTYApplication — exercises the
 * quick keys and the prompt box through {@code CodingAgentActions} into recording connectors,
 * renders the {@link BadgeIconRenderer} and snapshots every surface to
 * {@code build/smoke/coding-agent-*.png}.
 *
 * <p>Stages are never shown, so every pulse timer stays off and frames come from
 * {@code renderFrameForTest}. {@code user.home} is redirected to a throwaway directory before
 * anything touches {@code TerminalTab} (KorTTYApplication resolves its config directory in a static
 * initializer). Run via the {@code codingAgentUiSmoke} Gradle task. Exit 0 = OK, 1 = failure,
 * 2 = timeout.
 */
public final class CodingAgentUiSmoke {

    private static final double FROZEN_T = 100.3;
    private static final long TIMEOUT_SECONDS = 120;

    private CodingAgentUiSmoke() {
    }

    public static void main(String[] args) throws Exception {
        // FIRST: nothing below may touch KorTTYApplication with the real profile directory.
        Path sandbox = Files.createTempDirectory("kortty-coding-agent-smoke-home");
        System.setProperty("user.home", sandbox.toString());
        Locale.setDefault(Locale.ENGLISH);
        File outputDir = new File(System.getProperty("kortty.smoke.outputDir", "build/smoke"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                CodingAgentSmokeFixture.initI18n();
                checkPanelAndStrip(outputDir);
                checkDockManager();
                checkBadge(outputDir);
                checkDashboard(outputDir);
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("codingAgentUiSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("codingAgentUiSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("codingAgentUiSmoke OK");
        System.exit(0);
    }

    // ---- panel + strip ----------------------------------------------------------------------------

    private static void checkPanelAndStrip(File outputDir) throws Exception {
        try (CodingAgentTestHarness harness = new CodingAgentTestHarness()) {
            CodingAgentSmokeFixture fixture = new CodingAgentSmokeFixture(harness);
            CodingAgentSmokeFixture.PaneIds ids = CodingAgentSmokeFixture.PaneIds.synthetic();
            fixture.populate(ids);
            require(fixture.registry().summary().blocked() == 1 && fixture.registry().summary().working() == 1
                && fixture.registry().summary().done() == 1, "scenario must be 1 blocked / 1 working / 1 done, was "
                + fixture.registry().summary());

            checkPanel(fixture, outputDir);
            checkStrip(fixture, outputDir);
        }
    }

    private static void checkPanel(CodingAgentSmokeFixture fixture, File outputDir) throws Exception {
        CodingAgentSmokeFixture.PaneIds ids = fixture.ids();
        CodingAgentPanel panel = new CodingAgentPanel(fixture.registry(), fixture.actions(), fixture.navigator(),
            fixture, fixture.harness()::nowMillis);
        Scene scene = new Scene(panel, 420, 520);
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
        CodingAgentSmokeFixture.stageFor(scene);
        panel.bind();
        scene.snapshot(null);

        // Rows: BLOCKED first, then longest in state first (Codex 0:47 before Gemini 0:12).
        ListView<?> list = (ListView<?>) panel.lookup(".coding-agent-list");
        require(list != null, "the panel must contain the .coding-agent-list ListView");
        List<?> items = list.getItems();
        require(items.size() == 3, "3 rows expected, found " + items.size());
        require(entry(items.get(0)).pane().equals(ids.claude()), "the BLOCKED agent must be the first row");
        require(entry(items.get(0)).state() == CodingAgentState.BLOCKED, "first row must be BLOCKED");
        require(entry(items.get(1)).pane().equals(ids.codex()), "second row must be Codex (longest in state)");
        require(entry(items.get(2)).pane().equals(ids.gemini()), "third row must be Gemini (shortest in state)");

        String summary = CodingAgentGlyphs.summaryText(fixture.registry().summary());
        require("✋ 1 · ⚡ 1 · ✓ 1".equals(summary), "unexpected summary text " + summary);
        require(labelWithText(panel, summary) != null, "the header must show the summary " + summary);

        // Send is disabled while the target (the first row, BLOCKED) waits for a decision.
        Button send = buttonWithText(panel, I18n.get("codingAgent.panel.prompt.send"));
        require(send != null, "the Send button was not found");
        require(send.isDisabled(), "Send must be disabled for a BLOCKED target");

        // Quick key 'y' in the BLOCKED row writes exactly "y" to that pane's connector.
        ListCell<?> claudeCell = cellFor(list, ids.claude());
        require(claudeCell != null, "no visible cell for the Claude row");
        Button yButton = buttonWithText(claudeCell, "y");
        require(yButton != null && !yButton.isDisabled(), "the y button must exist and be enabled (pane connected)");
        require(yButton.getStyle() != null && yButton.getStyle().contains("-fx-background-color: #f59e0b"),
            "answer buttons of a BLOCKED row carry the explicit blocked accent, style was " + yButton.getStyle());
        yButton.fire();
        require("y".equals(fixture.connector(ids.claude()).writtenText()),
            "clicking y must write 'y', wrote " + fixture.connector(ids.claude()).writtenText());
        require(fixture.connector(ids.codex()).written().length == 0, "y must not reach another pane");
        require(labelWithText(panel, I18n.get("codingAgent.panel.sent", "Claude Code")) != null,
            "the status line must confirm 'Sent to Claude Code'");

        // Explain opens the drawer with the detection explanation of that row.
        Button explain = buttonWithText(claudeCell, I18n.get("codingAgent.panel.explain"));
        require(explain != null, "the Explain button was not found");
        explain.fire();
        scene.snapshot(null);
        claudeCell = cellFor(list, ids.claude());
        TextArea drawer = visibleTextArea(claudeCell);
        require(drawer != null, "the Explain drawer must be visible after clicking Explain");
        require(drawer.getText().contains(CodingAgentSmokeFixture.CLAUDE_EVIDENCE),
            "the Explain text must quote the evidence line, was: " + drawer.getText());
        require(drawer.getText().contains("2:14"), "the Explain text must state the time in state, was: "
            + drawer.getText());

        // Focus goes through the navigator to the fixture's PaneFocuser.
        Button focus = buttonWithText(claudeCell, I18n.get("codingAgent.panel.focus"));
        require(focus != null, "the Focus button was not found");
        focus.fire();
        require(fixture.focused().equals(List.of(ids.claude())), "Focus must hand the pane to the focuser");

        // Rename: the alias replaces the name and the kind tag appears.
        fixture.actions().rename(ids.claude(), "reviewer");
        scene.snapshot(null);
        claudeCell = cellFor(list, ids.claude());
        require(labelWithText(claudeCell, "reviewer") != null, "the row must show the alias after rename");
        require(labelWithText(claudeCell, CodingAgentKind.CLAUDE_CODE.displayName()) != null,
            "the kind tag must show the agent kind next to the alias");
        require("reviewer".equals(fixture.registry().entry(ids.claude()).orElseThrow().alias()), "alias not stored");

        // Pulse: never shown → timer off; a frame still renders via the seam.
        require(!panel.isPulseTimerRunning(), "the pulse timer must be off on a never-shown stage");
        panel.renderFrameForTest(FROZEN_T);

        WritableImage image = CodingAgentSmokeFixture.snapshot(scene, 1, Color.web("#1e1e1e"));
        requireNotBlank(image, "panel");
        CodingAgentSmokeFixture.writePng(image, new File(outputDir, "coding-agent-panel.png"));

        // Prompt box: a host-shortcut first line is refused with the mapped message and writes nothing.
        panel.selectPane(ids.codex());
        TextArea prompt = promptArea(panel);
        require(prompt != null, "the prompt TextArea was not found");
        require(!send.isDisabled(), "Send must be enabled for the WORKING Codex target");
        prompt.setText("agent summarise this repo");
        send.fire();
        require(labelWithText(panel, I18n.get("codingAgent.panel.prompt.hostShortcut", "agent")) != null,
            "the status line must show the host-shortcut message");
        require(fixture.connector(ids.codex()).written().length == 0, "a refused prompt must write nothing");
        require("agent summarise this repo".equals(prompt.getText()), "a refused prompt keeps its text");

        // A plain single-line prompt is sent as text + Enter and the box is cleared.
        prompt.setText("run the tests again");
        send.fire();
        require("run the tests again\r".equals(fixture.connector(ids.codex()).writtenText()),
            "single-line prompt must be text + CR, was " + fixture.connector(ids.codex()).writtenText());
        require(prompt.getText().isEmpty(), "the prompt box must be cleared after a successful send");
        require(labelWithText(panel, I18n.get("codingAgent.panel.sent", "Codex")) != null,
            "the status line must confirm 'Sent to Codex'");

        // Send follows the target's state: BLOCKED disables, leaving BLOCKED enables.
        panel.selectPane(ids.claude());
        require(send.isDisabled(), "Send must be disabled again for the BLOCKED target");
        fixture.harness().setState(ids.claude(), CodingAgentState.WORKING, "Editing src/main.rs");
        require(!send.isDisabled(), "Send must be enabled once the target leaves BLOCKED");
        fixture.harness().setState(ids.claude(), CodingAgentState.BLOCKED, CodingAgentSmokeFixture.CLAUDE_EVIDENCE);
        require(send.isDisabled(), "Send must be disabled when the target is BLOCKED again");

        panel.dispose();
        require(!panel.isBound(), "dispose must unbind the panel");
        require(!panel.isPulseTimerRunning(), "dispose must stop the pulse timer");
    }

    private static void checkStrip(CodingAgentSmokeFixture fixture, File outputDir) throws Exception {
        CodingAgentStatusStrip strip = new CodingAgentStatusStrip(fixture.registry());
        AtomicBoolean activated = new AtomicBoolean();
        strip.setOnActivate(() -> activated.set(true));

        Label ready = new Label(I18n.get("app.ready"));
        ready.setStyle("-fx-text-fill: #cccccc;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusRow = new HBox(8, ready, spacer, strip);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        VBox statusBar = new VBox(statusRow);
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        Scene scene = new Scene(statusBar, 600, 36);
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
        CodingAgentSmokeFixture.stageFor(scene);

        require(!strip.isVisible() && !strip.isManaged(), "the strip starts hidden until attached");
        strip.attach();
        strip.applyTheme("#2d2d2d", "#cccccc");
        scene.snapshot(null);
        require(strip.isVisible() && strip.isManaged(), "the strip must show while agents are known");
        String expected = CodingAgentGlyphs.summaryText(fixture.registry().summary());
        String actual = stripText(strip);
        require(expected.equals(actual), "strip text must equal the summary '" + expected + "', was '" + actual + "'");
        require("✋ 1 · ⚡ 1 · ✓ 1".equals(actual), "unexpected strip text " + actual);

        Event.fireEvent(strip, primaryClick());
        require(activated.get(), "a primary click must fire the activate handler");

        require(!strip.isPulseTimerRunning(), "the strip pulse timer must be off on a never-shown stage");
        strip.renderFrameForTest(FROZEN_T);
        WritableImage image = CodingAgentSmokeFixture.snapshot(scene, 1, Color.web("#2d2d2d"));
        requireNotBlank(image, "strip");
        CodingAgentSmokeFixture.writePng(image, new File(outputDir, "coding-agent-strip.png"));

        fixture.removeAllAgents();
        require(fixture.registry().summary().isEmpty(), "removing every agent must empty the registry");
        require(!strip.isVisible() && !strip.isManaged(), "the strip must hide when the registry empties");

        strip.dispose();
        require(!strip.isPulseTimerRunning(), "dispose must stop the strip pulse timer");
    }

    // ---- dock manager -----------------------------------------------------------------------------

    private static void checkDockManager() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        List<CodingAgentPanelDockManager.Placement> seen = new ArrayList<>();
        manager.addPlacementListener(seen::add);
        require(manager.getPlacement() == CodingAgentPanelDockManager.Placement.HIDDEN, "starts HIDDEN");
        require(!manager.isDocked(), "not docked at start");
        manager.toggle(CodingAgentPanelDockManager.Placement.LEFT);
        require(manager.getPlacement() == CodingAgentPanelDockManager.Placement.LEFT, "toggle(LEFT) docks left");
        manager.toggle(CodingAgentPanelDockManager.Placement.LEFT);
        require(manager.getPlacement() == CodingAgentPanelDockManager.Placement.HIDDEN, "toggle(LEFT) again hides");
        require(manager.getLastDockedSide() == CodingAgentPanelDockManager.Placement.LEFT, "last side remembered");
        manager.toggleVisible();
        require(manager.getPlacement() == CodingAgentPanelDockManager.Placement.LEFT, "toggleVisible re-opens the last side");
        manager.setPlacement(CodingAgentPanelDockManager.Placement.RIGHT);
        require(manager.isDocked() && manager.getPlacement() == CodingAgentPanelDockManager.Placement.RIGHT,
            "setPlacement(RIGHT)");
        manager.toggleVisible();
        require(manager.getPlacement() == CodingAgentPanelDockManager.Placement.HIDDEN, "toggleVisible hides");
        manager.setPreferredWidth(50);
        require(manager.getPreferredWidth() == CodingAgentPanelDockManager.MIN_WIDTH, "width clamps to MIN");
        require(seen.equals(List.of(
            CodingAgentPanelDockManager.Placement.LEFT, CodingAgentPanelDockManager.Placement.HIDDEN,
            CodingAgentPanelDockManager.Placement.LEFT, CodingAgentPanelDockManager.Placement.RIGHT,
            CodingAgentPanelDockManager.Placement.HIDDEN)), "placement listener sequence was " + seen);
    }

    // ---- badge ------------------------------------------------------------------------------------

    private static void checkBadge(File outputDir) throws Exception {
        Image base = new Image(Objects.requireNonNull(
            CodingAgentUiSmoke.class.getResource("/icon/kortty_icon.png"), "app icon").toExternalForm());
        Image badged = BadgeIconRenderer.render(base, 3, 256);
        require((int) badged.getWidth() == 256 && (int) badged.getHeight() == 256, "badge must be 256x256");
        // Inside the bubble (centre 198, radius 52) but left of the glyph: the bubble red.
        Color inBubble = badged.getPixelReader().getColor(158, 198);
        require(inBubble.getRed() > 0.6 && inBubble.getGreen() < 0.4 && inBubble.getBlue() < 0.4,
            "bottom-right bubble must be red, was " + inBubble);
        Color glyph = badged.getPixelReader().getColor(198, 198);
        require(glyph.getBrightness() > 0.8, "the count glyph must be painted white at the bubble centre, was " + glyph);
        Image plain = BadgeIconRenderer.render(base, 0, 256);
        require((int) plain.getWidth() == 256 && (int) plain.getHeight() == 256, "plain icon must be 256x256");
        Color plainPixel = plain.getPixelReader().getColor(158, 198);
        require(!(plainPixel.getRed() > 0.6 && plainPixel.getGreen() < 0.4), "count 0 must draw no bubble");
        // Away from the bubble both renderings are the scaled base icon.
        Color a = badged.getPixelReader().getColor(64, 64);
        Color b = plain.getPixelReader().getColor(64, 64);
        require(a.equals(b), "outside the bubble the badged icon must equal the plain one");
        CodingAgentSmokeFixture.writePng(badged, new File(outputDir, "coding-agent-badge.png"));
    }

    // ---- dashboard --------------------------------------------------------------------------------

    private static void checkDashboard(File outputDir) throws Exception {
        try (CodingAgentTestHarness harness = new CodingAgentTestHarness()) {
            CodingAgentSmokeFixture fixture = new CodingAgentSmokeFixture(harness);
            TabPane tabPane = new TabPane();
            TerminalTab apiTab = fixture.addTab(tabPane, CodingAgentSmokeFixture.TAB_API);
            TerminalTab webTab = fixture.addTab(tabPane, CodingAgentSmokeFixture.TAB_WEB);
            PaneRef claude = CodingAgentSmokeFixture.paneOf(apiTab, 0);
            PaneRef gemini = CodingAgentSmokeFixture.paneOf(apiTab, 1); // synthetic second pane of the same tab
            PaneRef codex = CodingAgentSmokeFixture.paneOf(webTab, 0);
            require(claude.tabId().equals(gemini.tabId()) && !claude.paneId().equals(gemini.paneId()),
                "the two api panes must share the tab id");
            fixture.populate(new CodingAgentSmokeFixture.PaneIds(claude, gemini, codex));

            DashboardView dashboard = new DashboardView(tabPane, (tab, action) -> { }, connection -> null);
            dashboard.setCodingAgentRegistry(fixture.registry());
            dashboard.setPaneLocator(fixture);
            dashboard.setPaneActionHandler((tab, widget, pane, action) -> { });
            Scene scene = new Scene(dashboard, 380, 300);
            AppDesignStyleSupport.registerApplicationBaseStyles(scene);
            CodingAgentSmokeFixture.stageFor(scene);
            dashboard.refresh();
            scene.snapshot(null);

            TreeView<?> tree = (TreeView<?>) dashboard.lookup(".dashboard-tree");
            require(tree != null, "the .dashboard-tree TreeView was not found");
            List<TreeCell<?>> cells = new ArrayList<>();
            for (Node node : tree.lookupAll(".tree-cell")) {
                if (node instanceof TreeCell<?> cell && !cell.isEmpty() && cell.getItem() != null) {
                    cells.add(cell);
                }
            }
            require(cells.size() >= 4, "expected the window row, two connection rows and a pane row, found "
                + cells.size() + " rows: " + rowTexts(cells));

            TreeCell<?> blocked = null;
            TreeCell<?> rollupRow = null;
            TreeCell<?> paneRow = null;
            String rollupText = CodingAgentGlyphs.rollupText(1, 1, 1);
            String paneTitle = I18n.get("dashboard.paneTitle", 1);
            for (TreeCell<?> cell : cells) {
                Label rowName = visibleLabelWithStyle(cell, "dashboard-node-name");
                // The MAIN_WINDOW container row carries the accent of its most urgent child too; the
                // assertion targets the connection row itself.
                if (cell.getStyleClass().contains("dashboard-row-agent-blocked") && blocked == null
                    && rowName != null && CodingAgentSmokeFixture.TAB_API.equals(rowName.getText())) {
                    blocked = cell;
                }
                Label rollup = visibleLabelWithStyle(cell, "dashboard-rollup-chip");
                if (rollup != null && rollupText.equals(rollup.getText()) && rollupRow == null) {
                    rollupRow = cell;
                }
                Label name = visibleLabelWithStyle(cell, "dashboard-node-name");
                if (name != null && name.getText() != null && name.getText().startsWith(paneTitle) && paneRow == null) {
                    paneRow = cell;
                }
            }
            require(blocked != null, "the api connection row must carry dashboard-row-agent-blocked; rows: "
                + rowTexts(cells));
            require(lookupWithStyle(blocked, "dashboard-agent-chip-blocked") != null,
                "the api row must show the blocked agent chip");
            require(rollupRow != null, "the MAIN_WINDOW row must show the rollup chip '" + rollupText + "'; rows: "
                + rowTexts(cells));
            require(paneRow != null, "the api tab must grow a PANE child row '" + paneTitle + " · api'; rows: "
                + rowTexts(cells));
            Label paneName = visibleLabelWithStyle(paneRow, "dashboard-node-name");
            require(paneName.getText().equals(paneTitle + CodingAgentGlyphs.SEPARATOR + "api"),
                "the pane row must carry the cwd tail, was " + paneName.getText());

            Label footer = (Label) dashboard.lookup(".dashboard-footer");
            require(footer != null && footer.getText().contains(rollupText),
                "the footer must append the rollup, was " + (footer == null ? null : footer.getText()));

            require(!dashboard.isPulseTimerRunning(), "the dashboard pulse timer must be off on a never-shown stage");
            dashboard.renderFrameForTest(FROZEN_T);
            WritableImage image = CodingAgentSmokeFixture.snapshot(scene, 1, Color.web("#252526"));
            requireNotBlank(image, "dashboard");
            CodingAgentSmokeFixture.writePng(image, new File(outputDir, "coding-agent-dashboard.png"));

            dashboard.dispose();
            require(!dashboard.isPulseTimerRunning(), "dispose must stop the dashboard pulse timer");
            for (TerminalTab tab : List.of(apiTab, webTab)) {
                try {
                    tab.getTerminalView().cleanup();
                } catch (RuntimeException ignored) {
                    // never connected; cleanup only stops timers
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static CodingAgentEntry entry(Object item) {
        return (CodingAgentEntry) item;
    }

    private static ListCell<?> cellFor(ListView<?> list, PaneRef pane) {
        for (Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && cell.getItem() instanceof CodingAgentEntry entry
                && pane.equals(entry.pane())) {
                return cell;
            }
        }
        return null;
    }

    private static Button buttonWithText(Node root, String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static Label labelWithText(Node root, String text) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return label;
            }
        }
        return null;
    }

    private static Label visibleLabelWithStyle(Node root, String styleClass) {
        Node node = lookupWithStyle(root, styleClass);
        return node instanceof Label label && label.isVisible() ? label : null;
    }

    private static Node lookupWithStyle(Node root, String styleClass) {
        for (Node node : root.lookupAll("." + styleClass)) {
            if (node.isVisible()) {
                return node;
            }
        }
        return null;
    }

    private static TextArea visibleTextArea(Node root) {
        for (Node node : root.lookupAll(".text-area")) {
            if (node instanceof TextArea area && area.isVisible() && area.isManaged()) {
                return area;
            }
        }
        return null;
    }

    private static TextArea promptArea(Node root) {
        String placeholder = I18n.get("codingAgent.panel.prompt.placeholder");
        for (Node node : root.lookupAll(".text-area")) {
            if (node instanceof TextArea area && placeholder.equals(area.getPromptText())) {
                return area;
            }
        }
        return null;
    }

    /** The visible texts of the strip's chips and separators in child order, joined by single spaces. */
    private static String stripText(Parent strip) {
        StringBuilder text = new StringBuilder();
        for (Node child : strip.getChildrenUnmodifiable()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof Label label) {
                append(text, label.getText());
            } else if (child instanceof Parent chip) {
                for (Node inner : chip.getChildrenUnmodifiable()) {
                    if (inner instanceof Label label && label.isVisible()) {
                        append(text, label.getText());
                    }
                }
            }
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(part);
    }

    private static String rowTexts(List<TreeCell<?>> cells) {
        List<String> names = new ArrayList<>();
        for (TreeCell<?> cell : cells) {
            Label name = visibleLabelWithStyle(cell, "dashboard-node-name");
            names.add((name != null ? name.getText() : "?") + cell.getStyleClass());
        }
        return names.toString();
    }

    private static MouseEvent primaryClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 4, 4, 4, 4, MouseButton.PRIMARY, 1,
            false, false, false, false, true, false, false, true, false, false, null);
    }

    /** Fails when the image is a single flat colour (nothing was painted). */
    private static void requireNotBlank(Image image, String what) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        require(w > 10 && h > 10, what + " snapshot is too small: " + w + "x" + h);
        Color first = image.getPixelReader().getColor(0, 0);
        for (int y = 0; y < h; y += 3) {
            for (int x = 0; x < w; x += 3) {
                if (!image.getPixelReader().getColor(x, y).equals(first)) {
                    return;
                }
            }
        }
        throw new AssertionError(what + " snapshot is blank (single colour " + first + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
