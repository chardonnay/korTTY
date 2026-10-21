package de.kortty.ui;

import de.kortty.core.AiTokenUsageManager;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * KI-Agent design 16, the log table: a dense table below the terminal with the time, kind, step,
 * result, duration and tokens of every step, six steps where the classic panel fits two. Numbers line up
 * in a fixed-width font; a new row flashes blue; the running row has a small spinner; an open approval
 * puts its buttons into the header. A double-click on a row shows its output, on the last row the
 * answer. FX thread.
 */
final class AgentDesignLogTable extends VBox implements AgentRunPresenter {

    static final double HEIGHT = 162;
    private static final double ROW_HEIGHT = 19;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** One row of the table. */
    record Row(String id, String time, String kind, String step, String result, String duration, String tokens,
               boolean running, boolean failed, String detail) {
    }

    private final AgentRunPresenter.Actions actions;
    private final Label prompt = new Label();
    private final Label meta = new Label();
    private final HBox approvalButtons = new HBox(5);
    private final Button pauseButton;
    private final Button cancelButton;
    private final Button hideButton;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final Map<String, Long> firstSeen = new HashMap<>();
    // The steps behind the rows, for a row's menu.
    private final Map<String, TerminalAgentModels.AgentActivity> stepsById = new HashMap<>();
    private final Map<String, Long> finishedAt = new HashMap<>();
    private final Set<String> flashed = new HashSet<>();
    private final Timeline spinner;
    private final Popup detailPopup = new Popup();
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private String runId;
    private View view;
    private int frame;

    AgentDesignLogTable(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        pauseButton = AgentDesignWidgets.iconButton("⏸", I18n.get("ai.agent.control.pauseRun"), palette);
        cancelButton = AgentDesignWidgets.iconButton("■", I18n.get("dialog.cancel"), palette);
        hideButton = AgentDesignWidgets.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"), palette);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        hideButton.setOnAction(event -> actions.hide());
        prompt.setMinWidth(0);
        prompt.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(prompt, Priority.ALWAYS);
        approvalButtons.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(6, prompt, approvalButtons, meta, pauseButton, cancelButton,
            AgentDesignWidgets.runMenuButton(actions, palette), hideButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(3, 6, 3, 10));
        table.setFixedCellSize(ROW_HEIGHT);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label(""));
        table.getColumns().addAll(List.of(
            column("aiAgentDesign.table.time", Row::time, 72, true),
            column("aiAgentDesign.table.kind", Row::kind, 50, true),
            column("aiAgentDesign.table.step", Row::step, 260, false),
            column("aiAgentDesign.table.result", Row::result, 150, false),
            column("aiAgentDesign.table.duration", Row::duration, 48, true),
            column("aiAgentDesign.table.tokens", Row::tokens, 44, true)));
        table.setRowFactory(view -> new FlashingRow());
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(header, table);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        detailPopup.setAutoHide(true);
        spinner = new Timeline(new KeyFrame(Duration.millis(120), event -> {
            frame++;
            if (view != null) {
                refreshRows();
            }
        }));
        spinner.setCycleCount(Animation.INDEFINITE);
        visibility.setOnHidden(() -> {
            spinner.stop();
            detailPopup.hide();
        });
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.BOTTOM;
    }

    @Override
    public Region node() {
        return this;
    }

    @Override
    public boolean answersApprovals() {
        return true;
    }

    @Override
    public boolean staysAfterRun() {
        return true;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.background()) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-width: 1 0 0 0;");
        prompt.setTextFill(palette.text());
        prompt.setStyle("-fx-font-weight: bold;");
        meta.setTextFill(palette.muted());
        String background = AgentDesignPalette.hex(palette.background());
        table.setStyle("-fx-font-size: 0.8462em; -fx-padding: 0; -fx-background-insets: 0;");
        // The table's colours and a slim header from the palette, the same in every app design. Looked-up
        // colours go into a stylesheet: inline they would not resolve for the cells.
        String css = ".table-view { -fx-background-color: " + background + "; -fx-control-inner-background: " + background + ";"
            + " -fx-control-inner-background-alt: " + AgentDesignPalette.hex(palette.surface()) + ";"
            + " -fx-table-cell-border-color: rgba(0,0,0,0); -fx-text-background-color: " + AgentDesignPalette.hex(palette.text()) + ";"
            + " -fx-selection-bar: " + AgentDesignPalette.rgba(palette.accent(), 0.35) + ";"
            + " -fx-selection-bar-non-focused: " + AgentDesignPalette.rgba(palette.accent(), 0.2) + "; }"
            + " .table-view .column-header-background { -fx-background-color: " + AgentDesignPalette.hex(palette.surface()) + "; }"
            + " .table-view .column-header, .table-view .filler { -fx-background-color: transparent;"
            + " -fx-border-color: transparent transparent " + AgentDesignPalette.hex(palette.border()) + " transparent;"
            + " -fx-padding: 1 0 1 0; -fx-size: 18px; }"
            + " .table-view .column-header .label { -fx-text-fill: " + AgentDesignPalette.hex(palette.muted()) + ";"
            + " -fx-font-size: 0.7692em; -fx-font-weight: bold; -fx-padding: 0 6 0 6; }"
            + " .table-view .table-row-cell { -fx-background-color: transparent; -fx-border-color: transparent; -fx-cell-size: 19px; }"
            + " .table-view .table-row-cell:selected { -fx-background-color: " + AgentDesignPalette.rgba(palette.accent(), 0.28) + "; }";
        table.getStylesheets().setAll("data:text/css;base64,"
            + java.util.Base64.getEncoder().encodeToString(css.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (table.getScene() != null) {
            table.refresh();
        }
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            spinner.stop();
        }
    }

    @Override
    public void show(View view) {
        if (!view.runId().equals(runId)) {
            runId = view.runId();
            rows.clear();
            firstSeen.clear();
            finishedAt.clear();
            flashed.clear();
        }
        this.view = view;
        visibility.show();
        int count = AgentInlineBlockLayout.listedSteps(view.trace()).size();
        prompt.setText(AgentInlineBlockLayout.oneLine(view.prompt()));
        meta.setText(I18n.get("aiAgentDesign.toast.steps", count) + " · " + view.meta());
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        approvalButtons.getChildren().clear();
        TerminalAgentModels.Approval approval = view.trace().pendingApproval;
        if (approval != null) {
            approvalButtons.getChildren().addAll(AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval));
        }
        boolean animate = view.running() && !view.paused() && !reducedAnimations;
        if (animate && spinner.getStatus() != Animation.Status.RUNNING) {
            spinner.play();
        } else if (!animate) {
            spinner.stop();
        }
        refreshRows();
    }

    @Override
    public void scheduleAutoHide(int seconds) {
        visibility.scheduleHide(seconds);
    }

    @Override
    public void hideNow() {
        visibility.hideNow();
    }

    @Override
    public boolean isShowing() {
        return visibility.isShowing();
    }

    private void refreshRows() {
        long now = System.currentTimeMillis();
        List<Row> fresh = new java.util.ArrayList<>();
        stepsById.clear();
        for (TerminalAgentModels.AgentActivity step : AgentInlineBlockLayout.listedSteps(view.trace())) {
            fresh.add(rowOf(step, now));
            stepsById.put(step.id(), step);
        }
        TerminalAgentModels.Approval approval = view.trace().pendingApproval;
        if (approval != null) {
            fresh.add(new Row("#approval", CLOCK.format(Instant.ofEpochMilli(now)), "ask",
                AgentDesignWidgets.approvalCommands(approval), I18n.get("aiAgentDesign.table.waiting"), "", "–", true, false,
                AgentDesignWidgets.approvalMessage(approval)));
        }
        if (!view.running()) {
            String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
            if (!answer.isBlank()) {
                fresh.add(new Row("#answer", CLOCK.format(Instant.ofEpochMilli(view.trace().finishedAtMillis > 0
                    ? view.trace().finishedAtMillis : now)), "done", AgentInlineBlockLayout.oneLine(answer), "◆", "", "–",
                    false, false, answer.strip()));
            }
        }
        for (int i = 0; i < fresh.size(); i++) {
            if (i < rows.size()) {
                if (!rows.get(i).equals(fresh.get(i))) {
                    rows.set(i, fresh.get(i));
                }
            } else {
                rows.add(fresh.get(i));
            }
        }
        if (rows.size() > fresh.size()) {
            rows.remove(fresh.size(), rows.size());
        }
        if (!rows.isEmpty() && view.running()) {
            table.scrollTo(rows.size() - 1);
        }
    }

    private Row rowOf(TerminalAgentModels.AgentActivity step, long now) {
        long seen = firstSeen.computeIfAbsent(step.id(), id -> now);
        boolean running = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
        if (!running) {
            finishedAt.putIfAbsent(step.id(), now);
        }
        long seconds = step.elapsedSeconds() > 0 ? step.elapsedSeconds()
            : Math.max(0, ((running ? now : finishedAt.getOrDefault(step.id(), now)) - seen) / 1000L);
        String duration = step.type() == TerminalAgentModels.AgentActivityType.MESSAGE ? "" : seconds + " s";
        TerminalAgentModels.AgentActivityTokenUsage usage = step.tokenUsage();
        String tokens = usage != null && usage.known() && usage.totalTokens() > 0
            ? AiTokenUsageManager.formatCompact(usage.totalTokens()) : "–";
        boolean command = step.type() == TerminalAgentModels.AgentActivityType.ACTION;
        String text = AgentInlineBlockLayout.oneLine(command ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step));
        String result = running ? AgentDesignWidgets.spinner(frame, reducedAnimations) + " " + I18n.get("ai.agent.activity.running")
            : AgentStepDetails.result(step);
        Integer exit = AgentStepDetails.exitCode(step);
        boolean failed = step.type() == TerminalAgentModels.AgentActivityType.ERROR
            || step.status() == TerminalAgentModels.AgentActivityStatus.FAILED || (exit != null && exit != 0);
        List<String> output = AgentStepDetails.output(step);
        String detail = output.isEmpty() ? (step.detail() != null ? step.detail() : "") : String.join("\n", output);
        return new Row(step.id(), CLOCK.format(Instant.ofEpochMilli(seen)), AgentStepDetails.kind(step), text, result,
            duration, tokens, running, failed, detail);
    }

    private TableColumn<Row, String> column(String titleKey, Function<Row, String> value, double width, boolean numeric) {
        TableColumn<Row, String> column = new TableColumn<>(I18n.get(titleKey));
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        if (numeric) {
            column.setMinWidth(width);
            column.setMaxWidth(width + 24);
        }
        column.setSortable(true);
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                Row row = getTableRow() != null ? getTableRow().getItem() : null;
                if (empty || item == null || row == null) {
                    setText(null);
                    return;
                }
                setText(item);
                setFont(Font.font(numeric || "aiAgentDesign.table.step".equals(titleKey) && row.kind().matches("read|run")
                    ? AgentDesignWidgets.MONOSPACED : Font.getDefault().getFamily(), 11));
                Color color = palette.text();
                if ("aiAgentDesign.table.kind".equals(titleKey)) {
                    color = "think".equals(item) ? palette.think() : "ask".equals(item) ? palette.warn() : palette.muted();
                } else if ("aiAgentDesign.table.result".equals(titleKey)) {
                    color = row.failed() ? palette.error() : row.id().equals("#approval") ? palette.warn()
                        : row.running() ? palette.accent() : palette.muted();
                } else if (numeric) {
                    color = palette.muted();
                }
                setTextFill(color);
                setStyle("-fx-padding: 0 6 0 6;");
                setAlignment(numeric ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            }
        });
        return column;
    }

    /** A row that flashes in the accent when it first appears, and opens its detail on a double-click. */
    private final class FlashingRow extends TableRow<Row> {
        private final ObjectProperty<Color> flash = new SimpleObjectProperty<>(Color.TRANSPARENT);
        private Timeline fade;

        FlashingRow() {
            flash.addListener((obs, oldColor, newColor) ->
                setBackground(new Background(new BackgroundFill(newColor, null, null))));
            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && getItem() != null) {
                    showDetail(this, getItem());
                }
            });
            setOnContextMenuRequested(event -> {
                TerminalAgentModels.AgentActivity step = getItem() != null ? stepsById.get(getItem().id()) : null;
                if (step != null) {
                    actions.showStepMenu(this, step, event.getScreenX(), event.getScreenY());
                } else {
                    actions.showRunMenu(this, event.getScreenX(), event.getScreenY());
                }
                event.consume();
            });
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (fade != null) {
                fade.stop();
            }
            flash.set(Color.TRANSPARENT);
            setBackground(null);
            if (empty || item == null || reducedAnimations || !flashed.add(item.id())) {
                return;
            }
            Color start = palette.accent().deriveColor(0, 1, 1, 0.5);
            fade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(flash, start)),
                new KeyFrame(Duration.millis(900), new KeyValue(flash, Color.TRANSPARENT)));
            fade.setOnFinished(event -> setBackground(null));
            fade.play();
        }
    }

    private void showDetail(TableRow<Row> rowNode, Row row) {
        String text = row.detail() == null || row.detail().isBlank() ? row.step() : row.detail();
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(460, 180);
        area.setStyle("-fx-font-family: '" + AgentDesignWidgets.MONOSPACED + "'; -fx-font-size: 0.8462em;"
            + " -fx-control-inner-background: " + AgentDesignPalette.hex(palette.surface()) + ";"
            + " -fx-text-fill: " + AgentDesignPalette.hex(palette.text()) + ";");
        VBox box = new VBox(area);
        box.setPadding(new Insets(6));
        box.setStyle(AgentDesignWidgets.cardStyle(palette, 0.98, 8));
        box.setEffect(AgentDesignWidgets.shadow(16, 0.4));
        detailPopup.getContent().setAll(box);
        javafx.geometry.Point2D at = rowNode.localToScreen(24, rowNode.getHeight());
        if (at != null && rowNode.getScene() != null) {
            detailPopup.show(rowNode.getScene().getWindow(), at.getX(), at.getY());
        }
    }
}
