package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import de.kortty.KorTTYApplication;
import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.core.ThemeManager;
import de.kortty.model.AgentActionCategory;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.Theme;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * The live preview of the KI-Agent Design dialog: a real terminal pane with a short shell session and an
 * {@link AgentDesignHost} below it, through which a scripted sample run ("why is /var almost full?")
 * plays in the chosen combination, exactly as a real run is shown. The approval is answered with the
 * key 1, as a user would.
 */
final class AgentDesignPreview extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(AgentDesignPreview.class);
    static final double PREVIEW_WIDTH = 620;
    static final double PREVIEW_HEIGHT = 360;
    private static final String RUN_ID = "agent-design-preview";
    private static final String LARGEST_DIRECTORIES = "du -xh /var --max-depth=2 | sort -h | tail -3";
    private static final String VACUUM = "sudo journalctl --vacuum-size=500M";
    private static final String ESC = "\u001b";

    private final ConnectionSettings terminalSettings;
    private final @Nullable Theme agentTheme;
    private @Nullable SithTermFxWidget widget;
    private @Nullable DemoConnector connector;
    private @Nullable AgentDesignHost host;
    private @Nullable Timeline script;

    AgentDesignPreview() {
        getStyleClass().add("kortty-box-border");
        setStyle("-fx-border-radius: 8; -fx-background-radius: 8;");
        setMinSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        setPrefSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        terminalSettings = previewTerminalSettings();
        agentTheme = previewAgentTheme(terminalSettings);
        setStyle(getStyle() + " -fx-background-color: " + terminalSettings.getBackgroundColor() + ";");
    }

    /** Plays the sample run in {@code combination} from the start. FX thread. */
    void play(AgentDesignCombination combination) {
        stop();
        try {
            SithTermFxWidget terminal = new KorttyTermWidget(80, 14, new TerminalView.KorTTYSettingsProvider(
                terminalSettings, new DynamicFontSizeSettingsProvider(terminalSettings.getFontSize()), () -> 0));
            DemoConnector demo = new DemoConnector(transcript());
            terminal.setTtyConnector(demo);
            Pane overlay = new Pane();
            overlay.setMouseTransparent(true);
            overlay.setPickOnBounds(false);
            // Like a pane's control layer: its own nodes take clicks, the rest reaches the terminal.
            Pane controls = new Pane();
            controls.setPickOnBounds(false);
            AgentDesignHost designHost = new AgentDesignHost(surfaceFor(terminal, overlay, controls));
            designHost.applyTheme(agentTheme);
            Region pane = terminal.getPane();
            pane.setMinSize(0, 0);
            // The same arrangement as a pane's cell: a top slot, the terminal with a right slot, the host.
            HBox middle = new HBox(pane, designHost.rightSlot());
            HBox.setHgrow(pane, Priority.ALWAYS);
            VBox column = new VBox(designHost.topSlot(), middle, designHost);
            VBox.setVgrow(middle, Priority.ALWAYS);
            column.setFillWidth(true);
            getChildren().setAll(column, overlay, controls);
            widget = terminal;
            connector = demo;
            host = designHost;
            terminal.start();
            script = script(designHost, combination);
            script.play();
        } catch (Exception e) {
            logger.warn("Could not start the KI-Agent design preview", e);
            stop();
            getChildren().setAll(new javafx.scene.control.Label(I18n.get("aiAgentDesign.preview.unavailable")));
        }
    }

    /** Stops the sample run and closes the preview terminal. FX thread. */
    void stop() {
        if (script != null) {
            script.stop();
            script = null;
        }
        if (host != null) {
            host.cancelAllRuns();
            host = null;
        }
        if (connector != null) {
            connector.close();
            connector = null;
        }
        if (widget != null) {
            try {
                widget.close();
            } catch (Exception e) {
                logger.debug("Could not close the preview terminal: {}", e.getMessage());
            }
            widget = null;
        }
        getChildren().clear();
    }

    private Timeline script(AgentDesignHost designHost, AgentDesignCombination combination) {
        String prompt = I18n.get("aiAgentDesign.preview.prompt");
        String answer = I18n.get("aiAgentDesign.preview.answer");
        boolean classic = combination.usesClassicHistory();
        Timeline timeline = new Timeline();
        List<KeyFrame> frames = timeline.getKeyFrames();
        if ("d05".equals(combination.statusId())) {
            // Design 05 is asked in its Spotlight, which then shrinks into the pill.
            frames.add(at(0.05, () -> designHost.playSpotlight(prompt)));
        }
        frames.add(at(0.5, () -> designHost.beginRun(RUN_ID, prompt, () -> { }, paused -> { }, null,
            new AiAgentActivityPanel.RunMetadata("preview", "Standard", "", "", "admin", "admin", "web-01"),
            combination)));
        frames.add(at(0.7, () -> state(designHost, TerminalAgentModels.Phase.PROBING)));
        frames.add(at(0.8, () -> designHost.publishActivity(RUN_ID, action("probe", "Inspect SSH session",
            TerminalAgentModels.AgentActivityStatus.RUNNING, "", "", AgentActionCategory.INSPECT))));
        frames.add(at(1.5, () -> designHost.publishActivity(RUN_ID, action("probe", "Inspect SSH session",
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Ubuntu 24.04 · bash", "", AgentActionCategory.INSPECT))));
        frames.add(at(1.7, () -> {
            state(designHost, TerminalAgentModels.Phase.PLANNING);
            designHost.publishActivity(RUN_ID, thinking(TerminalAgentModels.AgentActivityStatus.RUNNING, ""));
        }));
        frames.add(at(2.8, () -> designHost.publishActivity(RUN_ID, thinking(TerminalAgentModels.AgentActivityStatus.COMPLETED,
            I18n.get("aiAgentDesign.preview.thinking")))));
        frames.add(at(3.0, () -> {
            state(designHost, TerminalAgentModels.Phase.RUNNING_COMMANDS);
            designHost.publishActivity(RUN_ID, action("cmd1", "Read(" + LARGEST_DIRECTORIES + ")",
                TerminalAgentModels.AgentActivityStatus.RUNNING, "", "", AgentActionCategory.READ));
        }));
        frames.add(at(4.0, () -> designHost.publishActivity(RUN_ID, action("cmd1", "Read(" + LARGEST_DIRECTORIES + ")",
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Exit 0 - 3 output lines",
            detail("Find the largest directories", LARGEST_DIRECTORIES, "1.4G\t/var/lib/docker\n21G\t/var/log/journal\n23G\t/var"),
            AgentActionCategory.READ))));
        frames.add(at(4.2, () -> designHost.publishActivity(RUN_ID, action("cmd2", "Read(journalctl --disk-usage)",
            TerminalAgentModels.AgentActivityStatus.RUNNING, "", "", AgentActionCategory.READ))));
        frames.add(at(5.0, () -> designHost.publishActivity(RUN_ID, action("cmd2", "Read(journalctl --disk-usage)",
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Exit 0 - 1 output lines",
            detail("Measure the journal", "journalctl --disk-usage", "Archived and active journals take up 21.4G in the file system."),
            AgentActionCategory.READ))));
        frames.add(at(5.3, () -> {
            state(designHost, TerminalAgentModels.Phase.AWAITING_APPROVAL);
            designHost.publishActivity(RUN_ID, question(TerminalAgentModels.AgentActivityStatus.RUNNING));
            if (!classic) {
                askApproval(designHost);
            }
        }));
        frames.add(at(7.6, () -> {
            if (!classic) {
                designHost.handlePromptKey(new KeyEvent(KeyEvent.KEY_PRESSED, "1", "1", KeyCode.DIGIT1,
                    false, false, false, false));
            } else {
                designHost.publishActivity(RUN_ID, question(TerminalAgentModels.AgentActivityStatus.COMPLETED));
            }
        }));
        frames.add(at(7.8, () -> {
            state(designHost, TerminalAgentModels.Phase.RUNNING_COMMANDS);
            designHost.publishActivity(RUN_ID, action("cmd3", "Run(" + VACUUM + ")",
                TerminalAgentModels.AgentActivityStatus.RUNNING, "", "", AgentActionCategory.WRITE));
        }));
        frames.add(at(8.9, () -> designHost.publishActivity(RUN_ID, action("cmd3", "Run(" + VACUUM + ")",
            TerminalAgentModels.AgentActivityStatus.COMPLETED, "Exit 0 - 1 output lines",
            detail("Shrink the journal", VACUUM, "Vacuuming done, freed 20.9G of archived journals from /var/log/journal."),
            AgentActionCategory.WRITE))));
        frames.add(at(9.2, () -> {
            designHost.publishActivity(RUN_ID, new TerminalAgentModels.AgentActivity(RUN_ID + ":message:final",
                TerminalAgentModels.AgentActivityType.MESSAGE, TerminalAgentModels.AgentActivityStatus.COMPLETED,
                answer, answer, "", TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, false, true));
            state(designHost, TerminalAgentModels.Phase.DONE);
            designHost.showFinalMessage(RUN_ID, answer, TerminalAgentModels.Phase.DONE);
            designHost.finishRun(RUN_ID);
        }));
        return timeline;
    }

    private void askApproval(AgentDesignHost designHost) {
        TerminalAgentModels.Approval approval = new TerminalAgentModels.Approval(RUN_ID, "preview", null,
            I18n.get("aiAgentDesign.preview.approval"), I18n.get("aiAgentDesign.preview.approval"),
            List.of(new TerminalAgentModels.PlannedCommand(VACUUM, "",
                TerminalAgentModels.Risk.REQUIRES_CONFIRMATION)), true);
        Thread worker = new Thread(() -> designHost.requestApproval(RUN_ID, approval), "ai-agent-design-preview");
        worker.setDaemon(true);
        worker.start();
    }

    private static void state(AgentDesignHost designHost, TerminalAgentModels.Phase phase) {
        designHost.updateState(RUN_ID, new TerminalAgentModels.RunState(RUN_ID, "preview", null, phase, "", "",
            null, null, null, 1));
    }

    private static KeyFrame at(double seconds, Runnable action) {
        return new KeyFrame(Duration.seconds(seconds), event -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                logger.debug("KI-Agent design preview step failed: {}", e.getMessage());
            }
        });
    }

    /** A command step as the agent service publishes it: titled {@code Read(…)} or {@code Run(…)}. */
    private static TerminalAgentModels.AgentActivity action(String id, String title,
                                                            TerminalAgentModels.AgentActivityStatus status,
                                                            String summary, String detail, AgentActionCategory category) {
        return new TerminalAgentModels.AgentActivity(RUN_ID + ":command:" + id, TerminalAgentModels.AgentActivityType.ACTION,
            status, title, summary, detail, TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, !detail.isBlank(),
            status != TerminalAgentModels.AgentActivityStatus.RUNNING, category);
    }

    /** A finished command's detail in the agent service's form: purpose, command, exit code, output. */
    private static String detail(String purpose, String command, String stdout) {
        return purpose + "\nCommand: " + command + "\nExit code: 0\nstdout:\n" + stdout;
    }

    private static TerminalAgentModels.AgentActivity thinking(TerminalAgentModels.AgentActivityStatus status, String summary) {
        TerminalAgentModels.AgentActivityTokenUsage usage = status == TerminalAgentModels.AgentActivityStatus.COMPLETED
            ? new TerminalAgentModels.AgentActivityTokenUsage(true, 1_020L, 264L, 1_284L)
            : TerminalAgentModels.AgentActivityTokenUsage.unknown();
        return new TerminalAgentModels.AgentActivity(RUN_ID + ":thinking:1", TerminalAgentModels.AgentActivityType.THINKING,
            status, I18n.get("ai.agent.activity.thinking"), summary, "", usage, 0L, false, true);
    }

    private static TerminalAgentModels.AgentActivity question(TerminalAgentModels.AgentActivityStatus status) {
        String text = I18n.get("aiAgentDesign.preview.approval");
        return new TerminalAgentModels.AgentActivity(RUN_ID + ":question:approval-1",
            TerminalAgentModels.AgentActivityType.QUESTION, status, text, text, "",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, false, false);
    }

    private AgentTerminalSurface surfaceFor(SithTermFxWidget terminal, Pane overlay, Pane controls) {
        return new AgentTerminalSurface() {
            @Override
            public AgentTerminalBlockWriter blockWriter() {
                return terminal.getTerminal() != null && terminal.getTerminalTextBuffer() != null
                    ? new AgentTerminalBlockWriter(terminal.getTerminal(), terminal.getTerminalTextBuffer(),
                        terminal.getTerminal().getStyleState())
                    : null;
            }

            @Override
            public boolean darkBackground() {
                return isDark(terminalSettings.getBackgroundColor());
            }

            @Override
            public void localTextWritten() {
                if (terminal.getTerminalPanel() != null) {
                    terminal.getTerminalPanel().repaint();
                }
            }

            @Override
            public void journalLocalOutput(String text) {
            }

            @Override
            public Pane overlayLayer() {
                return overlay;
            }

            @Override
            public Pane controlLayer() {
                return controls;
            }

            @Override
            public javafx.scene.Node canvas() {
                return terminal.getTerminalPanel() != null ? terminal.getTerminalPanel().getCanvas() : null;
            }

            @Override
            public TerminalCellGeometry cellGeometry() {
                return terminal instanceof KorttyTermWidget kortty
                    && kortty.getTerminalPanel() instanceof KorttyTermWidget.KorttyTerminalPanel panel
                    ? panel.cellGeometry() : null;
            }

            @Override
            public com.sithtermfx.core.compatibility.Point cursorCell() {
                return terminal.getTerminal() != null
                    ? new com.sithtermfx.core.compatibility.Point(terminal.getTerminal().getCursorX() - 1,
                        terminal.getTerminal().getCursorY() - 1)
                    : null;
            }
        };
    }

    private static String transcript() {
        String prompt = ESC + "[1;32madmin@web-01" + ESC + "[0m:" + ESC + "[1;34m~" + ESC + "[0m$ ";
        return prompt + "df -h /var\r\n"
            + "Filesystem      Size  Used Avail Use% Mounted on\r\n"
            + "/dev/sda3        40G   37G  3.1G  " + ESC + "[31m93%" + ESC + "[0m /var\r\n"
            + prompt;
    }

    /** The terminal colours and font of a new connection, a little smaller to fit the preview. */
    private static ConnectionSettings previewTerminalSettings() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        ConnectionSettings base = settings != null ? settings.getDefaultTerminalSettings() : new ConnectionSettings();
        ConnectionSettings copy = new ConnectionSettings(base != null ? base : new ConnectionSettings());
        ThemeManager themes = app != null ? app.getThemeManager() : null;
        if (themes != null && copy.getThemeId() != null && !copy.getThemeId().isBlank()) {
            copy = themes.resolveSettings(copy, copy.getThemeId());
        }
        copy.setFontSize(Math.min(copy.getFontSize(), 12));
        return copy;
    }

    private static @Nullable Theme previewAgentTheme(ConnectionSettings terminal) {
        KorTTYApplication app = KorTTYApplication.getInstance();
        ThemeManager themes = app != null ? app.getThemeManager() : null;
        if (themes == null) {
            return null;
        }
        String themeId = terminal.getThemeId();
        return themeId != null && !themeId.isBlank()
            ? themes.getTheme(themeId).orElseGet(themes::getDefaultTheme)
            : themes.getDefaultTheme();
    }

    static boolean isDark(String color) {
        try {
            Color background = Color.web(color);
            return 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue() < 0.55;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Prints the shell session once, then stays silent until it is closed. */
    private static final class DemoConnector implements TtyConnector {
        private final CountDownLatch closed = new CountDownLatch(1);
        private final char[] transcript;
        private int position;
        private volatile boolean connected = true;

        DemoConnector(String transcript) {
            this.transcript = transcript.toCharArray();
        }

        @Override
        public synchronized int read(char[] buffer, int offset, int length) {
            if (position < transcript.length) {
                int count = Math.min(length, transcript.length - position);
                System.arraycopy(transcript, position, buffer, offset, count);
                position += count;
                return count;
            }
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public boolean ready() {
            return position < transcript.length;
        }

        @Override
        public String getName() {
            return "ai-agent-design-preview";
        }

        @Override
        public void close() {
            connected = false;
            closed.countDown();
        }
    }
}
