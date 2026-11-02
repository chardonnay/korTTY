package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiSentContext;
import de.kortty.core.AiSkillRepository;
import de.kortty.core.AiSkillShortener;
import de.kortty.core.AiSkillShortener.Proposal;
import de.kortty.core.AiSkillShortener.ShortenException;
import de.kortty.model.AiProfile;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiTokenizerType;
import de.kortty.policy.PolicyManager;
import de.kortty.stats.SkillSize;
import de.kortty.ui.EditorSettingsHelper.Settings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Shortens one AI skill. The AI call and its checks run off the FX thread, see {@link AiSkillShortener}; the proposal is
 * shown as a diff with the token sizes before and after. Only Apply saves it, as the user's own change: the save goes through
 * {@link AiSkillRepository#commit}, which runs the secret guard, and a proposal whose skill changed since it was made is not
 * saved. Cancel and closing change nothing.
 */
final class AiSkillShortenDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(AiSkillShortenDialog.class);
    private static final String ERROR_STYLE = "-fx-text-fill: #d9534f;";

    /** A proposal with the token sizes of both versions, counted off the FX thread. */
    private record Sized(Proposal proposal, int beforeTokens, int afterTokens) {
    }

    private final KorTTYApplication app;
    private final AiMemoryAssistant assistant;
    private final AiSkillRepository repository;
    private final Consumer<String> editorOpener;
    private final Runnable onSaved;
    /** The skill as it was stored at the last look; only its id is kept for the later lookups. */
    private AiSkill current;
    private Sized sized;
    private boolean busy;
    private boolean disposed;
    private MonacoDiffPane diffPane;

    private final Label sizeLabel = new Label();
    private final Label nothingLabel = hint(I18n.get("settings.aiSkills.shorten.nothing"));
    private final Label statusLabel = new Label();
    private final Label sizesLabel = new Label();
    private final VBox diffBox = new VBox(6);
    private final Button proposeButton = new Button(I18n.get("settings.aiSkills.shorten.propose"));
    private final Button applyButton = new Button(I18n.get("settings.aiSkills.shorten.apply"));
    private final Button editorButton = new Button(I18n.get("settings.aiSkills.shorten.editor"));

    /**
     * @param skill        the skill to shorten, as the statistics show it
     * @param editorOpener selects the skill in the AI-skills editor by id; may be null
     * @param onSaved      runs on the FX thread after a shortening was saved
     */
    AiSkillShortenDialog(Window owner, KorTTYApplication app, AiSkill skill,
                         Consumer<String> editorOpener, Runnable onSaved) {
        this.app = app;
        this.assistant = new AiMemoryAssistant(app);
        this.repository = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().aiSkillRepository()
            : null;
        this.editorOpener = editorOpener;
        this.onSaved = onSaved != null ? onSaved : () -> {
        };
        this.current = new AiSkill(skill);
        setTitle(I18n.get("settings.aiSkills.shorten.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        getDialogPane().setPrefSize(900, 640);
        getDialogPane().getButtonTypes().add(
            new ButtonType(I18n.get("settings.aiSkills.shorten.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        setResultConverter(button -> null);
        setOnHidden(event -> release());

        Label name = new Label(nameOf(current));
        name.setWrapText(true);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 1.1em;");
        Label scope = hint(I18n.get(AiSkillShortener.shortensText(current)
            ? "settings.aiSkills.shorten.scope.text"
            : "settings.aiSkills.shorten.scope.rulesOnly"));
        sizeLabel.setWrapText(true);
        statusLabel.setWrapText(true);
        statusLabel.setStyle(MutedTextStyle.HINT);
        sizesLabel.setWrapText(true);
        sizesLabel.setStyle("-fx-font-weight: bold;");
        diffBox.getChildren().setAll(sizesLabel, hint(I18n.get("settings.aiSkills.shorten.diffHeader")));
        diffBox.setVisible(false);
        diffBox.setManaged(false);
        VBox.setVgrow(diffBox, Priority.ALWAYS);

        proposeButton.setOnAction(event -> propose());
        applyButton.setOnAction(event -> apply());
        editorButton.setOnAction(event -> openInEditor());
        HBox actions = new HBox(8, proposeButton, applyButton, editorButton);

        boolean hasSomething = AiSkillShortener.hasSomethingToShorten(current);
        nothingLabel.setVisible(!hasSomething);
        nothingLabel.setManaged(!hasSomething);

        VBox root = new VBox(10, name, scope, sizeLabel, nothingLabel, actions, statusLabel, diffBox);
        root.setPadding(new Insets(14));
        getDialogPane().setContent(root);

        refreshSize();
        updateButtons();
        if (hasSomething && !assistant.available()) {
            showStatus(unavailableMessage(), true);
        }
    }

    /** The token count of the skill as it is stored now, counted off the FX thread. */
    private void refreshSize() {
        AiSkill snapshot = new AiSkill(current);
        AiTokenizerType tokenizer = tokenizer();
        CompletableFuture.supplyAsync(() -> SkillSize.of(snapshot, tokenizer).tokens())
            .whenComplete((tokens, error) -> Platform.runLater(() -> {
                if (disposed || error != null) {
                    return;
                }
                sizeLabel.setText(I18n.get("settings.aiSkills.shorten.size", AiSkillStatisticsFormat.number(tokens)));
            }));
    }

    /** Asks the default AI profile for a shorter version, in the background; the proposal is shown when it is ready. */
    private void propose() {
        if (busy || disposed) {
            return;
        }
        AiSkill stored = storedSkill();
        if (stored == null) {
            showStatus(I18n.get("settings.aiSkills.shorten.missing"), true);
            updateButtons();
            return;
        }
        current = stored;
        if (!AiSkillShortener.hasSomethingToShorten(stored)) {
            showStatus(I18n.get("settings.aiSkills.shorten.nothing"), true);
            updateButtons();
            return;
        }
        if (!assistant.available()) {
            showStatus(unavailableMessage(), true);
            updateButtons();
            return;
        }
        AiProfile profile = assistant.defaultProfile();
        AiSkill input = new AiSkill(stored);
        AiTokenizerType tokenizer = AiSentContext.tokenizerOf(profile);
        busy = true;
        showStatus(I18n.get("settings.aiSkills.shorten.busy"), false);
        updateButtons();
        CompletableFuture.supplyAsync(() -> compute(input, profile, tokenizer))
            .whenComplete((result, error) -> Platform.runLater(() -> finishProposal(result, error)));
    }

    /** The AI call and the checks of the reply, with the token sizes of both versions. Runs off the FX thread. */
    private Sized compute(AiSkill input, AiProfile profile, AiTokenizerType tokenizer) {
        try {
            Proposal proposal = AiSkillShortener.propose(input,
                (systemPrompt, userPrompt) -> assistant.complete(profile, systemPrompt, userPrompt));
            return new Sized(proposal,
                SkillSize.of(proposal.before(), tokenizer).tokens(),
                SkillSize.of(proposal.after(), tokenizer).tokens());
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private void finishProposal(Sized result, Throwable error) {
        if (disposed) {
            return;
        }
        busy = false;
        if (error != null) {
            showStatus(failureMessage(error), true);
            updateButtons();
            return;
        }
        showProposal(result);
    }

    private void showProposal(Sized result) {
        sized = result;
        sizesLabel.setText(I18n.get("settings.aiSkills.shorten.sizes",
            AiSkillStatisticsFormat.number(result.beforeTokens()),
            AiSkillStatisticsFormat.number(result.afterTokens())));
        if (diffPane == null) {
            diffPane = new MonacoDiffPane();
            Settings settings = EditorSettingsHelper.loadSnippetSettings();
            diffPane.setFont(settings.fontFamily(), settings.fontSize());
            diffPane.setThemeColors(settings.foregroundColor(), settings.backgroundColor());
            diffPane.setMinHeight(280);
            VBox.setVgrow(diffPane, Priority.ALWAYS);
            diffBox.getChildren().add(diffPane);
        }
        diffPane.setComparison(documentOf(result.proposal().before()), documentOf(result.proposal().after()),
            "markdown", "markdown");
        diffBox.setVisible(true);
        diffBox.setManaged(true);
        showStatus("", false);
        updateButtons();
    }

    /**
     * Saves the confirmed proposal as the user's own change. Nothing is saved when the stored skill no longer holds what the
     * proposal was made from, and a refusal of the secret guard is shown with its line and kind.
     */
    private void apply() {
        Sized confirmed = sized;
        if (confirmed == null || busy || disposed || repository == null) {
            return;
        }
        Proposal proposal = confirmed.proposal();
        List<AiSkill> stored = repository.snapshot();
        AiSkill storedSkill = null;
        for (AiSkill skill : stored) {
            if (skill != null && Objects.equals(proposal.before().getId(), skill.getId())) {
                storedSkill = skill;
                break;
            }
        }
        if (storedSkill == null) {
            showStatus(I18n.get("settings.aiSkills.shorten.missing"), true);
            return;
        }
        if (!AiSkillShortener.stillMatches(proposal.before(), storedSkill)) {
            showStatus(I18n.get("settings.aiSkills.shorten.stale"), true);
            return;
        }
        // The merge base is the skill as the proposal was made, so a rule or field changed while the dialog was open is
        // kept by the save instead of being taken for a deletion.
        AiSkillRepository.Outcome outcome = repository.commit(AiSkillShortener.mergeBase(stored, proposal),
            AiSkillShortener.savedSkills(stored, proposal));
        if (!outcome.refusals().isEmpty()) {
            showStatus(I18n.get("settings.aiSkills.save.failed", String.join(" ", outcome.refusals())), true);
            return;
        }
        if (!outcome.changed()) {
            showStatus(I18n.get("settings.aiSkills.shorten.unchanged"), true);
            return;
        }
        persist();
    }

    /** Writes the settings file in the background; the statistics are refreshed and the dialog closed once it is written. */
    private void persist() {
        busy = true;
        showStatus("", false);
        updateButtons();
        app.getGlobalSettingsManager().saveAsync().whenComplete((ignored, error) -> Platform.runLater(() -> {
            busy = false;
            if (error != null) {
                Throwable cause = unwrap(error);
                logger.warn("The shortened AI skill could not be saved: {}", String.valueOf(cause.getMessage()));
                if (!disposed) {
                    showStatus(I18n.get("settings.aiSkills.shorten.saveFailed", String.valueOf(cause.getMessage())), true);
                    updateButtons();
                }
                return;
            }
            onSaved.run();
            close();
        }));
    }

    private void openInEditor() {
        if (editorOpener != null) {
            editorOpener.accept(current.getId());
        }
        close();
    }

    /** The stored skill with the id of this dialog, or null when it no longer exists. */
    private AiSkill storedSkill() {
        if (repository == null) {
            return null;
        }
        for (AiSkill skill : repository.snapshot()) {
            if (skill != null && Objects.equals(current.getId(), skill.getId())) {
                return skill;
            }
        }
        return null;
    }

    private void updateButtons() {
        boolean canPropose = AiSkillShortener.hasSomethingToShorten(current) && assistant.available();
        proposeButton.setDisable(busy || !canPropose);
        applyButton.setDisable(busy || sized == null || repository == null);
    }

    private void showStatus(String text, boolean error) {
        statusLabel.setText(text != null ? text : "");
        statusLabel.setStyle(error ? ERROR_STYLE : MutedTextStyle.HINT);
    }

    private void release() {
        disposed = true;
        if (diffPane != null) {
            diffPane.dispose();
        }
    }

    private AiTokenizerType tokenizer() {
        return AiSentContext.tokenizerOf(assistant.defaultProfile());
    }

    private String unavailableMessage() {
        return PolicyManager.effective().aiAllowed()
            ? I18n.get("settings.aiSkills.shorten.noProfile")
            : I18n.get("settings.aiSkills.shorten.noAi");
    }

    private static String failureMessage(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof AiMemoryAssistant.NoProfileException) {
            return I18n.get("settings.aiSkills.shorten.noProfile");
        }
        if (cause instanceof ShortenException refused) {
            return problemMessage(refused);
        }
        logger.warn("The AI skill could not be shortened: {}", String.valueOf(cause.getMessage()));
        return I18n.get("settings.aiSkills.shorten.failed", String.valueOf(cause.getMessage()));
    }

    /** The text for a refused reply; a secret refusal already names its line and kind, never the value. */
    private static String problemMessage(ShortenException refused) {
        return switch (refused.problem()) {
            case NOTHING_TO_SHORTEN -> I18n.get("settings.aiSkills.shorten.nothing");
            case NO_REPLY -> I18n.get("settings.aiSkills.shorten.problem.noReply");
            case MALFORMED_REPLY -> I18n.get("settings.aiSkills.shorten.problem.malformed");
            case NOT_SHORTER -> I18n.get("settings.aiSkills.shorten.problem.notShorter");
            case NOTHING_TO_CHANGE -> I18n.get("settings.aiSkills.shorten.problem.nothingToChange");
            case SECRET -> refused.getMessage();
        };
    }

    /** The diff side of a skill: its text when that may be shortened, then its rules as one line each. */
    private static String documentOf(AiSkill skill) {
        StringBuilder document = new StringBuilder();
        if (AiSkillShortener.shortensText(skill) && skill.getContent() != null && !skill.getContent().isBlank()) {
            document.append(skill.getContent().strip()).append("\n\n");
        }
        document.append("## ").append(I18n.get("settings.aiSkills.shorten.rulesHeader")).append("\n\n");
        for (AiSkillLearnedRule rule : skill.getLearnedRules()) {
            if (rule != null && rule.getText() != null && !rule.getText().isBlank()) {
                document.append("- ").append(rule.getText().replaceAll("\\s*\\R\\s*", " ").strip()).append('\n');
            }
        }
        return document.toString();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String nameOf(AiSkill skill) {
        if (skill.getName() != null && !skill.getName().isBlank()) {
            return skill.getName();
        }
        return skill.getId() != null ? skill.getId() : "";
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }
}
