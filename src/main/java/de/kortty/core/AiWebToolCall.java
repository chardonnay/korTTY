package de.kortty.core;

import java.util.List;

/**
 * One internet tool call the model made while answering (a web search, a page extraction or an
 * LM Studio MCP tool), kept so the chat and the agent can show that and how the web was used.
 *
 * <p>Display-only metadata: it never carries the page content itself, only what was asked
 * ({@code input}: the query or URL), whether it worked, and which sources came back.
 */
public record AiWebToolCall(
    Kind kind,
    String tool,
    String input,
    boolean success,
    String message,
    List<Source> sources,
    int contentChars,
    boolean truncated) {

    public enum Kind {
        SEARCH,
        EXTRACT,
        OTHER
    }

    public record Source(String title, String url) {
        public Source {
            title = title != null ? title.trim() : "";
            url = url != null ? url.trim() : "";
        }
    }

    public AiWebToolCall {
        kind = kind != null ? kind : Kind.OTHER;
        tool = tool != null ? tool.trim() : "";
        input = input != null ? input.trim() : "";
        message = message != null && !message.isBlank() ? message.trim() : null;
        sources = sources != null ? List.copyOf(sources) : List.of();
        contentChars = Math.max(0, contentChars);
    }

    public static AiWebToolCall failed(Kind kind, String tool, String input, String message) {
        return new AiWebToolCall(kind, tool, input, false, message, List.of(), 0, false);
    }

    /**
     * Maps a tool name to a kind. Covers KorTTY's own tools and the MCP tools KorTTY allows in
     * LM Studio integrations; unknown tools stay {@link Kind#OTHER}.
     */
    public static Kind kindOfTool(String toolName) {
        String name = toolName != null ? toolName.toLowerCase(java.util.Locale.ROOT) : "";
        if (name.contains("extract") || name.contains("scrape") || name.contains("url_read")
            || name.contains("fetch") || name.contains("crawl")) {
            return Kind.EXTRACT;
        }
        if (name.contains("search") || name.contains("discover")) {
            return Kind.SEARCH;
        }
        return Kind.OTHER;
    }
}
