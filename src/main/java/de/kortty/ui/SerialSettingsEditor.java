package de.kortty.ui;

import de.kortty.core.SerialPortCatalog;
import de.kortty.model.SerialSettings;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The serial-port rows of the connection editor and Quick Connect: port (picked from the ports
 * present or typed), speed, data format, flow control, what Enter sends, and the DTR/RTS/echo
 * options. Both dialogs add the rows to their connection grid and enable them for
 * {@link de.kortty.model.ConnectionProtocol#SERIAL} only.
 */
final class SerialSettingsEditor {

    private final ComboBox<String> portCombo = new ComboBox<>();
    private final Button refreshButton = new Button(I18n.get("connEdit.serial.refresh"));
    private final ComboBox<String> baudCombo = new ComboBox<>();
    private final ComboBox<Integer> dataBitsCombo = new ComboBox<>();
    private final ComboBox<SerialSettings.Parity> parityCombo = new ComboBox<>();
    private final ComboBox<SerialSettings.StopBits> stopBitsCombo = new ComboBox<>();
    private final ComboBox<SerialSettings.FlowControl> flowControlCombo = new ComboBox<>();
    private final ComboBox<SerialSettings.LineEnding> lineEndingCombo = new ComboBox<>();
    private final CheckBox assertDtrCheck = new CheckBox(I18n.get("connEdit.serial.assertDtr"));
    private final CheckBox assertRtsCheck = new CheckBox(I18n.get("connEdit.serial.assertRts"));
    private final CheckBox localEchoCheck = new CheckBox(I18n.get("connEdit.serial.localEcho"));
    private final List<Label> labels = new ArrayList<>();
    /** Port name to its description label, from the last scan; typed names have no entry. */
    private final Map<String, String> portLabels = new LinkedHashMap<>();
    private Runnable onChange = () -> { };

    SerialSettingsEditor(SerialSettings initial) {
        SerialSettings settings = initial != null ? initial : new SerialSettings();

        portCombo.setEditable(true);
        portCombo.setPromptText(I18n.get("connEdit.serial.portPrompt"));
        portCombo.setMaxWidth(Double.MAX_VALUE);
        portCombo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : portLabels.getOrDefault(item, item));
            }
        });
        portCombo.setValue(settings.getPortName());
        portCombo.getEditor().textProperty().addListener((obs, old, value) -> onChange.run());
        portCombo.valueProperty().addListener((obs, old, value) -> onChange.run());
        HBox.setHgrow(portCombo, Priority.ALWAYS);
        refreshButton.setTooltip(new Tooltip(I18n.get("connEdit.serial.refreshTooltip")));
        refreshButton.setOnAction(e -> refreshPorts());

        baudCombo.setEditable(true);
        for (int rate : SerialSettings.COMMON_BAUD_RATES) {
            baudCombo.getItems().add(String.valueOf(rate));
        }
        baudCombo.setValue(String.valueOf(settings.getBaudRate()));
        baudCombo.setPrefWidth(UiFontScaleSupport.scaleDimension(120, true));
        baudCombo.getEditor().textProperty().addListener((obs, old, value) -> onChange.run());

        for (int bits = SerialSettings.MAX_DATA_BITS; bits >= SerialSettings.MIN_DATA_BITS; bits--) {
            dataBitsCombo.getItems().add(bits);
        }
        dataBitsCombo.setValue(settings.getDataBits());
        configure(parityCombo, SerialSettings.Parity.values(), settings.getParity(),
                p -> I18n.get("connEdit.serial.parity." + camel(p.name())));
        configure(stopBitsCombo, SerialSettings.StopBits.values(), settings.getStopBits(),
                s -> I18n.get("connEdit.serial.stopBits." + camel(s.name())));
        configure(flowControlCombo, SerialSettings.FlowControl.values(), settings.getFlowControl(),
                f -> I18n.get("connEdit.serial.flow." + camel(f.name())));
        configure(lineEndingCombo, SerialSettings.LineEnding.values(), settings.getLineEnding(),
                l -> I18n.get("connEdit.serial.lineEnding." + l.name().toLowerCase(java.util.Locale.ROOT)));
        flowControlCombo.valueProperty().addListener((obs, old, value) -> updateHandshakeChecks());

        assertDtrCheck.setSelected(settings.isAssertDtr());
        assertRtsCheck.setSelected(settings.isAssertRts());
        localEchoCheck.setSelected(settings.isLocalEcho());
        assertDtrCheck.setTooltip(new Tooltip(I18n.get("connEdit.serial.assertDtr.tooltip")));
        localEchoCheck.setTooltip(new Tooltip(I18n.get("connEdit.serial.localEcho.tooltip")));
        updateHandshakeChecks();
    }

    /** Shows {@code settings} (a saved connection picked in Quick Connect); defaults when null. */
    void load(SerialSettings settings) {
        SerialSettings shown = settings != null ? settings : new SerialSettings();
        portCombo.setValue(shown.getPortName());
        portCombo.getEditor().setText(shown.getPortName() != null ? shown.getPortName() : "");
        baudCombo.setValue(String.valueOf(shown.getBaudRate()));
        baudCombo.getEditor().setText(String.valueOf(shown.getBaudRate()));
        dataBitsCombo.setValue(shown.getDataBits());
        parityCombo.setValue(shown.getParity());
        stopBitsCombo.setValue(shown.getStopBits());
        flowControlCombo.setValue(shown.getFlowControl());
        lineEndingCombo.setValue(shown.getLineEnding());
        assertDtrCheck.setSelected(shown.isAssertDtr());
        assertRtsCheck.setSelected(shown.isAssertRts());
        localEchoCheck.setSelected(shown.isLocalEcho());
        updateHandshakeChecks();
    }

    /** Adds the rows to {@code grid} from {@code row}; returns the next free row. */
    int addTo(GridPane grid, int row) {
        HBox portBox = new HBox(6, portCombo, refreshButton);
        portBox.setAlignment(Pos.CENTER_LEFT);
        row = addRow(grid, row, "connEdit.serial.port", portBox);
        row = addRow(grid, row, "connEdit.serial.baudRate", baudCombo);
        HBox frameBox = new HBox(6, dataBitsCombo, parityCombo, stopBitsCombo);
        frameBox.setAlignment(Pos.CENTER_LEFT);
        row = addRow(grid, row, "connEdit.serial.frame", frameBox);
        row = addRow(grid, row, "connEdit.serial.flowControl", flowControlCombo);
        row = addRow(grid, row, "connEdit.serial.lineEnding", lineEndingCombo);
        HBox optionBox = new HBox(12, assertDtrCheck, assertRtsCheck, localEchoCheck);
        optionBox.setAlignment(Pos.CENTER_LEFT);
        return addRow(grid, row, "connEdit.serial.options", optionBox);
    }

    private int addRow(GridPane grid, int row, String labelKey, javafx.scene.Node control) {
        Label label = new Label(I18n.get(labelKey));
        labels.add(label);
        grid.add(label, 0, row);
        grid.add(control, 1, row);
        return row + 1;
    }

    /** Called whenever the port or speed text changes, so the dialog can re-validate. */
    void setOnChange(Runnable onChange) {
        this.onChange = onChange != null ? onChange : () -> { };
    }

    /** Enables the rows for a serial connection; disabled rows keep their values. */
    void setActive(boolean active) {
        for (Label label : labels) {
            label.setDisable(!active);
        }
        portCombo.setDisable(!active);
        refreshButton.setDisable(!active);
        baudCombo.setDisable(!active);
        dataBitsCombo.setDisable(!active);
        parityCombo.setDisable(!active);
        stopBitsCombo.setDisable(!active);
        flowControlCombo.setDisable(!active);
        lineEndingCombo.setDisable(!active);
        localEchoCheck.setDisable(!active);
        updateHandshakeChecks();
        if (active && portLabels.isEmpty()) {
            refreshPorts();
        }
    }

    /** The settings the rows show, or {@code null} when {@link #validationErrorKey()} reports a problem. */
    SerialSettings toSettings() {
        if (validationErrorKey() != null) {
            return null;
        }
        SerialSettings settings = new SerialSettings();
        settings.setPortName(portText());
        settings.setBaudRate(parseBaudRate(baudCombo.getEditor().getText()));
        settings.setDataBits(dataBitsCombo.getValue() != null ? dataBitsCombo.getValue() : SerialSettings.MAX_DATA_BITS);
        settings.setParity(parityCombo.getValue());
        settings.setStopBits(stopBitsCombo.getValue());
        settings.setFlowControl(flowControlCombo.getValue());
        settings.setLineEnding(lineEndingCombo.getValue());
        settings.setAssertDtr(assertDtrCheck.isSelected());
        settings.setAssertRts(assertRtsCheck.isSelected());
        settings.setLocalEcho(localEchoCheck.isSelected());
        return settings;
    }

    /** The i18n key of what is wrong with the rows, or {@code null} when they can be saved. */
    String validationErrorKey() {
        if (portText() == null) {
            return "connEdit.serial.validation.portRequired";
        }
        if (parseBaudRate(baudCombo.getEditor().getText()) <= 0) {
            return "connEdit.serial.validation.baudRate";
        }
        return null;
    }

    /** A positive rate up to {@link SerialSettings#MAX_BAUD_RATE}, or 0 for anything else. */
    static int parseBaudRate(String text) {
        if (text == null) {
            return 0;
        }
        try {
            int rate = Integer.parseInt(text.trim());
            return rate > 0 && rate <= SerialSettings.MAX_BAUD_RATE ? rate : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String portText() {
        String text = portCombo.getEditor().getText();
        if (text == null || text.isBlank()) {
            text = portCombo.getValue();
        }
        return text != null && !text.isBlank() ? text.trim() : null;
    }

    /** A flow-control mode owns its handshake line, so the matching "raise on connect" option is moot. */
    private void updateHandshakeChecks() {
        boolean active = !portCombo.isDisabled();
        SerialSettings.FlowControl flow = flowControlCombo.getValue();
        assertDtrCheck.setDisable(!active || flow == SerialSettings.FlowControl.DSR_DTR);
        assertRtsCheck.setDisable(!active || flow == SerialSettings.FlowControl.RTS_CTS);
    }

    /** Scans the ports off the JavaFX thread and offers them in the picker; the typed name stays. */
    private void refreshPorts() {
        refreshButton.setDisable(true);
        Thread scan = new Thread(() -> {
            List<SerialPortCatalog.PortInfo> ports = SerialPortCatalog.list();
            Platform.runLater(() -> showPorts(ports));
        }, "kortty-serial-port-scan");
        scan.setDaemon(true);
        scan.start();
    }

    private void showPorts(List<SerialPortCatalog.PortInfo> ports) {
        String typed = portText();
        portLabels.clear();
        List<String> names = new ArrayList<>();
        for (SerialPortCatalog.PortInfo port : ports) {
            portLabels.put(port.name(), port.label());
            names.add(port.name());
        }
        portCombo.getItems().setAll(names);
        portCombo.setValue(typed);
        portCombo.getEditor().setText(typed != null ? typed : "");
        portCombo.setPlaceholder(new Label(I18n.get("connEdit.serial.noPortsFound")));
        if (typed == null && names.size() == 1) {
            portCombo.setValue(names.get(0));
        }
        refreshButton.setDisable(portCombo.isDisabled());
    }

    private static <T> void configure(ComboBox<T> combo, T[] values, T selected, Function<T, String> label) {
        combo.getItems().setAll(values);
        combo.setValue(selected);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        });
    }

    /** {@code ONE_POINT_FIVE} → {@code onePointFive}, {@code RTS_CTS} → {@code rtsCts}. */
    static String camel(String enumName) {
        String[] parts = enumName.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }
}
