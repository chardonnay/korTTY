package de.kortty.ui;

import com.github.lalyos.jfiglet.FigletFont;
import de.kortty.core.AiAction;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiService;
import de.kortty.core.AsciiArtCopySupport;
import de.kortty.core.AsciiArtImageSupport;
import de.kortty.core.AsciiArtPictureRenderer;
import de.kortty.core.AsciiArtSupport;
import de.kortty.core.FailingAiService;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiProfile;
import de.kortty.model.AsciiArtCommentStyle;
import de.kortty.model.AsciiArtPictureSize;
import de.kortty.model.AsciiArtPrintStyle;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dialog to create ASCII art, with three tabs: a FIGlet text banner (jfiglet/FIGfonts), an
 * AI-drawn picture generated from a subject such as "house in the forest", and an existing image
 * file converted through the same cell matcher. All previews share one zoom level and one copy
 * action; the copy action can wrap the picture as a code comment or as print statements of a
 * chosen language. Zoom, picture sizes and copy format are remembered across sessions.
 *
 * <p>The AI tab only orchestrates: the request, the conversion and the retry policy live in
 * {@link AsciiArtSupport} (JavaFX-free, unit-tested). The dialog runs that pipeline on a daemon
 * thread, mirrors its stages into the status line, and discards results of a run the user has
 * since cancelled or superseded — every handler checks the generation it was started for.
 */
public class AsciiArtBannerDialog extends ThemeAwareDialog<Void> {

    /** Owner window, used to resolve the AI profile and service. {@code null} disables the AI tab. */
    private final MainWindow ownerWindow;

    private final TextField inputField;
    private final TextArea outputArea;
    private final ComboBox<String> styleCombo;

    private final TextField subjectField;
    private final TextArea aiOutputArea;
    private final Button generateButton;
    private final Button retryButton;
    private final Button cancelButton;
    private final ComboBox<SnippetAiDialogSupport.ProfileChoice> aiProfileCombo;
    private final ComboBox<AsciiArtPictureSize> sizeCombo;
    private final ProgressIndicator aiProgress;
    private final Label aiStatusLabel;

    private final TextArea imageOutputArea;
    private final Button openImageButton;
    private final Label imageFileLabel;
    private final ComboBox<AsciiArtPictureSize> imageSizeCombo;
    private final Slider brightnessSlider;
    private final Slider contrastSlider;
    private final CheckBox invertCheck;
    private final Button imageResetButton;
    private final ProgressIndicator imageProgress;
    private final Label imageStatusLabel;

    private final ComboBox<AsciiArtCommentStyle> commentCombo;
    private final ComboBox<AsciiArtPrintStyle> printCombo;
    private final Spinner<Integer> gapSpinner;
    private final Tooltip copyTooltip;

    private final TabPane tabPane;
    private final Label zoomLabel;

    private double previewFontSize = AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE;

    /** The subject the current AI picture was drawn for; {@code null} until one succeeded. */
    private String lastAiSubject;
    /** 0-based attempt counter for the current subject; each retry asks for a different treatment. */
    private int aiAttempt;
    private Task<AsciiArtSupport.AsciiArtResult> aiTask;
    /**
     * Bumped on every start and cancel. A handler or status update from an older generation is
     * ignored, so a slow request that finishes after Cancel cannot overwrite the next picture.
     */
    private long aiGeneration;
    /** Guards the comment/print combos while one resets the other. */
    private boolean syncingCopyStyles;

    /** The opened image reduced to luminance; {@code null} until a file was loaded. */
    private AsciiArtImageSupport.GrayImage loadedImage;
    private Task<AsciiArtImageSupport.GrayImage> imageTask;
    /** Bumped per load so a slow decode cannot overwrite a file opened after it. */
    private long imageGeneration;
    /** Where the file chooser opens next; starts at the platform default. */
    private File lastImageDirectory;

    /** File extensions the JDK decodes, offered by the chooser and accepted on drop. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "wbmp");

    /** Bundled font names (must match flf/<name>.flf filename without .flf). */
    private static final String[] BUNDLED_FONTS = {
        "3-D", "banner", "big", "block", "cosmic", "Digital", "Lean", "roman", "script", "small"
    };

    /** Cached loaded fonts per style so each font file is only parsed once. */
    private static final Map<String, FigletFont> FONT_CACHE = new ConcurrentHashMap<>();

    /** Shown in the copy tooltip while both previews are still empty. */
    private static final String COPY_SAMPLE_LINE = " /\\_/\\";

    public AsciiArtBannerDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        setTitle(I18n.get("asciiArt.title"));
        setResizable(true);

        // ---- Text banner tab ----

        styleCombo = new ComboBox<>();
        List<String> styleList = buildFontList();
        styleCombo.getItems().addAll(styleList);
        styleCombo.setValue(styleList.isEmpty() ? null : styleList.get(0));
        styleCombo.setPrefWidth(200);
        styleCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateOutput());
        styleCombo.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.UP) {
                selectPrevStyle();
                e.consume();
            } else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.DOWN) {
                selectNextStyle();
                e.consume();
            }
        });

        inputField = new TextField();
        inputField.setPromptText(I18n.get("asciiArt.inputPrompt"));
        inputField.setPrefColumnCount(30);
        inputField.textProperty().addListener((obs, oldVal, newVal) -> updateOutput());

        outputArea = buildPreviewArea();

        Button prevStyleBtn = new Button("◀");
        prevStyleBtn.setTooltip(new Tooltip(I18n.get("asciiArt.prevStyle")));
        prevStyleBtn.setOnAction(e -> selectPrevStyle());
        Button nextStyleBtn = new Button("▶");
        nextStyleBtn.setTooltip(new Tooltip(I18n.get("asciiArt.nextStyle")));
        nextStyleBtn.setOnAction(e -> selectNextStyle());
        HBox styleBox = new HBox(5, prevStyleBtn, styleCombo, nextStyleBtn);
        styleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(styleCombo, Priority.ALWAYS);

        GridPane bannerGrid = new GridPane();
        bannerGrid.setHgap(10);
        bannerGrid.setVgap(10);
        bannerGrid.setPadding(new Insets(15));
        bannerGrid.add(rowLabel(I18n.get("asciiArt.style")), 0, 0);
        bannerGrid.add(styleBox, 1, 0);
        bannerGrid.add(rowLabel(I18n.get("asciiArt.inputLabel")), 0, 1);
        bannerGrid.add(inputField, 1, 1);
        GridPane.setHgrow(inputField, Priority.ALWAYS);
        bannerGrid.add(rowLabel(I18n.get("asciiArt.outputLabel")), 0, 2);
        bannerGrid.add(outputArea, 1, 2);
        GridPane.setHgrow(outputArea, Priority.ALWAYS);
        GridPane.setVgrow(outputArea, Priority.ALWAYS);

        // ---- AI picture tab ----

        subjectField = new TextField();
        subjectField.setPromptText(I18n.get("asciiArt.ai.subjectPrompt"));
        subjectField.setPrefColumnCount(24);
        subjectField.textProperty().addListener((obs, oldVal, newVal) -> updateAiControls());
        subjectField.setOnAction(e -> startGeneration(false));

        sizeCombo = new ComboBox<>();
        sizeCombo.getItems().addAll(AsciiArtPictureSize.values());
        sizeCombo.setValue(AsciiArtPictureSize.DEFAULT);
        applyCellText(sizeCombo, AsciiArtBannerDialog::sizeLabel);
        sizeCombo.setMinWidth(Region.USE_PREF_SIZE);

        generateButton = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("asciiArt.ai.generate"));
        generateButton.setDefaultButton(false);
        generateButton.setMinWidth(Region.USE_PREF_SIZE);
        generateButton.setOnAction(e -> startGeneration(false));

        retryButton = new Button("↻ " + I18n.get("asciiArt.ai.retry"));
        retryButton.setTooltip(new Tooltip(I18n.get("asciiArt.ai.retry.hint")));
        retryButton.setMinWidth(Region.USE_PREF_SIZE);
        retryButton.setOnAction(e -> startGeneration(true));

        cancelButton = new Button(I18n.get("asciiArt.ai.cancel"));
        cancelButton.setMinWidth(Region.USE_PREF_SIZE);
        cancelButton.setOnAction(e -> {
            cancelAiTask();
            setAiBusy(false, I18n.get("asciiArt.ai.cancelled"));
        });

        aiProfileCombo = SnippetAiDialogSupport.buildProfileCombo(null);

        aiOutputArea = buildPreviewArea();

        aiProgress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        aiProgress.setPrefSize(18, 18);
        aiProgress.setMinSize(18, 18);
        aiProgress.setMaxSize(18, 18);
        aiProgress.setVisible(false);
        aiProgress.setManaged(false);
        aiStatusLabel = new Label();
        aiStatusLabel.setWrapText(true);
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox aiStatusBox = new HBox(8, aiProgress, aiStatusLabel, statusSpacer, cancelButton);
        aiStatusBox.setAlignment(Pos.CENTER_LEFT);

        HBox subjectBox = new HBox(5, subjectField, rowLabel(I18n.get("asciiArt.ai.sizeLabel")), sizeCombo, generateButton);
        subjectBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(subjectField, Priority.ALWAYS);

        HBox profileBox = new HBox(5, aiProfileCombo, retryButton);
        profileBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(aiProfileCombo, Priority.ALWAYS);

        GridPane aiGrid = new GridPane();
        aiGrid.setHgap(10);
        aiGrid.setVgap(10);
        aiGrid.setPadding(new Insets(15));
        aiGrid.add(rowLabel(I18n.get("asciiArt.ai.subjectLabel")), 0, 0);
        aiGrid.add(subjectBox, 1, 0);
        aiGrid.add(SnippetAiDialogSupport.profileLabel(), 0, 1);
        aiGrid.add(profileBox, 1, 1);
        aiGrid.add(rowLabel(I18n.get("asciiArt.outputLabel")), 0, 2);
        aiGrid.add(aiOutputArea, 1, 2);
        GridPane.setHgrow(aiOutputArea, Priority.ALWAYS);
        GridPane.setVgrow(aiOutputArea, Priority.ALWAYS);
        aiGrid.add(aiStatusBox, 1, 3);

        // ---- Image file tab ----

        openImageButton = new Button(I18n.get("asciiArt.image.open"));
        openImageButton.setMinWidth(Region.USE_PREF_SIZE);
        openImageButton.setOnAction(e -> openImage());
        imageFileLabel = new Label(I18n.get("asciiArt.image.noFile"));
        imageFileLabel.setWrapText(true);

        imageSizeCombo = new ComboBox<>();
        imageSizeCombo.getItems().addAll(AsciiArtPictureSize.values());
        imageSizeCombo.setValue(AsciiArtPictureSize.DEFAULT);
        applyCellText(imageSizeCombo, AsciiArtBannerDialog::sizeLabel);
        imageSizeCombo.setMinWidth(Region.USE_PREF_SIZE);
        imageSizeCombo.valueProperty().addListener((obs, oldVal, newVal) -> renderImage());

        brightnessSlider = adjustmentSlider(I18n.get("asciiArt.image.brightness"));
        contrastSlider = adjustmentSlider(I18n.get("asciiArt.image.contrast"));
        invertCheck = new CheckBox(I18n.get("asciiArt.image.invert"));
        invertCheck.setTooltip(new Tooltip(I18n.get("asciiArt.image.invert.hint")));
        invertCheck.setMinWidth(Region.USE_PREF_SIZE);
        invertCheck.selectedProperty().addListener((obs, oldVal, newVal) -> renderImage());
        imageResetButton = new Button(I18n.get("asciiArt.image.reset"));
        imageResetButton.setTooltip(new Tooltip(I18n.get("asciiArt.image.reset.hint")));
        imageResetButton.setMinWidth(Region.USE_PREF_SIZE);
        imageResetButton.setOnAction(e -> {
            brightnessSlider.setValue(0);
            contrastSlider.setValue(0);
            invertCheck.setSelected(false);
        });

        imageOutputArea = buildPreviewArea();

        imageProgress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        imageProgress.setPrefSize(18, 18);
        imageProgress.setMinSize(18, 18);
        imageProgress.setMaxSize(18, 18);
        imageProgress.setVisible(false);
        imageProgress.setManaged(false);
        imageStatusLabel = new Label();
        imageStatusLabel.setWrapText(true);
        HBox imageStatusBox = new HBox(8, imageProgress, imageStatusLabel);
        imageStatusBox.setAlignment(Pos.CENTER_LEFT);

        HBox imageFileBox = new HBox(5, openImageButton, imageFileLabel, rowLabel(I18n.get("asciiArt.ai.sizeLabel")), imageSizeCombo);
        imageFileBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(imageFileLabel, Priority.ALWAYS);
        imageFileLabel.setMaxWidth(Double.MAX_VALUE);

        HBox adjustBox = new HBox(8, brightnessSlider, rowLabel(I18n.get("asciiArt.image.contrast")), contrastSlider,
            invertCheck, imageResetButton);
        adjustBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(brightnessSlider, Priority.ALWAYS);
        HBox.setHgrow(contrastSlider, Priority.ALWAYS);

        GridPane imageGrid = new GridPane();
        imageGrid.setHgap(10);
        imageGrid.setVgap(10);
        imageGrid.setPadding(new Insets(15));
        imageGrid.add(rowLabel(I18n.get("asciiArt.tab.image")), 0, 0);
        imageGrid.add(imageFileBox, 1, 0);
        imageGrid.add(rowLabel(I18n.get("asciiArt.image.brightness")), 0, 1);
        imageGrid.add(adjustBox, 1, 1);
        imageGrid.add(rowLabel(I18n.get("asciiArt.outputLabel")), 0, 2);
        imageGrid.add(imageOutputArea, 1, 2);
        GridPane.setHgrow(imageOutputArea, Priority.ALWAYS);
        GridPane.setVgrow(imageOutputArea, Priority.ALWAYS);
        imageGrid.add(imageStatusBox, 1, 3);
        installImageDrop(imageGrid);

        Tab bannerTab = new Tab(I18n.get("asciiArt.tab.figlet"), bannerGrid);
        bannerTab.setClosable(false);
        Tab aiTab = new Tab(I18n.get("asciiArt.tab.ai"), aiGrid);
        aiTab.setClosable(false);
        Tab imageTab = new Tab(I18n.get("asciiArt.tab.image"), imageGrid);
        imageTab.setClosable(false);
        tabPane = new TabPane(bannerTab, aiTab, imageTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ---- Copy format row: comment marker or print statements, plus the gap ----

        commentCombo = new ComboBox<>();
        commentCombo.getItems().addAll(AsciiArtCommentStyle.values());
        commentCombo.setValue(AsciiArtCommentStyle.NONE);
        applyCellText(commentCombo, style -> style == AsciiArtCommentStyle.NONE
            ? I18n.get("asciiArt.copy.none") : style.label());

        printCombo = new ComboBox<>();
        printCombo.getItems().addAll(AsciiArtPrintStyle.values());
        printCombo.setValue(AsciiArtPrintStyle.NONE);
        applyCellText(printCombo, style -> style == AsciiArtPrintStyle.NONE
            ? I18n.get("asciiArt.copy.none") : style.label());

        gapSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            AsciiArtCopySupport.MIN_GAP, AsciiArtCopySupport.MAX_GAP, AsciiArtCopySupport.DEFAULT_GAP));
        gapSpinner.setEditable(true);
        gapSpinner.setPrefWidth(70);
        gapSpinner.setTooltip(new Tooltip(I18n.get("asciiArt.copy.gap.hint")));

        // A comment and a print wrapper make no sense together, so picking one clears the other.
        commentCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!syncingCopyStyles && newVal != null && newVal != AsciiArtCommentStyle.NONE) {
                syncingCopyStyles = true;
                try {
                    printCombo.setValue(AsciiArtPrintStyle.NONE);
                } finally {
                    syncingCopyStyles = false;
                }
            }
            updateCopyHint();
        });
        printCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!syncingCopyStyles && newVal != null && newVal != AsciiArtPrintStyle.NONE) {
                syncingCopyStyles = true;
                try {
                    commentCombo.setValue(AsciiArtCommentStyle.NONE);
                } finally {
                    syncingCopyStyles = false;
                }
            }
            updateCopyHint();
        });
        gapSpinner.valueProperty().addListener((obs, oldVal, newVal) -> updateCopyHint());

        HBox copyRow = new HBox(5,
            rowLabel(I18n.get("asciiArt.copy.as")),
            rowLabel(I18n.get("asciiArt.copy.comment")), commentCombo,
            rowLabel(I18n.get("asciiArt.copy.print")), printCombo,
            rowLabel(I18n.get("asciiArt.copy.gap")), gapSpinner);
        copyRow.setAlignment(Pos.CENTER_LEFT);
        copyRow.setPadding(new Insets(0, 15, 0, 15));
        // The combos take a fixed share so the gap spinner at the row's end is never squeezed
        // away; the long comment labels are readable in the drop-down list.
        commentCombo.setPrefWidth(230);
        commentCombo.setMinWidth(120);
        printCombo.setPrefWidth(200);
        printCombo.setMinWidth(120);
        gapSpinner.setMinWidth(Region.USE_PREF_SIZE);

        // ---- Shared bottom bar: zoom on the left, copy on the right ----

        Button zoomOutBtn = new Button("−");
        zoomOutBtn.setTooltip(new Tooltip(I18n.get("asciiArt.zoomOut")));
        zoomOutBtn.setOnAction(e -> zoomBy(-1));
        Button zoomInBtn = new Button("+");
        zoomInBtn.setTooltip(new Tooltip(I18n.get("asciiArt.zoomIn")));
        zoomInBtn.setOnAction(e -> zoomBy(1));
        Button zoomResetBtn = new Button("⟲");
        zoomResetBtn.setTooltip(new Tooltip(I18n.get("asciiArt.zoomReset")));
        zoomResetBtn.setOnAction(e -> resetZoom());
        zoomLabel = new Label();
        zoomLabel.setMinWidth(52);
        zoomLabel.setAlignment(Pos.CENTER);

        Button copyBtn = new Button(I18n.get("asciiArt.copyToClipboard"));
        copyBtn.setMinWidth(Region.USE_PREF_SIZE);
        copyBtn.setOnAction(e -> copyToClipboard());
        copyTooltip = new Tooltip();
        copyBtn.setTooltip(copyTooltip);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(5,
            rowLabel(I18n.get("asciiArt.zoom")), zoomOutBtn, zoomLabel, zoomInBtn, zoomResetBtn,
            spacer, copyBtn);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(0, 15, 15, 15));

        VBox root = new VBox(10, tabPane, copyRow, bottomBar);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(780);
        getDialogPane().setPrefHeight(620);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleZoomShortcut);

        tabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> updateCopyHint());
        outputArea.textProperty().addListener((obs, oldVal, newVal) -> updateCopyHint());
        aiOutputArea.textProperty().addListener((obs, oldVal, newVal) -> updateCopyHint());
        imageOutputArea.textProperty().addListener((obs, oldVal, newVal) -> updateCopyHint());

        restoreState();
        setOnCloseRequest(e -> saveState());
        setResultConverter(bt -> { saveState(); return null; });

        applyPreviewFontSize();
        updateOutput();
        updateAiControls();
        updateImageControls();
        updateCopyHint();
    }

    /** A -100..100 slider whose neutral middle is 0; the tooltip names what it adjusts. */
    private Slider adjustmentSlider(String name) {
        Slider slider = new Slider(-100, 100, 0);
        slider.setBlockIncrement(5);
        slider.setTooltip(new Tooltip(name));
        slider.setMinWidth(90);
        slider.valueProperty().addListener((obs, oldVal, newVal) -> renderImage());
        return slider;
    }

    /** A read-only monospace preview that zooms with the shared zoom level. */
    private TextArea buildPreviewArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(14);
        area.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() && e.getDeltaY() != 0) {
                zoomBy(e.getDeltaY() > 0 ? 1 : -1);
                e.consume();
            }
        });
        return area;
    }

    /**
     * A row label that keeps its full text: the rows around it hold growing fields and combos, and
     * without a minimum width JavaFX shortens the label to an ellipsis before it shrinks those.
     */
    private static Label rowLabel(String text) {
        Label label = new Label(text + ":");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /** Renders every item of {@code combo} (list and button cell) through {@code text}. */
    private static <T> void applyCellText(ComboBox<T> combo, Function<T, String> text) {
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        });
    }

    private static String sizeLabel(AsciiArtPictureSize size) {
        return I18n.get("asciiArt.ai.size." + size.name().toLowerCase(Locale.ROOT))
            + " (" + size.columns() + " × " + size.rows() + ")";
    }

    // ---- Zoom ----

    private void handleZoomShortcut(KeyEvent event) {
        if (!event.isShortcutDown()) {
            return;
        }
        switch (event.getCode()) {
            case PLUS, ADD, EQUALS -> { zoomBy(1); event.consume(); }
            case MINUS, SUBTRACT -> { zoomBy(-1); event.consume(); }
            case DIGIT0, NUMPAD0 -> { resetZoom(); event.consume(); }
            default -> { /* not a zoom shortcut */ }
        }
    }

    private void zoomBy(int steps) {
        previewFontSize = AsciiArtSupport.stepPreviewFontSize(previewFontSize, steps);
        applyPreviewFontSize();
    }

    private void resetZoom() {
        previewFontSize = AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE;
        applyPreviewFontSize();
    }

    private void applyPreviewFontSize() {
        String style = AsciiArtSupport.previewStyle(previewFontSize);
        outputArea.setStyle(style);
        aiOutputArea.setStyle(style);
        imageOutputArea.setStyle(style);
        zoomLabel.setText(AsciiArtSupport.zoomPercent(previewFontSize) + " %");
    }

    // ---- AI picture ----

    /** Whether the AI tab can run: an owner window, the AI feature switch, and at least one profile. */
    private boolean isAiAvailable() {
        if (ownerWindow == null) {
            return false;
        }
        GlobalSettings settings = currentSettings();
        if (settings == null || !settings.isAiFeaturesEnabled()) {
            return false;
        }
        return !ownerWindow.getAvailableAiProfiles().isEmpty();
    }

    private void updateAiControls() {
        boolean available = isAiAvailable();
        boolean busy = aiTask != null && aiTask.isRunning();
        boolean hasSubject = !subjectField.getText().isBlank();

        subjectField.setDisable(!available);
        sizeCombo.setDisable(!available || busy);
        aiProfileCombo.setDisable(!available || busy);
        generateButton.setDisable(!available || busy || !hasSubject);
        retryButton.setDisable(!available || busy || lastAiSubject == null);
        cancelButton.setVisible(busy);
        cancelButton.setManaged(busy);

        if (!available) {
            aiStatusLabel.setText(I18n.get("asciiArt.ai.unavailable"));
        }
    }

    private void setAiBusy(boolean busy, String statusText) {
        aiProgress.setVisible(busy);
        aiProgress.setManaged(busy);
        aiStatusLabel.setText(statusText != null ? statusText : "");
        updateAiControls();
    }

    /**
     * Starts a generation for the subject in the field. A {@code retry} asks for the next variation of
     * the same subject; a fresh generation starts the variation counter over.
     */
    private void startGeneration(boolean retry) {
        if (!isAiAvailable()) {
            return;
        }
        String subject = subjectField.getText() != null ? subjectField.getText().trim() : "";
        if (subject.isEmpty()) {
            setAiBusy(false, I18n.get("asciiArt.ai.subjectPrompt"));
            return;
        }
        cancelAiTask();
        long generation = ++aiGeneration;
        aiAttempt = retry && subject.equals(lastAiSubject) ? aiAttempt + 1 : 0;

        String profileId = SnippetAiDialogSupport.selectedProfileId(aiProfileCombo);
        int attempt = aiAttempt;
        AsciiArtPictureSize size = sizeCombo.getValue() != null ? sizeCombo.getValue() : AsciiArtPictureSize.DEFAULT;
        Task<AsciiArtSupport.AsciiArtResult> task = new Task<>() {
            @Override
            protected AsciiArtSupport.AsciiArtResult call() throws Exception {
                return generateArt(subject, profileId, attempt, size, stage -> Platform.runLater(() -> {
                    if (generation == aiGeneration) {
                        aiStatusLabel.setText(stageText(stage));
                    }
                }));
            }
        };
        aiTask = task;
        task.setOnRunning(event -> {
            if (generation == aiGeneration) {
                setAiBusy(true, stageText(AsciiArtSupport.Stage.REQUESTING));
            }
        });
        task.setOnSucceeded(event -> {
            if (generation != aiGeneration) {
                return;
            }
            aiTask = null;
            showResult(task.getValue(), subject);
        });
        task.setOnFailed(event -> {
            if (generation != aiGeneration) {
                return;
            }
            aiTask = null;
            Throwable error = task.getException();
            String detail = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage().strip()
                : null;
            setAiBusy(false, detail != null
                ? I18n.get("asciiArt.ai.failed", detail)
                : I18n.get("asciiArt.ai.failed", I18n.get("ai.result.error")));
        });
        // No onCancelled handler: the only cancel path is cancelAiTask(), which bumps the
        // generation (so a late result is ignored) and whose callers set the status themselves.
        Thread thread = new Thread(task, "ascii-art-ai");
        thread.setDaemon(true);
        thread.start();
    }

    private void showResult(AsciiArtSupport.AsciiArtResult result, String subject) {
        if (result == null || !result.isUsable()) {
            AsciiArtPictureRenderer.RejectReason rejection = result != null ? result.rejection() : null;
            if (rejection == AsciiArtPictureRenderer.RejectReason.TRUNCATED_EMPTY) {
                setAiBusy(false, I18n.get("asciiArt.ai.truncatedEmpty"));
            } else if (rejection != null) {
                setAiBusy(false, I18n.get("asciiArt.ai.emptyReason",
                    I18n.get("asciiArt.ai.reject." + rejection.i18nKey())));
            } else {
                setAiBusy(false, I18n.get("asciiArt.ai.empty"));
            }
            return;
        }
        aiOutputArea.setText(result.picture());
        lastAiSubject = subject;
        String note = "";
        if (result.truncated()) {
            note = I18n.get("asciiArt.ai.truncated");
        } else if (result.source() == AsciiArtSupport.Source.LEGACY_ASCII) {
            note = I18n.get("asciiArt.ai.fallbackUsed");
        }
        setAiBusy(false, note);
    }

    private static String stageText(AsciiArtSupport.Stage stage) {
        return switch (stage) {
            case REQUESTING -> I18n.get("asciiArt.ai.status.requesting");
            case CONVERTING -> I18n.get("asciiArt.ai.status.converting");
            case REPAIRING -> I18n.get("asciiArt.ai.status.repairing");
            case FALLBACK -> I18n.get("asciiArt.ai.status.fallback");
        };
    }

    /**
     * Resolves the profile and service for this run and runs the drawing pipeline. Reasoning is
     * switched off for this action where the profile offers that (the picture's fidelity comes from
     * the composition, not from deliberation), and a service that can only fail is reported before
     * a request is spent on it.
     */
    private AsciiArtSupport.AsciiArtResult generateArt(
            String subject,
            String profileId,
            int attempt,
            AsciiArtPictureSize size,
            Consumer<AsciiArtSupport.Stage> stageListener) throws Exception {
        AiProfile profile = ownerWindow.resolveAiProfileForAction(null, AiAction.GENERATE_ASCII_ART, profileId);
        if (profile == null) {
            throw new IllegalStateException(I18n.get("ai.error.notConfigured"));
        }
        AiProfile executionProfile = AiReasoningSupport.profileForAction(profile, AiAction.GENERATE_ASCII_ART);
        AiService service = ownerWindow.createAiServiceForProfile(executionProfile);
        if (service == null) {
            throw new IllegalStateException(I18n.get("ai.error.notConfigured"));
        }
        if (service instanceof FailingAiService failing) {
            throw new IllegalStateException(failing.message());
        }
        return AsciiArtSupport.generateAsciiArt(
            service,
            subject,
            null,
            LanguageManager.getInstance().getCurrentLanguageCode(),
            attempt,
            size,
            (request, result) -> ownerWindow.recordAiUsageForProfile(profile, request, result),
            stageListener);
    }

    private void cancelAiTask() {
        aiGeneration++;
        Task<AsciiArtSupport.AsciiArtResult> running = aiTask;
        aiTask = null;
        if (running != null && running.isRunning()) {
            // cancel() interrupts the worker: the streaming HTTP transports check the interrupt
            // between chunks, the CLI transport destroys its process, and the pipeline checks it
            // between its steps, so the request is abandoned rather than merely ignored.
            running.cancel();
        }
    }

    // ---- State ----

    private static GlobalSettings currentSettings() {
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            return gsm != null ? gsm.getSettings() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void restoreState() {
        try {
            GlobalSettings settings = currentSettings();
            if (settings == null) return;
            previewFontSize = AsciiArtSupport.clampPreviewFontSize(settings.getAsciiArtPreviewFontSize());
            sizeCombo.setValue(settings.getAsciiArtPictureSize());
            imageSizeCombo.setValue(settings.getAsciiArtImageSize());
            // Through the live listeners, so a settings file that somehow holds both styles ends
            // up with the same precedence the formatter applies: the print style wins.
            commentCombo.setValue(settings.getAsciiArtCopyCommentStyle());
            printCombo.setValue(settings.getAsciiArtCopyPrintStyle());
            gapSpinner.getValueFactory().setValue(AsciiArtCopySupport.clampGap(settings.getAsciiArtCopyGap()));
            DialogGeometrySupport.restore(this, settings.getAsciiArtDialogGeometry());
        } catch (Exception ignored) { /* use default size/position/zoom */ }
    }

    private void saveState() {
        cancelAiTask();
        cancelImageTask();
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (gsm == null || gsm.getSettings() == null) return;
            writeState(gsm.getSettings());
            // One save for everything; persist(...) would write the settings file a second time.
            gsm.save();
        } catch (Exception ignored) { /* skip save on error */ }
    }

    /**
     * Writes everything the dialog remembers into {@code settings} without saving them: the window
     * geometry (through the shared helper, so the UI font scale stamp that validates the stored
     * size on the next open is refreshed too), the zoom, the picture sizes and the copy format.
     */
    void writeState(GlobalSettings settings) {
        DialogGeometrySupport.store(this, settings, GlobalSettings::setAsciiArtDialogGeometry);
        settings.setAsciiArtPreviewFontSize(previewFontSize);
        settings.setAsciiArtPictureSize(sizeCombo.getValue());
        settings.setAsciiArtImageSize(imageSizeCombo.getValue());
        settings.setAsciiArtCopyCommentStyle(commentCombo.getValue());
        settings.setAsciiArtCopyPrintStyle(printCombo.getValue());
        settings.setAsciiArtCopyGap(currentGap());
    }

    // ---- FIGlet rendering ----

    private static List<String> buildFontList() {
        List<String> names = new ArrayList<>();
        names.add("Standard");
        try (InputStream slant = openFontStream("Slant", "/slant.flf")) {
            if (slant != null) names.add("Slant");
        } catch (Exception ignored) { /* no Slant */ }
        for (String name : BUNDLED_FONTS) {
            boolean added = false;
            try (InputStream in = openFontStream(name, "/flf/" + name + ".flf")) {
                if (in != null) {
                    names.add(name);
                    added = true;
                }
            } catch (Exception ignored) {
                if (!added && fontResourceExists(name)) names.add(name);
            }
        }
        return names;
    }

    private static boolean fontResourceExists(String style) {
        if (AsciiArtBannerDialog.class.getResource("/flf/" + style + ".flf") != null) return true;
        if (!style.equals(style.toLowerCase()) && AsciiArtBannerDialog.class.getResource("/flf/" + style.toLowerCase() + ".flf") != null) return true;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && ctx.getResource("flf/" + style + ".flf") != null) return true;
        if (ctx != null && !style.equals(style.toLowerCase()) && ctx.getResource("flf/" + style.toLowerCase() + ".flf") != null) return true;
        return false;
    }

    /** Opens a font stream using classloader and path variants so resources are found from JAR or IDE. */
    private static InputStream openFontStream(String style, String classPath) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            String noLeading = classPath.startsWith("/") ? classPath.substring(1) : classPath;
            InputStream in = ctx.getResourceAsStream(noLeading);
            if (in != null) return in;
            String lower = "flf/" + style.toLowerCase() + ".flf";
            if (!lower.equals(noLeading)) {
                in = ctx.getResourceAsStream(lower);
                if (in != null) return in;
            }
        }
        InputStream in = AsciiArtBannerDialog.class.getResourceAsStream(classPath);
        if (in != null) return in;
        if (!style.equals(style.toLowerCase())) {
            in = AsciiArtBannerDialog.class.getResourceAsStream("/flf/" + style.toLowerCase() + ".flf");
            if (in != null) return in;
        }
        return null;
    }

    private void updateOutput() {
        String text = inputField.getText();
        if (text == null || text.isBlank()) {
            outputArea.setText("");
            return;
        }
        String styleVal = styleCombo.getValue() != null ? styleCombo.getValue() : "Standard";
        try {
            String result = text.lines()
                    .map(line -> {
                        if (line.isBlank()) return "";
                        try {
                            return convertLine(styleVal, line);
                        } catch (Exception e) {
                            return line;
                        }
                    })
                    .collect(Collectors.joining("\n"));
            outputArea.setText(result);
        } catch (Exception e) {
            outputArea.setText(text);
        }
    }

    private static String convertLine(String style, String line) throws Exception {
        if ("Standard".equals(style)) {
            return FigletFont.convertOneLine(line);
        }
        FigletFont font = getOrLoadFont(style);
        if (font != null) {
            return font.convert(line);
        }
        return FigletFont.convertOneLine(line);
    }

    /** Load and cache a font by style; uses openFontStream and reads fully so stream is not reused. */
    private static FigletFont getOrLoadFont(String style) {
        FigletFont cached = FONT_CACHE.get(style);
        if (cached != null) return cached;
        InputStream in = null;
        if ("Slant".equals(style)) {
            in = FigletFont.class.getResourceAsStream("/slant.flf");
            if (in == null) in = openFontStream(style, "/slant.flf");
        } else {
            in = openFontStream(style, "/flf/" + style + ".flf");
        }
        try {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            FigletFont font = new FigletFont(new ByteArrayInputStream(bytes));
            FONT_CACHE.put(style, font);
            return font;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void selectPrevStyle() {
        List<String> items = styleCombo.getItems();
        if (items.isEmpty()) return;
        String current = styleCombo.getValue();
        int idx = current != null ? items.indexOf(current) : 0;
        idx = idx <= 0 ? items.size() - 1 : idx - 1;
        styleCombo.setValue(items.get(idx));
    }

    private void selectNextStyle() {
        List<String> items = styleCombo.getItems();
        if (items.isEmpty()) return;
        String current = styleCombo.getValue();
        int idx = current != null ? items.indexOf(current) : -1;
        idx = idx < 0 || idx >= items.size() - 1 ? 0 : idx + 1;
        styleCombo.setValue(items.get(idx));
    }

    // ---- Image file ----

    private void updateImageControls() {
        boolean busy = imageTask != null && imageTask.isRunning();
        boolean hasImage = loadedImage != null;
        openImageButton.setDisable(busy);
        imageSizeCombo.setDisable(busy || !hasImage);
        brightnessSlider.setDisable(busy || !hasImage);
        contrastSlider.setDisable(busy || !hasImage);
        invertCheck.setDisable(busy || !hasImage);
        imageResetButton.setDisable(busy || !hasImage);
        imageProgress.setVisible(busy);
        imageProgress.setManaged(busy);
    }

    private void openImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("asciiArt.image.chooserTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            I18n.get("asciiArt.image.filter"), IMAGE_EXTENSIONS.stream().map(ext -> "*." + ext).sorted().toList()));
        if (lastImageDirectory != null && lastImageDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastImageDirectory);
        }
        Window owner = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        File file = chooser.showOpenDialog(owner);
        if (file != null) {
            loadImageFile(file);
        }
    }

    /** Accepts one image file dropped anywhere on the tab, the same way the Open button does. */
    private void installImageDrop(Region target) {
        target.setOnDragOver(event -> {
            if (droppedImage(event) != null) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        target.setOnDragDropped(event -> {
            File file = droppedImage(event);
            if (file != null) {
                loadImageFile(file);
            }
            event.setDropCompleted(file != null);
            event.consume();
        });
    }

    private static File droppedImage(DragEvent event) {
        Dragboard board = event.getDragboard();
        if (board == null || !board.hasFiles()) {
            return null;
        }
        for (File file : board.getFiles()) {
            if (file != null && file.isFile() && hasImageExtension(file.getName())) {
                return file;
            }
        }
        return null;
    }

    private static boolean hasImageExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Decodes {@code file} on a worker thread — a large JPEG takes a noticeable moment — and then
     * converts it. Only the decode is asynchronous: every later slider move converts the cached
     * luminance image on the FX thread, which takes a few milliseconds even at the largest grid.
     */
    private void loadImageFile(File file) {
        cancelImageTask();
        long generation = ++imageGeneration;
        lastImageDirectory = file.getParentFile();
        Task<AsciiArtImageSupport.GrayImage> task = new Task<>() {
            @Override
            protected AsciiArtImageSupport.GrayImage call() throws Exception {
                return AsciiArtImageSupport.load(file.toPath());
            }
        };
        imageTask = task;
        task.setOnRunning(event -> {
            if (generation == imageGeneration) {
                imageStatusLabel.setText(I18n.get("asciiArt.image.loading"));
                updateImageControls();
            }
        });
        task.setOnSucceeded(event -> {
            if (generation != imageGeneration) {
                return;
            }
            imageTask = null;
            showImage(task.getValue(), file.getName());
        });
        task.setOnFailed(event -> {
            if (generation != imageGeneration) {
                return;
            }
            imageTask = null;
            Throwable error = task.getException();
            String detail = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage().strip()
                : String.valueOf(error);
            imageStatusLabel.setText(I18n.get("asciiArt.image.failed", detail));
            updateImageControls();
        });
        Thread thread = new Thread(task, "ascii-art-image");
        thread.setDaemon(true);
        thread.start();
    }

    /** Shows a decoded image as the current one and converts it. */
    private void showImage(AsciiArtImageSupport.GrayImage image, String name) {
        loadedImage = image;
        imageFileLabel.setText(name != null ? name : "");
        updateImageControls();
        renderImage();
    }

    /** The smoke harness's entry point for an in-memory image. */
    void showImage(BufferedImage image, String name) {
        showImage(AsciiArtImageSupport.toGray(image), name);
    }

    private void renderImage() {
        if (loadedImage == null) {
            imageOutputArea.setText("");
            imageStatusLabel.setText("");
            return;
        }
        AsciiArtPictureSize size = imageSizeCombo.getValue() != null ? imageSizeCombo.getValue() : AsciiArtPictureSize.DEFAULT;
        AsciiArtImageSupport.Adjustments adjustments = new AsciiArtImageSupport.Adjustments(
            brightnessSlider.getValue() / 100.0, contrastSlider.getValue() / 100.0, invertCheck.isSelected());
        String picture = AsciiArtImageSupport.convert(loadedImage, size, adjustments);
        if (picture == null) {
            imageOutputArea.setText("");
            imageStatusLabel.setText(I18n.get("asciiArt.image.empty"));
            return;
        }
        imageOutputArea.setText(picture);
        int rows = (int) picture.lines().count();
        int columns = picture.lines().mapToInt(String::length).max().orElse(0);
        imageStatusLabel.setText(I18n.get("asciiArt.image.info",
            loadedImage.width(), loadedImage.height(), columns, rows));
    }

    private void cancelImageTask() {
        imageGeneration++;
        Task<AsciiArtImageSupport.GrayImage> running = imageTask;
        imageTask = null;
        if (running != null && running.isRunning()) {
            running.cancel();
        }
    }

    // ---- Copy ----

    /** The preview of the tab that is currently open. */
    private TextArea activePreviewArea() {
        return switch (tabPane.getSelectionModel().getSelectedIndex()) {
            case 1 -> aiOutputArea;
            case 2 -> imageOutputArea;
            default -> outputArea;
        };
    }

    private int currentGap() {
        Integer value = gapSpinner.getValue();
        return AsciiArtCopySupport.clampGap(value != null ? value : AsciiArtCopySupport.DEFAULT_GAP);
    }

    /** Shows the first line exactly as the next copy would produce it. */
    private void updateCopyHint() {
        String text = activePreviewArea().getText();
        String firstLine = text != null
            ? text.lines().filter(line -> !line.isBlank()).findFirst().orElse(COPY_SAMPLE_LINE)
            : COPY_SAMPLE_LINE;
        String preview = AsciiArtCopySupport.previewLine(firstLine, commentCombo.getValue(), printCombo.getValue(), currentGap());
        copyTooltip.setText(I18n.get("asciiArt.copy.hint", preview));
    }

    private void copyToClipboard() {
        String text = activePreviewArea().getText();
        if (text == null || text.isEmpty()) return;
        de.kortty.core.KorttyClipboard.setText(
            AsciiArtCopySupport.format(text, commentCombo.getValue(), printCombo.getValue(), currentGap()));
    }
}
