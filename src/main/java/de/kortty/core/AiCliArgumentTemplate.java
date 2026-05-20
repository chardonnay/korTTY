package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses and expands CLI argument templates without invoking a shell.
 */
public final class AiCliArgumentTemplate {

    public static final String MODEL = "{model}";
    public static final String REASONING = "{reasoning}";
    public static final String PROMPT_FILE = "{promptFile}";
    public static final String SYSTEM_PROMPT_FILE = "{systemPromptFile}";
    public static final String USER_PROMPT_FILE = "{userPromptFile}";

    private final String source;
    private final List<String> arguments;

    private AiCliArgumentTemplate(String source, List<String> arguments) {
        this.source = source != null ? source : "";
        this.arguments = List.copyOf(arguments);
    }

    public static AiCliArgumentTemplate parse(String template) {
        String source = template != null ? template.trim() : "";
        if (source.isBlank()) {
            throw new IllegalArgumentException("AI CLI argument template must be configured.");
        }
        List<String> arguments = source.contains("\n") || source.contains("\r")
            ? parseLineArguments(source)
            : parseInlineArguments(source);
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("AI CLI argument template must contain at least one argument.");
        }
        return new AiCliArgumentTemplate(source, arguments);
    }

    public boolean containsPromptPlaceholder() {
        return source.contains(PROMPT_FILE)
            || source.contains(SYSTEM_PROMPT_FILE)
            || source.contains(USER_PROMPT_FILE);
    }

    public List<String> expand(Map<String, String> values) {
        List<String> expanded = new ArrayList<>();
        for (String argument : arguments) {
            String resolved = argument;
            if (values != null) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    resolved = resolved.replace(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
            }
            expanded.add(resolved);
        }
        return expanded;
    }

    public List<String> arguments() {
        return arguments;
    }

    private static List<String> parseLineArguments(String template) {
        return template.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
    }

    private static List<String> parseInlineArguments(String template) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaping = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote) {
                escaping = true;
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (escaping) {
            current.append('\\');
        }
        if (inSingleQuote || inDoubleQuote) {
            throw new IllegalArgumentException("AI CLI argument template contains an unterminated quote.");
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
