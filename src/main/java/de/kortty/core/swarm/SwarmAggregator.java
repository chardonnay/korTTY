package de.kortty.core.swarm;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiTokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Synthesizes the per-server agent answers into one bundled Markdown answer (preferably a comparison
 * table). Falls back to a deterministic local table if the LLM call fails, so the swarm always
 * yields a result.
 */
public final class SwarmAggregator {

    private static final Logger logger = LoggerFactory.getLogger(SwarmAggregator.class);
    private static final int PER_SERVER_ANSWER_CAP = 2_000;
    private static final int PER_SERVER_TRANSCRIPT_CAP = 800;

    public SwarmModels.SwarmAggregationResult aggregate(
        SwarmModels.SwarmAggregationRequest request,
        AiPromptService aiService) {

        List<SwarmModels.SwarmAgentStatus> results = request != null ? request.perAgentResults() : List.of();
        String query = request != null ? request.userQuery() : "";
        if (results == null || results.isEmpty()) {
            return new SwarmModels.SwarmAggregationResult("", SwarmModels.TokenTotals.zero(), null);
        }
        if (aiService == null) {
            return localFallback(query, results, "AI service unavailable");
        }
        try {
            AiExecutionResult result = aiService.executePrompt(buildSystemPrompt(), buildUserPrompt(query, results));
            String content = result != null ? result.content() : null;
            if (content == null || content.isBlank()) {
                return localFallback(query, results, "Empty aggregation response");
            }
            return new SwarmModels.SwarmAggregationResult(content.trim(), toTotals(result.usage()), null);
        } catch (Exception e) {
            logger.warn("Swarm aggregation failed, using local fallback", e);
            return localFallback(query, results, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static SwarmModels.TokenTotals toTotals(AiTokenUsage usage) {
        if (usage == null) {
            return SwarmModels.TokenTotals.zero();
        }
        return new SwarmModels.TokenTotals(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private static String buildSystemPrompt() {
        return String.join(" ",
            "Du bist ein Aggregator.",
            "Du erhältst Pro-Server-Antworten desselben KI-Agenten, der dieselbe Anfrage über mehrere Server ausgeführt hat.",
            "Erzeuge EINE knappe Antwort in Markdown, bevorzugt eine Vergleichstabelle mit genau einer Zeile pro Server.",
            "Nenne Abweichungen, fehlende Daten und Fehler explizit.",
            "Erfinde keine Daten; nutze ausschließlich die gelieferten Pro-Server-Antworten.",
            "Antworte in der Sprache der Nutzeranfrage.");
    }

    private static String buildUserPrompt(String query, List<SwarmModels.SwarmAgentStatus> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Nutzeranfrage:\n").append(query == null ? "" : query.trim()).append("\n\n");
        sb.append("Pro-Server-Ergebnisse:\n");
        for (SwarmModels.SwarmAgentStatus status : results) {
            sb.append("\n### ").append(safe(status.displayName())).append('\n');
            sb.append("Status: ").append(status.state()).append('\n');
            String answer = status.finalAnswer();
            if (answer != null && !answer.isBlank()) {
                sb.append("Antwort: ").append(cap(answer.trim(), PER_SERVER_ANSWER_CAP)).append('\n');
            } else if (status.errorMessage() != null && !status.errorMessage().isBlank()) {
                sb.append("Fehler: ").append(cap(status.errorMessage().trim(), PER_SERVER_ANSWER_CAP)).append('\n');
            } else if (status.transcriptSummary() != null && !status.transcriptSummary().isBlank()) {
                sb.append("Ausgabe (Auszug): ").append(cap(status.transcriptSummary().trim(), PER_SERVER_TRANSCRIPT_CAP)).append('\n');
            } else {
                sb.append("(keine Antwort)\n");
            }
        }
        sb.append("\nFasse diese Ergebnisse jetzt in einer Markdown-Tabelle zusammen.");
        return sb.toString();
    }

    private static SwarmModels.SwarmAggregationResult localFallback(
        String query,
        List<SwarmModels.SwarmAgentStatus> results,
        String error) {
        StringBuilder sb = new StringBuilder();
        if (query != null && !query.isBlank()) {
            sb.append("**").append(escapeCell(query.trim())).append("**\n\n");
        }
        sb.append("| Server | Status | Antwort |\n");
        sb.append("|---|---|---|\n");
        for (SwarmModels.SwarmAgentStatus status : results) {
            String answer = status.finalAnswer();
            if (answer == null || answer.isBlank()) {
                answer = status.errorMessage() != null && !status.errorMessage().isBlank()
                    ? status.errorMessage()
                    : (status.transcriptSummary() != null ? status.transcriptSummary() : "");
            }
            sb.append("| ").append(escapeCell(safe(status.displayName())))
                .append(" | ").append(escapeCell(String.valueOf(status.state())))
                .append(" | ").append(escapeCell(cap(answer == null ? "" : answer.trim(), PER_SERVER_ANSWER_CAP)))
                .append(" |\n");
        }
        return new SwarmModels.SwarmAggregationResult(sb.toString(), SwarmModels.TokenTotals.zero(), error);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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
