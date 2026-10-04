package de.kortty.ui.sftp;

import de.kortty.core.remote.search.RemoteSearchHit;
import de.kortty.core.remote.search.RemoteSearchOutcome;
import de.kortty.ui.I18n;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The results mode of the SFTP manager's remote panel: the hits of a recursive search with their
 * path below the searched folder, a status line ("n results, stopped at limit") and a way back to
 * the folder listing. Double-click or Enter on a hit opens its folder with the hit selected. FX
 * thread only.
 */
public final class RemoteSearchResultsPane extends VBox {

    private final ObservableList<RemoteSearchHit> hits = FXCollections.observableArrayList();
    private final TableView<RemoteSearchHit> table = new TableView<>(hits);
    private final Label status = new Label();
    private Consumer<RemoteSearchHit> onOpen = hit -> { };

    public RemoteSearchResultsPane(Runnable onBack) {
        super(4);
        getStyleClass().add("sftp-search-results");

        Button back = new Button(I18n.get("sftp.search.back"));
        back.setOnAction(e -> onBack.run());
        status.setMaxWidth(Double.MAX_VALUE);
        HBox header = new HBox(8, back, status);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 2, 0));
        HBox.setHgrow(status, Priority.ALWAYS);

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getStyleClass().add("file-browser-table");
        table.setPlaceholder(new Label(I18n.get("sftp.search.noResults")));
        TableColumn<RemoteSearchHit, String> pathColumn = new TableColumn<>(I18n.get("sftp.search.column.path"));
        pathColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().relativePath()));
        pathColumn.setPrefWidth(420);
        TableColumn<RemoteSearchHit, String> kindColumn = new TableColumn<>(I18n.get("sftp.search.column.type"));
        kindColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(kindLabel(cell.getValue().kind())));
        kindColumn.setPrefWidth(90);
        kindColumn.setMaxWidth(140);
        table.getColumns().addAll(List.of(pathColumn, kindColumn));
        table.setRowFactory(tv -> {
            TableRow<RemoteSearchHit> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onOpen.accept(row.getItem());
                }
            });
            return row;
        });
        table.setOnKeyPressed(e -> {
            RemoteSearchHit selected = table.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && selected != null) {
                onOpen.accept(selected);
                e.consume();
            }
        });

        getChildren().addAll(header, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    /** What double-click or Enter on a hit does. */
    public void setOnOpen(Consumer<RemoteSearchHit> handler) {
        this.onOpen = handler == null ? hit -> { } : handler;
    }

    public void clear() {
        hits.clear();
    }

    public void addAll(List<RemoteSearchHit> batch) {
        hits.addAll(batch);
    }

    public int size() {
        return hits.size();
    }

    public void setStatus(String text) {
        status.setText(text);
    }

    /** The status while a search runs. */
    public static String runningText(String root, int count) {
        return I18n.get("sftp.search.running", root, count);
    }

    /** The status once a search ended. */
    public static String outcomeText(RemoteSearchOutcome outcome) {
        int n = outcome.results();
        return switch (outcome.stop()) {
            case COMPLETED -> I18n.get("sftp.search.done", n);
            case RESULT_LIMIT -> I18n.get("sftp.search.stoppedAtLimit", n);
            case TIME_LIMIT -> I18n.get("sftp.search.stoppedAtTime", n);
            case ENTRY_LIMIT -> I18n.get("sftp.search.stoppedAtEntries", n);
            case CANCELLED -> I18n.get("sftp.search.cancelled", n);
        };
    }

    static String kindLabel(RemoteSearchHit.Kind kind) {
        return switch (kind) {
            case DIRECTORY -> I18n.get("sftp.search.kind.directory");
            case FILE -> I18n.get("sftp.search.kind.file");
            case SYMLINK -> I18n.get("sftp.search.kind.link");
            case OTHER, UNKNOWN -> "";
        };
    }
}
