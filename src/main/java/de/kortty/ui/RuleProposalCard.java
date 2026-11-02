package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiSecretGuard;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.memory.LearningState;
import de.kortty.memory.RuleDetector;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSource;
import de.kortty.memory.RuleTarget;
import de.kortty.memory.SavedRule;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * The card that offers to keep a rule: the rule text, which the user may edit, the place it is saved to with an exact
 * preview of the entry, and three answers. Nothing is written before Save; Not now postpones the rule for a week, and
 * Never for this rule keeps it from being proposed again. A card that is answered reports it once through
 * {@code onFinished}. A saved card is handed to the host at once, which removes it; the undo toast that follows carries the
 * confirmation, so the one-line confirmation shown on the card is not seen on the hosts. FX thread.
 */
final class RuleProposalCard extends VBox {

    private static final int MAX_CHARACTERS = RuleDetector.MAX_RULE_CHARACTERS;
    private static final String CARD_STYLE =
        "-fx-border-color: rgba(56, 189, 248, 0.85); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 10;";
    private static final String REFUSAL_STYLE = "-fx-text-fill: #d9534f;";
    private static final String SMALL_STYLE = "-fx-font-size: 0.8462em;";
    private static final String PREVIEW_STYLE = "-fx-font-family: 'Monospaced'; -fx-font-size: 0.9231em;";

    private final RuleProposal proposal;
    private final SolutionChoiceRules.Setup rules;
    private final @Nullable Window toastOwner;
    private final Consumer<RuleProposalCard> onFinished;
    private final Label title;
    private final Label hint = new Label(I18n.get("ai.learn.card.hint"));
    private final TextArea ruleArea = new TextArea();
    private final Label counter = new Label();
    private final ComboBox<RuleTarget> targetBox = new ComboBox<>();
    private final Label skillHint = new Label(I18n.get("ai.triage.rule.skillHint"));
    private final Label preview = new Label();
    private final Label message = new Label();
    private final Button saveButton = new Button(I18n.get("ai.learn.card.save"));
    private final Button laterButton = new Button(I18n.get("ai.learn.card.later"));
    private final Button neverButton = new Button(I18n.get("ai.learn.card.never"));
    private final VBox body = new VBox(6);
    private final HBox buttons = new HBox(8, saveButton, laterButton, neverButton);
    private List<RuleTarget> targets = List.of();
    private boolean updatingTargets;
    private boolean targetChosenByUser;
    private boolean refused;
    private boolean saving;
    private boolean finished;

    /**
     * @param proposal    the rule as found or typed, with its source and connection
     * @param toastOwner  the window the undo of a saved rule hangs over; {@code null} takes the card's own window
     * @param onFinished  called once when the card is answered: saved (the host removes it), not now, or never
     */
    RuleProposalCard(RuleProposal proposal, @Nullable Window toastOwner, Consumer<RuleProposalCard> onFinished) {
        this.proposal = proposal;
        this.toastOwner = toastOwner;
        this.onFinished = onFinished;
        this.rules = SolutionChoiceRules.Setup.of(settingsManager());
        this.title = bold(I18n.get(proposal.source() == RuleSource.MANUAL
            ? "ai.learn.card.titleManual" : "ai.learn.card.title"));
        setSpacing(8);
        setStyle(CARD_STYLE);
        setPadding(new Insets(10));
        hint.setWrapText(true);
        hint.setStyle(SMALL_STYLE);
        buildBody();
        buildButtons();
        message.setWrapText(true);
        message.setVisible(false);
        message.setManaged(false);
        getChildren().addAll(title, hint, body, buttons, message);
        refresh();
    }

    private void buildBody() {
        ruleArea.setWrapText(true);
        ruleArea.setPrefRowCount(2);
        ruleArea.setPromptText(I18n.get("ai.triage.rule.prompt"));
        ruleArea.setText(proposal.text());
        ruleArea.textProperty().addListener((observed, was, now) -> refresh());
        counter.setMaxWidth(Double.MAX_VALUE);
        counter.setAlignment(Pos.CENTER_RIGHT);
        counter.setStyle(SMALL_STYLE);
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
            refreshPreview();
            refreshButtons();
        });
        HBox targetRow = new HBox(8, new Label(I18n.get("ai.triage.rule.target")), targetBox);
        HBox.setHgrow(targetBox, Priority.ALWAYS);
        skillHint.setWrapText(true);
        skillHint.setStyle(SMALL_STYLE);
        preview.setWrapText(true);
        preview.setStyle(PREVIEW_STYLE);
        body.getChildren().addAll(sectionLabel(I18n.get("ai.learn.card.ruleLabel")), ruleArea, counter, targetRow,
            skillHint, preview);
    }

    private void buildButtons() {
        saveButton.setDefaultButton(true);
        saveButton.setOnAction(event -> save());
        laterButton.setOnAction(event -> dismiss(false));
        neverButton.setOnAction(event -> dismiss(true));
        buttons.setAlignment(Pos.CENTER_LEFT);
    }

    /** Recomputes the places, the counter, the secret check and the preview for the text as it is now. */
    private void refresh() {
        String text = oneLine(ruleText());
        List<RuleTarget> offered = rules.targets(proposalWith(text));
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
            RuleTarget suggestion = rules.suggestion(proposalWith(text));
            updatingTargets = true;
            try {
                targetBox.setValue(offered.contains(suggestion) ? suggestion : offered.stream().findFirst().orElse(null));
            } finally {
                updatingTargets = false;
            }
        }
        refreshRefusal(text);
        refreshPreview();
        refreshButtons();
    }

    /** The refusal of the text: too long, or a possible secret with its line and kind, never the value. */
    private void refreshRefusal(String text) {
        int length = text.codePointCount(0, text.length());
        counter.setText(I18n.get("ai.learn.card.counter", length, MAX_CHARACTERS));
        counter.setStyle(length > MAX_CHARACTERS ? REFUSAL_STYLE + SMALL_STYLE : SMALL_STYLE);
        String refusal = null;
        if (length > MAX_CHARACTERS) {
            refusal = I18n.get("ai.learn.refused.tooLong", length, MAX_CHARACTERS);
        } else if (!text.isEmpty()) {
            AiSecretGuard.Finding finding = SolutionChoiceRules.firstSecret(text);
            if (finding != null) {
                refusal = SolutionChoiceRules.refusal(finding);
            }
        }
        refused = refusal != null;
        showMessage(refusal != null ? refusal : "", refusal != null ? REFUSAL_STYLE : null);
    }

    private void refreshPreview() {
        RuleTarget target = targetBox.getValue();
        boolean skill = SolutionChoiceRules.appliesToSkill(target);
        skillHint.setVisible(skill);
        skillHint.setManaged(skill);
        String text = target == null || oneLine(ruleText()).isEmpty()
            ? ""
            : RuleCardText.preview(target, oneLine(ruleText()), rules.skills(), rules.catalog());
        preview.setText(text);
        preview.setVisible(!text.isEmpty());
        preview.setManaged(!text.isEmpty());
    }

    private void refreshButtons() {
        boolean open = !saving && !finished;
        boolean ready = open && !refused && targetBox.getValue() != null && !targets.isEmpty()
            && !oneLine(ruleText()).isEmpty();
        saveButton.setDisable(!ready);
        laterButton.setDisable(!open);
        neverButton.setDisable(!open);
        ruleArea.setDisable(!open);
        targetBox.setDisable(!open);
    }

    /** Saves the text to the chosen place on the background, and shows the undo or the refusal when it is done. */
    private void save() {
        String text = oneLine(ruleText());
        RuleTarget target = targetBox.getValue();
        if (saving || finished || refused || text.isEmpty() || target == null || rules.service() == null) {
            return;
        }
        saving = true;
        refreshButtons();
        rules.service().confirm(proposalWith(text), text, target)
            .whenComplete((saved, failure) -> Platform.runLater(() -> finishSave(text, target, saved, failure)));
    }

    private void finishSave(String text, RuleTarget target, SavedRule saved, Throwable failure) {
        saving = false;
        if (failure != null) {
            showMessage(RuleCardText.refusalText(failure), REFUSAL_STYLE);
            refreshButtons();
            return;
        }
        finished = true;
        String confirmation = RuleCardText.savedMessage(target, proposal, rules.skills(), rules.catalog());
        title.setVisible(false);
        title.setManaged(false);
        hint.setVisible(false);
        hint.setManaged(false);
        body.setVisible(false);
        body.setManaged(false);
        buttons.setVisible(false);
        buttons.setManaged(false);
        showMessage(confirmation, null);
        RuleSavedToast.show(saveOwner(), confirmation, saved);
        onFinished.accept(this);
    }

    /** "Nicht jetzt" or "Nie für diese Regel": the card goes, and the learning state keeps the answer. */
    private void dismiss(boolean forever) {
        if (saving || finished) {
            return;
        }
        finished = true;
        String rule = proposal.text();
        if (!rule.isBlank()) {
            RuleLearning.runInBackground(() -> {
                LearningState state = LearningState.shared();
                if (forever) {
                    state.reject(rule);
                } else {
                    state.snooze(rule);
                }
            });
        }
        setVisible(false);
        setManaged(false);
        onFinished.accept(this);
    }

    private void showMessage(String text, @Nullable String style) {
        message.setText(text);
        message.setStyle(style != null ? style : "");
        boolean shown = text != null && !text.isBlank();
        message.setVisible(shown);
        message.setManaged(shown);
    }

    private RuleProposal proposalWith(String text) {
        return new RuleProposal(text, proposal.source(), proposal.connectionId(), proposal.connectionName(),
            proposal.connectionHost(), proposal.topicHint());
    }

    private String ruleText() {
        String text = ruleArea.getText();
        return text == null ? "" : text;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** The window the undo of a saved rule hangs over: the one given, else the window this card is shown in. */
    private @Nullable Window saveOwner() {
        if (toastOwner != null) {
            return toastOwner;
        }
        return getScene() != null ? getScene().getWindow() : null;
    }

    private static Label bold(String text) {
        Label label = sectionLabel(text);
        label.setWrapText(true);
        return label;
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static @Nullable GlobalSettingsManager settingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }
}
