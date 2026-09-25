package de.kortty.ui;

import de.kortty.core.AiWebToolCall;
import de.kortty.model.SavedAiWebToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the internet tool calls behind an AI reply into the chat's persisted form and renders
 * the localized one-line summary and the detail text of the "Internet research" disclosure.
 */
final class AiWebActivitySupport {

    private static final int MAX_INPUT_CHARS = 160;
    private static final int MAX_MESSAGE_CHARS = 240;

    private AiWebActivitySupport() {
    }

    static List<SavedAiWebToolCall> toSaved(List<AiWebToolCall> calls) {
        List<SavedAiWebToolCall> saved = new ArrayList<>();
        if (calls == null) {
            return saved;
        }
        for (AiWebToolCall call : calls) {
            if (call == null) {
                continue;
            }
            SavedAiWebToolCall item = new SavedAiWebToolCall();
            item.setKind(call.kind().name());
            item.setTool(call.tool());
            item.setInput(call.input());
            item.setSuccess(call.success());
            item.setMessage(call.message());
            item.setContentChars(call.contentChars());
            item.setTruncated(call.truncated());
            List<SavedAiWebToolCall.Source> sources = new ArrayList<>();
            for (AiWebToolCall.Source source : call.sources()) {
                sources.add(new SavedAiWebToolCall.Source(source.title(), source.url()));
            }
            item.setSources(sources);
            saved.add(item);
        }
        return saved;
    }

    /** e.g. "Internet research · 2× search · 1× page read · 1× failed". */
    static String summary(List<SavedAiWebToolCall> calls) {
        int searches = 0;
        int extracts = 0;
        int others = 0;
        int failed = 0;
        for (SavedAiWebToolCall call : calls) {
            if (!call.isSuccess()) {
                failed++;
                continue;
            }
            switch (kindOf(call)) {
                case SEARCH -> searches++;
                case EXTRACT -> extracts++;
                case OTHER -> others++;
            }
        }
        StringBuilder text = new StringBuilder(I18n.get("ai.result.web.label"));
        appendCount(text, searches, "ai.result.web.count.search");
        appendCount(text, extracts, "ai.result.web.count.extract");
        appendCount(text, others, "ai.result.web.count.other");
        appendCount(text, failed, "ai.result.web.count.failed");
        return text.toString();
    }

    /** One block per call in call order, with the sources of successful calls indented below. */
    static String detail(List<SavedAiWebToolCall> calls) {
        StringBuilder text = new StringBuilder();
        for (SavedAiWebToolCall call : calls) {
            if (text.length() > 0) {
                text.append('\n');
            }
            String action = action(call);
            if (!call.isSuccess()) {
                text.append(I18n.get("ai.result.web.detail.failed", action,
                    shorten(call.getMessage() != null ? call.getMessage() : "", MAX_MESSAGE_CHARS)));
                continue;
            }
            AiWebToolCall.Kind kind = kindOf(call);
            if (kind == AiWebToolCall.Kind.SEARCH) {
                text.append(I18n.get("ai.result.web.detail.results", action, Integer.toString(call.getSources().size())));
            } else if (kind == AiWebToolCall.Kind.EXTRACT && call.getContentChars() > 0) {
                text.append(I18n.get(
                    call.isTruncated() ? "ai.result.web.detail.charsTruncated" : "ai.result.web.detail.chars",
                    action,
                    Integer.toString(call.getContentChars())));
            } else {
                text.append(action);
            }
            if (kind == AiWebToolCall.Kind.EXTRACT && call.getSources().size() == 1
                && sameUrl(call.getSources().get(0).getUrl(), call.getInput())) {
                continue;
            }
            for (SavedAiWebToolCall.Source source : call.getSources()) {
                String title = source.getTitle() != null ? source.getTitle().trim() : "";
                String url = source.getUrl() != null ? source.getUrl().trim() : "";
                if (title.isEmpty() && url.isEmpty()) {
                    continue;
                }
                text.append("\n  • ").append(title.isEmpty() ? url : title);
                if (!title.isEmpty() && !url.isEmpty()) {
                    text.append("\n    ").append(url);
                }
            }
        }
        return text.toString();
    }

    private static String action(SavedAiWebToolCall call) {
        String input = shorten(call.getInput() != null ? call.getInput() : "", MAX_INPUT_CHARS);
        return switch (kindOf(call)) {
            case SEARCH -> I18n.get("ai.result.web.action.search", input);
            case EXTRACT -> I18n.get("ai.result.web.action.extract", input);
            case OTHER -> I18n.get("ai.result.web.action.other",
                call.getTool() != null && !call.getTool().isBlank() ? call.getTool() : "?", input);
        };
    }

    private static AiWebToolCall.Kind kindOf(SavedAiWebToolCall call) {
        try {
            return call.getKind() != null ? AiWebToolCall.Kind.valueOf(call.getKind()) : AiWebToolCall.Kind.OTHER;
        } catch (IllegalArgumentException e) {
            return AiWebToolCall.Kind.OTHER;
        }
    }

    private static void appendCount(StringBuilder text, int count, String key) {
        if (count > 0) {
            text.append(" · ").append(I18n.get(key, Integer.toString(count)));
        }
    }

    private static boolean sameUrl(String left, String right) {
        String a = left != null ? left.trim().replaceAll("/+$", "") : "";
        String b = right != null ? right.trim().replaceAll("/+$", "") : "";
        return a.equalsIgnoreCase(b);
    }

    private static String shorten(String value, int max) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
