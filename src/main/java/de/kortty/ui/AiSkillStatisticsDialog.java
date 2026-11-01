package de.kortty.ui;

import de.kortty.core.BuiltinAiSkillSupport;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.KorTTYApplication;
import de.kortty.model.AiSkill;
import de.kortty.model.GlobalSettings;
import de.kortty.policy.ManagedSetting;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyUiSupport;
import de.kortty.stats.SkillStatsReport;
import de.kortty.stats.SkillStatsStore;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The skill statistics: the uses per period as a stacked chart, the headline figures, one sortable row per skill with
 * its sizes and growth, the size history of the selected row and hints about skills to review. The statistics are
 * recorded only while the setting is on and the policy allows them, and this dialog switches that setting itself.
 * Everything is read and aggregated off the FX thread.
 */
final class AiSkillStatisticsDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(AiSkillStatisticsDialog.class);
    private static final String CARD_STYLE =
        "-fx-border-color: rgba(128, 128, 128, 0.35); -fx-border-radius: 6; -fx-background-radius: 6;";
    private static final String VALUE_STYLE = "-fx-font-weight: bold; -fx-font-size: 1.2em;";
    private static final List<Integer> DAY_RANGES = List.of(30, 90, 400);
    private static final List<Integer> MONTH_RANGES = List.of(12, 24, 60);

    /** One table row with its badge text, built on the FX thread from the skill it describes. */
    record Line(SkillStatsReport.Row row, String badges) {
    }

    private final KorTTYApplication app;
    private final Function<AiSkill, BuiltinAiSkillSupport.AiSkillStatus> statusOf;
    private final ToggleGroup granularityGroup = new ToggleGroup();
    private final ToggleButton dayToggle = granularityToggle("settings.aiSkills.stats.granularity.day",
        SkillStatsReport.Granularity.DAY);
    private final ToggleButton monthToggle = granularityToggle("settings.aiSkills.stats.granularity.month",
        SkillStatsReport.Granularity.MONTH);
    private final ToggleButton yearToggle = granularityToggle("settings.aiSkills.stats.granularity.year",
        SkillStatsReport.Granularity.YEAR);
    private final ComboBox<SkillStatsReport.Range> rangeBox = new ComboBox<>();
    private final CheckBox recordCheck = new CheckBox(I18n.get("settings.aiSkills.stats.record"));
    private final CheckBox includePinnedCheck = new CheckBox(I18n.get("settings.aiSkills.stats.includePinned"));
    private final Label bannerLabel = new Label();
    private final Label emptyLabel = new Label(I18n.get("settings.aiSkills.stats.empty"));
    private final Label statusLabel = new Label();
    private final Label automaticValue = new Label(AiSkillStatisticsFormat.NONE);
    private final Label averageSizeValue = new Label(AiSkillStatisticsFormat.NONE);
    private final Label sentTokensValue = new Label(AiSkillStatisticsFormat.NONE);
    private final Label growthValue = new Label(AiSkillStatisticsFormat.NONE);
    private final CategoryAxis barAxis = new CategoryAxis();
    private final NumberAxis barValues = new NumberAxis();
    private final StackedBarChart<String, Number> barChart = new StackedBarChart<>(barAxis, barValues);
    private final TableView<Line> table = new TableView<>();
    private final CategoryAxis historyAxis = new CategoryAxis();
    private final NumberAxis historyValues = new NumberAxis();
    private final LineChart<String, Number> historyChart = new LineChart<>(historyAxis, historyValues);
    private final Label historyTitle = new Label();
    private final Label historyEmpty = new Label();
    private final VBox hintsBox = new VBox(6);
    private final Button resetButton = new Button(I18n.get("settings.aiSkills.stats.reset"));
    private SkillStatsReport lastReport;
    private long generation;
    private boolean loadingControls;
    private boolean disposed;

    AiSkillStatisticsDialog(Window owner, KorTTYApplication app,
                            Function<AiSkill, BuiltinAiSkillSupport.AiSkillStatus> statusOf) {
        this.app = app;
        this.statusOf = statusOf;
        setTitle(I18n.get("settings.aiSkills.stats.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(1060, 800);
        setResultConverter(button -> null);
        setOnHidden(event -> disposed = true);

        dayToggle.setToggleGroup(granularityGroup);
        monthToggle.setToggleGroup(granularityGroup);
        yearToggle.setToggleGroup(granularityGroup);
        dayToggle.setSelected(true);
        granularityGroup.selectedToggleProperty().addListener((obs, previous, current) -> {
            if (current == null) {
                granularityGroup.selectToggle(previous);
                return;
            }
            if (!loadingControls) {
                fillRangeChoices(granularity());
                refresh();
            }
        });
        rangeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SkillStatsReport.Range range) {
                return range == null ? "" : rangeLabel(range);
            }

            @Override
            public SkillStatsReport.Range fromString(String text) {
                return null;
            }
        });
        rangeBox.valueProperty().addListener((obs, previous, current) -> {
            if (!loadingControls && current != null) {
                refresh();
            }
        });
        fillRangeChoices(SkillStatsReport.Granularity.DAY);

        recordCheck.setSelected(settings() == null || settings().isAiSkillStatisticsEnabled());
        PolicyUiSupport.lockIfManaged(recordCheck, ManagedSetting.AI_SKILL_STATISTICS);
        recordCheck.selectedProperty().addListener((obs, previous, current) -> {
            if (!loadingControls) {
                setRecording(current);
            }
        });
        includePinnedCheck.setTooltip(new Tooltip(I18n.get("settings.aiSkills.stats.includePinned.tooltip")));
        includePinnedCheck.selectedProperty().addListener((obs, previous, current) -> {
            if (lastReport != null) {
                rebuildBars(lastReport);
            }
        });
        resetButton.setOnAction(event -> resetStatistics());

        bannerLabel.setWrapText(true);
        bannerLabel.setStyle(MutedTextStyle.HINT);
        emptyLabel.setWrapText(true);
        emptyLabel.setStyle(MutedTextStyle.HINT);
        statusLabel.setWrapText(true);
        statusLabel.setStyle(MutedTextStyle.HINT);
        historyEmpty.setWrapText(true);
        historyEmpty.setStyle(MutedTextStyle.HINT);
        historyTitle.setStyle("-fx-font-weight: bold;");

        barAxis.setLabel(null);
        barValues.setMinorTickVisible(false);
        barValues.setLabel(I18n.get("settings.aiSkills.stats.chart.uses"));
        barChart.setAnimated(false);
        barChart.setLegendSide(Side.BOTTOM);
        barChart.setCategoryGap(6);
        barChart.setPrefHeight(300);
        historyChart.setAnimated(false);
        historyChart.setLegendVisible(false);
        historyChart.setPrefHeight(240);
        historyValues.setLabel(I18n.get("settings.aiSkills.stats.col.size"));
        historyValues.setMinorTickVisible(false);
        historyAxis.setLabel(null);

        buildTable();
        table.getSelectionModel().selectedItemProperty().addListener((obs, previous, current) -> showHistory(current));
        table.setPrefHeight(300);
        table.setPlaceholder(new Label(I18n.get("settings.aiSkills.stats.empty.table")));

        HBox granularity = new HBox(0, dayToggle, monthToggle, yearToggle);
        FlowPane header = new FlowPane(10, 8, granularity, rangeBox, recordCheck, resetButton);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox kpis = new HBox(10,
            kpiCard("settings.aiSkills.stats.kpi.automatic", automaticValue),
            kpiCard("settings.aiSkills.stats.kpi.averageSize", averageSizeValue),
            kpiCard("settings.aiSkills.stats.kpi.sentTokens", sentTokensValue),
            kpiCard("settings.aiSkills.stats.kpi.growth", growthValue));
        for (javafx.scene.Node card : kpis.getChildren()) {
            HBox.setHgrow(card, Priority.ALWAYS);
        }

        VBox chartBox = new VBox(6, includePinnedCheck, barChart);
        Label tableTitle = new Label(I18n.get("settings.aiSkills.stats.table.title"));
        tableTitle.setStyle("-fx-font-weight: bold;");
        VBox historyBox = new VBox(6, historyTitle, historyChart, historyEmpty);
        HBox.setHgrow(historyBox, Priority.ALWAYS);
        Label hintsTitle = new Label(I18n.get("settings.aiSkills.stats.hints.title"));
        hintsTitle.setStyle("-fx-font-weight: bold;");
        VBox hintsColumn = new VBox(8, hintsTitle, hintsBox);
        hintsColumn.setPrefWidth(300);
        HBox lower = new HBox(16, historyBox, hintsColumn);

        VBox root = new VBox(12, bannerLabel, header, kpis, emptyLabel, chartBox, tableTitle, table, lower, statusLabel);
        root.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        getDialogPane().setContent(scroll);

        refreshBanner();
        refresh();
    }

    /** Recording state, the policy and the chart reflect the settings; nothing is written here until a user action. */
    private void refreshBanner() {
        String text = null;
        if (!PolicyManager.effective().aiSkillStatisticsAllowed()) {
            text = I18n.get("settings.aiSkills.stats.banner.policy");
        } else if (settings() != null && !settings().isAiSkillStatisticsEnabled()) {
            text = I18n.get("settings.aiSkills.stats.banner.off");
        }
        bannerLabel.setText(text != null ? text : "");
        bannerLabel.setVisible(text != null);
        bannerLabel.setManaged(text != null);
    }

    private void refresh() {
        long current = ++generation;
        SkillStatsReport.Range range = selectedRange();
        List<AiSkill> skills = effectiveSkillCopies();
        CompletableFuture.supplyAsync(() -> SkillStatsStore.get().report(skills, range))
            .whenComplete((report, error) -> Platform.runLater(() -> {
                if (disposed || current != generation) {
                    return;
                }
                if (error != null) {
                    logger.warn("The skill statistics could not be computed: {}", String.valueOf(error.getMessage()));
                    statusLabel.setText(I18n.get("settings.aiSkills.stats.loadFailed"));
                    return;
                }
                apply(report, skills);
            }));
    }

    private void apply(SkillStatsReport report, List<AiSkill> skills) {
        lastReport = report;
        statusLabel.setText("");
        Map<String, AiSkill> byId = new HashMap<>();
        for (AiSkill skill : skills) {
            byId.putIfAbsent(skill.getId(), skill);
        }
        List<Line> lines = new ArrayList<>();
        for (SkillStatsReport.Row row : report.rows()) {
            lines.add(new Line(row, badgesOf(byId.get(row.skillId()))));
        }
        table.getItems().setAll(lines);
        SkillStatsReport.Kpis kpis = report.kpis();
        automaticValue.setText(AiSkillStatisticsFormat.number(kpis.automaticUses()));
        averageSizeValue.setText(kpis.averageSizeTokens() != null
            ? AiSkillStatisticsFormat.tokens(kpis.averageSizeTokens()) + " " + I18n.get("settings.aiSkills.stats.unit.tokens")
            : AiSkillStatisticsFormat.NONE);
        sentTokensValue.setText(AiSkillStatisticsFormat.number(kpis.sentTokens()));
        growthValue.setText(kpis.growthTokensPerMonth() != null
            ? AiSkillStatisticsFormat.perMonth(kpis.growthTokensPerMonth()) + " " + I18n.get("settings.aiSkills.stats.unit.perMonth")
            : AiSkillStatisticsFormat.NONE);
        emptyLabel.setVisible(!report.hasUsage());
        emptyLabel.setManaged(!report.hasUsage());
        barChart.setVisible(report.hasUsage());
        barChart.setManaged(report.hasUsage());
        rebuildBars(report);
        rebuildHints(report);
        if (table.getSelectionModel().getSelectedItem() == null && !lines.isEmpty()) {
            table.getSelectionModel().select(0);
        }
        showHistory(table.getSelectionModel().getSelectedItem());
    }

    private void rebuildBars(SkillStatsReport report) {
        barChart.getData().clear();
        List<String> labels = report.periods().stream().map(AiSkillStatisticsFormat::periodLabel).toList();
        barAxis.setCategories(FXCollections.observableArrayList(labels));
        boolean pinned = includePinnedCheck.isSelected();
        String pinnedLabel = I18n.get("settings.aiSkills.stats.col.pinned");
        for (SkillStatsReport.Series seriesRecord : report.series()) {
            String name = seriesRecord.skillId() == null
                ? I18n.get("settings.aiSkills.stats.chart.other")
                : seriesRecord.name();
            barChart.getData().add(barSeries(name, labels, seriesRecord.automatic()));
            if (pinned) {
                barChart.getData().add(barSeries(name + " (" + pinnedLabel + ")", labels, seriesRecord.pinned()));
            }
        }
    }

    /** One stacked series of the usage chart: a name and one count per period label. */
    private static XYChart.Series<String, Number> barSeries(String name, List<String> labels, List<Long> counts) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(name);
        for (int index = 0; index < labels.size(); index++) {
            series.getData().add(new XYChart.Data<>(labels.get(index), counts.get(index)));
        }
        return series;
    }

    private void rebuildHints(SkillStatsReport report) {
        hintsBox.getChildren().clear();
        if (report.hints().isEmpty()) {
            Label none = new Label(I18n.get("settings.aiSkills.stats.hints.none"));
            none.setWrapText(true);
            none.setStyle(MutedTextStyle.HINT);
            hintsBox.getChildren().add(none);
            return;
        }
        for (SkillStatsReport.Hint hint : report.hints()) {
            String name = hint.name() != null ? hint.name() : hint.skillId();
            String text = hint.kind() == SkillStatsReport.HintKind.UNUSED_90_DAYS
                ? I18n.get(AiSkillStatisticsFormat.kindKey(hint.kind()), name)
                : I18n.get(AiSkillStatisticsFormat.kindKey(hint.kind()), name, hint.tokens());
            Label label = new Label(text);
            label.setWrapText(true);
            hintsBox.getChildren().add(label);
        }
    }

    private void showHistory(Line line) {
        historyChart.getData().clear();
        if (line == null) {
            historyTitle.setText("");
            historyEmpty.setText(I18n.get("settings.aiSkills.stats.history.none"));
            return;
        }
        SkillStatsReport.Row row = line.row();
        String name = row.name() != null ? row.name() : row.skillId();
        historyTitle.setText(I18n.get("settings.aiSkills.stats.history.title", name));
        historyEmpty.setText(row.history().isEmpty() ? I18n.get("settings.aiSkills.stats.history.empty") : "");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        List<String> labels = new ArrayList<>();
        for (SkillStatsReport.Point point : row.history()) {
            String label = AiSkillStatisticsFormat.date(point.date());
            labels.add(label);
            series.getData().add(new XYChart.Data<>(label, point.tokens()));
        }
        historyAxis.setCategories(FXCollections.observableArrayList(labels));
        historyChart.getData().add(series);
    }

    private void buildTable() {
        table.getColumns().add(textColumn("settings.aiSkills.stats.col.skill",
            line -> line.row().name() != null ? line.row().name() : line.row().skillId(), 180));
        table.getColumns().add(textColumn("settings.aiSkills.stats.col.badges", Line::badges, 200));
        table.getColumns().add(countColumn("settings.aiSkills.stats.col.automatic",
            line -> line.row().automatic(), 90));
        table.getColumns().add(countColumn("settings.aiSkills.stats.col.pinned",
            line -> line.row().pinned(), 80));
        table.getColumns().add(column("settings.aiSkills.stats.col.averageSize",
            line -> AiSkillStatisticsFormat.sortKey(line.row().averageTokens()),
            line -> AiSkillStatisticsFormat.tokens(line.row().averageTokens()), 120));
        table.getColumns().add(column("settings.aiSkills.stats.col.currentSize",
            line -> AiSkillStatisticsFormat.sortKey(line.row().currentTokens() != null
                ? line.row().currentTokens().doubleValue() : null),
            line -> AiSkillStatisticsFormat.tokens(line.row().currentTokens() != null
                ? line.row().currentTokens().doubleValue() : null), 120));
        table.getColumns().add(column("settings.aiSkills.stats.col.growth",
            line -> AiSkillStatisticsFormat.sortKey(line.row().growthTokensPerMonth()),
            line -> AiSkillStatisticsFormat.perMonth(line.row().growthTokensPerMonth()), 110));
        table.getColumns().add(column("settings.aiSkills.stats.col.growthPercent",
            line -> AiSkillStatisticsFormat.sortKey(line.row().growthPercent()),
            line -> AiSkillStatisticsFormat.percent(line.row().growthPercent()), 100));
        table.getColumns().add(column("settings.aiSkills.stats.col.lastUsed",
            line -> line.row().lastUsed() != null ? line.row().lastUsed().toString() : "",
            line -> line.row().lastUsed() != null
                ? AiSkillStatisticsFormat.date(line.row().lastUsed())
                : I18n.get("settings.aiSkills.stats.never"), 110));
        table.getColumns().add(countColumn("settings.aiSkills.stats.col.rules",
            line -> line.row().learnedRules(), 80));
    }

    /**
     * A sortable column. The sort key is any comparable value, so a number sorts as a number; the text is what the cell
     * shows. The cell reads the row's line, which is the item the table holds.
     */
    private static <T extends Comparable<T>> TableColumn<Line, T> column(
        String key, Function<Line, T> sortKey, Function<Line, String> text, double width) {

        TableColumn<Line, T> column = new TableColumn<>(I18n.get(key));
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(sortKey.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                Line line = getTableRow() != null ? getTableRow().getItem() : null;
                setText(empty || item == null || line == null ? null : text.apply(line));
            }
        });
        return column;
    }

    private static TableColumn<Line, String> textColumn(String key, Function<Line, String> text, double width) {
        return column(key, text, text, width);
    }

    private static TableColumn<Line, Long> countColumn(String key, ToLongFunction<Line> value, double width) {
        return column(key, line -> value.applyAsLong(line),
            line -> AiSkillStatisticsFormat.number(value.applyAsLong(line)), width);
    }

    private void resetStatistics() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("settings.aiSkills.stats.reset.title"));
        confirm.setHeaderText(I18n.get("settings.aiSkills.stats.reset.header"));
        confirm.setContentText(I18n.get("settings.aiSkills.stats.reset.content"));
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                SkillStatsStore.get().reset();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            if (error != null) {
                logger.warn("The skill statistics could not be reset: {}", String.valueOf(error.getMessage()));
                statusLabel.setText(I18n.get("settings.aiSkills.stats.reset.failed"));
                return;
            }
            statusLabel.setText(I18n.get("settings.aiSkills.stats.reset.done"));
            refresh();
        }));
    }

    private void setRecording(boolean enabled) {
        GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
        if (manager == null) {
            return;
        }
        manager.getSettings().setAiSkillStatisticsEnabled(enabled);
        manager.saveAsync().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (!disposed && error != null) {
                statusLabel.setText(I18n.get("settings.aiSkills.stats.save.failed", String.valueOf(error.getMessage())));
            }
        }));
        refreshBanner();
        refresh();
    }

    private void fillRangeChoices(SkillStatsReport.Granularity granularity) {
        loadingControls = true;
        try {
            List<SkillStatsReport.Range> ranges = new ArrayList<>();
            switch (granularity) {
                case DAY -> DAY_RANGES.forEach(days -> ranges.add(new SkillStatsReport.Range(granularity, days)));
                case MONTH -> MONTH_RANGES.forEach(months -> ranges.add(new SkillStatsReport.Range(granularity, months)));
                case YEAR -> ranges.add(new SkillStatsReport.Range(granularity, SkillStatsReport.Range.ALL));
            }
            rangeBox.getItems().setAll(ranges);
            rangeBox.getSelectionModel().selectFirst();
        } finally {
            loadingControls = false;
        }
    }

    private SkillStatsReport.Range selectedRange() {
        SkillStatsReport.Range selected = rangeBox.getValue();
        return selected != null ? selected : new SkillStatsReport.Range(granularity(), 0);
    }

    private SkillStatsReport.Granularity granularity() {
        if (granularityGroup.getSelectedToggle() == monthToggle) {
            return SkillStatsReport.Granularity.MONTH;
        }
        if (granularityGroup.getSelectedToggle() == yearToggle) {
            return SkillStatsReport.Granularity.YEAR;
        }
        return SkillStatsReport.Granularity.DAY;
    }

    private static String rangeLabel(SkillStatsReport.Range range) {
        return switch (range.granularity()) {
            case DAY -> I18n.get("settings.aiSkills.stats.range.days", range.periods());
            case MONTH -> I18n.get("settings.aiSkills.stats.range.months", range.periods());
            case YEAR -> I18n.get("settings.aiSkills.stats.range.all");
        };
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    /** Copies of the effective skills, taken on the FX thread so the background aggregation never shares them. */
    private List<AiSkill> effectiveSkillCopies() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return List.of();
        }
        List<AiSkill> effective = BuiltinAiSkillSupport.effectiveSkills(
            Objects.requireNonNullElse(settings.getAiSkills(), List.of()));
        List<AiSkill> copies = new ArrayList<>();
        for (AiSkill skill : effective) {
            if (skill != null && skill.getId() != null) {
                copies.add(new AiSkill(skill));
            }
        }
        return List.copyOf(copies);
    }

    private String badgesOf(AiSkill skill) {
        if (skill == null) {
            return "";
        }
        List<String> badges = new ArrayList<>();
        String status = AiSkillListFormat.badge(statusOf.apply(skill));
        if (!status.isEmpty()) {
            badges.add(status);
        }
        badges.addAll(AiSkillListFormat.korttyBadges(skill));
        return String.join(", ", badges);
    }

    private static ToggleButton granularityToggle(String key, SkillStatsReport.Granularity granularity) {
        ToggleButton toggle = new ToggleButton(I18n.get(key));
        toggle.setUserData(granularity);
        return toggle;
    }

    private static VBox kpiCard(String key, Label value) {
        Label caption = new Label(I18n.get(key));
        caption.setStyle(MutedTextStyle.HINT);
        value.setStyle(VALUE_STYLE);
        VBox card = new VBox(2, caption, value);
        card.setPadding(new Insets(8, 12, 8, 12));
        card.setStyle(CARD_STYLE);
        return card;
    }
}
