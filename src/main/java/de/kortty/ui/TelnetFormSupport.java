package de.kortty.ui;

import de.kortty.model.ConnectionProtocol;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;

/** Shared controls and port handling for the two connection forms. */
final class TelnetFormSupport {

    private final Spinner<Integer> port;
    private boolean changingPort;
    private boolean customPort;

    TelnetFormSupport(Spinner<Integer> port, ConnectionProtocol protocol) {
        this.port = port;
        customPort = port.getValue() != defaultPort(protocol);
        port.getEditor().textProperty().addListener((obs, oldValue, newValue) -> {
            if (!changingPort) {
                customPort = true;
            }
        });
    }

    void loadPort(int value, ConnectionProtocol protocol) {
        setPort(value);
        customPort = value != defaultPort(protocol);
    }

    void protocolChanged(ConnectionProtocol oldProtocol, ConnectionProtocol newProtocol) {
        if (!customPort && (oldProtocol == ConnectionProtocol.TELNET || newProtocol == ConnectionProtocol.TELNET)) {
            setPort(defaultPort(newProtocol));
        }
    }

    private void setPort(int value) {
        changingPort = true;
        try {
            port.getValueFactory().setValue(value);
            port.getEditor().setText(port.getValueFactory().getConverter().toString(port.getValue()));
        } finally {
            changingPort = false;
        }
    }

    int enteredPort() {
        try {
            int value = Integer.parseInt(port.getEditor().getText().trim());
            return value >= 1 && value <= 65535 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int defaultPort(ConnectionProtocol protocol) {
        return protocol == ConnectionProtocol.TELNET ? ConnectionProtocol.TELNET_PORT : 22;
    }

    static Label warning() {
        Label label = new Label(I18n.get("telnet.warning"));
        label.setWrapText(true);
        label.setMaxWidth(400);
        return label;
    }

    static void hideForTelnet(GridPane grid, int first, int last, javafx.beans.value.ObservableBooleanValue telnet) {
        for (Node node : grid.getChildren()) {
            Integer row = GridPane.getRowIndex(node);
            if (row != null && row >= first && row <= last) {
                node.visibleProperty().bind(javafx.beans.binding.Bindings.not(telnet));
                node.managedProperty().bind(node.visibleProperty());
            }
        }
    }
}
