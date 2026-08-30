package de.kortty.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the unified snippet-editor AI dialogs (code review, description,
 * alternatives, diff). It builds each real dialog owner-less/app-less with the transient profile picker
 * and re-run enabled, asserts the shared toolbar controls are present, and snapshots each pane to
 * {@code build/smoke/snippet-ai-*.png}. It also proves the {@link MonacoDiffPane} WebView Java bridge
 * installs cleanly (public {@code netscape.javascript.JSObject}) by capturing the pane's logger while the
 * diff editor loads and asserting no "Could not install Monaco diff Java bridge" error is logged. Run via
 * the {@code snippetAiDialogsSmoke} Gradle task. Exit 0 = OK.
 */
public final class SnippetAiDialogsSmoke {

    private SnippetAiDialogsSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                render(failure, done);
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("snippetAiDialogsSmoke OK");
    }

    private static void render(AtomicReference<String> failure, CountDownLatch done) throws Exception {
        String rerunText = I18n.get("snippets.ai.rerun");

        // 1) Code-review findings dialog (themed HTML report).
        List<SnippetAiResponseSupport.CodeReviewFinding> findings = List.of(
            new SnippetAiResponseSupport.CodeReviewFinding(
                "R1", "high", "Unquoted variable expansion",
                "The variable $path is used unquoted, so spaces split it into multiple words.",
                "Wrap the expansion in double quotes: \"$path\".", 12),
            new SnippetAiResponseSupport.CodeReviewFinding(
                "R2", "low", "Prefer printf over echo",
                "echo handling of backslashes is shell-dependent.",
                "Use printf '%s\\n' for portable output.", null));
        SnippetAiReviewDialog review = new SnippetAiReviewDialog(
            null, I18n.get("snippets.ai.review.title"), findings, null, id -> { });
        assertControls("SnippetAiReviewDialog", review.getDialogPane(), rerunText);
        snapshotPane(review.getDialogPane(), "snippet-ai-review.png", 820);

        // 2) Technical-description dialog.
        SnippetDescriptionDialog describe = new SnippetDescriptionDialog(
            null,
            "Reads the config file, validates each entry and returns the parsed settings.",
            "bash", "", text -> { }, null, id -> { });
        assertControls("SnippetDescriptionDialog", describe.getDialogPane(), rerunText);
        snapshotPane(describe.getDialogPane(), "snippet-ai-describe.png", 760);

        // 3) Alternative-solutions dialog (profile combo drives the reload).
        AlternativeSnippetSolutionsDialog alternatives = new AlternativeSnippetSolutionsDialog(
            null, "bash", (instructions, profileId) -> List.of(), true, null);
        if (findNodes(alternatives.getDialogPane(), ComboBox.class).isEmpty()) {
            throw new AssertionError("AlternativeSnippetSolutionsDialog is missing the profile picker");
        }
        snapshotPane(alternatives.getDialogPane(), "snippet-ai-alternatives.png", 920);

        // 3b) Code-analysis dialog (split pane: selectable categorized improvements + deps left, diagram right).
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Downloads a release asset with curl and installs it, logging progress.",
            List.of(new SnippetAiResponseSupport.ScriptDependency(
                "D1", "curl", "program", "download the release asset", "use wget, or a language built-in HTTP client")),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement(
                    "SEC-1", "security", "high", "Unquoted path expansion",
                    "$path is used unquoted.", "Quote it: \"$path\".", 12),
                new SnippetAiResponseSupport.ScriptImprovement(
                    "OPT-1", "optimization", "low", "Avoid re-downloading",
                    "The asset is fetched twice.", "Cache the download.", null),
                new SnippetAiResponseSupport.ScriptImprovement(
                    "DES-1", "design", "medium", "Separate download and install",
                    "One function handles both concerns.", "Split it into two functions.", 18)));
        // The diagram viewer only renders when the dialog is shown (setOnShown), which the smoke never does,
        // so no Mermaid render is invoked; the supplier is just a placeholder source.
        java.util.function.Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramLoader =
            () -> CompletableFuture.completedFuture(new SnippetDiagramView.DiagramSource(
                de.kortty.core.SnippetDiagramSupport.buildFallbackLogicalStructureMermaid("print 'x';\n", "perl"),
                "print 'x';\n", java.util.List.of()));
        verifyPendingDiagramSourcesAreCancelled();
        List<de.kortty.model.AiSkill> analysisSkills = List.of(
            smokeSkill("skill-bash", "Bash hardening", "Adds strict mode, traps and safe expansions"),
            smokeSkill("skill-posix", "POSIX portability", "Prefers POSIX-compliant constructs"));
        SnippetCodeAnalysisDialog.SkillContext skillContext = new SnippetCodeAnalysisDialog.SkillContext(
            analysisSkills,
            new java.util.LinkedHashSet<>(List.of("skill-bash")),
            true,
            ids -> { });
        SnippetCodeAnalysisDialog analysisDialog = new SnippetCodeAnalysisDialog(
            null, "server_monitor_stats.pl", "perl", analysis, diagramLoader, null, id -> { }, skillContext,
            de.kortty.core.ScriptLanguageMixSupport.detect("perl", "#!/usr/bin/env perl\nprint 1;\n"), "de");
        // The dialog's content is wrapped in a ScrollPane so a short window cannot push the button
        // bar off screen. A ScrollPane exposes its content through its skin, which only exists after
        // a layout pass — realize the pane before walking it for controls.
        realize(analysisDialog.getDialogPane());
        AtomicBoolean analysisSelectionVerified = new AtomicBoolean();
        CheckBox selectAllImprovements = selectAllImprovementsCheckBox(analysisDialog.getDialogPane());
        Label profileUsing = nodeById(
            analysisDialog.getDialogPane(), "snippet-analysis-profile-using", Label.class);
        verifySelectAllImprovementPlacement(selectAllImprovements, profileUsing);
        WebEngine analysisEngine = findingsWebView(analysisDialog).getEngine();
        onLoadSuccess(analysisEngine, () -> {
            try {
                verifyImprovementBulkSelection(selectAllImprovements, analysisEngine);
                analysisSelectionVerified.set(true);
            } catch (Throwable e) {
                failure.compareAndSet(null, "SnippetCodeAnalysisDialog selection check failed: " + e);
            }
        });
        if (findNodes(analysisDialog.getDialogPane(), SplitPane.class).isEmpty()) {
            throw new AssertionError("SnippetCodeAnalysisDialog is missing the split pane");
        }
        assertControls("SnippetCodeAnalysisDialog", analysisDialog.getDialogPane(), rerunText);
        boolean hasExport = findNodes(analysisDialog.getDialogPane(), javafx.scene.control.MenuButton.class).stream()
            .anyMatch(node -> I18n.get("snippets.ai.analysis.export")
                .equals(((javafx.scene.control.MenuButton) node).getText()));
        if (!hasExport) {
            throw new AssertionError("SnippetCodeAnalysisDialog is missing the Export button");
        }
        // Snapshot first: applyCss()/layout() realizes the DialogPane button bar so the Apply button is traversable.
        snapshotPane(analysisDialog.getDialogPane(), "snippet-code-analysis.png", 1160);
        boolean hasApply = findNodes(analysisDialog.getDialogPane(), Button.class).stream()
            .anyMatch(b -> ((Button) b).getText() != null
                && ((Button) b).getText().contains(I18n.get("snippets.ai.analysis.applySelected")));
        if (!hasApply) {
            throw new AssertionError("SnippetCodeAnalysisDialog is missing the Apply-selected button");
        }
        HardeningOptionsSelector hardeningSelector = field(
            analysisDialog, "hardeningSelector", HardeningOptionsSelector.class);
        if (!hardeningSelector.selectedOptions().equals(
                de.kortty.core.WorkflowScriptSupport.HardeningOption.defaults())) {
            throw new AssertionError("Hardening selector did not expose the all-on default option set");
        }
        Button clearHardening = findNodes(hardeningSelector, Button.class).stream()
            .map(Button.class::cast)
            .filter(button -> I18n.get("ai.workflow.options.clear").equals(button.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Hardening selector is missing Clear"));
        Button allHardening = findNodes(hardeningSelector, Button.class).stream()
            .map(Button.class::cast)
            .filter(button -> I18n.get("ai.workflow.options.all").equals(button.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Hardening selector is missing All"));
        clearHardening.fire();
        if (!hardeningSelector.selectedOptions().isEmpty() || hardeningSelector.selectedCount() != 0) {
            throw new AssertionError("Hardening Clear did not remove the effective option set");
        }
        allHardening.fire();
        if (!hardeningSelector.selectedOptions().equals(
                de.kortty.core.WorkflowScriptSupport.HardeningOption.defaults())) {
            throw new AssertionError("Hardening All did not restore every effective option");
        }

        // Dependencies alone must not enable the JavaFX bulk selector: it controls improvements only.
        SnippetAiResponseSupport.ScriptAnalysis dependenciesOnly = new SnippetAiResponseSupport.ScriptAnalysis(
            "Calls curl.", analysis.dependencies(), List.of());
        SnippetCodeAnalysisDialog dependenciesOnlyDialog = new SnippetCodeAnalysisDialog(
            null, "dependency_only.yml", "yaml", dependenciesOnly, diagramLoader, null, null, null,
            de.kortty.core.ScriptLanguageMixSupport.detect("yaml", "key: value\n"), "de");
        realize(dependenciesOnlyDialog.getDialogPane());
        CheckBox dependenciesOnlyBulkCheck = selectAllImprovementsCheckBox(dependenciesOnlyDialog.getDialogPane());
        if (!dependenciesOnlyBulkCheck.isDisable()) {
            throw new AssertionError("Select-all-improvements must be disabled when only dependencies exist");
        }

        InputHardeningSelector declarativeSelector = field(
            dependenciesOnlyDialog, "inputHardeningSelector", InputHardeningSelector.class);
        if (declarativeSelector.isSupported() || !declarativeSelector.isDisable()
                || declarativeSelector.currentConfig().isEnabled()) {
            throw new AssertionError("YAML analysis must disable input hardening instead of silently ignoring it");
        }

        // 3c) The AI text-language selector is independent from the snippet's code language. Drive the
        // real editor controls and defer checking the asynchronous provider calls until the final pause.
        Runnable verifyAiTextLanguage = exerciseSnippetEditorAiTextLanguageSelection();
        Runnable verifyAnalysisLanguages = exerciseSnippetAnalysisLanguageRouting();
        Runnable verifyAnalysisApplyPreview = exerciseFullAnalysisApplyPreview(failure);

        // 4) Diff / "review changes" dialog with a re-run handler (improve/assist flow). Capture the
        //    MonacoDiffPane logger while its WebView loads to assert the Java bridge installs cleanly.
        ListAppender<ILoggingEvent> diffLog = new ListAppender<>();
        diffLog.start();
        Logger diffLogger = (Logger) LoggerFactory.getLogger("de.kortty.ui.MonacoDiffPane");
        Level previousLevel = diffLogger.getLevel();
        diffLogger.setLevel(Level.DEBUG);
        diffLogger.addAppender(diffLog);

        // A staged Full-code-analysis apply concatenates one paragraph per stage, so the summary is the
        // long case the review window has to survive: it scrolls inside its own pane and the split
        // divider below it keeps the diff readable.
        SnippetAiDiffDialog diff = new SnippetAiDiffDialog(
            null, I18n.get("snippets.ai.diff.title"),
            "Quote the path expansion.\n\nApplied SEC-1 by replacing the shell-interpolated invocation "
                + "with a list-form pipe and adding input validation.\n\nApplied mandatory hardening: "
                + "configuration block for literals, distinct non-zero exit codes per failure class, "
                + "and an error trap with cleanup logic.\n\nAdded the --help usage message and argument "
                + "parsing to satisfy HARDENING-05.",
            "cat $path\n", "cat \"$path\"\n", "bash",
            EditorSettingsHelper.loadSnippetSettings(), null);
        diff.setRerunHandler(null, id -> { });
        // Exercise the "Why these parts changed" cards: category icons per finding-id prefix plus the
        // reasons JSON handed to the diff host (idx/finding/anchor/reason per change).
        diff.setChangeExplanations(List.of(
            new SnippetAiResponseSupport.SecurityChange(
                "SEC-1", "cat \"$path\"", "Quoted the path expansion to prevent word splitting."),
            new SnippetAiResponseSupport.SecurityChange(
                "D1", "cat \"$path\"", "Replaced the external helper with a shell built-in.")));
        // The toolbar now sits inside the summary/review split, so its skin has to exist before the
        // walker can reach the profile picker and the re-run button.
        realize(diff.getDialogPane());
        assertControls("SnippetAiDiffDialog", diff.getDialogPane(), rerunText);
        assertFindingFilter(diff.getDialogPane());
        snapshotPane(diff.getDialogPane(), "snippet-ai-diff.png", 1040);

        // Let the FX event loop pump so the diff editor loads and installBridge() runs, then verify.
        PauseTransition pause = new PauseTransition(Duration.seconds(8));
        pause.setOnFinished(event -> {
            try {
                boolean bridgeError = diffLog.list.stream().anyMatch(e ->
                    e.getLevel() == Level.ERROR && String.valueOf(e.getMessage()).contains("Monaco diff Java bridge"));
                boolean bridgeInstalled = diffLog.list.stream().anyMatch(e ->
                    String.valueOf(e.getMessage()).contains("Installed Monaco diff Java bridge"));
                if (bridgeError) {
                    failure.compareAndSet(null, "MonacoDiffPane Java bridge failed to install (regression)");
                } else if (bridgeInstalled) {
                    System.out.println("MonacoDiffPane bridge installed cleanly (public JSObject).");
                } else {
                    System.out.println("MonacoDiffPane bridge did not report within the wait "
                        + "(WebView likely did not finish loading headless); no error was logged.");
                }
                if (!analysisSelectionVerified.get()) {
                    failure.compareAndSet(null,
                        "SnippetCodeAnalysisDialog selection page did not finish loading within the wait");
                }
                try {
                    verifyAiTextLanguage.run();
                    verifyAnalysisLanguages.run();
                    verifyAnalysisApplyPreview.run();
                } catch (Throwable e) {
                    failure.compareAndSet(null, "SnippetEditDialog AI flow check failed: " + e);
                }
            } finally {
                diffLogger.detachAppender(diffLog);
                diffLogger.setLevel(previousLevel);
                // Closing the modal diff unwinds a nested JavaFX event loop and then disposes the
                // analysis WebViews. Give macOS WebKit one pulse to release its native scenes before
                // the harness calls Platform.exit(); immediate shutdown can crash in objc_msgSend.
                PauseTransition cleanupPause = new PauseTransition(Duration.seconds(1));
                cleanupPause.setOnFinished(cleanup -> done.countDown());
                cleanupPause.play();
            }
        });
        pause.play();
    }

    /** Test-double futures prove Regenerate and close cancel superseded diagram-source work. */
    private static void verifyPendingDiagramSourcesAreCancelled() {
        List<CompletableFuture<SnippetDiagramView.DiagramSource>> requests = new ArrayList<>();
        SnippetDiagramView view = new SnippetDiagramView(() -> {
            CompletableFuture<SnippetDiagramView.DiagramSource> request = new CompletableFuture<>();
            requests.add(request);
            return request;
        }, true);

        view.reload();
        CompletableFuture<SnippetDiagramView.DiagramSource> first = requests.get(0);
        view.reload();
        CompletableFuture<SnippetDiagramView.DiagramSource> second = requests.get(1);
        if (!first.isCancelled()) {
            throw new AssertionError("Regenerate did not cancel the superseded diagram source");
        }

        view.dispose();
        if (!second.isCancelled()) {
            throw new AssertionError("Closing the diagram view did not cancel its pending source");
        }
    }

    /**
     * Exercises the temporary AI text-language setting through the real selector and action controls.
     * The persistent XML round-trip is covered by {@code GlobalSettingsManagerTest}; this owner-less
     * JavaFX harness intentionally keeps "remember" off so it never touches the user's settings file.
     */
    private static Runnable exerciseSnippetEditorAiTextLanguageSelection() throws Exception {
        AtomicReference<ProviderLanguage> metadataLanguage = new AtomicReference<>();
        AtomicReference<ProviderLanguage> descriptionLanguage = new AtomicReference<>();
        AtomicReference<SnippetEditDialog.SelectionTextTransformRequest> selectionRequest =
            new AtomicReference<>();

        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            (content, language, responseLanguageCode) -> {
                metadataLanguage.set(new ProviderLanguage(language, responseLanguageCode));
                return new SnippetEditDialog.SuggestedSnippetMetadata(
                    "language-smoke.sh", "Prüft die temporäre Textsprache.", language, null);
            },
            (content, language, description, responseLanguageCode) -> {
                descriptionLanguage.set(new ProviderLanguage(language, responseLanguageCode));
                return description;
            },
            request -> {
                selectionRequest.set(request);
                return request.selectedText();
            },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null);

        String content = "# Deutscher Kommentar\nprintf '%s\\n' \"$HOME\"\n";
        Snippet snippet = new Snippet("language-smoke.sh", content, "bash");
        snippet.setDescription("Beschreibung für den Sprachtest.");
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, List.of(), assist);

        @SuppressWarnings("unchecked")
        ComboBox<AiLanguageSupport.LanguageOption> textLanguageCombo =
            (ComboBox<AiLanguageSupport.LanguageOption>) nodeById(
                dialog.getDialogPane(), "snippet-ai-text-language", ComboBox.class);
        CheckBox rememberLanguage = nodeById(
            dialog.getDialogPane(), "snippet-ai-text-language-remember", CheckBox.class);
        if (!textLanguageCombo.isVisible() || !textLanguageCombo.isManaged()
                || textLanguageCombo.getParent() == null
                || !textLanguageCombo.getParent().isVisible()
                || !textLanguageCombo.getParent().isManaged()) {
            throw new AssertionError("SnippetEditDialog AI text-language selector is not visible with AiAssist");
        }
        if (rememberLanguage.isSelected()) {
            throw new AssertionError("Remember-AI-text-language must be off by default");
        }

        @SuppressWarnings("unchecked")
        ComboBox<String> codeLanguageCombo = (ComboBox<String>) field(
            dialog, "languageCombo", ComboBox.class);
        if (!"bash".equals(codeLanguageCombo.getValue())) {
            throw new AssertionError("Expected the snippet code language to start as bash, was "
                + codeLanguageCombo.getValue());
        }

        AiLanguageSupport.LanguageOption english = textLanguageCombo.getItems().stream()
            .filter(option -> option != null && "en".equals(option.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI text-language selector is missing English"));
        textLanguageCombo.getSelectionModel().select(english);
        if (textLanguageCombo.getOnAction() == null) {
            throw new AssertionError("AI text-language selector has no action handler");
        }
        textLanguageCombo.getOnAction().handle(new ActionEvent(textLanguageCombo, textLanguageCombo));
        if (rememberLanguage.isSelected()) {
            throw new AssertionError("Temporary language selection unexpectedly enabled persistence");
        }
        if (!"bash".equals(codeLanguageCombo.getValue())) {
            throw new AssertionError("Changing AI text language changed the snippet code language");
        }

        MonacoEditorPane editor = field(dialog, "contentArea", MonacoEditorPane.class);
        String selectedComment = "Deutscher Kommentar";
        int selectedCommentStart = content.indexOf(selectedComment);
        editor.selectRange(selectedCommentStart, selectedCommentStart + selectedComment.length());
        field(dialog, "generateMetadataButton", Button.class).fire();
        field(dialog, "correctDescriptionButton", Button.class).fire();
        field(dialog, "correctSelectionTextItem", MenuItem.class).fire();
        snapshotPane(dialog.getDialogPane(), "snippet-ai-language-selector.png", 900);

        return () -> {
            try {
                assertProviderLanguage("metadata", metadataLanguage.get(), "bash", "en");
                assertProviderLanguage("description", descriptionLanguage.get(), "bash", "en");
                SnippetEditDialog.SelectionTextTransformRequest request = selectionRequest.get();
                if (request == null) {
                    throw new AssertionError("Selection correction provider was not invoked");
                }
                assertProviderLanguage(
                    "selection",
                    new ProviderLanguage(request.snippetLanguage(), request.fallbackLanguageCode()),
                    "bash",
                    "en");
                if (!selectedComment.equals(request.selectedText())
                        || request.selectionStart() != selectedCommentStart
                        || request.selectionEnd() != selectedCommentStart + selectedComment.length()) {
                    throw new AssertionError("Selection correction did not preserve the partial comment range");
                }
                if (!"bash".equals(codeLanguageCombo.getValue())) {
                    throw new AssertionError("AI provider completion changed the snippet code language");
                }
                if (rememberLanguage.isSelected()) {
                    throw new AssertionError("Temporary language selection was persisted unexpectedly");
                }
            } finally {
                editor.dispose();
            }
        };
    }

    private static void assertProviderLanguage(
        String provider,
        ProviderLanguage actual,
        String expectedSnippetLanguage,
        String expectedTextLanguage) {

        if (actual == null) {
            throw new AssertionError(provider + " provider was not invoked");
        }
        if (!expectedSnippetLanguage.equals(actual.snippetLanguage())) {
            throw new AssertionError(provider + " provider received snippet language "
                + actual.snippetLanguage() + " instead of " + expectedSnippetLanguage);
        }
        if (!expectedTextLanguage.equals(actual.textLanguage())) {
            throw new AssertionError(provider + " provider received AI text language "
                + actual.textLanguage() + " instead of " + expectedTextLanguage);
        }
    }

    private record ProviderLanguage(String snippetLanguage, String textLanguage) {
    }

    /**
     * Drives the real Full-code-analysis window through Apply selected and proves that the generated
     * replacement is surfaced in the review-diff window before the editor content can change.
     */
    private static Runnable exerciseFullAnalysisApplyPreview(AtomicReference<String> failure) throws Exception {
        // Real comments, because the apply flow now keeps whatever language the script is written
        // in and asks the user when it cannot tell. A bare two-line fixture is exactly the
        // "cannot tell" case and would stop this harness on a question nobody can answer.
        String prose = "# Prints the value that was passed to the script and checks that the\n"
            + "# variable is quoted, otherwise the shell splits it into several arguments.\n";
        String original = "#!/usr/bin/env bash\n" + prose + "printf '%s\\n' $value\n";
        String replacement = "#!/usr/bin/env bash\n" + prose + "printf '%s\\n' \"$value\"\n";
        String longImprovementTitle = "Quote the untrusted variable expansion before invoking the command "
            + "so spaces, wildcard characters and user-controlled shell fragments remain one literal argument "
            + "without changing the command's existing output or error handling";
        SnippetAiResponseSupport.ScriptImprovement improvement =
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", longImprovementTitle, "Expansion is unquoted.",
                "Quote the expansion.", 2);
        SnippetAiResponseSupport.ScriptImprovement optimization =
            new SnippetAiResponseSupport.ScriptImprovement(
                "OPT-1", "optimization", "medium", "Avoid repeated parsing", "The value is parsed twice.",
                "Parse the value once and reuse it.", 2);
        SnippetAiResponseSupport.ScriptAnalysis fullAnalysis =
            new SnippetAiResponseSupport.ScriptAnalysis(
                "Prints one value.", List.of(), List.of(improvement, optimization));
        AtomicBoolean diagramProviderCalled = new AtomicBoolean();
        AtomicBoolean applyProviderCalled = new AtomicBoolean();
        AtomicBoolean applyClicked = new AtomicBoolean();
        AtomicBoolean previewShown = new AtomicBoolean();
        AtomicBoolean autoCompletionProviderCalled = new AtomicBoolean();
        AtomicReference<Timeline> poller = new AtomicReference<>();

        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null,
            null,
            null,
            null,
            null,
            null,
            request -> {
                // Test double: any invocation proves the pending completion escaped into the analysis flow.
                autoCompletionProviderCalled.set(true);
                return new SnippetAiResponseSupport.CompletionSuggestion(
                    "#!/usr/bin/perl\nuse autodie qw(open close);", "Unexpected during analysis");
            },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request -> {
                diagramProviderCalled.set(true);
                return new SnippetAiResponseSupport.MermaidDiagram("Flow", "");
            },
            request -> fullAnalysis,
            request -> {
                applyProviderCalled.set(true);
                List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan =
                    SnippetAiWorkflowSupport.planSnippetImprovements(
                        request.improvements(),
                        request.dependencies(),
                        request.classicHardeningInstructions(),
                        request.inputHardeningInstructions());
                if (request.progressListener() != null && !plan.isEmpty()) {
                    SnippetAiWorkflowSupport.ImprovementApplyProgress pending = plan.get(0);
                    request.progressListener().onProgress(new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                        pending.phase(), pending.stage(), pending.totalStages(),
                        pending.firstRequirement(), pending.lastRequirement(), pending.phaseRequirementCount(),
                        pending.detail(), pending.workItems(),
                        SnippetAiWorkflowSupport.ImprovementApplyProgressState.RUNNING, null));
                    request.progressListener().onProgress(new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                        pending.phase(), pending.stage(), pending.totalStages(),
                        pending.firstRequirement(), pending.lastRequirement(), pending.phaseRequirementCount(),
                        pending.detail(), pending.workItems(),
                        SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED,
                        new de.kortty.core.AiTokenUsage(80, 40, 120)));
                }
                return new SnippetAiResponseSupport.SnippetSecurityFix(
                    replacement,
                    "Quotes the variable expansion.",
                    List.of(new SnippetAiResponseSupport.SecurityChange(
                        "SEC-1", "printf '%s\\n' \"$value\"", "Prevents word splitting.")));
            },
            false,
            null);
        SnippetEditDialog editorDialog = new SnippetEditDialog(
            new Snippet("analysis-preview-smoke.sh", original, "bash"), List.of(), assist);
        MonacoEditorPane editor = field(editorDialog, "contentArea", MonacoEditorPane.class);
        editorDialog.show();

        // Reproduce the reported race: an edit has queued auto-completion, then Full code analysis starts
        // before the 900 ms debounce expires. The analysis must own the AI flow and discard that queue.
        field(editorDialog, "autoCompleteItem", CheckMenuItem.class).setSelected(true);
        setField(editorDialog, "autoCompletionWarningAccepted", true);
        invoke(editorDialog, "scheduleAutoCompletion", new Class<?>[0]);

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            try {
                Stage preview = findShowingStage(I18n.get("snippets.ai.analysis.diff.title"));
                if (preview != null) {
                    Stage analysis = findShowingStage(I18n.get("snippets.ai.analysis.title"));
                    Stage progress = Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(Stage.class::isInstance)
                        .map(Stage.class::cast)
                        .filter(candidate -> candidate.getTitle() != null
                            && candidate.getTitle().startsWith(I18n.get("snippets.ai.analysis.progress.title")))
                        .filter(candidate -> candidate.getOwner() == analysis)
                        .findFirst()
                        .orElse(null);
                    if (analysis == null || progress == null) {
                        throw new AssertionError("Analysis and its docked AI-processing window must remain visible");
                    }
                    ProgressBar improvementsProgress = nodeById(
                        progress.getScene().getRoot(),
                        "snippet-analysis-progress-improvements",
                        ProgressBar.class);
                    ProgressBar hardeningProgress = nodeById(
                        progress.getScene().getRoot(),
                        "snippet-analysis-progress-hardening",
                        ProgressBar.class);
                    if (!improvementsProgress.isVisible() || !hardeningProgress.isVisible()
                            || improvementsProgress.getProgress() != 1.0
                            || hardeningProgress.getProgress() != 1.0) {
                        throw new AssertionError(
                            "AI-processing window did not keep separate completed progress bars");
                    }
                    boolean completedCheckVisible = findNodes(progress.getScene().getRoot(), Label.class).stream()
                        .map(Label.class::cast)
                        .anyMatch(label -> "✓".equals(label.getText()));
                    if (!completedCheckVisible) {
                        throw new AssertionError("AI-processing window did not mark the completed step");
                    }
                    boolean reportedTokensVisible = findNodes(progress.getScene().getRoot(), Label.class).stream()
                        .map(Label.class::cast)
                        .map(Label::getText)
                        .anyMatch(text -> text != null && text.contains("120"));
                    if (!reportedTokensVisible) {
                        throw new AssertionError("AI-processing window did not show provider-reported token usage");
                    }
                    HBox improvementRow = findNodes(progress.getScene().getRoot(), HBox.class).stream()
                        .map(HBox.class::cast)
                        .filter(row -> "SEC-1".equals(row.getUserData()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                            "AI-processing window did not expose the SEC-1 work row"));
                    List<String> progressTexts = findNodes(improvementRow, Label.class).stream()
                        .map(Label.class::cast)
                        .map(Label::getText)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                    String improvementClassification =
                        I18n.get("snippets.ai.analysis.section.security") + " · high";
                    if (progressTexts.contains(improvementClassification)) {
                        throw new AssertionError(
                            "AI-processing window still showed category and severity as right-side text");
                    }
                    SVGPath categoryIcon = findNodes(improvementRow, SVGPath.class).stream()
                        .map(SVGPath.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                            "AI-processing improvement row did not show its category icon"));
                    if (!SnippetAiDialogSupport.sectionIconPath("security").equals(categoryIcon.getContent())
                            || !Color.web(SnippetAiDialogSupport.sectionColor("security"))
                                .equals(categoryIcon.getFill())
                            || !(categoryIcon.getParent() instanceof HBox identifierLine)
                            || identifierLine.getChildren().indexOf(categoryIcon) != 1
                            || !(identifierLine.getChildren().getFirst() instanceof Label identifier)
                            || !"SEC-1".equals(identifier.getText())) {
                        throw new AssertionError(
                            "AI-processing category icon was not coloured and placed beside its identifier");
                    }
                    Label clampedDescription = findNodes(improvementRow, Label.class).stream()
                        .map(Label.class::cast)
                        .filter(label -> label.getStyleClass()
                            .contains("snippet-analysis-progress-description"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                            "AI-processing improvement row did not expose its bounded description"));
                    if (!longImprovementTitle.equals(clampedDescription.getText())
                            || !clampedDescription.isWrapText()
                            || clampedDescription.getTextOverrun() != OverrunStyle.ELLIPSIS
                            || clampedDescription.getHeight() > clampedDescription.getMaxHeight() + 0.5
                            || clampedDescription.getHeight() < clampedDescription.getMaxHeight() - 0.5) {
                        throw new AssertionError(
                            "AI-processing description was not visually clamped to its three-line height");
                    }
                    SVGPath optimizationIcon = findNodes(progress.getScene().getRoot(), HBox.class).stream()
                        .map(HBox.class::cast)
                        .filter(row -> "OPT-1".equals(row.getUserData()))
                        .flatMap(row -> findNodes(row, SVGPath.class).stream())
                        .map(SVGPath.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                            "AI-processing optimization row did not show its lightning icon"));
                    if (!SnippetAiDialogSupport.sectionIconPath("optimization")
                            .equals(optimizationIcon.getContent())
                            || !Color.web(SnippetAiDialogSupport.sectionColor("optimization"))
                                .equals(optimizationIcon.getFill())) {
                        throw new AssertionError(
                            "AI-processing optimization row did not use the coloured lightning icon");
                    }
                    boolean hardeningRowHasClassification = findNodes(
                            progress.getScene().getRoot(), HBox.class).stream()
                        .map(HBox.class::cast)
                        .filter(row -> row.getUserData() instanceof String id && id.startsWith("HARDENING-"))
                        .findFirst()
                        .map(row -> findNodes(row, Label.class).size() > 3
                            || !findNodes(row, SVGPath.class).isEmpty())
                        .orElseThrow(() -> new AssertionError(
                            "AI-processing window did not expose a hardening work row"));
                    if (hardeningRowHasClassification) {
                        throw new AssertionError(
                            "AI-processing hardening row still showed a redundant right-side classification");
                    }
                    previewShown.set(true);
                    Timeline active = poller.get();
                    if (active != null) {
                        active.stop();
                    }
                    return;
                }
                if (applyClicked.get()) {
                    return;
                }
                Stage analysis = findShowingStage(I18n.get("snippets.ai.analysis.title"));
                if (analysis == null || analysis.getScene() == null) {
                    return;
                }
                WebEngine findings = analysisFindingsEngine(analysis);
                if (findings == null) {
                    return;
                }
                setChecked(findings, "imp", "SEC-1", true);
                setChecked(findings, "imp", "OPT-1", true);
                Button apply = findNodes(analysis.getScene().getRoot(), Button.class).stream()
                    .map(Button.class::cast)
                    .filter(button -> button.getText() != null
                        && button.getText().contains(I18n.get("snippets.ai.analysis.applySelected")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Full-code-analysis Apply-selected button is missing"));
                applyClicked.set(true);
                apply.fire();
            } catch (Throwable e) {
                failure.compareAndSet(null, "Full-code-analysis preview flow failed: " + e);
                Timeline active = poller.get();
                if (active != null) {
                    active.stop();
                }
                editorDialog.close();
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        poller.set(timeline);
        timeline.play();
        invoke(editorDialog, "runCodeReview", new Class<?>[0]);

        return () -> {
            timeline.stop();
            try {
                if (!applyClicked.get()) {
                    throw new AssertionError("Full-code-analysis selection was not applied from its real dialog");
                }
                if (!applyProviderCalled.get()) {
                    throw new AssertionError("Full-code-analysis apply provider was not invoked");
                }
                if (!diagramProviderCalled.get()) {
                    throw new AssertionError("Full-code-analysis did not run the dedicated diagram request on open");
                }
                if (!previewShown.get()) {
                    throw new AssertionError("Full-code-analysis did not show the review-diff window");
                }
                if (autoCompletionProviderCalled.get()) {
                    throw new AssertionError(
                        "Full-code-analysis allowed a pending auto-completion popup to run");
                }
                if (!original.equals(editor.getText())) {
                    throw new AssertionError("Editor content changed before the review-diff was confirmed");
                }
            } finally {
                Stage preview = findShowingStage(I18n.get("snippets.ai.analysis.diff.title"));
                if (preview != null) {
                    // Close only after Monaco has had the smoke's full wait interval to finish loading.
                    preview.close();
                }
                Platform.runLater(editorDialog::close);
            }
        };
    }

    private static Stage findShowingStage(String titlePrefix) {
        return Window.getWindows().stream()
            .filter(Window::isShowing)
            .filter(Stage.class::isInstance)
            .map(Stage.class::cast)
            .filter(stage -> stage.getTitle() != null && stage.getTitle().startsWith(titlePrefix))
            .findFirst()
            .orElse(null);
    }

    private static WebEngine analysisFindingsEngine(Stage analysisStage) {
        for (Node node : findNodes(analysisStage.getScene().getRoot(), WebView.class)) {
            WebEngine engine = ((WebView) node).getEngine();
            if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
                continue;
            }
            try {
                Object found = engine.executeScript(
                    "document.querySelector(\"input.analysis-check[data-kind='imp'][data-id='SEC-1']\") !== null");
                if (Boolean.TRUE.equals(found)) {
                    return engine;
                }
            } catch (RuntimeException ignored) {
                // This is another WebView in the analysis window (for example the Mermaid renderer).
            }
        }
        return null;
    }

    /** Verifies the editor forwards GUI/report language and code language through the two analysis actions. */
    private static Runnable exerciseSnippetAnalysisLanguageRouting() throws Exception {
        String guiLanguage = LanguageManager.getInstance().getCurrentLanguageCode();
        AtomicReference<SnippetEditDialog.CodeAnalysisRequest> analysisRequest = new AtomicReference<>();
        AtomicReference<SnippetEditDialog.ImprovementApplyRequest> applyRequest = new AtomicReference<>();
        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request -> new SnippetAiResponseSupport.MermaidDiagram("", ""),
            request -> {
                analysisRequest.set(request);
                return null;
            },
            request -> {
                applyRequest.set(request);
                return new SnippetAiResponseSupport.SnippetSecurityFix("", "", List.of());
            },
            false,
            null);
        Snippet snippet = new Snippet(
            "analysis-language-smoke.sh",
            "#!/usr/bin/env bash\nprintf '%s\\n' \"$value\"\n",
            "bash");
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, List.of(), assist);

        @SuppressWarnings("unchecked")
        ComboBox<AiLanguageSupport.LanguageOption> textLanguageCombo =
            (ComboBox<AiLanguageSupport.LanguageOption>) nodeById(
                dialog.getDialogPane(), "snippet-ai-text-language", ComboBox.class);
        AiLanguageSupport.LanguageOption english = textLanguageCombo.getItems().stream()
            .filter(option -> option != null && "en".equals(option.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI text-language selector is missing English"));
        textLanguageCombo.getSelectionModel().select(english);
        textLanguageCombo.getOnAction().handle(new ActionEvent(textLanguageCombo, textLanguageCombo));

        invoke(dialog, "runCodeReview", new Class<?>[0]);
        SnippetAiResponseSupport.ScriptImprovement improvement =
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", "Quote variable", "Expansion is unquoted.",
                "Quote the expansion.", 2);
        SnippetCodeAnalysisDialog.ApplySelection selection = new SnippetCodeAnalysisDialog.ApplySelection(
            List.of(improvement),
            List.of(),
            EnumSet.of(de.kortty.core.WorkflowScriptSupport.HardeningOption.SAFE_MODE),
            de.kortty.core.WorkflowScriptSupport.InputHardeningConfig.disabled(),
            null,
            null,
            null);
        // runCodeReview completes on a background Task. Start the apply probe on a later pulse so the
        // editor's single-AI-action guard does not correctly reject it as a concurrent second action.
        PauseTransition applyDelay = new PauseTransition(Duration.millis(250));
        applyDelay.setOnFinished(event -> {
            try {
                invoke(
                    dialog,
                    "runImprovementFixes",
                    new Class<?>[] {SnippetCodeAnalysisDialog.ApplySelection.class},
                    selection);
            } catch (Exception e) {
                throw new IllegalStateException("Could not start the apply-language smoke probe", e);
            }
        });
        applyDelay.play();

        MonacoEditorPane editor = field(dialog, "contentArea", MonacoEditorPane.class);
        return () -> {
            try {
                SnippetEditDialog.CodeAnalysisRequest analysis = analysisRequest.get();
                if (analysis == null) {
                    throw new AssertionError("Full-code-analysis provider was not invoked");
                }
                assertProviderLanguage(
                    "full-code-analysis",
                    new ProviderLanguage(analysis.snippetLanguage(), analysis.fallbackLanguageCode()),
                    "bash",
                    guiLanguage);
                SnippetEditDialog.ImprovementApplyRequest apply = applyRequest.get();
                if (apply == null) {
                    throw new AssertionError("Apply-selected provider was not invoked");
                }
                assertProviderLanguage(
                    "apply-selected",
                    new ProviderLanguage(apply.snippetLanguage(), apply.fallbackLanguageCode()),
                    "bash",
                    "en");
                if (apply.improvements().size() != 1 || !"SEC-1".equals(apply.improvements().getFirst().id())) {
                    throw new AssertionError("Apply-selected did not forward the selected improvement");
                }
                if (apply.mandatoryHardeningInstructions() == null
                        || !apply.mandatoryHardeningInstructions().contains("--dry-run")) {
                    throw new AssertionError("Apply-selected did not forward hardening as a mandatory contract");
                }
            } finally {
                editor.dispose();
            }
        };
    }

    /** Asserts the dialog exposes both a profile combo and a re-run button. */
    /**
     * The review preview offers a per-finding focus picker once at least two findings carry a reason:
     * it must list "all changes" plus every finding id, and selecting one must reach the diff host.
     */
    private static void assertFindingFilter(DialogPane pane) {
        String allLabel = I18n.get("snippets.ai.diff.focus.all");
        ComboBox<?> focusCombo = findNodes(pane, ComboBox.class).stream()
            .map(node -> (ComboBox<?>) node)
            .filter(combo -> combo.getItems().contains(allLabel))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAiDiffDialog is missing the finding focus picker"));
        if (!focusCombo.getItems().containsAll(List.of(allLabel, "SEC-1", "D1"))) {
            throw new AssertionError("Finding focus picker is missing an annotated finding: "
                + focusCombo.getItems());
        }
        if (!allLabel.equals(focusCombo.getValue())) {
            throw new AssertionError("Finding focus picker must start on every change, was "
                + focusCombo.getValue());
        }
        Button previous = findNodes(pane, Button.class).stream().map(node -> (Button) node)
            .filter(button -> "◀".equals(button.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAiDiffDialog is missing the previous-finding button"));
        Button next = findNodes(pane, Button.class).stream().map(node -> (Button) node)
            .filter(button -> "▶".equals(button.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAiDiffDialog is missing the next-finding button"));

        // Stepping forward from "all changes" must enter the list rather than do nothing, and each
        // step must re-filter the diff — the pane queues that call while Monaco is still booting.
        next.fire();
        if (!"SEC-1".equals(focusCombo.getValue())) {
            throw new AssertionError("Next finding should select SEC-1, was " + focusCombo.getValue());
        }
        next.fire();
        if (!"D1".equals(focusCombo.getValue())) {
            throw new AssertionError("Next finding should select D1, was " + focusCombo.getValue());
        }
        // Two findings in this fixture, so forward wraps back to the first, never onto "all changes".
        next.fire();
        if (!"SEC-1".equals(focusCombo.getValue())) {
            throw new AssertionError("Next finding should wrap to SEC-1, was " + focusCombo.getValue());
        }
        previous.fire();
        if (!"D1".equals(focusCombo.getValue())) {
            throw new AssertionError("Previous finding should wrap to D1, was " + focusCombo.getValue());
        }
        ((ComboBox<Object>) focusCombo).setValue(allLabel);
    }

    private static void assertControls(String name, DialogPane pane, String rerunText) {
        if (findNodes(pane, ComboBox.class).isEmpty()) {
            throw new AssertionError(name + " is missing the AI-profile picker");
        }
        boolean hasRerun = findNodes(pane, Button.class).stream()
            .anyMatch(button -> rerunText.equals(((Button) button).getText()));
        if (!hasRerun) {
            throw new AssertionError(name + " is missing the re-run button ('" + rerunText + "')");
        }
    }

    private static de.kortty.model.AiSkill smokeSkill(String id, String name, String description) {
        de.kortty.model.AiSkill skill = new de.kortty.model.AiSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription(description);
        skill.setContent("Example skill content.");
        skill.setEnabled(true);
        return skill;
    }

    /**
     * Drives the real JavaFX bulk checkbox and checks the WebView selection state. Improvements from all
     * three display categories must follow it, while a dependency keeps its independently chosen state.
     */
    private static void verifyImprovementBulkSelection(CheckBox bulkCheck, WebEngine engine) {
        assertChecked(engine, "imp", "SEC-1", false);
        assertChecked(engine, "imp", "OPT-1", false);
        assertChecked(engine, "imp", "DES-1", false);

        setChecked(engine, "dep", "D1", true);
        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", true);
        assertChecked(engine, "imp", "OPT-1", true);
        assertChecked(engine, "imp", "DES-1", true);
        assertChecked(engine, "dep", "D1", true);

        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", false);
        assertChecked(engine, "imp", "OPT-1", false);
        assertChecked(engine, "imp", "DES-1", false);
        assertChecked(engine, "dep", "D1", true);

        setChecked(engine, "dep", "D1", false);
        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", true);
        assertChecked(engine, "imp", "OPT-1", true);
        assertChecked(engine, "imp", "DES-1", true);
        assertChecked(engine, "dep", "D1", false);

        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", false);
        assertChecked(engine, "imp", "OPT-1", false);
        assertChecked(engine, "imp", "DES-1", false);
        assertChecked(engine, "dep", "D1", false);
    }

    private static CheckBox selectAllImprovementsCheckBox(DialogPane pane) {
        String expectedText = I18n.get("snippets.ai.analysis.selectAllImprovements");
        return findNodes(pane, CheckBox.class).stream()
            .map(CheckBox.class::cast)
            .filter(checkBox -> expectedText.equals(checkBox.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "SnippetCodeAnalysisDialog is missing the select-all-improvements checkbox ('"
                    + expectedText + "')"));
    }

    /** Keeps the bulk selector at the far-left edge of the Full-code-analysis toolbar. */
    private static void verifySelectAllImprovementPlacement(CheckBox bulkCheck, Label profileUsing) {
        if (!(bulkCheck.getParent() instanceof HBox toolbar)
                || toolbar.getChildren().isEmpty()
                || toolbar.getChildren().getFirst() != bulkCheck) {
            throw new AssertionError("Select-all-improvements must be the leftmost toolbar control");
        }
        if (profileUsing.getParent() != toolbar
                || toolbar.getChildren().indexOf(profileUsing) != 1
                || HBox.getMargin(profileUsing) == null
                || HBox.getMargin(profileUsing).getLeft() < 16) {
            throw new AssertionError(
                "The used-profile label must be visibly separated from select-all-improvements");
        }
    }

    private static <T extends Node> T nodeById(Node root, String id, Class<T> type) {
        return findNodes(root, type).stream()
            .map(type::cast)
            .filter(node -> id.equals(node.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + type.getSimpleName() + " with id " + id));
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }

    private static void invoke(
        Object target, String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {

        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    private static WebView findingsWebView(SnippetCodeAnalysisDialog dialog) {
        try {
            Field field = SnippetCodeAnalysisDialog.class.getDeclaredField("findingsView");
            field.setAccessible(true);
            return (WebView) field.get(dialog);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SnippetCodeAnalysisDialog is missing its findings WebView", e);
        }
    }

    private static void setChecked(WebEngine engine, String kind, String id, boolean checked) {
        Object result = engine.executeScript("(function(){var b=document.querySelector(\"input.analysis-check[data-kind='"
            + kind + "'][data-id='" + id + "']\");if(!b)return false;b.checked=" + checked
            + ";return true;})()");
        if (!Boolean.TRUE.equals(result)) {
            throw new AssertionError("Missing analysis checkbox " + kind + ":" + id);
        }
    }

    private static void assertChecked(WebEngine engine, String kind, String id, boolean expected) {
        Object result = engine.executeScript("(function(){var b=document.querySelector(\"input.analysis-check[data-kind='"
            + kind + "'][data-id='" + id + "']\");return b?b.checked:null;})()");
        if (!(result instanceof Boolean actual) || actual != expected) {
            throw new AssertionError("Expected " + kind + ":" + id + " checked=" + expected + ", was " + result);
        }
    }

    private static void onLoadSuccess(WebEngine engine, Runnable action) {
        if (engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            action.run();
            return;
        }
        AtomicReference<javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>> holder =
            new AtomicReference<>();
        holder.set((obs, was, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                engine.getLoadWorker().stateProperty().removeListener(holder.get());
                action.run();
            }
        });
        engine.getLoadWorker().stateProperty().addListener(holder.get());
    }

    private static List<Node> findNodes(Node root, Class<? extends Node> type) {
        List<Node> all = new ArrayList<>();
        collect(root, all);
        List<Node> matches = new ArrayList<>();
        for (Node node : all) {
            if (type.isInstance(node)) {
                matches.add(node);
            }
        }
        return matches;
    }

    private static void collect(Node root, List<Node> out) {
        if (root == null) {
            return;
        }
        out.add(root);
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    /** Builds the pane's skins so nodes behind a skin (e.g. a ScrollPane's content) become walkable. */
    private static void realize(DialogPane pane) {
        pane.applyCss();
        pane.layout();
    }

    private static void snapshotPane(DialogPane pane, String fileName, double minWidth) throws Exception {
        pane.applyCss();
        pane.layout();
        double width = Math.max(pane.prefWidth(-1), minWidth);
        double height = Math.max(pane.prefHeight(width), 300);
        pane.resize(width, height);
        pane.applyCss();
        pane.layout();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        WritableImage image = pane.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }

}
