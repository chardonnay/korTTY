package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.ConflictResolver.Resolution;
import de.kortty.ui.DialogThemeHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The "file already exists" dialog. It is not modal for the app: the tab stays usable while it is
 * open, and the transfer workers wait in {@link FxConflictResolver}. The window's close box and
 * Escape cancel the batch.
 */
public final class SftpConflictDialog {

    private SftpConflictDialog() {
    }

    /** A presenter that shows the dialog on the FX thread, owned by the window {@code owner} gives. */
    public static FxConflictResolver.Presenter presenter(Supplier<Window> owner) {
        return prompt -> Platform.runLater(() -> show(prompt, owner == null ? null : owner.get()));
    }

    private static void show(FxConflictResolver.Prompt prompt, Window owner) {
        if (prompt.isDone()) {
            return; // cancelled before the FX thread got here
        }
        Dialog<Resolution> dialog = build(SftpConflictViewModel.of(prompt.info()));
        dialog.initModality(Modality.NONE);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setOnHidden(event -> prompt.answer(dialog.getResult()));
        prompt.whenDone(() -> Platform.runLater(() -> {
            if (dialog.isShowing()) {
                dialog.setResult(Resolution.CANCEL_ALL);
                dialog.close();
            }
        }));
        dialog.show();
    }

    /**
     * The themed dialog for {@code model}, not yet shown and without owner or modality; its result
     * is the chosen {@link Resolution}, with {@link Resolution#CANCEL_ALL} for the cancel choice.
     * Package-private for the manual's screenshot generator.
     */
    static Dialog<Resolution> build(SftpConflictViewModel model) {
        Dialog<Resolution> dialog = new Dialog<>();
        dialog.setTitle(model.title());
        dialog.setHeaderText(model.header());

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        grid.add(new Label(model.rowLabels().get(0)), 0, 1);
        grid.add(new Label(model.rowLabels().get(1)), 0, 2);
        addSide(grid, 1, model.source(), model.newerLabel());
        addSide(grid, 2, model.existing(), model.newerLabel());

        VBox content = new VBox(10, grid);
        content.setPadding(new Insets(10, 12, 0, 12));
        for (String note : model.notes()) {
            Label label = new Label(note);
            label.setWrapText(true);
            label.setMaxWidth(460);
            content.getChildren().add(label);
        }
        CheckBox applyToAll = new CheckBox(model.applyToAll());
        content.getChildren().add(applyToAll);
        dialog.getDialogPane().setContent(content);

        Map<ButtonType, SftpConflictViewModel.Action> byButton = new HashMap<>();
        for (SftpConflictViewModel.Action action : model.actions()) {
            ButtonBar.ButtonData data = action.cancel() ? ButtonBar.ButtonData.CANCEL_CLOSE : ButtonBar.ButtonData.OTHER;
            ButtonType type = new ButtonType(action.label(), data);
            byButton.put(type, action);
            dialog.getDialogPane().getButtonTypes().add(type);
        }
        dialog.setResultConverter(button -> {
            SftpConflictViewModel.Action action = byButton.get(button);
            if (action == null || action.cancel()) {
                return Resolution.CANCEL_ALL;
            }
            return new Resolution(action.action(), applyToAll.isSelected());
        });
        DialogThemeHelper.applyTheme(dialog);
        return dialog;
    }

    private static void addSide(GridPane grid, int column, SftpConflictViewModel.Side side, String newerLabel) {
        Label title = new Label(side.label());
        title.setStyle("-fx-font-weight: bold;");
        grid.add(title, column, 0);
        grid.add(new Label(side.size()), column, 1);
        Label modified = new Label(side.newer() ? side.modified() + " (" + newerLabel + ")" : side.modified());
        if (side.newer()) {
            modified.setStyle("-fx-font-weight: bold;");
        }
        grid.add(modified, column, 2);
    }
}
