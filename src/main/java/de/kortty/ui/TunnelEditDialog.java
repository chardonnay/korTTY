package de.kortty.ui;

import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Dialog for editing SSH tunnel configuration.
 */
public class TunnelEditDialog extends Dialog<SSHTunnel> {
    
    private final SSHTunnel tunnel;
    private final boolean isNew;
    
    private final ComboBox<TunnelType> typeCombo;
    private final TextField localHostField;
    private final Spinner<Integer> localPortSpinner;
    private final TextField remoteHostField;
    private final Spinner<Integer> remotePortSpinner;
    private final TextField descriptionField;
    private final CheckBox enabledCheck;
    
    public TunnelEditDialog(Stage owner, SSHTunnel tunnel) {
        this.tunnel = tunnel != null ? tunnel : new SSHTunnel();
        this.isNew = tunnel == null;
        
        setTitle(isNew ? "SSH-Tunnel hinzufügen" : "SSH-Tunnel bearbeiten");
        setHeaderText(isNew ? "Neuen SSH-Tunnel konfigurieren" : "SSH-Tunnel bearbeiten");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(false);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Enabled checkbox
        enabledCheck = new CheckBox("Tunnel aktivieren");
        enabledCheck.setSelected(this.tunnel.isEnabled());
        grid.add(enabledCheck, 0, row++, 2, 1);
        
        // Tunnel type
        Label typeLabel = new Label("Typ:");
        typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(TunnelType.values());
        typeCombo.setValue(this.tunnel.getType());
        typeCombo.setPrefWidth(200);
        
        // Update fields based on type
        typeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateFieldsForType(newVal);
        });
        
        grid.add(typeLabel, 0, row);
        grid.add(typeCombo, 1, row++);
        
        // Local host (for LOCAL and DYNAMIC)
        localHostField = new TextField(this.tunnel.getLocalHost() != null ? this.tunnel.getLocalHost() : "localhost");
        localHostField.setPromptText("localhost");
        localHostField.setPrefWidth(200);
        
        Label localHostLabel = new Label("Lokaler Host:");
        grid.add(localHostLabel, 0, row);
        grid.add(localHostField, 1, row++);
        
        // Local port
        localPortSpinner = new Spinner<>(1, 65535, this.tunnel.getLocalPort() > 0 ? this.tunnel.getLocalPort() : 8080);
        localPortSpinner.setEditable(true);
        localPortSpinner.setPrefWidth(200);
        
        Label localPortLabel = new Label("Lokaler Port:");
        grid.add(localPortLabel, 0, row);
        grid.add(localPortSpinner, 1, row++);
        
        // Remote host (for LOCAL and REMOTE)
        remoteHostField = new TextField(this.tunnel.getRemoteHost() != null ? this.tunnel.getRemoteHost() : "localhost");
        remoteHostField.setPromptText("localhost");
        remoteHostField.setPrefWidth(200);
        
        Label remoteHostLabel = new Label("Remote Host:");
        grid.add(remoteHostLabel, 0, row);
        grid.add(remoteHostField, 1, row++);
        
        // Remote port (for LOCAL and REMOTE)
        remotePortSpinner = new Spinner<>(1, 65535, this.tunnel.getRemotePort() > 0 ? this.tunnel.getRemotePort() : 80);
        remotePortSpinner.setEditable(true);
        remotePortSpinner.setPrefWidth(200);
        
        Label remotePortLabel = new Label("Remote Port:");
        grid.add(remotePortLabel, 0, row);
        grid.add(remotePortSpinner, 1, row++);
        
        // Description
        Label descLabel = new Label("Beschreibung:");
        descriptionField = new TextField(this.tunnel.getDescription());
        descriptionField.setPromptText("Optionale Beschreibung");
        descriptionField.setPrefWidth(200);
        
        grid.add(descLabel, 0, row);
        grid.add(descriptionField, 1, row++);
        
        // Info label
        Label infoLabel = new Label();
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        grid.add(infoLabel, 0, row++, 2, 1);
        
        // Update info based on type
        updateFieldsForType(this.tunnel.getType());
        updateInfoLabel(typeCombo.getValue(), infoLabel);
        typeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateInfoLabel(newVal, infoLabel);
        });
        
        VBox content = new VBox(grid);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
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
                
                result.setDescription(descriptionField.getText().trim());
                return result;
            }
            return null;
        });
    }
    
    private void updateFieldsForType(TunnelType type) {
        if (type == TunnelType.DYNAMIC) {
            // Dynamic port forwarding only needs local port
            remoteHostField.setDisable(true);
            remotePortSpinner.setDisable(true);
        } else {
            // LOCAL and REMOTE need both local and remote
            remoteHostField.setDisable(false);
            remotePortSpinner.setDisable(false);
        }
    }
    
    private void updateInfoLabel(TunnelType type, Label infoLabel) {
        switch (type) {
            case LOCAL:
                infoLabel.setText("Local Port Forwarding (-L): Leitet lokalen Port zu Remote-Host:Port weiter.\n" +
                                 "Beispiel: localhost:8080 -> remote.host:80");
                break;
            case REMOTE:
                infoLabel.setText("Remote Port Forwarding (-R): Leitet Remote-Port zu lokalem Host:Port weiter.\n" +
                                 "Beispiel: remote.host:9090 -> localhost:8080");
                break;
            case DYNAMIC:
                infoLabel.setText("Dynamic Port Forwarding (-D): Erstellt einen SOCKS-Proxy auf lokalem Port.\n" +
                                 "Beispiel: SOCKS-Proxy auf localhost:1080");
                break;
        }
    }
}
