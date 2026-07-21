package de.kortty.ui;

import com.github.lalyos.jfiglet.FigletFont;
import de.kortty.core.AiAction;
import de.kortty.core.AiService;
import de.kortty.core.AsciiArtSupport;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dialog to create ASCII art, with two tabs: a FIGlet text banner (jfiglet/FIGfonts) and an
 * AI-drawn picture generated from a subject word such as "house". Both previews share one zoom
 * level and one copy action, and the zoom level is remembered across sessions.
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
    private final ComboBox<SnippetAiDialogSupport.ProfileChoice> aiProfileCombo;
    private final ProgressIndicator aiProgress;
    private final Label aiStatusLabel;

    private final TabPane tabPane;
    private final Label zoomLabel;

    private double previewFontSize = AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE;

    /** The subject the current AI picture was drawn for; {@code null} until one succeeded. */
    private String lastAiSubject;
    /** 0-based attempt counter for the current subject; each retry asks for a different treatment. */
    private int aiAttempt;
    private Task<String> aiTask;

    /** Bundled font names (must match flf/<name>.flf filename without .flf). */
    private static final String[] BUNDLED_FONTS = {
        "3-D", "banner", "big", "block", "cosmic", "Digital", "Lean", "roman", "script", "small"
    };

    /** Cached loaded fonts per style so each font file is only parsed once. */
    private static final Map<String, FigletFont> FONT_CACHE = new ConcurrentHashMap<>();

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
        bannerGrid.add(new Label(I18n.get("asciiArt.style") + ":"), 0, 0);
        bannerGrid.add(styleBox, 1, 0);
        bannerGrid.add(new Label(I18n.get("asciiArt.inputLabel") + ":"), 0, 1);
        bannerGrid.add(inputField, 1, 1);
        GridPane.setHgrow(inputField, Priority.ALWAYS);
        bannerGrid.add(new Label(I18n.get("asciiArt.outputLabel") + ":"), 0, 2);
        bannerGrid.add(outputArea, 1, 2);
        GridPane.setHgrow(outputArea, Priority.ALWAYS);
        GridPane.setVgrow(outputArea, Priority.ALWAYS);

        // ---- AI picture tab ----

        subjectField = new TextField();
        subjectField.setPromptText(I18n.get("asciiArt.ai.subjectPrompt"));
        subjectField.setPrefColumnCount(24);
        subjectField.textProperty().addListener((obs, oldVal, newVal) -> updateAiControls());
        subjectField.setOnAction(e -> startGeneration(false));

        generateButton = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("asciiArt.ai.generate"));
        generateButton.setDefaultButton(false);
        generateButton.setOnAction(e -> startGeneration(false));

        retryButton = new Button("↻ " + I18n.get("asciiArt.ai.retry"));
        retryButton.setTooltip(new Tooltip(I18n.get("asciiArt.ai.retry.hint")));
        retryButton.setOnAction(e -> startGeneration(true));

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
        HBox aiStatusBox = new HBox(8, aiProgress, aiStatusLabel);
        aiStatusBox.setAlignment(Pos.CENTER_LEFT);

        HBox subjectBox = new HBox(5, subjectField, generateButton);
        subjectBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(subjectField, Priority.ALWAYS);

        HBox profileBox = new HBox(5, aiProfileCombo, retryButton);
        profileBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(aiProfileCombo, Priority.ALWAYS);

        GridPane aiGrid = new GridPane();
        aiGrid.setHgap(10);
        aiGrid.setVgap(10);
        aiGrid.setPadding(new Insets(15));
        aiGrid.add(new Label(I18n.get("asciiArt.ai.subjectLabel") + ":"), 0, 0);
        aiGrid.add(subjectBox, 1, 0);
        aiGrid.add(SnippetAiDialogSupport.profileLabel(), 0, 1);
        aiGrid.add(profileBox, 1, 1);
        aiGrid.add(new Label(I18n.get("asciiArt.outputLabel") + ":"), 0, 2);
        aiGrid.add(aiOutputArea, 1, 2);
        GridPane.setHgrow(aiOutputArea, Priority.ALWAYS);
        GridPane.setVgrow(aiOutputArea, Priority.ALWAYS);
        aiGrid.add(aiStatusBox, 1, 3);

        Tab bannerTab = new Tab(I18n.get("asciiArt.tab.figlet"), bannerGrid);
        bannerTab.setClosable(false);
        Tab aiTab = new Tab(I18n.get("asciiArt.tab.ai"), aiGrid);
        aiTab.setClosable(false);
        tabPane = new TabPane(bannerTab, aiTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

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
        copyBtn.setOnAction(e -> copyToClipboard());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(5,
            new Label(I18n.get("asciiArt.zoom") + ":"), zoomOutBtn, zoomLabel, zoomInBtn, zoomResetBtn,
            spacer, copyBtn);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(0, 15, 15, 15));

        VBox root = new VBox(10, tabPane, bottomBar);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(620);
        getDialogPane().setPrefHeight(560);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleZoomShortcut);

        restoreState();
        setOnCloseRequest(e -> saveState());
        setResultConverter(bt -> { saveState(); return null; });

        applyPreviewFontSize();
        updateOutput();
        updateAiControls();
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
        aiProfileCombo.setDisable(!available || busy);
        generateButton.setDisable(!available || busy || !hasSubject);
        retryButton.setDisable(!available || busy || lastAiSubject == null);

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
        aiAttempt = retry && subject.equals(lastAiSubject) ? aiAttempt + 1 : 0;

        String profileId = SnippetAiDialogSupport.selectedProfileId(aiProfileCombo);
        int attempt = aiAttempt;
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return generateArt(subject, profileId, attempt);
            }
        };
        aiTask = task;
        task.setOnRunning(event -> setAiBusy(true, I18n.get("asciiArt.ai.generating")));
        task.setOnSucceeded(event -> {
            aiTask = null;
            String art = task.getValue();
            if (art == null || art.isBlank()) {
                setAiBusy(false, I18n.get("asciiArt.ai.empty"));
                return;
            }
            aiOutputArea.setText(art);
            lastAiSubject = subject;
            setAiBusy(false, "");
        });
        task.setOnFailed(event -> {
            aiTask = null;
            Throwable error = task.getException();
            String detail = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage().strip()
                : null;
            setAiBusy(false, detail != null
                ? I18n.get("asciiArt.ai.failed", detail)
                : I18n.get("asciiArt.ai.failed", I18n.get("ai.result.error")));
        });
        task.setOnCancelled(event -> {
            aiTask = null;
            setAiBusy(false, "");
        });
        Thread thread = new Thread(task, "ascii-art-ai");
        thread.setDaemon(true);
        thread.start();
    }

    /** Resolves the profile and service for this run and asks the model to draw the subject. */
    private String generateArt(String subject, String profileId, int attempt) throws Exception {
        AiProfile profile = ownerWindow.resolveAiProfileForAction(null, AiAction.GENERATE_ASCII_ART, profileId);
        if (profile == null) {
            throw new IllegalStateException(I18n.get("ai.error.notConfigured"));
        }
        AiService service = ownerWindow.createAiServiceForProfile(profile);
        if (service == null) {
            throw new IllegalStateException(I18n.get("ai.error.notConfigured"));
        }
        return AsciiArtSupport.generateAsciiArt(
            service,
            subject,
            null,
            LanguageManager.getInstance().getCurrentLanguageCode(),
            attempt,
            (request, result) -> ownerWindow.recordAiUsageForProfile(profile, request, result));
    }

    private void cancelAiTask() {
        Task<String> running = aiTask;
        aiTask = null;
        if (running != null && running.isRunning()) {
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
            WindowGeometry geo = settings.getAsciiArtDialogGeometry();
            if (geo != null && geo.getWidth() > 100 && geo.getHeight() > 100) {
                getDialogPane().setPrefWidth(geo.getWidth());
                getDialogPane().setPrefHeight(geo.getHeight());
                setOnShowing(e -> {
                    javafx.stage.Window window = getDialogPane().getScene().getWindow();
                    if (window instanceof Stage s) {
                        s.setX(geo.getX());
                        s.setY(geo.getY());
                        s.setWidth(geo.getWidth());
                        s.setHeight(geo.getHeight());
                    }
                });
            }
        } catch (Exception ignored) { /* use default size/position/zoom */ }
    }

    private void saveState() {
        cancelAiTask();
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (gsm == null || gsm.getSettings() == null) return;
            javafx.stage.Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                gsm.getSettings().setAsciiArtDialogGeometry(new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
            }
            gsm.getSettings().setAsciiArtPreviewFontSize(previewFontSize);
            gsm.save();
        } catch (Exception ignored) { /* skip save on error */ }
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

    /** The preview of the tab that is currently open. */
    private TextArea activePreviewArea() {
        return tabPane.getSelectionModel().getSelectedIndex() == 1 ? aiOutputArea : outputArea;
    }

    private void copyToClipboard() {
        String text = activePreviewArea().getText();
        if (text == null || text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
