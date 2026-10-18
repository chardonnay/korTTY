package de.kortty.ui;

import de.kortty.core.sftp.FolderComparison;
import de.kortty.core.sftp.FolderComparison.Entry;
import de.kortty.core.sftp.FolderComparison.State;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * The result of comparing two folders on the same side, two local ones or two on the server, which
 * the panels cannot colour as they do for a local and a server folder. It lists every path below
 * the two folders with how it compares and its size and time on each side; a double-click on a
 * file that exists on both sides opens the two files in {@link SftpFileDiffDialog}. Read-only and
 * non-modal: copying within one side is not what a comparison offers.
 */
final class SftpFolderCompareDialog extends ThemeAwareDialog<Void> {

    /** One listed path. */
    record Row(String path, State state, Entry left, Entry right) {
        boolean bothFiles() {
            return left != null && right != null && !left.directory() && !right.directory();
        }
    }

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private final TableView<Row> table = new TableView<>();
    private final CheckBox differencesOnly = new CheckBox(I18n.get("sftp.compare.differencesOnly"));

    /**
     * @param openFileDiff opens the two files at a relative path side by side
     * @param formatSize   formats a byte count as the panels do
     */
    SftpFolderCompareDialog(Window owner, String leftLabel, String rightLabel, FolderComparison comparison,
                            Consumer<String> openFileDiff, LongFunction<String> formatSize) {
        setTitle(I18n.get("sftp.compare.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        List<Row> rows = rows(comparison);

        Label title = new Label(leftLabel + "  ⇄  " + rightLabel);
        title.setStyle("-fx-font-weight: bold;");
        title.setWrapText(true);
        FlowPane counts = new FlowPane(12, 4);
        FolderComparison.Counts summary = comparison.counts();
        if (summary.differing() == 0) {
            counts.getChildren().add(new Label(I18n.get("sftp.compare.identical")));
        } else {
            addCount(counts, State.ONLY_LOCAL, summary.onlyLocal());
            addCount(counts, State.NEWER_LOCAL, summary.newerLocal());
            addCount(counts, State.ONLY_REMOTE, summary.onlyRemote());
            addCount(counts, State.NEWER_REMOTE, summary.newerRemote());
            addCount(counts, State.DIFFERENT, summary.different());
            addCount(counts, State.SAME, summary.same());
        }

        TableColumn<Row, String> path = new TableColumn<>(I18n.get("sftp.search.column.path"));
        path.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().path()
            + (isFolder(cell.getValue()) ? "/" : "")));
        path.setPrefWidth(380);
        TableColumn<Row, String> state = new TableColumn<>(I18n.get("sftp.folderCompare.column.state"));
        state.setCellValueFactory(cell -> new ReadOnlyStringWrapper(stateLabel(cell.getValue().state())));
        state.setPrefWidth(170);
        TableColumn<Row, String> leftColumn = new TableColumn<>(I18n.get("sftp.folderCompare.column.left"));
        leftColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(describe(cell.getValue().left(), formatSize)));
        leftColumn.setPrefWidth(230);
        TableColumn<Row, String> rightColumn = new TableColumn<>(I18n.get("sftp.folderCompare.column.right"));
        rightColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(describe(cell.getValue().right(), formatSize)));
        rightColumn.setPrefWidth(230);
        table.getColumns().addAll(List.of(path, state, leftColumn, rightColumn));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getStyleClass().add("sftp-folder-compare-table");
        table.setRowFactory(view -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && row.getItem() != null && row.getItem().bothFiles()) {
                    openFileDiff.accept(row.getItem().path());
                }
            });
            return row;
        });

        FilteredList<Row> shown = new FilteredList<>(FXCollections.observableArrayList(rows));
        differencesOnly.setSelected(summary.differing() > 0);
        differencesOnly.selectedProperty().addListener((obs, was, only) -> shown.setPredicate(filter(only)));
        shown.setPredicate(filter(differencesOnly.isSelected()));
        table.setItems(shown);
        table.setPlaceholder(new Label(I18n.get("sftp.compare.identical")));

        Label hint = new Label(I18n.get("sftp.folderCompare.hint"));
        hint.setStyle("-fx-font-size: 0.8462em; -fx-opacity: 0.75;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(10, hint, spacer, differencesOnly);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, title, counts, table, bottom);
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(1100);
        getDialogPane().setPrefHeight(680);
        setResultConverter(buttonType -> null);
    }

    /** The listed rows; for tests. */
    TableView<Row> table() {
        return table;
    }

    CheckBox differencesOnly() {
        return differencesOnly;
    }

    static List<Row> rows(FolderComparison comparison) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, State> entry : comparison.states().entrySet()) {
            rows.add(new Row(entry.getKey(), entry.getValue(),
                comparison.local(entry.getKey()).orElse(null), comparison.remote(entry.getKey()).orElse(null)));
        }
        return rows;
    }

    private static java.util.function.Predicate<Row> filter(boolean differencesOnly) {
        return row -> !differencesOnly || row.state() != State.SAME;
    }

    private static boolean isFolder(Row row) {
        Entry entry = row.left() != null ? row.left() : row.right();
        return entry != null && entry.directory();
    }

    /** How a state reads for two folders on the same side: left and right, not here and on the server. */
    static String stateLabel(State state) {
        return I18n.get(switch (state) {
            case ONLY_LOCAL -> "sftp.folderCompare.state.onlyLeft";
            case ONLY_REMOTE -> "sftp.folderCompare.state.onlyRight";
            case NEWER_LOCAL -> "sftp.folderCompare.state.newerLeft";
            case NEWER_REMOTE -> "sftp.folderCompare.state.newerRight";
            case DIFFERENT -> "sftp.folderCompare.state.different";
            case SAME -> "sftp.folderCompare.state.same";
            case CONTENT_DIFFERS -> "sftp.folderCompare.state.contentDiffers";
        });
    }

    private static void addCount(FlowPane counts, State state, int count) {
        if (count > 0) {
            Label label = new Label(stateLabel(state) + ": " + count);
            label.setStyle("-fx-opacity: 0.85;");
            counts.getChildren().add(label);
        }
    }

    private static String describe(Entry entry, LongFunction<String> formatSize) {
        if (entry == null) {
            return "—";
        }
        String time = new SimpleDateFormat(DATE_FORMAT).format(new Date(entry.modifiedMillis()));
        return entry.directory() ? time : formatSize.apply(entry.size()) + "  ·  " + time;
    }
}
