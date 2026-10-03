package de.kortty.ui;

import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Dialog for editing SSH tunnel configuration.
 *
 * <p>The host and port fields keep their model meaning for every type (see
 * {@link TunnelEditSupport}), but their labels and order follow the type: the listener comes
 * first, so a remote tunnel shows the address the SSH server listens on before the host on this
 * computer it forwards to. A listener that other computers may reach gets a warning.
 */
public class TunnelEditDialog extends ThemeAwareDialog<SSHTunnel> {

    private final SSHTunnel tunnel;
    private final boolean isNew;

    private final ComboBox<TunnelType> typeCombo;
    private final TextField localHostField;
    private final Spinner<Integer> localPortSpinner;
    private final TextField remoteHostField;
    private final Spinner<Integer> remotePortSpinner;
    private final TextField descriptionField;
    private final CheckBox enabledCheck;
    private final Label localHostLabel;
    private final Label localPortLabel;
    private final Label remoteHostLabel;
    private final Label remotePortLabel;
    private final Label bindWarningLabel;
    /** Grid row of the first host/port pair; the second pair follows directly. */
    private final int firstPairRow;

    public TunnelEditDialog(Stage owner, SSHTunnel tunnel) {
        this.tunnel = tunnel != null ? tunnel : new SSHTunnel();
        this.isNew = tunnel == null;

        setTitle(isNew ? I18n.get("tunnel.addTitle") : I18n.get("tunnel.editTitle"));
        setHeaderText(isNew ? I18n.get("tunnel.addHeader") : I18n.get("tunnel.editHeader"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        int row = 0;

        // Enabled checkbox. A tunnel you add is meant to run; it still needs the one-time
        // confirmation when the tab connects.
        enabledCheck = new CheckBox(I18n.get("tunnel.enable"));
        enabledCheck.setSelected(isNew || this.tunnel.isEnabled());
        grid.add(enabledCheck, 0, row++, 2, 1);

        // Tunnel type
        Label typeLabel = new Label(I18n.get("tunnel.type"));
        typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(TunnelType.values());
        typeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(TunnelType type) {
                return type == null ? "" : I18n.get(TunnelEditSupport.typeLabelKey(type));
            }

            @Override
            public TunnelType fromString(String text) {
                return typeCombo.getValue();
            }
        });
        typeCombo.setValue(this.tunnel.getType() != null ? this.tunnel.getType() : TunnelType.LOCAL);
        typeCombo.setPrefWidth(200);

        grid.add(typeLabel, 0, row);
        grid.add(typeCombo, 1, row++);

        // Local host: the bind address of a LOCAL or DYNAMIC tunnel, the target of a REMOTE one
        localHostField = new TextField(this.tunnel.getLocalHost() != null ? this.tunnel.getLocalHost() : "localhost");
        localHostField.setPromptText("localhost");
        localHostField.setPrefWidth(200);
        localHostLabel = new Label();

        // Local port
        localPortSpinner = new Spinner<>(1, 65535, this.tunnel.getLocalPort() > 0 ? this.tunnel.getLocalPort() : 8080);
        localPortSpinner.setEditable(true);
        localPortSpinner.setPrefWidth(200);
        localPortLabel = new Label();

        // Remote host: the target of a LOCAL tunnel, the bind address on the server of a REMOTE one
        remoteHostField = new TextField(this.tunnel.getRemoteHost() != null ? this.tunnel.getRemoteHost() : "localhost");
        remoteHostField.setPromptText("localhost");
        remoteHostField.setPrefWidth(200);
        remoteHostLabel = new Label();

        // Remote port (for LOCAL and REMOTE)
        remotePortSpinner = new Spinner<>(1, 65535, this.tunnel.getRemotePort() > 0 ? this.tunnel.getRemotePort() : 80);
        remotePortSpinner.setEditable(true);
        remotePortSpinner.setPrefWidth(200);
        remotePortLabel = new Label();

        // Four rows; updateFieldsForType puts the listener pair first.
        firstPairRow = row;
        grid.addRow(row++, localHostLabel, localHostField);
        grid.addRow(row++, localPortLabel, localPortSpinner);
        grid.addRow(row++, remoteHostLabel, remoteHostField);
        grid.addRow(row++, remotePortLabel, remotePortSpinner);

        // Warning for a listener other computers may reach
        bindWarningLabel = new Label();
        bindWarningLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: #e67e22;");
        bindWarningLabel.setWrapText(true);
        // Narrower than the label and field columns, so showing it never widens the dialog.
        bindWarningLabel.setMaxWidth(320);
        grid.add(bindWarningLabel, 0, row++, 2, 1);

        // Description
        Label descLabel = new Label(I18n.get("common.description") + ":");
        descriptionField = new TextField(this.tunnel.getDescription());
        descriptionField.setPromptText(I18n.get("tunnel.descriptionPrompt"));
        descriptionField.setPrefWidth(200);

        grid.add(descLabel, 0, row);
        grid.add(descriptionField, 1, row++);

        // Info label
        Label infoLabel = new Label(I18n.get("tunnel.info"));
        infoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        infoLabel.setMaxWidth(420);
        grid.add(infoLabel, 0, row++, 2, 1);

        // Labels, field order and warning follow the type and the bind address
        typeCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateFieldsForType(newVal));
        localHostField.textProperty().addListener((obs, oldVal, newVal) -> updateBindWarning());
        remoteHostField.textProperty().addListener((obs, oldVal, newVal) -> updateBindWarning());
        updateFieldsForType(typeCombo.getValue());

        VBox content = new VBox(grid);
        getDialogPane().setContent(content);

        // Buttons
        ButtonType saveButtonType = new ButtonType(I18n.get("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);

        // Validation
        Runnable validator = () -> {
            boolean valid = localPortSpinner.getValue() != null &&
                           localPortSpinner.getValue() > 0 &&
                           localPortSpinner.getValue() <= 65535;

            TunnelType type = typeCombo.getValue();
            if (type == TunnelType.LOCAL || type == TunnelType.REMOTE) {
                valid = valid && remotePortSpinner.getValue() != null &&
                       remotePortSpinner.getValue() > 0 &&
                       remotePortSpinner.getValue() <= 65535;
            }

            saveButton.setDisable(!valid);
        };

        localPortSpinner.valueProperty().addListener((obs, oldVal, newVal) -> validator.run());
        remotePortSpinner.valueProperty().addListener((obs, oldVal, newVal) -> validator.run());
        typeCombo.valueProperty().addListener((obs, oldVal, newVal) -> validator.run());
        validator.run();

        // Result converter
        setResultConverter(buttonType -> {
            if (buttonType == saveButtonType) {
                SSHTunnel result = new SSHTunnel();
                result.setEnabled(enabledCheck.isSelected());
                result.setType(typeCombo.getValue());
                result.setLocalHost(localHostField.getText().trim().isEmpty() ? "localhost" : localHostField.getText().trim());
                result.setLocalPort(localPortSpinner.getValue());

                TunnelType type = typeCombo.getValue();
                if (type == TunnelType.LOCAL || type == TunnelType.REMOTE) {
                    result.setRemoteHost(remoteHostField.getText().trim().isEmpty() ? "localhost" : remoteHostField.getText().trim());
                    result.setRemotePort(remotePortSpinner.getValue());
                }

                result.setDescription(descriptionField.getText() != null ? descriptionField.getText().trim() : null);
                return result;
            }
            return null;
        });
    }

    private void updateFieldsForType(TunnelType type) {
        TunnelEditSupport.FieldLabels labels = TunnelEditSupport.fieldLabels(type);
        localHostLabel.setText(I18n.get(labels.localHostKey()));
        localPortLabel.setText(I18n.get(labels.localPortKey()));
        remoteHostLabel.setText(I18n.get(labels.remoteHostKey()));
        remotePortLabel.setText(I18n.get(labels.remotePortKey()));

        // The listener pair first: local for LOCAL/DYNAMIC, remote for REMOTE.
        int localRow = labels.remoteFirst() ? firstPairRow + 2 : firstPairRow;
        int remoteRow = labels.remoteFirst() ? firstPairRow : firstPairRow + 2;
        GridPane.setRowIndex(localHostLabel, localRow);
        GridPane.setRowIndex(localHostField, localRow);
        GridPane.setRowIndex(localPortLabel, localRow + 1);
        GridPane.setRowIndex(localPortSpinner, localRow + 1);
        GridPane.setRowIndex(remoteHostLabel, remoteRow);
        GridPane.setRowIndex(remoteHostField, remoteRow);
        GridPane.setRowIndex(remotePortLabel, remoteRow + 1);
        GridPane.setRowIndex(remotePortSpinner, remoteRow + 1);

        // A dynamic tunnel (SOCKS proxy) only needs the local pair
        remoteHostField.setDisable(!labels.remoteUsed());
        remotePortSpinner.setDisable(!labels.remoteUsed());
        updateBindWarning();
    }

    private void updateBindWarning() {
        String warningKey = TunnelEditSupport.bindWarningKey(
            typeCombo.getValue(), localHostField.getText(), remoteHostField.getText());
        bindWarningLabel.setText(warningKey != null ? I18n.get(warningKey) : "");
        bindWarningLabel.setVisible(warningKey != null);
        bindWarningLabel.setManaged(warningKey != null);
        // The dialog is not resizable: fit it to the warning. The warning wraps narrower than the
        // fields, so only the height changes.
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            getDialogPane().getScene().getWindow().sizeToScene();
        }
    }
}
