package de.kortty.core;

import de.kortty.model.TerminalAgentModels;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the command, exit code and output of a KI-Agent command step. The agent service writes a
 * finished command's detail as its purpose, {@code Command: …}, {@code Exit code: …}, then
 * {@code stdout:} and {@code stderr:} blocks; its title is {@code Read(…)} or {@code Run(…)}. Shared by
 * the agent designs and the chat handoff, so both read a step the same way. Pure text.
 */
public final class AgentStepReader {

    private static final Pattern WRAPPED_TITLE = Pattern.compile("^(?:Read|Run)\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern EXIT_CODE = Pattern.compile("(?m)^Exit code: (-?\\d+)");
    private static final Pattern SUMMARY_EXIT = Pattern.compile("^Exit (-?\\d+)\\b");

    private AgentStepReader() {
    }

    /** The command a command step runs: from its detail, else its title without {@code Read(…)}. */
    public static String command(TerminalAgentModels.AgentActivity step) {
        if (step == null) {
            return "";
        }
        String detail = step.detail() != null ? step.detail() : "";
        for (String line : detail.lines().toList()) {
            if (line.startsWith("Command: ")) {
                return line.substring("Command: ".length()).strip();
            }
        }
        String title = step.title() != null ? step.title().strip() : "";
        Matcher wrapped = WRAPPED_TITLE.matcher(title);
        return wrapped.matches() ? wrapped.group(1).strip() : title;
    }

    /**
     * Whether a step ran a shell command ({@code Read(…)}, {@code Run(…)}, or a detail naming the
     * command), not an action like inspecting the session.
     */
    public static boolean isCommand(TerminalAgentModels.AgentActivity step) {
        if (step == null || step.type() != TerminalAgentModels.AgentActivityType.ACTION) {
            return false;
        }
        String title = step.title() != null ? step.title().strip() : "";
        return WRAPPED_TITLE.matcher(title).matches()
            || (step.detail() != null && step.detail().lines().anyMatch(line -> line.startsWith("Command: ")));
    }

    /** The exit code of a finished command step, or {@code null} when it is not known (yet). */
    public static Integer exitCode(TerminalAgentModels.AgentActivity step) {
        if (step == null) {
            return null;
        }
        Matcher detail = EXIT_CODE.matcher(step.detail() != null ? step.detail() : "");
        if (detail.find()) {
            return Integer.valueOf(detail.group(1));
        }
        Matcher summary = SUMMARY_EXIT.matcher(step.summary() != null ? step.summary().strip() : "");
        return summary.find() ? Integer.valueOf(summary.group(1)) : null;
    }

    /** The output lines of a finished command step, standard output first; empty when it printed nothing. */
    public static List<String> output(TerminalAgentModels.AgentActivity step) {
        List<String> lines = new ArrayList<>();
        if (step == null || step.detail() == null) {
            return lines;
        }
        boolean inOutput = false;
        for (String line : step.detail().lines().toList()) {
            if (line.equals("stdout:") || line.equals("stderr:")) {
                inOutput = true;
                continue;
            }
            if (inOutput) {
                lines.add(line);
            }
        }
        return lines;
    }
}
