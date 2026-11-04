package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.ui.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns how a KI-Agent step is written in every export: the headline, the command and output body, and the
 * Markdown and plain-text step blocks. The chat exports (Markdown, plain text, PDF) and the session
 * journal (Markdown, PDF appendix) call it, so a step reads the same everywhere. Steps are shown as the
 * chat stores them: masked and already capped.
 */
public final class AgentChatTranscriptFormatter {

    private AgentChatTranscriptFormatter() {
    }

    /** Markdown: a heading per message, its text, then its steps; command output in fenced blocks. */
    public static String toMarkdown(List<SavedAiChatMessage> messages) {
        StringBuilder out = new StringBuilder(1024);
        for (SavedAiChatMessage message : safe(messages)) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("## ").append(roleLabel(message)).append("\n\n");
            out.append(content(message));
            String steps = markdownSteps(message.getAgentSteps());
            if (!steps.isEmpty()) {
                out.append("\n\n").append(steps);
            }
        }
        return out.toString();
    }

    /**
     * The steps as a Markdown block: a {@code ###} heading with the step count, then a list item per step with
     * its command and output in a fenced block indented under the item. Empty for a message without steps;
     * otherwise the block ends with a newline.
     */
    public static String markdownSteps(List<SavedAgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("### ").append(I18n.get("ai.result.agentRun.steps", steps.size())).append("\n\n");
        for (SavedAgentStep step : steps) {
            if (step == null) {
                continue;
            }
            out.append("- ").append(stepHeadline(step)).append('\n');
            List<String> body = stepBody(step);
            if (!body.isEmpty()) {
                String fence = fenceFor(String.join("\n", body));
                out.append('\n').append("  ").append(fence).append("text\n");
                for (String line : body) {
                    out.append("  ").append(line).append('\n');
                }
                out.append("  ").append(fence).append('\n');
            }
            if (step.isTruncated()) {
                out.append('\n').append("  _").append(I18n.get("ai.result.agentRun.truncated")).append("_\n");
            }
        }
        return out.toString();
    }

    /**
     * The steps as an indented plain-text block: a label line, then each step's headline with its command and
     * output indented under it. Empty for a message without steps; no trailing newline.
     */
    public static String plainSteps(List<SavedAgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("  " + I18n.get("ai.result.agentRun.steps", steps.size()));
        for (SavedAgentStep step : steps) {
            if (step == null) {
                continue;
            }
            lines.add("  - " + stepHeadline(step));
            for (String line : stepBody(step)) {
                lines.add("      " + line);
            }
            if (step.isTruncated()) {
                lines.add("      " + I18n.get("ai.result.agentRun.truncated"));
            }
        }
        return String.join("\n", lines);
    }

    /** {@code [status] title: summary · exit N}, the part every format shares. */
    public static String stepHeadline(SavedAgentStep step) {
        StringBuilder headline = new StringBuilder();
        if (step.getStatus() != null && !step.getStatus().isBlank()) {
            headline.append('[').append(step.getStatus().strip()).append("] ");
        }
        headline.append(step.getTitle() != null ? step.getTitle().strip() : "");
        if (step.getSummary() != null && !step.getSummary().isBlank()) {
            headline.append(": ").append(step.getSummary().strip());
        }
        if (step.getExitCode() != null) {
            headline.append(" · ").append(I18n.get("ai.result.agentRun.exit", step.getExitCode()));
        }
        return headline.toString();
    }

    /** The command, then the output, one line each; empty for a step that ran no command. */
    public static List<String> stepBody(SavedAgentStep step) {
        List<String> lines = new ArrayList<>();
        if (step.getCommand() != null && !step.getCommand().isBlank()) {
            lines.add("$ " + step.getCommand());
        }
        if (step.getOutput() != null && !step.getOutput().isEmpty()) {
            lines.addAll(List.of(step.getOutput().split("\n", -1)));
        }
        return lines;
    }

    /** A code fence longer than any backtick run in the body, so the body cannot close it. */
    private static String fenceFor(String body) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < body.length(); i++) {
            run = body.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    private static String content(SavedAiChatMessage message) {
        return message.getContent() != null ? message.getContent().trim() : "";
    }

    /** The label the journal shows for a message: the user, the profile that answered, or the generic assistant. */
    private static String roleLabel(SavedAiChatMessage message) {
        if (SavedAiChatMessage.ROLE_USER.equals(message.getRole())) {
            return I18n.get("ai.result.user");
        }
        String profileName = message.getAiProfileName();
        return profileName == null || profileName.isBlank() ? I18n.get("ai.result.assistant") : profileName;
    }

    private static List<SavedAiChatMessage> safe(List<SavedAiChatMessage> messages) {
        return messages != null ? messages.stream().filter(Objects::nonNull).toList() : List.of();
    }
}
