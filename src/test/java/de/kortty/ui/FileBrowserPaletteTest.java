package de.kortty.ui;

import javafx.scene.paint.Color;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class FileBrowserPaletteTest {

    @Test
    void setsEveryTokenTheStylesheetReads() {
        Map<String, String> tokens = tokens(FileBrowserPalette.tokenStyle("#282828", "#ebdbb2", "#a89984", "#fe8019"));

        assertThat(tokens.keySet()).containsExactly("-kortty-fb-bg", "-kortty-fb-fg", "-kortty-fb-dim",
            "-kortty-fb-accent", "-kortty-fb-border", "-kortty-fb-hover", "-kortty-fb-selected",
            "-kortty-fb-selected-fg", "-kortty-fb-status-bg", "-fx-background-color");
        assertThat(tokens.get("-kortty-fb-bg")).isEqualTo("rgba(40,40,40,1.000)");
        assertThat(tokens.get("-kortty-fb-fg")).isEqualTo("rgba(235,219,178,1.000)");
        assertThat(tokens.get("-kortty-fb-accent")).isEqualTo("rgba(254,128,25,1.000)");
        assertThat(tokens.get("-fx-background-color")).isEqualTo("-kortty-fb-bg");
    }

    @Test
    void hoverAndSelectionStandOutFromTheRowsAndHoverStaysSubtler() {
        Map<String, String> tokens = tokens(FileBrowserPalette.tokenStyle("#282828", "#ebdbb2", "#a89984", "#fe8019"));
        Color bg = Color.web(tokens.get("-kortty-fb-bg"));
        Color hover = Color.web(tokens.get("-kortty-fb-hover"));
        Color selected = Color.web(tokens.get("-kortty-fb-selected"));

        assertThat(distance(bg, hover)).isGreaterThan(0.02);
        assertThat(distance(bg, selected)).isGreaterThan(distance(bg, hover));
    }

    @Test
    void readsTranslucentDesignColors() {
        // Tactical Ops: its text and dim colors are rgba(...) values.
        Map<String, String> tokens = tokens(FileBrowserPalette.tokenStyle(
            "#0d0906", "rgba(255,180,180,0.85)", "rgba(204,68,85,0.6)", "#ff3c5a"));

        assertThat(tokens.get("-kortty-fb-fg")).isEqualTo("rgba(255,180,180,0.850)");
        assertThat(tokens.get("-kortty-fb-dim")).isEqualTo("rgba(204,68,85,0.600)");
    }

    private static Map<String, String> tokens(String style) {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String declaration : style.split(";")) {
            if (declaration.isBlank()) {
                continue;
            }
            String[] parts = declaration.split(":", 2);
            tokens.put(parts[0].strip(), parts[1].strip());
        }
        return tokens;
    }

    private static double distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
            + Math.abs(a.getBlue() - b.getBlue());
    }
}
