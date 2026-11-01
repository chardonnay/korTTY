package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Chooses one cleanup step. Each step makes its own proposal and asks for its own confirmation; the dialog never starts
 * two at once. Shortening the user's own text is a separate step behind its own checkbox.
 */
final class AiMemoryCleanupDialog extends ThemeAwareDialog<AiMemoryCleanupDialog.Action> {

    /** The steps the dialog offers. */
    enum Action {
        MERGE,
        ARCHIVE,
        SHORTEN
    }

    private AiMemoryCleanupDialog(Window owner, boolean aiAvailable) {
        setTitle(I18n.get("settings.ai.memory.cleanup.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        String noProfile = I18n.get("settings.ai.memory.cleanup.noProfile");

        Button merge = new Button(I18n.get("settings.ai.memory.cleanup.merge"));
        merge.setDisable(!aiAvailable);
        if (!aiAvailable) {
            merge.setTooltip(new Tooltip(noProfile));
        }
        merge.setOnAction(event -> choose(Action.MERGE));

        Button archive = new Button(I18n.get("settings.ai.memory.cleanup.archive"));
        archive.setOnAction(event -> choose(Action.ARCHIVE));

        CheckBox shortenCheck = new CheckBox(I18n.get("settings.ai.memory.cleanup.userShorten"));
        Button shorten = new Button(I18n.get("settings.ai.memory.cleanup.start"));
        shorten.setDisable(true);
        shorten.setOnAction(event -> choose(Action.SHORTEN));
        shortenCheck.setDisable(!aiAvailable);
        shortenCheck.selectedProperty().addListener((obs, was, now) -> shorten.setDisable(!now || !aiAvailable));

        VBox root = new VBox(10,
            section("settings.ai.memory.cleanup.merge", "settings.ai.memory.cleanup.mergeHint", merge),
            new Separator(),
            section("settings.ai.memory.cleanup.archive", "settings.ai.memory.cleanup.archiveHint", archive),
            new Separator(),
            shortenCheck,
            hint("settings.ai.memory.cleanup.userShortenHint"),
            shorten);
        root.setPadding(new Insets(14));
        root.setPrefWidth(560);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        setResultConverter(button -> null);
    }

    private void choose(Action action) {
        setResult(action);
        close();
    }

    private static VBox section(String titleKey, String hintKey, Button button) {
        Label title = new Label(I18n.get(titleKey));
        title.setStyle("-fx-font-weight: bold;");
        return new VBox(6, title, hint(hintKey), button);
    }

    private static Label hint(String key) {
        Label label = new Label(I18n.get(key));
        label.setWrapText(true);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }

    /** The step the user picked, or empty when the dialog was closed. */
    static Optional<Action> choose(Window owner, boolean aiAvailable) {
        return new AiMemoryCleanupDialog(owner, aiAvailable).showAndWait();
    }
}
