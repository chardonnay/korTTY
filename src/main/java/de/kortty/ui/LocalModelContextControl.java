package de.kortty.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * The context size of an embedded llama.cpp profile's model, as a number field and a slider kept
 * in step, from {@value #MIN_CONTEXT} tokens up to the context the model was trained for. The
 * value belongs to the model, not the profile: llama.cpp loads a model with one context size, so
 * every profile that uses the model shares it.
 */
final class LocalModelContextControl {

    static final int MIN_CONTEXT = 512;
    static final int STEP = 512;

    private final SpinnerValueFactory.IntegerSpinnerValueFactory factory;
    private final Spinner<Integer> spinner;
    private final Slider slider;
    private final Label maxLabel;
    private final HBox node;
    private boolean syncing;

    LocalModelContextControl() {
        factory = new SpinnerValueFactory.IntegerSpinnerValueFactory(MIN_CONTEXT, MIN_CONTEXT, MIN_CONTEXT, STEP);
        spinner = new Spinner<>(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(120);
        // A typed number counts once the field loses the focus, not only on Enter.
        spinner.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused) {
                commitEditorText();
            }
        });
        slider = new Slider(MIN_CONTEXT, MIN_CONTEXT, MIN_CONTEXT);
        slider.setMinWidth(160);
        HBox.setHgrow(slider, Priority.ALWAYS);
        maxLabel = new Label();
        maxLabel.setStyle(MutedTextStyle.HINT);
        Tooltip tooltip = new Tooltip(I18n.get("settings.ai.contextSize.tooltip"));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(480);
        spinner.setTooltip(tooltip);
        slider.setTooltip(tooltip);

        factory.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!syncing && newValue != null) {
                syncing = true;
                slider.setValue(newValue);
                syncing = false;
            }
        });
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!syncing) {
                syncing = true;
                factory.setValue(snap(newValue.doubleValue(), (int) slider.getMax()));
                syncing = false;
            }
        });

        node = new HBox(8, spinner, slider, maxLabel);
        node.setAlignment(Pos.CENTER_LEFT);
    }

    Node node() {
        return node;
    }

    /** Sets the range to the model's maximum and puts that maximum into the field. */
    void showMaximum(int maximum) {
        int max = Math.max(MIN_CONTEXT, maximum);
        syncing = true;
        factory.setMax(max);
        slider.setMax(max);
        slider.setMajorTickUnit(Math.max(STEP, max / 4.0));
        syncing = false;
        factory.setValue(max);
        slider.setValue(max);
        maxLabel.setText(I18n.get("settings.ai.contextSize.max", max));
    }

    int value() {
        commitEditorText();
        Integer value = factory.getValue();
        return value != null ? value : MIN_CONTEXT;
    }

    private void commitEditorText() {
        String text = spinner.getEditor().getText();
        try {
            int typed = Integer.parseInt(text.replaceAll("[^0-9]", ""));
            factory.setValue(clamp(typed, factory.getMax()));
        } catch (NumberFormatException e) {
            // Not a number: show the value the field still holds.
        }
        spinner.getEditor().setText(String.valueOf(factory.getValue()));
    }

    /** {@code value} within {@value #MIN_CONTEXT} and {@code maximum}. */
    static int clamp(int value, int maximum) {
        return Math.max(MIN_CONTEXT, Math.min(value, Math.max(MIN_CONTEXT, maximum)));
    }

    /**
     * A slider position as a context size: a multiple of {@value #STEP}, or the maximum itself near
     * the right end, so a model trained for an odd context can still be given all of it.
     */
    static int snap(double sliderValue, int maximum) {
        int max = Math.max(MIN_CONTEXT, maximum);
        if (sliderValue >= max - STEP / 2.0) {
            return max;
        }
        long stepped = Math.round(sliderValue / STEP) * (long) STEP;
        return clamp((int) Math.min(Integer.MAX_VALUE, stepped), max);
    }
}
