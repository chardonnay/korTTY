package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.ConnectionGroupColors;
import de.kortty.model.GroupPath;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Map;

/**
 * Picks the tab color of a folder (connection group) in the Connection Manager: a check box that
 * gives the folder a color of its own and the color picker with the same presets as the connection
 * editor. A folder without a color of its own names the color it takes from a folder above it.
 * The result is the {@link Choice} on OK and {@code null} on Cancel; the Connection Manager stores it.
 */
final class ConnectionGroupColorDialog extends ThemeAwareDialog<ConnectionGroupColorDialog.Choice> {

    /** The color the folder gets of its own ({@code #RRGGBB}), or {@code null} to remove it. */
    record Choice(String color) {
    }

    /**
     * @param groupPath the folder
     * @param colors    the tab colors of all folders by path, as stored
     */
    ConnectionGroupColorDialog(GroupPath groupPath, Map<String, String> colors) {
        String own = colors.get(ConnectionGroupColors.key(groupPath.getPath()));
        ConnectionGroupColors.Inherited above = ConnectionGroupColors.inheritedFromAbove(groupPath.getPath(), colors::get);

        setTitle(I18n.get("connManager.group.tabColor.title"));
        setHeaderText(I18n.get("connManager.group.tabColor.header", groupPath.getPath()));

        CheckBox enable = new CheckBox(I18n.get("connManager.group.tabColor.enable"));
        enable.setSelected(own != null);
        String initial = own != null ? own : above != null ? above.hex() : ConnectionColorSupport.PRESETS.get(0);
        ColorPicker picker = new ColorPicker(Color.web(initial));
        for (String preset : ConnectionColorSupport.PRESETS) {
            picker.getCustomColors().add(Color.web(preset));
        }
        picker.setAccessibleText(I18n.get("connManager.group.tabColor.enable"));
        picker.disableProperty().bind(enable.selectedProperty().not());
        // The picker sits below the check box, indented under its text, so the long label is never cut.
        HBox pickerRow = new HBox(picker);
        pickerRow.setAlignment(Pos.CENTER_LEFT);
        pickerRow.setPadding(new Insets(0, 0, 0, 28));
        VBox colorBox = new VBox(8, enable, pickerRow);

        VBox content = new VBox(12, colorBox);
        content.setPadding(new Insets(20));
        content.setPrefWidth(480);
        if (above != null) {
            Label inherited = new Label(I18n.get("connManager.group.tabColor.inherited",
                    I18n.get(TabColorPresentation.familyKey(ConnectionColorSupport.family(above.hex()))),
                    above.hex(), TabColorPresentation.groupLabel(above.groupPath())));
            inherited.setWrapText(true);
            content.getChildren().add(inherited);
        }
        Label info = new Label(I18n.get("connManager.group.tabColor.info"));
        info.setWrapText(true);
        info.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        content.getChildren().add(info);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(buttonType -> buttonType == ButtonType.OK
                ? new Choice(enable.isSelected() && picker.getValue() != null
                        ? TabColorPresentation.hexOf(picker.getValue()) : null)
                : null);
    }
}
