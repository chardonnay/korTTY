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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The serial-port rows of the connection editor and Quick Connect: port (picked from the ports
 * present or typed), speed, data format, flow control, what Enter sends, and the DTR/RTS/echo
 * options. Both dialogs add the rows to their connection grid and enable them for
 * {@link de.kortty.model.ConnectionProtocol#SERIAL} only.
 *
 * <p>The port list shows each port's driver description, its USB manufacturer, serial number and
 * vendor:product id, so two identical adapters can be told apart. While the rows are active the
 * list is rescanned every {@link #RESCAN_SECONDS} seconds, and a port that appeared after the first
 * scan is marked <i>New</i> (and picked when no port is entered yet): plug the adapter in with the
 * dialog open and its port is the marked one.</p>
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
    /** Port name to what the last scan knew about it; typed names have no entry. */
    private final Map<String, SerialPortCatalog.PortInfo> ports = new LinkedHashMap<>();
    /** The ports of the first scan; null until it finished. A port missing from it is new. */
    private Set<String> baselinePorts;
    private final Set<String> newPorts = new HashSet<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final javafx.animation.Timeline rescan = new javafx.animation.Timeline(
        new javafx.animation.KeyFrame(javafx.util.Duration.seconds(RESCAN_SECONDS), e -> rescanIfShowing()));
    private Runnable onChange = () -> { };

    /** How often the port list is rescanned while the serial rows are active. */
    static final int RESCAN_SECONDS = 2;

    SerialSettingsEditor(SerialSettings initial) {
        SerialSettings settings = initial != null ? initial : new SerialSettings();

        portCombo.setEditable(true);
        portCombo.setPromptText(I18n.get("connEdit.serial.portPrompt"));
        portCombo.setMaxWidth(Double.MAX_VALUE);
        portCombo.setCellFactory(list -> new PortCell());
        portCombo.setOnShown(e -> fitPopupToCells());
        rescan.setCycleCount(javafx.animation.Animation.INDEFINITE);
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
        if (active) {
            if (baselinePorts == null) {
                refreshPorts();
            }
            rescan.play();
        } else {
            rescan.stop();
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
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        refreshButton.setDisable(true);
        Thread scan = new Thread(() -> {
            List<SerialPortCatalog.PortInfo> found;
            try {
                found = SerialPortCatalog.list();
            } finally {
                scanning.set(false);
            }
            List<SerialPortCatalog.PortInfo> result = found;
            Platform.runLater(() -> showPorts(result));
        }, "kortty-serial-port-scan");
        scan.setDaemon(true);
        scan.start();
    }

    /** The periodic rescan; stops by itself once the dialog is closed. */
    private void rescanIfShowing() {
        javafx.stage.Window window = portCombo.getScene() != null ? portCombo.getScene().getWindow() : null;
        if (window == null || !window.isShowing() || portCombo.isDisabled()) {
            rescan.stop();
            return;
        }
        // An open list would jump under the pointer; the next tick catches up.
        if (!portCombo.isShowing()) {
            refreshPorts();
        }
    }

    private void showPorts(List<SerialPortCatalog.PortInfo> found) {
        refreshButton.setDisable(portCombo.isDisabled());
        List<String> names = new ArrayList<>();
        for (SerialPortCatalog.PortInfo port : found) {
            names.add(port.name());
        }
        boolean firstScan = baselinePorts == null;
        if (firstScan) {
            baselinePorts = new LinkedHashSet<>(names);
        }
        Set<String> fresh = newPorts(baselinePorts, names);
        if (!firstScan && new ArrayList<>(ports.values()).equals(found) && fresh.equals(newPorts)) {
            return;
        }
        Set<String> appeared = new LinkedHashSet<>(fresh);
        appeared.removeAll(newPorts);
        newPorts.clear();
        newPorts.addAll(fresh);
        ports.clear();
        for (SerialPortCatalog.PortInfo port : found) {
            ports.put(port.name(), port);
        }

        String typed = portText();
        portCombo.getItems().setAll(names);
        portCombo.setValue(typed);
        portCombo.getEditor().setText(typed != null ? typed : "");
        portCombo.setPlaceholder(new Label(I18n.get("connEdit.serial.noPortsFound")));
        if (typed == null && firstScan && names.size() == 1) {
            portCombo.setValue(names.get(0));
        } else if (typed == null && appeared.size() == 1) {
            // The adapter just plugged in is almost certainly the one wanted.
            portCombo.setValue(appeared.iterator().next());
        }
    }

    /**
     * Gives every row of the open list the height of the tallest one. The popup estimates its height
     * before the two-line rows are laid out, which cut the last row off in the AtlantaFX designs.
     */
    private void fitPopupToCells() {
        if (!(portCombo.getSkin() instanceof javafx.scene.control.skin.ComboBoxListViewSkin<?> skin)
                || !(skin.getPopupContent() instanceof javafx.scene.control.ListView<?> list)) {
            return;
        }
        Platform.runLater(() -> {
            double tallest = 0;
            for (javafx.scene.Node cell : list.lookupAll(".list-cell")) {
                if (cell instanceof PortCell portCell && portCell.getItem() != null) {
                    tallest = Math.max(tallest, portCell.prefHeight(list.getWidth()));
                }
            }
            if (tallest > 0 && Math.abs(list.getFixedCellSize() - tallest) > 0.5) {
                list.setFixedCellSize(tallest);
                int rows = Math.min(portCombo.getItems().size(), portCombo.getVisibleRowCount());
                javafx.geometry.Insets insets = list.getInsets();
                list.setPrefHeight(rows * tallest + insets.getTop() + insets.getBottom());
                // The open popup keeps the size it was shown with; reopening sizes it to the rows. The
                // fixed size now matches, so the reopened list does not come back here.
                portCombo.hide();
                portCombo.show();
            }
        });
    }

    /** The ports in {@code current} that the first scan did not have, in list order. */
    static Set<String> newPorts(Set<String> baseline, List<String> current) {
        Set<String> fresh = new LinkedHashSet<>();
        for (String name : current) {
            if (baseline != null && !baseline.contains(name)) {
                fresh.add(name);
            }
        }
        return fresh;
    }

    /**
     * The second line of a port in the list: manufacturer (unless the description already names it),
     * serial number and USB vendor:product id, separated by middle dots; empty when none is known.
     */
    static String detailLine(SerialPortCatalog.PortInfo port) {
        List<String> parts = new ArrayList<>();
        String manufacturer = port.manufacturerBeyondDescription();
        if (manufacturer != null) {
            parts.add(manufacturer);
        }
        if (port.serialNumber() != null) {
            parts.add(I18n.get("connEdit.serial.portSerialNumber", port.serialNumber()));
        }
        if (port.usbId() != null) {
            parts.add(I18n.get("connEdit.serial.portUsbId", port.usbId()));
        }
        return String.join(" · ", parts);
    }

    /** A port in the list: its label, a New badge for a port plugged in after the first scan, details below. */
    private final class PortCell extends ListCell<String> {
        private final Label title = new Label();
        private final Label badge = new Label(I18n.get("connEdit.serial.portNew"));
        private final Label detail = new Label();
        private final HBox titleRow = new HBox(6, title, badge);
        private final javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(1, titleRow, detail);

        PortCell() {
            titleRow.setAlignment(Pos.CENTER_LEFT);
            // Literal colours: the badge must read the same in the Normal design and every AtlantaFX theme.
            badge.setStyle("-fx-background-color: #2da44e; -fx-text-fill: white; -fx-background-radius: 3;"
                + " -fx-padding: 0 5 0 5; -fx-font-size: 0.85em; -fx-font-weight: bold;");
            detail.setStyle("-fx-font-size: 0.9em;");
            detail.setOpacity(0.75);
            // Follow the cell's own text colour, so a selected row stays readable.
            title.textFillProperty().bind(textFillProperty());
            detail.textFillProperty().bind(textFillProperty());
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            SerialPortCatalog.PortInfo port = ports.get(item);
            setText(item);
            title.setText(port != null ? port.label() : item);
            boolean fresh = newPorts.contains(item);
            badge.setVisible(fresh);
            badge.setManaged(fresh);
            // Every row keeps its second line, empty or not: the list sizes its popup from the first
            // rows, and rows of mixed height cut the last one off.
            String details = port != null ? detailLine(port) : "";
            detail.setText(details.isEmpty() ? " " : details);
            setGraphic(box);
            setTooltip(port != null && port.location() != null
                ? new Tooltip(I18n.get("connEdit.serial.portLocation", port.location())) : null);
        }
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
