package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spotlight of KI-Agent design 05: a field in the middle of the pane where the user asks the agent,
 * with the profile to use and the questions asked before (↑ and ↓). Enter starts the run as
 * {@code agent …} at the shell would; the field then shrinks into the status pill in the corner when the
 * run's design shows one, or fades out. FX thread.
 */
final class AgentDesignSpotlight {

    static final double WIDTH = 460;

    private final @Nullable AgentTerminalSurface surface;
    private final Label icon = new Label("◆");
    private final TextField field = new TextField();
    private final Label enterHint = new Label();
    private final ComboBox<String> profile = new ComboBox<>();
    private final Label historyHint = new Label();
    private final VBox card;
    private final List<String> history = new ArrayList<>();
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean submitted;
    private boolean takesKeyboard;
    private int historyIndex = -1;

    AgentDesignSpotlight(@Nullable AgentTerminalSurface surface) {
        this.surface = surface;
        field.setPromptText(I18n.get("aiAgentDesign.spotlight.prompt"));
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox input = new HBox(10, icon, field, enterHint);
        input.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox options = new HBox(10, new Label("⇥"), profile, spacer, historyHint);
        options.setAlignment(Pos.CENTER_LEFT);
        card = new VBox(10, input, options);
        card.setPadding(new Insets(14, 16, 12, 16));
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        card.setEffect(AgentDesignWidgets.shadow(40, 0.5));
        card.setManaged(false);
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        profile.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                submit();
                event.consume();
            }
        });
        applyPalette(palette);
    }

    void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        card.setStyle(AgentDesignWidgets.cardStyle(palette, 0.98, 12));
        icon.setTextFill(palette.accent());
        icon.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 18));
        field.setStyle("-fx-background-color: transparent; -fx-text-fill: " + AgentDesignPalette.hex(palette.text())
            + "; -fx-prompt-text-fill: " + AgentDesignPalette.hex(palette.muted()) + "; -fx-font-size: 1.2308em;"
            + " -fx-highlight-fill: " + AgentDesignPalette.hex(palette.accent()) + "; -fx-padding: 4 0 4 0;");
        enterHint.setText("↵ " + I18n.get("aiAgentDesign.spotlight.start"));
        enterHint.setTextFill(palette.muted());
        historyHint.setText("↑ " + I18n.get("aiAgentDesign.spotlight.history"));
        historyHint.setTextFill(palette.muted());
        for (javafx.scene.Node node : ((HBox) card.getChildren().get(1)).getChildren()) {
            if (node instanceof Label label && label != historyHint) {
                label.setTextFill(palette.muted());
            }
        }
    }

    void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
    }

    /** Opens the field in the middle of the pane and puts the keyboard into it. */
    void open() {
        open(true);
    }

    private void open(boolean takeKeyboard) {
        takesKeyboard = takeKeyboard;
        Pane layer = surface != null ? surface.controlLayer() : null;
        if (layer == null) {
            return;
        }
        submitted = false;
        field.clear();
        loadProfilesAndHistory();
        card.setOpacity(1.0);
        card.setScaleX(1.0);
        card.setScaleY(1.0);
        card.setTranslateX(0);
        card.setTranslateY(0);
        AgentDesignWidgets.anchor(card, layer, Pos.CENTER, new Insets(16), surface::canvas);
        if (!reducedAnimations) {
            card.setOpacity(0.0);
            card.setScaleX(0.96);
            card.setScaleY(0.96);
            FadeTransition fade = new FadeTransition(Duration.millis(140), card);
            fade.setToValue(1.0);
            ScaleTransition scale = new ScaleTransition(Duration.millis(160), card);
            scale.setToX(1.0);
            scale.setToY(1.0);
            new ParallelTransition(fade, scale).play();
        }
        if (takeKeyboard) {
            Platform.runLater(field::requestFocus);
        }
    }

    /** Opens the field with {@code prompt} as if the user had just asked it (the design preview). */
    void openAsked(String prompt) {
        open(false);
        field.setText(prompt);
        field.end();
        submitted = true;
    }

    /** Whether {@code node} is the Spotlight's card. */
    boolean isCard(javafx.scene.Node node) {
        return node == card;
    }

    boolean isOpen() {
        return card.getParent() != null;
    }

    /** Closes the field without starting a run, giving the keyboard back to the terminal. */
    void close() {
        AgentDesignWidgets.release(card);
        AgentDesignWidgets.detach(card);
        if (!takesKeyboard) {
            return;
        }
        takesKeyboard = false;
        Platform.runLater(() -> {
            if (surface != null && surface.canvas() != null) {
                surface.canvas().requestFocus();
            }
        });
    }

    /**
     * A run started in the pane: when it came from the field, the field shrinks into the status pill in
     * the top-right corner ({@code intoPill}) or fades out.
     */
    void runStarted(String prompt, boolean intoPill) {
        if (!submitted || !isOpen()) {
            return;
        }
        submitted = false;
        if (reducedAnimations) {
            close();
            return;
        }
        Pane layer = (Pane) card.getParent();
        AgentDesignWidgets.release(card);
        FadeTransition fade = new FadeTransition(Duration.millis(intoPill ? 420 : 220), card);
        fade.setToValue(0.0);
        ParallelTransition morph;
        if (intoPill) {
            ScaleTransition scale = new ScaleTransition(Duration.millis(420), card);
            scale.setToX(0.3);
            scale.setToY(0.3);
            scale.setInterpolator(Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0));
            TranslateTransition move = new TranslateTransition(Duration.millis(420), card);
            move.setToX(layer.getWidth() / 2.0 - card.getLayoutX() - card.getWidth() * 0.15 - 12);
            move.setToY(-card.getLayoutY() - card.getHeight() * 0.35 + 8);
            move.setInterpolator(Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0));
            morph = new ParallelTransition(scale, move, fade);
        } else {
            morph = new ParallelTransition(fade);
        }
        morph.setOnFinished(event -> close());
        morph.play();
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER -> {
                submit();
                event.consume();
            }
            case ESCAPE -> {
                close();
                event.consume();
            }
            case UP -> {
                browseHistory(1);
                event.consume();
            }
            case DOWN -> {
                browseHistory(-1);
                event.consume();
            }
            case TAB -> {
                profile.requestFocus();
                event.consume();
            }
            default -> {
            }
        }
    }

    private void submit() {
        String prompt = field.getText() != null ? field.getText().strip() : "";
        if (prompt.isEmpty() || surface == null) {
            return;
        }
        String chosen = profile.getSelectionModel().getSelectedIndex() > 0 ? profile.getValue() : null;
        submitted = true;
        if (!surface.startRun(prompt, chosen)) {
            submitted = false;
            field.setStyle(field.getStyle() + " -fx-border-color: " + AgentDesignPalette.hex(palette.error()) + ";");
        }
    }

    private void browseHistory(int step) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.max(-1, Math.min(history.size() - 1, historyIndex + step));
        field.setText(historyIndex < 0 ? "" : history.get(historyIndex));
        field.end();
    }

    /** The profiles to choose from (the pane's default first) and the questions asked before, newest first. */
    private void loadProfilesAndHistory() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        List<String> names = new ArrayList<>();
        names.add(I18n.get("aiAgentDesign.spotlight.defaultProfile"));
        if (settings != null && settings.getAiProfiles() != null) {
            for (AiProfile aiProfile : settings.getAiProfiles()) {
                if (aiProfile != null && aiProfile.getName() != null && !aiProfile.getName().isBlank()) {
                    names.add(aiProfile.getName().strip());
                }
            }
        }
        profile.getItems().setAll(names);
        profile.getSelectionModel().selectFirst();
        history.clear();
        if (settings != null) {
            history.addAll(settings.getTerminalAgentInputHistory());
        }
        historyIndex = -1;
        historyHint.setVisible(!history.isEmpty());
    }
}
