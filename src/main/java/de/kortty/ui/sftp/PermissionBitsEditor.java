package de.kortty.ui.sftp;

import de.kortty.ui.I18n;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;

import java.util.Optional;

/**
 * The checkboxes of "Set Owner/Permissions": a grid of owner, group and others against read, write
 * and execute, kept in step with the octal permissions field in both directions. Ticking a box
 * writes the three digits into the field; typing three octal digits ticks the boxes. An empty field
 * (keep the current mode) or an invalid one leaves the boxes as they are, and the label beside the
 * field says so instead of showing {@code rwxr-xr-x}.
 */
public final class PermissionBitsEditor {

    private final TextField octalField;
    private final CheckBox[] boxes = new CheckBox[PermissionBits.COUNT];
    private final GridPane grid = new GridPane();
    private final Label symbolicLabel = new Label();
    /** Set while one side writes the other, so the change does not echo back. */
    private boolean syncing;

    public PermissionBitsEditor(TextField octalField) {
        this.octalField = octalField;
        // Opacity rather than a colour token: dialogs run under Modena and AtlantaFX alike.
        symbolicLabel.setStyle("-fx-font-family: \"Menlo\", \"Consolas\", monospace; -fx-opacity: 0.75;");
        buildGrid();
        octalField.textProperty().addListener((obs, oldText, newText) -> fieldChanged(newText));
        fieldChanged(octalField.getText());
    }

    /** The checkbox grid, to be placed below the permissions field. */
    public GridPane grid() {
        return grid;
    }

    /** {@code rwxr-xr-x}, "unchanged" or the invalid-mode hint, to be placed beside the field. */
    public Label symbolicLabel() {
        return symbolicLabel;
    }

    /** The checkbox for {@code index} (0 owner read ... 8 others execute); for tests. */
    CheckBox box(int index) {
        return boxes[index];
    }

    private void buildGrid() {
        grid.getStyleClass().add("sftp-permission-bits");
        grid.setHgap(18);
        grid.setVgap(6);
        grid.setPadding(new Insets(0, 0, 0, 2));
        String[] columns = {"sftp.read", "sftp.write", "sftp.execute"};
        String[] rows = {"sftp.ownerLabel", "sftp.groupLabel", "sftp.otherLabel"};
        for (int column = 0; column < columns.length; column++) {
            Label header = new Label(I18n.get(columns[column]));
            header.getStyleClass().add("sftp-permission-bits-header");
            if (column == 2) {
                header.setTooltip(new Tooltip(I18n.get("sftp.setOwner.executeTooltip")));
            }
            GridPane.setHalignment(header, HPos.CENTER);
            grid.add(header, column + 1, 0);
        }
        for (int row = 0; row < rows.length; row++) {
            grid.add(new Label(I18n.get(rows[row])), 0, row + 1);
            for (int column = 0; column < 3; column++) {
                CheckBox box = new CheckBox();
                box.setAccessibleText(I18n.get(rows[row]) + " " + I18n.get(columns[column]));
                box.selectedProperty().addListener((obs, was, is) -> boxesChanged());
                GridPane.setHalignment(box, HPos.CENTER);
                boxes[row * 3 + column] = box;
                grid.add(box, column + 1, row + 1);
            }
        }
    }

    private void boxesChanged() {
        if (syncing) {
            return;
        }
        boolean[] bits = currentBits();
        syncing = true;
        try {
            octalField.setText(PermissionBits.toOctal(bits));
        } finally {
            syncing = false;
        }
        symbolicLabel.setText(PermissionBits.toSymbolic(bits));
    }

    private void fieldChanged(String text) {
        if (syncing) {
            return;
        }
        Optional<boolean[]> parsed = PermissionBits.parseOctal(text);
        if (parsed.isPresent()) {
            boolean[] bits = parsed.get();
            syncing = true;
            try {
                for (int index = 0; index < boxes.length; index++) {
                    boxes[index].setSelected(bits[index]);
                }
            } finally {
                syncing = false;
            }
            symbolicLabel.setText(PermissionBits.toSymbolic(bits));
        } else if (text == null || text.isBlank()) {
            symbolicLabel.setText(I18n.get("sftp.setOwner.unchanged"));
        } else {
            symbolicLabel.setText(I18n.get("sftp.setOwner.invalidMode"));
        }
    }

    private boolean[] currentBits() {
        boolean[] bits = new boolean[PermissionBits.COUNT];
        for (int index = 0; index < boxes.length; index++) {
            bits[index] = boxes[index].isSelected();
        }
        return bits;
    }
}
