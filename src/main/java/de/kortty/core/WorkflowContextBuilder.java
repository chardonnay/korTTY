package de.kortty.core;

import de.kortty.model.TerminalAgentModels;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, UI-free compaction of a finished terminal-agent run into a token-budgeted context string
 * that the AI uses to regenerate a reproducible script.
 *
 * <p>Reproduction-relevant activities (the executed ACTIONs, then ERRORs) are kept; THINKING / chat
 * noise is dropped. Long command output is truncated in the middle so both the command and its
 * final status/error survive. A global character budget caps the total size.
 */
public final class WorkflowContextBuilder {

    public static final int DEFAULT_DETAIL_HEAD = 1200;
    public static final int DEFAULT_DETAIL_TAIL = 800;
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 48_000;

    private WorkflowContextBuilder() {
    }

    public static WorkflowScriptSupport.WorkflowContext build(
        TerminalAgentActivityExportService.Run run, int maxContextChars) {

        List<TerminalAgentActivityExportService.Activity> selected = selectReproductionActivities(run);
        int total = selected.size();
        int budget = maxContextChars > 0 ? maxContextChars : DEFAULT_MAX_CONTEXT_CHARS;

        StringBuilder body = new StringBuilder();
        int includedInFull = 0;
        boolean truncated = false;
        // Full-detail entries stay a contiguous prefix: once one action no longer fits in full,
        // every later action is rendered as a summary (or dropped), never full again.
        boolean fullAllowed = true;

        for (TerminalAgentActivityExportService.Activity activity : selected) {
            if (fullAllowed) {
                String full = renderActivity(activity, true);
                if (body.length() + full.length() <= budget) {
                    body.append(full).append("\n");
                    includedInFull++;
                    continue;
                }
                fullAllowed = false;
                truncated = true;
            }
            // Summary-only mode. Always keep at least one entry (the very first), even if it would
            // exceed the budget on its own, so the AI never receives an empty reproduction context.
            String summary = renderActivity(activity, false);
            if (body.length() == 0 || body.length() + summary.length() <= budget) {
                body.append(summary).append("\n");
            } else {
                truncated = true;
                break;
            }
        }

        String header = "Executed actions: included " + includedInFull + " of " + total
            + (truncated ? " in full (the rest are summarized or omitted due to length).\n\n" : ".\n\n");
        String markdown = (header + body.toString().strip()).strip();
        return new WorkflowScriptSupport.WorkflowContext(markdown, truncated, includedInFull, total);
    }

    /** ACTION activities first (the real commands), then ERROR activities; THINKING/chat dropped. */
    static List<TerminalAgentActivityExportService.Activity> selectReproductionActivities(
        TerminalAgentActivityExportService.Run run) {

        List<TerminalAgentActivityExportService.Activity> result = new ArrayList<>();
        if (run == null || run.activities() == null) {
            return result;
        }
        for (TerminalAgentActivityExportService.Activity a : run.activities()) {
            if (a.type() == TerminalAgentModels.AgentActivityType.ACTION) {
                result.add(a);
            }
        }
        for (TerminalAgentActivityExportService.Activity a : run.activities()) {
            if (a.type() == TerminalAgentModels.AgentActivityType.ERROR) {
                result.add(a);
            }
        }
        return result;
    }

    /** Keeps the head and tail of a long string, replacing the middle with an omission marker. */
    static String truncateMiddle(String text, int head, int tail) {
        if (text == null) {
            return "";
        }
        int safeHead = Math.max(0, head);
        int safeTail = Math.max(0, tail);
        if (text.length() <= safeHead + safeTail) {
            return text;
        }
        int omitted = text.length() - safeHead - safeTail;
        return text.substring(0, safeHead)
            + "\n… [" + omitted + " characters omitted] …\n"
            + text.substring(text.length() - safeTail);
    }

    private static String renderActivity(TerminalAgentActivityExportService.Activity activity, boolean full) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(activity.type().name()).append(" — ").append(nz(activity.title()))
            .append(" [").append(activity.status().name()).append("]\n");
        if (notBlank(activity.summary())) {
            sb.append(activity.summary().strip()).append("\n");
        }
        if (full && notBlank(activity.detail())) {
            sb.append("```\n")
                .append(truncateMiddle(activity.detail().strip(), DEFAULT_DETAIL_HEAD, DEFAULT_DETAIL_TAIL))
                .append("\n```\n");
        }
        return sb.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
