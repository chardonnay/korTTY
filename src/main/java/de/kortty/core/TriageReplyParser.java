package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import de.kortty.model.TerminalAgentModels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the reply of the complexity check: whether the request looks complex, why, and two or three solution
 * options. Every reply that is not a usable object fails with a {@link JsonSyntaxException}, so the caller can run
 * the request as asked.
 */
final class TriageReplyParser {

    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 3;

    private static final Gson GSON = new Gson();

    private TriageReplyParser() {
    }

    /** The proposal the reply describes, without connection fields. */
    static TerminalAgentModels.TriageProposal parse(String rawContent) {
        Reply reply = GSON.fromJson(TerminalAgentService.extractJsonObjectContent(rawContent), Reply.class);
        if (reply == null) {
            throw new JsonSyntaxException("Triage reply missing");
        }
        boolean complex = complexity(reply.complexity());
        List<TerminalAgentModels.PlanOption> options = new ArrayList<>();
        for (Option item : safe(reply.options())) {
            if (options.size() == MAX_OPTIONS) {
                break;
            }
            if (item == null || blank(item.title()) || blank(item.summary())) {
                continue;
            }
            options.add(new TerminalAgentModels.PlanOption(
                "triage-option-" + (options.size() + 1),
                item.title().strip(),
                item.summary().strip(),
                item.feasibility() == null ? "" : item.feasibility().strip(),
                strings(item.risks()),
                strings(item.prerequisites()),
                strings(item.steps()),
                strings(item.alternatives())));
        }
        if (options.size() < MIN_OPTIONS) {
            throw new JsonSyntaxException("Triage needs at least " + MIN_OPTIONS + " complete options");
        }
        int requested = reply.recommended() == null ? 0 : reply.recommended();
        int recommended = Math.max(0, Math.min(options.size() - 1, requested));
        return new TerminalAgentModels.TriageProposal(
            complex,
            strings(reply.reasons()),
            recommended,
            options,
            null,
            null,
            null);
    }

    private static boolean complexity(String value) {
        if (value == null) {
            throw new JsonSyntaxException("Triage complexity missing");
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "complex" -> true;
            case "simple" -> false;
            default -> throw new JsonSyntaxException("Triage complexity must be simple or complex");
        };
    }

    private static List<String> strings(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : safe(values)) {
            if (!blank(value)) {
                result.add(value.strip());
            }
        }
        return result;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** The reply as the model writes it; every field may be missing. */
    private record Reply(String complexity, List<String> reasons, Integer recommended, List<Option> options) {
    }

    private record Option(
        String title,
        String summary,
        String feasibility,
        List<String> risks,
        List<String> prerequisites,
        List<String> steps,
        List<String> alternatives) {
    }
}
