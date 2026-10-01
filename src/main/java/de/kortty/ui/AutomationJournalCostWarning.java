package de.kortty.ui;

import de.kortty.core.AiCostCalculator;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AutomationJournalCostEstimator;
import de.kortty.model.AutomationJournalAiMode;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The warning shown before session journals — and especially their AI summaries — are switched
 * on for an automation (a JobScheduler job or the AI Swarm): it runs on every run and every
 * server without anyone watching, so the tokens add up quickly. Shows an estimate and the
 * cheaper alternatives, and lets the user back out.
 */
public final class AutomationJournalCostWarning {

    private AutomationJournalCostWarning() {
    }

    /**
     * Shows the warning and returns true when the user confirms.
     *
     * @param aiMode   the AI mode that will be active
     * @param estimate the cost estimate, or null when none could be made
     */
    public static boolean confirm(Window owner, AutomationJournalAiMode aiMode,
                                  AutomationJournalCostEstimator.Estimate estimate) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogThemeHelper.applyTheme(alert);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("journal.automation.warning.title"));
        alert.setHeaderText(I18n.get(aiMode == AutomationJournalAiMode.OFF
            ? "journal.automation.warning.header.capture"
            : "journal.automation.warning.header.ai"));
        Label content = new Label(buildText(aiMode, estimate));
        content.setWrapText(true);
        content.setMaxWidth(520);
        alert.getDialogPane().setContent(content);
        ButtonType enable = new ButtonType(I18n.get("journal.automation.warning.confirm"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.get("journal.automation.warning.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, enable);
        return alert.showAndWait().filter(enable::equals).isPresent();
    }

    /** The warning's body text; package-private for tests. */
    static String buildText(AutomationJournalAiMode aiMode, AutomationJournalCostEstimator.Estimate estimate) {
        List<String> paragraphs = new ArrayList<>();
        if (aiMode == AutomationJournalAiMode.OFF) {
            paragraphs.add(I18n.get("journal.automation.warning.captureOnly"));
            paragraphs.add(I18n.get("journal.automation.warning.storage"));
            return String.join("\n\n", paragraphs);
        }
        paragraphs.add(I18n.get("journal.automation.warning.body"));
        if (estimate != null) {
            paragraphs.add(estimateText(estimate));
        }
        paragraphs.add(I18n.get("journal.automation.warning.alternatives"));
        paragraphs.add(I18n.get("journal.automation.warning.storage"));
        return String.join("\n\n", paragraphs);
    }

    private static String estimateText(AutomationJournalCostEstimator.Estimate estimate) {
        Locale locale = Locale.getDefault();
        StringBuilder text = new StringBuilder(I18n.get(estimate.fromHistory()
                ? "journal.automation.warning.perRun.history"
                : "journal.automation.warning.perRun.bound",
            AiTokenUsageManager.formatCompact(estimate.tokensPerRun())));
        if (estimate.tokensPerMonth() != null && estimate.runsPerDay() != null) {
            text.append('\n').append(I18n.get("journal.automation.warning.perMonth",
                String.format(locale, "%.1f", estimate.runsPerDay()),
                AiTokenUsageManager.formatCompact(estimate.tokensPerDay()),
                AiTokenUsageManager.formatCompact(estimate.tokensPerMonth())));
        }
        if (estimate.local()) {
            text.append('\n').append(I18n.get("journal.automation.warning.local"));
        } else if (!Double.isNaN(estimate.costPerRun())) {
            String perRun = AiCostCalculator.format(estimate.costPerRun(), estimate.currency(), locale);
            text.append('\n').append(Double.isNaN(estimate.costPerMonth())
                ? I18n.get("journal.automation.warning.costPerRun", perRun)
                : I18n.get("journal.automation.warning.cost", perRun,
                    AiCostCalculator.format(estimate.costPerMonth(), estimate.currency(), locale)));
        } else {
            text.append('\n').append(I18n.get("journal.automation.warning.noPrice"));
        }
        if (estimate.remainingQuota() != Long.MAX_VALUE) {
            text.append('\n').append(I18n.get("journal.automation.warning.quota",
                AiTokenUsageManager.formatCompact(estimate.remainingQuota())));
        }
        return text.toString();
    }
}
