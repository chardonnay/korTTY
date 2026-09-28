package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.Snippet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single, reused read-only preview tab of the snippet workspace. Browsing the library only
 * swaps this tab's content: one read-only Monaco (acquired on the first preview) gets the new text
 * and language — no editor is constructed, no AI work starts.
 *
 * <p>Editing turns the preview into a pinned editor tab: the Edit button, Enter, or simply typing.
 * Promotion happens on {@code KEY_TYPED}, i.e. after dead keys and compose sequences resolved, and
 * the typed character is handed to the new editor.
 */
final class SnippetPreviewTab extends Tab {

    private static final Logger logger = LoggerFactory.getLogger(SnippetPreviewTab.class);

    /** Pin {@code snippet}; {@code typed} is the character that triggered it, or {@code null}. */
    record PromoteRequest(Snippet snippet, int caret, String typed) {
    }

    private final Consumer<PromoteRequest> promote;
    private final Consumer<String> revealElsewhere;
    private final EditorSettingsHelper.Settings editorSettings;
    private final Label nameLabel = new Label();
    private final Label metaLabel = new Label();
    private final Label descriptionLabel = new Label();
    private final Label bannerLabel = new Label();
    private final Button bannerButton = new Button(I18n.get("snippets.workspace.openElsewhere.goTo"));
    private final HBox banner;
    private final Button editButton = new Button("✎ " + I18n.get("snippets.workspace.preview.edit"));
    private final CheckBox wordWrapCheckBox = new CheckBox(I18n.get("snippets.wordWrap"));
    private final CheckBox lineNumbersCheckBox = new CheckBox(I18n.get("snippets.lineNumbers"));
    private final StackPane editorHolder = new StackPane();
    private MonacoEditorPane previewArea;
    private Snippet shown;
    private boolean editable;
    private boolean disposed;

    /**
     * @param promote         pins the shown snippet (Edit, Enter, typing)
     * @param revealElsewhere jumps to the editor that already holds a snippet id elsewhere
     */
    SnippetPreviewTab(Consumer<PromoteRequest> promote, Consumer<String> revealElsewhere) {
        this.promote = promote;
        this.revealElsewhere = revealElsewhere;
        this.editorSettings = EditorSettingsHelper.loadSnippetSettings();
        getStyleClass().add("snippet-preview-tab");
        setStyle("-fx-font-style: italic;");
        setTooltip(new Tooltip(I18n.get("snippets.workspace.previewTab.tooltip")));
        setText(I18n.get("snippets.workspace.previewTab"));

        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.15em;");
        metaLabel.setStyle("-fx-opacity: 0.75;");
        descriptionLabel.setWrapText(true);
        descriptionLabel.setMaxHeight(80);

        bannerLabel.setWrapText(true);
        bannerLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bannerLabel, Priority.ALWAYS);
        bannerButton.setOnAction(e -> {
            if (shown != null && revealElsewhere != null) {
                revealElsewhere.accept(shown.getId());
            }
        });
        banner = new HBox(8, bannerLabel, bannerButton);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(6, 8, 6, 8));
        banner.getStyleClass().add("snippet-preview-banner");
        banner.setStyle("-fx-background-color: rgba(250, 204, 21, 0.18); -fx-background-radius: 4;");
        setBannerVisible(false);

        editButton.setOnAction(e -> requestPromotion(null));
        wordWrapCheckBox.setSelected(loadWordWrapSetting());
        wordWrapCheckBox.selectedProperty().addListener((obs, oldValue, on) -> {
            if (previewArea != null) {
                previewArea.setWrapText(on);
            }
            saveViewSetting(on, true);
        });
        lineNumbersCheckBox.setSelected(loadLineNumbersSetting());
        lineNumbersCheckBox.selectedProperty().addListener((obs, oldValue, on) -> {
            if (previewArea != null) {
                EditorSettingsHelper.applyLineNumbers(previewArea, on, editorSettings);
            }
            saveViewSetting(on, false);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, editButton, spacer, wordWrapCheckBox, lineNumbersCheckBox);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(editorHolder, Priority.ALWAYS);
        editorHolder.setMinHeight(90);
        VBox content = new VBox(6, nameLabel, metaLabel, descriptionLabel, banner, toolbar, editorHolder);
        content.setPadding(new Insets(8));
        content.setFillWidth(true);
        setContent(content);
        showNothing();
    }

    /** Shows {@code snippet} read-only; {@code openElsewhere} adds a "go to editor" banner. */
    void show(Snippet snippet, boolean openElsewhere) {
        if (disposed) {
            return;
        }
        if (snippet == null) {
            showNothing();
            return;
        }
        shown = snippet;
        ensureEditor();
        String name = snippet.getName() != null && !snippet.getName().isBlank()
            ? snippet.getName().trim()
            : I18n.get("snippets.workspace.untitled");
        setText(I18n.get("snippets.workspace.previewTab") + ": " + name);
        nameLabel.setText(name);
        metaLabel.setText(metaLine(snippet));
        String description = snippet.getDescription();
        descriptionLabel.setText(description != null ? description.trim() : "");
        descriptionLabel.setVisible(description != null && !description.isBlank());
        descriptionLabel.setManaged(descriptionLabel.isVisible());
        editable = !snippet.isPolicyManaged() && !openElsewhere;
        editButton.setDisable(snippet.isPolicyManaged());
        if (openElsewhere) {
            bannerLabel.setText(I18n.get("snippets.workspace.openElsewhere"));
            bannerButton.setVisible(true);
            bannerButton.setManaged(true);
            setBannerVisible(true);
        } else if (snippet.isPolicyManaged()) {
            bannerLabel.setText(I18n.get("snippets.workspace.preview.policyManaged"));
            bannerButton.setVisible(false);
            bannerButton.setManaged(false);
            setBannerVisible(true);
        } else {
            setBannerVisible(false);
        }
        previewArea.replaceText(snippet.getContent() != null ? snippet.getContent() : "");
        previewArea.setLanguage(snippet.getLanguage());
    }

    /** Re-renders when {@code snippet} (mutated in place by a save elsewhere) is the shown one. */
    void reloadIfShowing(Snippet snippet, boolean openElsewhere) {
        if (snippet != null && shown != null && snippet.getId() != null && snippet.getId().equals(shown.getId())) {
            show(snippet, openElsewhere);
        }
    }

    /** Clears the preview when it shows {@code snippetId} (e.g. the snippet was deleted). */
    void clearIfShowing(String snippetId) {
        if (shown != null && snippetId != null && snippetId.equals(shown.getId())) {
            showNothing();
        }
    }

    Snippet shownSnippet() {
        return shown;
    }

    /** The read-only preview editor, or {@code null} before the first preview (tests). */
    MonacoEditorPane editorPane() {
        return previewArea;
    }

    /** Whether the preview holds a Monaco page (tests: browsing must not create more). */
    boolean hasEditor() {
        return previewArea != null;
    }

    void dispose() {
        disposed = true;
        if (previewArea != null) {
            previewArea.dispose();
        }
    }

    private void showNothing() {
        shown = null;
        editable = false;
        setText(I18n.get("snippets.workspace.previewTab"));
        nameLabel.setText(I18n.get("snippets.workspace.empty"));
        metaLabel.setText("");
        descriptionLabel.setText("");
        descriptionLabel.setVisible(false);
        descriptionLabel.setManaged(false);
        editButton.setDisable(true);
        setBannerVisible(false);
        if (previewArea != null) {
            previewArea.clear();
        }
    }

    private void setBannerVisible(boolean visible) {
        banner.setVisible(visible);
        banner.setManaged(visible);
    }

    private void ensureEditor() {
        if (previewArea != null) {
            return;
        }
        previewArea = MonacoEditorWarmup.acquire();
        previewArea.setEditable(false);
        EditorSettingsHelper.applyStyle(previewArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(previewArea, editorSettings);
        previewArea.setWrapText(wordWrapCheckBox.isSelected());
        EditorSettingsHelper.applyLineNumbers(previewArea, lineNumbersCheckBox.isSelected(), editorSettings);
        // Typing promotes: KEY_TYPED carries the finished character (dead keys and compose
        // sequences already resolved), so nothing is lost or doubled.
        previewArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (!editable || shown == null || !isPromotingCharacter(event)) {
                return;
            }
            event.consume();
            requestPromotion(event.getCharacter());
        });
        previewArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!editable || shown == null || !isPromotingEditKey(event)) {
                return;
            }
            event.consume();
            requestPromotion(null);
        });
        var scrollPane = EditorSettingsHelper.createScrollPane(previewArea);
        editorHolder.getChildren().setAll(scrollPane);
    }

    private void requestPromotion(String typed) {
        if (shown == null || shown.isPolicyManaged() || promote == null) {
            return;
        }
        int caret = previewArea != null ? previewArea.getCaretPosition() : 0;
        promote.accept(new PromoteRequest(shown, caret, typed));
    }

    /** A printable character typed without a shortcut modifier (AltGr = Ctrl+Alt counts as typing). */
    static boolean isPromotingCharacter(KeyEvent event) {
        String character = event.getCharacter();
        if (character == null || character.isEmpty() || KeyEvent.CHAR_UNDEFINED.equals(character)) {
            return false;
        }
        char first = character.charAt(0);
        if (first < 0x20 || first == 0x7f) {
            return false;
        }
        if (event.isMetaDown()) {
            return false;
        }
        return !event.isControlDown() || event.isAltDown();
    }

    /** Keys that edit without typing a character: Enter, Backspace, Delete, paste, cut. */
    private static boolean isPromotingEditKey(KeyEvent event) {
        KeyCode code = event.getCode();
        boolean plain = !event.isShortcutDown() && !event.isAltDown() && !event.isMetaDown() && !event.isControlDown();
        if (plain && (code == KeyCode.ENTER || code == KeyCode.BACK_SPACE || code == KeyCode.DELETE)) {
            return true;
        }
        return event.isShortcutDown() && !event.isAltDown() && (code == KeyCode.V || code == KeyCode.X);
    }

    private static String metaLine(Snippet snippet) {
        List<String> parts = new ArrayList<>();
        if (snippet.getLanguage() != null && !snippet.getLanguage().isBlank()) {
            parts.add(snippet.getLanguage());
        }
        if (snippet.getCategory() != null && !snippet.getCategory().isBlank()) {
            parts.add(snippet.getCategory());
        }
        if (snippet.getOperatingSystem() != null && !snippet.getOperatingSystem().isBlank()) {
            parts.add(snippet.getOperatingSystem());
        }
        String tags = snippet.getTagsAsString();
        if (tags != null && !tags.isBlank()) {
            parts.add(tags);
        }
        return String.join(" · ", parts);
    }

    private static boolean loadWordWrapSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings().isSnippetWordWrap();
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean loadLineNumbersSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings().isSnippetLineNumbers();
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveViewSetting(boolean enabled, boolean wordWrap) {
        try {
            var manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (wordWrap) {
                manager.getSettings().setSnippetWordWrap(enabled);
            } else {
                manager.getSettings().setSnippetLineNumbers(enabled);
            }
            manager.scheduleSave();
        } catch (Exception e) {
            logger.debug("Could not save the snippet preview view setting", e);
        }
    }
}
