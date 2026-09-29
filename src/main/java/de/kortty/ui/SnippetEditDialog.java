package de.kortty.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.KorTTYApplication;
import de.kortty.core.AiAction;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.CodeTextLanguageDetector;
import de.kortty.core.AiRequest;
import de.kortty.core.AiSkillRelevanceSelector;
import de.kortty.core.CodeFormatterService;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetEditorProfileSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAiTextSupport;
import de.kortty.core.SnippetCompletionShortcut;
import de.kortty.core.SnippetCompletionSupport;
import de.kortty.core.SnippetLinter;
import de.kortty.core.SnippetMarkupPreviewRenderer;
import de.kortty.core.MermaidRenderService;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.ScriptLanguageMixSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.SnippetOneLiner;
import de.kortty.model.AiSkill;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.SnippetDiagram;
import de.kortty.model.SnippetDiagramType;
import de.kortty.model.SnippetEditorProfile;
import de.kortty.model.SnippetHistoryEntry;
import de.kortty.model.WindowGeometry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.IndexRange;
import javafx.scene.Node;
import javafx.event.EventHandler;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog for creating or editing a code snippet.
 * Provides form fields for name, language, category, tags, and
 * a syntax-highlighted content editor with placeholder help.
 */
public class SnippetEditDialog extends ThemeAwareDialog<Snippet> implements HostedCloseGuard {

    private static final Logger logger = LoggerFactory.getLogger(SnippetEditDialog.class);

    private final TextField nameField;
    private final ComboBox<String> languageCombo;
    private final ComboBox<String> categoryCombo;
    private final TextField tagsField;
    private final TextArea descriptionArea;
    private final HBox metadataHintBox;
    private final ProgressIndicator metadataProgressIndicator;
    private final Label metadataHintLabel;
    private final HBox snippetAiHintBox;
    private final ProgressIndicator snippetAiProgressIndicator;
    private final Label snippetAiHintLabel;
    private final Button cancelSnippetAiActionButton;
    private final Label snippetAiElapsedLabel;
    private final Button retrySnippetAiActionButton;
    private final Button dismissSnippetAiRetryButton;
    private final javafx.animation.Timeline snippetAiElapsedTicker;
    private long snippetAiStartedNanos;
    /** How to repeat each running AI task (by identity); a task without an entry offers no Retry. */
    private final Map<Task<?>, AiRetry> aiRetries = new java.util.IdentityHashMap<>();
    /** The Retry the hint bar offers after a stop, a failure or a timeout; {@code null} = none. */
    private AiRetry offeredAiRetry;
    private String offeredAiRetryText;
    private final MonacoEditorPane contentArea;
    private final SnippetColumnRuler columnRuler;
    private final ToggleButton markupPreviewToggleButton;
    // Created on the first preview toggle: a WebView is a native WebKit page, too expensive to
    // instantiate on every editor open for a feature most sessions never switch on.
    private WebView markupPreviewView;
    private final StackPane contentStack;
    private final TextArea aiAdditionalInstructionsArea;
    private ComboBox<AiLanguageSupport.LanguageOption> aiCodeTextLanguageCombo;
    private CheckBox rememberAiCodeTextLanguageCheckBox;
    private HBox aiCodeTextLanguageRow;
    private volatile String aiCodeTextLanguageCode;
    private MenuButton aiSkillsMenuButton;
    private HBox aiSkillsRow;
    private final Set<String> selectedAiSkillIds = new LinkedHashSet<>();
    private boolean aiSkillsUserEdited;
    private final VBox aiAdditionalInstructionsBox;
    private final CheckBox wordWrapCheckBox;
    private final CheckBox lineNumbersCheckBox;
    private final Button generateMetadataButton;
    private final Button formatBtn;
    private final Button lintBtn;
    private final Button correctDescriptionButton;
    private final Button toggleLastAiChangeButton;
    private final MenuButton aiTextMenu;
    private final MenuItem correctSelectionTextItem;
    private final MenuItem translateSelectionTextItem;
    private final MenuItem describeSnippetItem;
    private final MenuButton editMenu;
    private final MenuItem undoItem;
    private final MenuButton aiCodeMenu;
    private final MenuItem completeCodeItem;
    private final CheckMenuItem autoCompleteItem;
    private final MenuItem reviewCodeItem;
    /** "Full code analysis with profile": one entry per AI profile, starting the analysis at once. */
    private final Menu reviewCodeWithProfileMenu;
    private final MenuItem improveReadabilityItem;
    private final MenuItem improveRobustnessItem;
    private final MenuItem improvePerformanceItem;
    private final MenuItem improveCommentsItem;
    private MenuItem improveCommentsContextItem;
    private final MenuItem improveCustomItem;
    private MenuItem migrateLanguageItem;
    private MenuItem migrateLanguageContextItem;
    private final MenuItem securityCheckItem;
    private final MenuItem diagramItem;
    private final MenuButton oneLinerMenu;
    private final Label fontSizeLabel;
    private final MenuButton editorProfileMenu;
    private final MenuButton backgroundBrightnessMenu;
    private final Label backgroundBrightnessValueLabel;
    private final Label statusLabel;
    private final Button saveButton;
    private Button okButton;
    private final Snippet existingSnippet;
    /**
     * Set when the editor's pane is embedded in the snippet workspace (never shown as a window):
     * no dialog buttons, no geometry, a close guard that saves before unmounting.
     */
    private final SnippetEditorEmbedding embedding;
    /** Stable id of a never-saved snippet, so hosts can key the editor before the first save. */
    private final String draftSnippetId;
    private final ReadOnlyBooleanWrapper unsavedChanges = new ReadOnlyBooleanWrapper(this, "unsavedChanges");
    private final ReadOnlyBooleanWrapper savable = new ReadOnlyBooleanWrapper(this, "savable");
    private final ReadOnlyBooleanWrapper aiBusy = new ReadOnlyBooleanWrapper(this, "aiBusy");
    private boolean hostedAttachHandled;
    /**
     * This editor's entry in {@link SnippetEditorRegistry} while it is open as a standalone editor
     * ({@link #showNonBlocking}); {@code null} for the workspace's embedded editors, whose tab is
     * their entry.
     */
    private StandaloneRegistration standaloneRegistration;
    /** The diagram window opened from this editor; closed with the editor so it never outlives it. */
    private SnippetDiagramDialog openDiagramDialog;
    /** Test seam: replaces the host-close unsaved-changes prompt (smokes cannot answer an Alert). */
    private static Function<SnippetEditDialog, UnsavedContentChoice> hostUnsavedPrompter;
    private final ExternalFileActionConfig externalFileActionConfig;
    private final boolean saveAsNewSnippetEnabled;
    private Button overwriteFileButton;
    private Button saveFileAsButton;
    private Button saveAsSnippetButton;
    private Button saveAsNewSnippetButton;
    private EditorSettingsHelper.Settings editorSettings;
    private SnippetEditorProfile editorProfile;
    private final AiAssist aiAssist;
    private Color backgroundBrightnessBaseColor;
    private Task<SuggestedSnippetMetadata> metadataTask;
    private Task<String> descriptionCorrectionTask;
    private Task<?> snippetAiActionTask;
    /**
     * The integrated, persisted Full-code analysis of this editor: the side panel, the staged apply
     * with its progress, and the change review that replaces the editor area.
     */
    private SnippetAnalysisController analysisController;
    /** Crash protection for unsaved edits; {@code null} for file editors and admin-managed snippets. */
    private SnippetDraftAutosave draftAutosave;
    private final ToggleButton analysisToggleButton;
    private final MenuItem analysisPanelItem;
    /**
     * The dialog content: the editor area, and while it is shown, a drag divider plus the analysis
     * panel beside it. Not a SplitPane: the editor area (with its Monaco page) never changes parent
     * when the panel comes and goes, and every node stays a real child. The panel keeps its width;
     * the editor area is the one that flexes.
     */
    private final SnippetEditorWorkbench analysisWorkbench;
    private final Region analysisDivider;
    private final StackPane editorAreaStack;
    private final VBox editorFormLayout;
    /** The change review currently shown instead of the editor form, or {@code null}. */
    private SnippetAiDiffPane editorAreaOverlay;
    /**
     * Reviews that arrived while another one held the editor area, shown in order once it is
     * decided. A result is never allowed to silently replace the review the user is looking at.
     */
    private final ArrayDeque<SnippetAiDiffPane> waitingEditorAreaPanes = new ArrayDeque<>();
    /**
     * The ad-hoc AI change (improve, migrate, assistant, security fix, format) waiting for Accept or
     * Reject in the editor area, or {@code null}. New AI actions stay disabled while it is open.
     */
    private SnippetAiDiffPane aiChangeReviewPane;
    /** Re-checks {@link #aiChangeReviewPane} against the current content whenever it comes on screen. */
    private Runnable aiChangeReviewGuard;
    /**
     * The non-modal result windows of this editor (security report, description, alternatives, AI
     * syntax check, editor profile), one per kind. They call back into the editor, so they are
     * closed with it.
     */
    private final Map<Class<?>, Dialog<?>> childWindows = new HashMap<>();
    private boolean editorClosed;
    private boolean widenedForAnalysisPanel;
    private boolean rememberCodeTextLanguageAnswer = true;
    private boolean programmaticNameUpdate;
    private boolean programmaticLanguageUpdate;
    private boolean programmaticAiTextLanguageUpdate;
    private boolean programmaticDescriptionUpdate;
    private boolean programmaticContentUpdate;
    private boolean nameUserEdited;
    private boolean languageUserEdited;
    private boolean aiTextLanguageUserEdited;
    private boolean descriptionUserEdited;
    private LastAiChangeSnapshot lastAiChangeSnapshot;
    private boolean lastAiChangeShowingModified = true;
    private String initialContentSnapshot = "";
    private FormSnapshot initialFormSnapshot;
    private boolean allowCloseWithoutUnsavedPrompt;
    private boolean externalFileActionRunning;
    private Consumer<Snippet> liveSaveHandler;
    private Snippet liveSavedSnippet;
    private boolean finalResultDelivered;
    private final List<SnippetDiagram> diagrams = new ArrayList<>();
    private final PauseTransition autoCompletionDelay = new PauseTransition(Duration.millis(900));
    private final PauseTransition markupPreviewRefreshDelay = new PauseTransition(Duration.millis(180));
    private String lastAutoCompletionKey;
    // Acknowledged once per application run (JVM-wide, deliberately not persisted): the ghost-text
    // notice is about what "Auto AI Complete" sends, not about one particular editor window.
    private static boolean autoCompletionWarningAccepted;
    /** AI candidates asked for when the Shift+TAB / Ctrl+Space / menu list opens. */
    private static final int LIST_AI_CANDIDATES = 5;
    /** AI candidates asked for after a typing pause (ghost text; Alt+] / Alt+[ cycle them). */
    private static final int GHOST_AI_CANDIDATES = 3;
    private static final int LOCAL_MAX_ITEMS = SnippetCompletionSupport.DEFAULT_MAX_ITEMS;
    /** A completion request is abandoned after this long, whatever the profile's own timeout allows. */
    private static final int COMPLETION_TIMEOUT_SECONDS = 30;
    private final PauseTransition completionTimeout =
        new PauseTransition(Duration.seconds(COMPLETION_TIMEOUT_SECONDS));
    private final Map<SnippetCompletionSupport.CandidateKind, String> completionKindLabels = completionKindLabels();
    private Task<List<SnippetAiResponseSupport.CompletionSuggestion>> completionTask;
    /** The request id whose AI result is still wanted; results for any other id are dropped. */
    private long activeCompletionRequestId = -1;
    /** The open suggest list's request id, or -1; the ghost timer stays quiet while a list is open. */
    private long listSessionId = -1;
    /** Ghost requests take ids far above anything the page's own counter reaches. */
    private long nextGhostRequestId = 1L << 40;
    /** The editor text before the latest change; an accepted completion is diffed against it. */
    private String previousEditorText = "";
    private String lastCompletionStatus;

    // History slider fields
    private Slider historySlider;
    private Label historyLabel;
    private List<SnippetHistoryEntry> contentHistory = new ArrayList<>();
    private int currentHistoryIndex = -1;
    private boolean sliderActive;
    private boolean updatingHistorySlider;
    // Telemetry: adoption flags — count content-history usage once per dialog instance, not per keystroke.
    private boolean telemetryUndoTracked;
    private boolean telemetryHistorySliderTracked;
    private String pendingHistoryContent = ""; // content pending for history (debounced)
    private final PauseTransition historyDebounce = new PauseTransition(Duration.millis(500));
    private String lastTrackedContent = ""; // last content that was added to history

    // Syntax highlight style constants (reused from FileEditorTab)
    private static final String STYLE_COMMENT = "-fx-fill: #888888; -fx-font-style: italic;";
    private static final String STYLE_STRING = "-fx-fill: #008800;";
    private static final String STYLE_NUMBER = "-fx-fill: #0066cc;";
    private static final String STYLE_BOOLEAN = "-fx-fill: #cc00cc; -fx-font-weight: bold;";
    private static final String STYLE_KEY = "-fx-fill: #cc0000; -fx-font-weight: bold;";
    private static final String STYLE_KEYWORD = "-fx-fill: #0000cc; -fx-font-weight: bold;";
    private static final String STYLE_SECTION = "-fx-fill: #9900cc; -fx-font-weight: bold;";
    private static final String STYLE_VARIABLE = "-fx-fill: #cc6600;";
    private static final String STYLE_BRACE = "-fx-fill: #cc6600; -fx-font-weight: bold;";
    private static final String STYLE_PLAIN = "-fx-fill: #d4d4d4;";
    private static final String SNIPPET_AI_HINT_ACTIVE_STYLE = "-fx-background-color: rgba(0,102,204,0.30);"
        + " -fx-background-radius: 8;"
        + " -fx-border-color: #4da3ff;"
        + " -fx-border-width: 1;"
        + " -fx-border-radius: 8;"
        + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.30), 8, 0, 0, 2);";
    /** A stopped or failed action waiting for Retry: amber, like the analysis panel's warnings. */
    private static final String SNIPPET_AI_HINT_OUTCOME_STYLE = "-fx-background-color: rgba(245,158,11,0.22);"
        + " -fx-background-radius: 8;"
        + " -fx-border-color: rgba(245,158,11,0.70);"
        + " -fx-border-width: 1;"
        + " -fx-border-radius: 8;";
    private static final String SNIPPET_AI_HINT_IDLE_STYLE = "-fx-background-color: transparent;"
        + " -fx-background-radius: 8;"
        + " -fx-border-color: transparent;"
        + " -fx-border-radius: 8;";
    private static final String SNIPPET_AI_HINT_TEXT_STYLE =
        "-fx-font-size: 1.0769em; -fx-font-weight: bold; -fx-text-fill: #f4f8ff;";
    private static final String METADATA_HINT_TEXT_STYLE =
        "-fx-font-size: 0.9231em; -fx-font-weight: bold; -fx-text-fill: #d7dde8;";
    private static final String STATUS_LABEL_STYLE = "-fx-font-size: 1em;"
        + " -fx-font-weight: bold;"
        + " -fx-text-fill: #d7dde8;"
        + " -fx-background-color: rgba(255,255,255,0.06);"
        + " -fx-border-color: rgba(255,255,255,0.16);"
        + " -fx-border-width: 1 0 0 0;"
        + " -fx-padding: 6 10 6 10;";
    private static final int MIN_EDITOR_FONT_SIZE = 8;
    private static final int MAX_EDITOR_FONT_SIZE = 72;
    private static final int EDITOR_FONT_ZOOM_STEP = 1;
    private static final int MAX_AUTO_SELECTED_SNIPPET_SKILLS = 2;
    private static final String AI_ACTION_PREFIX = "\u2728 ";
    private static final KeyCombination UNDO_SHORTCUT =
        new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    
    private static final List<String> LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "typescript", "groovy",
        "powershell", "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "asciidoctor", "dockerfile"
    );

    private static String aiActionLabel(String key) {
        return AI_ACTION_PREFIX + I18n.get(key);
    }

    @FunctionalInterface
    public interface ExternalFileAction {
        boolean run(Snippet draft) throws Exception;
    }

    public record ExternalFileActionConfig(
        String sourceLabel,
        String overwriteLabel,
        String saveAsLabel,
        String saveAsSnippetLabel,
        String overwriteSuccessMessage,
        String saveAsSuccessMessage,
        String saveAsSnippetSuccessMessage,
        ExternalFileAction overwriteAction,
        ExternalFileAction saveAsAction,
        ExternalFileAction saveAsSnippetAction) {
    }

    @FunctionalInterface
    public interface SuggestedMetadataProvider {
        SuggestedSnippetMetadata generate(String content, String language, String responseLanguageCode) throws Exception;
    }

    @FunctionalInterface
    public interface DescriptionCorrectionProvider {
        String correct(String content, String language, String description, String responseLanguageCode) throws Exception;
    }

    @FunctionalInterface
    public interface SelectionTextTransformProvider {
        String transform(SelectionTextTransformRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface SnippetDescriptionProvider {
        String describe(SnippetDescriptionRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface AlternativeSolutionsProvider {
        List<SnippetAiResponseSupport.AlternativeSolution> generate(AlternativeSolutionsRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CompletionProvider {
        /** Completion candidates at the request's caret, best first; empty when the model had nothing usable. */
        List<SnippetAiResponseSupport.CompletionSuggestion> complete(CompletionRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeReviewProvider {
        List<SnippetAiResponseSupport.CodeReviewFinding> review(CodeReviewRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeImprovementProvider {
        SnippetAiResponseSupport.CodeImprovement improve(CodeImprovementRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface LanguageMigrationProvider {
        SnippetAiResponseSupport.LanguageMigration migrate(LanguageMigrationRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeAssistantProvider {
        SnippetAiResponseSupport.CodeImprovement assist(CodeAssistantRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface SecurityReportProvider {
        List<SnippetAiResponseSupport.SecurityFinding> review(SecurityReviewRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface SecurityFixProvider {
        SnippetAiResponseSupport.SnippetSecurityFix applyFixes(SecurityFixRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeAnalysisProvider {
        SnippetAiResponseSupport.ScriptAnalysis analyze(CodeAnalysisRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface ImprovementFixProvider {
        SnippetAiResponseSupport.SnippetSecurityFix applyFixes(ImprovementApplyRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface OneLinerProvider {
        SnippetAiResponseSupport.OneLinerSuggestion generate(OneLinerRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface DiagramProvider {
        SnippetAiResponseSupport.MermaidDiagram generate(DiagramRequest request) throws Exception;
    }

    public record SelectionTextTransformRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        int selectionStart,
        int selectionEnd,
        String fallbackLanguageCode,
        String targetLanguageCode,
        String additionalInstructions) {
    }

    public record SnippetDescriptionRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        boolean wholeSnippet,
        String fallbackLanguageCode,
        String additionalInstructions,
        String aiProfileId) {
    }

    public record AlternativeSolutionsRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        boolean wholeSnippet,
        String fallbackLanguageCode,
        String additionalInstructions,
        int maxSolutions,
        String aiProfileId) {
    }

    /**
     * @param maxCandidates how many candidates to ask for (at least one), best first
     * @param localContext an optional paragraph for the prompt describing the cursor context and
     *                     the symbols known in the snippet; blank when the editor has none
     */
    public record CompletionRequest(
        String fullContent,
        String snippetLanguage,
        int cursorOffset,
        String fallbackLanguageCode,
        String additionalInstructions,
        int maxCandidates,
        String localContext) {
    }

    public record CodeReviewRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        boolean wholeSnippet,
        String fallbackLanguageCode,
        String reviewTheme,
        String additionalInstructions,
        String aiProfileId) {
    }

    public record CodeImprovementRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        String fallbackLanguageCode,
        String improvementTheme,
        String additionalInstructions,
        boolean allowPlainTextFallback,
        String aiProfileId) {

        public CodeImprovementRequest(
            String fullContent,
            String snippetLanguage,
            String selectedText,
            String fallbackLanguageCode,
            String improvementTheme,
            String additionalInstructions) {

            this(
                fullContent,
                snippetLanguage,
                selectedText,
                fallbackLanguageCode,
                improvementTheme,
                additionalInstructions,
                false,
                null);
        }

        public CodeImprovementRequest(
            String fullContent,
            String snippetLanguage,
            String selectedText,
            String fallbackLanguageCode,
            String improvementTheme,
            String additionalInstructions,
            boolean allowPlainTextFallback) {

            this(
                fullContent,
                snippetLanguage,
                selectedText,
                fallbackLanguageCode,
                improvementTheme,
                additionalInstructions,
                allowPlainTextFallback,
                null);
        }
    }

    /**
     * @param plan what was detected plus what the user chose; the two travel together so they
     *             cannot drift apart on the way to the model.
     */
    public record LanguageMigrationRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        SnippetAiWorkflowSupport.MigrationPlan plan,
        String additionalInstructions,
        String aiProfileId) {
    }

    public record CodeAssistantRequest(
        String fullContent,
        String snippetLanguage,
        int cursorOffset,
        int cursorLine,
        int cursorColumn,
        String fallbackLanguageCode,
        String userInstruction,
        String additionalInstructions,
        boolean includeAiSkills,
        String aiProfileId) {
    }

    public record SecurityReviewRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    /** @param migration optional language unification run before the fixes; may be null or a no-op. */
    public record SecurityFixRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings,
        String additionalInstructions,
        SnippetAiWorkflowSupport.MigrationPlan migration) {

        public SecurityFixRequest(
            String fullContent,
            String snippetLanguage,
            String fallbackLanguageCode,
            List<SnippetAiResponseSupport.SecurityFinding> selectedFindings,
            String additionalInstructions) {
            this(fullContent, snippetLanguage, fallbackLanguageCode, selectedFindings,
                additionalInstructions, null);
        }
    }

    /**
     * Told which AI profile and model actually served a request, and the token usage accumulated so
     * far. Called from the AI worker thread: once when the profile is resolved, then after every AI
     * call. Must not throw (failures are logged and ignored).
     */
    @FunctionalInterface
    public interface AiProvenanceListener {
        void onProvenance(de.kortty.core.SnippetAnalysisRecord.Provenance provenance);
    }

    /** @param provenanceListener optional; told the resolved profile/model and the usage */
    public record CodeAnalysisRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions,
        String aiProfileId,
        AiProvenanceListener provenanceListener) {

        public CodeAnalysisRequest(
            String fullContent,
            String snippetLanguage,
            String fallbackLanguageCode,
            String additionalInstructions,
            String aiProfileId) {
            this(fullContent, snippetLanguage, fallbackLanguageCode, additionalInstructions, aiProfileId, null);
        }
    }

    public record ImprovementApplyRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.ScriptImprovement> improvements,
        List<SnippetAiResponseSupport.ScriptDependency> dependencies,
        String additionalInstructions,
        String classicHardeningInstructions,
        String inputHardeningInstructions,
        SnippetAiWorkflowSupport.ImprovementApplyProgressListener progressListener,
        SnippetAiWorkflowSupport.ImprovementApplyCheckpointListener checkpointListener,
        SnippetAiWorkflowSupport.ImprovementApplyCheckpoint resumeFrom,
        String aiProfileId,
        SnippetAiWorkflowSupport.MigrationPlan migration,
        AiProvenanceListener provenanceListener) {

        public ImprovementApplyRequest(
            String fullContent,
            String snippetLanguage,
            String fallbackLanguageCode,
            List<SnippetAiResponseSupport.ScriptImprovement> improvements,
            List<SnippetAiResponseSupport.ScriptDependency> dependencies,
            String additionalInstructions,
            String classicHardeningInstructions,
            String inputHardeningInstructions,
            SnippetAiWorkflowSupport.ImprovementApplyProgressListener progressListener,
            SnippetAiWorkflowSupport.ImprovementApplyCheckpointListener checkpointListener,
            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint resumeFrom,
            String aiProfileId,
            SnippetAiWorkflowSupport.MigrationPlan migration) {
            this(fullContent, snippetLanguage, fallbackLanguageCode, improvements, dependencies,
                additionalInstructions, classicHardeningInstructions, inputHardeningInstructions,
                progressListener, checkpointListener, resumeFrom, aiProfileId, migration, null);
        }

        /** Compatibility view used by callers that only need to inspect the complete selected contract. */
        public String mandatoryHardeningInstructions() {
            return java.util.stream.Stream.of(classicHardeningInstructions, inputHardeningInstructions)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        }
    }

    public record OneLinerRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    /**
     * @param scopedContent the selected part of the snippet to diagram, or {@code null} for the
     *                      whole snippet; line numbers in the generated diagram are relative to it
     * @param scopeStartLine 1-based first line of the selection in the full snippet, 0 when unscoped
     */
    public record DiagramRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions,
        String aiProfileId,
        de.kortty.model.SnippetDiagramType diagramType,
        String scopedContent,
        int scopeStartLine,
        int scopeEndLine) {

        public DiagramRequest(
                String fullContent,
                String snippetLanguage,
                String fallbackLanguageCode,
                String additionalInstructions) {
            this(fullContent, snippetLanguage, fallbackLanguageCode, additionalInstructions, null);
        }

        public DiagramRequest(
                String fullContent,
                String snippetLanguage,
                String fallbackLanguageCode,
                String additionalInstructions,
                String aiProfileId) {
            this(fullContent, snippetLanguage, fallbackLanguageCode, additionalInstructions, aiProfileId,
                de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, null, 0, 0);
        }

        /** The content the diagram is generated from: the selection when scoped, else the snippet. */
        public String generationContent() {
            return scopedContent != null && !scopedContent.isBlank() ? scopedContent : fullContent;
        }

        public boolean hasScope() {
            return scopedContent != null && !scopedContent.isBlank() && scopeStartLine > 0;
        }
    }

    /** @param textLanguage ISO 639-1 code of the natural language used in the snippet's own text, or null. */
    public record SuggestedSnippetMetadata(
        String fileName, String description, String language, String textLanguage) {
    }

    public record AiAssist(
        SuggestedMetadataProvider metadataProvider,
        DescriptionCorrectionProvider descriptionCorrectionProvider,
        SelectionTextTransformProvider selectionCorrectionProvider,
        SelectionTextTransformProvider selectionTranslationProvider,
        SnippetDescriptionProvider snippetDescriptionProvider,
        AlternativeSolutionsProvider alternativeSolutionsProvider,
        CompletionProvider completionProvider,
        CodeReviewProvider codeReviewProvider,
        CodeImprovementProvider codeImprovementProvider,
        LanguageMigrationProvider languageMigrationProvider,
        CodeAssistantProvider codeAssistantProvider,
        SecurityReportProvider securityReportProvider,
        SecurityFixProvider securityFixProvider,
        OneLinerProvider oneLinerProvider,
        DiagramProvider diagramProvider,
        CodeAnalysisProvider codeAnalysisProvider,
        ImprovementFixProvider improvementFixProvider,
        boolean profileSwitchingSupported,
        SnippetAiRuntimeOptions runtimeOptions) {
    }

    private record LastAiChangeSnapshot(
        String actionLabel,
        String beforeText,
        String afterText,
        int beforeAnchor,
        int beforeCaret,
        int afterAnchor,
        int afterCaret) {
    }

    private record SelectionTextTransformTarget(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        int selectionStart,
        int selectionEnd) {
    }

    private record CodeAssistantPrompt(String instruction, boolean includeAiSkills) {
    }

    private record CursorLocation(int offset, int line, int column) {
    }

    /**
     * @param outputLimitReached the model used its whole completion budget (typically on hidden
     *                           reasoning) and returned no complete diagram
     */
    /**
     * {@code fallbackReason} is set when {@code diagram} is the deterministic local fallback and
     * names why the AI diagram was not used; {@code null} for a genuine AI diagram or a failure.
     */
    private record DiagramGenerationResult(
        SnippetAiResponseSupport.MermaidDiagram diagram,
        MermaidRenderService.SyntaxCheckResult syntaxCheck,
        MermaidRenderService.RenderResult renderCheck,
        boolean outputLimitReached,
        String fallbackReason) {

        private DiagramGenerationResult(
            SnippetAiResponseSupport.MermaidDiagram diagram,
            MermaidRenderService.SyntaxCheckResult syntaxCheck,
            MermaidRenderService.RenderResult renderCheck,
            boolean outputLimitReached) {

            this(diagram, syntaxCheck, renderCheck, outputLimitReached, null);
        }
    }

    private record FormSnapshot(
        String name,
        String language,
        String category,
        String tags,
        String description,
        String content,
        String diagrams) {
    }

    private enum AiFormatScope {
        SELECTION,
        FULL_CONTENT
    }
    
    /**
     * Creates a new snippet edit dialog.
     *
     * @param snippet            the snippet to edit, or null for creating a new one
     * @param existingCategories list of existing category names
     */
    public SnippetEditDialog(Snippet snippet, List<String> existingCategories) {
        this(snippet, existingCategories, null);
    }

    public SnippetEditDialog(Snippet snippet, List<String> existingCategories, AiAssist aiAssist) {
        this(snippet, existingCategories, aiAssist, (ExternalFileActionConfig) null);
    }

    public SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        boolean saveAsNewSnippetEnabled) {

        this(snippet, existingCategories, aiAssist, null, saveAsNewSnippetEnabled);
    }

    public SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        ExternalFileActionConfig externalFileActionConfig) {

        this(snippet, existingCategories, aiAssist, externalFileActionConfig, false);
    }

    /**
     * Creates an editor whose pane the snippet workspace embeds in an inner tab (never shown as a
     * window). {@code snippet} may be {@code null} for a new snippet, keyed by {@link #snippetId()}.
     */
    SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        SnippetEditorEmbedding embedding) {

        this(snippet, existingCategories, aiAssist, null, snippet != null,
            java.util.Objects.requireNonNull(embedding, "embedding"));
    }

    private SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        ExternalFileActionConfig externalFileActionConfig,
        boolean saveAsNewSnippetEnabled) {

        this(snippet, existingCategories, aiAssist, externalFileActionConfig, saveAsNewSnippetEnabled, null);
    }

    private SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        ExternalFileActionConfig externalFileActionConfig,
        boolean saveAsNewSnippetEnabled,
        SnippetEditorEmbedding embedding) {
        this.existingSnippet = snippet;
        this.embedding = embedding;
        this.draftSnippetId = snippet == null ? UUID.randomUUID().toString() : null;
        this.aiAssist = aiAssist;
        this.aiCodeTextLanguageCode = loadConfiguredAiCodeTextLanguageCode();
        this.externalFileActionConfig = externalFileActionConfig;
        this.saveAsNewSnippetEnabled = saveAsNewSnippetEnabled && snippet != null && externalFileActionConfig == null;
        if (snippet != null && snippet.getDiagrams() != null) {
            for (SnippetDiagram diagram : snippet.getDiagrams()) {
                if (diagram != null) {
                    diagrams.add(new SnippetDiagram(diagram));
                }
            }
        }
        EditorSettingsHelper.Settings loaded = EditorSettingsHelper.loadSnippetSettings();
        this.editorProfile = loadActiveSnippetEditorProfile(loaded);
        this.editorSettings = applyProfileToSettings(loaded, editorProfile);
        this.backgroundBrightnessBaseColor = parseEditorBackgroundColor();
        
        setTitle(externalFileActionConfig != null
            ? I18n.get("snippets.fileEdit.title", externalFileActionConfig.sourceLabel())
            : snippet == null ? I18n.get("snippets.addTitle") : I18n.get("snippets.editTitle"));
        setResizable(true);
        initModality(Modality.NONE);
        
        // Form fields
        nameField = new TextField();
        nameField.setPromptText(I18n.get("snippets.name"));
        nameField.setPrefWidth(400);
        
        languageCombo = new ComboBox<>();
        languageCombo.getItems().addAll(LANGUAGES);
        languageCombo.getItems().addAll(loadCustomCodeLanguages());
        languageCombo.setValue("plain");
        languageCombo.setPrefWidth(200);
        languageCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!programmaticLanguageUpdate) {
                languageUserEdited = true;
            }
            updateMarkupPreviewAvailability();
            updateAiActionAvailability();
            updateSaveButtonState();
            autoDetectAiSkills();
        });
        
        categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        if (existingCategories != null) {
            categoryCombo.getItems().addAll(existingCategories);
        }
        categoryCombo.setPromptText(I18n.get("snippets.categoryNew"));
        categoryCombo.setPrefWidth(200);
        categoryCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateSaveButtonState());
        categoryCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) -> updateSaveButtonState());
        
        tagsField = new TextField();
        tagsField.setPromptText(I18n.get("snippets.tagsPrompt"));
        tagsField.setPrefWidth(400);
        tagsField.textProperty().addListener((obs, oldText, newText) -> updateSaveButtonState());

        descriptionArea = new TextArea();
        descriptionArea.setPromptText(I18n.get("snippets.descriptionPrompt"));
        descriptionArea.setWrapText(true);
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setMinHeight(Region.USE_PREF_SIZE);
        descriptionArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!programmaticDescriptionUpdate) {
                descriptionUserEdited = true;
            }
            updateAiActionAvailability();
            updateSaveButtonState();
        });

        aiAdditionalInstructionsArea = new TextArea();
        aiAdditionalInstructionsArea.setPromptText(I18n.get("snippets.ai.instructions.prompt"));
        aiAdditionalInstructionsArea.setWrapText(true);
        aiAdditionalInstructionsArea.setPrefRowCount(3);
        aiAdditionalInstructionsArea.setMinHeight(Region.USE_PREF_SIZE);
        VBox instructionsSubBox = new VBox(6,
            new Label(I18n.get("snippets.ai.instructions.label")),
            aiAdditionalInstructionsArea);
        boolean instructionsEnabled = isAdditionalInstructionsEnabled();
        instructionsSubBox.setVisible(instructionsEnabled);
        instructionsSubBox.setManaged(instructionsEnabled);

        aiCodeTextLanguageRow = buildAiCodeTextLanguageRow();
        boolean languagePickerShow = aiAssist != null;
        aiCodeTextLanguageRow.setVisible(languagePickerShow);
        aiCodeTextLanguageRow.setManaged(languagePickerShow);

        aiSkillsRow = buildAiSkillsRow();
        boolean skillsShow = aiSkillPickerShouldShow();
        aiSkillsRow.setVisible(skillsShow);
        aiSkillsRow.setManaged(skillsShow);

        aiAdditionalInstructionsBox = new VBox(6, aiSkillsRow, instructionsSubBox);
        boolean aiBoxShow = instructionsEnabled || skillsShow;
        aiAdditionalInstructionsBox.setVisible(aiBoxShow);
        aiAdditionalInstructionsBox.setManaged(aiBoxShow);

        metadataProgressIndicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        metadataProgressIndicator.setPrefSize(18, 18);
        metadataProgressIndicator.setMinSize(18, 18);
        metadataProgressIndicator.setMaxSize(18, 18);
        metadataHintLabel = new Label();
        metadataHintLabel.setStyle(METADATA_HINT_TEXT_STYLE);
        metadataHintLabel.setWrapText(true);
        metadataHintBox = new HBox(8, metadataProgressIndicator, metadataHintLabel);
        metadataHintBox.setAlignment(Pos.CENTER_LEFT);
        metadataHintBox.setVisible(false);
        metadataHintBox.setManaged(false);

        snippetAiProgressIndicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        snippetAiProgressIndicator.setPrefSize(24, 24);
        snippetAiProgressIndicator.setMinSize(24, 24);
        snippetAiProgressIndicator.setMaxSize(24, 24);
        snippetAiHintLabel = new Label();
        snippetAiHintLabel.setStyle(SNIPPET_AI_HINT_TEXT_STYLE);
        snippetAiHintLabel.setWrapText(true);
        snippetAiHintLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(snippetAiHintLabel, Priority.ALWAYS);
        cancelSnippetAiActionButton = AiStopRetrySupport.stopButton(this::cancelSnippetAiActionTask);
        cancelSnippetAiActionButton.setId("snippet-ai-stop");
        cancelSnippetAiActionButton.setDisable(true);
        snippetAiElapsedLabel = new Label();
        snippetAiElapsedLabel.setId("snippet-ai-elapsed");
        snippetAiElapsedLabel.setStyle(SNIPPET_AI_HINT_TEXT_STYLE + " -fx-font-weight: normal; -fx-opacity: 0.85;");
        snippetAiElapsedLabel.setMinWidth(Region.USE_PREF_SIZE);
        snippetAiElapsedTicker = AiStopRetrySupport.ticker(this::refreshSnippetAiElapsed);
        retrySnippetAiActionButton = AiStopRetrySupport.retryButton(this::runOfferedAiRetry);
        retrySnippetAiActionButton.setId("snippet-ai-retry");
        dismissSnippetAiRetryButton = new Button("\u2715");
        dismissSnippetAiRetryButton.setId("snippet-ai-retry-dismiss");
        dismissSnippetAiRetryButton.setTooltip(new Tooltip(I18n.get("snippets.ai.retry.dismiss")));
        dismissSnippetAiRetryButton.setOnAction(e -> dismissAiRetry());
        snippetAiHintBox = new HBox(
            12,
            snippetAiProgressIndicator,
            snippetAiHintLabel,
            snippetAiElapsedLabel,
            cancelSnippetAiActionButton,
            retrySnippetAiActionButton,
            dismissSnippetAiRetryButton);
        snippetAiHintBox.setAlignment(Pos.CENTER_LEFT);
        snippetAiHintBox.setPadding(new Insets(10, 12, 10, 12));
        snippetAiHintBox.setMaxWidth(Double.MAX_VALUE);
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_IDLE_STYLE);
        snippetAiHintBox.setManaged(false);
        snippetAiHintBox.setVisible(false);
        snippetAiProgressIndicator.setVisible(false);
        cancelSnippetAiActionButton.setManaged(false);
        cancelSnippetAiActionButton.setVisible(false);
        setShown(snippetAiElapsedLabel, false);
        setShown(retrySnippetAiActionButton, false);
        setShown(dismissSnippetAiRetryButton, false);
        
        // Content area with syntax highlighting – use saved editor settings
        contentArea = MonacoEditorWarmup.acquire();
        // Installing the host before the page boots enables the completion providers with the
        // editor (Shift+TAB / Ctrl+Space list, ghost text); the page calls back on the FX thread.
        contentArea.setCompletionHost(new MonacoEditorPane.CompletionHost() {
            @Override
            public void onCompletionRequested(long requestId, String requestJson) {
                handleCompletionRequested(requestId, requestJson);
            }

            @Override
            public void onCompletionListClosed(long requestId) {
                handleCompletionListClosed(requestId);
            }

            @Override
            public void onCompletionAccepted(String acceptedJson) {
                handleCompletionAccepted(acceptedJson);
            }
        });
        contentArea.setCompletionShortcut(loadCompletionShortcutSetting());
        contentArea.setPrefHeight(350);
        contentArea.setPrefWidth(600);
        EditorSettingsHelper.applyStyle(contentArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(contentArea, () -> editorSettings);
        // Ensure block caret remains visible while typing (caret node may be recreated).
        contentArea.caretPositionProperty().addListener((obs, oldPos, newPos) ->
                EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
        contentArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (UNDO_SHORTCUT.match(event)) {
                undoContentChange();
                event.consume();
                return;
            }
            if (handleEditorZoomShortcut(event)) {
                return;
            }
            KeyCode code = event.getCode();
            if (code == KeyCode.UP || code == KeyCode.DOWN || code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.HOME || code == KeyCode.END
                    || code == KeyCode.PAGE_UP || code == KeyCode.PAGE_DOWN) {
                refreshBlockCaretSoon();
            }
        });

        columnRuler = new SnippetColumnRuler();
        columnRuler.setEditorAppearance(editorSettings);
        columnRuler.setOnLimitColumnChanged(contentArea::setRulerColumn);
        columnRuler.setOnFormatAtLimit(this::runFormatToRulerWidth);
        contentArea.editorContentLeftProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerMetrics());
        contentArea.editorCharacterWidthProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerMetrics());
        contentArea.editorScrollLeftProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerMetrics());
        contentArea.caretVisualXProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerCaret());
        
        // Wrap content area in Monaco editor for scrollbars
        var contentScrollPane = EditorSettingsHelper.createScrollPane(contentArea);
        VBox.setVgrow(contentScrollPane, Priority.ALWAYS);

        markupPreviewToggleButton = new ToggleButton(I18n.get("snippets.preview"));
        markupPreviewToggleButton.setTooltip(new Tooltip(I18n.get("snippets.preview.tooltip")));
        markupPreviewToggleButton.setAccessibleText(I18n.get("snippets.preview.tooltip"));
        markupPreviewToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> handleMarkupPreviewToggle(selected));
        contentScrollPane.visibleProperty().bind(markupPreviewToggleButton.selectedProperty().not());
        contentScrollPane.managedProperty().bind(contentScrollPane.visibleProperty());
        columnRuler.visibleProperty().bind(markupPreviewToggleButton.selectedProperty().not());
        columnRuler.managedProperty().bind(columnRuler.visibleProperty());
        markupPreviewRefreshDelay.setOnFinished(event -> refreshMarkupPreview());
        contentStack = new StackPane(contentScrollPane);
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        
        // Word wrap checkbox – persistent setting
        wordWrapCheckBox = new CheckBox(I18n.get("snippets.wordWrap"));
        boolean savedWordWrap = loadWordWrapSetting();
        wordWrapCheckBox.setSelected(savedWordWrap);
        contentArea.setWrapText(savedWordWrap);
        
        wordWrapCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            contentArea.setWrapText(newVal);
            saveWordWrapSetting(newVal);
        });
        
        lineNumbersCheckBox = new CheckBox(I18n.get("snippets.lineNumbers"));
        boolean savedLineNumbers = loadLineNumbersSetting();
        lineNumbersCheckBox.setSelected(savedLineNumbers);
        EditorSettingsHelper.applyLineNumbers(contentArea, savedLineNumbers, editorSettings);
        lineNumbersCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            EditorSettingsHelper.applyLineNumbers(contentArea, newVal, editorSettings);
            saveLineNumbersSetting(newVal);
        });
        
        // Right-click context menu on content area
        contentArea.setContextMenu(createEditorContextMenu());
        
        // Re-apply highlighting when language changes
        languageCombo.setOnAction(e -> {
            applyHighlighting();
            updateFormatLintButtonState();
            updateOneLinerButtonState();
            updateMarkupPreviewAvailability();
            updateAiActionAvailability();
        });
        
        // Re-apply highlighting on text change
        contentArea.textProperty().addListener((obs, oldText, newText) -> {
            // Stored first: an accepted completion is reported after this change and diffs against it.
            previousEditorText = oldText != null ? oldText : "";
            if (!programmaticContentUpdate) {
                clearLastAiChangeSnapshot();
            }
            applyHighlighting();
            EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
            updateUndoControls();
            updateSaveButtonState();
            updateExternalFileButtonState();
            updateAiActionAvailability();
            updateColumnRulerCaret();
            scheduleMarkupPreviewRefresh();
            scheduleAutoCompletion();
            if (analysisController != null) {
                analysisController.onContentChanged();
            }

            // Track history changes with debounce (only when user is editing, not when slider is active)
            if (!programmaticContentUpdate && !sliderActive) {
                pendingHistoryContent = newText;
                historyDebounce.playFromStart();
            }
        });
        contentArea.selectionProperty().addListener((obs, oldSelection, newSelection) -> updateAiActionAvailability());
        contentArea.caretPositionProperty().addListener((obs, oldValue, newValue) -> {
            updateColumnRulerCaret();
            scheduleAutoCompletion();
        });
        contentArea.caretColumnProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerCaret());
        autoCompletionDelay.setOnFinished(event -> requestGhostCompletion());
        completionTimeout.setOnFinished(event -> handleCompletionTimeout());

        // Setup history debounce timer
        historyDebounce.setOnFinished(event -> {
            flushPendingHistory();
        });

        // Leaving the slider makes the previewed text the active editor content.
        contentArea.setOnMousePressed(event -> {
            if (sliderActive) {
                sliderActive = false;
                updateSaveButtonState();
            }
        });
        
        // Placeholder info label
        Label placeholderInfo = new Label(I18n.get("snippets.placeholderInfo"));
        placeholderInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #888888;");
        placeholderInfo.setWrapText(true);
        
        // Layout
        GridPane formGrid = new GridPane();
        formGrid.setHgap(10);
        formGrid.setVgap(8);
        formGrid.setPadding(new Insets(10));
        
        formGrid.add(new Label(I18n.get("snippets.name") + ":"), 0, 0);
        formGrid.add(nameField, 1, 0);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        
        Button addCodeLanguageButton = new Button("+");
        addCodeLanguageButton.setId("snippet-add-code-language");
        addCodeLanguageButton.setTooltip(new Tooltip(I18n.get("snippets.codeLanguage.add")));
        addCodeLanguageButton.setOnAction(event -> promptForCustomCodeLanguage());

        HBox langCatBox = new HBox(10);
        langCatBox.setAlignment(Pos.CENTER_LEFT);
        langCatBox.getChildren().addAll(
            new Label(I18n.get("snippets.codeLanguage") + ":"), languageCombo, addCodeLanguageButton,
            new Label(I18n.get("snippets.category") + ":"), categoryCombo
        );
        // Text language sits directly below the code language: both describe the snippet, while the
        // skills/instructions box below the toolbar is about the next AI request.
        VBox languageBox = new VBox(6, langCatBox, aiCodeTextLanguageRow);
        formGrid.add(languageBox, 0, 1, 2, 1);
        
        formGrid.add(new Label(I18n.get("snippets.tags") + ":"), 0, 2);
        formGrid.add(tagsField, 1, 2);
        GridPane.setHgrow(tagsField, Priority.ALWAYS);

        generateMetadataButton = new Button(aiActionLabel("snippets.ai.metadata.generate"));
        generateMetadataButton.setTooltip(new Tooltip(I18n.get("snippets.ai.metadata.generate.tooltip")));
        generateMetadataButton.setOnAction(e -> { trackSnippetAiAction("metadata_generate"); beginMetadataGeneration(true); });

        correctDescriptionButton = new Button(aiActionLabel("snippets.description.correct"));
        correctDescriptionButton.setTooltip(new Tooltip(I18n.get("snippets.description.correct.tooltip")));
        correctDescriptionButton.setOnAction(e -> { trackSnippetAiAction("description_correct"); runDescriptionCorrection(); });

        HBox descriptionHeader = new HBox(10,
            new Label(I18n.get("common.description") + ":"),
            generateMetadataButton,
            correctDescriptionButton);
        descriptionHeader.setAlignment(Pos.CENTER_LEFT);
        formGrid.add(descriptionHeader, 0, 3);
        formGrid.add(descriptionArea, 1, 3);
        GridPane.setHgrow(descriptionArea, Priority.ALWAYS);
        formGrid.add(metadataHintBox, 1, 4);
        
        // Content header: label + Format / Lint buttons (with symbols) + Word wrap
        formatBtn = new Button("\u2728 " + I18n.get("editor.format"));
        formatBtn.setTooltip(new Tooltip(I18n.get("editor.format.tooltip", I18n.get("editor.format.tooltip.builtin"))));
        formatBtn.setOnAction(e -> runFormat());
        
        lintBtn = new Button("\u2713 " + I18n.get("editor.lint"));
        lintBtn.setTooltip(new Tooltip(I18n.get("editor.lint.title")));
        lintBtn.setOnAction(e -> runLint());

        undoItem = new MenuItem(I18n.get("editor.context.undo"));
        // An embedded editor shares its scene with the workspace and every other editor tab: a
        // scene-wide accelerator would undo in whichever editor registered last. The Monaco key
        // filter handles Shortcut+Z for the focused editor anyway.
        if (embedding == null) {
            undoItem.setAccelerator(UNDO_SHORTCUT);
        }
        undoItem.setOnAction(e -> undoContentChange());

        // History slider UI
        historyLabel = new Label(I18n.get("snippets.history.label"));
        historyLabel.setStyle("-fx-font-size: 0.8462em; -fx-padding: 2 4 2 4;");
        historySlider = new Slider(0, 1, 0);
        historySlider.setDisable(true);
        historySlider.setPrefWidth(180);
        historySlider.setSnapToTicks(true);
        historySlider.setMajorTickUnit(1);
        historySlider.setMinorTickCount(0);
        historySlider.visibleProperty().bind(historySlider.disableProperty().not());
        historySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!historySlider.isDisabled() && !updatingHistorySlider) {
                int idx = (int) Math.round(newVal.doubleValue());
                navigateToHistoryEntry(idx);
            }
        });
        historySlider.setOnMousePressed(event -> {
            flushPendingHistory();
            sliderActive = true;
        });
        historySlider.setOnMouseReleased(event -> {
            sliderActive = false;
            updateSaveButtonState();
        });
        historySlider.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            // Ensure label is visible when slider has items
            updateHistorySliderState();
        });

        VBox historyBox = new VBox(4);
        historyBox.setStyle("-fx-padding: 4 8 4 8;");
        historyBox.getChildren().addAll(historyLabel, historySlider);

        CustomMenuItem historyMenuItem = new CustomMenuItem(historyBox, false);

        editMenu = new MenuButton(I18n.get("menu.edit"));
        editMenu.getItems().addAll(historyMenuItem, new SeparatorMenuItem(), undoItem);
        editMenu.setOnShowing(e -> {
            updateUndoControls();
            updateHistorySliderState();
        });

        correctSelectionTextItem = new MenuItem(aiActionLabel("snippets.ai.menu.correct"));
        correctSelectionTextItem.setOnAction(e -> { trackSnippetAiAction("text_correct"); runSelectionCorrection(); });
        translateSelectionTextItem = new MenuItem(aiActionLabel("snippets.ai.menu.translate"));
        translateSelectionTextItem.setOnAction(e -> { trackSnippetAiAction("text_translate"); runSelectionTranslation(); });
        describeSnippetItem = new MenuItem(aiActionLabel("snippets.ai.menu.describe"));
        describeSnippetItem.setOnAction(e -> { trackSnippetAiAction("text_describe"); runSnippetDescription(); });
        aiTextMenu = new MenuButton(aiActionLabel("snippets.ai.menu"));
        aiTextMenu.getItems().addAll(correctSelectionTextItem, translateSelectionTextItem, describeSnippetItem);

        completeCodeItem = new MenuItem(aiActionLabel("snippets.ai.code.complete"));
        completeCodeItem.setOnAction(e -> { trackSnippetAiAction("code_complete"); contentArea.triggerCompletionList(); });
        autoCompleteItem = new CheckMenuItem(aiActionLabel("snippets.ai.code.autoComplete"));
        autoCompleteItem.setOnAction(e -> { trackSnippetAiAction("code_autocomplete_toggle"); handleAutoCompletionToggle(); });
        reviewCodeItem = new MenuItem(aiActionLabel("snippets.ai.code.review"));
        reviewCodeItem.setOnAction(e -> { trackSnippetAiAction("code_review"); runCodeReview(); });
        reviewCodeWithProfileMenu = buildReviewWithProfileMenu();
        analysisPanelItem = new MenuItem(I18n.get("snippets.ai.analysis.panel.menu"));
        analysisPanelItem.setId("snippet-analysis-panel-item");
        analysisPanelItem.setOnAction(e -> toggleAnalysisPanel());
        improveReadabilityItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.readability"));
        improveReadabilityItem.setOnAction(e -> { trackSnippetAiAction("code_improve_readability"); runCodeImprovement(I18n.get("snippets.ai.code.improve.readability.theme")); });
        improveRobustnessItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.robustness"));
        improveRobustnessItem.setOnAction(e -> { trackSnippetAiAction("code_improve_robustness"); runImproveRobustness(); });
        improvePerformanceItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.performance"));
        improvePerformanceItem.setOnAction(e -> { trackSnippetAiAction("code_improve_performance"); runCodeImprovement(I18n.get("snippets.ai.code.improve.performance.theme")); });
        improveCommentsItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.comments"));
        improveCommentsItem.setOnAction(e -> { trackSnippetAiAction("code_improve_comments"); runCommentOptimization(); });
        improveCustomItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.custom"));
        improveCustomItem.setOnAction(e -> { trackSnippetAiAction("code_improve_custom"); runCustomCodeImprovement(); });
        migrateLanguageItem = new MenuItem(aiActionLabel("snippets.ai.code.migrate"));
        migrateLanguageItem.setOnAction(e -> { trackSnippetAiAction("code_migrate"); runLanguageMigration(); });
        securityCheckItem = new MenuItem(aiActionLabel("snippets.ai.security.title"));
        securityCheckItem.setOnAction(e -> { trackSnippetAiAction("code_security_check"); runSecurityCheck(); });
        diagramItem = new MenuItem(aiActionLabel("snippets.ai.diagram.menu"));
        diagramItem.setOnAction(e -> { trackSnippetAiAction("code_diagram"); openOrCreateDiagram(); });
        aiCodeMenu = new MenuButton(aiActionLabel("snippets.ai.code.menu"));
        aiCodeMenu.setOnShowing(e -> refreshReviewWithProfileMenu(reviewCodeWithProfileMenu));
        aiCodeMenu.getItems().addAll(
            completeCodeItem,
            autoCompleteItem,
            new SeparatorMenuItem(),
            reviewCodeItem,
            reviewCodeWithProfileMenu,
            analysisPanelItem,
            improveReadabilityItem,
            improveRobustnessItem,
            improvePerformanceItem,
            improveCommentsItem,
            improveCustomItem,
            migrateLanguageItem,
            new SeparatorMenuItem(),
            securityCheckItem,
            diagramItem);

        toggleLastAiChangeButton = new Button("\u21ba");
        toggleLastAiChangeButton.setTooltip(new Tooltip(I18n.get("snippets.ai.toggle.tooltip")));
        toggleLastAiChangeButton.setOnAction(e -> toggleLastAiChange());
        toggleLastAiChangeButton.setDisable(true);

        MenuItem oneLinerCompactItem = new MenuItem(I18n.get("snippets.oneliner.compact"));
        oneLinerCompactItem.setOnAction(e -> {
            Telemetry.track(TelemetryEvents.SNIPPET_ONELINER_USED, Map.of("variant", "compact"));
            runOneLiner(true);
        });
        MenuItem oneLinerEmbeddedItem = new MenuItem(I18n.get("snippets.oneliner.embedded"));
        oneLinerEmbeddedItem.setOnAction(e -> {
            Telemetry.track(TelemetryEvents.SNIPPET_ONELINER_USED, Map.of("variant", "base64"));
            runOneLiner(false);
        });
        oneLinerMenu = new MenuButton("\u2192 " + I18n.get("snippets.oneliner.menu"));
        oneLinerMenu.getItems().addAll(oneLinerCompactItem, oneLinerEmbeddedItem);
        oneLinerMenu.setTooltip(new Tooltip(I18n.get("snippets.oneliner.tooltip")));
        
        updateFormatLintButtonState();
        updateOneLinerButtonState();

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(e -> changeEditorFontSize(-EDITOR_FONT_ZOOM_STEP));

        fontSizeLabel = new Label(formatEditorFontSize());
        fontSizeLabel.setMinWidth(42);
        fontSizeLabel.setAlignment(Pos.CENTER);

        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(e -> changeEditorFontSize(EDITOR_FONT_ZOOM_STEP));

        editorProfileMenu = new MenuButton();
        refreshEditorProfileMenu();

        backgroundBrightnessValueLabel = new Label(formatBackgroundBrightnessValue());
        backgroundBrightnessMenu = createBackgroundBrightnessMenu();

        analysisToggleButton = new ToggleButton(I18n.get("snippets.ai.analysis.panel.toggle"));
        analysisToggleButton.setId("snippet-analysis-toggle");
        analysisToggleButton.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.panel.toggle.tooltip")));
        analysisToggleButton.setOnAction(e -> toggleAnalysisPanel());

        HBox contentHeader = new HBox(10,
                new Label(I18n.get("snippets.content") + ":"),
                editMenu, formatBtn, lintBtn, aiTextMenu, aiCodeMenu, analysisToggleButton,
                toggleLastAiChangeButton, oneLinerMenu,
                new Separator(), zoomOutButton, fontSizeLabel, zoomInButton, editorProfileMenu, backgroundBrightnessMenu,
                new Separator(), markupPreviewToggleButton, wordWrapCheckBox, lineNumbersCheckBox);
        contentHeader.setAlignment(Pos.CENTER_LEFT);
        // Wrap the wide toolbar in a min-width-0 horizontal scroll pane so it cannot force the whole
        // dialog wider than the window. Otherwise the editor's right-edge scrollbar and the action
        // buttons get pushed off-screen when the window is narrowed; this lets the content shrink to
        // the window width (the toolbar scrolls horizontally only when there is not enough room).
        ScrollPane contentHeaderScroll = new ScrollPane(contentHeader);
        contentHeaderScroll.setFitToHeight(true);
        contentHeaderScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentHeaderScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentHeaderScroll.setMinWidth(0);
        contentHeaderScroll.setMaxWidth(Double.MAX_VALUE);
        contentHeaderScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        formGrid.add(contentHeaderScroll, 0, 5, 2, 1);

        formGrid.add(aiAdditionalInstructionsBox, 0, 6, 2, 1);

        updateColumnRulerMetrics();
        updateColumnRulerCaret();

        VBox contentBox = new VBox(0, columnRuler, contentStack, placeholderInfo);
        VBox.setMargin(placeholderInfo, new Insets(5, 0, 0, 0));
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        formGrid.add(contentBox, 0, 7, 2, 1);
        GridPane.setVgrow(contentBox, Priority.ALWAYS);

        statusLabel = new Label();
        statusLabel.setStyle(STATUS_LABEL_STYLE);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setMinHeight(32);
        statusLabel.setWrapText(true);
        statusLabel.setText("");

        VBox.setMargin(snippetAiHintBox, new Insets(10, 10, 0, 10));
        VBox rootLayout = new VBox(0, snippetAiHintBox, formGrid, statusLabel);
        VBox.setVgrow(formGrid, Priority.ALWAYS);
        editorFormLayout = rootLayout;
        // The editor area can be replaced by a change review; the analysis panel sits beside it and
        // is only a child of the workbench while it is shown.
        editorAreaStack = new StackPane(rootLayout);
        editorAreaStack.setMinWidth(0);
        analysisDivider = buildAnalysisDivider();
        analysisWorkbench = new SnippetEditorWorkbench(editorAreaStack, analysisDivider);
        analysisWorkbench.setId("snippet-editor-workbench");
        // Esc stops the running AI request of this editor (form, Monaco or analysis panel). A filter,
        // so it wins over Monaco and the dialog's Cancel — but only while something runs, and never
        // for a change review, which keeps its own Esc handling.
        analysisWorkbench.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !event.isShortcutDown() && !event.isAltDown()
                    && !isInsideChangeReview(event.getTarget()) && stopRunningAiByKeyboard()) {
                event.consume();
            }
        });

        getDialogPane().setContent(analysisWorkbench);
        getDialogPane().setPrefWidth(700);
        getDialogPane().setPrefHeight(640);

        Button validationButton;
        Button cancelButton;
        Button assignedSaveButton;
        if (externalFileActionConfig != null) {
            ButtonType overwriteButtonType = new ButtonType(
                externalFileActionConfig.overwriteLabel(), ButtonBar.ButtonData.APPLY);
            ButtonType saveAsButtonType = new ButtonType(
                externalFileActionConfig.saveAsLabel(), ButtonBar.ButtonData.APPLY);
            ButtonType saveAsSnippetButtonType = new ButtonType(
                externalFileActionConfig.saveAsSnippetLabel(), ButtonBar.ButtonData.APPLY);
            ButtonType closeButtonType = new ButtonType(I18n.get("editor.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
            getDialogPane().getButtonTypes().addAll(
                overwriteButtonType, saveAsButtonType, saveAsSnippetButtonType, closeButtonType);
            overwriteFileButton = (Button) getDialogPane().lookupButton(overwriteButtonType);
            saveFileAsButton = (Button) getDialogPane().lookupButton(saveAsButtonType);
            saveAsSnippetButton = (Button) getDialogPane().lookupButton(saveAsSnippetButtonType);
            configureExternalFileActionButton(
                overwriteFileButton,
                externalFileActionConfig.overwriteAction(),
                externalFileActionConfig.overwriteSuccessMessage(),
                true);
            configureExternalFileActionButton(
                saveFileAsButton,
                externalFileActionConfig.saveAsAction(),
                externalFileActionConfig.saveAsSuccessMessage(),
                true);
            configureExternalFileActionButton(
                saveAsSnippetButton,
                externalFileActionConfig.saveAsSnippetAction(),
                externalFileActionConfig.saveAsSnippetSuccessMessage(),
                false);
            validationButton = null;
            assignedSaveButton = null;
            cancelButton = (Button) getDialogPane().lookupButton(closeButtonType);
        } else if (embedding != null) {
            // The workspace's action bar saves; no dialog buttons means no default button for
            // Enter, no Cancel for Esc and no silent discard.
            validationButton = null;
            assignedSaveButton = null;
            cancelButton = null;
        } else {
            ButtonType saveButtonType = new ButtonType(I18n.get("dialog.save"), ButtonBar.ButtonData.APPLY);
            ButtonType saveAsNewButtonType = new ButtonType(I18n.get("snippets.saveAsNew"), ButtonBar.ButtonData.APPLY);
            if (this.saveAsNewSnippetEnabled) {
                getDialogPane().getButtonTypes().addAll(saveButtonType, saveAsNewButtonType, ButtonType.OK, ButtonType.CANCEL);
            } else {
                getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.OK, ButtonType.CANCEL);
            }

            okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
            assignedSaveButton = (Button) getDialogPane().lookupButton(saveButtonType);
            saveAsNewSnippetButton = this.saveAsNewSnippetEnabled
                ? (Button) getDialogPane().lookupButton(saveAsNewButtonType)
                : null;
            cancelButton = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
            okButton.setDisable(true);
            assignedSaveButton.setVisible(false);
            assignedSaveButton.setManaged(false);
            assignedSaveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                event.consume();
                saveSnippetWithoutClosing();
            });
            if (saveAsNewSnippetButton != null) {
                saveAsNewSnippetButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                    event.consume();
                    if (!isSnippetFormValid()) {
                        updateSaveButtonState();
                        return;
                    }
                    if (!validateUniqueSnippetNameBeforeSave(null)) {
                        updateSaveButtonState();
                        return;
                    }
                    saveGeometry();
                    flushPendingHistory();
                    Snippet copy = buildNewResultSnippet();
                    offerAnalysisCopy(snippetId(), copy);
                    allowCloseWithoutUnsavedPrompt = true;
                    setResult(copy);
                    closeDialogOrHostTab();
                });
            }
            validationButton = okButton;
        }
        saveButton = assignedSaveButton;

        // Cancel button should close directly without prompting
        if (cancelButton != null) {
            cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                allowCloseWithoutUnsavedPrompt = true;
            });
        }

        // Prevent Enter key from triggering default button when content area has focus
        EventHandler<javafx.event.ActionEvent> enterGuard = event -> {
            if (event.getTarget() instanceof Button) {
                Button clickedButton = (Button) event.getTarget();
                Node focusOwner = getDialogPane().getScene() != null
                    ? getDialogPane().getScene().getFocusOwner()
                    : null;
                // If content area or its inner components have focus, don't trigger button
                if (focusOwner != null && isDescendantOf(focusOwner, contentArea)) {
                    event.consume();
                }
                // Same while a change review replaces the editor: Enter there must not fire the
                // dialog's default button (its own buttons keep working).
                SnippetAiDiffPane overlay = editorAreaOverlay;
                if (overlay != null && focusOwner != null && isDescendantOf(focusOwner, overlay)
                        && !isDescendantOf(clickedButton, overlay)) {
                    event.consume();
                }
            }
        };
        getDialogPane().addEventFilter(javafx.event.ActionEvent.ACTION, enterGuard);

        nameField.textProperty().addListener((obs, o, n) -> {
            if (!programmaticNameUpdate) {
                nameUserEdited = true;
            }
            validateForm(validationButton);
            updateSaveButtonState();
            updateExternalFileButtonState();
            updateEditorTitle();
        });
        contentArea.textProperty().addListener((obs, o, n) -> {
            validateForm(validationButton);
            updateOneLinerButtonState();
            updateExternalFileButtonState();
        });
        
        // Pre-fill if editing
        if (snippet != null) {
            programmaticNameUpdate = true;
            try {
                nameField.setText(snippet.getName());
            } finally {
                programmaticNameUpdate = false;
            }
            programmaticLanguageUpdate = true;
            try {
                languageCombo.setValue(SnippetLanguageSupport.detectSnippetLanguage(snippet.getLanguage(), snippet.getContent()));
            } finally {
                programmaticLanguageUpdate = false;
            }
            categoryCombo.setValue(snippet.getCategory());
            tagsField.setText(snippet.getTagsAsString());
            programmaticDescriptionUpdate = true;
            try {
                descriptionArea.setText(snippet.getDescription() != null ? snippet.getDescription() : "");
            } finally {
                programmaticDescriptionUpdate = false;
            }
            programmaticContentUpdate = true;
            try {
                contentArea.replaceText(snippet.getContent() != null ? snippet.getContent() : "");
            } finally {
                programmaticContentUpdate = false;
            }
            previousEditorText = safeContentText();
            applyHighlighting();

            // Initialize history with current content
            String currentContent = snippet.getContent() != null ? snippet.getContent() : "";
            contentHistory.clear();
            if (snippet.getHistory() != null) {
                for (SnippetHistoryEntry entry : snippet.getHistory()) {
                    if (entry != null && entry.getContent() != null) {
                        contentHistory.add(new SnippetHistoryEntry(entry.getContent(), entry.getTimestamp()));
                    }
                }
            }
            if (contentHistory.isEmpty()
                    || !currentContent.equals(contentHistory.get(contentHistory.size() - 1).getContent())) {
                contentHistory.add(new SnippetHistoryEntry(currentContent));
            }
            trimHistoryToLimit();
            currentHistoryIndex = contentHistory.size() - 1;
            lastTrackedContent = currentContent; // Initialize so first change is tracked
            updateHistorySliderState();
        } else {
            // For new snippets, also initialize history
            contentHistory.clear();
            contentHistory.add(new SnippetHistoryEntry(""));
            currentHistoryIndex = 0;
            updateHistorySliderState();
        }
        // Only forget history for new snippets, not when editing existing ones
        if (existingSnippet == null) {
            contentArea.getUndoManager().forgetHistory();
        }
        updateFormatLintButtonState();
        updateOneLinerButtonState();
        updateMarkupPreviewAvailability();
        updateAiActionAvailability();
        initialContentSnapshot = safeContentText();
        initialFormSnapshot = currentFormSnapshot();
        updateUndoControls();
        updateSaveButtonState();
        updateExternalFileButtonState();
        installUnsavedContentCloseGuard(cancelButton);
        
        // Restore saved geometry (an embedded pane has no window of its own)
        if (embedding == null) {
            restoreGeometry();
            enforceMinimumWindowSize();
        }
        installSaveShortcut();

        // Result converter (also saves geometry)
        setResultConverter(buttonType -> {
            saveGeometry();
            if (buttonType == ButtonType.OK) {
                allowCloseWithoutUnsavedPrompt = true;
            }
            return null;
        });

        analysisController = new SnippetAnalysisController(new AnalysisHost(), SnippetAnalysisStore.shared());
        analysisController.attach();
        if (externalFileActionConfig == null && (snippet == null || !snippet.isPolicyManaged())) {
            draftAutosave = new SnippetDraftAutosave(new DraftForm(), de.kortty.core.SnippetDraftStore.shared());
            draftAutosave.checkForDraft();
        }

        setOnHidden(event -> {
            // First: a running apply is recorded as interrupted before cancelAiTasks() cancels it.
            analysisController.dispose();
            if (draftAutosave != null) {
                draftAutosave.dispose();
            }
            editorClosed = true;
            cancelAiTasks();
            closeDiagramDialog();
            closeChildWindows();
            // An ad-hoc change nobody decided is simply dropped with the editor, like the old window.
            SnippetAiDiffPane openReview = aiChangeReviewPane;
            aiChangeReviewPane = null;
            aiChangeReviewGuard = null;
            waitingEditorAreaPanes.clear();
            if (openReview != null) {
                openReview.dispose();
            }
            // Tear down the Monaco WebView (page, JS bridge, boot retries) on close instead of leaking it.
            contentArea.dispose();
            // Same for the markup preview's WebKit engine, if the preview was ever shown.
            if (markupPreviewView != null) {
                markupPreviewView.getEngine().loadContent("");
            }
        });
        if (aiAssist != null
            && aiAssist.metadataProvider() != null
            && contentArea.getText() != null
            && !contentArea.getText().isBlank()
            && (nameField.getText() == null || nameField.getText().isBlank())
            && (descriptionArea.getText() == null || descriptionArea.getText().isBlank())) {
            beginMetadataGeneration(false);
        }
    }

    public void showNonBlocking(Consumer<Snippet> resultHandler) {
        this.liveSaveHandler = resultHandler;
        registerStandalone();
        if (resultHandler != null) {
            addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
                // Deliver the final result at most once. A button whose ACTION filter consumes the event and
                // then calls setResult(...) + close() (our hidden Save / "Save as new" buttons) makes JavaFX
                // fire DIALOG_HIDDEN twice for a single click, so without this guard the caller's save runs
                // twice: for "Save as new" the second addSnippet sees the snippet the first one just added
                // and throws "Snippet name already exists" even though the save actually succeeded.
                if (finalResultDelivered) {
                    return;
                }
                Snippet result = getResult();
                if (result != null) {
                    finalResultDelivered = true;
                    try {
                        resultHandler.accept(result);
                    } catch (RuntimeException e) {
                        showSaveFailure(e);
                    }
                }
            });
        }
        MainWindow tabHost = resolveTabHost();
        if (tabHost != null) {
            tabHost.hostMultiInstanceToolTab(this);
            return;
        }
        show();
    }

    /**
     * Lists this standalone editor in {@link SnippetEditorRegistry}: the close/quit guards then ask
     * about its unsaved edits (also when it has no owner window), and the snippet workspace reveals
     * it instead of opening the same snippet a second time. Released when the editor is hidden.
     */
    private void registerStandalone() {
        if (standaloneRegistration != null || embedding != null) {
            return;
        }
        StandaloneRegistration registration = new StandaloneRegistration();
        standaloneRegistration = registration;
        SnippetEditorRegistry.track(registration);
        claimPersistedSnippetId();
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
            SnippetEditorRegistry.release(registration);
            if (standaloneRegistration == registration) {
                standaloneRegistration = null;
            }
        });
    }

    /**
     * Binds this standalone editor to the snippet it edits, once there is one: a snippet it was
     * opened with, or the one its first save created. File editors edit a file, not a snippet, and
     * only stay tracked. A snippet already open in another editor stays with that one.
     */
    private void claimPersistedSnippetId() {
        StandaloneRegistration registration = standaloneRegistration;
        Snippet persisted = persistedSnippet();
        if (registration == null || externalFileActionConfig != null || persisted == null || persisted.getId() == null) {
            return;
        }
        if (!SnippetEditorRegistry.claim(persisted.getId(), registration)) {
            logger.debug("Snippet {} is already open in another editor; this editor stays unclaimed", persisted.getId());
        }
    }

    /**
     * The main window to host this editor as a tab, or {@code null} to open a normal window.
     * Tab hosting applies when the global "tool windows as tabs" setting is on AND the owner set
     * by the call site (main window, snippet-manager tab, SFTP tab, file browser, …) is a main
     * window's stage. Call sites owned by other windows (e.g. a snippet manager opened as a
     * dialog, the swarm script window) keep the classic editor window.
     */
    private MainWindow resolveTabHost() {
        try {
            var settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings == null || !settings.isOpenToolWindowsAsTabs()) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return MainWindow.findByStage(getOwner());
    }

    private boolean saveSnippetWithoutClosing() {
        if (!isSnippetFormValid()) {
            updateSaveButtonState();
            return false;
        }
        String ignoredSnippetId = existingSnippet != null
            ? existingSnippet.getId()
            : liveSavedSnippet != null ? liveSavedSnippet.getId() : null;
        if (!validateUniqueSnippetNameBeforeSave(ignoredSnippetId)) {
            updateSaveButtonState();
            updateExternalFileButtonState();
            return false;
        }

        flushPendingHistory();
        saveGeometry();

        boolean created = existingSnippet == null && liveSavedSnippet == null;
        Snippet saved = existingSnippet != null
            ? existingSnippet
            : liveSavedSnippet != null ? liveSavedSnippet : newDraftSnippet();
        applyFormValues(saved);

        boolean firstLiveSave = existingSnippet == null && liveSavedSnippet == null && liveSaveHandler != null;
        boolean savedSuccessfully;
        if (firstLiveSave) {
            try {
                liveSaveHandler.accept(saved);
                savedSuccessfully = true;
            } catch (RuntimeException e) {
                showSaveFailure(e);
                savedSuccessfully = false;
            }
        } else {
            savedSuccessfully = persistSnippet(saved);
        }
        if (!savedSuccessfully) {
            updateSaveButtonState();
            updateExternalFileButtonState();
            return false;
        }

        liveSavedSnippet = saved;
        trackSnippetSaved(saved);
        initialContentSnapshot = safeContentText();
        initialFormSnapshot = currentFormSnapshot();
        lastTrackedContent = initialContentSnapshot;
        pendingHistoryContent = "";
        sliderActive = false;
        currentHistoryIndex = contentHistory.isEmpty() ? -1 : contentHistory.size() - 1;
        updateHistorySliderState();
        updateSaveButtonState();
        updateExternalFileButtonState();
        if (draftAutosave != null) {
            draftAutosave.saved();
        }
        if (embedding != null) {
            embedding.snippetPersisted(this, saved, created);
        }
        claimPersistedSnippetId();
        if (analysisController != null) {
            analysisController.onSnippetSaved(saved);
        }
        return true;
    }

    /** A new snippet carrying the draft id, so the key hosts used before the first save survives it. */
    private Snippet newDraftSnippet() {
        Snippet snippet = new Snippet();
        if (draftSnippetId != null) {
            snippet.setId(draftSnippetId);
        }
        return snippet;
    }

    /** The embedding's manager (injected, test-isolated), or the application's. */
    private SnippetManager resolveSnippetManager() {
        if (embedding != null) {
            return embedding.snippetManager();
        }
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getSnippetManager() : null;
    }

    private boolean persistSnippet(Snippet snippet) {
        if (snippet == null) {
            return false;
        }
        try {
            var snippetManager = resolveSnippetManager();
            if (snippetManager == null) {
                throw new IllegalStateException(I18n.get("snippets.error.unknown"));
            }
            // A category typed into the editable combo must exist as a category too, or the
            // manager's filter and every other editor would never offer it.
            snippetManager.ensureCategory(snippet.getCategory());
            if (snippetManager.findById(snippet.getId()).isPresent()) {
                snippetManager.updateSnippet(snippet);
            } else {
                snippetManager.addSnippet(snippet);
            }
            snippetManager.save();
            return true;
        } catch (Exception e) {
            showSaveFailure(e);
            return false;
        }
    }

    private void showSaveFailure(Throwable failure) {
        String message = failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
            ? failure.getMessage()
            : failure != null ? failure.getClass().getSimpleName() : I18n.get("snippets.error.unknown");
        setStatus(message);
        showAlert(message, Alert.AlertType.ERROR);
    }
    
    private void installUnsavedContentCloseGuard(Button cancelButton) {
        // Cancel button should close directly without prompting
        // Only window close (X) should prompt for unsaved changes

        setOnCloseRequest(event -> {
            if (embedding != null) {
                // Embedded: decide (and save) BEFORE the pane is unmounted; on success close
                // ourselves, otherwise the tab stays open with the edits intact.
                if (allowCloseWithoutUnsavedPrompt) {
                    return;
                }
                event.consume();
                if (isAnyAiTaskRunning() && !confirmCloseWhileAiRunning()) {
                    return;
                }
                if (confirmCloseFromHost()) {
                    closeWithoutPrompt();
                }
                return;
            }
            // Only prompt if closing via window X button, not from Cancel/OK buttons
            if (allowCloseWithoutUnsavedPrompt || !hasUnsavedContentChanges()) {
                return;
            }
            // Check if this close was triggered by a button - buttons handle their own logic
            event.consume();
            closeFromUnsavedContentChoice(promptForUnsavedContentChoice());
        });
    }

    /** One event per snippet-editor AI action; stable literal (never a localized theme string). */
    private void trackSnippetAiAction(String action) {
        Telemetry.track(TelemetryEvents.SNIPPET_AI_ACTION, Map.of("action", action));
    }

    /** Fires on save so "most used language" reflects persisted work, not combo clicks. Never sends name/content. */
    private void trackSnippetSaved(Snippet saved) {
        if (saved == null) {
            return;
        }
        // Clamp to a bounded known-language token — never send AI-/user-produced free text.
        String language = SnippetLanguageSupport.telemetryLanguageToken(saved.getLanguage(), saved.getContent());
        String content = saved.getContent();
        int lineCount = content == null || content.isEmpty() ? 0 : content.split("\n", -1).length;
        Telemetry.track(TelemetryEvents.SNIPPET_SAVED, Map.of(
            "language", language,
            "has_category", saved.getCategory() != null && !saved.getCategory().isBlank(),
            "content_lines", lineCount));
    }

    private void undoContentChange() {
        if (!contentArea.isUndoAvailable()) {
            updateUndoControls();
            return;
        }
        if (!telemetryUndoTracked) {
            telemetryUndoTracked = true;
            Telemetry.track(TelemetryEvents.SNIPPET_HISTORY_USED, Map.of("kind", "undo"));
        }
        contentArea.undo();
        refreshBlockCaretSoon();
        updateUndoControls();
        updateSaveButtonState();
    }

    private void updateUndoControls() {
        if (undoItem != null) {
            undoItem.setDisable(!contentArea.isUndoAvailable());
        }
    }

    /** Keeps the window title showing the current file/snippet name so it's identifiable in the taskbar. */
    private void updateEditorTitle() {
        String base = externalFileActionConfig != null
            ? I18n.get("snippets.fileEdit.title", externalFileActionConfig.sourceLabel())
            : existingSnippet == null ? I18n.get("snippets.addTitle") : I18n.get("snippets.editTitle");
        String name = nameField != null ? nameField.getText() : null;
        // External-file titles already contain the file name; only the internal snippet name needs appending.
        if (externalFileActionConfig == null && name != null && !name.isBlank()) {
            setTitle(base + " — " + name.trim());
        } else {
            setTitle(base);
        }
    }

    /** The current snippet/file name for use in secondary window titles (e.g. the analysis window). */
    String currentSnippetName() {
        String name = nameField != null ? nameField.getText() : null;
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return externalFileActionConfig != null ? externalFileActionConfig.sourceLabel() : "";
    }

    private void updateSaveButtonState() {
        boolean formValid = isSnippetFormValid();
        boolean hasUnsavedChanges = hasUnsavedContentChanges();
        unsavedChanges.set(hasUnsavedChanges);
        savable.set(formValid);
        if (saveButton != null) {
            boolean visible = hasUnsavedChanges;
            saveButton.setVisible(visible);
            saveButton.setManaged(visible);
            saveButton.setDisable(!formValid);
        }
        if (saveAsNewSnippetButton != null) {
            saveAsNewSnippetButton.setDisable(!formValid);
        }
        updateOkButtonState(hasUnsavedChanges);
        if (draftAutosave != null) {
            draftAutosave.formChanged();
        }
    }

    private void updateOkButtonState(boolean hasUnsavedChanges) {
        if (okButton != null) {
            okButton.setDisable(hasUnsavedChanges);
        }
    }

    // ---- Content History Methods ----

    private int getEffectiveHistoryMaxSize() {
        if (existingSnippet != null && existingSnippet.getHistoryMaxSize() != null) {
            int size = existingSnippet.getHistoryMaxSize();
            if (size == 0) return Integer.MAX_VALUE; // 0 means unlimited
            return Math.max(1, Math.min(99, size));
        }
        try {
            var settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings != null) {
                int size = settings.getSnippetHistoryMaxSize();
                if (size == 0) return Integer.MAX_VALUE; // 0 means unlimited
                return Math.max(1, Math.min(99, size));
            }
        } catch (Exception e) {
            // Ignore
        }
        return 30;
    }

    private void addToHistory(String newContent) {
        if (newContent == null) {
            return;
        }
        // Don't track if content hasn't changed from last entry
        if (!contentHistory.isEmpty()
                && java.util.Objects.equals(contentHistory.get(contentHistory.size() - 1).getContent(), newContent)) {
            return;
        }
        int maxSize = getEffectiveHistoryMaxSize();
        contentHistory.add(new SnippetHistoryEntry(newContent));
        currentHistoryIndex = contentHistory.size() - 1;

        trimHistoryToLimit(maxSize);

        updateHistorySliderState();
    }

    private void flushPendingHistory() {
        historyDebounce.stop();
        String currentContent = safeContentText();
        if (!currentContent.isBlank()
                && (contentHistory.isEmpty()
                || !java.util.Objects.equals(currentContent, contentHistory.get(contentHistory.size() - 1).getContent()))) {
            addToHistory(currentContent);
        }
        lastTrackedContent = currentContent;
        pendingHistoryContent = "";
    }

    private void trimHistoryToLimit() {
        trimHistoryToLimit(getEffectiveHistoryMaxSize());
    }

    private void trimHistoryToLimit(int maxSize) {
        if (maxSize == Integer.MAX_VALUE) {
            return;
        }
        while (contentHistory.size() > maxSize) {
            contentHistory.remove(0);
            currentHistoryIndex--;
        }
        if (contentHistory.isEmpty()) {
            currentHistoryIndex = -1;
        } else {
            currentHistoryIndex = Math.max(0, Math.min(currentHistoryIndex, contentHistory.size() - 1));
        }
    }

    private void updateHistorySliderState() {
        if (historySlider == null || historyLabel == null) {
            return;
        }
        int size = contentHistory.size();
        updatingHistorySlider = true;
        try {
            if (size <= 1) {
                historySlider.setDisable(true);
                historySlider.setMin(0);
                historySlider.setMax(1);
                historySlider.setValue(0);
                historyLabel.setText(I18n.get("snippets.history.label") + ": 0");
            } else {
                historySlider.setDisable(false);
                historySlider.setMin(0);
                historySlider.setMax(size - 1);
                historySlider.setMajorTickUnit(1);
                historySlider.setValue(Math.max(0, Math.min(currentHistoryIndex, size - 1)));
                historyLabel.setText(String.format(I18n.get("snippets.history.position"), currentHistoryIndex + 1, size));
            }
        } finally {
            updatingHistorySlider = false;
        }
    }

    private void navigateToHistoryEntry(int index) {
        if (index < 0 || index >= contentHistory.size()) {
            return;
        }
        if (sliderActive && !telemetryHistorySliderTracked) {
            telemetryHistorySliderTracked = true;
            Telemetry.track(TelemetryEvents.SNIPPET_HISTORY_USED, Map.of("kind", "history_slider"));
        }
        currentHistoryIndex = index;
        String historicalContent = contentHistory.get(currentHistoryIndex).getContent();
        if (!safeContentText().equals(historicalContent)) {
            programmaticContentUpdate = true;
            try {
                contentArea.replaceText(historicalContent);
            } finally {
                programmaticContentUpdate = false;
            }
        }
        updateHistorySliderState();
    }

    private void applyHistoryHighlighting(String oldContent, String newContent) {
        // Simple diff highlighting - in a full implementation you would use a proper diff algorithm
        // For now, we just apply a general highlight style
        // The actual diff visualization would require more complex implementation with the MonacoEditorPane
        contentArea.setStyle("-fx-background-color: #2d2d30;");
    }

    private void clearContentHistory() {
        contentHistory.clear();
        currentHistoryIndex = -1;
        updateHistorySliderState();
    }

    private void configureExternalFileActionButton(
        Button button,
        ExternalFileAction action,
        String successMessage,
        boolean markFileSaved) {
        if (button == null) {
            return;
        }
        if (action == null) {
            // No action supplied — currently only the enterprise policy's read-only mode for
            // remote write-back. Keep the button visible but locked, so the restriction is evident.
            button.setDisable(true);
            button.setTooltip(new Tooltip(I18n.get("policy.terminal.loadReadOnly")));
            return;
        }
        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runExternalFileAction(action, successMessage, markFileSaved);
        });
    }

    private void runExternalFileAction(ExternalFileAction action, String successMessage, boolean markFileSaved) {
        if (externalFileActionRunning) {
            return;
        }
        if (!isSnippetFormValid()) {
            updateExternalFileButtonState();
            return;
        }

        Snippet draft = buildResultSnippet();
        externalFileActionRunning = true;
        updateExternalFileButtonState();
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                return action.run(draft);
            }
        };
        task.setOnSucceeded(event -> {
            boolean completed = Boolean.TRUE.equals(task.getValue());
            if (completed) {
                if (markFileSaved) {
                    initialContentSnapshot = safeContentText();
                    initialFormSnapshot = currentFormSnapshot();
                }
                setStatus(successMessage);
            }
            externalFileActionRunning = false;
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            String message;
            if (failure == null) {
                message = I18n.get("snippets.error.unknown");
            } else if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                message = failure.getMessage();
            } else {
                message = failure.getClass().getSimpleName();
            }
            setStatus(message);
            showAlert(message, Alert.AlertType.ERROR);
            externalFileActionRunning = false;
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        task.setOnCancelled(event -> {
            externalFileActionRunning = false;
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        Thread thread = new Thread(task, "snippet-external-file-action");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateExternalFileButtonState() {
        if (externalFileActionConfig == null) {
            return;
        }
        boolean disable = externalFileActionRunning || !isSnippetFormValid();
        if (overwriteFileButton != null) {
            overwriteFileButton.setDisable(disable);
        }
        if (saveFileAsButton != null) {
            saveFileAsButton.setDisable(disable);
        }
        if (saveAsSnippetButton != null) {
            saveAsSnippetButton.setDisable(disable);
        }
    }

    private UnsavedContentChoice promptForUnsavedContentChoice() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(getTitle());
        alert.setHeaderText(I18n.get("editor.close.header"));
        alert.setContentText(I18n.get("editor.close.unsaved"));

        ButtonType saveButtonType = new ButtonType(I18n.get("editor.close.save"));
        ButtonType discardButtonType = new ButtonType(I18n.get("editor.close.discard"));
        ButtonType cancelButtonType = new ButtonType(I18n.get("editor.close.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        if (externalFileActionConfig != null) {
            alert.getButtonTypes().setAll(discardButtonType, cancelButtonType);
        } else {
            alert.getButtonTypes().setAll(saveButtonType, discardButtonType, cancelButtonType);
        }

        if (getDialogPane().getScene() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
        if (externalFileActionConfig == null) {
            alert.getDialogPane().lookupButton(saveButtonType).setDisable(!isSnippetFormValid());
        }

        Optional<ButtonType> response = alert.showAndWait();
        if (response.isEmpty() || response.get() == cancelButtonType) {
            return UnsavedContentChoice.CANCEL;
        }
        return response.get() == saveButtonType ? UnsavedContentChoice.SAVE : UnsavedContentChoice.DISCARD;
    }

    private void closeFromUnsavedContentChoice(UnsavedContentChoice choice) {
        if (choice == null || choice == UnsavedContentChoice.CANCEL) {
            return;
        }
        saveGeometry();
        allowCloseWithoutUnsavedPrompt = true;
        if (choice == UnsavedContentChoice.SAVE) {
            String ignoredSnippetId = existingSnippet != null
                ? existingSnippet.getId()
                : liveSavedSnippet != null ? liveSavedSnippet.getId() : null;
            if (!validateUniqueSnippetNameBeforeSave(ignoredSnippetId)) {
                allowCloseWithoutUnsavedPrompt = false;
                return;
            }
            flushPendingHistory();
            setResult(buildResultSnippet());
        } else {
            setResult(null);
            if (draftAutosave != null) {
                draftAutosave.discarded();
            }
        }
        closeDialogOrHostTab();
    }

    // ---- Hosting API (snippet workspace) ----

    private static final KeyCombination SAVE_SHORTCUT =
        new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);

    /**
     * Shortcut+S saves without closing. A scene-wide accelerator would fire for whichever editor
     * registered last (and in tab mode lose to "Save project"), so this is a key filter on the
     * pane: it only sees keys while focus is inside this editor, and consuming the event stops
     * the host window's accelerators.
     */
    private void installSaveShortcut() {
        if (externalFileActionConfig != null) {
            return;
        }
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!SAVE_SHORTCUT.match(event)) {
                return;
            }
            event.consume();
            if (hasUnsavedContentChanges() && isSnippetFormValid()) {
                saveSnippetWithoutClosing();
            }
        });
    }

    @Override
    protected void onHostedAttached() {
        // setOnShown never fires for a hosted pane; run the "opened" work here instead.
        if (hostedAttachHandled) {
            return;
        }
        hostedAttachHandled = true;
        // A hosted pane shares the main window's (or workspace's) scene with other editors: a
        // scene-wide Shortcut+Z accelerator would undo in whichever editor registered last. The
        // Monaco key filter handles Shortcut+Z for the focused editor anyway.
        undoItem.setAccelerator(null);
        autoDetectAiSkills();
    }

    /** Saves the form in place (no close); {@code false} when validation or persistence failed. */
    boolean saveFromHost() {
        return saveSnippetWithoutClosing();
    }

    /**
     * "Save as new" for an embedded editor: persists a copy of the form as a new snippet and
     * returns it, leaving this editor's own snippet untouched. {@code null} when not possible.
     */
    Snippet saveAsNewFromHost() {
        if (!saveAsNewSnippetEnabled || !isSnippetFormValid()) {
            updateSaveButtonState();
            return null;
        }
        if (!validateUniqueSnippetNameBeforeSave(null)) {
            updateSaveButtonState();
            return null;
        }
        flushPendingHistory();
        Snippet copy = buildNewResultSnippet();
        if (!persistSnippet(copy)) {
            return null;
        }
        trackSnippetSaved(copy);
        return copy;
    }

    /** Whether "Save as new" applies (an existing snippet, not an external file). */
    boolean canSaveAsNew() {
        return saveAsNewSnippetEnabled;
    }

    /**
     * Asks about unsaved changes without closing: Save (saves, {@code true} on success), Discard
     * ({@code true}) or Cancel ({@code false}). Clean editors return {@code true} without asking.
     */
    boolean confirmCloseFromHost() {
        if (!hasUnsavedContentChanges()) {
            return true;
        }
        UnsavedContentChoice choice = hostUnsavedPrompter != null
            ? hostUnsavedPrompter.apply(this)
            : promptForUnsavedContentChoice();
        if (choice == null || choice == UnsavedContentChoice.CANCEL) {
            return false;
        }
        if (choice == UnsavedContentChoice.SAVE) {
            return saveFromHost();
        }
        if (draftAutosave != null) {
            draftAutosave.discarded();
        }
        return true;
    }

    /**
     * The host's close guard (main-window Cmd+W, close all, window close, quit): asks before
     * cancelling running AI work, then about unsaved changes (Save / Discard / Cancel). Brings the
     * editor forward first when it has to ask. Never closes; {@code false} vetoes.
     */
    @Override
    public boolean confirmHostedClose() {
        if (!needsCloseConfirmation()) {
            return true;
        }
        revealDialogOrHost();
        if (isAnyAiTaskRunning() && !confirmCloseWhileAiRunning()) {
            return false;
        }
        return confirmCloseFromHost();
    }

    @Override
    public boolean needsCloseConfirmation() {
        return isAnyAiTaskRunning() || hasUnsavedContentChanges();
    }

    /** Closes the editor without any prompt (the host already asked); unsaved edits are dropped. */
    void closeWithoutPrompt() {
        allowCloseWithoutUnsavedPrompt = true;
        closeDialogOrHostTab();
    }

    /** Closing cancels running AI work; asks first. {@code true} means close anyway. */
    private boolean confirmCloseWhileAiRunning() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(getTitle());
        alert.setHeaderText(I18n.get("snippets.workspace.close.aiRunning.header"));
        alert.setContentText(I18n.get("snippets.workspace.close.aiRunning.content"));
        Window owner = resolveAlertOwner();
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Test seam: answers the host-close unsaved prompt instead of an Alert; {@code null} restores it. */
    static void setHostUnsavedPrompterForTesting(Function<SnippetEditDialog, UnsavedContentChoice> prompter) {
        hostUnsavedPrompter = prompter;
    }

    /** The edited snippet's id; a never-saved snippet reports its stable draft id (never null). */
    String snippetId() {
        if (existingSnippet != null) {
            return existingSnippet.getId();
        }
        if (liveSavedSnippet != null) {
            return liveSavedSnippet.getId();
        }
        return draftSnippetId;
    }

    /** The snippet this editor has persisted to, or {@code null} for an unsaved draft. */
    Snippet persistedSnippet() {
        return existingSnippet != null ? existingSnippet : liveSavedSnippet;
    }

    boolean hasUnsavedChanges() {
        return hasUnsavedContentChanges();
    }

    ReadOnlyBooleanProperty unsavedChangesProperty() {
        return unsavedChanges.getReadOnlyProperty();
    }

    /** Whether the form is complete enough to save (name and content present). */
    ReadOnlyBooleanProperty savableProperty() {
        return savable.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty aiBusyProperty() {
        return aiBusy.getReadOnlyProperty();
    }

    boolean isAiWorkRunning() {
        return isAnyAiTaskRunning();
    }

    ReadOnlyStringProperty snippetNameProperty() {
        return nameField.textProperty();
    }

    /** Refreshes the category choices in place (keeps what the user typed into the combo). */
    void updateCategoryChoices(List<String> fresh) {
        String typed = categoryCombo.getValue();
        String editorText = categoryCombo.getEditor().getText();
        ObservableListSync.sync(categoryCombo.getItems(), fresh != null ? fresh : List.of(), Function.identity());
        if (!java.util.Objects.equals(typed, categoryCombo.getValue())) {
            categoryCombo.setValue(typed);
        }
        if (!java.util.Objects.equals(editorText, categoryCombo.getEditor().getText())) {
            categoryCombo.getEditor().setText(editorText);
        }
    }

    /** Focuses the name field for a new snippet, the code editor otherwise. */
    void focusEditor() {
        if (existingSnippet == null && liveSavedSnippet == null
            && (nameField.getText() == null || nameField.getText().isBlank())
            && safeContentText().isEmpty()) {
            nameField.requestFocus();
            return;
        }
        contentArea.requestEditorFocus();
    }

    /**
     * Replays the keystroke that promoted a read-only preview to this editor: inserts
     * {@code text} at {@code caret} (clamped) and places the caret after it.
     */
    void applyInitialKeystroke(int caret, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int position = Math.max(0, Math.min(caret, safeContentText().length()));
        contentArea.insertText(position, text);
        contentArea.moveTo(position + text.length());
        updateSaveButtonState();
    }

    private boolean validateUniqueSnippetNameBeforeSave(String ignoredSnippetId) {
        SnippetManager manager = resolveSnippetManager();
        if (manager == null) {
            return true;
        }
        if (!manager.hasSnippetName(nameField.getText(), ignoredSnippetId)) {
            return true;
        }
        String snippetName = normalizedFieldValue(nameField.getText());
        showSaveFailure(new IllegalArgumentException(I18n.get("snippets.error.duplicateName", snippetName)));
        return false;
    }

    enum UnsavedContentChoice {
        SAVE,
        DISCARD,
        CANCEL
    }

    private boolean hasUnsavedContentChanges() {
        FormSnapshot initial = initialFormSnapshot != null
            ? initialFormSnapshot
            : new FormSnapshot("", "", "", "", "", initialContentSnapshot != null ? initialContentSnapshot : "", "");
        return !currentFormSnapshot().equals(initial);
    }

    private boolean isSnippetFormValid() {
        return nameField.getText() != null && !nameField.getText().isBlank()
                && !safeContentText().isBlank();
    }

    private String safeContentText() {
        String content = contentArea.getText();
        return content != null ? content : "";
    }

    private FormSnapshot currentFormSnapshot() {
        return new FormSnapshot(
            normalizedFieldValue(nameField.getText()),
            normalizedFieldValue(languageCombo.getValue()),
            normalizedFieldValue(categoryCombo.getValue()),
            normalizedFieldValue(tagsField.getText()),
            normalizedFieldValue(descriptionArea.getText()),
            safeContentText(),
            diagramFingerprint());
    }

    private String diagramFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        for (SnippetDiagram diagram : diagrams) {
            if (diagram == null) {
                fingerprint.append("<null>;");
                continue;
            }
            appendFingerprintValue(fingerprint, diagram.getId());
            appendFingerprintValue(fingerprint, diagram.getTitle());
            appendFingerprintValue(fingerprint, diagram.getType());
            appendFingerprintValue(fingerprint, diagram.getMermaidSource());
            appendFingerprintValue(fingerprint, diagram.getSourceContentSha256());
            appendFingerprintValue(fingerprint, diagram.getCustomInstructions());
            fingerprint.append(diagram.getCreatedAt()).append(':').append(diagram.getUpdatedAt()).append(';');
            for (SnippetDiagram.CodeReference reference : diagram.getCodeReferences()) {
                if (reference == null) {
                    fingerprint.append("<null-ref>;");
                    continue;
                }
                appendFingerprintValue(fingerprint, reference.getNodeId());
                appendFingerprintValue(fingerprint, reference.getLabel());
                fingerprint.append(reference.getStartLine()).append(':').append(reference.getEndLine()).append(';');
            }
            fingerprint.append('|');
        }
        return fingerprint.toString();
    }

    private static void appendFingerprintValue(StringBuilder target, String value) {
        String safe = value != null ? value : "";
        target.append(safe.length()).append(':').append(safe).append(';');
    }

    private String normalizedFieldValue(String value) {
        return value == null ? "" : value.trim();
    }

    private Snippet buildResultSnippet() {
        Snippet result = existingSnippet != null
            ? existingSnippet
            : liveSavedSnippet != null ? liveSavedSnippet : newDraftSnippet();
        applyFormValues(result);
        return result;
    }

    private Snippet buildNewResultSnippet() {
        Snippet result = new Snippet();
        applyFormValues(result);
        return result;
    }

    private void applyFormValues(Snippet result) {
        String content = safeContentText();
        result.setName(nameField.getText().trim());
        result.setContent(content);
        result.setLanguage(SnippetLanguageSupport.detectSnippetLanguage(languageCombo.getValue(), content));
        result.setCategory(categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : null);
        result.setTagsFromString(tagsField.getText());
        result.setDescription(descriptionArea.getText() != null ? descriptionArea.getText().trim() : null);
        result.setDiagrams(copyDiagrams());
        result.setHistory(new ArrayList<>(contentHistory));
    }

    private List<SnippetDiagram> copyDiagrams() {
        List<SnippetDiagram> copy = new ArrayList<>();
        for (SnippetDiagram diagram : diagrams) {
            if (diagram != null) {
                copy.add(new SnippetDiagram(diagram));
            }
        }
        return copy;
    }

    /**
     * Stops the user from shrinking the editor window so narrow that the editor scrollbar gets clipped
     * on the right edge or the dialog's action buttons (Save / Save as new / OK / Cancel) overflow the
     * button bar and become unreachable. Width is generous so the longest localized button row fits.
     */
    private static final double MIN_DIALOG_WIDTH = 660;
    private static final double MIN_DIALOG_HEIGHT = 440;

    private void enforceMinimumWindowSize() {
        setOnShown(event -> {
            javafx.stage.Window window = getDialogPane().getScene() != null
                ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof javafx.stage.Stage stage) {
                stage.setMinWidth(MIN_DIALOG_WIDTH);
                stage.setMinHeight(MIN_DIALOG_HEIGHT);
                if (stage.getWidth() < MIN_DIALOG_WIDTH) {
                    stage.setWidth(MIN_DIALOG_WIDTH);
                }
                if (stage.getHeight() < MIN_DIALOG_HEIGHT) {
                    stage.setHeight(MIN_DIALOG_HEIGHT);
                }
            }
            autoDetectAiSkills();
        });
    }

    private void restoreGeometry() {
        DialogGeometrySupport.restore(this, settings -> settings.getSnippetEditGeometry());
    }

    private void saveGeometry() {
        if (embedding != null || isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        DialogGeometrySupport.persist(this, (settings, geometry) -> settings.setSnippetEditGeometry(geometry));
    }


    private boolean loadWordWrapSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetWordWrap();
        } catch (Exception e) {
            return true; // default on
        }
    }
    
    private void saveWordWrapSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetWordWrap(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    /** The configured chord that opens the completion list; the default when nothing is stored. */
    private String loadCompletionShortcutSetting() {
        try {
            return SnippetCompletionShortcut.normalizeOrDefault(
                KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().getSnippetCompletionShortcut());
        } catch (Exception e) {
            return SnippetCompletionShortcut.DEFAULT;
        }
    }

    private boolean loadLineNumbersSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetLineNumbers();
        } catch (Exception e) {
            return false;
        }
    }
    
    private void saveLineNumbersSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetLineNumbers(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private SnippetEditorProfile loadActiveSnippetEditorProfile(EditorSettingsHelper.Settings settings) {
        SnippetEditorProfile fallback = SnippetEditorProfileSupport.fromCurrentSettings(
            settings.foregroundColor(),
            settings.backgroundColor(),
            settings.cursorStyle(),
            settings.cursorColor());
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return SnippetEditorProfileSupport.resolveActiveProfile(globalSettings, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private EditorSettingsHelper.Settings applyProfileToSettings(
        EditorSettingsHelper.Settings settings,
        SnippetEditorProfile profile) {

        SnippetEditorProfile normalized = SnippetEditorProfileSupport.normalize(profile);
        return new EditorSettingsHelper.Settings(
            settings.fontFamily(),
            settings.fontSize(),
            normalized.getForegroundColor(),
            normalized.getBackgroundColor(),
            normalized.getCursorStyle(),
            normalized.getCursorColor());
    }

    private void refreshEditorProfileMenu() {
        if (editorProfileMenu == null) {
            return;
        }
        editorProfileMenu.setText(I18n.get("snippets.editor.profile.menu", editorProfileName(editorProfile)));
        editorProfileMenu.getItems().clear();

        MenuItem presetHeader = new MenuItem(I18n.get("snippets.editor.profile.presets"));
        presetHeader.setDisable(true);
        editorProfileMenu.getItems().add(presetHeader);
        for (SnippetEditorProfile profile : SnippetEditorProfileSupport.builtInProfiles()) {
            MenuItem item = new MenuItem(profile.getName());
            item.setOnAction(event -> applySnippetEditorProfile(profile, true));
            editorProfileMenu.getItems().add(item);
        }

        List<SnippetEditorProfile> customProfiles = loadCustomSnippetEditorProfiles();
        if (!customProfiles.isEmpty()) {
            editorProfileMenu.getItems().add(new SeparatorMenuItem());
            MenuItem customHeader = new MenuItem(I18n.get("snippets.editor.profile.custom"));
            customHeader.setDisable(true);
            editorProfileMenu.getItems().add(customHeader);
            for (SnippetEditorProfile profile : customProfiles) {
                MenuItem item = new MenuItem(profile.getName());
                item.setOnAction(event -> applySnippetEditorProfile(profile, true));
                editorProfileMenu.getItems().add(item);
            }
        }

        editorProfileMenu.getItems().add(new SeparatorMenuItem());
        MenuItem newProfileItem = new MenuItem(I18n.get("snippets.editor.profile.new"));
        newProfileItem.setOnAction(event -> openCustomSnippetEditorProfileDialog(false));
        MenuItem editProfileItem = new MenuItem(I18n.get("snippets.editor.profile.edit"));
        editProfileItem.setDisable(editorProfile == null
            || editorProfile.isBuiltIn()
            || SnippetEditorProfileSupport.CURRENT_SETTINGS_PROFILE_ID.equals(editorProfile.getId()));
        editProfileItem.setOnAction(event -> openCustomSnippetEditorProfileDialog(true));
        editorProfileMenu.getItems().addAll(newProfileItem, editProfileItem);
    }

    private List<SnippetEditorProfile> loadCustomSnippetEditorProfiles() {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return SnippetEditorProfileSupport.customProfiles(globalSettings);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void openCustomSnippetEditorProfileDialog(boolean editExisting) {
        SnippetEditorProfile baseProfile = editorProfile != null
            ? editorProfile
            : SnippetEditorProfileSupport.fromCurrentSettings(
                editorSettings.foregroundColor(),
                editorSettings.backgroundColor(),
                editorSettings.cursorStyle(),
                editorSettings.cursorColor());
        SnippetEditorProfileDialog dialog = new SnippetEditorProfileDialog(
            childWindowOwner(),
            baseProfile,
            editExisting);
        // Non-modal, answered through a callback: a nested event loop here would hold up every other
        // editor tab's dialogs until this one closed.
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, hidden -> {
            SnippetEditorProfile profile = dialog.getResult();
            if (profile == null || editorClosed) {
                return;
            }
            saveCustomSnippetEditorProfile(profile);
            applySnippetEditorProfile(profile, true);
            setStatus(I18n.get("snippets.editor.profile.saved", profile.getName()));
        });
        showChildWindow(dialog);
    }

    private void applySnippetEditorProfile(SnippetEditorProfile profile, boolean save) {
        editorProfile = SnippetEditorProfileSupport.normalize(profile);
        editorSettings = applyProfileToSettings(editorSettings, editorProfile);
        backgroundBrightnessBaseColor = parseEditorBackgroundColor();
        applyEditorAppearance();
        updateBackgroundBrightnessControls();
        refreshEditorProfileMenu();
        if (save) {
            saveSnippetEditorProfileSelection(editorProfile);
            setStatus(I18n.get("snippets.editor.profile.selected", editorProfileName(editorProfile)));
        }
    }

    private void saveSnippetEditorProfileSelection(SnippetEditorProfile profile) {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (SnippetEditorProfileSupport.CURRENT_SETTINGS_PROFILE_ID.equals(profile.getId())) {
                globalSettings.setSelectedSnippetEditorProfileId(null);
            } else {
                globalSettings.setSelectedSnippetEditorProfileId(profile.getId());
            }
            syncSnippetEditorColorSettings(globalSettings, profile);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private void saveCustomSnippetEditorProfile(SnippetEditorProfile profile) {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            List<SnippetEditorProfile> profiles = new ArrayList<>(globalSettings.getSnippetEditorProfiles());
            profiles.removeIf(existing -> existing == null || profile.getId().equals(existing.getId()));
            profiles.add(SnippetEditorProfileSupport.normalize(profile));
            globalSettings.setSnippetEditorProfiles(profiles);
            globalSettings.setSelectedSnippetEditorProfileId(profile.getId());
            syncSnippetEditorColorSettings(globalSettings, profile);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private void syncSnippetEditorColorSettings(GlobalSettings globalSettings, SnippetEditorProfile profile) {
        SnippetEditorProfile normalized = SnippetEditorProfileSupport.normalize(profile);
        globalSettings.setSnippetForegroundColor(normalized.getForegroundColor());
        globalSettings.setSnippetBackgroundColor(normalized.getBackgroundColor());
        globalSettings.setSnippetCursorStyle(normalized.getCursorStyle());
        globalSettings.setSnippetCursorColor(normalized.getCursorColor());
    }

    private String editorProfileName(SnippetEditorProfile profile) {
        if (profile == null) {
            return I18n.get("snippets.editor.profile.current");
        }
        if (SnippetEditorProfileSupport.CURRENT_SETTINGS_PROFILE_ID.equals(profile.getId())) {
            return I18n.get("snippets.editor.profile.current");
        }
        return profile.getName();
    }

    private boolean handleEditorZoomShortcut(KeyEvent event) {
        if (!event.isShortcutDown() && !event.isControlDown()) {
            return false;
        }

        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changeEditorFontSize(EDITOR_FONT_ZOOM_STEP);
            event.consume();
            return true;
        }
        if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changeEditorFontSize(-EDITOR_FONT_ZOOM_STEP);
            event.consume();
            return true;
        }
        return false;
    }

    private void changeEditorFontSize(int delta) {
        int newSize = Math.max(MIN_EDITOR_FONT_SIZE,
                Math.min(MAX_EDITOR_FONT_SIZE, editorSettings.fontSize() + delta));
        if (newSize == editorSettings.fontSize()) {
            updateFontSizeLabel();
            return;
        }

        editorSettings = new EditorSettingsHelper.Settings(
                editorSettings.fontFamily(),
                newSize,
                editorSettings.foregroundColor(),
                editorSettings.backgroundColor(),
                editorSettings.cursorStyle(),
                editorSettings.cursorColor()
        );

        applyEditorAppearance();
        updateFontSizeLabel();
        saveSnippetFontSize(newSize);
        setStatus(I18n.get("editor.status.fontSize", newSize));
    }

    private MenuButton createBackgroundBrightnessMenu() {
        MenuButton menu = new MenuButton(formatBackgroundBrightnessButton());
        menu.setTooltip(new Tooltip(I18n.get("snippets.editor.backgroundBrightness.tooltip")));

        Slider brightnessSlider = new Slider(0, 100, currentBackgroundBrightnessPercent());
        brightnessSlider.setPrefWidth(180);
        brightnessSlider.setBlockIncrement(5);
        brightnessSlider.setMajorTickUnit(25);
        brightnessSlider.setMinorTickCount(4);
        brightnessSlider.setShowTickMarks(true);

        brightnessSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            applyEditorBackgroundBrightness(newValue.doubleValue(), !brightnessSlider.isValueChanging());
        });
        brightnessSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                saveSnippetBackgroundColor(editorSettings.backgroundColor());
            }
        });

        VBox controlBox = new VBox(8, new Label(I18n.get("snippets.editor.backgroundBrightness")), brightnessSlider,
                backgroundBrightnessValueLabel);
        controlBox.setPadding(new Insets(8));
        CustomMenuItem sliderItem = new CustomMenuItem(controlBox, false);
        menu.getItems().add(sliderItem);
        return menu;
    }

    private void applyEditorBackgroundBrightness(double brightnessPercent, boolean save) {
        double brightness = Math.max(0.0, Math.min(1.0, brightnessPercent / 100.0));
        Color adjusted = Color.hsb(
                backgroundBrightnessBaseColor.getHue(),
                backgroundBrightnessBaseColor.getSaturation(),
                brightness
        );
        String backgroundColor = toHex(adjusted);

        editorSettings = new EditorSettingsHelper.Settings(
                editorSettings.fontFamily(),
                editorSettings.fontSize(),
                editorSettings.foregroundColor(),
                backgroundColor,
                editorSettings.cursorStyle(),
                editorSettings.cursorColor()
        );

        applyEditorAppearance();
        updateBackgroundBrightnessControls();
        if (save) {
            editorProfile = SnippetEditorProfileSupport.fromCurrentSettings(
                editorSettings.foregroundColor(),
                editorSettings.backgroundColor(),
                editorSettings.cursorStyle(),
                editorSettings.cursorColor());
            refreshEditorProfileMenu();
            saveSnippetBackgroundColor(backgroundColor);
        }
        setStatus(I18n.get("snippets.editor.backgroundBrightness.status", currentBackgroundBrightnessPercent()));
    }

    private void applyEditorAppearance() {
        EditorSettingsHelper.applyStyle(contentArea, editorSettings);
        if (columnRuler != null) {
            columnRuler.setEditorAppearance(editorSettings);
        }
        if (lineNumbersCheckBox != null && lineNumbersCheckBox.isSelected()) {
            EditorSettingsHelper.applyLineNumbers(contentArea, true, editorSettings);
        }
        applyHighlighting();
        EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(formatEditorFontSize());
        }
    }

    private String formatEditorFontSize() {
        return editorSettings.fontSize() + "pt";
    }

    private void updateBackgroundBrightnessControls() {
        if (backgroundBrightnessMenu != null) {
            backgroundBrightnessMenu.setText(formatBackgroundBrightnessButton());
        }
        if (backgroundBrightnessValueLabel != null) {
            backgroundBrightnessValueLabel.setText(formatBackgroundBrightnessValue());
        }
    }

    private String formatBackgroundBrightnessButton() {
        return "\u2600 " + currentBackgroundBrightnessPercent() + "%";
    }

    private String formatBackgroundBrightnessValue() {
        return I18n.get("snippets.editor.backgroundBrightness.value", currentBackgroundBrightnessPercent());
    }

    private int currentBackgroundBrightnessPercent() {
        return (int) Math.round(parseEditorBackgroundColor().getBrightness() * 100.0);
    }

    private Color parseEditorBackgroundColor() {
        String backgroundColor = editorSettings != null ? editorSettings.backgroundColor() : "#1e1e1e";
        try {
            return Color.web(backgroundColor);
        } catch (Exception e) {
            return Color.web("#1e1e1e");
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                colorComponentToByte(color.getRed()),
                colorComponentToByte(color.getGreen()),
                colorComponentToByte(color.getBlue()));
    }

    private static int colorComponentToByte(double component) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, component)) * 255.0);
    }

    private void saveSnippetFontSize(int fontSize) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetFontSize(fontSize);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private void saveSnippetBackgroundColor(String backgroundColor) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSelectedSnippetEditorProfileId(null);
            gs.setSnippetForegroundColor(editorSettings.foregroundColor());
            gs.setSnippetBackgroundColor(backgroundColor);
            gs.setSnippetCursorStyle(editorSettings.cursorStyle());
            gs.setSnippetCursorColor(editorSettings.cursorColor());
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    private void validateForm(Button okButton) {
        updateSaveButtonState();
    }

    private void updateColumnRulerMetrics() {
        if (columnRuler == null || contentArea == null) {
            return;
        }
        columnRuler.setEditorMetrics(
            contentArea.editorContentLeftProperty().get(),
            contentArea.editorCharacterWidthProperty().get(),
            contentArea.editorScrollLeftProperty().get());
    }

    private void updateColumnRulerCaret() {
        if (columnRuler == null || contentArea == null) {
            return;
        }
        columnRuler.setCaretColumn(contentArea.getCaretColumn(), contentArea.getCaretVisualX());
    }

    private ContextMenu createEditorContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem cutItem = new MenuItem(I18n.get("editor.context.cut"));
        cutItem.setOnAction(e -> contentArea.cut());
        MenuItem copyItem = new MenuItem(I18n.get("editor.context.copy"));
        copyItem.setOnAction(e -> contentArea.copy());
        MenuItem pasteItem = new MenuItem(I18n.get("editor.context.paste"));
        pasteItem.setOnAction(e -> contentArea.paste());
        MenuItem deleteItem = new MenuItem(I18n.get("editor.context.delete"));
        deleteItem.setOnAction(e -> contentArea.replaceSelection(""));
        MenuItem selectAllItem = new MenuItem(I18n.get("editor.context.selectAll"));
        selectAllItem.setOnAction(e -> contentArea.selectAll());
        MenuItem formatItem = new MenuItem(I18n.get("editor.format"));
        formatItem.setOnAction(e -> runFormat());
        MenuItem lintItem = new MenuItem(I18n.get("editor.lint"));
        lintItem.setOnAction(e -> runLint());
        MenuItem correctSelectionItem = new MenuItem(aiActionLabel("snippets.ai.menu.correct"));
        correctSelectionItem.setOnAction(e -> runSelectionCorrection());
        MenuItem translateSelectionItem = new MenuItem(aiActionLabel("snippets.ai.menu.translate"));
        translateSelectionItem.setOnAction(e -> runSelectionTranslation());
        MenuItem describeSnippetContextItem = new MenuItem(aiActionLabel("snippets.ai.menu.describe"));
        describeSnippetContextItem.setOnAction(e -> runSnippetDescription());
        MenuItem oneLinerCompactCtx = new MenuItem(I18n.get("snippets.oneliner.compact"));
        oneLinerCompactCtx.setOnAction(e -> runOneLiner(true));
        MenuItem oneLinerEmbeddedCtx = new MenuItem(I18n.get("snippets.oneliner.embedded"));
        oneLinerEmbeddedCtx.setOnAction(e -> runOneLiner(false));
        MenuItem alternativeSolutionItem = new MenuItem(aiActionLabel("snippets.ai.alternatives.context"));
        alternativeSolutionItem.setOnAction(e -> runAlternativeSolutions());
        MenuItem completeCodeContextItem = new MenuItem(aiActionLabel("snippets.ai.code.complete"));
        completeCodeContextItem.setOnAction(e -> { trackSnippetAiAction("code_complete"); contentArea.triggerCompletionList(); });
        MenuItem codeAssistantContextItem = new MenuItem(aiActionLabel("snippets.ai.assistant.context"));
        codeAssistantContextItem.setOnAction(e -> runCodeAssistant());
        MenuItem reviewCodeContextItem = new MenuItem(aiActionLabel("snippets.ai.code.review"));
        reviewCodeContextItem.setOnAction(e -> runCodeReview());
        Menu reviewCodeWithProfileContextMenu = buildReviewWithProfileMenu();
        improveCommentsContextItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.comments"));
        improveCommentsContextItem.setOnAction(e -> {
            trackSnippetAiAction("code_improve_comments");
            runCommentOptimization();
        });
        MenuItem improveCustomContextItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.custom"));
        improveCustomContextItem.setOnAction(e -> runCustomCodeImprovement());
        migrateLanguageContextItem = new MenuItem(aiActionLabel("snippets.ai.code.migrate"));
        migrateLanguageContextItem.setOnAction(e -> {
            trackSnippetAiAction("code_migrate");
            runLanguageMigration();
        });
        MenuItem securityCheckContextItem = new MenuItem(aiActionLabel("snippets.ai.security.title"));
        securityCheckContextItem.setOnAction(e -> runSecurityCheck());
        // Diagram submenu: with a selection the chosen family diagrams just the selected lines,
        // otherwise the whole snippet.
        Menu diagramContextMenu = new Menu(aiActionLabel("snippets.ai.diagram.menu.generate"));
        for (SnippetDiagramType diagramType : SnippetDiagramType.values()) {
            MenuItem typeItem = new MenuItem(SnippetDiagramDialog.typeLabel(diagramType));
            typeItem.setOnAction(e -> {
                trackSnippetAiAction("code_diagram");
                runDiagramGenerationForSelection(diagramType);
            });
            diagramContextMenu.getItems().add(typeItem);
        }
        MenuItem diagramContextItem = new MenuItem(aiActionLabel("snippets.ai.diagram.menu"));
        diagramContextItem.setOnAction(e -> openOrCreateDiagram());
        CheckMenuItem wordWrapItem = new CheckMenuItem(I18n.get("snippets.wordWrap"));
        wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
        wordWrapItem.setOnAction(e -> {
            boolean on = wordWrapItem.isSelected();
            wordWrapCheckBox.setSelected(on);
            contentArea.setWrapText(on);
            saveWordWrapSetting(on);
        });
        CheckMenuItem lineNumbersItem = new CheckMenuItem(I18n.get("snippets.lineNumbers"));
        lineNumbersItem.setSelected(lineNumbersCheckBox.isSelected());
        lineNumbersItem.setOnAction(e -> {
            boolean on = lineNumbersItem.isSelected();
            lineNumbersCheckBox.setSelected(on);
            EditorSettingsHelper.applyLineNumbers(contentArea, on, editorSettings);
            saveLineNumbersSetting(on);
        });
        menu.getItems().addAll(
                cutItem, copyItem, pasteItem, deleteItem,
                new SeparatorMenuItem(),
                selectAllItem,
                new SeparatorMenuItem(),
                formatItem, lintItem,
                new SeparatorMenuItem(),
                correctSelectionItem,
                translateSelectionItem,
                describeSnippetContextItem,
                new SeparatorMenuItem(),
                alternativeSolutionItem,
                completeCodeContextItem,
                codeAssistantContextItem,
                reviewCodeContextItem,
                reviewCodeWithProfileContextMenu,
                improveCommentsContextItem,
                improveCustomContextItem,
                migrateLanguageContextItem,
                securityCheckContextItem,
                diagramContextItem,
                diagramContextMenu,
                new SeparatorMenuItem(),
                oneLinerCompactCtx, oneLinerEmbeddedCtx,
                new SeparatorMenuItem(),
                wordWrapItem, lineNumbersItem
        );
        menu.setOnShowing(e -> {
            boolean hasSelection = contentArea.getSelection().getLength() > 0;
            cutItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
            wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
            lineNumbersItem.setSelected(lineNumbersCheckBox.isSelected());
            String lang = languageCombo.getValue();
            String t = contentArea.getText();
            boolean hasContent = t != null && !t.isBlank();
            boolean aiBusy = isAnyAiTaskRunning() || isAiChangeReviewOpen();
            formatItem.setDisable(!hasContent || aiBusy || (!CodeFormatterService.isSupported(lang) && !hasCodeImprovementProvider()));
            lintItem.setDisable(!hasContent || aiBusy || (!SnippetLinter.isSupported(lang) && !hasCodeReviewProvider()));
            boolean compactOneLinerOk = hasContent && (SnippetOneLiner.isCompactSupported(lang) || hasOneLinerProvider());
            boolean embeddedOneLinerOk = hasContent && SnippetOneLiner.isEmbeddedSupported(lang);
            oneLinerCompactCtx.setDisable(!compactOneLinerOk || aiBusy);
            oneLinerEmbeddedCtx.setDisable(!embeddedOneLinerOk || aiBusy);
            correctSelectionItem.setDisable(!hasSelection || aiAssist == null || aiAssist.selectionCorrectionProvider() == null || aiBusy);
            translateSelectionItem.setDisable(!hasSelection || aiAssist == null || aiAssist.selectionTranslationProvider() == null || aiBusy);
            describeSnippetContextItem.setDisable(!hasContent || aiAssist == null || aiAssist.snippetDescriptionProvider() == null || aiBusy);
            alternativeSolutionItem.setDisable(!hasContent || !hasAlternativeSolutionProvider() || aiBusy);
            // The list is Monaco's own and lists local candidates without any provider; AI rows join when one exists.
            completeCodeContextItem.setDisable(!hasContent);
            codeAssistantContextItem.setDisable(!hasContent || !hasCodeAssistantProvider() || aiBusy);
            reviewCodeContextItem.setDisable(!hasContent || !hasCodeAnalysisProviders() || aiBusy);
            refreshReviewWithProfileMenu(reviewCodeWithProfileContextMenu);
            reviewCodeWithProfileContextMenu.setDisable(!hasContent || !hasCodeAnalysisProviders() || aiBusy);
            improveCommentsContextItem.setDisable(!hasSelection || !hasCodeImprovementProvider() || aiBusy);
            improveCustomContextItem.setDisable(!hasSelection || !hasCodeImprovementProvider() || aiBusy);
            // A migration always rewrites the whole snippet, so it needs content but no selection.
            migrateLanguageContextItem.setDisable(!hasContent || !hasLanguageMigrationProvider() || aiBusy);
            securityCheckContextItem.setDisable(!hasContent || !hasSecurityProviders() || aiBusy);
            diagramContextItem.setDisable(!hasContent || !hasDiagramProvider() || aiBusy);
            diagramContextMenu.setDisable(!hasContent || !hasDiagramProvider() || aiBusy);
            diagramContextMenu.setText(aiActionLabel(hasSelection
                ? "snippets.ai.diagram.menu.generate.selection"
                : "snippets.ai.diagram.menu.generate"));
        });
        return menu;
    }

    private void updateFormatLintButtonState() {
        String lang = languageCombo.getValue();
        String text = contentArea.getText();
        boolean hasContent = text != null && !text.isBlank();
        boolean aiBusy = isAnyAiTaskRunning();
        CodeFormatterService.FormatterInfo formatterInfo = CodeFormatterService.getFormatterInfo(lang);
        formatBtn.setDisable(aiBusy || !hasContent || (formatterInfo == null && !hasCodeImprovementProvider()));
        formatBtn.setTooltip(new Tooltip(I18n.get(
            "editor.format.tooltip",
            formatterInfo != null ? formatterInfo.displayName() : I18n.get("editor.format.tooltip.builtin"))));
        lintBtn.setDisable(aiBusy || !hasContent || (!SnippetLinter.isSupported(lang) && !hasCodeReviewProvider()));
    }

    private void updateOneLinerButtonState() {
        String lang = languageCombo.getValue();
        String text = contentArea.getText();
        boolean hasContent = text != null && !text.isBlank();
        boolean ok = hasContent
            && !isAnyAiTaskRunning()
            && (SnippetOneLiner.isEmbeddedSupported(lang)
            || SnippetOneLiner.isCompactSupported(lang)
            || hasOneLinerProvider());
        oneLinerMenu.setDisable(!ok);
    }

    private void updateMarkupPreviewAvailability() {
        if (markupPreviewToggleButton == null) {
            return;
        }
        boolean supported = SnippetMarkupPreviewRenderer.supports(languageCombo.getValue());
        if (!supported && markupPreviewToggleButton.isSelected()) {
            markupPreviewToggleButton.setSelected(false);
        } else if (supported && markupPreviewToggleButton.isSelected()) {
            refreshMarkupPreview();
        }
        markupPreviewToggleButton.setDisable(!supported);
    }

    private void handleMarkupPreviewToggle(boolean selected) {
        if (!selected) {
            return;
        }
        if (!SnippetMarkupPreviewRenderer.supports(languageCombo.getValue())) {
            markupPreviewToggleButton.setSelected(false);
            setStatus(I18n.get("snippets.preview.unsupported"));
            return;
        }
        refreshMarkupPreview();
    }

    private void scheduleMarkupPreviewRefresh() {
        if (markupPreviewToggleButton != null && markupPreviewToggleButton.isSelected()) {
            markupPreviewRefreshDelay.playFromStart();
        }
    }

    private void refreshMarkupPreview() {
        if (markupPreviewToggleButton == null || !markupPreviewToggleButton.isSelected()) {
            return;
        }
        String language = languageCombo.getValue();
        if (!SnippetMarkupPreviewRenderer.supports(language)) {
            updateMarkupPreviewAvailability();
            return;
        }
        ensureMarkupPreviewView().getEngine().loadContent(
            SnippetMarkupPreviewRenderer.renderHtml(language, safeContentText()),
            "text/html");
    }

    /** Lazily creates the preview WebView and stacks it over the editor, bound to the toggle. */
    private WebView ensureMarkupPreviewView() {
        if (markupPreviewView == null) {
            markupPreviewView = new WebView();
            markupPreviewView.getEngine().setJavaScriptEnabled(false);
            markupPreviewView.setContextMenuEnabled(false);
            markupPreviewView.visibleProperty().bind(markupPreviewToggleButton.selectedProperty());
            markupPreviewView.managedProperty().bind(markupPreviewView.visibleProperty());
            contentStack.getChildren().add(markupPreviewView);
        }
        return markupPreviewView;
    }

    private void updateAiActionAvailability() {
        boolean metadataRunning = isMetadataTaskRunning();
        boolean correctionRunning = isDescriptionCorrectionRunning();
        boolean snippetActionRunning = isSnippetAiActionRunning();
        boolean busy = metadataRunning || correctionRunning || snippetActionRunning;
        aiBusy.set(busy);
        // From here on "busy" also covers an AI change that still waits for Accept or Reject: a new
        // result must not arrive on top of it (the tab's spinner above only shows real work).
        busy = busy || isAiChangeReviewOpen();
        if (aiCodeTextLanguageCombo != null) {
            aiCodeTextLanguageCombo.setDisable(busy);
        }
        if (rememberAiCodeTextLanguageCheckBox != null) {
            rememberAiCodeTextLanguageCheckBox.setDisable(busy);
        }
        boolean hasMetadataProvider = aiAssist != null && aiAssist.metadataProvider() != null;
        boolean hasCorrectionProvider = aiAssist != null && aiAssist.descriptionCorrectionProvider() != null;
        boolean hasContent = contentArea.getText() != null && !contentArea.getText().isBlank();
        boolean hasSelection = contentArea.getSelection().getLength() > 0;
        generateMetadataButton.setDisable(
            busy
                || !hasMetadataProvider
                || !hasContent);
        correctDescriptionButton.setDisable(
            busy
                || !hasCorrectionProvider
                || descriptionArea.getText() == null
                || descriptionArea.getText().isBlank());
        boolean hasSelectionCorrectionProvider = aiAssist != null && aiAssist.selectionCorrectionProvider() != null;
        boolean hasSelectionTranslationProvider = aiAssist != null && aiAssist.selectionTranslationProvider() != null;
        boolean hasDescriptionProvider = aiAssist != null && aiAssist.snippetDescriptionProvider() != null;
        correctSelectionTextItem.setDisable(busy || !hasSelection || !hasSelectionCorrectionProvider);
        translateSelectionTextItem.setDisable(busy || !hasSelection || !hasSelectionTranslationProvider);
        describeSnippetItem.setDisable(busy || !hasContent || !hasDescriptionProvider);
        aiTextMenu.setDisable(busy || !hasContent || (!hasSelectionCorrectionProvider && !hasSelectionTranslationProvider && !hasDescriptionProvider));
        // Local candidates need no provider and the list never blocks on another action.
        completeCodeItem.setDisable(!hasContent);
        autoCompleteItem.setDisable(busy || !hasContent || !hasCompletionProvider());
        reviewCodeItem.setDisable(busy || !hasContent || !hasCodeAnalysisProviders());
        reviewCodeWithProfileMenu.setDisable(busy || !hasContent || !hasCodeAnalysisProviders());
        improveReadabilityItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improveRobustnessItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improvePerformanceItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improveCommentsItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improveCustomItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        migrateLanguageItem.setDisable(busy || !hasContent || !hasLanguageMigrationProvider());
        securityCheckItem.setDisable(busy || !hasContent || !hasSecurityProviders());
        diagramItem.setDisable(busy || !hasContent || !hasDiagramProvider());
        aiCodeMenu.setDisable(busy || !hasContent || (!hasCompletionProvider() && !hasCodeReviewProvider()
            && !hasCodeImprovementProvider() && !hasSecurityProviders() && !hasDiagramProvider()));
        // A completion request is cancellable too, but it blocks nothing else (busy stays as it is).
        boolean cancellable = snippetActionRunning || completionTask != null
            || isMetadataTaskRunning() || isDescriptionCorrectionRunning();
        cancelSnippetAiActionButton.setDisable(!cancellable);
        toggleLastAiChangeButton.setDisable(lastAiChangeSnapshot == null || busy);
        updateLastAiToggleTooltip();
        if (analysisController != null) {
            analysisController.onAvailabilityChanged();
        }
    }

    private boolean isSnippetAiActionRunning() {
        // The reference is installed before the worker thread starts and cleared by its terminal handler.
        // Treat READY/SCHEDULED as busy too, otherwise a debounced completion can overlap a new AI action.
        return snippetAiActionTask != null;
    }

    private boolean isMetadataTaskRunning() {
        return metadataTask != null && metadataTask.isRunning();
    }

    private boolean isDescriptionCorrectionRunning() {
        return descriptionCorrectionTask != null && descriptionCorrectionTask.isRunning();
    }

    private boolean isAnyAiTaskRunning() {
        return isMetadataTaskRunning() || isDescriptionCorrectionRunning() || isSnippetAiActionRunning();
    }

    private boolean hasAlternativeSolutionProvider() {
        return aiAssist != null && aiAssist.alternativeSolutionsProvider() != null;
    }

    /**
     * Whether the AI backing supports re-running a check/adjustment with a different profile. Only the
     * {@link MainWindow}-backed assist resolves the profile per request; the {@code AiResultTab} assist
     * uses a single fixed profile, so its dialogs hide the profile picker.
     */
    private boolean profileSwitchingSupported() {
        return aiAssist != null && aiAssist.profileSwitchingSupported();
    }

    // ---- AI text language + skill pickers -------------------------------------------------------

    private HBox buildAiCodeTextLanguageRow() {
        aiCodeTextLanguageCombo = new ComboBox<>();
        aiCodeTextLanguageCombo.setId("snippet-ai-text-language");
        // First entry, and the default: no translation at all. Everything below it converts the
        // snippet's prose into that language, which is a deliberate choice rather than a side effect.
        aiCodeTextLanguageCombo.getItems().add(new AiLanguageSupport.LanguageOption(
            AiLanguageSupport.AUTO_CODE, I18n.get("snippets.ai.language.auto")));
        aiCodeTextLanguageCombo.getItems().addAll(
            AiLanguageSupport.buildAvailableLanguageOptions(
                AiLanguageSupport.isAutomatic(aiCodeTextLanguageCode) ? null : aiCodeTextLanguageCode));
        aiCodeTextLanguageCombo.setPrefWidth(260);
        aiCodeTextLanguageCombo.setTooltip(new Tooltip(I18n.get("snippets.ai.language.tooltip")));
        aiCodeTextLanguageCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageSupport.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        aiCodeTextLanguageCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageSupport.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        AiLanguageSupport.LanguageOption selected = AiLanguageSupport.findOption(
            aiCodeTextLanguageCombo.getItems(), aiCodeTextLanguageCode);
        if (selected != null && !aiCodeTextLanguageCombo.getItems().contains(selected)) {
            aiCodeTextLanguageCombo.getItems().add(selected);
        }
        aiCodeTextLanguageCombo.getSelectionModel().select(selected);

        rememberAiCodeTextLanguageCheckBox = new CheckBox(I18n.get("snippets.ai.language.remember"));
        rememberAiCodeTextLanguageCheckBox.setId("snippet-ai-text-language-remember");
        rememberAiCodeTextLanguageCheckBox.setTooltip(
            new Tooltip(I18n.get("snippets.ai.language.remember.tooltip")));

        aiCodeTextLanguageCombo.setOnAction(event -> applyAiCodeTextLanguageSelection());
        rememberAiCodeTextLanguageCheckBox.setOnAction(event -> {
            if (rememberAiCodeTextLanguageCheckBox.isSelected()) {
                persistSelectedAiCodeTextLanguage();
            } else {
                setStatus(I18n.get("snippets.ai.language.temporary", selectedAiCodeTextLanguageLabel()));
            }
        });

        HBox row = new HBox(8,
            new Label(I18n.get("snippets.textLanguage") + ":"),
            aiCodeTextLanguageCombo,
            rememberAiCodeTextLanguageCheckBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void applyAiCodeTextLanguageSelection() {
        AiLanguageSupport.LanguageOption selected = aiCodeTextLanguageCombo != null
            ? aiCodeTextLanguageCombo.getSelectionModel().getSelectedItem()
            : null;
        if (selected == null) {
            return;
        }
        if (!programmaticAiTextLanguageUpdate) {
            aiTextLanguageUserEdited = true;
        }
        // "Automatic" is a mode, not a language: resolving it against the interface language would
        // turn the default straight back into the translation this feature exists to stop.
        aiCodeTextLanguageCode = AiLanguageSupport.isAutomatic(selected.code())
            ? AiLanguageSupport.AUTO_CODE
            : AiLanguageSupport.resolveFallbackLanguageCode(selected.code());
        if (programmaticAiTextLanguageUpdate) {
            return;
        }
        if (rememberAiCodeTextLanguageCheckBox != null && rememberAiCodeTextLanguageCheckBox.isSelected()) {
            persistSelectedAiCodeTextLanguage();
        } else {
            setStatus(I18n.get("snippets.ai.language.temporary", selected.label()));
        }
    }

    private void persistSelectedAiCodeTextLanguage() {
        if (saveAiCodeTextLanguagePreference(aiCodeTextLanguageCode)) {
            setStatus(I18n.get("snippets.ai.language.saved", selectedAiCodeTextLanguageLabel()));
            return;
        }
        if (rememberAiCodeTextLanguageCheckBox != null) {
            rememberAiCodeTextLanguageCheckBox.setSelected(false);
        }
        setStatus(I18n.get("snippets.ai.language.saveFailed"));
    }

    private String selectedAiCodeTextLanguageLabel() {
        AiLanguageSupport.LanguageOption selected = aiCodeTextLanguageCombo != null
            ? aiCodeTextLanguageCombo.getSelectionModel().getSelectedItem()
            : null;
        return selected != null ? selected.label() : aiCodeTextLanguageCode;
    }

    /**
     * The configured code-text language, defaulting to {@link AiLanguageSupport#AUTO_CODE}.
     *
     * <p>It used to default to the interface language, which meant a user who never opened this
     * picker had every AI code rewrite translate their script's comments into the language korTTY
     * happens to run in. Keeping the script's own language is the default now; naming a language
     * here is the deliberate opt-in to converting it.</p>
     */
    // ---- Code-text language: keep the script's own, or deliberately convert it ------------------

    /** What a code-rewriting action should do about the prose inside the snippet. */
    private record CodeTextChoice(de.kortty.core.CodeTextLanguage language, boolean aborted) {
        static CodeTextChoice of(de.kortty.core.CodeTextLanguage language) {
            return new CodeTextChoice(language, false);
        }

        static CodeTextChoice abort() {
            return new CodeTextChoice(null, true);
        }
    }

    /**
     * Works out which language a code rewrite must write, and installs it for the run.
     *
     * <p>Order matters: an explicitly picked language is the user saying "convert it", so it wins.
     * Otherwise the snippet keeps whatever language its prose already uses — remembered from an
     * earlier answer, or detected. When neither is available the user is asked, because guessing
     * here rewrites their own comments into a language they never chose.</p>
     *
     * @param mayAsk whether interrupting is acceptable. False for auto-completion, which fires on a
     *     timer while typing; a dialog there would be indefensible, so an undetectable language
     *     simply leaves the prompt's previous behaviour in place for that one action.
     */
    private CodeTextChoice resolveCodeTextLanguage(boolean mayAsk) {
        if (!AiLanguageSupport.isAutomatic(aiCodeTextLanguageCode)) {
            return CodeTextChoice.of(
                de.kortty.core.CodeTextLanguage.translateInto(aiCodeTextLanguageCode));
        }
        String remembered = existingSnippet != null ? existingSnippet.getCodeTextLanguageCode() : null;
        if (remembered != null && !remembered.isBlank()) {
            return CodeTextChoice.of(de.kortty.core.CodeTextLanguage.keep(remembered));
        }
        CodeTextLanguageDetector.Detection detection = CodeTextLanguageDetector.detect(
            contentArea.getText(), languageCombo.getValue());
        if (detection.isUsable()) {
            return CodeTextChoice.of(
                de.kortty.core.CodeTextLanguage.keep(detection.languageCode()));
        }
        if (!mayAsk) {
            return CodeTextChoice.of(null);
        }
        String answered = askForCodeTextLanguage(detection);
        if (answered == null) {
            return CodeTextChoice.abort();
        }
        rememberCodeTextLanguage(answered);
        return CodeTextChoice.of(de.kortty.core.CodeTextLanguage.keep(answered));
    }

    /**
     * Installs the resolved contract for the upcoming action.
     *
     * @return whether the action may proceed; {@code false} when the user dismissed the question
     */
    private boolean applyCodeTextLanguage(boolean mayAsk) {
        CodeTextChoice choice = resolveCodeTextLanguage(mayAsk);
        if (choice.aborted()) {
            setStatus(I18n.get("snippets.ai.language.ask.cancelled"));
            return false;
        }
        if (aiAssist != null && aiAssist.runtimeOptions() != null) {
            aiAssist.runtimeOptions().setCodeTextLanguage(choice.language());
        }
        return true;
    }

    /**
     * Asks which language the snippet's comments and messages are written in.
     *
     * @return the chosen ISO code, or {@code null} when the user dismissed the dialog
     */
    private String askForCodeTextLanguage(CodeTextLanguageDetector.Detection detection) {
        List<AiLanguageSupport.LanguageOption> options =
            AiLanguageSupport.buildAvailableLanguageOptions(null);
        ComboBox<AiLanguageSupport.LanguageOption> combo = new ComboBox<>();
        combo.setId("snippet-code-text-language-ask");
        combo.getItems().setAll(options);
        combo.setPrefWidth(260);
        // Pre-select the ambiguous winner when there was one: it is a hint, not a decision, and the
        // user still has to confirm it.
        AiLanguageSupport.LanguageOption preselected = AiLanguageSupport.findOption(
            options,
            detection.languageCode() != null
                ? detection.languageCode()
                : AiLanguageSupport.resolveFallbackLanguageCode(null));
        combo.getSelectionModel().select(preselected != null ? preselected : options.get(0));

        CheckBox remember = new CheckBox(I18n.get("snippets.ai.language.ask.remember"));
        remember.setSelected(true);

        Label explanation = new Label(I18n.get(
            detection.confidence() == CodeTextLanguageDetector.Confidence.AMBIGUOUS
                ? "snippets.ai.language.ask.ambiguous"
                : "snippets.ai.language.ask.unknown"));
        explanation.setWrapText(true);
        explanation.setMaxWidth(430);

        Dialog<String> dialog = new ThemeAwareDialog<>();
        dialog.setTitle(I18n.get("snippets.ai.language.ask.title"));
        dialog.getDialogPane().setHeaderText(I18n.get("snippets.ai.language.ask.header"));
        VBox content = new VBox(10, explanation,
            new HBox(8, new Label(I18n.get("snippets.ai.language")), combo), remember);
        content.setPadding(new Insets(6, 4, 2, 4));
        dialog.getDialogPane().setContent(content);
        ButtonType useButton = new ButtonType(
            I18n.get("snippets.ai.language.ask.use"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(useButton, ButtonType.CANCEL);
        Window owner = aiFlowAlertOwner();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        // Scoped to the snippet tool: a question about a snippet must not freeze a terminal session.
        dialog.initModality(aiFlowAlertModality());
        dialog.setResultConverter(button -> {
            if (button != useButton) {
                return null;
            }
            AiLanguageSupport.LanguageOption chosen = combo.getValue();
            rememberCodeTextLanguageAnswer = remember.isSelected();
            return chosen != null ? chosen.code() : null;
        });
        try {
            return dialog.showAndWait().orElse(null);
        } catch (IllegalStateException e) {
            // JavaFX refuses a nested event loop during a layout or animation pass. Rather than let
            // that escape into the action that asked, treat it as "no answer": the rewrite is
            // skipped, which is the safe outcome — the alternative is translating the user's
            // comments on a guess.
            logger.debug("Could not ask for the snippet code-text language right now", e);
            return null;
        }
    }

    /**
     * Stores the answer on the snippet so the question is asked once, not before every action.
     *
     * <p>Written straight through rather than waiting for the editor to be saved: the answer is
     * about the file's existing prose, not about the edit in progress, and a user who cancels their
     * edit should still not be asked the same question again.</p>
     */
    private void rememberCodeTextLanguage(String languageCode) {
        if (!rememberCodeTextLanguageAnswer || existingSnippet == null
            || languageCode == null || languageCode.isBlank()) {
            return;
        }
        existingSnippet.setCodeTextLanguageCode(languageCode);
        try {
            var manager = resolveSnippetManager();
            if (manager != null && manager.findById(existingSnippet.getId()).isPresent()) {
                manager.updateSnippet(existingSnippet);
                manager.save();
            }
        } catch (Exception e) {
            // The answer still applies to this editor for the rest of the session; only the
            // remembering failed, which is not worth interrupting a code rewrite for.
            logger.debug("Could not persist the snippet code-text language", e);
        }
    }

    private String loadConfiguredAiCodeTextLanguageCode() {
        GlobalSettings settings = currentGlobalSettings();
        String configured = settings != null ? settings.getAiCodeTextDefaultLanguage() : null;
        return AiLanguageSupport.isAutomatic(configured)
            ? AiLanguageSupport.AUTO_CODE
            : AiLanguageSupport.resolveFallbackLanguageCode(configured);
    }

    private boolean saveAiCodeTextLanguagePreference(String languageCode) {
        GlobalSettings settings = null;
        String previousLanguageCode = null;
        try {
            KorTTYApplication application = KorTTYApplication.getInstance();
            settings = application.getGlobalSettingsManager().getSettings();
            if (settings != null) {
                previousLanguageCode = settings.getAiCodeTextDefaultLanguage();
                settings.setAiCodeTextDefaultLanguage(languageCode);
                application.getGlobalSettingsManager().save();
                return true;
            }
        } catch (Exception e) {
            if (settings != null) {
                settings.setAiCodeTextDefaultLanguage(previousLanguageCode);
            }
            logger.debug("Could not persist the snippet AI text language", e);
        }
        return false;
    }

    // ---- AI skill picker (explicit allowlist for every AI-code run) -------------------------------

    private HBox buildAiSkillsRow() {
        aiSkillsMenuButton = new MenuButton();
        aiSkillsMenuButton.setTooltip(new Tooltip(I18n.get("snippets.ai.skills.picker.tooltip")));
        aiSkillsMenuButton.setOnShowing(event -> {
            if (!aiSkillsUserEdited) {
                autoDetectAiSkills();
            }
            rebuildAiSkillMenuItems();
        });
        updateAiSkillsButtonText();
        HBox row = new HBox(8, new Label(I18n.get("snippets.ai.skills.picker.label")), aiSkillsMenuButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Selects the natural language the AI read out of the snippet's comments and printed output.
     * A language korTTY has no translation for is still offered — {@code findOption} synthesizes an
     * entry for an unknown code — so a Swedish script can be marked as Swedish. This only preselects
     * the picker for this editor; it never overwrites the stored default, which stays the user's
     * explicit choice via "Remember as default".
     */
    private void applyDetectedTextLanguage(String textLanguageCode, boolean overwriteExisting) {
        if (!shouldApplyDetectedTextLanguage(overwriteExisting, aiTextLanguageUserEdited)
            || textLanguageCode == null
            || textLanguageCode.isBlank()
            || aiCodeTextLanguageCombo == null) {
            return;
        }
        AiLanguageSupport.LanguageOption detected =
            AiLanguageSupport.findOption(aiCodeTextLanguageCombo.getItems(), textLanguageCode);
        if (detected == null) {
            return;
        }
        if (!aiCodeTextLanguageCombo.getItems().contains(detected)) {
            aiCodeTextLanguageCombo.getItems().add(detected);
        }
        programmaticAiTextLanguageUpdate = true;
        try {
            aiCodeTextLanguageCombo.getSelectionModel().select(detected);
            aiCodeTextLanguageCode = AiLanguageSupport.resolveFallbackLanguageCode(detected.code());
        } finally {
            programmaticAiTextLanguageUpdate = false;
        }
    }

    static boolean shouldApplyDetectedTextLanguage(boolean overwriteExisting, boolean userEdited) {
        return overwriteExisting || !userEdited;
    }

    /** The picker only applies where the {@link MainWindow}-backed assist can enforce its selection. */
    private boolean aiSkillPickerShouldShow() {
        if (aiAssist == null || aiAssist.runtimeOptions() == null || !profileSwitchingSupported()) {
            return false;
        }
        return !enabledAiSkills().isEmpty();
    }

    private List<de.kortty.model.AiSkill> enabledAiSkills() {
        GlobalSettings settings = currentGlobalSettings();
        if (settings == null || !settings.isAiSkillsEnabled() || settings.getAiSkills() == null) {
            return List.of();
        }
        List<de.kortty.model.AiSkill> result = new ArrayList<>();
        for (de.kortty.model.AiSkill skill : settings.getAiSkills()) {
            if (skill != null && skill.isEnabled() && !skill.isHidden() && skill.getId() != null
                && skill.getContent() != null && !skill.getContent().isBlank()) {
                result.add(skill);
            }
        }
        return result;
    }

    private void rebuildAiSkillMenuItems() {
        if (aiSkillsMenuButton == null) {
            return;
        }
        aiSkillsMenuButton.getItems().clear();
        List<de.kortty.model.AiSkill> skills = enabledAiSkills();
        if (skills.isEmpty()) {
            CheckMenuItem none = new CheckMenuItem(I18n.get("snippets.ai.skills.picker.none"));
            none.setDisable(true);
            aiSkillsMenuButton.getItems().add(none);
            return;
        }
        for (de.kortty.model.AiSkill skill : skills) {
            String id = skill.getId();
            String label = skill.getName() != null && !skill.getName().isBlank() ? skill.getName() : id;
            CheckMenuItem item = new CheckMenuItem(label);
            item.setSelected(selectedAiSkillIds.contains(id));
            item.setOnAction(event -> {
                if (item.isSelected()) {
                    selectedAiSkillIds.add(id);
                } else {
                    selectedAiSkillIds.remove(id);
                }
                aiSkillsUserEdited = true;
                applyForcedAiSkills();
                updateAiSkillsButtonText();
            });
            aiSkillsMenuButton.getItems().add(item);
        }
    }

    /** Pre-ticks skills relevant to the current snippet (language + content), until the user edits the set. */
    private void autoDetectAiSkills() {
        if (!aiSkillPickerShouldShow() || aiSkillsUserEdited) {
            return;
        }
        List<de.kortty.model.AiSkill> skills = enabledAiSkills();
        String content = contentArea != null ? contentArea.getText() : null;
        if (skills.isEmpty() || content == null || content.isBlank()) {
            return;
        }
        Set<String> relevant = new LinkedHashSet<>();
        try {
            // Force relevance scoring on (regardless of the global auto-detection setting) so the pre-tick
            // reflects the snippet; explicitly selected skills bypass the skill target at request time.
            AiSkillRelevanceSelector selector = new AiSkillRelevanceSelector(true, true, skills);
            String lang = languageCombo != null ? languageCombo.getValue() : null;
            AiRequest chatRequest = new AiRequest(
                AiAction.REVIEW_SNIPPET_CODE, content, null, resolveAiTextFallbackLanguageCode(), lang);
            List<de.kortty.model.AiSkill> selected = preferDeclaredSnippetLanguageSkills(
                skills, selector.selectChatSkillsLocal(chatRequest), lang);
            for (de.kortty.model.AiSkill skill : selected) {
                if (skill.getId() != null) {
                    relevant.add(skill.getId());
                }
            }
        } catch (Exception ignored) {
            // Relevance detection must never block editing.
        }
        selectedAiSkillIds.clear();
        selectedAiSkillIds.addAll(relevant);
        applyForcedAiSkills();
        updateAiSkillsButtonText();
    }

    /** Prefer a skill that explicitly names the editor's declared language over source-token collisions. */
    static List<de.kortty.model.AiSkill> preferDeclaredSnippetLanguageSkills(
        List<de.kortty.model.AiSkill> available,
        List<de.kortty.model.AiSkill> fallbackSelection,
        String snippetLanguage) {

        List<de.kortty.model.AiSkill> fallback = fallbackSelection != null
            ? List.copyOf(fallbackSelection)
            : List.of();
        if (available == null || available.isEmpty() || snippetLanguage == null || snippetLanguage.isBlank()) {
            return fallback;
        }
        String language = SnippetLanguageSupport.normalizeSnippetLanguage(snippetLanguage);
        List<de.kortty.model.AiSkill> exact = available.stream()
            .filter(skill -> skill != null && (containsIgnoreCase(skill.getBuiltinTopics(), language)
                || containsIgnoreCase(skill.getTags(), language)))
            .limit(MAX_AUTO_SELECTED_SNIPPET_SKILLS)
            .toList();
        return exact.isEmpty() ? fallback : exact;
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values != null && expected != null
            && values.stream().anyMatch(value -> value != null && value.equalsIgnoreCase(expected));
    }

    private void applyForcedAiSkills() {
        if (aiAssist != null && aiAssist.runtimeOptions() != null) {
            aiAssist.runtimeOptions().setForcedSkillIds(selectedAiSkillIds);
        }
    }

    private boolean hasForcedAiSkills() {
        return aiAssist != null && aiAssist.runtimeOptions() != null
            && !aiAssist.runtimeOptions().forcedSkillIds().isEmpty();
    }

    private void updateAiSkillsButtonText() {
        if (aiSkillsMenuButton != null) {
            aiSkillsMenuButton.setText(I18n.get("snippets.ai.skills.picker.count", selectedAiSkillIds.size()));
        }
    }

    /**
     * The AI-skill context handed to the code-analysis dialog so it can show which skills were included and
     * let the user change them. The dialog's edits flow back into {@link #selectedAiSkillIds} and the shared
     * runtime options here, so the next re-run picks them up. Returns {@code null} when the skill picker does
     * not apply (no skills, or a profile that cannot enforce the selection).
     */
    private SnippetAnalysisPanel.SkillContext buildAnalysisSkillContext() {
        if (!aiSkillPickerShouldShow()) {
            return null;
        }
        return new SnippetAnalysisPanel.SkillContext(
            enabledAiSkills(),
            new LinkedHashSet<>(selectedAiSkillIds),
            !aiSkillsUserEdited,
            ids -> {
                selectedAiSkillIds.clear();
                selectedAiSkillIds.addAll(ids);
                aiSkillsUserEdited = true;
                applyForcedAiSkills();
                updateAiSkillsButtonText();
            });
    }

    // ---- Custom code languages -------------------------------------------------------------------

    /**
     * The code languages the user added on top of {@link #LANGUAGES}. Unknown language tokens are
     * already tolerated everywhere downstream — {@code normalizeSnippetLanguage} passes them through
     * and Monaco falls back to plain text — so no extra validation is needed beyond the token shape.
     */
    private static List<String> loadCustomCodeLanguages() {
        GlobalSettings settings = currentGlobalSettings();
        if (settings == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String language : settings.getCustomSnippetCodeLanguages()) {
            String normalized = normalizeCustomCodeLanguage(language);
            if (normalized != null && !LANGUAGES.contains(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /** Returns the language token, or {@code null} when the input is not a usable language name. */
    private static String normalizeCustomCodeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String candidate = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        return candidate.matches("[a-z0-9][a-z0-9+._-]*") ? candidate : null;
    }

    private void promptForCustomCodeLanguage() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        dialog.setTitle(I18n.get("snippets.codeLanguage.add.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(I18n.get("snippets.codeLanguage.add.prompt"));
        DialogThemeHelper.applyTheme(dialog);
        String entered = dialog.showAndWait().orElse(null);
        if (entered == null) {
            return;
        }
        String language = normalizeCustomCodeLanguage(entered);
        if (language == null) {
            setStatus(I18n.get("snippets.codeLanguage.add.invalid"));
            return;
        }
        selectCodeLanguage(language);
        if (persistCustomCodeLanguage(language)) {
            setStatus(I18n.get("snippets.codeLanguage.added", language));
        }
    }

    /** Selects a language in the combo, adding it to the item list first when it is not offered yet. */
    private void selectCodeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return;
        }
        if (!languageCombo.getItems().contains(language)) {
            languageCombo.getItems().add(language);
        }
        languageCombo.setValue(language);
    }

    /** @return {@code true} when the language was newly stored for future snippet editors. */
    private boolean persistCustomCodeLanguage(String language) {
        if (LANGUAGES.contains(language)) {
            return false;
        }
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager != null ? manager.getSettings() : null;
            if (settings == null || settings.getCustomSnippetCodeLanguages().contains(language)) {
                return false;
            }
            List<String> languages = new ArrayList<>(settings.getCustomSnippetCodeLanguages());
            languages.add(language);
            settings.setCustomSnippetCodeLanguages(languages);
            manager.save();
            return true;
        } catch (Exception e) {
            logger.warn("Could not store the custom snippet code language", e);
            return false;
        }
    }

    private static GlobalSettings currentGlobalSettings() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasCompletionProvider() {
        return aiAssist != null && aiAssist.completionProvider() != null;
    }

    private boolean hasCodeReviewProvider() {
        return aiAssist != null && aiAssist.codeReviewProvider() != null;
    }

    private boolean hasCodeAnalysisProviders() {
        return aiAssist != null
            && aiAssist.codeAnalysisProvider() != null
            && aiAssist.improvementFixProvider() != null
            && aiAssist.diagramProvider() != null;
    }

    private boolean hasCodeImprovementProvider() {
        return aiAssist != null && aiAssist.codeImprovementProvider() != null;
    }

    private boolean hasLanguageMigrationProvider() {
        return aiAssist != null && aiAssist.languageMigrationProvider() != null;
    }

    private boolean hasCodeAssistantProvider() {
        return aiAssist != null && aiAssist.codeAssistantProvider() != null;
    }

    private boolean hasSecurityProviders() {
        return aiAssist != null && aiAssist.securityReportProvider() != null && aiAssist.securityFixProvider() != null;
    }

    private boolean hasOneLinerProvider() {
        return aiAssist != null && aiAssist.oneLinerProvider() != null;
    }

    private boolean hasDiagramProvider() {
        return aiAssist != null && aiAssist.diagramProvider() != null;
    }

    private boolean isAdditionalInstructionsEnabled() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return settings != null && settings.isAiSnippetEditorAdditionalInstructionsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private int configuredAlternativeSolutionCount() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return settings != null ? settings.getAiSnippetAlternativeSolutionCount() : 3;
        } catch (Exception e) {
            return 3;
        }
    }

    private String resolveAiTextFallbackLanguageCode() {
        return AiLanguageSupport.resolveFallbackLanguageCode(aiCodeTextLanguageCode);
    }

    /** Analysis reports and their diagrams follow the application UI, independently of code-text output. */
    private String resolveAnalysisLanguageCode() {
        return AiLanguageSupport.resolveFallbackLanguageCode(null);
    }

    private String additionalInstructions() {
        if (!isAdditionalInstructionsEnabled()) {
            return null;
        }
        String instructions = aiAdditionalInstructionsArea.getText();
        return instructions != null && !instructions.isBlank() ? instructions.trim() : null;
    }

    /**
     * Requires explicit one-time confirmation only for auto-completion because it can send snippet
     * data continuously. Other AI actions are user-initiated clicks and are treated as implicitly
     * acknowledged. When {@code autoCompletion} is true, {@code autoCompletionWarningAccepted}
     * controls whether the warning alert is shown; the alert is attached with initAlertOwner and
     * only ButtonType.OK accepts it.
     */
    private boolean ensureSnippetAiDataNoticeAccepted(boolean autoCompletion) {
        if (autoCompletion && !autoCompletionWarningAccepted) {
            Alert autoAlert = new Alert(Alert.AlertType.CONFIRMATION);
            autoAlert.setTitle(I18n.get("snippets.ai.autocomplete.warning.title"));
            autoAlert.setHeaderText(I18n.get("snippets.ai.autocomplete.warning.header"));
            autoAlert.setContentText(I18n.get("snippets.ai.autocomplete.warning.content"));
            initAlertOwner(autoAlert);
            Optional<ButtonType> response = autoAlert.showAndWait();
            if (response.isEmpty() || response.get() != ButtonType.OK) {
                return false;
            }
            autoCompletionWarningAccepted = true;
        }
        return true;
    }

    private void initAlertOwner(Alert alert) {
        if (alert != null && getDialogPane().getScene() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
    }

    private void runSelectionCorrection() {
        if (aiAssist == null || aiAssist.selectionCorrectionProvider() == null) {
            return;
        }
        SelectionTextTransformTarget target = captureSelectionTextTransformTarget();
        if (target == null) {
            return;
        }
        runSelectionTextTransform(
            aiAssist.selectionCorrectionProvider(),
            target,
            null,
            I18n.get("snippets.ai.correctingSelection"),
            I18n.get("snippets.ai.selectionCorrected"),
            I18n.get("snippets.ai.selectionCorrectionFailed"),
            I18n.get("snippets.ai.toggle.action.correct"));
    }

    private void runSelectionTranslation() {
        if (aiAssist == null || aiAssist.selectionTranslationProvider() == null) {
            return;
        }
        SelectionTextTransformTarget target = captureSelectionTextTransformTarget();
        if (target == null) {
            return;
        }
        AiLanguageSupport.LanguageOption targetLanguage = promptTranslationLanguage();
        if (targetLanguage == null) {
            return;
        }
        runSelectionTextTransform(
            aiAssist.selectionTranslationProvider(),
            target,
            targetLanguage.code(),
            I18n.get("snippets.ai.translatingSelection"),
            I18n.get("snippets.ai.selectionTranslated"),
            I18n.get("snippets.ai.selectionTranslationFailed"),
            I18n.get("snippets.ai.toggle.action.translate"));
    }

    private void runSelectionTextTransform(
        SelectionTextTransformProvider provider,
        SelectionTextTransformTarget target,
        String targetLanguageCode,
        String runningStatus,
        String successStatus,
        String failedStatus,
        String actionLabel) {

        if (provider == null || target == null || aiActionBlocked()) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String selectedText = target.selectedText();
        int selectionStart = target.selectionStart();
        int selectionEnd = target.selectionEnd();
        String fallbackLanguageCode = resolveAiTextFallbackLanguageCode();
        String instructions = additionalInstructions();
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return provider.transform(new SelectionTextTransformRequest(
                    target.fullContent(),
                    target.snippetLanguage(),
                    selectedText,
                    selectionStart,
                    selectionEnd,
                    fallbackLanguageCode,
                    targetLanguageCode,
                    instructions));
            }
        };
        beginSnippetAiAction(task, retryOnSelection(() -> runSelectionTextTransform(provider,
            captureSelectionTextTransformTarget(), targetLanguageCode, runningStatus, successStatus, failedStatus,
            actionLabel)));
        task.setOnRunning(event -> {
            showSnippetAiHint(runningStatus);
            setStatus(runningStatus);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            String replacement = task.getValue();
            if (replacement == null || replacement.equals(selectedText)) {
                setStatus(successStatus);
                finishSnippetAiAction(task);
                return;
            }
            applyAiContentChange(selectionStart, selectionEnd, replacement, actionLabel);
            setStatus(successStatus);
            finishSnippetAiAction(task);
        });
        task.setOnFailed(event -> handleSnippetAiActionFailure(task, failedStatus));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-selection-transform");
    }

    private SelectionTextTransformTarget captureSelectionTextTransformTarget() {
        contentArea.syncFromEditor();
        IndexRange range = contentArea.getSelection();
        String fullContent = contentArea.getText() != null ? contentArea.getText() : "";
        if (range == null || range.getLength() <= 0) {
            return null;
        }
        int selectionStart = Math.max(0, Math.min(range.getStart(), fullContent.length()));
        int selectionEnd = Math.max(selectionStart, Math.min(range.getEnd(), fullContent.length()));
        if (selectionStart == selectionEnd) {
            return null;
        }
        String snippetLanguage = languageCombo.getValue();
        List<SnippetAiTextSupport.EditableTextSegment> segments =
            SnippetAiTextSupport.extractEditableSegments(
                fullContent,
                selectionStart,
                selectionEnd,
                snippetLanguage);
        if (segments.isEmpty()) {
            setStatus(I18n.get("snippets.ai.noTextSegments"));
            return null;
        }
        return new SelectionTextTransformTarget(
            fullContent,
            snippetLanguage,
            fullContent.substring(selectionStart, selectionEnd),
            selectionStart,
            selectionEnd);
    }

    private void runSnippetDescription() {
        runSnippetDescription(null);
    }

    private void runSnippetDescription(String aiProfileId) {
        if (aiAssist == null || aiAssist.snippetDescriptionProvider() == null || aiActionBlocked()) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        boolean wholeSnippet = selection == null || selection.getLength() <= 0;
        String selectedText = wholeSnippet ? fullContent : contentArea.getSelectedText();
        int selectionStart = wholeSnippet ? 0 : selection.getStart();
        int selectionEnd = wholeSnippet ? 0 : selection.getEnd();
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return aiAssist.snippetDescriptionProvider().describe(new SnippetDescriptionRequest(
                    fullContent,
                    languageCombo.getValue(),
                    selectedText,
                    wholeSnippet,
                    resolveAiTextFallbackLanguageCode(),
                    additionalInstructions(),
                    aiProfileId));
            }
        };
        beginSnippetAiAction(task, retryOnSelection(() -> runSnippetDescription(aiProfileId)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.description.generating"));
            setStatus(I18n.get("snippets.ai.description.generating"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            String description = task.getValue();
            finishSnippetAiAction(task);
            if (description == null || description.isBlank()) {
                setStatus(I18n.get("snippets.ai.description.generateFailed"));
                return;
            }
            int insertOffset = wholeSnippet ? 0 : startOfLine(selectionStart);
            String indentation = wholeSnippet
                ? SnippetAiTextSupport.findLineIndentation(fullContent, firstContentOffset(fullContent))
                : SnippetAiTextSupport.findLineIndentation(fullContent, selectionStart);
            // Non-modal: the editor stays editable while the description is open, so it is only
            // inserted at the computed line while the content is still what it was written for.
            SnippetDescriptionDialog dialog = new SnippetDescriptionDialog(
                childWindowOwner(),
                description,
                languageCombo.getValue(),
                indentation,
                text -> applyFromResultWindow(fullContent, text,
                    () -> insertTechnicalDescription(text, insertOffset)),
                aiProfileId,
                profileSwitchingSupported() ? this::runSnippetDescription : null);
            showChildWindow(dialog);
            setStatus(I18n.get("snippets.ai.description.generated"));
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.description.generateFailed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-description");
    }

    private void runAlternativeSolutions() {
        if (!hasAlternativeSolutionProvider()) {
            return;
        }
        Dialog<?> open = openChildWindow(AlternativeSnippetSolutionsDialog.class);
        if (open != null) {
            // It generates on demand itself; a second window would only run the same requests twice.
            Window window = open.getDialogPane().getScene() != null ? open.getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                stage.toFront();
                stage.requestFocus();
            }
            return;
        }
        if (aiActionBlocked()) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        contentArea.syncFromEditor();
        IndexRange selection = contentArea.getSelection();
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        boolean hasSelection = selection != null && selection.getLength() > 0;
        int replacementStart = hasSelection ? selection.getStart() : 0;
        int replacementEnd = hasSelection ? selection.getEnd() : fullContent.length();
        String targetText = hasSelection ? contentArea.getSelectedText() : fullContent;
        AlternativeSnippetSolutionsDialog dialog = new AlternativeSnippetSolutionsDialog(
            childWindowOwner(),
            languageCombo.getValue(),
            (additionalInstructions, aiProfileId) -> aiAssist.alternativeSolutionsProvider().generate(new AlternativeSolutionsRequest(
                fullContent,
                languageCombo.getValue(),
                targetText,
                !hasSelection,
                resolveAiTextFallbackLanguageCode(),
                additionalInstructions,
                configuredAlternativeSolutionCount(),
                aiProfileId)),
            profileSwitchingSupported(),
            null);
        // Non-modal: the chosen solution arrives when the window closes, and only replaces the range
        // it was generated for while the content is still what it was generated from.
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, hidden -> {
            SnippetAiResponseSupport.AlternativeSolution solution = dialog.getResult();
            if (solution == null || editorClosed) {
                return;
            }
            applyFromResultWindow(fullContent, solution.code(), () -> {
                applyAiContentChange(replacementStart, replacementEnd, solution.code(),
                    I18n.get("snippets.ai.toggle.action.alternative"));
                setStatus(I18n.get("snippets.ai.alternatives.applied"));
            });
        });
        showChildWindow(dialog);
    }

    private void handleAutoCompletionToggle() {
        if (autoCompleteItem.isSelected() && !ensureSnippetAiDataNoticeAccepted(true)) {
            autoCompleteItem.setSelected(false);
            return;
        }
        if (autoCompleteItem.isSelected()) {
            scheduleAutoCompletion();
            setStatus(I18n.get("snippets.ai.autocomplete.enabled"));
        } else {
            autoCompletionDelay.stop();
            cancelCompletionRequest();
            contentArea.clearGhostCompletions();
            setStatus(I18n.get("snippets.ai.autocomplete.disabled"));
        }
    }

    /**
     * Arms the ghost-text timer. A no-op while a suggest list is open: the caret listener fires on
     * every filter keystroke, and a ghost request started then would supersede the list's own AI
     * request only to have its result discarded by the page because the list is still open.
     */
    private void scheduleAutoCompletion() {
        if (autoCompleteItem == null || !autoCompleteItem.isSelected() || listSessionId >= 0 || isAnyAiTaskRunning()) {
            return;
        }
        String content = contentArea.getText();
        if (content == null || content.isBlank() || !hasCompletionProvider()) {
            return;
        }
        autoCompletionDelay.playFromStart();
    }

    /** The ghost-text timer fired: ask the model for a few continuations at the end of the caret line. */
    private void requestGhostCompletion() {
        if (autoCompleteItem == null || !autoCompleteItem.isSelected()) {
            return;
        }
        // The text mirror lags behind the editor's typing; the request must see the caret and the
        // text exactly as the editor has them.
        contentArea.syncFromEditor();
        String content = safeContentText();
        int caret = Math.max(0, Math.min(contentArea.getCaretPosition(), content.length()));
        int lineStart = content.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = content.indexOf('\n', caret);
        String lineBefore = content.substring(lineStart, caret);
        String lineAfter = content.substring(caret, lineEnd >= 0 ? lineEnd : content.length());
        if (!ghostRequestAllowed(true, listSessionId >= 0, isAnyAiTaskRunning(), hasCompletionProvider(),
            lineBefore, lineAfter)) {
            return;
        }
        String key = content + "::" + caret;
        if (key.equals(lastAutoCompletionKey)) {
            return;
        }
        lastAutoCompletionKey = key;
        long requestId = nextGhostRequestId++;
        startCompletionTask(requestId, content, caret, GHOST_AI_CANDIDATES, null, result -> {
            if (!content.equals(contentArea.getText()) || caret != contentArea.getCaretPosition()) {
                setCompletionStatus(I18n.get("snippets.ai.complete.discarded"));
                return;
            }
            contentArea.pushGhostCompletions(
                MonacoCompletionPayloads.ghost(requestId, caret, content.length(), result));
            setCompletionStatus(I18n.get("snippets.ai.complete.ready"));
        }, true);
    }

    /**
     * Whether the ghost-text timer may fire a request: the switch is on, no suggest list is open, no
     * heavy AI action owns the flow, a provider exists, and the caret sits at the end of a non-empty
     * line (the same rule Shift+TAB uses to open the list).
     */
    static boolean ghostRequestAllowed(boolean autoSelected, boolean listSessionActive, boolean otherAiTaskRunning,
                                       boolean hasProvider, String lineBefore, String lineAfter) {
        return autoSelected && !listSessionActive && !otherAiTaskRunning && hasProvider
            && SnippetCompletionSupport.shiftTabShouldOpenList(lineBefore, lineAfter);
    }

    /**
     * What one pass over the snippet yields for a list request: the caret context, the local rows
     * and the "Cursor context"/"Known symbols" paragraph of the AI prompt, all from the same
     * language detection, classification and symbol harvest — a Shift+TAB on a large snippet scans
     * it once on the FX thread, not once per consumer.
     */
    record LocalCompletion(SnippetCompletionSupport.Context context,
                           List<SnippetCompletionSupport.Candidate> candidates, String localContext) {

        static final LocalCompletion NONE = new LocalCompletion(null, List.of(), "");
    }

    /**
     * The rows of {@link SnippetCompletionSupport#localCandidates(String, String, int, int, boolean)}
     * for {@code !hasLanguageService} and the paragraph of
     * {@link SnippetCompletionSupport#localContext(String, String, int)}, computed together.
     * {@code hasLanguageService} drops the plain identifiers from the rows only: Monaco's own
     * language services add their word entries for javascript/typescript/json/css/html.
     */
    static LocalCompletion localCompletion(String declaredLanguage, String text, int caretOffset,
                                           boolean hasLanguageService) {
        String language = SnippetCompletionSupport.effectiveLanguage(declaredLanguage, text, caretOffset);
        SnippetCompletionSupport.Context ctx = SnippetCompletionSupport.classify(language, text, caretOffset);
        SnippetCompletionSupport.Symbols symbols = SnippetCompletionSupport.harvest(language, text, caretOffset);
        List<SnippetCompletionSupport.Candidate> candidates = SnippetCompletionSupport.candidates(
            ctx, hasLanguageService ? symbols.withoutIdentifiers() : symbols, LOCAL_MAX_ITEMS);
        return new LocalCompletion(ctx, candidates, SnippetCompletionSupport.localContext(ctx, symbols));
    }

    /**
     * The suggest list opened on the page. Local candidates resolve it within this pulse; when a
     * provider is configured the AI candidates follow into the open list — every list (Shift+TAB,
     * Ctrl+Space, the menu) is a deliberate action, so no data notice is needed. Progress shows in
     * the status line only: the hint bar would resize the WebView under the open widget.
     */
    private void handleCompletionRequested(long requestId, String requestJson) {
        JsonObject request = parseJsonObject(requestJson);
        String text = jsonString(request, "text", null);
        if (text == null) {
            contentArea.syncFromEditor();
            text = safeContentText();
        }
        int caret = Math.max(0, Math.min(jsonInt(request, "caretOffset", text.length()), text.length()));
        boolean hasLanguageService = jsonBoolean(request, "hasLanguageService", false);
        listSessionId = requestId;
        activeCompletionRequestId = requestId;
        cancelCompletionRequest();
        autoCompletionDelay.stop();
        LocalCompletion local;
        try {
            local = localCompletion(languageCombo.getValue(), text, caret, hasLanguageService);
        } catch (RuntimeException e) {
            logger.warn("Local completion candidates failed", e);
            local = LocalCompletion.NONE;
        }
        boolean aiFollows = hasCompletionProvider() && !isAnyAiTaskRunning() && !text.isBlank();
        contentArea.pushCompletions(MonacoCompletionPayloads.list(
            requestId, "local", local.candidates(), completionKindLabels, aiFollows));
        if (!aiFollows) {
            return;
        }
        LocalCompletion scanned = local;
        startCompletionTask(requestId, text, caret, LIST_AI_CANDIDATES, scanned.localContext(), result -> {
            List<SnippetCompletionSupport.Candidate> ai = SnippetCompletionSupport.aiCandidates(
                scanned.context(), caret, scanned.candidates(), result,
                completionKindLabels.get(SnippetCompletionSupport.CandidateKind.AI));
            if (ai.isEmpty()) {
                settlePendingListAi(requestId);
                setCompletionStatus(I18n.get("snippets.ai.complete.empty"));
                return;
            }
            contentArea.pushCompletions(MonacoCompletionPayloads.list(requestId, "ai", ai, completionKindLabels));
            setCompletionStatus(I18n.get("snippets.ai.complete.list.ready", ai.size()));
        }, false);
    }

    /**
     * The suggest list closed (Esc, blur, accept, ...). Monaco cancels the list before it inserts an
     * accepted item, so this precedes the text change and the accept report of that item.
     */
    private void handleCompletionListClosed(long requestId) {
        listSessionId = -1;
        if (requestId == activeCompletionRequestId) {
            cancelCompletionRequest();
            activeCompletionRequestId = -1;
        }
        clearCompletionStatus();
        scheduleAutoCompletion();
    }

    /**
     * An AI list entry or ghost text was inserted. Deliberately not gated on the active request id:
     * the list has already closed (and cancelled its request) by the time the accept arrives. The
     * before/after texts come from the editor mirror and the inserted text from the model, so the
     * ↺ toggle also works for multi-line entries; on a mismatch there is simply no ↺ entry.
     */
    private void handleCompletionAccepted(String acceptedJson) {
        JsonObject accepted = parseJsonObject(acceptedJson);
        String source = jsonString(accepted, "source", "");
        if (!"ai".equals(source) && !"ghost".equals(source)) {
            return;
        }
        String before = previousEditorText != null ? previousEditorText : "";
        String after = safeContentText();
        int start = jsonInt(accepted, "start", -1);
        String insertedText = jsonString(accepted, "insertedText", "");
        int valueLength = jsonInt(accepted, "valueLength", -1);
        int typedLength = completionAcceptTypedLength(before, after, start, insertedText, valueLength);
        if (typedLength >= 0) {
            storeLastAiChangeSnapshot(
                I18n.get("snippets.ai.toggle.action.complete"),
                before,
                after,
                start,
                start + typedLength,
                start,
                start + insertedText.length());
        } else {
            logger.debug("Accepted {} completion does not match the editor change (start={}, inserted={} chars)",
                source, start, insertedText.length());
        }
        setCompletionStatus(I18n.get("snippets.ai.complete.inserted"));
        trackSnippetAiAction("code_complete_accepted");
    }

    /** Whether Monaco's accept report fits the editor's before/after texts (see {@link #completionAcceptTypedLength}). */
    static boolean completionAcceptMatches(String before, String after, int start, String insertedText, int valueLength) {
        return completionAcceptTypedLength(before, after, start, insertedText, valueLength) >= 0;
    }

    /**
     * Checks that {@code after} is {@code before} with the typed token at {@code start} replaced by
     * {@code insertedText}, and returns that token's length, or -1 when the texts do not fit together
     * (a stale mirror, another edit in between, an offset from a different model state). Line endings
     * are normalized to LF for the comparison; {@code start} and {@code valueLength} are model offsets
     * and are read against the raw {@code after} text.
     */
    static int completionAcceptTypedLength(String before, String after, int start, String insertedText, int valueLength) {
        if (before == null || after == null || insertedText == null || insertedText.isEmpty()) {
            return -1;
        }
        if (start < 0 || start > after.length() || (valueLength >= 0 && valueLength != after.length())) {
            return -1;
        }
        int normalizedStart = start - countCrlf(after, start);
        String beforeText = normalizeEol(before);
        String afterText = normalizeEol(after);
        String inserted = normalizeEol(insertedText);
        if (!afterText.startsWith(inserted, normalizedStart)) {
            return -1;
        }
        if (beforeText.length() < normalizedStart || !afterText.regionMatches(0, beforeText, 0, normalizedStart)) {
            return -1;
        }
        int typed = beforeText.length() + inserted.length() - afterText.length();
        if (typed < 0 || normalizedStart + typed > beforeText.length()) {
            return -1;
        }
        String afterTail = afterText.substring(normalizedStart + inserted.length());
        String beforeTail = beforeText.substring(normalizedStart + typed);
        return afterTail.equals(beforeTail) ? typed : -1;
    }

    /**
     * Runs one AI completion request in its own task: it blocks none of the other AI actions (they
     * take over through {@link #beginSnippetAiAction}), a new request supersedes a running one, and
     * the result is delivered only while {@code requestId} is still the wanted one. {@code localContext}
     * is the prompt's cursor-context paragraph when the caller has it already (the list request), or
     * null to derive it here (ghost text). {@code showHintBar} is true for ghost text only; list
     * requests report in the status line so the WebView is not resized under the open widget.
     */
    private void startCompletionTask(long requestId, String content, int caretOffset, int maxCandidates,
                                     String precomputedLocalContext,
                                     Consumer<List<SnippetAiResponseSupport.CompletionSuggestion>> onResult,
                                     boolean showHintBar) {
        if (!hasCompletionProvider() || content == null || content.isBlank() || isAnyAiTaskRunning()) {
            return;
        }
        cancelCompletionRequest();
        activeCompletionRequestId = requestId;
        // Completion fires while typing, so it never interrupts: an undetectable language simply
        // leaves this one request without an explicit contract.
        applyCodeTextLanguage(false);
        String language = languageCombo.getValue();
        String localContext = precomputedLocalContext;
        if (localContext == null) {
            try {
                localContext = SnippetCompletionSupport.localContext(language, content, caretOffset);
            } catch (RuntimeException e) {
                logger.warn("Local completion context failed", e);
                localContext = "";
            }
        }
        CompletionRequest request = new CompletionRequest(
            content,
            language,
            caretOffset,
            resolveAiTextFallbackLanguageCode(),
            additionalInstructions(),
            maxCandidates,
            localContext);
        CompletionProvider provider = aiAssist.completionProvider();
        Task<List<SnippetAiResponseSupport.CompletionSuggestion>> task = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.CompletionSuggestion> call() throws Exception {
                return provider.complete(request);
            }
        };
        completionTask = task;
        String runningStatus = I18n.get(showHintBar ? "snippets.ai.complete.running" : "snippets.ai.complete.list.running");
        task.setOnRunning(event -> {
            if (completionTask != task) {
                return;
            }
            if (showHintBar) {
                showSnippetAiHint(runningStatus, true);
            }
            setCompletionStatus(runningStatus);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            if (!finishCompletionTask(task) || requestId != activeCompletionRequestId) {
                return;
            }
            List<SnippetAiResponseSupport.CompletionSuggestion> result = task.getValue();
            if (!hasUsableSuggestion(result)) {
                setCompletionStatus(I18n.get("snippets.ai.complete.empty"));
                return;
            }
            onResult.accept(result);
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            if (failure != null) {
                logger.warn("Snippet AI completion failed (request {})", requestId, failure);
            }
            settlePendingListAi(requestId);
            if (finishCompletionTask(task) && requestId == activeCompletionRequestId) {
                setCompletionStatus(completionFailureStatus(failure));
            }
        });
        task.setOnCancelled(event -> {
            settlePendingListAi(requestId);
            finishCompletionTask(task);
        });
        completionTimeout.playFromStart();
        AiTaskRunner.start(task, "snippet-ai-complete-" + requestId);
        updateAiActionAvailability();
    }

    private static boolean hasUsableSuggestion(List<SnippetAiResponseSupport.CompletionSuggestion> suggestions) {
        if (suggestions == null) {
            return false;
        }
        for (SnippetAiResponseSupport.CompletionSuggestion suggestion : suggestions) {
            if (suggestion != null && suggestion.isUsable()) {
                return true;
            }
        }
        return false;
    }

    /** Clears {@code task} if it is the current request; false when it was already superseded or cancelled. */
    private boolean finishCompletionTask(Task<?> task) {
        if (completionTask != task) {
            return false;
        }
        completionTask = null;
        completionTimeout.stop();
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
        return true;
    }

    /** Cancels the running completion request, if any; true when there was one. */
    private boolean cancelCompletionRequest() {
        completionTimeout.stop();
        Task<?> task = completionTask;
        if (task == null) {
            return false;
        }
        completionTask = null;
        task.cancel(true);
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
        return true;
    }

    /**
     * Tells the page that no AI rows will follow for the list {@code requestId}: a list that opened
     * without local rows is kept in Monaco's loading state until then. Harmless for any other id.
     */
    private void settlePendingListAi(long requestId) {
        if (requestId == listSessionId) {
            contentArea.pushCompletions(MonacoCompletionPayloads.list(requestId, "ai", List.of(), completionKindLabels));
        }
    }

    /** A blocking socket read cannot be interrupted sharply; the timeout at least clears the UI. */
    private void handleCompletionTimeout() {
        if (cancelCompletionRequest()) {
            setCompletionStatus(I18n.get("snippets.ai.complete.timeout", COMPLETION_TIMEOUT_SECONDS));
        }
    }

    /** The status-line text for a failed completion request; completion never raises a dialog. */
    private String completionFailureStatus(Throwable failure) {
        if (isResponseStreamInterruptedFailure(failure)) {
            return I18n.get("snippets.ai.streamInterrupted");
        }
        if (isOutputTokenLimitFailure(failure)) {
            return I18n.get("snippets.ai.outputLimitReached");
        }
        String detail = failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
            ? failure.getMessage().strip()
            : null;
        return detail != null
            ? I18n.get("snippets.ai.actionFailed", shortenStatusMessage(detail))
            : I18n.get("snippets.ai.complete.failed");
    }

    /**
     * Every heavy AI action starts here: it takes the flow over from completion, so a debounced ghost
     * request cannot fire into the analysis and a visible ghost text does not linger over its result.
     */
    private void beginSnippetAiAction(Task<?> task, AiRetry retry) {
        cancelCompletionRequest();
        autoCompletionDelay.stop();
        contentArea.clearGhostCompletions();
        // A new run replaces an offered Retry: the bar shows what runs now.
        offeredAiRetry = null;
        offeredAiRetryText = null;
        snippetAiActionTask = task;
        if (retry != null) {
            aiRetries.put(task, retry);
        }
    }

    /** A completion status is transient: it is cleared again when its list closes. */
    private void setCompletionStatus(String message) {
        lastCompletionStatus = message;
        setStatus(message);
    }

    private void clearCompletionStatus() {
        if (lastCompletionStatus != null && statusLabel != null && lastCompletionStatus.equals(statusLabel.getText())) {
            setStatus("");
        }
        lastCompletionStatus = null;
    }

    private static Map<SnippetCompletionSupport.CandidateKind, String> completionKindLabels() {
        Map<SnippetCompletionSupport.CandidateKind, String> labels =
            new EnumMap<>(SnippetCompletionSupport.CandidateKind.class);
        labels.put(SnippetCompletionSupport.CandidateKind.ARRAY, I18n.get("snippets.ai.complete.kind.array"));
        labels.put(SnippetCompletionSupport.CandidateKind.HASH, I18n.get("snippets.ai.complete.kind.hash"));
        labels.put(SnippetCompletionSupport.CandidateKind.VARIABLE, I18n.get("snippets.ai.complete.kind.variable"));
        labels.put(SnippetCompletionSupport.CandidateKind.FUNCTION, I18n.get("snippets.ai.complete.kind.function"));
        labels.put(SnippetCompletionSupport.CandidateKind.IDIOM, I18n.get("snippets.ai.complete.kind.idiom"));
        labels.put(SnippetCompletionSupport.CandidateKind.TEXT, I18n.get("snippets.ai.complete.kind.text"));
        labels.put(SnippetCompletionSupport.CandidateKind.AI, I18n.get("snippets.ai.complete.aiDetail"));
        return labels;
    }

    private static JsonObject parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            logger.warn("Invalid completion payload from the editor page", e);
            return new JsonObject();
        }
    }

    private static String jsonString(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int jsonInt(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean jsonBoolean(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String normalizeEol(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The number of CRLF pairs that lie completely before {@code end}. */
    private static int countCrlf(String text, int end) {
        int count = 0;
        int limit = Math.min(end, text.length());
        for (int i = text.indexOf("\r\n"); i >= 0 && i + 1 < limit; i = text.indexOf("\r\n", i + 2)) {
            count++;
        }
        return count;
    }

    /**
     * The plain "Full code analysis" entry: opens the analysis panel on its "New analysis" chooser so the AI
     * profile can be picked first (see {@link SnippetAnalysisController#openStartPanel()}); with a single
     * profile it starts at once.
     *
     * <p>The rich "AI Code Review" (Full code analysis): the provider call returns the summary,
     * dependencies and categorized improvements, which are stored with the snippet the moment they
     * arrive and shown in this editor's analysis side panel; the Mermaid diagram is fetched by a
     * separate dedicated request (better diagram quality than a combined request, and the analysis
     * is visible while the diagram loads). See {@link SnippetAnalysisController}.
     */
    private void runCodeReview() {
        analysisController.openStartPanel();
    }

    /** "Full code analysis with profile": starts at once with the given profile and remembers it. */
    private void runCodeReview(String aiProfileId) {
        analysisController.startWithProfile(aiProfileId);
    }

    /** The "with profile" submenu; its entries are filled in whenever a parent menu is about to show. */
    private Menu buildReviewWithProfileMenu() {
        Menu menu = new Menu(aiActionLabel("snippets.ai.code.review.withProfile"));
        menu.setId("snippet-analysis-with-profile-menu");
        menu.setVisible(false);
        return menu;
    }

    private void refreshReviewWithProfileMenu(Menu menu) {
        List<SnippetAnalysisProfileSupport.Option> options = analysisController != null
            ? analysisController.profileOptions() : List.of();
        boolean offered = SnippetAnalysisProfileSupport.choiceOffered(options, profileSwitchingSupported());
        menu.setVisible(offered);
        if (!offered) {
            menu.getItems().clear();
            return;
        }
        List<MenuItem> items = new ArrayList<>();
        for (SnippetAnalysisProfileSupport.Option option : options) {
            MenuItem item = new MenuItem(SnippetAnalysisController.profileOptionLabel(option));
            item.setOnAction(e -> {
                trackSnippetAiAction("code_review");
                runCodeReview(option.id());
            });
            items.add(item);
        }
        menu.getItems().setAll(items);
    }

    /** Generates the analysis diagram (initial load and explicit Regenerate), with a local fallback on failure. */
    private CompletableFuture<SnippetDiagramView.DiagramSource> generateDiagramMermaid(
        String fullContent, String language, String fallback, String aiProfileId) {
        CompletableFuture<SnippetDiagramView.DiagramSource> future = new CompletableFuture<>();
        Task<SnippetDiagramView.DiagramSource> task = new Task<>() {
            @Override
            protected SnippetDiagramView.DiagramSource call() throws Exception {
                SnippetAiResponseSupport.MermaidDiagram diagram = null;
                String failure = null;
                String failureDetail = null;
                try {
                    diagram = aiAssist.diagramProvider() != null
                        ? aiAssist.diagramProvider().generate(
                            new DiagramRequest(fullContent, language, fallback, "", aiProfileId))
                        : null;
                } catch (Exception e) {
                    if (isCancelled() || e instanceof de.kortty.core.AiCancelledException) {
                        throw e;
                    }
                    failure = isOutputTokenLimitFailure(e)
                        ? I18n.get("snippets.ai.diagram.rejection.outputLimit")
                        : I18n.get("snippets.ai.diagram.rejection.requestFailed");
                    failureDetail = shortenStatusMessage(String.valueOf(e.getMessage()));
                    logger.warn("AI diagram generation failed; using the local Mermaid fallback", e);
                }
                if (isCancelled()) {
                    return null;
                }
                if (diagram != null && diagram.isUsable()) {
                    return new SnippetDiagramView.DiagramSource(diagram.mermaid(), fullContent, diagram.codeReferences());
                }
                // The fallback looks like a real diagram, so it is labelled: without the notice a
                // discarded AI answer was indistinguishable from a merely poor one.
                // The notice names the reason in a few localized words; the precise English
                // rejection sentence stays in the log and in the notice's tooltip.
                String detail = failure != null
                    ? failureDetail
                    : diagram != null ? diagram.rejectionReason() : null;
                if (failure == null && detail != null) {
                    logger.warn("AI diagram was rejected ({}); using the local Mermaid fallback", detail);
                }
                String shortReason = failure != null ? failure
                    : detail != null ? SnippetDiagramFallbackText.shortReason(detail) : null;
                String notice = shortReason != null
                    ? I18n.get("snippets.ai.analysis.diagram.fallback", shortReason)
                    : I18n.get("snippets.ai.analysis.diagram.fallback.generic");
                String mermaid = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(fullContent, language);
                return new SnippetDiagramView.DiagramSource(
                    mermaid, fullContent, List.of(), SnippetDiagramType.LOGICAL_STRUCTURE, notice, detail);
            }
        };
        task.setOnSucceeded(event -> future.complete(task.getValue()));
        task.setOnFailed(event -> future.completeExceptionally(task.getException()));
        task.setOnCancelled(event -> future.cancel(false));
        cancelTaskWhenDiagramFutureIsCancelled(future, task);
        AiTaskRunner.start(task, "snippet-analysis-diagram");
        return future;
    }

    /** Propagates viewer reload/close cancellation to the provider task that owns the HTTP call. */
    static void cancelTaskWhenDiagramFutureIsCancelled(
            CompletableFuture<?> future, java.util.concurrent.Future<?> task) {
        future.whenComplete((ignored, error) -> {
            if (future.isCancelled()) {
                task.cancel(true);
            }
        });
    }

    /**
     * Applies the user-selected analysis improvements + dependency suggestions (mirror of
     * {@link #runSecurityFixes}); the progress shows in the analysis panel and the result is
     * reviewed in the editor area before it replaces anything.
     */
    private void runImprovementFixes(SnippetAnalysisPanel.ApplySelection selection) {
        analysisController.apply(selection);
    }

    private void toggleAnalysisPanel() {
        if (analysisController != null) {
            analysisController.togglePanel();
        }
    }

    /** The analysis controller of this editor (tests, screenshots). */
    SnippetAnalysisController analysisController() {
        return analysisController;
    }

    /**
     * Whether the editor still holds exactly the text the AI worked from. Guards every full
     * replacement now that the review no longer freezes the rest of the application.
     */
    private boolean contentUnchangedSince(String originalContent) {
        String current = contentArea.getText() != null ? contentArea.getText() : "";
        return current.equals(originalContent != null ? originalContent : "");
    }

    /**
     * The window a dialog raised by the AI apply flow may block — never the main window. The
     * terminals live there, and the whole point of this flow being non-modal is that they keep
     * working while the AI runs and while its result is reviewed.
     */
    private Window aiFlowAlertOwner() {
        javafx.scene.Scene scene = getDialogPane().getScene();
        Window own = scene != null ? scene.getWindow() : null;
        return own != null ? own : resolveAlertOwner();
    }

    /**
     * How far a dialog raised by the AI flow blocks: only this editor's window, and nothing at all
     * while the editor is hosted in a tab — there the window is the main window with the terminals.
     */
    private Modality aiFlowAlertModality() {
        return isHostedInTab() || embedding != null ? Modality.NONE : Modality.WINDOW_MODAL;
    }

    /** True when an aborted staged apply left recoverable work and the abort was an interactive one. */
    static boolean shouldOfferImprovementApplyRecovery(
            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint,
            boolean silentCancel,
            boolean recoverySuppressed) {
        return checkpoint != null && checkpoint.completedStages() >= 1 && !silentCancel && !recoverySuppressed;
    }

    /** Resume is pointless when every stage completed and only the final cumulative verification failed. */
    static boolean improvementApplyResumeOffered(
            SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint) {
        return checkpoint != null && checkpoint.completedStages() < checkpoint.totalStages();
    }

    static String improvementApplyProgressText(SnippetAiWorkflowSupport.ImprovementApplyProgress progress) {
        if (progress == null) {
            return I18n.get("snippets.ai.analysis.fix.running");
        }
        String phase = switch (progress.phase()) {
            case MIGRATION -> progress.detail().isBlank()
                ? I18n.get("snippets.ai.analysis.progress.migration")
                : I18n.get("snippets.ai.analysis.progress.migration") + " \u2013 " + progress.detail();
            case ANALYSIS_ITEMS -> {
                if (progress.phaseRequirementCount() <= 0) {
                    yield I18n.get("snippets.ai.analysis.fix.running");
                }
                if (progress.lastRequirement() > progress.firstRequirement()) {
                    yield I18n.get(
                        "snippets.ai.analysis.fix.progress.analysisRange",
                        progress.firstRequirement(), progress.lastRequirement(),
                        progress.phaseRequirementCount(), progress.detail());
                }
                yield I18n.get(
                    "snippets.ai.analysis.fix.progress.analysis",
                    progress.firstRequirement(), progress.phaseRequirementCount(), progress.detail());
            }
            case HARDENING -> I18n.get(
                "snippets.ai.analysis.fix.progress.hardening",
                progress.firstRequirement(), progress.lastRequirement(), progress.phaseRequirementCount());
            case INPUT_HARDENING -> I18n.get(
                "snippets.ai.analysis.fix.progress.inputHardening",
                progress.firstRequirement(), progress.lastRequirement(), progress.phaseRequirementCount());
        };
        if (progress.retry()) {
            phase = I18n.get("snippets.ai.analysis.fix.progress.retry", phase);
        }
        return I18n.get(
            "snippets.ai.analysis.fix.progress.step",
            phase, progress.stage(), progress.totalStages());
    }

    /** Prepends the analysis dialog's chosen script header (if any) to {@code content}, using the editor's
     *  language to place it after an existing shebang / lead line (reusing the workflow-script injector). */
    private String injectSelectedHeader(SnippetAnalysisPanel.ApplySelection selection, String content) {
        if (selection == null || !selection.hasHeader()) {
            return content;
        }
        WorkflowScriptSupport.ScriptLanguage language =
            WorkflowScriptSupport.ScriptLanguage.fromId(languageCombo.getValue());
        return WorkflowScriptSupport.injectHeaderOverride(content, language, selection.headerText());
    }

    /** "Improve robustness" with the reusable script-hardening options folded into the improvement prompt. */
    private void runImproveRobustness() {
        promptImprovementOptions(I18n.get("snippets.ai.code.improve.robustness"), null, false)
            .ifPresent(options -> {
                String theme = withInputHardeningRules(
                    withHardeningRules(I18n.get("snippets.ai.code.improve.robustness.theme"), options.hardening()),
                    options.inputHardening());
                runCodeImprovement(theme, null,
                    requiresWholeSnippetForHardening(options.hardening(), options.inputHardening()));
            });
    }

    /**
     * Comments the selected code without touching the code itself. It runs through the shared
     * improvement flow, so the result is reviewed in the diff window before it replaces the
     * selection. The comment language follows the Text language picker, like every other AI action
     * that writes text into code.
     */
    private void runCommentOptimization() {
        runCodeImprovement(I18n.get("snippets.ai.code.improve.comments.theme"));
    }

    private void runCustomCodeImprovement() {
        promptImprovementOptions(
            I18n.get("snippets.ai.code.improve.custom.title"),
            I18n.get("snippets.ai.code.improve.custom.header"),
            true)
            .filter(options -> options.instruction() != null && !options.instruction().isBlank())
            .ifPresent(options -> {
                String theme = withInputHardeningRules(
                    withHardeningRules(options.instruction(), options.hardening()), options.inputHardening());
                runCodeImprovement(theme, null,
                    requiresWholeSnippetForHardening(options.hardening(), options.inputHardening()));
            });
    }

    /** Global hardening rules need access to the script prologue/body/epilogue, not only a marked region. */
    static boolean requiresWholeSnippetForHardening(
            EnumSet<HardeningOption> hardening,
            WorkflowScriptSupport.InputHardeningConfig inputHardening) {
        return (hardening != null && !hardening.isEmpty())
            || (inputHardening != null && inputHardening.isEnabled());
    }

    record CodeImprovementTarget(int start, int end, String text) {
    }

    /** Resolves the exact replacement range handed to the selected-region AI improvement flow. */
    static CodeImprovementTarget resolveCodeImprovementTarget(
            String fullContent, int selectionStart, int selectionEnd, String selectedText,
            boolean wholeSnippet) {
        return wholeSnippet
            ? new CodeImprovementTarget(0, fullContent.length(), fullContent)
            : new CodeImprovementTarget(selectionStart, selectionEnd, selectedText);
    }

    /**
     * Result of {@link #promptImprovementOptions}: an optional free-text instruction, the chosen
     * hardening options, and the input-hardening guard configuration (disabled unless its master
     * toggle was ticked).
     */
    private record ImprovementOptions(String instruction, EnumSet<HardeningOption> hardening,
                                      WorkflowScriptSupport.InputHardeningConfig inputHardening) {
    }

    /**
     * Shows a themed dialog offering the same script-hardening options as the KI-Agent workflow-script
     * generator (reuses {@link HardeningOption} and the {@code ai.workflow.option.*} labels), plus a
     * collapsed, strictly opt-in {@link InputHardeningSelector} panel for the AI-generated input
     * guard. When {@code withInstruction} is set it also collects a free-text instruction (custom
     * improvement).
     */
    private Optional<ImprovementOptions> promptImprovementOptions(String title, String header, boolean withInstruction) {
        ThemeAwareDialog<ImprovementOptions> dialog = new ThemeAwareDialog<>();
        dialog.setTitle(title);
        if (header != null && !header.isBlank()) {
            dialog.setHeaderText(header);
        }
        dialog.setResizable(true);
        if (!withInstruction) {
            DialogGeometrySupport.installAutomatic(dialog, "snippets.improvementOptions");
        }
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        HardeningOptionsSelector hardeningSelector = new HardeningOptionsSelector();
        Label optionsHeader = new Label(I18n.get("ai.workflow.options.title"));
        ThemeCssSupport.ThemeColors optionsColors = SnippetAiDialogSupport.resolveThemeColors();
        optionsHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: "
            + (optionsColors != null ? optionsColors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG) + ";");
        TitledPane optionsPane = new TitledPane();
        optionsPane.setText(null);
        optionsPane.setGraphic(optionsHeader);
        optionsPane.setContent(hardeningSelector);
        optionsPane.setExpanded(true);

        InputHardeningSelector inputHardeningSelector = new InputHardeningSelector();
        boolean inputHardeningSupported = WorkflowScriptSupport.supportsInputHardeningForSnippet(
            languageCombo.getValue());
        inputHardeningSelector.setSupported(inputHardeningSupported);
        Label inputHardeningHeader = new Label(I18n.get("ai.inputHardening.title"));
        inputHardeningHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: "
            + (optionsColors != null ? optionsColors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG) + ";");
        TitledPane inputHardeningPane = new TitledPane();
        inputHardeningPane.setText(null);
        inputHardeningPane.setGraphic(inputHardeningHeader);
        inputHardeningPane.setContent(inputHardeningSelector);
        inputHardeningPane.setDisable(!inputHardeningSupported);
        // The guard changes the script's runtime behaviour, so the panel starts collapsed and off.
        inputHardeningPane.setExpanded(false);
        // A shown dialog window never resizes itself when content grows; without this the expanded
        // panel pushes the OK/Cancel bar below the window edge (this VBox has no growable child).
        inputHardeningPane.expandedProperty().addListener((obs, was, isNow) -> {
            javafx.stage.Window window = pane.getScene() != null ? pane.getScene().getWindow() : null;
            if (window != null) {
                window.sizeToScene();
            }
        });

        TextArea instructionArea = withInstruction ? new TextArea() : null;
        VBox box = new VBox(10);
        box.setPadding(new Insets(4));
        if (withInstruction) {
            Label promptLabel = new Label(I18n.get("snippets.ai.code.improve.custom.prompt"));
            promptLabel.setWrapText(true);
            instructionArea.setWrapText(true);
            instructionArea.setPrefRowCount(5);
            VBox.setVgrow(instructionArea, Priority.ALWAYS);
            box.getChildren().addAll(promptLabel, instructionArea);
        }
        box.getChildren().addAll(optionsPane, inputHardeningPane);
        box.setPrefSize(560, withInstruction ? 400 : 280);
        pane.setContent(box);

        if (withInstruction) {
            Button okButton = (Button) pane.lookupButton(ButtonType.OK);
            okButton.disableProperty().bind(instructionArea.textProperty().isEmpty());
            restoreCustomImprovementGeometry(dialog);
            dialog.setOnShown(e -> instructionArea.requestFocus());
            dialog.setOnHidden(e -> saveCustomImprovementGeometry(dialog));
        }

        TextArea finalInstruction = instructionArea;
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            EnumSet<HardeningOption> selected = hardeningSelector.selectedOptions();
            String instruction = finalInstruction != null ? finalInstruction.getText() : null;
            return new ImprovementOptions(instruction != null ? instruction.trim() : null, selected,
                inputHardeningSelector.currentConfig());
        });
        return dialog.showAndWait();
    }

    /** Appends the selected hardening options' prompt rules to a base improvement theme/instruction. */
    private String withHardeningRules(String baseTheme, EnumSet<HardeningOption> hardening) {
        if (hardening == null || hardening.isEmpty()) {
            return baseTheme;
        }
        String rules = WorkflowScriptSupport.hardeningRulesText(
            hardening, !WorkflowScriptSupport.supportsInputHardeningForSnippet(languageCombo.getValue()));
        if (rules == null || rules.isBlank()) {
            return baseTheme;
        }
        String base = baseTheme != null ? baseTheme : "";
        return base + "\n\n" + I18n.get("snippets.ai.improve.hardeningHeader") + "\n" + rules;
    }

    /** Appends the input-hardening guard rules to a base improvement theme/instruction. */
    private String withInputHardeningRules(String baseTheme, WorkflowScriptSupport.InputHardeningConfig config) {
        if (config == null || !config.isEnabled()
                || !WorkflowScriptSupport.supportsInputHardeningForSnippet(languageCombo.getValue())) {
            return baseTheme;
        }
        // Only map to a workflow language when the snippet language is one of the guard-capable
        // interpreters; ScriptLanguage.fromId() would otherwise fall back to BASH and attach bash
        // idioms to e.g. a JavaScript snippet.
        WorkflowScriptSupport.ScriptLanguage lang = SnippetOneLiner.isEmbeddedSupported(languageCombo.getValue())
            ? WorkflowScriptSupport.ScriptLanguage.fromId(languageCombo.getValue())
            : null;
        String rules = WorkflowScriptSupport.inputHardeningRulesText(config, lang);
        if (rules == null || rules.isBlank()) {
            return baseTheme;
        }
        String base = baseTheme != null ? baseTheme : "";
        return base + "\n\n" + I18n.get("snippets.ai.improve.inputHardeningHeader") + "\n" + rules;
    }

    private void restoreCustomImprovementGeometry(ThemeAwareDialog<?> dialog) {
        DialogGeometrySupport.restore(dialog, settings -> settings.getCustomAiImprovementDialogGeometry());
    }

    private void saveCustomImprovementGeometry(ThemeAwareDialog<?> dialog) {
        DialogGeometrySupport.persist(dialog,
            (settings, geometry) -> settings.setCustomAiImprovementDialogGeometry(geometry));
    }

    private void runCodeImprovement(String theme) {
        runCodeImprovement(theme, null, false);
    }

    private void runCodeImprovement(String theme, String aiProfileId, boolean wholeSnippet) {
        if (!hasCodeImprovementProvider() || aiActionBlocked() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        if (!wholeSnippet && (selection == null || selection.getLength() <= 0)) {
            setStatus(I18n.get("snippets.ai.improve.selectFirst"));
            return;
        }
        CodeImprovementTarget target = resolveCodeImprovementTarget(
            fullContent,
            selection != null ? selection.getStart() : 0,
            selection != null ? selection.getEnd() : 0,
            selection != null ? contentArea.getSelectedText() : "",
            wholeSnippet);
        int selectionStart = target.start();
        int selectionEnd = target.end();
        String selectedText = target.text();
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.codeImprovementProvider().improve(new CodeImprovementRequest(
                    fullContent,
                    languageCombo.getValue(),
                    selectedText,
                    resolveAiTextFallbackLanguageCode(),
                    theme,
                    additionalInstructions(),
                    false,
                    aiProfileId));
            }
        };
        // The rewrite must not silently translate the snippet's own comments and messages;
        // an undetectable language is a question for the user, not a guess.
        if (!applyCodeTextLanguage(true)) {
            return;
        }
        beginSnippetAiAction(task, wholeSnippet
            ? retryOf(() -> runCodeImprovement(theme, aiProfileId, true))
            : retryOnSelection(() -> runCodeImprovement(theme, aiProfileId, false)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.improve.running"));
            setStatus(I18n.get("snippets.ai.improve.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.CodeImprovement improvement = task.getValue();
            if (improvement == null || !improvement.isUsable()) {
                setStatus(I18n.get("snippets.ai.improve.empty"));
                return;
            }
            if (wholeSnippet
                    && SnippetAiResponseSupport.isDegenerateFullReplacement(fullContent, improvement.replacement())) {
                setStatus(I18n.get("snippets.ai.fix.degenerate"));
                return;
            }
            // A selection-scoped re-run is not offered on changed content: the old selection is gone.
            showAiChangeReview(
                I18n.get("snippets.ai.diff.title"),
                improvement.summary(),
                fullContent,
                selectedText,
                improvement.replacement(),
                languageCombo.getValue(),
                wholeSnippet ? () -> runCodeImprovement(theme, aiProfileId, true) : null,
                withProfileRerun(aiProfileId,
                    profileSwitchingSupported() ? id -> runCodeImprovement(theme, id, wholeSnippet) : null),
                () -> {
                    applyAiContentChange(selectionStart, selectionEnd, improvement.replacement(),
                        I18n.get("snippets.ai.toggle.action.improve"));
                    setStatus(I18n.get("snippets.ai.improve.applied"));
                });
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.improve.failed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-improve");
    }

    // ---------------------------------------------------------------- language migration

    private void runLanguageMigration() {
        if (!hasLanguageMigrationProvider() || aiActionBlocked() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        promptMigrationPlan(fullContent)
            .filter(plan -> !plan.isNoOp())
            .ifPresent(plan -> runLanguageMigration(plan, null));
    }

    /**
     * Asks what the snippet should be unified into. The whole host-format rule lives in
     * {@link TargetLanguageSelector#setDetectedMix}, so this dialog only has to hand it the detection
     * result and read back the finished order.
     */
    private Optional<SnippetAiWorkflowSupport.MigrationPlan> promptMigrationPlan(String fullContent) {
        ThemeAwareDialog<SnippetAiWorkflowSupport.MigrationPlan> dialog = new ThemeAwareDialog<>();
        dialog.setTitle(I18n.get("snippets.ai.migrate.title"));
        dialog.setHeaderText(I18n.get("snippets.ai.migrate.header"));
        dialog.setResizable(true);
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TargetLanguageSelector selector = new TargetLanguageSelector(true);
        selector.setDetectedMix(
            ScriptLanguageMixSupport.detect(languageCombo.getValue(), fullContent));

        Button okButton = (Button) pane.lookupButton(ButtonType.OK);
        Runnable refreshOk = () -> okButton.setDisable(selector.buildPlan().isNoOp());
        selector.setOnSelectionChanged(refreshOk);
        refreshOk.run();

        VBox box = new VBox(10, selector);
        box.setPadding(new Insets(4));
        box.setPrefSize(560, 220);
        pane.setContent(box);

        DialogGeometrySupport.restore(dialog, settings -> settings.getLanguageMigrationDialogGeometry());
        dialog.setOnHidden(e -> DialogGeometrySupport.persist(dialog,
            (settings, geometry) -> settings.setLanguageMigrationDialogGeometry(geometry)));

        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? selector.buildPlan() : null);
        return dialog.showAndWait();
    }

    private void runLanguageMigration(SnippetAiWorkflowSupport.MigrationPlan plan, String aiProfileId) {
        if (aiActionBlocked()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        String lang = languageCombo.getValue();
        Task<SnippetAiResponseSupport.LanguageMigration> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.LanguageMigration call() throws Exception {
                return aiAssist.languageMigrationProvider().migrate(new LanguageMigrationRequest(
                    fullContent,
                    lang,
                    resolveAiTextFallbackLanguageCode(),
                    plan,
                    additionalInstructions(),
                    aiProfileId));
            }
        };
        beginSnippetAiAction(task, retryOf(() -> runLanguageMigration(plan, aiProfileId)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.migrate.running"));
            setStatus(I18n.get("snippets.ai.migrate.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.LanguageMigration migration = task.getValue();
            if (migration == null || !migration.isUsable()) {
                setStatus(I18n.get("snippets.ai.migrate.empty"));
                return;
            }
            showAiChangeReview(
                I18n.get("snippets.ai.migrate.diffTitle"),
                migrationSummary(plan, migration),
                fullContent,
                fullContent,
                migration.replacement(),
                lang,
                () -> runLanguageMigration(plan, aiProfileId),
                withProfileRerun(aiProfileId,
                    profileSwitchingSupported() ? id -> runLanguageMigration(plan, id) : null),
                () -> {
                    applyAiContentChange(0, fullContent.length(), migration.replacement(),
                        I18n.get("snippets.ai.code.migrate"));
                    retargetSnippetAfterMigration(plan);
                    setStatus(migration.notes().isEmpty()
                        ? I18n.get("snippets.ai.migrate.applied")
                        : I18n.get("snippets.ai.migrate.notes", String.join(" ", migration.notes())));
                });
        });
        task.setOnFailed(event -> handleMigrationFailure(task, plan));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-migrate");
    }

    /** Puts the notes above the diff for a platform conversion, where they are the actual to-do list. */
    private String migrationSummary(SnippetAiWorkflowSupport.MigrationPlan plan,
                                    SnippetAiResponseSupport.LanguageMigration migration) {
        if (migration.notes().isEmpty()) {
            return migration.summary();
        }
        String heading = plan.changesHostFormat()
            ? I18n.get("snippets.ai.migrate.notes.platform")
            : I18n.get("snippets.ai.migrate.notes.heading");
        StringBuilder text = new StringBuilder(heading.strip());
        for (String note : migration.notes()) {
            text.append("\n\u2022 ").append(note);
        }
        String summary = migration.summary();
        return summary.isBlank() ? text.toString() : summary + "\n\n" + text;
    }

    /**
     * A migration is the only AI action that changes what kind of file the snippet is, so the
     * editor's own metadata has to follow: language, file extension and the auto-detected AI skills.
     * A steps-only unification changes none of that — the snippet is still the same pipeline.
     */
    private void retargetSnippetAfterMigration(SnippetAiWorkflowSupport.MigrationPlan plan) {
        String targetLanguage = null;
        String targetFileName = null;
        if (plan.changesHostFormat()) {
            targetLanguage = plan.targetHostFormat().snippetLanguage();
            targetFileName = plan.targetHostFormat().defaultFileName();
        } else if (plan.modes().contains(ScriptLanguageMixSupport.MigrationMode.WHOLE_SCRIPT)
            && plan.targetLanguage() != null) {
            targetLanguage = plan.targetLanguage().snippetLanguage();
        }
        if (targetLanguage == null) {
            return;
        }
        if (!languageCombo.getItems().contains(targetLanguage)) {
            languageCombo.getItems().add(targetLanguage);
        }
        languageCombo.setValue(targetLanguage);
        String currentName = nameField.getText();
        if (currentName != null && !currentName.isBlank()) {
            nameField.setText(targetFileName != null
                ? targetFileName
                : SnippetLanguageSupport.sanitizeFileName(currentName, targetLanguage));
        }
        autoDetectAiSkills();
    }

    private void handleMigrationFailure(Task<?> task, SnippetAiWorkflowSupport.MigrationPlan plan) {
        Throwable failure = task.getException();
        SnippetAiWorkflowSupport.MigrationRejectedException rejection = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SnippetAiWorkflowSupport.MigrationRejectedException found) {
                rejection = found;
                break;
            }
        }
        if (rejection == null) {
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.migrate.failed"));
            return;
        }
        finishSnippetAiAction(task);
        setStatus(switch (rejection.reason()) {
            case DEGENERATE -> I18n.get("snippets.ai.migrate.degenerate");
            case SCAFFOLD_CHANGED -> I18n.get("snippets.ai.migrate.scaffoldChanged");
            case TARGET_FORMAT_NOT_REACHED -> I18n.get("snippets.ai.migrate.targetFormatNotReached",
                plan.targetHostFormat() != null ? plan.targetHostFormat().displayName() : "");
            case NO_USABLE_SCRIPT -> I18n.get("snippets.ai.migrate.empty");
        });
    }

    private void runCodeAssistant() {
        if (!hasCodeAssistantProvider() || aiActionBlocked()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        Optional<CodeAssistantPrompt> prompt = promptCodeAssistantInstruction();
        if (prompt.isEmpty() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        runCodeAssistant(prompt.get(), null);
    }

    private void runCodeAssistant(CodeAssistantPrompt assistantPrompt, String aiProfileId) {
        if (!hasCodeAssistantProvider() || assistantPrompt == null || aiActionBlocked()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        String snippetLanguage = languageCombo.getValue();
        String fallbackLanguage = resolveAiTextFallbackLanguageCode();
        String additionalInstructions = additionalInstructions();
        CursorLocation cursor = cursorLocation(fullContent, contentArea.getCaretPosition());
        // Forced skills from the picker apply even if the assistant's own skills checkbox is off/disabled.
        boolean includeSkills = assistantPrompt.includeAiSkills() || hasForcedAiSkills();
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.codeAssistantProvider().assist(new CodeAssistantRequest(
                    fullContent,
                    snippetLanguage,
                    cursor.offset(),
                    cursor.line(),
                    cursor.column(),
                    fallbackLanguage,
                    assistantPrompt.instruction(),
                    additionalInstructions,
                    includeSkills,
                    aiProfileId));
            }
        };
        // The rewrite must not silently translate the snippet's own comments and messages;
        // an undetectable language is a question for the user, not a guess.
        if (!applyCodeTextLanguage(true)) {
            return;
        }
        beginSnippetAiAction(task, retryOnSelection(() -> runCodeAssistant(assistantPrompt, aiProfileId)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.assistant.running"));
            setStatus(I18n.get("snippets.ai.assistant.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.CodeImprovement improvement = task.getValue();
            if (improvement == null || !improvement.isUsable()) {
                setStatus(I18n.get("snippets.ai.assistant.empty"));
                return;
            }
            showAiChangeReview(
                I18n.get("snippets.ai.assistant.diffTitle"),
                improvement.summary(),
                fullContent,
                fullContent,
                improvement.replacement(),
                snippetLanguage,
                () -> runCodeAssistant(assistantPrompt, aiProfileId),
                withProfileRerun(aiProfileId,
                    profileSwitchingSupported() ? id -> runCodeAssistant(assistantPrompt, id) : null),
                () -> {
                    applyAiContentChange(
                        0,
                        fullContent.length(),
                        improvement.replacement(),
                        I18n.get("snippets.ai.toggle.action.assistant"));
                    setStatus(I18n.get("snippets.ai.assistant.applied"));
                });
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.assistant.failed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-assistant");
    }

    private Optional<CodeAssistantPrompt> promptCodeAssistantInstruction() {
        Dialog<CodeAssistantPrompt> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.ai.assistant.title"));
        dialog.setHeaderText(I18n.get("snippets.ai.assistant.header"));
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, "snippets.codeAssistantInstruction");
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }

        TextArea instructionArea = new TextArea();
        instructionArea.setPromptText(I18n.get("snippets.ai.assistant.prompt"));
        instructionArea.setPrefRowCount(5);
        instructionArea.setPrefColumnCount(120);
        instructionArea.setWrapText(true);

        CheckBox includeSkillsCheck = new CheckBox(I18n.get("snippets.ai.assistant.skills"));
        boolean skillsAvailable = isAiSkillsAvailableForChat();
        includeSkillsCheck.setSelected(skillsAvailable);
        includeSkillsCheck.setDisable(!skillsAvailable);
        includeSkillsCheck.setTooltip(new Tooltip(skillsAvailable
            ? I18n.get("snippets.ai.assistant.skills.tooltip")
            : I18n.get("snippets.ai.assistant.skills.unavailable")));

        VBox content = new VBox(8, new Label(I18n.get("snippets.ai.assistant.instruction")), instructionArea, includeSkillsCheck);
        content.setPadding(new Insets(10));
        VBox.setVgrow(instructionArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(900);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        instructionArea.textProperty().addListener((obs, oldValue, newValue) ->
            okButton.setDisable(newValue == null || newValue.trim().isBlank()));
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            String instruction = instructionArea.getText();
            return instruction != null && !instruction.trim().isBlank()
                ? new CodeAssistantPrompt(instruction.trim(), includeSkillsCheck.isSelected())
                : null;
        });
        Platform.runLater(instructionArea::requestFocus);
        return dialog.showAndWait();
    }

    private boolean isAiSkillsAvailableForChat() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings == null || !settings.isAiSkillsEnabled()) {
                return false;
            }
            for (AiSkill skill : settings.getAiSkills()) {
                if (skill != null
                    && skill.isEnabled()
                    && skill.getTarget().appliesToChat()
                    && skill.getContent() != null
                    && !skill.getContent().isBlank()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static CursorLocation cursorLocation(String content, int cursorOffset) {
        String value = content != null ? content : "";
        int safeOffset = Math.max(0, Math.min(cursorOffset, value.length()));
        int line = 1;
        int column = 1;
        for (int i = 0; i < safeOffset; i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
            } else if (c != '\r') {
                column++;
            }
        }
        return new CursorLocation(safeOffset, line, column);
    }

    private void runSecurityCheck() {
        if (!hasSecurityProviders() || aiActionBlocked() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        Task<List<SnippetAiResponseSupport.SecurityFinding>> task = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.SecurityFinding> call() throws Exception {
                return aiAssist.securityReportProvider().review(new SecurityReviewRequest(
                    fullContent,
                    languageCombo.getValue(),
                    resolveAiTextFallbackLanguageCode(),
                    additionalInstructions()));
            }
        };
        beginSnippetAiAction(task, retryOf(this::runSecurityCheck));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.security.running"));
            setStatus(I18n.get("snippets.ai.security.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetSecurityReportDialog dialog = new SnippetSecurityReportDialog(
                childWindowOwner(),
                task.getValue(),
                this::runSecurityCheck,
                ScriptLanguageMixSupport.detect(languageCombo.getValue(), fullContent));
            // Non-modal: the report stays open beside the editor and its "Apply selected" answers
            // through this callback instead of a nested event loop.
            dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, hidden -> {
                SnippetSecurityReportDialog.FixSelection chosen = dialog.getResult();
                if (chosen != null && !editorClosed) {
                    Platform.runLater(() -> runSecurityFixes(chosen));
                }
            });
            showChildWindow(dialog);
            setStatus(I18n.get("snippets.ai.security.ready"));
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.security.failed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-security-review");
    }

    private void runSecurityFixes(SnippetSecurityReportDialog.FixSelection selection) {
        if (selection == null || selection.findings().isEmpty() || !hasSecurityProviders() || aiActionBlocked()) {
            return;
        }
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings = selection.findings();
        String originalContent = contentArea.getText();
        Task<SnippetAiResponseSupport.SnippetSecurityFix> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.SnippetSecurityFix call() throws Exception {
                return aiAssist.securityFixProvider().applyFixes(new SecurityFixRequest(
                    originalContent,
                    languageCombo.getValue(),
                    resolveAiTextFallbackLanguageCode(),
                    selectedFindings,
                    additionalInstructions(),
                    selection.migration()));
            }
        };
        // The rewrite must not silently translate the snippet's own comments and messages;
        // an undetectable language is a question for the user, not a guess.
        if (!applyCodeTextLanguage(true)) {
            return;
        }
        // The findings describe this exact content, so Retry is only offered while it is unchanged.
        beginSnippetAiAction(task, new AiRetry(() -> runSecurityFixes(selection),
            () -> java.util.Objects.equals(contentArea.getText(), originalContent)
                ? null
                : I18n.get("snippets.ai.retry.contentChanged")));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.security.fix.running"));
            setStatus(I18n.get("snippets.ai.security.fix.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.SnippetSecurityFix fix = task.getValue();
            if (fix == null || !fix.isUsable()) {
                setStatus(I18n.get("snippets.ai.security.fix.empty"));
                return;
            }
            // Reject incomplete full replacements, including omission comments such as "rest unchanged".
            if (SnippetAiResponseSupport.isDegenerateFullReplacement(originalContent, fix.replacement())) {
                setStatus(I18n.get("snippets.ai.fix.degenerate"));
                return;
            }
            showAiChangeReview(
                I18n.get("snippets.ai.security.diff.title"),
                fix.summary(),
                originalContent,
                originalContent,
                fix.replacement(),
                languageCombo.getValue(),
                () -> runSecurityFixes(selection),
                pane -> pane.setChangeExplanations(fix.changes()),
                () -> {
                    applyAiContentChange(0, originalContent.length(), fix.replacement(),
                        I18n.get("snippets.ai.toggle.action.security"));
                    setStatus(I18n.get("snippets.ai.security.fix.applied"));
                });
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.security.fix.failed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-security-fix");
    }

    private void openOrCreateDiagram() {
        if (!hasDiagramProvider()) {
            return;
        }
        if (diagrams.isEmpty()) {
            startDiagramGeneration(null, SnippetDiagramType.LOGICAL_STRUCTURE, null);
            return;
        }
        // One diagram window per editor: a new one replaces the old (it shows the current code).
        closeDiagramDialog();
        SnippetDiagramDialog dialog = new SnippetDiagramDialog(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            copyDiagrams(),
            contentArea.getText(),
            currentSnippetDisplayName(),
            this::runDiagramGeneration,
            type -> startDiagramGeneration(null, type, null),
            this::deleteDiagram,
            this::navigateToDiagramCodeReference);
        openDiagramDialog = dialog;
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, hidden -> {
            if (openDiagramDialog == dialog) {
                openDiagramDialog = null;
            }
        });
        dialog.show();
    }

    /**
     * Closes the diagram window opened from this editor. It calls back into the editor (navigate
     * to code, delete a diagram), so it must not outlive it.
     */
    private void closeDiagramDialog() {
        SnippetDiagramDialog dialog = openDiagramDialog;
        openDiagramDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.close();
        }
    }

    private void deleteDiagram(SnippetDiagram diagram) {
        if (diagram == null || diagram.getId() == null) {
            return;
        }
        diagrams.removeIf(current -> current != null && diagram.getId().equals(current.getId()));
        updateSaveButtonState();
    }

    private String currentSnippetDisplayName() {
        String currentName = nameField.getText();
        if (currentName != null && !currentName.isBlank()) {
            return currentName.trim();
        }
        return existingSnippet != null && existingSnippet.getName() != null && !existingSnippet.getName().isBlank()
            ? existingSnippet.getName().trim()
            : I18n.get("snippets.ai.diagram.script.unnamed");
    }

    private void navigateToDiagramCodeReference(SnippetDiagramDialog.CodeNavigationTarget target) {
        if (target == null) {
            return;
        }
        Runnable navigation = () -> {
            String content = contentArea.getText() != null ? contentArea.getText() : "";
            int startLine = Math.max(1, target.startLine());
            int endLine = Math.max(startLine, target.endLine());
            int startOffset = lineStartOffset(content, startLine);
            int endOffset = lineEndOffset(content, endLine);
            int safeStart = Math.max(0, Math.min(startOffset, content.length()));
            int safeEnd = Math.max(safeStart, Math.min(endOffset, content.length()));
            contentArea.selectRange(safeStart, safeEnd);
            contentArea.requestFocus();
            contentArea.requestFollowCaret();
        };
        if (Platform.isFxApplicationThread()) {
            navigation.run();
        } else {
            Platform.runLater(navigation);
        }
    }

    private static int lineStartOffset(String content, int lineNumber) {
        String value = content != null ? content : "";
        if (lineNumber <= 1) {
            return 0;
        }
        int currentLine = 1;
        for (int offset = 0; offset < value.length(); offset++) {
            if (value.charAt(offset) == '\n') {
                currentLine++;
                if (currentLine == lineNumber) {
                    return offset + 1;
                }
            }
        }
        return value.length();
    }

    private static int lineEndOffset(String content, int lineNumber) {
        String value = content != null ? content : "";
        int startOffset = lineStartOffset(value, lineNumber);
        int endOffset = value.indexOf('\n', startOffset);
        return endOffset >= 0 ? endOffset : value.length();
    }

    static int lineNumberAtOffset(String content, int offset) {
        String value = content != null ? content : "";
        int line = 1;
        int limit = Math.max(0, Math.min(offset, value.length()));
        for (int index = 0; index < limit; index++) {
            if (value.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Context-menu entry point: generate a diagram of {@code type} for the current selection. */
    private void runDiagramGenerationForSelection(SnippetDiagramType type) {
        startDiagramGeneration(null, type, captureDiagramScope());
    }

    /**
     * The current editor selection snapped to whole lines, or {@code null} when nothing is selected
     * or the selection covers the whole snippet (then a whole-snippet diagram is generated).
     */
    private DiagramScope captureDiagramScope() {
        contentArea.syncFromEditor();
        IndexRange range = contentArea.getSelection();
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        if (range == null || range.getLength() <= 0 || content.isBlank()) {
            return null;
        }
        int selectionStart = Math.max(0, Math.min(range.getStart(), content.length()));
        int selectionEnd = Math.max(selectionStart, Math.min(range.getEnd(), content.length()));
        if (selectionStart == selectionEnd) {
            return null;
        }
        int startLine = lineNumberAtOffset(content, selectionStart);
        int endLine = lineNumberAtOffset(content, Math.max(selectionStart, selectionEnd - 1));
        int startOffset = lineStartOffset(content, startLine);
        int endOffset = Math.min(lineEndOffset(content, endLine), content.length());
        String text = content.substring(Math.min(startOffset, endOffset), endOffset);
        if (text.isBlank()) {
            return null;
        }
        if (startLine == 1 && endOffset >= content.stripTrailing().length()) {
            return null;
        }
        return new DiagramScope(text, startLine, endLine);
    }

    /** The text of lines {@code startLine..endLine} as {@link #captureDiagramScope()} cuts it, or "". */
    static String diagramScopeText(String content, int startLine, int endLine) {
        String value = content != null ? content : "";
        if (startLine < 1 || endLine < startLine) {
            return "";
        }
        int startOffset = lineStartOffset(value, startLine);
        int endOffset = Math.min(lineEndOffset(value, endLine), value.length());
        return startOffset <= endOffset ? value.substring(startOffset, endOffset) : "";
    }

    /** Regenerates an existing diagram in place, keeping its family and selection scope. */
    private void runDiagramGeneration(SnippetDiagram existingDiagram) {
        SnippetDiagramType type = existingDiagram != null
            ? SnippetDiagramType.fromIdOrDefault(existingDiagram.getType())
            : SnippetDiagramType.LOGICAL_STRUCTURE;
        startDiagramGeneration(existingDiagram, type, scopeFromDiagram(existingDiagram));
    }

    /** The selection a diagram is scoped to, re-read from the current snippet content. */
    private record DiagramScope(String text, int startLine, int endLine) {
    }

    private DiagramScope scopeFromDiagram(SnippetDiagram diagram) {
        if (diagram == null || !diagram.hasScope()) {
            return null;
        }
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        String[] lines = content.split("\\R", -1);
        int startLine = Math.min(diagram.getScopeStartLine(), lines.length);
        int endLine = Math.min(Math.max(diagram.getScopeEndLine(), startLine), lines.length);
        if (startLine < 1) {
            return null;
        }
        String text = String.join("\n", java.util.Arrays.copyOfRange(lines, startLine - 1, endLine));
        return !text.isBlank() ? new DiagramScope(text, startLine, endLine) : null;
    }

    private void startDiagramGeneration(
        SnippetDiagram existingDiagram, SnippetDiagramType diagramType, DiagramScope scope) {

        if (!hasDiagramProvider() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        SnippetDiagramType type = diagramType != null ? diagramType : SnippetDiagramType.LOGICAL_STRUCTURE;
        String generationContent = scope != null ? scope.text() : fullContent;
        // The deterministic flowchart fallback only makes sense for a whole-snippet logical
        // structure; a scoped or non-flowchart generation fails visibly instead.
        boolean allowFallback = type == SnippetDiagramType.LOGICAL_STRUCTURE && scope == null;
        SnippetDiagram targetDiagram = existingDiagram;
        String savedInstructions = targetDiagram != null ? targetDiagram.getCustomInstructions() : null;
        String requestInstructions = savedInstructions != null && !savedInstructions.isBlank()
            ? savedInstructions
            : "";
        String snippetLanguage = languageCombo.getValue();
        String fallbackLanguageCode = resolveAiTextFallbackLanguageCode();
        Task<DiagramGenerationResult> task = new Task<>() {
            @Override
            protected DiagramGenerationResult call() throws Exception {
                SnippetAiResponseSupport.MermaidDiagram diagram = null;
                boolean outputLimitReached = false;
                String failureMessage = null;
                try {
                    diagram = aiAssist.diagramProvider().generate(new DiagramRequest(
                        fullContent,
                        snippetLanguage,
                        fallbackLanguageCode,
                        requestInstructions,
                        null,
                        type,
                        scope != null ? scope.text() : null,
                        scope != null ? scope.startLine() : 0,
                        scope != null ? scope.endLine() : 0));
                } catch (Exception e) {
                    if (isCancelled() || e instanceof de.kortty.core.AiCancelledException) {
                        // A stopped generation is not a failure: no local fallback is built for it.
                        throw e;
                    }
                    outputLimitReached = isOutputTokenLimitFailure(e);
                    failureMessage = outputLimitReached
                        ? I18n.get("snippets.ai.diagram.outputLimitReached")
                        : shortenStatusMessage(String.valueOf(e.getMessage()));
                    logger.warn("AI diagram generation failed", e);
                }
                MermaidRenderService.SyntaxCheckResult syntaxCheck = null;
                MermaidRenderService.RenderResult renderCheck = null;
                if (diagram != null && diagram.isUsable()) {
                    syntaxCheck = MermaidRenderService.checkSyntax(diagram.mermaid())
                        .get(31, java.util.concurrent.TimeUnit.SECONDS);
                    renderCheck = MermaidRenderService.render(MermaidRenderService.RenderRequest.generated(
                            diagram.mermaid(), type, MermaidRenderService.Theme.LIGHT, "#FFFFFF", false))
                        .get(31, java.util.concurrent.TimeUnit.SECONDS);
                }
                if (allowFallback
                    && (diagram == null || !diagram.isUsable() || renderCheck == null || !renderCheck.success())) {
                    String fallbackDetail = failureMessage != null
                        ? failureMessage
                        : diagram == null
                            ? null
                            : !diagram.isUsable()
                                ? diagram.rejectionReason()
                                : renderCheck != null && !renderCheck.success()
                                    ? "Mermaid could not parse the diagram: " + shortenStatusMessage(renderCheck.message())
                                    : null;
                    if (failureMessage == null && fallbackDetail != null) {
                        logger.warn("AI diagram was rejected ({}); using the local Mermaid fallback", fallbackDetail);
                    }
                    // The status line names the reason in a few localized words; the precise
                    // rejection sentence is in the log.
                    String fallbackReason = fallbackDetail == null ? null
                        : failureMessage != null
                            ? I18n.get(outputLimitReached
                                ? "snippets.ai.diagram.rejection.outputLimit"
                                : "snippets.ai.diagram.rejection.requestFailed")
                            : SnippetDiagramFallbackText.shortReason(fallbackDetail);
                    String fallbackSource = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(fullContent, snippetLanguage);
                    String fallbackTitle = diagram != null && diagram.title() != null && !diagram.title().isBlank()
                        ? diagram.title()
                        : I18n.get("snippets.ai.diagram.title");
                    SnippetAiResponseSupport.MermaidDiagram fallbackDiagram =
                        new SnippetAiResponseSupport.MermaidDiagram(fallbackTitle, fallbackSource);
                    MermaidRenderService.SyntaxCheckResult fallbackSyntaxCheck =
                        MermaidRenderService.checkSyntax(fallbackDiagram.mermaid())
                            .get(31, java.util.concurrent.TimeUnit.SECONDS);
                    MermaidRenderService.RenderResult fallbackRenderCheck =
                        MermaidRenderService.render(MermaidRenderService.RenderRequest.generatedFlow(
                                fallbackDiagram.mermaid(), MermaidRenderService.Theme.LIGHT, "#FFFFFF", false))
                            .get(31, java.util.concurrent.TimeUnit.SECONDS);
                    if (fallbackRenderCheck.success()) {
                        return new DiagramGenerationResult(
                            fallbackDiagram, fallbackSyntaxCheck, fallbackRenderCheck, false, fallbackReason);
                    }
                }
                return new DiagramGenerationResult(diagram, syntaxCheck, renderCheck, outputLimitReached);
            }
        };
        beginSnippetAiAction(task, new AiRetry(() -> startDiagramGeneration(existingDiagram, diagramType, scope),
            () -> scope == null || scope.text().equals(diagramScopeText(contentArea.getText(), scope.startLine(), scope.endLine()))
                ? null
                : I18n.get("snippets.ai.retry.selectionChanged")));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.diagram.generating"));
            setStatus(I18n.get("snippets.ai.diagram.generating"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            DiagramGenerationResult result = task.getValue();
            SnippetAiResponseSupport.MermaidDiagram generated = result != null ? result.diagram() : null;
            if (generated == null || !generated.isUsable()) {
                setStatus(result != null && result.outputLimitReached()
                    ? I18n.get("snippets.ai.diagram.outputLimitReached")
                    : generated != null && generated.rejectionReason() != null
                        ? I18n.get("snippets.ai.diagram.rejected",
                            SnippetDiagramFallbackText.shortReason(generated.rejectionReason()))
                        : I18n.get("snippets.ai.diagram.failed"));
                return;
            }
            MermaidRenderService.SyntaxCheckResult syntaxCheck = result.syntaxCheck();
            if (syntaxCheck != null && syntaxCheck.available() && !syntaxCheck.valid()) {
                setStatus(I18n.get("snippets.ai.diagram.invalid", shortenStatusMessage(syntaxCheck.message())));
                return;
            }
            MermaidRenderService.RenderResult renderCheck = result.renderCheck();
            if (renderCheck != null && !renderCheck.success()) {
                setStatus(I18n.get("snippets.ai.diagram.invalid", shortenStatusMessage(renderCheck.message())));
                return;
            }
            SnippetDiagram diagram = targetDiagram != null ? new SnippetDiagram(targetDiagram) : new SnippetDiagram();
            diagram.setTitle(generated.title());
            diagram.setType(generated.diagramType().id());
            diagram.setMermaidSource(generated.mermaid());
            diagram.setSourceContentSha256(SnippetDiagramSupport.contentHash(fullContent));
            diagram.setCustomInstructions(requestInstructions);
            diagram.setScopeStartLine(scope != null ? scope.startLine() : 0);
            diagram.setScopeEndLine(scope != null ? scope.endLine() : 0);
            diagram.setCodeReferences(persistedCodeReferences(
                generated, generationContent, scope != null ? scope.startLine() - 1 : 0));
            diagram.setUpdatedAt(System.currentTimeMillis());
            upsertDiagram(diagram);
            updateSaveButtonState();
            setStatus(result.fallbackReason() != null
                ? I18n.get("snippets.ai.diagram.fallbackSaved", result.fallbackReason())
                : I18n.get("snippets.ai.diagram.ready"));
            openOrCreateDiagram();
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.diagram.failed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-diagram");
    }

    /**
     * Persists a generated diagram's source mapping. {@code generationContent} is what the AI saw
     * (the selection for a scoped diagram), and {@code lineOffset} shifts the relative line numbers
     * to absolute snippet lines so click-to-code navigation stays untouched.
     */
    private List<SnippetDiagram.CodeReference> persistedCodeReferences(
        SnippetAiResponseSupport.MermaidDiagram generated,
        String generationContent,
        int lineOffset) {

        if (generated == null || !generated.isUsable()) {
            return List.of();
        }
        List<SnippetDiagram.CodeReference> persistedReferences = new ArrayList<>();
        if (generated.diagramType() == SnippetDiagramType.LOGICAL_STRUCTURE) {
            List<SnippetDiagramSupport.CodeReference> validatedReferences =
                SnippetDiagramSupport.buildExpandedCodeReferences(
                    generated.mermaid(),
                    generationContent,
                    generated.codeReferences());
            for (SnippetDiagramSupport.CodeReference reference : validatedReferences) {
                persistedReferences.add(new SnippetDiagram.CodeReference(
                    reference.nodeId(),
                    reference.label(),
                    reference.startLine() + lineOffset,
                    reference.endLine() + lineOffset));
            }
            return persistedReferences;
        }
        int lineCount = (generationContent != null ? generationContent : "").split("\\R", -1).length;
        for (SnippetDiagramSupport.SourceCodeReference reference : generated.codeReferences()) {
            if (reference == null || reference.startLine() < 1 || reference.endLine() > lineCount) {
                continue;
            }
            persistedReferences.add(new SnippetDiagram.CodeReference(
                reference.nodeId(),
                reference.label(),
                reference.startLine() + lineOffset,
                reference.endLine() + lineOffset));
        }
        return persistedReferences;
    }

    private void upsertDiagram(SnippetDiagram diagram) {
        for (int i = 0; i < diagrams.size(); i++) {
            SnippetDiagram current = diagrams.get(i);
            if (current != null && current.getId() != null && current.getId().equals(diagram.getId())) {
                diagrams.set(i, diagram);
                return;
            }
        }
        diagrams.add(diagram);
    }

    private void insertTechnicalDescription(String text, int insertOffset) {
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        int safeOffset = Math.max(0, Math.min(insertOffset, content.length()));
        String insertion = text != null ? text.trim() : "";
        if (insertion.isBlank()) {
            return;
        }
        String prefix = safeOffset > 0 && content.charAt(safeOffset - 1) != '\n' ? "\n" : "";
        String suffix = safeOffset < content.length() && content.charAt(safeOffset) != '\n' ? "\n\n" : "\n\n";
        applyAiContentChange(safeOffset, safeOffset, prefix + insertion + suffix, I18n.get("snippets.ai.toggle.action.description"));
        setStatus(I18n.get("snippets.ai.description.inserted"));
    }

    private void applyAiContentChange(int start, int end, String replacement, String actionLabel) {
        String beforeText = contentArea.getText() != null ? contentArea.getText() : "";
        IndexRange beforeSelection = contentArea.getSelection();
        int safeStart = Math.max(0, Math.min(start, beforeText.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, beforeText.length()));
        String safeReplacement = replacement != null ? replacement : "";
        String afterText = beforeText.substring(0, safeStart) + safeReplacement + beforeText.substring(safeEnd);
        int afterSelectionStart = safeStart;
        int afterSelectionEnd = safeStart + safeReplacement.length();
        programmaticContentUpdate = true;
        try {
            contentArea.replaceText(safeStart, safeEnd, safeReplacement);
            contentArea.selectRange(afterSelectionStart, afterSelectionEnd);
            applyHighlighting();
        } finally {
            programmaticContentUpdate = false;
        }
        storeLastAiChangeSnapshot(
            actionLabel,
            beforeText,
            afterText,
            beforeSelection.getStart(),
            beforeSelection.getEnd(),
            afterSelectionStart,
            afterSelectionEnd);
    }

    private void storeLastAiChangeSnapshot(
        String actionLabel,
        String beforeText,
        String afterText,
        int beforeAnchor,
        int beforeCaret,
        int afterAnchor,
        int afterCaret) {

        if (beforeText == null || afterText == null || beforeText.equals(afterText)) {
            clearLastAiChangeSnapshot();
            return;
        }
        lastAiChangeSnapshot = new LastAiChangeSnapshot(
            actionLabel,
            beforeText,
            afterText,
            beforeAnchor,
            beforeCaret,
            afterAnchor,
            afterCaret);
        lastAiChangeShowingModified = true;
        updateAiActionAvailability();
    }

    private void clearLastAiChangeSnapshot() {
        lastAiChangeSnapshot = null;
        lastAiChangeShowingModified = true;
        updateLastAiToggleTooltip();
    }

    private void toggleLastAiChange() {
        if (lastAiChangeSnapshot == null) {
            return;
        }
        if (lastAiChangeShowingModified) {
            restoreContentSnapshot(
                lastAiChangeSnapshot.beforeText(),
                lastAiChangeSnapshot.beforeAnchor(),
                lastAiChangeSnapshot.beforeCaret());
            lastAiChangeShowingModified = false;
            setStatus(I18n.get("snippets.ai.toggle.showingOriginal"));
        } else {
            restoreContentSnapshot(
                lastAiChangeSnapshot.afterText(),
                lastAiChangeSnapshot.afterAnchor(),
                lastAiChangeSnapshot.afterCaret());
            lastAiChangeShowingModified = true;
            setStatus(I18n.get("snippets.ai.toggle.showingModified"));
        }
        updateAiActionAvailability();
    }

    private void restoreContentSnapshot(String text, int anchor, int caret) {
        String value = text != null ? text : "";
        int safeAnchor = Math.max(0, Math.min(anchor, value.length()));
        int safeCaret = Math.max(0, Math.min(caret, value.length()));
        programmaticContentUpdate = true;
        try {
            contentArea.replaceText(value);
            contentArea.selectRange(safeAnchor, safeCaret);
            applyHighlighting();
        } finally {
            programmaticContentUpdate = false;
        }
    }

    private void updateLastAiToggleTooltip() {
        String key = lastAiChangeShowingModified
            ? "snippets.ai.toggle.tooltip.showOriginal"
            : "snippets.ai.toggle.tooltip.showModified";
        toggleLastAiChangeButton.setTooltip(new Tooltip(I18n.get(key)));
    }

    private int startOfLine(int offset) {
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        int safeOffset = Math.max(0, Math.min(offset, content.length()));
        int lineStart = content.lastIndexOf('\n', Math.max(0, safeOffset - 1));
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    private int firstContentOffset(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return 0;
    }

    /** The target language of the last translation, or null when none was chosen yet. */
    private String loadRememberedTranslationLanguage() {
        GlobalSettings settings = currentGlobalSettings();
        String remembered = settings != null ? settings.getSnippetTranslationTargetLanguage() : null;
        return remembered != null && !remembered.isBlank() ? remembered.trim() : null;
    }

    /**
     * Persists the chosen target language so the dialog reopens on it. A typed language name is
     * stored verbatim and stays until a different language is chosen, exactly like a listed one.
     */
    private void saveRememberedTranslationLanguage(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return;
        }
        GlobalSettings settings = null;
        String previous = null;
        try {
            KorTTYApplication application = KorTTYApplication.getInstance();
            settings = application.getGlobalSettingsManager().getSettings();
            if (settings != null) {
                previous = settings.getSnippetTranslationTargetLanguage();
                settings.setSnippetTranslationTargetLanguage(languageCode);
                application.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            if (settings != null) {
                settings.setSnippetTranslationTargetLanguage(previous);
            }
            logger.debug("Could not persist the snippet translation language", e);
        }
    }

    private AiLanguageSupport.LanguageOption promptTranslationLanguage() {
        ThemeAwareDialog<AiLanguageSupport.LanguageOption> dialog = new ThemeAwareDialog<>();
        dialog.setTitle(I18n.get("snippets.ai.translate.dialog.title"));
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        ComboBox<AiLanguageSupport.LanguageOption> comboBox = new ComboBox<>();
        comboBox.setId("snippet-ai-translate-language");
        comboBox.getItems().setAll(AiLanguageSupport.buildAvailableLanguageOptions(resolveAiTextFallbackLanguageCode()));
        comboBox.setPrefWidth(260);
        // Editable so a language the list does not carry can simply be typed. The value is handed
        // to the model as prompt text ("Translate into language code …") and never parsed as a
        // locale, so a plain language name works exactly as well as a code.
        comboBox.setEditable(true);
        comboBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiLanguageSupport.LanguageOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public AiLanguageSupport.LanguageOption fromString(String text) {
                String typed = text == null ? "" : text.trim();
                if (typed.isEmpty()) {
                    return null;
                }
                // Typing the label of a listed language must select that language rather than
                // create a second, free-text entry that means the same thing.
                for (AiLanguageSupport.LanguageOption option : comboBox.getItems()) {
                    if (typed.equalsIgnoreCase(option.label()) || typed.equalsIgnoreCase(option.code())) {
                        return option;
                    }
                }
                return new AiLanguageSupport.LanguageOption(typed, typed);
            }
        });

        // Remembered choice first, the AI-text default only when nothing was chosen yet.
        String remembered = loadRememberedTranslationLanguage();
        AiLanguageSupport.LanguageOption selection = AiLanguageSupport.findOption(
            comboBox.getItems(),
            remembered != null ? remembered : resolveAiTextFallbackLanguageCode());
        if (selection == null && remembered != null) {
            // A previously typed free-text language is not in the list; keep it as its own entry.
            selection = new AiLanguageSupport.LanguageOption(remembered, remembered);
        }
        if (selection != null && !comboBox.getItems().contains(selection)) {
            comboBox.getItems().add(selection);
        }
        comboBox.getSelectionModel().select(selection);
        Label customLanguageHint = new Label(I18n.get("snippets.ai.translate.dialog.customHint"));
        customLanguageHint.setWrapText(true);
        customLanguageHint.setMaxWidth(320);
        customLanguageHint.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #888888;");
        VBox content = new VBox(10,
            new Label(I18n.get("snippets.ai.translate.dialog.prompt")),
            comboBox,
            customLanguageHint);
        content.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            // Read the editor too: a typed value that was never committed with Enter would
            // otherwise be silently dropped in favour of the previous selection.
            AiLanguageSupport.LanguageOption chosen = comboBox.getConverter()
                .fromString(comboBox.getEditor().getText());
            return chosen != null ? chosen : comboBox.getSelectionModel().getSelectedItem();
        });
        AiLanguageSupport.LanguageOption result = dialog.showAndWait().orElse(null);
        if (result != null) {
            saveRememberedTranslationLanguage(result.code());
        }
        return result;
    }

    private void beginMetadataGeneration(boolean overwriteExisting) {
        if (aiAssist == null || aiAssist.metadataProvider() == null) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        cancelMetadataTask();
        offeredAiRetry = null;
        offeredAiRetryText = null;
        showMetadataHint(I18n.get("snippets.ai.metadata.generating"));
        showSnippetAiHint(I18n.get("snippets.ai.metadata.generating"), true);
        setStatus(I18n.get("snippets.ai.metadata.generating"));
        Task<SuggestedSnippetMetadata> task = new Task<>() {
            @Override
            protected SuggestedSnippetMetadata call() throws Exception {
                return aiAssist.metadataProvider().generate(
                    contentArea.getText(), languageCombo.getValue(), resolveAiTextFallbackLanguageCode());
            }
        };
        metadataTask = task;
        aiRetries.put(task, retryOf(() -> beginMetadataGeneration(overwriteExisting)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.metadata.generating"), true);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            applySuggestedMetadata(task.getValue(), overwriteExisting);
            finishMetadataTask(task);
            setStatus(I18n.get("snippets.ai.metadata.generated"));
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            if (failure != null) {
                logger.warn("Snippet AI metadata suggestion failed", failure);
            }
            AiRetry retry = aiRetries.remove(task);
            finishMetadataTask(task);
            setStatus(I18n.get("snippets.ai.metadata.generateFailed"));
            offerAiRetry(retry, I18n.get("snippets.ai.retry.failed", I18n.get("snippets.ai.metadata.generateFailed")));
        });
        task.setOnCancelled(event -> finishMetadataTask(task));
        AiTaskRunner.start(task, "snippet-metadata-suggestion");
    }

    private void applySuggestedMetadata(SuggestedSnippetMetadata metadata, boolean overwriteExisting) {
        if (metadata == null) {
            return;
        }
        if ((overwriteExisting || !nameUserEdited) && metadata.fileName() != null && !metadata.fileName().isBlank()) {
            programmaticNameUpdate = true;
            try {
                nameField.setText(metadata.fileName().trim());
            } finally {
                programmaticNameUpdate = false;
            }
        }
        if ((overwriteExisting || !languageUserEdited) && metadata.language() != null && !metadata.language().isBlank()) {
            programmaticLanguageUpdate = true;
            try {
                // The model may name a language the built-in list does not offer; selectCodeLanguage
                // adds it to the combo so the detected value is visible instead of silently blank.
                selectCodeLanguage(SnippetLanguageSupport.normalizeSnippetLanguage(metadata.language()));
            } finally {
                programmaticLanguageUpdate = false;
            }
        }
        applyDetectedTextLanguage(metadata.textLanguage(), overwriteExisting);
        if ((overwriteExisting || !descriptionUserEdited) && metadata.description() != null && !metadata.description().isBlank()) {
            programmaticDescriptionUpdate = true;
            try {
                descriptionArea.setText(metadata.description().trim());
            } finally {
                programmaticDescriptionUpdate = false;
            }
        }
    }

    private void runDescriptionCorrection() {
        if (aiAssist == null || aiAssist.descriptionCorrectionProvider() == null) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String description = descriptionArea.getText();
        if (description == null || description.isBlank()) {
            setStatus(I18n.get("snippets.ai.description.empty"));
            updateAiActionAvailability();
            return;
        }
        cancelDescriptionCorrectionTask();
        offeredAiRetry = null;
        offeredAiRetryText = null;
        showSnippetAiHint(I18n.get("snippets.ai.description.correcting"), true);
        setStatus(I18n.get("snippets.ai.description.correcting"));
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return aiAssist.descriptionCorrectionProvider().correct(
                    contentArea.getText(), languageCombo.getValue(), description,
                    resolveAiTextFallbackLanguageCode());
            }
        };
        descriptionCorrectionTask = task;
        aiRetries.put(task, retryOf(this::runDescriptionCorrection));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.description.correcting"), true);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            String corrected = task.getValue();
            if (corrected != null && !corrected.isBlank()) {
                programmaticDescriptionUpdate = true;
                try {
                    descriptionArea.setText(corrected.trim());
                } finally {
                    programmaticDescriptionUpdate = false;
                }
            }
            finishDescriptionCorrectionTask(task);
            setStatus(I18n.get("snippets.ai.description.corrected"));
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            if (failure != null) {
                logger.warn("Snippet AI description correction failed", failure);
            }
            AiRetry retry = aiRetries.remove(task);
            finishDescriptionCorrectionTask(task);
            setStatus(I18n.get("snippets.ai.description.correctFailed"));
            offerAiRetry(retry, I18n.get("snippets.ai.retry.failed", I18n.get("snippets.ai.description.correctFailed")));
        });
        task.setOnCancelled(event -> finishDescriptionCorrectionTask(task));
        AiTaskRunner.start(task, "snippet-description-correction");
    }

    private void cancelAiTasks() {
        autoCompletionDelay.stop();
        cancelCompletionRequest();
        contentArea.clearGhostCompletions();
        cancelMetadataTask();
        cancelDescriptionCorrectionTask();
        cancelSnippetAiActionTask(false);
    }

    private void cancelMetadataTask() {
        if (metadataTask != null) {
            metadataTask.cancel(true);
            metadataTask = null;
        }
        hideMetadataHint();
        hideSnippetAiHintIfIdle();
    }

    private void cancelDescriptionCorrectionTask() {
        if (descriptionCorrectionTask != null) {
            descriptionCorrectionTask.cancel(true);
            descriptionCorrectionTask = null;
        }
        hideSnippetAiHintIfIdle();
    }

    private void finishMetadataTask(Task<SuggestedSnippetMetadata> task) {
        if (metadataTask == task) {
            metadataTask = null;
        }
        aiRetries.remove(task);
        hideMetadataHint();
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
    }

    private void finishDescriptionCorrectionTask(Task<String> task) {
        if (descriptionCorrectionTask == task) {
            descriptionCorrectionTask = null;
        }
        aiRetries.remove(task);
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
    }

    private void cancelSnippetAiActionTask() {
        cancelSnippetAiActionTask(true);
    }

    /**
     * Stops what the hint bar shows as running. {@code userStop} is the Stop button or Esc: the
     * stopped action then offers Retry in the hint bar and the status says it was stopped. The
     * editor closing or another action taking over cancels silently.
     */
    private void cancelSnippetAiActionTask(boolean userStop) {
        // The hint bar's Stop button also serves a ghost-text request, which is not a snippet action.
        boolean completionCancelled = cancelCompletionRequest();
        String runningText = snippetAiHintLabel.getText();
        AiRetry retry = null;
        boolean stopped = false;
        if (snippetAiActionTask != null) {
            Task<?> task = snippetAiActionTask;
            retry = aiRetries.remove(task);
            task.cancel(true);
            snippetAiActionTask = null;
            stopped = true;
        }
        if (userStop) {
            // Metadata and description correction run beside the actions; Stop ends them too.
            if (metadataTask != null) {
                AiRetry metadataRetry = aiRetries.remove(metadataTask);
                retry = retry != null ? retry : metadataRetry;
                cancelMetadataTask();
                stopped = true;
            }
            if (descriptionCorrectionTask != null) {
                AiRetry correctionRetry = aiRetries.remove(descriptionCorrectionTask);
                retry = retry != null ? retry : correctionRetry;
                cancelDescriptionCorrectionTask();
                stopped = true;
            }
        }
        if (stopped) {
            if (userStop) {
                String stoppedText = I18n.get("snippets.ai.stop.stopped", stripEllipsis(runningText));
                setStatus(I18n.get("ai.result.cancelled"));
                offerAiRetry(retry, stoppedText);
            }
            hideSnippetAiHintIfIdle();
            updateAiActionAvailability();
        } else if (completionCancelled && userStop) {
            setStatus(I18n.get("ai.result.cancelled"));
        }
    }

    /**
     * Esc in the editor or its analysis panel stops the running AI operation — the snippet action
     * (including a running Full-code analysis or apply), metadata generation, description
     * correction, or else a diagram the analysis panel is generating. A ghost-text request is left
     * to Monaco, which uses Esc to dismiss it. {@code true} = something was stopped.
     */
    private static boolean isInsideChangeReview(Object target) {
        for (javafx.scene.Node node = target instanceof javafx.scene.Node n ? n : null; node != null;
                node = node.getParent()) {
            if (node instanceof SnippetAiDiffPane) {
                return true;
            }
        }
        return false;
    }

    boolean stopRunningAiByKeyboard() {
        if (isAnyAiTaskRunning()) {
            cancelSnippetAiActionTask(true);
            return true;
        }
        return analysisController != null && analysisController.stopRunningDiagram();
    }

    private static String stripEllipsis(String text) {
        String value = text != null ? text.strip() : "";
        while (value.endsWith("…") || value.endsWith(".")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return value;
    }

    private void showMetadataHint(String text) {
        metadataHintLabel.setText(text != null ? text : "");
        metadataHintBox.setManaged(true);
        metadataHintBox.setVisible(true);
    }

    private void hideMetadataHint() {
        metadataHintLabel.setText("");
        metadataHintBox.setVisible(false);
        metadataHintBox.setManaged(false);
    }

    private void showSnippetAiHint(String text) {
        showSnippetAiHint(text, true);
    }

    private void showSnippetAiHint(String text, boolean cancellable) {
        if (!snippetAiHintBox.isVisible() || !snippetAiProgressIndicator.isVisible()) {
            // A new run: the clock starts now (a message update of a running one keeps it going).
            snippetAiStartedNanos = System.nanoTime();
        }
        snippetAiHintLabel.setText(text != null ? text : "");
        snippetAiHintBox.setManaged(true);
        snippetAiHintBox.setVisible(true);
        snippetAiProgressIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        snippetAiProgressIndicator.setManaged(true);
        snippetAiProgressIndicator.setVisible(true);
        cancelSnippetAiActionButton.setDisable(!cancellable);
        cancelSnippetAiActionButton.setManaged(cancellable);
        cancelSnippetAiActionButton.setVisible(cancellable);
        setShown(retrySnippetAiActionButton, false);
        setShown(dismissSnippetAiRetryButton, false);
        setShown(snippetAiElapsedLabel, true);
        refreshSnippetAiElapsed();
        snippetAiElapsedTicker.play();
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_ACTIVE_STYLE);
    }

    /** Hides the running state; an offered Retry keeps the bar, as its stopped/failed strip. */
    private void hideSnippetAiHint() {
        snippetAiElapsedTicker.stop();
        if (offeredAiRetry != null) {
            showAiRetryStrip();
            return;
        }
        snippetAiHintLabel.setText("");
        snippetAiHintBox.setManaged(false);
        snippetAiHintBox.setVisible(false);
        snippetAiProgressIndicator.setVisible(false);
        cancelSnippetAiActionButton.setDisable(true);
        cancelSnippetAiActionButton.setManaged(false);
        cancelSnippetAiActionButton.setVisible(false);
        setShown(snippetAiElapsedLabel, false);
        setShown(retrySnippetAiActionButton, false);
        setShown(dismissSnippetAiRetryButton, false);
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_IDLE_STYLE);
    }

    private void hideSnippetAiHintIfIdle() {
        if (!isAnyAiTaskRunning() && completionTask == null) {
            hideSnippetAiHint();
        }
    }

    private void refreshSnippetAiElapsed() {
        snippetAiElapsedLabel.setText(AiStopRetrySupport.formatElapsed(
            (System.nanoTime() - snippetAiStartedNanos) / 1_000_000L));
    }

    /**
     * After a stop, a failure or a timeout: "Stopped: … / Failed: …" with Retry in the hint bar.
     * {@code retry} {@code null} (an action that offers its own way forward, e.g. an apply run with
     * its recovery strip) leaves the bar alone.
     */
    private void offerAiRetry(AiRetry retry, String text) {
        if (retry == null || editorClosed) {
            return;
        }
        offeredAiRetry = retry;
        offeredAiRetryText = text;
        if (!isAnyAiTaskRunning() && completionTask == null) {
            showAiRetryStrip();
        }
    }

    private void showAiRetryStrip() {
        AiRetry retry = offeredAiRetry;
        if (retry == null) {
            return;
        }
        String blocked = retry.blockedReason();
        snippetAiHintLabel.setText(blocked != null
            ? offeredAiRetryText + " – " + blocked
            : offeredAiRetryText);
        snippetAiHintBox.setManaged(true);
        snippetAiHintBox.setVisible(true);
        setShown(snippetAiProgressIndicator, false);
        cancelSnippetAiActionButton.setDisable(true);
        cancelSnippetAiActionButton.setManaged(false);
        cancelSnippetAiActionButton.setVisible(false);
        setShown(snippetAiElapsedLabel, false);
        setShown(retrySnippetAiActionButton, true);
        retrySnippetAiActionButton.setDisable(blocked != null || isAnyAiTaskRunning() || isAiChangeReviewOpen());
        setShown(dismissSnippetAiRetryButton, true);
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_OUTCOME_STYLE);
    }

    /** Retry: runs the stopped/failed action again with the inputs it had (re-selecting its range). */
    private void runOfferedAiRetry() {
        AiRetry retry = offeredAiRetry;
        if (retry == null) {
            return;
        }
        String blocked = retry.blockedReason();
        if (blocked != null) {
            setStatus(blocked);
            showAiRetryStrip();
            return;
        }
        if (isAnyAiTaskRunning() || isAiChangeReviewOpen()) {
            setStatus(I18n.get("snippets.ai.analysis.panel.busy"));
            return;
        }
        dismissAiRetry();
        retry.action().run();
    }

    private void dismissAiRetry() {
        offeredAiRetry = null;
        offeredAiRetryText = null;
        if (!isAnyAiTaskRunning() && completionTask == null) {
            hideSnippetAiHint();
        }
    }

    /** The Retry currently offered by the hint bar (test seam). */
    AiRetry offeredAiRetry() {
        return offeredAiRetry;
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /**
     * How to repeat an AI action exactly as it ran: {@code action} re-runs it with the inputs it
     * captured; {@code blockedCheck} (optional) says why that is no longer possible — the text it
     * worked on changed — or returns {@code null} when Retry may run.
     */
    record AiRetry(Runnable action, Supplier<String> blockedCheck) {
        AiRetry {
            java.util.Objects.requireNonNull(action, "action");
        }

        String blockedReason() {
            return blockedCheck != null ? blockedCheck.get() : null;
        }
    }

    /** A Retry for an action on the whole snippet: it runs on the current content. */
    private static AiRetry retryOf(Runnable action) {
        return new AiRetry(action, null);
    }

    /**
     * A Retry for an action on the current selection (or caret): it selects the same range again
     * first, and is blocked once the text in that range changed.
     */
    private AiRetry retryOnSelection(Runnable action) {
        IndexRange range = contentArea.getSelection();
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        int start = range != null ? Math.max(0, Math.min(range.getStart(), content.length())) : 0;
        int end = range != null ? Math.max(start, Math.min(range.getEnd(), content.length())) : 0;
        String selected = content.substring(start, end);
        return new AiRetry(() -> {
            contentArea.selectRange(start, end);
            action.run();
        }, () -> selectionStillMatches(contentArea.getText(), start, end, selected)
            ? null
            : I18n.get("snippets.ai.retry.selectionChanged"));
    }

    /** Whether {@code content} still holds {@code expected} at {@code [start, end)}. */
    static boolean selectionStillMatches(String content, int start, int end, String expected) {
        String value = content != null ? content : "";
        if (start < 0 || end < start || end > value.length()) {
            return false;
        }
        return value.substring(start, end).equals(expected != null ? expected : "");
    }

    private void finishSnippetAiAction(Task<?> task) {
        if (snippetAiActionTask == task) {
            snippetAiActionTask = null;
        }
        aiRetries.remove(task);
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
        updateOneLinerButtonState();
    }

    /**
     * Uniform failure handling for a snippet AI action's background task. Logs the actual cause —
     * previously every handler discarded {@code task.getException()}, so a misconfigured profile
     * (a {@link de.kortty.core.FailingAiService} throwing "Select a model…") or any provider/network
     * error left NO trace in the log and only a generic "…failed" status. When the cause carries a
     * message it is surfaced to the user (e.g. the configuration error that breaks every AI function)
     * instead of the bare generic status. The typed output-limit failure is mapped to its own
     * localized message so the core exception text never becomes UI copy. Every failure — a timeout
     * included — offers Retry in the hint bar; a stop that surfaced as a failure is reported as a stop.
     */
    private void handleSnippetAiActionFailure(Task<?> task, String genericFailedStatus) {
        Throwable failure = task != null ? task.getException() : null;
        AiRetry retry = task != null ? aiRetries.remove(task) : null;
        if (failure instanceof de.kortty.core.AiCancelledException) {
            setStatus(I18n.get("ai.result.cancelled"));
            finishSnippetAiAction(task);
            offerAiRetry(retry, I18n.get("snippets.ai.stop.stopped", stripEllipsis(snippetAiHintLabel.getText())));
            return;
        }
        if (failure != null) {
            logger.warn("Snippet AI action failed ({})", genericFailedStatus, failure);
        }
        String status;
        if (isResponseStreamInterruptedFailure(failure)) {
            status = I18n.get("snippets.ai.streamInterrupted");
        } else if (isOutputTokenLimitFailure(failure)) {
            status = I18n.get("snippets.ai.outputLimitReached");
        } else if (isFullReplacementRejectedFailure(failure)) {
            status = I18n.get("snippets.ai.fix.degenerate");
        } else if (isIncompleteMandatoryRequirementsFailure(failure)) {
            status = I18n.get(
                "snippets.ai.analysis.fix.incompleteHardening",
                String.join("; ", incompleteMandatoryRequirementLabels(failure)));
        } else {
            String detail = failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
                ? failure.getMessage().strip()
                : null;
            status = detail != null
                ? I18n.get("snippets.ai.actionFailed", shortenStatusMessage(detail))
                : genericFailedStatus;
        }
        setStatus(status);
        finishSnippetAiAction(task);
        offerAiRetry(retry, I18n.get("snippets.ai.retry.failed", status));
    }

    static boolean isResponseStreamInterruptedFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof de.kortty.core.SnippetAiWorkflowSupport.ResponseStreamInterruptedException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    static boolean isOutputTokenLimitFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof de.kortty.core.SnippetAiWorkflowSupport.OutputTokenLimitReachedException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    static boolean isFullReplacementRejectedFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SnippetAiWorkflowSupport.FullReplacementRejectedException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    static boolean isIncompleteMandatoryRequirementsFailure(Throwable failure) {
        return findIncompleteMandatoryRequirementsFailure(failure) != null;
    }

    static List<String> incompleteMandatoryRequirementIds(Throwable failure) {
        SnippetAiWorkflowSupport.IncompleteMandatoryRequirementsException incomplete =
            findIncompleteMandatoryRequirementsFailure(failure);
        return incomplete != null ? incomplete.missingRequirementIds() : List.of();
    }

    /** The unmet requirements as {@code id (rule)}, so the status names what was not implemented. */
    static List<String> incompleteMandatoryRequirementLabels(Throwable failure) {
        SnippetAiWorkflowSupport.IncompleteMandatoryRequirementsException incomplete =
            findIncompleteMandatoryRequirementsFailure(failure);
        return incomplete != null ? incomplete.missingRequirementLabels() : List.of();
    }

    private static SnippetAiWorkflowSupport.IncompleteMandatoryRequirementsException
            findIncompleteMandatoryRequirementsFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SnippetAiWorkflowSupport.IncompleteMandatoryRequirementsException incomplete) {
                return incomplete;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    private void runOneLiner(boolean compact) {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        SnippetOneLiner.OneLinerResult r = compact
                ? SnippetOneLiner.toCompact(text, lang)
                : SnippetOneLiner.toEmbedded(text, lang);
        if (!r.isOk()) {
            if (compact && shouldGenerateCompactOneLinerWithAi(r)) {
                runCompactOneLinerGeneration(text, lang);
                return;
            }
            setStatus(I18n.get(r.errorKey(), r.errorArgs()));
            return;
        }
        copyOneLinerToClipboard(r.line());
    }

    private boolean shouldGenerateCompactOneLinerWithAi(SnippetOneLiner.OneLinerResult localResult) {
        return localResult != null
            && hasOneLinerProvider()
            && !"snippets.oneliner.empty".equals(localResult.errorKey())
            && ensureSnippetAiDataNoticeAccepted(false);
    }

    private void runCompactOneLinerGeneration(String text, String lang) {
        if (text == null || text.isBlank()) {
            setStatus(I18n.get("snippets.oneliner.empty"));
            return;
        }
        String instructions = additionalInstructions();
        Task<SnippetAiResponseSupport.OneLinerSuggestion> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.OneLinerSuggestion call() throws Exception {
                return aiAssist.oneLinerProvider().generate(new OneLinerRequest(
                    text,
                    lang,
                    resolveAiTextFallbackLanguageCode(),
                    instructions));
            }
        };
        // The rewrite must not silently translate the snippet's own comments and messages;
        // an undetectable language is a question for the user, not a guess.
        if (!applyCodeTextLanguage(true)) {
            return;
        }
        beginSnippetAiAction(task, retryOf(() -> runCompactOneLinerGeneration(text, lang)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.oneliner.generating"));
            setStatus(I18n.get("snippets.oneliner.generating"));
            updateAiActionAvailability();
            updateOneLinerButtonState();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.OneLinerSuggestion suggestion = task.getValue();
            if (suggestion == null || !suggestion.isUsable()) {
                setStatus(I18n.get("snippets.oneliner.generateFailed"));
                return;
            }
            copyOneLinerToClipboard(suggestion.command());
        });
        task.setOnFailed(event ->
            handleSnippetAiActionFailure(task, I18n.get("snippets.oneliner.generateFailed")));
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        AiTaskRunner.start(task, "snippet-ai-one-liner");
    }

    private void copyOneLinerToClipboard(String line) {
        de.kortty.core.KorttyClipboard.setText(line);
        setStatus(I18n.get("snippets.oneliner.success"));
    }

    private void runFormat() {
        runFormat(null);
    }

    private void runFormatToRulerWidth() {
        int lineWidth = columnRuler != null ? columnRuler.getLimitColumn() : 0;
        if (lineWidth <= 0) {
            setStatus(I18n.get("snippets.ruler.noLimit"));
            return;
        }
        runFormat(lineWidth);
    }

    private void runFormat(Integer maxLineLength) {
        if (isAiChangeReviewOpen()) {
            setStatus(I18n.get("snippets.ai.change.decideFirst"));
            return;
        }
        String lang = languageCombo.getValue();
        CodeFormatterService.FormatterInfo formatterInfo = CodeFormatterService.getFormatterInfo(lang);
        if (formatterInfo == null) {
            if (confirmAiFormatFallback(I18n.get("editor.format.notSupported", lang != null ? lang : "plain"))) {
                runAiFormat(maxLineLength, maxLineLength == null ? currentFormatScope() : AiFormatScope.FULL_CONTENT);
                return;
            }
            setStatus(I18n.get("editor.format.notSupported", lang != null ? lang : "plain"));
            return;
        }
        if (maxLineLength != null && !CodeFormatterService.supportsLineWidth(lang)) {
            String reason = I18n.get("snippets.ruler.formatNotSupported", lang != null ? lang : "plain");
            boolean aiFallbackAvailable = hasCodeImprovementProvider();
            if (confirmAiFormatFallback(reason)) {
                runAiFormat(maxLineLength, AiFormatScope.FULL_CONTENT);
                return;
            }
            if (aiFallbackAvailable) {
                showAlert(reason, Alert.AlertType.WARNING);
            }
            setStatus(I18n.get("snippets.ruler.formatNotSupported", lang != null ? lang : "plain"));
            return;
        }
        if (!CodeFormatterService.isFormatterAvailable(formatterInfo)) {
            boolean aiFallbackAvailable = hasCodeImprovementProvider();
            if (confirmAiFormatFallback(I18n.get("editor.format.unavailable", formatterInfo.displayName()))) {
                runAiFormat(maxLineLength, maxLineLength == null ? currentFormatScope() : AiFormatScope.FULL_CONTENT);
                return;
            }
            if (aiFallbackAvailable) {
                showFormatterUnavailable(formatterInfo);
            }
            return;
        }
        int start = contentArea.getSelection().getStart();
        int end = contentArea.getSelection().getEnd();
        String baseContent = contentArea.getText();
        String text;
        boolean selectionOnly = maxLineLength == null && (end > start);
        if (selectionOnly) {
            text = contentArea.getSelectedText();
            if (text == null || text.isBlank()) return;
        } else {
            text = contentArea.getText();
            if (text == null || text.isBlank()) return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return CodeFormatterService.formatOrThrow(text, lang, maxLineLength);
            }
        };
        task.setOnRunning(event -> {
            setStatus(I18n.get("editor.format.running"));
            formatBtn.setDisable(true);
        });
        task.setOnSucceeded(event -> {
            updateFormatLintButtonState();
            String formatted = task.getValue();
            if (formatted == null) {
                setStatus(I18n.get("editor.format.failed"));
                return;
            }
            if (formatted.equals(text)) {
                if (maxLineLength == null) {
                    setStatus(I18n.get("editor.format.noChanges"));
                } else {
                    setFormatSuccessStatus(formatted, maxLineLength);
                }
                return;
            }
            if (maxLineLength != null) {
                showLineWidthFormatPreview(baseContent, text, formatted, lang, maxLineLength, selectionOnly, start, end);
                return;
            }
            applyFormattedText(selectionOnly, start, end, formatted);
            setFormatSuccessStatus(formatted, maxLineLength);
        });
        task.setOnFailed(event -> {
            updateFormatLintButtonState();
            setStatus(I18n.get("editor.format.failed"));
            Throwable failure = task.getException();
            showAlert(
                I18n.get("editor.format.error", failure != null ? failure.getMessage() : I18n.get("editor.format.failed")),
                Alert.AlertType.ERROR);
        });
        Thread thread = new Thread(task, "snippet-code-formatter");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The line-width format result, reviewed in the editor area like every AI change. It is a local
     * formatter run, so a re-run on changed content is simply Format again.
     */
    private void showLineWidthFormatPreview(
        String baseContent,
        String originalText,
        String formattedText,
        String language,
        int maxLineLength,
        boolean selectionOnly,
        int start,
        int end) {

        showAiChangeReview(
            I18n.get("snippets.ruler.formatPreviewTitle"),
            I18n.get("snippets.ruler.formatPreviewSummary", maxLineLength),
            baseContent,
            originalText,
            formattedText,
            language,
            null,
            null,
            () -> {
                applyFormattedText(selectionOnly, start, end, formattedText);
                setFormatSuccessStatus(formattedText, maxLineLength);
            });
    }

    private void applyFormattedText(boolean selectionOnly, int start, int end, String formattedText) {
        clearLastAiChangeSnapshot();
        programmaticContentUpdate = true;
        try {
            if (selectionOnly) {
                contentArea.replaceText(start, end, formattedText);
                contentArea.selectRange(start, start + formattedText.length());
            } else {
                int caret = Math.min(contentArea.getCaretPosition(), formattedText.length());
                contentArea.replaceText(formattedText);
                contentArea.moveTo(caret);
            }
        } finally {
            programmaticContentUpdate = false;
        }
        applyHighlighting();
    }

    private AiFormatScope currentFormatScope() {
        IndexRange selection = contentArea.getSelection();
        return selection != null && selection.getLength() > 0
            ? AiFormatScope.SELECTION
            : AiFormatScope.FULL_CONTENT;
    }

    private boolean confirmAiFormatFallback(String reason) {
        if (!hasCodeImprovementProvider()) {
            showAlert(I18n.get("snippets.ai.format.unavailable", reason), Alert.AlertType.WARNING);
            return false;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return false;
        }
        return confirmAiFallback(
            I18n.get("snippets.ai.format.fallback.title"),
            I18n.get("snippets.ai.format.fallback.header"),
            I18n.get("snippets.ai.format.fallback.content", reason));
    }

    private boolean confirmAiLintFallback(String reason) {
        if (!hasCodeReviewProvider()) {
            showAlert(I18n.get("snippets.ai.lint.unavailable", reason), Alert.AlertType.WARNING);
            return false;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return false;
        }
        return confirmAiFallback(
            I18n.get("snippets.ai.lint.fallback.title"),
            I18n.get("snippets.ai.lint.fallback.header"),
            I18n.get("snippets.ai.lint.fallback.content", reason));
    }

    private boolean confirmAiFallback(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        initAlertOwner(alert);
        Optional<ButtonType> response = alert.showAndWait();
        return response.isPresent() && response.get() == ButtonType.OK;
    }

    private void runAiFormat(Integer maxLineLength, AiFormatScope scope) {
        runAiFormat(maxLineLength, scope, null);
    }

    private void runAiFormat(Integer maxLineLength, AiFormatScope scope, String aiProfileId) {
        if (aiActionBlocked()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        boolean selectionOnly = scope == AiFormatScope.SELECTION && selection != null && selection.getLength() > 0;
        int replacementStart = selectionOnly ? selection.getStart() : 0;
        int replacementEnd = selectionOnly ? selection.getEnd() : fullContent.length();
        String targetText = selectionOnly ? contentArea.getSelectedText() : fullContent;
        if (targetText == null || targetText.isBlank()) {
            return;
        }
        String lang = languageCombo.getValue();
        String theme = maxLineLength != null
            ? I18n.get("snippets.ai.format.widthTheme", maxLineLength)
            : I18n.get("snippets.ai.format.theme");
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.codeImprovementProvider().improve(new CodeImprovementRequest(
                    fullContent,
                    lang,
                    targetText,
                    resolveAiTextFallbackLanguageCode(),
                    theme,
                    additionalInstructions(),
                    true,
                    aiProfileId));
            }
        };
        // The rewrite must not silently translate the snippet's own comments and messages;
        // an undetectable language is a question for the user, not a guess.
        if (!applyCodeTextLanguage(true)) {
            return;
        }
        beginSnippetAiAction(task, scope == AiFormatScope.SELECTION
            ? retryOnSelection(() -> runAiFormat(maxLineLength, scope, aiProfileId))
            : retryOf(() -> runAiFormat(maxLineLength, scope, aiProfileId)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.format.running"));
            setStatus(I18n.get("snippets.ai.format.running"));
            updateAiActionAvailability();
            updateFormatLintButtonState();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
            SnippetAiResponseSupport.CodeImprovement improvement = task.getValue();
            if (improvement == null || !improvement.isUsable()) {
                setStatus(I18n.get("snippets.ai.format.empty"));
                return;
            }
            showAiChangeReview(
                I18n.get("snippets.ai.format.diffTitle"),
                improvement.summary(),
                fullContent,
                targetText,
                improvement.replacement(),
                lang,
                // A selection-scoped re-run is not offered on changed content: the old selection is gone.
                selectionOnly ? null : () -> runAiFormat(maxLineLength, scope, aiProfileId),
                withProfileRerun(aiProfileId,
                    profileSwitchingSupported() ? id -> runAiFormat(maxLineLength, scope, id) : null),
                () -> {
                    applyAiContentChange(
                        replacementStart,
                        replacementEnd,
                        improvement.replacement(),
                        I18n.get("snippets.ai.toggle.action.format"));
                    if (maxLineLength != null) {
                        setFormatSuccessStatus(improvement.replacement(), maxLineLength);
                    } else {
                        setStatus(I18n.get("snippets.ai.format.applied"));
                    }
                });
        });
        task.setOnFailed(event -> {
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.format.failed"));
            updateFormatLintButtonState();
        });
        task.setOnCancelled(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
        });
        AiTaskRunner.start(task, "snippet-ai-format");
    }

    private void setFormatSuccessStatus(String formatted, Integer maxLineLength) {
        if (maxLineLength == null) {
            setStatus(I18n.get("editor.format.success"));
            return;
        }
        int overLimitLineCount = countLinesLongerThan(formatted, maxLineLength);
        if (overLimitLineCount > 0) {
            setStatus(I18n.get("snippets.ruler.formatPartial", overLimitLineCount, maxLineLength));
            return;
        }
        setStatus(I18n.get("snippets.ruler.formatSuccess", maxLineLength));
    }

    private static int countLinesLongerThan(String text, int maxLineLength) {
        if (text == null || maxLineLength <= 0) {
            return 0;
        }
        int count = 0;
        int currentLength = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (currentLength > maxLineLength) {
                    count++;
                }
                currentLength = 0;
            } else if (c != '\r') {
                currentLength++;
            }
        }
        if (currentLength > maxLineLength) {
            count++;
        }
        return count;
    }

    private void showFormatterUnavailable(CodeFormatterService.FormatterInfo formatterInfo) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.get("editor.format.title"));
        alert.setHeaderText(I18n.get("editor.format.unavailable", formatterInfo.displayName()));
        alert.setContentText(formatterInfo.unavailableReason() != null
            ? formatterInfo.unavailableReason()
            : I18n.get("editor.format.installHint", formatterInfo.installHint()));
        alert.initOwner(resolveAlertOwner());
        alert.showAndWait();
        setStatus(I18n.get("editor.format.unavailable", formatterInfo.displayName()));
    }

    private void runLint() {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        if (!SnippetLinter.isSupported(lang)) {
            String reason = I18n.get("editor.lint.notAvailable", lang != null ? lang : "plain");
            if (confirmAiLintFallback(reason)) {
                runAiSyntaxCheck();
            } else {
                setStatus(reason);
            }
            return;
        }
        SnippetLinter.LintResult result = SnippetLinter.lint(text != null ? text : "", lang);
        if (result.isSuccess()) {
            showAlert(I18n.get("editor.lint.success"), Alert.AlertType.INFORMATION);
        } else {
            showAlert(I18n.get("editor.lint.errors") + "\n\n" + result.getMessage(), Alert.AlertType.ERROR);
        }
    }

    private void runAiSyntaxCheck() {
        runAiSyntaxCheck(null);
    }

    private void runAiSyntaxCheck(String aiProfileId) {
        if (!hasCodeReviewProvider() || aiActionBlocked()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        String lang = languageCombo.getValue();
        Task<List<SnippetAiResponseSupport.CodeReviewFinding>> task = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.CodeReviewFinding> call() throws Exception {
                return aiAssist.codeReviewProvider().review(new CodeReviewRequest(
                    fullContent,
                    lang,
                    fullContent,
                    true,
                    resolveAiTextFallbackLanguageCode(),
                    I18n.get("snippets.ai.lint.theme"),
                    additionalInstructions(),
                    aiProfileId));
            }
        };
        beginSnippetAiAction(task, retryOf(() -> runAiSyntaxCheck(aiProfileId)));
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.lint.running"));
            setStatus(I18n.get("snippets.ai.lint.running"));
            updateAiActionAvailability();
            updateFormatLintButtonState();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
            List<SnippetAiResponseSupport.CodeReviewFinding> findings = task.getValue();
            if (findings == null || findings.isEmpty()) {
                showAlert(I18n.get("snippets.ai.lint.noFindings"), Alert.AlertType.INFORMATION);
                setStatus(I18n.get("snippets.ai.lint.noFindings"));
                return;
            }
            showChildWindow(new SnippetAiReviewDialog(
                childWindowOwner(),
                I18n.get("snippets.ai.lint.title"),
                findings,
                aiProfileId,
                profileSwitchingSupported() ? this::runAiSyntaxCheck : null));
            setStatus(I18n.get("snippets.ai.lint.ready"));
        });
        task.setOnFailed(event -> {
            handleSnippetAiActionFailure(task, I18n.get("snippets.ai.lint.failed"));
            updateFormatLintButtonState();
        });
        task.setOnCancelled(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
        });
        AiTaskRunner.start(task, "snippet-ai-syntax-check");
    }

    private void showAlert(String message) {
        showAlert(message, Alert.AlertType.INFORMATION);
    }

    private void showAlert(String message, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(I18n.get("snippets.editTitle"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(resolveAlertOwner());
        alert.showAndWait();
    }

    /**
     * The window an alert raised by this dialog should own: the dialog's stable owner (every caller sets
     * one via {@code initOwner}), falling back to its scene's window, else {@code null}. This must never
     * dereference a possibly-detached scene directly — a save failure surfaced from {@code DIALOG_HIDDEN}
     * (see {@link #showNonBlocking}) runs after {@code close()} has torn the scene down, so
     * {@code getDialogPane().getScene()} is {@code null} at that point.
     */
    private javafx.stage.Window resolveAlertOwner() {
        // A hosted pane can be dragged to another main window with its tab; the window it is shown
        // in now beats the owner captured when it was opened.
        if (isHostedInTab() && getDialogPane().getScene() != null
            && getDialogPane().getScene().getWindow() != null) {
            return getDialogPane().getScene().getWindow();
        }
        javafx.stage.Window owner = getOwner();
        if (owner == null) {
            javafx.scene.Scene scene = getDialogPane().getScene();
            owner = scene != null ? scene.getWindow() : null;
        }
        return owner;
    }

    private void setStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message != null ? message : "");
        }
    }

    private static String shortenStatusMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 177) + "...";
    }

    private void refreshBlockCaretSoon() {
        EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
        // Run once more on the next pulse, because Monaco Editor may recreate caret after key handling.
        Platform.runLater(() -> EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
    }

    private boolean isDescendantOf(Node node, Region parent) {
        if (node == null || parent == null) {
            return false;
        }
        if (node == parent) {
            return true;
        }
        if (node.getParent() != null) {
            return isDescendantOf(node.getParent(), parent);
        }
        return false;
    }

    // ---- Monaco Syntax Highlighting ----

    private void applyHighlighting() {
        if (contentArea != null && languageCombo != null) {
            contentArea.setLanguage(languageCombo.getValue());
        }
    }

    // ---- Integrated Full-code analysis: side panel and editor-area review ----

    /** Adds the analysis side panel beside the editor area (once), at its remembered width. */
    private void showAnalysisSidePanel(Region panel) {
        if (analysisWorkbench.panel() == panel) {
            return;
        }
        double width = SnippetAnalysisController.storedPanelWidth();
        panel.setPrefWidth(width);
        panel.setMaxWidth(Region.USE_PREF_SIZE);
        analysisWorkbench.setPanel(panel);
        widenWindowForAnalysisPanel(width);
        if (embedding != null) {
            embedding.analysisPanelShown(this, width);
        }
    }

    private void hideAnalysisSidePanel() {
        boolean shown = analysisWorkbench.isPanelShown();
        analysisWorkbench.setPanel(null);
        if (shown && embedding != null && !editorClosed) {
            embedding.analysisPanelHidden(this);
        }
    }

    /** The drag handle between the editor area and the analysis panel; dragging sets the panel width. */
    private Region buildAnalysisDivider() {
        Region divider = new Region();
        divider.setId("snippet-analysis-divider");
        divider.setMinWidth(SnippetEditorWorkbench.DIVIDER_WIDTH);
        divider.setPrefWidth(SnippetEditorWorkbench.DIVIDER_WIDTH);
        divider.setMaxWidth(SnippetEditorWorkbench.DIVIDER_WIDTH);
        divider.setCursor(javafx.scene.Cursor.H_RESIZE);
        divider.setStyle("-fx-background-color: rgba(128,128,128,0.22);");
        double[] drag = new double[2];
        divider.setOnMousePressed(event -> {
            Region panel = analysisSidePanelNode();
            drag[0] = event.getScreenX();
            drag[1] = panel != null ? panel.getWidth() : SnippetAnalysisController.DEFAULT_PANEL_WIDTH;
        });
        divider.setOnMouseDragged(event -> {
            Region panel = analysisSidePanelNode();
            if (panel == null) {
                return;
            }
            double maximum = analysisWorkbench.maximumPanelWidth(SnippetAnalysisController.MIN_PANEL_WIDTH);
            double width = drag[1] - (event.getScreenX() - drag[0]);
            panel.setPrefWidth(Math.max(SnippetAnalysisController.MIN_PANEL_WIDTH, Math.min(maximum, width)));
        });
        return divider;
    }

    private Region analysisSidePanelNode() {
        return analysisWorkbench.panel();
    }

    /** Whether the analysis side panel currently sits beside the editor area. */
    boolean isAnalysisSidePanelShown() {
        return analysisWorkbench.isPanelShown();
    }

    /** The width the analysis panel asks for (stored or dragged); 0 while it is hidden. */
    double analysisSidePanelPreferredWidth() {
        Region panel = analysisWorkbench.panel();
        return panel != null ? panel.getPrefWidth() : 0;
    }

    /** The laid-out width of the editor's content row (editor area plus panel); 0 before layout. */
    double workbenchWidth() {
        return analysisWorkbench.getWidth();
    }

    /**
     * A windowed editor grows once by the panel's width when the screen has room, so opening the
     * analysis does not squeeze the code. A hosted editor shares its tab instead.
     */
    private void widenWindowForAnalysisPanel(double width) {
        if (widenedForAnalysisPanel || isHostedInTab() || embedding != null) {
            return;
        }
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (!(window instanceof Stage stage) || !stage.isShowing() || stage.isMaximized() || stage.isFullScreen()) {
            return;
        }
        widenedForAnalysisPanel = true;
        try {
            javafx.geometry.Rectangle2D bounds = Screen.getScreensForRectangle(
                    stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()))
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
            double target = Math.min(stage.getWidth() + width, bounds.getWidth());
            if (target <= stage.getWidth()) {
                return;
            }
            double x = stage.getX();
            if (x + target > bounds.getMaxX()) {
                x = Math.max(bounds.getMinX(), bounds.getMaxX() - target);
            }
            stage.setX(x);
            stage.setWidth(target);
        } catch (RuntimeException e) {
            logger.debug("Could not widen the snippet editor for the analysis panel", e);
        }
    }

    /**
     * Shows a change review instead of the editor form until it is decided. Only one review holds the
     * editor area at a time: one that arrives while another is open waits in line (the status line
     * says so) and comes on screen when that one is decided — it never silently replaces it.
     */
    private void showInEditorArea(SnippetAiDiffPane pane) {
        if (pane == null || editorClosed) {
            return;
        }
        if (editorAreaOverlay == pane) {
            pane.requestFocus();
            return;
        }
        if (editorAreaOverlay != null) {
            if (!waitingEditorAreaPanes.contains(pane)) {
                waitingEditorAreaPanes.add(pane);
            }
            setStatus(I18n.get("snippets.ai.change.queued"));
            updateAiActionAvailability();
            return;
        }
        displayInEditorArea(pane);
    }

    /**
     * Puts the review on screen. Esc is consumed there: it would otherwise fire the dialog's Cancel
     * and close the editor without the unsaved prompt.
     */
    private void displayInEditorArea(SnippetAiDiffPane pane) {
        editorAreaOverlay = pane;
        if (pane.getProperties().putIfAbsent("kortty.escapeGuard", Boolean.TRUE) == null) {
            pane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    event.consume();
                }
            });
        }
        editorFormLayout.setVisible(false);
        editorAreaStack.getChildren().add(pane);
        if (pane == aiChangeReviewPane && aiChangeReviewGuard != null) {
            // It may have waited behind another review whose Accept changed the content.
            aiChangeReviewGuard.run();
        }
        Platform.runLater(() -> {
            pane.fitSummaryHeight();
            pane.requestFocus();
        });
    }

    /** The review is decided: the next waiting one takes the editor area, else the form comes back. */
    private void restoreEditorArea(SnippetAiDiffPane pane) {
        if (pane != null && waitingEditorAreaPanes.remove(pane)) {
            updateAiActionAvailability();
            return;
        }
        if (pane != null && editorAreaOverlay != pane) {
            return;
        }
        if (editorAreaOverlay != null) {
            editorAreaStack.getChildren().remove(editorAreaOverlay);
            editorAreaOverlay = null;
        }
        SnippetAiDiffPane next = waitingEditorAreaPanes.poll();
        if (next != null && !editorClosed) {
            displayInEditorArea(next);
            return;
        }
        editorFormLayout.setVisible(true);
        Platform.runLater(this::focusEditor);
    }

    /** Whether an AI change waits for a decision in the editor area (on screen or in line). */
    private boolean isAiChangeReviewOpen() {
        return editorAreaOverlay != null || !waitingEditorAreaPanes.isEmpty();
    }

    /**
     * Whether a new AI action must not start now, saying why in the status line: another one is
     * running, or an AI change still waits for Accept or Reject. The result windows (security report,
     * description, syntax check) are non-modal, so their Apply and Re-run buttons can be pressed at
     * any time and have to pass this check like the menu items do.
     */
    private boolean aiActionBlocked() {
        if (editorClosed) {
            return true;
        }
        if (isAiChangeReviewOpen()) {
            setStatus(I18n.get("snippets.ai.change.decideFirst"));
            if (editorAreaOverlay != null) {
                editorAreaOverlay.requestFocus();
            }
            return true;
        }
        if (isAnyAiTaskRunning()) {
            setStatus(I18n.get("snippets.ai.analysis.panel.busy"));
            return true;
        }
        return false;
    }

    /**
     * Reviews an ad-hoc AI change in the editor area — the side-by-side diff with Accept and Reject —
     * instead of a window that blocks in a nested event loop. Nothing is applied until Accept, and
     * Accept only applies while the editor still holds exactly {@code baseContent}, the text the
     * change was computed from (otherwise it would overwrite the edits made meanwhile; the review then
     * says so and, when {@code rerunOnCurrent} is given, offers to run the action again).
     *
     * @param originalText the part of {@code baseContent} the change replaces (the left diff side)
     * @param setup        optional extras (explanations, the profile re-run)
     * @param onAccept     applies the change; runs after the review has given the editor area back
     */
    private void showAiChangeReview(
            String heading,
            String summary,
            String baseContent,
            String originalText,
            String replacementText,
            String language,
            Runnable rerunOnCurrent,
            Consumer<SnippetAiDiffPane> setup,
            Runnable onAccept) {

        if (editorClosed) {
            return;
        }
        if (aiChangeReviewPane != null) {
            // Cannot happen through the menus (they are disabled while a review is open); a late
            // local-formatter result is the one path left, and it must not replace the open review.
            logger.debug("An AI change review is already open; dropping the newer result");
            setStatus(I18n.get("snippets.ai.change.decideFirst"));
            return;
        }
        String base = baseContent != null ? baseContent : "";
        SnippetAiDiffPane pane = new SnippetAiDiffPane(
            summary, originalText, replacementText, language, editorSettings, true);
        pane.setReviewLaterAvailable(false);
        pane.setHeading(heading);
        if (setup != null) {
            setup.accept(pane);
        }
        Runnable guard = () -> guardAiChangeReview(pane, base, rerunOnCurrent);
        aiChangeReviewPane = pane;
        aiChangeReviewGuard = guard;
        pane.setOnDecision(decision -> {
            if (aiChangeReviewPane != pane) {
                return;
            }
            if (decision == SnippetAiDiffPane.Decision.ACCEPT) {
                if (!contentUnchangedSince(base)) {
                    guard.run();
                    return;
                }
                closeAiChangeReview(pane);
                onAccept.run();
                return;
            }
            closeAiChangeReview(pane);
            setStatus(I18n.get("snippets.ai.change.rejected"));
        });
        guard.run();
        showInEditorArea(pane);
        updateAiActionAvailability();
    }

    /** Blocks Accept while the content differs from the text the change was computed from. */
    private void guardAiChangeReview(SnippetAiDiffPane pane, String baseContent, Runnable rerunOnCurrent) {
        if (contentUnchangedSince(baseContent)) {
            pane.showBlockingNotice(null);
            return;
        }
        Runnable rerun = rerunOnCurrent == null ? null : () -> {
            closeAiChangeReview(pane);
            Platform.runLater(rerunOnCurrent);
        };
        pane.showBlockingNotice(I18n.get("snippets.ai.change.contentChanged"),
            rerun != null ? I18n.get("snippets.ai.change.rerunOnCurrent") : null, rerun);
    }

    /** Ends the ad-hoc review without applying anything (decided, re-run, or the editor closes). */
    private void closeAiChangeReview(SnippetAiDiffPane pane) {
        if (pane == null || aiChangeReviewPane != pane) {
            return;
        }
        aiChangeReviewPane = null;
        aiChangeReviewGuard = null;
        restoreEditorArea(pane);
        pane.dispose();
        updateAiActionAvailability();
    }

    /** The profile re-run of an ad-hoc review: the review is discarded first, then the action runs again. */
    private Consumer<SnippetAiDiffPane> withProfileRerun(String activeProfileId, Consumer<String> onRerun) {
        return pane -> {
            if (onRerun != null) {
                pane.setRerunHandler(activeProfileId, onRerun, () -> closeAiChangeReview(pane));
            }
        };
    }

    /** The ad-hoc review currently waiting for a decision (tests). */
    SnippetAiDiffPane aiChangeReviewPane() {
        return aiChangeReviewPane;
    }

    /**
     * Opens a result window of this editor without blocking: non-modal, one per kind (a newer result
     * replaces the older window), and closed together with the editor it calls back into.
     */
    private void showChildWindow(Dialog<?> dialog) {
        if (editorClosed) {
            return;
        }
        Dialog<?> previous = childWindows.put(dialog.getClass(), dialog);
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> childWindows.remove(dialog.getClass(), dialog));
        if (previous != null && previous != dialog && previous.isShowing()) {
            previous.close();
        }
        dialog.show();
    }

    /** The open result window of a kind, or {@code null}. */
    private Dialog<?> openChildWindow(Class<?> kind) {
        Dialog<?> dialog = childWindows.get(kind);
        return dialog != null && dialog.isShowing() ? dialog : null;
    }

    private void closeChildWindows() {
        for (Dialog<?> dialog : List.copyOf(childWindows.values())) {
            if (dialog.isShowing()) {
                dialog.close();
            }
        }
        childWindows.clear();
    }

    /** The owner of a result window: this editor's window (the main window while hosted in a tab). */
    private Window childWindowOwner() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    /**
     * Applies an AI result from a non-modal window. The editor stayed editable while it was open, so
     * the result is only inserted when the content is still what it was computed from and no AI
     * change waits for review; otherwise it goes to the clipboard instead of landing at an offset
     * that no longer means anything.
     */
    private boolean applyFromResultWindow(String baseContent, String result, Runnable apply) {
        // Not under an open review either: its Accept would then refuse the content it was made for.
        if (!isAiChangeReviewOpen() && contentUnchangedSince(baseContent)) {
            apply.run();
            return true;
        }
        ClipboardContent clip = new ClipboardContent();
        clip.putString(result != null ? result : "");
        Clipboard.getSystemClipboard().setContent(clip);
        setStatus(I18n.get("snippets.ai.change.contentChangedCopied"));
        return false;
    }

    /** Mirrors a long-running task's message and progress into the hint bar while it is the active action. */
    private void followTaskProgress(Task<?> task) {
        task.messageProperty().addListener((observable, oldMessage, message) -> {
            if (snippetAiActionTask == task && message != null && !message.isBlank()) {
                snippetAiHintLabel.setText(message);
                setStatus(message);
            }
        });
        task.progressProperty().addListener((observable, oldProgress, progress) -> {
            if (snippetAiActionTask == task && progress != null) {
                snippetAiProgressIndicator.setProgress(progress.doubleValue());
            }
        });
    }

    /** What {@link SnippetAnalysisController} sees of this editor. */
    // ---- Save as new: take the analysis along ----

    /** Test seam: answers "take the analysis along?" (gets the number of stored analyses); {@code null} asks. */
    private static java.util.function.IntPredicate analysisCopyPrompter;

    static void setAnalysisCopyPrompterForTesting(java.util.function.IntPredicate prompter) {
        analysisCopyPrompter = prompter;
    }

    /**
     * "Save as new snippet": when the snippet has stored analyses, asks whether the new snippet
     * should get a copy of them ({@link SnippetAnalysisStore#copy}); the original keeps its own.
     * A copy for a snippet that is not saved yet waits in memory until its first save.
     */
    void offerAnalysisCopy(String fromId, Snippet copy) {
        if (fromId == null || fromId.isBlank() || copy == null || copy.getId() == null || fromId.equals(copy.getId())) {
            return;
        }
        SnippetAnalysisStore store = SnippetAnalysisStore.shared();
        de.kortty.core.SnippetAnalysisHistory history = store.cached(fromId);
        if (history == null || history.isEmpty()) {
            return;
        }
        int count = history.records().size();
        boolean take = analysisCopyPrompter != null ? analysisCopyPrompter.test(count) : askAnalysisCopy(count);
        if (!take) {
            return;
        }
        try {
            store.copy(fromId, copy.getId());
            // The copy is saved with the editor's text: an applied result that is exactly that text
            // is saved there (the original keeps its own remembered state).
            String copySha = de.kortty.core.SnippetDiagramSupport.contentHash(
                copy.getContent() != null ? copy.getContent() : "");
            long now = System.currentTimeMillis();
            store.update(copy.getId(), h -> h.withAcceptedRunsSaved(copySha, now));
            setStatus(I18n.get("snippets.saveAsNew.analysisCopied", count));
        } catch (RuntimeException e) {
            logger.warn("Could not copy the stored analyses of {} to {}", fromId, copy.getId(), e);
        }
    }

    private boolean askAnalysisCopy(int count) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("snippets.saveAsNew"));
        alert.setHeaderText(I18n.get("snippets.saveAsNew.analysis.header"));
        alert.setContentText(I18n.get("snippets.saveAsNew.analysis.content", count));
        ButtonType take = new ButtonType(I18n.get("snippets.saveAsNew.analysis.take"), ButtonBar.ButtonData.YES);
        ButtonType leave = new ButtonType(I18n.get("snippets.saveAsNew.analysis.leave"), ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(take, leave);
        Window owner = resolveAlertOwner();
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        return alert.showAndWait().orElse(leave) == take;
    }

    // ---- Draft autosave ----

    /** The draft autosave of this editor, or {@code null} (file editors, admin-managed snippets). */
    SnippetDraftAutosave draftAutosave() {
        return draftAutosave;
    }

    /**
     * Fills the form from a draft left behind by a never-saved snippet (restored by the workspace);
     * the editor then has unsaved changes and keeps its own draft from here on.
     */
    void restoreDraft(de.kortty.core.SnippetDraftStore.SnippetDraft draft) {
        if (draft == null) {
            return;
        }
        new DraftForm().restore(draft);
    }

    private String currentCategoryText() {
        String typed = categoryCombo.getEditor() != null ? categoryCombo.getEditor().getText() : null;
        if (typed != null && !typed.isBlank()) {
            return typed;
        }
        return categoryCombo.getValue();
    }

    /** The editor form seen by {@link SnippetDraftAutosave}. */
    private final class DraftForm implements SnippetDraftAutosave.Form {
        @Override
        public String snippetId() {
            return SnippetEditDialog.this.snippetId();
        }

        @Override
        public boolean isNewSnippet() {
            return persistedSnippet() == null;
        }

        @Override
        public boolean hasUnsavedChanges() {
            return hasUnsavedContentChanges();
        }

        @Override
        public String savedContent() {
            Snippet persisted = persistedSnippet();
            return persisted != null ? persisted.getContent() : null;
        }

        @Override
        public de.kortty.core.SnippetDraftStore.SnippetDraft capture(long now) {
            String saved = savedContent();
            return de.kortty.core.SnippetDraftStore.SnippetDraft.of(
                SnippetEditDialog.this.snippetId(), now, isNewSnippet(),
                nameField.getText(), languageCombo.getValue(), currentCategoryText(), tagsField.getText(),
                descriptionArea.getText(), safeContentText(),
                saved != null ? SnippetDiagramSupport.contentHash(saved) : "");
        }

        @Override
        public void restore(de.kortty.core.SnippetDraftStore.SnippetDraft draft) {
            nameField.setText(draft.name());
            if (!draft.language().isBlank()) {
                if (!languageCombo.getItems().contains(draft.language())) {
                    languageCombo.getItems().add(draft.language());
                }
                languageCombo.setValue(draft.language());
            }
            categoryCombo.setValue(draft.category().isBlank() ? null : draft.category());
            if (categoryCombo.getEditor() != null) {
                categoryCombo.getEditor().setText(draft.category());
            }
            tagsField.setText(draft.tags());
            descriptionArea.setText(draft.description());
            if (!draft.content().equals(safeContentText())) {
                contentArea.replaceText(draft.content());
            }
            applyHighlighting();
            updateSaveButtonState();
            setStatus(I18n.get("snippets.draft.restored"));
        }

        @Override
        public void showBanner(Region banner) {
            VBox.setMargin(banner, new Insets(8, 10, 0, 10));
            editorFormLayout.getChildren().add(0, banner);
        }

        @Override
        public void hideBanner(Region banner) {
            editorFormLayout.getChildren().remove(banner);
            // The analysis panel does not repeat a result the draft banner was offering.
            if (analysisController != null) {
                analysisController.onContentChanged();
            }
        }
    }

    private final class AnalysisHost implements SnippetAnalysisController.Host {
        @Override
        public String snippetId() {
            return SnippetEditDialog.this.snippetId();
        }

        @Override
        public String snippetName() {
            return currentSnippetName();
        }

        @Override
        public String currentContent() {
            return contentArea.getText() != null ? contentArea.getText() : "";
        }

        @Override
        public String snippetLanguage() {
            return languageCombo.getValue();
        }

        @Override
        public String reportLanguageCode() {
            return resolveAnalysisLanguageCode();
        }

        @Override
        public String codeTextFallbackLanguageCode() {
            return resolveAiTextFallbackLanguageCode();
        }

        @Override
        public String additionalInstructions() {
            return SnippetEditDialog.this.additionalInstructions();
        }

        @Override
        public AiAssist aiAssist() {
            return aiAssist;
        }

        @Override
        public boolean hasCodeAnalysisProviders() {
            return SnippetEditDialog.this.hasCodeAnalysisProviders();
        }

        @Override
        public boolean profileSwitchingSupported() {
            return SnippetEditDialog.this.profileSwitchingSupported();
        }

        @Override
        public SnippetAnalysisPanel.SkillContext skillContext() {
            return buildAnalysisSkillContext();
        }

        @Override
        public void autoDetectAiSkills() {
            SnippetEditDialog.this.autoDetectAiSkills();
        }

        @Override
        public boolean ensureDataNoticeAccepted() {
            return ensureSnippetAiDataNoticeAccepted(false);
        }

        @Override
        public boolean isAnyAiTaskRunning() {
            // An ad-hoc change waiting for Accept/Reject blocks the analysis' own actions too.
            return SnippetEditDialog.this.isAnyAiTaskRunning() || aiChangeReviewPane != null;
        }

        @Override
        public void beginAiAction(Task<?> task) {
            beginSnippetAiAction(task, null);
            updateAiActionAvailability();
        }

        @Override
        public void beginAiAction(Task<?> task, Runnable retry) {
            beginSnippetAiAction(task, retry != null ? retryOf(retry) : null);
            updateAiActionAvailability();
        }

        @Override
        public void finishAiAction(Task<?> task) {
            finishSnippetAiAction(task);
        }

        @Override
        public void handleAiActionFailure(Task<?> task, String genericStatus) {
            handleSnippetAiActionFailure(task, genericStatus);
        }

        @Override
        public void showAiHint(String message) {
            showSnippetAiHint(message);
            updateAiActionAvailability();
        }

        @Override
        public void followTaskProgress(Task<?> task) {
            SnippetEditDialog.this.followTaskProgress(task);
        }

        @Override
        public void setStatus(String message) {
            SnippetEditDialog.this.setStatus(message);
        }

        @Override
        public boolean applyCodeTextLanguage(boolean mayAsk) {
            return SnippetEditDialog.this.applyCodeTextLanguage(mayAsk);
        }

        @Override
        public String hardeningInstructions(EnumSet<HardeningOption> options) {
            return withHardeningRules(null, options);
        }

        @Override
        public String inputHardeningInstructions(WorkflowScriptSupport.InputHardeningConfig config) {
            return withInputHardeningRules(null, config);
        }

        @Override
        public String injectSelectedHeader(SnippetAnalysisPanel.ApplySelection selection, String content) {
            return SnippetEditDialog.this.injectSelectedHeader(selection, content);
        }

        @Override
        public boolean contentUnchangedSince(String original) {
            return SnippetEditDialog.this.contentUnchangedSince(original);
        }

        @Override
        public void applyFullReplacement(String original, String replacement, String actionLabel) {
            applyAiContentChange(0, original != null ? original.length() : 0, replacement, actionLabel);
        }

        @Override
        public CompletableFuture<SnippetDiagramView.DiagramSource> generateDiagram(
                String content, String language, String aiProfileId) {
            return generateDiagramMermaid(content, language, resolveAnalysisLanguageCode(), aiProfileId);
        }

        @Override
        public void showSidePanel(Region panel) {
            showAnalysisSidePanel(panel);
        }

        @Override
        public void hideSidePanel() {
            hideAnalysisSidePanel();
        }

        @Override
        public void showInEditorArea(SnippetAiDiffPane pane) {
            SnippetEditDialog.this.showInEditorArea(pane);
        }

        @Override
        public void restoreEditorArea(SnippetAiDiffPane pane) {
            SnippetEditDialog.this.restoreEditorArea(pane);
        }

        @Override
        public EditorSettingsHelper.Settings editorSettings() {
            return editorSettings;
        }

        @Override
        public void panelStateChanged(boolean visible, String badge, String tooltip) {
            analysisToggleButton.setSelected(visible);
            String label = I18n.get("snippets.ai.analysis.panel.toggle");
            analysisToggleButton.setText(badge == null || badge.isBlank() ? label : label + " " + badge);
            analysisToggleButton.getTooltip().setText(tooltip != null ? tooltip : "");
        }

        @Override
        public String offeredDraftContentSha256() {
            return draftAutosave != null ? draftAutosave.offeredContentSha256() : null;
        }

        @Override
        public String savedSnippetContent() {
            Snippet persisted = persistedSnippet();
            return persisted != null ? persisted.getContent() : null;
        }

        @Override
        public boolean isTransientSnippet() {
            return externalFileActionConfig != null
                || existingSnippet != null && existingSnippet.isPolicyManaged();
        }

        @Override
        public void navigateToCode(int startLine, int endLine) {
            navigateToDiagramCodeReference(new SnippetDiagramDialog.CodeNavigationTarget(startLine, endLine));
        }
    }

    /**
     * A standalone editor's entry in {@link SnippetEditorRegistry} (window, or a main-window tab
     * via "tool windows as tabs"). The embedded workspace editors are listed by their tab instead.
     */
    final class StandaloneRegistration implements SnippetEditorRegistry.OpenEditor {

        SnippetEditDialog editor() {
            return SnippetEditDialog.this;
        }

        /** Whether the editor lives in a main-window tab, whose own close guard asks for it. */
        boolean isHostedInMainTab() {
            return isHostedInTab();
        }

        @Override
        public String snippetId() {
            Snippet persisted = persistedSnippet();
            return externalFileActionConfig == null && persisted != null ? persisted.getId() : null;
        }

        @Override
        public void reveal() {
            revealDialogOrHost();
            focusEditor();
        }

        @Override
        public boolean hasUnsavedChanges() {
            return hasUnsavedContentChanges();
        }

        @Override
        public Window ownerStage() {
            if (isHostedInTab()) {
                return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            }
            return getOwner();
        }

        @Override
        public boolean confirmCloseFromHost() {
            return confirmHostedClose();
        }

        @Override
        public void closeWithoutPrompt() {
            SnippetEditDialog.this.closeWithoutPrompt();
        }
    }
}
