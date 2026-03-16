package de.kortty.ui;

import de.kortty.core.AiTokenWarningLevel;
import javafx.geometry.Insets;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Visual quota bar with threshold background and colored usage fill.
 */
public class AiQuotaBar extends StackPane {

    private final Region thresholdBackground = new Region();
    private final Region usageFill = new Region();

    private double progress;

    public AiQuotaBar() {
        setPrefHeight(14);
        setMinHeight(14);
        setMaxHeight(14);
        setPadding(Insets.EMPTY);

        thresholdBackground.setMinHeight(14);
        thresholdBackground.setMaxHeight(14);
        thresholdBackground.setPrefHeight(14);
        thresholdBackground.setStyle(
            "-fx-background-color: linear-gradient(to right, "
                + "#2f855a 0%, #2f855a 75%, "
                + "#b7791f 75%, #b7791f 90%, "
                + "#c53030 90%, #c53030 100%);"
                + "-fx-background-radius: 7;");

        usageFill.setMinHeight(14);
        usageFill.setMaxHeight(14);
        usageFill.setPrefHeight(14);
        usageFill.setManaged(false);
        usageFill.setStyle("-fx-background-color: rgba(255,255,255,0.55); -fx-background-radius: 7;");

        getChildren().addAll(thresholdBackground, usageFill);

        widthProperty().addListener((obs, oldValue, newValue) -> updateFillWidth());
        heightProperty().addListener((obs, oldValue, newValue) -> updateFillWidth());
    }

    public void update(double usedFraction, int yellowPercent, int redPercent, AiTokenWarningLevel warningLevel, boolean unlimited) {
        double safeYellow = clampPercent(yellowPercent);
        double safeRed = Math.max(safeYellow, clampPercent(redPercent));
        thresholdBackground.setStyle(
            "-fx-background-color: linear-gradient(to right, "
                + "#2f855a 0%, #2f855a " + safeYellow + "%, "
                + "#b7791f " + safeYellow + "%, #b7791f " + safeRed + "%, "
                + "#c53030 " + safeRed + "%, #c53030 100%);"
                + "-fx-background-radius: 7;");

        progress = unlimited ? 0.0 : clamp01(usedFraction);
        usageFill.setVisible(!unlimited);
        usageFill.setStyle(switch (warningLevel != null ? warningLevel : AiTokenWarningLevel.NONE) {
            case YELLOW -> "-fx-background-color: rgba(255,245,180,0.82); -fx-background-radius: 7;";
            case RED -> "-fx-background-color: rgba(255,220,220,0.86); -fx-background-radius: 7;";
            case NONE -> "-fx-background-color: rgba(255,255,255,0.55); -fx-background-radius: 7;";
        });
        setOpacity(unlimited ? 0.45 : 1.0);
        updateFillWidth();
    }

    private void updateFillWidth() {
        double width = Math.max(0.0, getWidth());
        usageFill.resizeRelocate(0, 0, width * progress, Math.max(14, getHeight()));
    }

    private static double clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
