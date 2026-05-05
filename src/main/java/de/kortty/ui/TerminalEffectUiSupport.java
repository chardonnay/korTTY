package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TerminalEffectUiSupport {

    static final Option NONE = new Option(null, "None");

    private TerminalEffectUiSupport() {
    }

    static boolean isTerminalEffectsEnabled() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            return app == null
                    || app.getGlobalSettingsManager() == null
                    || app.getGlobalSettingsManager().getSettings() == null
                    || app.getGlobalSettingsManager().getSettings().isTerminalEffectsEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    static void configureComboBox(ComboBox<Option> comboBox) {
        comboBox.getItems().setAll(loadOptions());
        comboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(Option item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });
        comboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Option item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? NONE.displayName() : item.displayName());
            }
        });
        comboBox.setValue(NONE);
    }

    static void selectPlugin(ComboBox<Option> comboBox, String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            comboBox.setValue(NONE);
            return;
        }
        String normalizedPluginId = pluginId.trim();
        for (Option option : comboBox.getItems()) {
            if (normalizedPluginId.equals(option.pluginId())) {
                comboBox.setValue(option);
                return;
            }
        }
        Option savedOption = new Option(normalizedPluginId, normalizedPluginId);
        comboBox.getItems().add(savedOption);
        comboBox.setValue(savedOption);
    }

    static String selectedPluginId(ComboBox<Option> comboBox) {
        if (!isTerminalEffectsEnabled()) {
            return null;
        }
        Option option = comboBox != null ? comboBox.getValue() : null;
        return option != null && !option.isNone() ? option.pluginId() : null;
    }

    static String formatAnimationSpeed(double speed) {
        return String.format(
                Locale.ROOT,
                "Animation Speed: %.2fx",
                TerminalEffectAnimationSpeed.normalize(speed));
    }

    static AnimationSpeedControls createAnimationSpeedControls(double speed) {
        return new AnimationSpeedControls(speed);
    }

    static Double animationSpeedForStorage(String pluginId, double speed) {
        if (pluginId == null || pluginId.isBlank()) {
            return null;
        }
        double normalizedSpeed = TerminalEffectAnimationSpeed.normalize(speed);
        if (Double.compare(normalizedSpeed, TerminalEffectAnimationSpeed.DEFAULT) == 0) {
            return null;
        }
        return normalizedSpeed;
    }

    static double sliderValueForAnimationSpeed(double speed) {
        double normalizedSpeed = TerminalEffectAnimationSpeed.normalize(speed);
        return Math.min(TerminalEffectAnimationSpeed.SLIDER_MAXIMUM, normalizedSpeed);
    }

    static String formatAnimationSpeedInput(double speed) {
        double normalizedSpeed = TerminalEffectAnimationSpeed.normalize(speed);
        return Integer.toString((int) Math.round(normalizedSpeed));
    }

    static Double parseAnimationSpeedInput(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int speed = Integer.parseInt(text.trim());
            if (speed < TerminalEffectAnimationSpeed.MINIMUM) {
                return null;
            }
            return TerminalEffectAnimationSpeed.normalize(speed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static TextFormatter<String> createAnimationSpeedTextFormatter() {
        return new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d{0,2}") ? change : null);
    }

    private static List<Option> loadOptions() {
        List<Option> options = new ArrayList<>();
        options.add(NONE);
        if (!isTerminalEffectsEnabled()) {
            return options;
        }
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getTerminalEffectPluginManager() == null) {
            return options;
        }
        for (TerminalEffectPlugin plugin : app.getTerminalEffectPluginManager().getPlugins()) {
            options.add(new Option(plugin.id(), plugin.displayName()));
        }
        return options;
    }

    record Option(String pluginId, String displayName) {

        boolean isNone() {
            return pluginId == null || pluginId.isBlank();
        }
    }

    static final class AnimationSpeedControls {
        private final Label label;
        private final Slider slider;
        private final TextField input;
        private final VBox root;
        private final DoubleProperty speed = new SimpleDoubleProperty(TerminalEffectAnimationSpeed.DEFAULT);
        private boolean updating;

        private AnimationSpeedControls(double initialSpeed) {
            label = new Label();
            slider = new Slider(
                    TerminalEffectAnimationSpeed.MINIMUM,
                    TerminalEffectAnimationSpeed.SLIDER_MAXIMUM,
                    sliderValueForAnimationSpeed(initialSpeed));
            slider.setPrefWidth(180);
            slider.setBlockIncrement(1.0);
            slider.setShowTickMarks(true);
            slider.setShowTickLabels(true);
            slider.setMajorTickUnit(1.0);
            slider.setMinorTickCount(0);
            slider.setSnapToTicks(true);

            input = new TextField();
            input.setPrefColumnCount(2);
            input.setMaxWidth(52);
            input.setTextFormatter(createAnimationSpeedTextFormatter());

            HBox controls = new HBox(8, slider, input);
            root = new VBox(4, label, controls);
            setValue(initialSpeed);

            slider.valueProperty().addListener((obs, oldValue, newValue) -> {
                if (!updating) {
                    setValue(newValue.doubleValue());
                }
            });
            input.textProperty().addListener((obs, oldValue, newValue) -> {
                if (updating) {
                    return;
                }
                Double parsedSpeed = parseAnimationSpeedInput(newValue);
                if (parsedSpeed != null) {
                    setValue(parsedSpeed);
                }
            });
            input.setOnAction(event -> refreshInputText());
            input.focusedProperty().addListener((obs, wasFocused, focused) -> {
                if (!focused) {
                    refreshInputText();
                }
            });
        }

        VBox root() {
            return root;
        }

        double getValue() {
            return speed.get();
        }

        void setValue(double value) {
            double normalizedSpeed = TerminalEffectAnimationSpeed.normalize(value);
            updating = true;
            try {
                speed.set(normalizedSpeed);
                label.setText(formatAnimationSpeed(normalizedSpeed));
                slider.setValue(sliderValueForAnimationSpeed(normalizedSpeed));
                input.setText(formatAnimationSpeedInput(normalizedSpeed));
            } finally {
                updating = false;
            }
        }

        DoubleProperty valueProperty() {
            return speed;
        }

        void setDisable(boolean disabled) {
            label.setDisable(disabled);
            slider.setDisable(disabled);
            input.setDisable(disabled);
        }

        private void refreshInputText() {
            if (!updating) {
                input.setText(formatAnimationSpeedInput(speed.get()));
            }
        }
    }
}
