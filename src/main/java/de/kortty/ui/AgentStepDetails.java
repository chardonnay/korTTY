package de.kortty.ui;

import de.kortty.core.AgentStepReader;
import de.kortty.model.AgentActionCategory;
import de.kortty.model.TerminalAgentModels;

import java.util.List;
import java.util.Locale;

/**
 * Reads what a KI-Agent step carries for the designs that show more than its title: the command a
 * command step ran, its exit code and output lines, and a short kind for a table or a tmux pane. The
 * agent service writes a finished command's detail as its purpose, {@code Command: …},
 * {@code Exit code: …}, then {@code stdout:} and {@code stderr:} blocks; its title is {@code Read(…)} or
 * {@code Run(…)}. Pure text, so it can be tested without a toolkit.
 */
final class AgentStepDetails {

    private AgentStepDetails() {
    }

    /** The command a command step runs: from its detail, else its title without {@code Read(…)}. */
    static String command(TerminalAgentModels.AgentActivity step) {
        return AgentStepReader.command(step);
    }

    /**
     * Whether a step ran a shell command ({@code Read(…)}, {@code Run(…)}, or a detail naming the
     * command), not an action like inspecting the session.
     */
    static boolean isCommand(TerminalAgentModels.AgentActivity step) {
        return AgentStepReader.isCommand(step);
    }

    /** The exit code of a finished command step, or {@code null} when it is not known (yet). */
    static Integer exitCode(TerminalAgentModels.AgentActivity step) {
        return AgentStepReader.exitCode(step);
    }

    /** The output lines of a finished command step, standard output first; empty when it printed nothing. */
    static List<String> output(TerminalAgentModels.AgentActivity step) {
        return AgentStepReader.output(step);
    }

    /** A short lower-case word for the step's kind: {@code ssh}, {@code read}, {@code run}, {@code think}, {@code ask}, … */
    static String kind(TerminalAgentModels.AgentActivity step) {
        if (step == null) {
            return "";
        }
        return switch (step.type()) {
            case THINKING -> "think";
            case QUESTION -> "ask";
            case ERROR -> "error";
            case MESSAGE -> "note";
            case ACTION -> {
                // The service's verb says it best: Read(…) only looks, Run(…) may change the system.
                String title = step.title() != null ? step.title().strip().toLowerCase(Locale.ROOT) : "";
                if (title.startsWith("read(")) {
                    yield "read";
                }
                if (title.startsWith("run(")) {
                    yield "run";
                }
                AgentActionCategory category = step.actionCategory();
                yield category == AgentActionCategory.INSPECT ? "ssh" : category == AgentActionCategory.READ ? "read" : "run";
            }
        };
    }

    /**
     * The result a list shows next to a finished step: {@code Exit 0 · 12 lines} or the summary for a
     * command, the tokens for a thought, nothing for the rest (their text already says it all).
     */
    static String result(TerminalAgentModels.AgentActivity step) {
        if (step == null) {
            return "";
        }
        return switch (step.type()) {
            case ACTION -> {
                Integer exit = exitCode(step);
                List<String> output = output(step);
                if (exit != null) {
                    String lines = output.isEmpty() ? "" : " · " + I18n.get("aiAgentDesign.lines", output.size());
                    yield I18n.get("aiAgentDesign.exit", exit) + lines;
                }
                String summary = step.summary() != null ? step.summary().strip() : "";
                yield AgentInlineBlockLayout.oneLine(summary.replace(" - ", " · "));
            }
            case THINKING -> {
                TerminalAgentModels.AgentActivityTokenUsage usage = step.tokenUsage();
                yield usage != null && usage.known() && usage.totalTokens() > 0
                    ? I18n.get("ai.agent.activity.tokens.short", de.kortty.core.AiTokenUsageManager.formatCompact(usage.totalTokens()))
                    : "";
            }
            default -> "";
        };
    }
}
