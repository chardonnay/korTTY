package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiSecretGuard;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSaveException;
import de.kortty.memory.RuleTarget;
import de.kortty.memory.SavedRule;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * The solution options of a KI-Agent run, shown before the run starts: the complexity reasons, one card per option
 * with the recommended one first, an own idea or instruction, and an optional permanent rule with the place it is
 * saved to. The same card in the classic panel, the chat window and the design strip.
 *
 * <p>Each button answers once: {@code onChoice} gets the answer. A rule is saved only with the proceed button, and a
 * refused rule keeps the card open with the reason. FX thread.</p>
 */
final class SolutionChoicePane extends VBox {

    private static final Duration SAVED_HOLD = Duration.millis(1200);
    private static final String OPTION_STYLE =
        "-fx-border-color: rgba(128, 128, 128, 0.45); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 10 8 10;";
    private static final String RECOMMENDED_STYLE =
        "-fx-border-color: rgba(56, 189, 248, 0.85); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 10 8 10;";
    private static final String REFUSAL_STYLE = "-fx-text-fill: #d9534f;";

    private final TerminalAgentModels.TriageProposal proposal;
    private final Consumer<TerminalAgentModels.SolutionChoice> onChoice;
    private final @Nullable Window toastOwner;
    private final SolutionChoiceRules.Setup rules;
    private final ToggleGroup toggles = new ToggleGroup();
    private final List<RadioButton> optionRadios = new ArrayList<>();
    private final RadioButton ownIdeaRadio = new RadioButton(I18n.get("ai.triage.ownIdea.option"));
    private final TextArea ideaArea = new TextArea();
    private final TextArea ruleArea = new TextArea();
    private final ComboBox<RuleTarget> targetBox = new ComboBox<>();
    private final Label skillHint = new Label(I18n.get("ai.triage.rule.skillHint"));
    private final Label message = new Label();
    private final VBox ruleSection = new VBox(6);
    private final Button proceedButton = new Button(I18n.get("ai.triage.proceed"));
    private final Button asRequestedButton = new Button(I18n.get("ai.triage.asRequested"));
    private final Button cancelButton = new Button(I18n.get("dialog.cancel"));
    private List<RuleTarget> targets = List.of();
    private boolean updatingTargets;
    private boolean targetChosenByUser;
    private boolean secretShown;
    private boolean noTargetShown;
    private boolean saving;
    private boolean decided;

    /**
     * @param proposal the complexity check's proposal, with the connection the run drives
     * @param onChoice receives the user's answer once, when a button is pressed and the rule, if any, is saved
     */
    SolutionChoicePane(TerminalAgentModels.TriageProposal proposal, Consumer<TerminalAgentModels.SolutionChoice> onChoice) {
        this(proposal, onChoice, null);
    }

    /**
     * @param toastOwner the window the undo of a saved rule hangs over; {@code null} takes the window this card is shown
     *                   in at the moment the rule is saved
     */
    SolutionChoicePane(TerminalAgentModels.TriageProposal proposal, Consumer<TerminalAgentModels.SolutionChoice> onChoice,
                       @Nullable Window toastOwner) {
        this.proposal = proposal;
        this.onChoice = onChoice;
        this.toastOwner = toastOwner;
        this.rules = SolutionChoiceRules.Setup.of(settingsManager());
        setSpacing(8);
        setPadding(new Insets(4));
        buildOptions();
        buildOwnIdea();
        buildRule();
        buildButtons();
        refreshRule();
        refreshButtons();
    }

    private void buildOptions() {
        if (proposal.isQuestion()) {
            // A question during a run: what the agent asks, in its own words, under a header that says who asks.
            Label question = wrapped(proposal.question().strip());
            question.setStyle("-fx-font-weight: bold;");
            getChildren().addAll(sectionLabel(I18n.get("ai.agent.question.header")), question);
        } else {
            Label header = wrapped(I18n.get(proposal.complex() ? "ai.triage.header.complex" : "ai.triage.header.simple"));
            header.setStyle("-fx-font-weight: bold;");
            getChildren().add(header);
        }
        if (!proposal.reasons().isEmpty()) {
            VBox reasons = new VBox(2);
            for (String reason : proposal.reasons()) {
                reasons.getChildren().add(wrapped(proposal.isQuestion() ? reason : "• " + reason));
            }
            getChildren().addAll(
                sectionLabel(I18n.get(proposal.isQuestion() ? "ai.agent.question.why" : "ai.triage.reasons")), reasons);
        }
        int recommended = Math.max(0, Math.min(proposal.options().size() - 1, proposal.recommended()));
        for (int index = 0; index < proposal.options().size(); index++) {
            TerminalAgentModels.PlanOption option = proposal.options().get(index);
            boolean isRecommended = index == recommended;
            String title = option.title() + (isRecommended ? " — " + I18n.get("ai.triage.recommended") : "");
            RadioButton radio = new RadioButton(title);
            radio.setWrapText(true);
            radio.setMnemonicParsing(false);
            radio.setToggleGroup(toggles);
            radio.setSelected(isRecommended);
            radio.selectedProperty().addListener((observed, was, now) -> refreshButtons());
            optionRadios.add(radio);
            getChildren().add(optionCard(option, radio, isRecommended));
        }
    }

    private VBox optionCard(TerminalAgentModels.PlanOption option, RadioButton radio, boolean recommended) {
        if (proposal.isQuestion()) {
            // A suggested answer is one line: no plan details to unfold.
            VBox card = new VBox(4, radio);
            if (!blank(option.summary())) {
                card.getChildren().add(wrapped(option.summary()));
            }
            card.setStyle(recommended ? RECOMMENDED_STYLE : OPTION_STYLE);
            return card;
        }
        VBox details = new VBox(4);
        if (!blank(option.feasibility())) {
            details.getChildren().add(wrapped(I18n.get("ai.triage.feasibility", option.feasibility())));
        }
        addItems(details, "ai.triage.risks", option.risks());
        addItems(details, "ai.triage.prerequisites", option.prerequisites());
        addItems(details, "ai.triage.steps", option.steps());
        addItems(details, "ai.triage.alternatives", option.alternatives());
        TitledPane more = new TitledPane(I18n.get("ai.triage.details"), details);
        // The steps and risks of the selected option become the binding plan, so they are shown while it is selected.
        more.setExpanded(radio.isSelected());
        radio.selectedProperty().addListener((observed, was, now) -> more.setExpanded(now));
        VBox card = new VBox(6, radio, wrapped(option.summary()), more);
        card.setStyle(recommended ? RECOMMENDED_STYLE : OPTION_STYLE);
        return card;
    }

    private void buildOwnIdea() {
        ownIdeaRadio.setToggleGroup(toggles);
        ownIdeaRadio.selectedProperty().addListener((observed, was, now) -> refreshButtons());
        ideaArea.setWrapText(true);
        ideaArea.setPrefRowCount(3);
        ideaArea.setPromptText(I18n.get(proposal.isQuestion() ? "ai.agent.question.own.prompt" : "ai.triage.ownIdea.prompt"));
        ideaArea.textProperty().addListener((observed, was, now) -> refreshButtons());
        if (proposal.isQuestion()) {
            ownIdeaRadio.setText(I18n.get("ai.agent.question.own.option"));
            ideaArea.setPrefRowCount(2);
            getChildren().addAll(ownIdeaRadio, ideaArea);
            if (proposal.options().isEmpty()) {
                // Without suggested answers the own answer is the only one.
                ownIdeaRadio.setSelected(true);
            }
            return;
        }
        getChildren().addAll(ownIdeaRadio, sectionLabel(I18n.get("ai.triage.ownIdea.label")), ideaArea,
            wrapped(I18n.get("ai.triage.ownIdea.hint")));
    }

    private void buildRule() {
        ruleArea.setWrapText(true);
        ruleArea.setPrefRowCount(2);
        ruleArea.setPromptText(I18n.get("ai.triage.rule.prompt"));
        ruleArea.textProperty().addListener((observed, was, now) -> refreshRule());
        targetBox.setMaxWidth(Double.MAX_VALUE);
        targetBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(RuleTarget target) {
                return target == null ? "" : SolutionChoiceRules.label(target, rules.skills(), rules.catalog());
            }

            @Override
            public RuleTarget fromString(String text) {
                return null;
            }
        });
        targetBox.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(RuleTarget target, boolean empty) {
                super.updateItem(target, empty);
                setText(empty || target == null ? "" : SolutionChoiceRules.label(target, rules.skills(), rules.catalog()));
            }
        });
        targetBox.valueProperty().addListener((observed, was, now) -> {
            if (!updatingTargets) {
                targetChosenByUser = true;
            }
            refreshSkillHint();
        });
        Label targetLabel = new Label(I18n.get("ai.triage.rule.target"));
        HBox targetRow = new HBox(8, targetLabel, targetBox);
        HBox.setHgrow(targetBox, Priority.ALWAYS);
        skillHint.setWrapText(true);
        skillHint.setStyle("-fx-font-size: 0.8462em;");
        message.setWrapText(true);
        message.setVisible(false);
        message.setManaged(false);
        ruleSection.getChildren().addAll(sectionLabel(I18n.get("ai.triage.rule.label")), ruleArea,
            wrapped(I18n.get(proposal.isQuestion() ? "ai.agent.question.rule.hint" : "ai.triage.rule.hintProceed")),
            targetRow, skillHint);
        getChildren().addAll(ruleSection, message);
    }

    private void buildButtons() {
        proceedButton.setDefaultButton(true);
        proceedButton.setOnAction(event -> proceed());
        // Only Proceed saves a typed rule: running the request as asked is an answer without a rule.
        asRequestedButton.setOnAction(event -> complete(SolutionChoiceRules.asRequestedChoice(ideaText())));
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(event -> {
            if (!saving) {
                complete(TerminalAgentModels.SolutionChoice.cancel());
            }
        });
        HBox buttons = new HBox(8, proceedButton, asRequestedButton, cancelButton);
        if (proposal.isQuestion()) {
            // A question has no "as requested": the answer, or stopping the run.
            proceedButton.setText(I18n.get("ai.agent.question.answer"));
            cancelButton.setText(I18n.get("ai.agent.question.stop"));
            buttons.getChildren().remove(asRequestedButton);
        }
        getChildren().add(buttons);
    }

    private void proceed() {
        TerminalAgentModels.SolutionChoice choice = SolutionChoiceRules.proceedChoice(
            selectedOption(), ownIdeaRadio.isSelected(), ideaText());
        if (choice != null) {
            choose(choice);
        }
    }

    /**
     * Saves the typed rule, when there is one and a place for it, and then completes the answer. A refused rule keeps
     * the card open with the reason; the answer is not given.
     */
    private void choose(TerminalAgentModels.SolutionChoice choice) {
        if (decided || saving) {
            return;
        }
        String rule = ruleText();
        RuleTarget target = targetBox.getValue();
        if (rule.isBlank()) {
            complete(choice);
            return;
        }
        if (targets.isEmpty() || target == null) {
            // A typed rule with no place is not dropped silently: the card stays open and says why.
            noTargetShown = true;
            showMessage(I18n.get(RuleSaveException.Reason.NO_TARGET.messageKey()), REFUSAL_STYLE);
            return;
        }
        saving = true;
        setRuleEnabled(false);
        refreshButtons();
        RuleProposal ruleProposal = SolutionChoiceRules.ruleFor(proposal, rule);
        rules.service().confirm(ruleProposal, rule, target)
            .whenComplete((saved, failure) -> Platform.runLater(() -> finishSave(choice, target, saved, failure)));
    }

    private void finishSave(
        TerminalAgentModels.SolutionChoice choice,
        RuleTarget target,
        SavedRule saved,
        Throwable failure) {
        if (failure != null) {
            saving = false;
            setRuleEnabled(true);
            refreshButtons();
            showMessage(refusalText(failure), REFUSAL_STYLE);
            secretShown = false;
            return;
        }
        String message = SolutionChoiceRules.savedMessage(target, proposal, rules.skills(), rules.catalog());
        showMessage(message, null);
        RuleSavedToast.show(saveOwner(), message, saved);
        PauseTransition hold = new PauseTransition(SAVED_HOLD);
        hold.setOnFinished(event -> complete(choice));
        hold.play();
    }

    /** The window the undo of a saved rule hangs over: the one given, else the window this card is shown in. */
    private @Nullable Window saveOwner() {
        if (toastOwner != null) {
            return toastOwner;
        }
        return getScene() != null ? getScene().getWindow() : null;
    }

    private void complete(TerminalAgentModels.SolutionChoice choice) {
        if (decided) {
            return;
        }
        decided = true;
        saving = false;
        proceedButton.setDisable(true);
        asRequestedButton.setDisable(true);
        cancelButton.setDisable(true);
        setRuleEnabled(false);
        onChoice.accept(choice);
    }

    /** Recomputes the places for the rule text, keeps the user's own choice, and shows a secret refusal inline. */
    private void refreshRule() {
        String text = ruleText();
        List<RuleTarget> offered = rules.targets(SolutionChoiceRules.ruleFor(proposal, text));
        if (!offered.equals(targets)) {
            targets = offered;
            updatingTargets = true;
            try {
                targetBox.getItems().setAll(offered);
            } finally {
                updatingTargets = false;
            }
        }
        if (!targetChosenByUser) {
            RuleTarget suggestion = rules.suggestion(SolutionChoiceRules.ruleFor(proposal, text));
            updatingTargets = true;
            try {
                targetBox.setValue(offered.contains(suggestion) ? suggestion : offered.stream().findFirst().orElse(null));
            } finally {
                updatingTargets = false;
            }
        }
        // The rule stays visible while it has text, so that a refused rule can be edited or cleared.
        boolean showRule = !targets.isEmpty() || !text.isBlank();
        ruleSection.setVisible(showRule);
        ruleSection.setManaged(showRule);
        if (noTargetShown && (text.isBlank() || !targets.isEmpty())) {
            showMessage("", null);
            noTargetShown = false;
        }
        refreshSkillHint();
        refreshSecret(text);
        refreshButtons();
    }

    private void refreshSecret(String text) {
        AiSecretGuard.Finding finding = SolutionChoiceRules.firstSecret(text);
        if (finding != null) {
            showMessage(SolutionChoiceRules.refusal(finding), REFUSAL_STYLE);
            secretShown = true;
        } else if (secretShown) {
            showMessage("", null);
            secretShown = false;
        }
    }

    private void refreshSkillHint() {
        boolean skill = SolutionChoiceRules.appliesToSkill(targetBox.getValue());
        skillHint.setVisible(skill);
        skillHint.setManaged(skill);
    }

    private void refreshButtons() {
        boolean ideaMissing = ownIdeaRadio.isSelected() && ideaText().isBlank();
        proceedButton.setDisable(saving || decided || ideaMissing);
        // Proceed is disabled for an empty own idea; the tooltip says what is missing.
        proceedButton.setTooltip(ideaMissing ? new Tooltip(ideaArea.getPromptText()) : null);
        asRequestedButton.setDisable(saving || decided);
        cancelButton.setDisable(saving || decided);
    }

    private void setRuleEnabled(boolean enabled) {
        ruleArea.setDisable(!enabled);
        targetBox.setDisable(!enabled);
    }

    private void showMessage(String text, String style) {
        message.setText(text);
        message.setStyle(style != null ? style : "");
        boolean shown = text != null && !text.isBlank();
        message.setVisible(shown);
        message.setManaged(shown);
    }

    private int selectedOption() {
        for (int index = 0; index < optionRadios.size(); index++) {
            if (optionRadios.get(index).isSelected()) {
                return index;
            }
        }
        return -1;
    }

    private String ideaText() {
        String text = ideaArea.getText();
        return text == null ? "" : text.strip();
    }

    private String ruleText() {
        String text = ruleArea.getText();
        return text == null ? "" : text.strip();
    }

    private static String refusalText(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        if (cause instanceof RuleSaveException refusal) {
            return refusal.userMessage();
        }
        return I18n.get("ai.learn.refused.failed");
    }

    private static void addItems(VBox box, String headingKey, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        box.getChildren().add(sectionLabel(I18n.get(headingKey)));
        for (String item : items) {
            if (!blank(item)) {
                box.getChildren().add(wrapped("• " + item));
            }
        }
    }

    private static Label sectionLabel(String text) {
        Label label = wrapped(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static GlobalSettingsManager settingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }
}
