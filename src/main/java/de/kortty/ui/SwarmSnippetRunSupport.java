package de.kortty.ui;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetVariableManager;
import de.kortty.core.swarm.SwarmSnippetExecutor;
import de.kortty.model.Snippet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic behind the "run script on swarm targets" feature: resolves snippet variables, wraps
 * the script into the base64 one-liner (reusing {@link SnippetOneLiner}), parses the parameter
 * lines and renders the per-server outcomes as a markdown table for the swarm chat. All
 * user-visible strings are passed in already localized — the class itself is I18n-free and
 * unit-testable without a JavaFX toolkit.
 */
public class SwarmSnippetRunSupport {

    public static final int DEFAULT_OUTPUT_CAP = 4_000;

    private final SnippetManager snippetManager;
    private final SnippetVariableManager variableManager;

    public SwarmSnippetRunSupport(SnippetManager snippetManager, SnippetVariableManager variableManager) {
        this.snippetManager = snippetManager;
        this.variableManager = variableManager;
    }

    /** The ready-to-run command plus display metadata for chat/confirmation texts. */
    public record PreparedRun(String command, String snippetName, List<String> arguments) {
    }

    /** Localized labels for non-completed outcomes in the result table. */
    public record OutcomeLabels(
        String cancelled,
        String timedOut,
        String notConnected,
        String unsupportedShell,
        String errorFormat) {
    }

    /** Carries an i18n key + args so the dialog can localize the blocking reason. */
    public static class SnippetRunBlockedException extends Exception {
        private final String messageKey;
        private final Object[] args;

        public SnippetRunBlockedException(String messageKey, Object... args) {
            super(messageKey);
            this.messageKey = messageKey;
            this.args = args != null ? args : new Object[0];
        }

        public String messageKey() {
            return messageKey;
        }

        public Object[] args() {
            return args;
        }
    }

    /**
     * Resolves built-in and custom snippet variables, then wraps the script into the base64
     * one-liner with the given positional arguments. Mirrors the job scheduler's
     * {@code resolveSnippetText}, but reports blocking reasons as i18n keys.
     */
    public PreparedRun prepare(Snippet snippet, List<String> arguments) throws SnippetRunBlockedException {
        if (snippet == null || snippet.getContent() == null || snippet.getContent().isBlank()) {
            throw new SnippetRunBlockedException("snippets.oneliner.empty");
        }
        SnippetManager.ResolvedSnippet resolved = snippetManager.resolveBuiltInVariables(snippet.getContent());
        String text = resolved.text();
        if (text == null || text.isBlank()) {
            throw new SnippetRunBlockedException("snippets.oneliner.empty");
        }
        List<String> customVariables = snippetManager.findCustomVariables(text);
        if (!customVariables.isEmpty()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String variable : customVariables) {
                String value = variableManager != null ? variableManager.getValue(variable) : null;
                if (value == null) {
                    throw new SnippetRunBlockedException("ai.swarm.script.error.variable", "${" + variable + "}");
                }
                values.put(variable, value);
            }
            text = snippetManager.replaceCustomVariables(text, values);
        }
        List<String> effectiveArguments = arguments != null ? List.copyOf(arguments) : List.of();
        SnippetOneLiner.OneLinerResult oneLiner =
            SnippetOneLiner.toEmbedded(text, snippet.getLanguage(), effectiveArguments);
        if (!oneLiner.isOk()) {
            throw new SnippetRunBlockedException(oneLiner.errorKey(), oneLiner.errorArgs());
        }
        String name = snippet.getName() != null && !snippet.getName().isBlank()
            ? snippet.getName().trim()
            : snippet.getId();
        return new PreparedRun(oneLiner.line(), name, effectiveArguments);
    }

    /** One parameter per line; trims, drops blank lines, preserves order and inner spacing. */
    public static List<String> parseArgumentLines(String text) {
        List<String> arguments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return arguments;
        }
        for (String line : text.split("\\R", -1)) {
            String trimmed = line != null ? line.trim() : "";
            if (!trimmed.isEmpty()) {
                arguments.add(trimmed);
            }
        }
        return arguments;
    }

    /** Markdown for the swarm chat: heading + one table row per target, in run order. */
    public static String buildResultMarkdown(
        String heading,
        List<String> headers,
        List<SwarmSnippetExecutor.TargetOutcome> outcomes,
        OutcomeLabels labels,
        int outputCap) {

        if (headers == null || headers.size() != 3) {
            throw new IllegalArgumentException("buildResultMarkdown expects exactly 3 headers (server, exit, output)");
        }
        StringBuilder sb = new StringBuilder();
        if (heading != null && !heading.isBlank()) {
            sb.append("**").append(escapeCell(heading.trim())).append("**\n\n");
        }
        sb.append('|');
        for (String header : headers) {
            sb.append(' ').append(escapeCell(header)).append(" |");
        }
        sb.append('\n').append("|");
        sb.append("---|".repeat(headers.size()));
        sb.append('\n');
        for (SwarmSnippetExecutor.TargetOutcome outcome : outcomes) {
            sb.append("| ").append(escapeCell(outcome.displayName()))
                .append(" | ").append(escapeCell(exitCell(outcome, labels)))
                .append(" | ").append(escapeCell(outputCell(outcome, labels, outputCap)))
                .append(" |\n");
        }
        return sb.toString();
    }

    private static String exitCell(SwarmSnippetExecutor.TargetOutcome outcome, OutcomeLabels labels) {
        return switch (outcome.kind()) {
            case COMPLETED -> String.valueOf(outcome.exitCode());
            case CANCELLED -> labels.cancelled();
            case TIMED_OUT -> labels.timedOut();
            default -> "—";
        };
    }

    private static String outputCell(
        SwarmSnippetExecutor.TargetOutcome outcome, OutcomeLabels labels, int outputCap) {
        return switch (outcome.kind()) {
            case COMPLETED, TIMED_OUT, CANCELLED -> cap(outcome.output() != null ? outcome.output().trim() : "", outputCap);
            case NOT_CONNECTED -> labels.notConnected();
            case UNSUPPORTED_SHELL -> labels.unsupportedShell();
            // plain {0}-replace like LanguageManager — MessageFormat would eat apostrophes (fr)
            case ERROR -> (labels.errorFormat() != null ? labels.errorFormat() : "{0}")
                .replace("{0}", outcome.errorDetail() != null ? outcome.errorDetail() : "");
        };
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }
}
