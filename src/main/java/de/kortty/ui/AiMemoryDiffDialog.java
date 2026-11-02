package de.kortty.ui;

import de.kortty.ui.EditorSettingsHelper.Settings;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * A side-by-side diff of a memory file: the text as it is and the text a change would save. Only the apply button
 * saves; closing the dialog keeps the file as it was.
 */
final class AiMemoryDiffDialog extends ThemeAwareDialog<Boolean> {

    private final MonacoDiffPane diffPane = new MonacoDiffPane();

    private AiMemoryDiffDialog(Window owner, String title, String header, String before, String after,
                               String applyLabel) {
        setTitle(title);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        Label headerLabel = new Label(header);
        headerLabel.setWrapText(true);
        Settings settings = EditorSettingsHelper.loadSnippetSettings();
        diffPane.setFont(settings.fontFamily(), settings.fontSize());
        diffPane.setThemeColors(settings.foregroundColor(), settings.backgroundColor());
        diffPane.setComparison(before, after, "markdown", "markdown");
        VBox root = new VBox(10, headerLabel, diffPane);
        root.setPadding(new Insets(14));
        VBox.setVgrow(diffPane, Priority.ALWAYS);
        getDialogPane().setContent(root);
        ButtonType apply = new ButtonType(applyLabel, ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        getDialogPane().setPrefSize(980, 640);
        setResultConverter(button -> button == apply);
        setOnHidden(event -> diffPane.dispose());
    }

    /** Shows the diff and returns whether the user confirmed it. */
    static boolean confirm(Window owner, String title, String header, String before, String after, String applyLabel) {
        Optional<Boolean> answer = new AiMemoryDiffDialog(owner, title, header, before, after, applyLabel).showAndWait();
        return answer.orElse(false);
    }
}
