package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog that shows AI-generated alternative implementations for a selected code region.
 */
public class AlternativeSnippetSolutionsDialog extends ThemeAwareDialog<SnippetAiResponseSupport.AlternativeSolution> {

    private static final String AI_ACTION_PREFIX = "\u2728 ";
    private static final int DEFAULT_PREVIEW_FONT_SIZE = 14;
    private static final int MIN_PREVIEW_FONT_SIZE = 8;
    private static final int MAX_PREVIEW_FONT_SIZE = 32;

    @FunctionalInterface
    public interface AlternativeSolutionLoader {
        List<SnippetAiResponseSupport.AlternativeSolution> load(String additionalInstructions, String aiProfileId) throws Exception;
    }

    private final String snippetLanguage;
    private final AlternativeSolutionLoader loader;
    private final TextArea instructionsArea;
    private final Button reloadButton;
    private final ProgressIndicator progressIndicator;
    private final Label statusLabel;
    private final Label fontSizeLabel = new Label();
    private final ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo;
    private final VBox solutionsBox;
    private final ScrollPane solutionsScrollPane;
    private final VBox root;
    private final List<SolutionCard> solutionCards = new ArrayList<>();
    private Task<List<SnippetAiResponseSupport.AlternativeSolution>> loadTask;
    private SolutionCard zoomedCard;
    private int previewFontSize;

    private record SolutionCard(
        VBox container,
        Label summaryLabel,
        MonacoEditorPane previewScrollPane,
        Button zoomButton) {
    }

    public AlternativeSnippetSolutionsDialog(
        Window owner,
        String snippetLanguage,
        AlternativeSolutionLoader loader) {

        this(owner, snippetLanguage, loader, false, null);
    }

    public AlternativeSnippetSolutionsDialog(
        Window owner,
        String snippetLanguage,
        AlternativeSolutionLoader loader,
        boolean profileSwitchingSupported,
        String activeProfileId) {

        this.snippetLanguage = snippetLanguage;
        this.loader = loader;
        this.previewFontSize = clampFontSize(loadPersistedFontSize());

        setTitle(I18n.get("snippets.ai.alternatives.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        instructionsArea = new TextArea();
        instructionsArea.setPromptText(I18n.get("snippets.ai.alternatives.instructions.prompt"));
        instructionsArea.setWrapText(true);
        instructionsArea.setPrefRowCount(3);
        instructionsArea.setMinHeight(Region.USE_PREF_SIZE);

        reloadButton = new Button(AI_ACTION_PREFIX + "\u21bb");
        reloadButton.setTooltip(new Tooltip(I18n.get("snippets.ai.alternatives.reload")));
        reloadButton.setOnAction(event -> loadSolutions());

        progressIndicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        progressIndicator.setPrefSize(16, 16);
        progressIndicator.setMinSize(16, 16);
        progressIndicator.setMaxSize(16, 16);
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        HBox topBar = new HBox(10, instructionsArea, reloadButton, progressIndicator);
        topBar.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(instructionsArea, Priority.ALWAYS);

        profileCombo = profileSwitchingSupported
            ? SnippetAiDialogSupport.buildProfileCombo(activeProfileId)
            : null;

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changePreviewFontSize(-1));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changePreviewFontSize(1));
        updateFontSizeLabel();

        Region controlsSpacer = new Region();
        HBox controlsBar = new HBox(8);
        controlsBar.setAlignment(Pos.CENTER_LEFT);
        if (profileCombo != null) {
            controlsBar.getChildren().addAll(SnippetAiDialogSupport.profileLabel(), profileCombo);
        }
        controlsBar.getChildren().addAll(controlsSpacer, zoomOutButton, fontSizeLabel, zoomInButton);
        HBox.setHgrow(controlsSpacer, Priority.ALWAYS);

        solutionsBox = new VBox(12);
        solutionsBox.setFillWidth(true);
        solutionsScrollPane = new ScrollPane(solutionsBox);
        solutionsScrollPane.setFitToWidth(true);
        solutionsScrollPane.setFitToHeight(true);
        VBox.setVgrow(solutionsScrollPane, Priority.ALWAYS);

        root = new VBox(10, topBar, controlsBar, statusLabel, solutionsScrollPane);
        root.setPadding(new Insets(14));
        VBox.setVgrow(solutionsScrollPane, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        setResultConverter(buttonType -> null);
        getDialogPane().setPrefWidth(920);
        getDialogPane().setPrefHeight(760);
        restoreGeometry();
        setOnShown(event -> loadSolutions());
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> {
            cancelLoadTask();
            saveGeometry();
        });
    }

    private void loadSolutions() {
        if (loader == null) {
            return;
        }
        cancelLoadTask();
        String profileId = SnippetAiDialogSupport.selectedProfileId(profileCombo);
        loadTask = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.AlternativeSolution> call() throws Exception {
                return loader.load(instructionsArea.getText(), profileId);
            }
        };
        loadTask.setOnRunning(event -> {
            solutionsBox.getChildren().clear();
            setBusy(true);
            statusLabel.setText(I18n.get("snippets.ai.alternatives.loading"));
        });
        loadTask.setOnSucceeded(event -> {
            setBusy(false);
            List<SnippetAiResponseSupport.AlternativeSolution> solutions = loadTask.getValue();
            solutionCards.clear();
            zoomedCard = null;
            if (solutions == null || solutions.isEmpty()) {
                statusLabel.setText(I18n.get("snippets.ai.alternatives.empty"));
                return;
            }
            List<VBox> cards = new ArrayList<>();
            for (SnippetAiResponseSupport.AlternativeSolution solution : solutions) {
                SolutionCard card = createSolutionCard(solution);
                solutionCards.add(card);
                cards.add(card.container());
            }
            solutionsBox.getChildren().setAll(cards);
            updateZoomState();
            statusLabel.setText(I18n.get("snippets.ai.alternatives.loaded", solutions.size()));
        });
        loadTask.setOnFailed(event -> {
            setBusy(false);
            statusLabel.setText(I18n.get("snippets.ai.alternatives.failed"));
        });
        Thread thread = new Thread(loadTask, "snippet-alternative-solutions");
        thread.setDaemon(true);
        thread.start();
    }

    private SolutionCard createSolutionCard(SnippetAiResponseSupport.AlternativeSolution solution) {
        Label titleLabel = new Label(solution.title());
        titleLabel.setStyle("-fx-font-weight: bold;");

        Label summaryLabel = new Label(solution.summary());
        summaryLabel.setWrapText(true);
        summaryLabel.setVisible(solution.summary() != null && !solution.summary().isBlank());
        summaryLabel.setManaged(summaryLabel.isVisible());

        MonacoEditorPane previewArea = new MonacoEditorPane();
        previewArea.setEditable(false);
        previewArea.setFocusTraversable(true);
        previewArea.setWrapText(false);
        previewArea.setPrefHeight(180);
        EditorSettingsHelper.Settings settings = EditorSettingsHelper.loadSnippetSettings();
        EditorSettingsHelper.applyStyle(previewArea, settings);
        previewArea.setFontSize(previewFontSize);
        previewArea.setLanguage(snippetLanguage);
        previewArea.replaceText(solution.code());
        installPreviewCopySupport(previewArea);
        MonacoEditorPane previewScrollPane = EditorSettingsHelper.createScrollPane(previewArea);
        previewScrollPane.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(previewScrollPane, Priority.ALWAYS);

        Button applyButton = new Button(I18n.get("snippets.ai.alternatives.apply"));
        applyButton.setOnAction(event -> {
            setResult(solution);
            close();
        });

        Button zoomButton = new Button("\u2922");
        zoomButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("snippets.ai.alternatives.zoom")));

        HBox buttonBar = new HBox(8, applyButton, zoomButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, titleLabel, summaryLabel, previewScrollPane, buttonBar);
        card.setPadding(new Insets(10));
        card.setFillWidth(true);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-border-color: rgba(128,128,128,0.35); -fx-border-radius: 6; -fx-background-radius: 6;");
        previewScrollPane.prefWidthProperty().bind(Bindings.max(520.0, solutionsScrollPane.widthProperty().subtract(48)));

        SolutionCard solutionCard = new SolutionCard(card, summaryLabel, previewScrollPane, zoomButton);
        DoubleBinding normalHeightBinding = Bindings.createDoubleBinding(
            () -> Math.max(180.0, root.getHeight() * 0.24),
            root.heightProperty());
        DoubleBinding zoomHeightBinding = Bindings.createDoubleBinding(
            () -> Math.max(320.0, root.getHeight() - 180.0),
            root.heightProperty());
        bindPreviewHeight(solutionCard, normalHeightBinding, zoomHeightBinding);
        zoomButton.setOnAction(event -> toggleZoom(solutionCard, normalHeightBinding, zoomHeightBinding));
        return solutionCard;
    }

    private void installPreviewCopySupport(MonacoEditorPane previewArea) {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem(I18n.get("snippets.copyClipboard"));
        copyItem.setOnAction(event -> copySelectedPreviewText(previewArea));
        contextMenu.getItems().add(copyItem);
        contextMenu.setOnShowing(event -> copyItem.setDisable(!hasSelectedText(previewArea)));
        previewArea.setContextMenu(contextMenu);
        previewArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copySelectedPreviewText(previewArea);
                event.consume();
            }
        });
    }

    private boolean hasSelectedText(MonacoEditorPane previewArea) {
        String selectedText = previewArea.getSelectedText();
        return selectedText != null && !selectedText.isEmpty();
    }

    private void copySelectedPreviewText(MonacoEditorPane previewArea) {
        String selectedText = previewArea.getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selectedText);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void bindPreviewHeight(
        SolutionCard card,
        DoubleBinding normalHeightBinding,
        DoubleBinding zoomHeightBinding) {

        card.previewScrollPane().prefHeightProperty().unbind();
        if (zoomedCard == card) {
            card.previewScrollPane().prefHeightProperty().bind(zoomHeightBinding);
        } else {
            card.previewScrollPane().prefHeightProperty().bind(normalHeightBinding);
        }
    }

    private void toggleZoom(
        SolutionCard card,
        DoubleBinding normalHeightBinding,
        DoubleBinding zoomHeightBinding) {

        zoomedCard = zoomedCard == card ? null : card;
        for (SolutionCard currentCard : solutionCards) {
            bindPreviewHeight(currentCard, normalHeightBinding, zoomHeightBinding);
        }
        updateZoomState();
    }

    private void updateZoomState() {
        for (SolutionCard card : solutionCards) {
            boolean visible = zoomedCard == null || zoomedCard == card;
            boolean zoomed = zoomedCard == card;
            card.container().setManaged(visible);
            card.container().setVisible(visible);
            card.summaryLabel().setManaged(visible && card.summaryLabel().isVisible());
            card.zoomButton().setText(zoomed ? "\u2921" : "\u2922");
            card.zoomButton().setTooltip(new javafx.scene.control.Tooltip(I18n.get(
                zoomed ? "snippets.ai.alternatives.zoom.restore" : "snippets.ai.alternatives.zoom")));
        }
    }

    private void restoreGeometry() {
        try {
            var settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            WindowGeometry geometry = settings != null ? settings.getAlternativeSnippetSolutionsDialogGeometry() : null;
            if (geometry != null && geometry.getWidth() > 100 && geometry.getHeight() > 100) {
                getDialogPane().setPrefWidth(geometry.getWidth());
                getDialogPane().setPrefHeight(geometry.getHeight());
                setOnShowing(event -> {
                    Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
                    if (window instanceof Stage stage) {
                        stage.setX(geometry.getX());
                        stage.setY(geometry.getY());
                        stage.setWidth(geometry.getWidth());
                        stage.setHeight(geometry.getHeight());
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private void saveGeometry() {
        try {
            Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                WindowGeometry geometry = new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var settingsManager = KorTTYApplication.getInstance().getGlobalSettingsManager();
                if (settingsManager != null && settingsManager.getSettings() != null) {
                    settingsManager.getSettings().setAlternativeSnippetSolutionsDialogGeometry(geometry);
                    settingsManager.save();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setBusy(boolean busy) {
        reloadButton.setDisable(busy);
        if (profileCombo != null) {
            profileCombo.setDisable(busy);
        }
        progressIndicator.setManaged(busy);
        progressIndicator.setVisible(busy);
    }

    private void cancelLoadTask() {
        if (loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }
        setBusy(false);
    }

    private void changePreviewFontSize(int delta) {
        int next = clampFontSize(previewFontSize + delta);
        if (next == previewFontSize) {
            return;
        }
        previewFontSize = next;
        for (SolutionCard card : solutionCards) {
            card.previewScrollPane().setFontSize(previewFontSize);
        }
        updateFontSizeLabel();
        persistFontSize();
    }

    private void updateFontSizeLabel() {
        fontSizeLabel.setText(previewFontSize + "pt");
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_PREVIEW_FONT_SIZE, Math.min(MAX_PREVIEW_FONT_SIZE, size));
    }

    private int loadPersistedFontSize() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        if (settings != null && settings.getAiAlternativesFontSize() != null) {
            return settings.getAiAlternativesFontSize();
        }
        return DEFAULT_PREVIEW_FONT_SIZE;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiAlternativesFontSize(previewFontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }
}
