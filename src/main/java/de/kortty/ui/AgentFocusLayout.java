package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out the lines KI-Agent design 17 (focus mode) writes into the terminal: what the agent does, as if
 * it had typed it. A summary line, each command as {@code $ command} with the last lines of its output
 * and its exit code, the agent's conclusions, an open approval with its keys, and after the run the
 * answer. Every line starts with two spaces, where the focus mode draws its glowing bar. Pure text
 * layout; {@link AgentTerminalBlockWriter} writes the lines and colours them by role.
 */
final class AgentFocusLayout {

    static final String INDENT = "  ";
    static final int OUTPUT_LINES = 3;

    private AgentFocusLayout() {
    }

    static List<AgentInlineBlockLayout.Line> render(AgentInlineBlockLayout.BlockState state, int columns,
                                                    String spinner, long nowMillis) {
        int width = Math.max(AgentInlineBlockLayout.MIN_WIDTH, columns - 2);
        List<AgentInlineBlockLayout.Line> lines = new ArrayList<>();
        lines.add(summary(state, width, spinner, nowMillis));
        if (!state.prompt.isBlank()) {
            lines.add(line(width, seg("› ", AgentInlineBlockLayout.Role.ACCENT),
                seg(AgentInlineBlockLayout.oneLine(state.prompt), AgentInlineBlockLayout.Role.TEXT)));
        }
        for (TerminalAgentModels.AgentActivity step : AgentInlineBlockLayout.listedSteps(state)) {
            addStep(lines, state, step, width, spinner, nowMillis);
        }
        if (state.pendingApproval != null) {
            addApproval(lines, state.pendingApproval, width);
        } else if (state.pendingPassword != null) {
            lines.add(line(width, seg("? ", AgentInlineBlockLayout.Role.WARN),
                seg(AgentInlineBlockLayout.oneLine(I18n.get("ai.agent.activity.passwordRequired")), AgentInlineBlockLayout.Role.TEXT),
                seg("  " + I18n.get("aiAgentDesign.inline.passwordHint"), AgentInlineBlockLayout.Role.MUTED)));
        }
        if (state.finished) {
            String answer = AgentInlineBlockLayout.finalAnswer(state);
            List<String> wrapped = AgentInlineBlockLayout.wrap(answer, width - INDENT.length() - 2);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(line(width, seg(i == 0 ? "◆ " : "  ", AgentInlineBlockLayout.Role.ACCENT),
                    seg(wrapped.get(i), AgentInlineBlockLayout.Role.TEXT)));
            }
        }
        return lines;
    }

    /** {@code ◆ KI-Agent · 2 commands · Exit 0 · 0:42}, with a spinner while the run works. */
    private static AgentInlineBlockLayout.Line summary(AgentInlineBlockLayout.BlockState state, int width,
                                                      String spinner, long nowMillis) {
        List<AgentInlineBlockLayout.Segment> segments = new ArrayList<>();
        segments.add(seg("◆ ", AgentInlineBlockLayout.Role.ACCENT));
        segments.add(seg(I18n.get("aiAgentDesign.inline.title"), AgentInlineBlockLayout.Role.TITLE));
        segments.add(seg(" · " + I18n.get("aiAgentDesign.inline.steps", state.commandCount()), AgentInlineBlockLayout.Role.MUTED));
        Integer exit = lastExitCode(state);
        if (exit != null) {
            segments.add(seg(" · ", AgentInlineBlockLayout.Role.MUTED));
            segments.add(seg(I18n.get("aiAgentDesign.exit", exit),
                exit == 0 ? AgentInlineBlockLayout.Role.OK : AgentInlineBlockLayout.Role.ERROR));
        }
        segments.add(seg(" · " + AgentInlineBlockLayout.formatElapsed(state.elapsedMillis(nowMillis)), AgentInlineBlockLayout.Role.MUTED));
        if (state.paused) {
            segments.add(seg("  ⏸ " + I18n.get("aiAgentDesign.inline.paused"), AgentInlineBlockLayout.Role.WARN));
        } else if (!state.finished && spinner != null && !spinner.isBlank()) {
            segments.add(seg("  " + spinner, AgentInlineBlockLayout.Role.ACCENT));
        }
        return line(width, segments.toArray(AgentInlineBlockLayout.Segment[]::new));
    }

    private static void addStep(List<AgentInlineBlockLayout.Line> lines, AgentInlineBlockLayout.BlockState state,
                                TerminalAgentModels.AgentActivity step, int width, String spinner, long nowMillis) {
        boolean running = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
        if (step.type() == TerminalAgentModels.AgentActivityType.ACTION && !AgentStepDetails.isCommand(step)) {
            // An action that is no shell command (inspecting the session): one line with its result.
            String result = running ? AgentInlineBlockLayout.rowMeta(state, step, nowMillis) : AgentStepDetails.result(step);
            lines.add(line(width, AgentInlineBlockLayout.marker(step, spinner),
                seg(" " + AgentInlineBlockLayout.oneLine(AgentInlineBlockLayout.rowText(step)), AgentInlineBlockLayout.Role.TEXT),
                seg(result.isBlank() ? "" : "  " + result, AgentInlineBlockLayout.Role.MUTED)));
            return;
        }
        switch (step.type()) {
            case ACTION -> {
                lines.add(line(width, seg("$ ", AgentInlineBlockLayout.Role.ACCENT),
                    seg(AgentInlineBlockLayout.oneLine(AgentStepDetails.command(step)), AgentInlineBlockLayout.Role.TITLE),
                    seg(running ? "  " + spinner + " " + AgentInlineBlockLayout.rowMeta(state, step, nowMillis) : "",
                        AgentInlineBlockLayout.Role.MUTED)));
                if (running) {
                    return;
                }
                List<String> output = AgentStepDetails.output(step);
                for (String outputLine : output.subList(Math.max(0, output.size() - OUTPUT_LINES), output.size())) {
                    lines.add(line(width, seg("  " + outputLine.stripTrailing(), AgentInlineBlockLayout.Role.MUTED)));
                }
                Integer exit = AgentStepDetails.exitCode(step);
                if (exit != null && exit != 0) {
                    lines.add(line(width, seg("  " + I18n.get("aiAgentDesign.exit", exit), AgentInlineBlockLayout.Role.ERROR)));
                }
            }
            case THINKING -> lines.add(line(width, seg((running ? spinner : "◇") + " ", AgentInlineBlockLayout.Role.THINK),
                seg(AgentInlineBlockLayout.oneLine(AgentInlineBlockLayout.rowText(step)), AgentInlineBlockLayout.Role.THINK)));
            case QUESTION -> lines.add(line(width, AgentInlineBlockLayout.marker(step, spinner),
                seg(" " + AgentInlineBlockLayout.oneLine(AgentInlineBlockLayout.rowText(step)), AgentInlineBlockLayout.Role.TEXT)));
            case ERROR -> lines.add(line(width, seg("✕ ", AgentInlineBlockLayout.Role.ERROR),
                seg(AgentInlineBlockLayout.oneLine(AgentInlineBlockLayout.rowText(step)), AgentInlineBlockLayout.Role.ERROR)));
            case MESSAGE -> lines.add(line(width, seg("• ", AgentInlineBlockLayout.Role.MUTED),
                seg(AgentInlineBlockLayout.oneLine(AgentInlineBlockLayout.rowText(step)), AgentInlineBlockLayout.Role.MUTED)));
        }
    }

    private static void addApproval(List<AgentInlineBlockLayout.Line> lines, TerminalAgentModels.Approval approval, int width) {
        lines.add(line(width, seg("? ", AgentInlineBlockLayout.Role.WARN),
            seg(AgentInlineBlockLayout.oneLine(AgentDesignWidgets.approvalMessage(approval)), AgentInlineBlockLayout.Role.TEXT)));
        if (approval.commands() != null) {
            for (TerminalAgentModels.PlannedCommand command : approval.commands()) {
                if (command != null && command.command() != null) {
                    lines.add(line(width, seg("$ ", AgentInlineBlockLayout.Role.WARN),
                        seg(AgentInlineBlockLayout.oneLine(command.command()), AgentInlineBlockLayout.Role.TITLE)));
                }
            }
        }
        List<AgentInlineBlockLayout.Segment> keys = new ArrayList<>();
        keys.add(seg("  1 ", AgentInlineBlockLayout.Role.KEY));
        keys.add(seg(I18n.get("ai.agent.approval.once"), AgentInlineBlockLayout.Role.TEXT));
        if (approval.allowAlways()) {
            keys.add(seg(" · 2 ", AgentInlineBlockLayout.Role.KEY));
            keys.add(seg(I18n.get("ai.agent.approval.always"), AgentInlineBlockLayout.Role.TEXT));
        }
        keys.add(seg(approval.allowAlways() ? " · 3 " : " · 2 ", AgentInlineBlockLayout.Role.KEY));
        keys.add(seg(I18n.get("dialog.cancel"), AgentInlineBlockLayout.Role.TEXT));
        lines.add(line(width, keys.toArray(AgentInlineBlockLayout.Segment[]::new)));
    }

    /** The exit code of the last finished command, or {@code null}. */
    static Integer lastExitCode(AgentInlineBlockLayout.BlockState state) {
        Integer exit = null;
        for (TerminalAgentModels.AgentActivity step : state.rows.values()) {
            if (step.type() == TerminalAgentModels.AgentActivityType.ACTION
                && step.status() != TerminalAgentModels.AgentActivityStatus.RUNNING) {
                Integer code = AgentStepDetails.exitCode(step);
                if (code != null) {
                    exit = code;
                }
            }
        }
        return exit;
    }

    private static AgentInlineBlockLayout.Line line(int width, AgentInlineBlockLayout.Segment... segments) {
        List<AgentInlineBlockLayout.Segment> all = new ArrayList<>(segments.length + 1);
        all.add(seg(INDENT, AgentInlineBlockLayout.Role.TEXT));
        for (AgentInlineBlockLayout.Segment segment : segments) {
            if (segment != null && !segment.text().isEmpty()) {
                all.add(segment);
            }
        }
        return AgentInlineBlockLayout.truncate(all, width);
    }

    private static AgentInlineBlockLayout.Segment seg(String text, AgentInlineBlockLayout.Role role) {
        return new AgentInlineBlockLayout.Segment(text, role);
    }
}
