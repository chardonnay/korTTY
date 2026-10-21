package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.List;

/**
 * The steps of a KI-Agent run as a compact list (marker, text, result), as the status designs show it
 * over the terminal: the Powerline feed, the top bar's drawer, the minimap's lens. Uses the same
 * markers and texts as the in-terminal block.
 */
final class AgentDesignStepList extends VBox {

    private int maxRows = 8;
    private AgentRunPresenter.Actions stepActions;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);

    AgentDesignStepList() {
        setSpacing(1);
        setPadding(new Insets(6, 10, 6, 10));
        setFillWidth(true);
        setMouseTransparent(true);
    }

    /** Lets a right-click on a step open its menu; the list then takes mouse events. */
    void setStepActions(AgentRunPresenter.Actions actions) {
        this.stepActions = actions;
        setMouseTransparent(actions == null);
    }

    void setMaxRows(int maxRows) {
        this.maxRows = Math.max(1, maxRows);
    }

    void setPalette(AgentDesignPalette palette) {
        this.palette = palette;
        setStyle("-fx-background-color: " + AgentDesignPalette.rgba(palette.background(), 0.97) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + ";"
            + " -fx-border-width: 1; -fx-background-radius: 6; -fx-border-radius: 6;");
    }

    /** Lists the run's steps, the newest last; older ones beyond the limit are counted on top. */
    void showSteps(AgentInlineBlockLayout.BlockState trace, String spinner, long nowMillis) {
        getChildren().clear();
        if (trace == null) {
            return;
        }
        List<TerminalAgentModels.AgentActivity> steps = AgentInlineBlockLayout.listedSteps(trace);
        int hidden = Math.max(0, steps.size() - maxRows);
        if (hidden > 0) {
            Label more = label("+" + hidden, palette.muted());
            getChildren().add(more);
        }
        for (TerminalAgentModels.AgentActivity step : steps.subList(hidden, steps.size())) {
            getChildren().add(row(trace, step, spinner, nowMillis));
        }
        if (steps.isEmpty()) {
            getChildren().add(label(I18n.get("ai.agent.activity.thinking"), palette.muted()));
        }
    }

    /** One step with its detail lines, for a lens over a single step. */
    void showStep(AgentInlineBlockLayout.BlockState trace, TerminalAgentModels.AgentActivity step, long nowMillis) {
        getChildren().setAll(row(trace, step, "", nowMillis));
        String detail = step.detail();
        if (detail != null && !detail.isBlank()) {
            List<String> lines = AgentInlineBlockLayout.wrap(detail, 60);
            for (String line : lines.subList(0, Math.min(4, lines.size()))) {
                Label label = label(line, palette.muted());
                label.setPadding(new Insets(0, 0, 0, 18));
                getChildren().add(label);
            }
        }
    }

    private HBox row(AgentInlineBlockLayout.BlockState trace, TerminalAgentModels.AgentActivity step,
                     String spinner, long nowMillis) {
        AgentInlineBlockLayout.Segment marker = AgentInlineBlockLayout.marker(step, spinner);
        Label markerLabel = label(marker.text(), palette.colorOf(marker.role()));
        markerLabel.setMinWidth(14);
        boolean command = AgentStepDetails.isCommand(step);
        Label text = label(AgentDesignPowerlineBar.stepText(step),
            step.type() == TerminalAgentModels.AgentActivityType.MESSAGE ? palette.muted() : palette.text());
        if (command) {
            text.setFont(Font.font("Monospaced", text.getFont().getSize()));
        }
        text.setTextOverrun(OverrunStyle.ELLIPSIS);
        text.setMinWidth(0);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        String result = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING ? "" : AgentStepDetails.result(step);
        Label meta = label(result.isBlank() ? AgentInlineBlockLayout.rowMeta(trace, step, nowMillis) : result, palette.muted());
        meta.setMinWidth(Label.USE_PREF_SIZE);
        HBox row = new HBox(6, markerLabel, text, meta);
        row.setAlignment(Pos.CENTER_LEFT);
        if (stepActions != null) {
            AgentDesignWidgets.stepMenuOnRightClick(row, stepActions, step);
        }
        return row;
    }

    private static Label label(String text, javafx.scene.paint.Color color) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + AgentDesignPalette.hex(color) + ";");
        return label;
    }
}
