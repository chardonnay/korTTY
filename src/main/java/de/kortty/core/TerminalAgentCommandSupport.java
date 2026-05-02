package de.kortty.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser and validator for terminal-agent shortcut commands.
 */
public final class TerminalAgentCommandSupport {

    public static final String DEFAULT_COMMAND_NAME = "agent";
    private static final Pattern COMMAND_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    private TerminalAgentCommandSupport() {
    }

    public enum InvocationKind {
        EXECUTE,
        ASK,
        PLAN
    }

    public record Invocation(
        InvocationKind kind,
        String profileName,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands,
        String userPrompt) {
    }

    public static String normalizeCommandName(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? DEFAULT_COMMAND_NAME : trimmed;
    }

    public static String getAskCommandName(String commandName) {
        return normalizeCommandName(commandName) + "-ask";
    }

    public static String getPlanCommandName(String commandName) {
        return normalizeCommandName(commandName) + "-plan";
    }

    public static String validateCommandName(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.isEmpty() || COMMAND_NAME_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return "Use a single command name that starts with a letter and contains only letters, numbers, '-' or '_'.";
    }

    public static Invocation parseShortcut(String rawCommand, String configuredCommandName) {
        return parseShortcut(rawCommand, configuredCommandName, false);
    }

    public static Invocation parseShortcut(
        String rawCommand,
        String configuredCommandName,
        boolean caseInsensitiveCommandName) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return null;
        }
        String commandName = normalizeCommandName(configuredCommandName);
        int patternFlags = caseInsensitiveCommandName ? Pattern.CASE_INSENSITIVE : 0;
        Matcher askMatcher = buildAskPattern(commandName, patternFlags).matcher(rawCommand.trim());
        if (askMatcher.matches()) {
            return new Invocation(InvocationKind.ASK, null, false, false, askMatcher.group(1).trim());
        }

        Matcher planMatcher = buildPlanPattern(commandName, patternFlags).matcher(rawCommand.trim());
        if (planMatcher.matches()) {
            InlineOptions options = parseInlineOptions(planMatcher.group(1));
            return new Invocation(
                InvocationKind.PLAN,
                options.profileName(),
                options.askConfirmationBeforeEveryCommand(),
                options.autoApproveRootCommands(),
                planMatcher.group(2).trim());
        }

        Matcher planFlagMatcher = buildPlanFlagPattern(commandName, patternFlags).matcher(rawCommand.trim());
        if (planFlagMatcher.matches()) {
            InlineOptions options = parseInlineOptions(combineInlineOptions(planFlagMatcher.group(1), planFlagMatcher.group(2)));
            return new Invocation(
                InvocationKind.PLAN,
                options.profileName(),
                options.askConfirmationBeforeEveryCommand(),
                options.autoApproveRootCommands(),
                planFlagMatcher.group(3).trim());
        }

        Matcher executeMatcher = buildExecutePattern(commandName, patternFlags).matcher(rawCommand.trim());
        if (executeMatcher.matches()) {
            InlineOptions options = parseInlineOptions(executeMatcher.group(1));
            return new Invocation(
                InvocationKind.EXECUTE,
                options.profileName(),
                options.askConfirmationBeforeEveryCommand(),
                options.autoApproveRootCommands(),
                executeMatcher.group(2).trim());
        }
        return null;
    }

    public static String buildUsageText(String commandName) {
        String normalizedName = normalizeCommandName(commandName);
        return "Use `"
            + normalizedName
            + " <prompt>`, `"
            + normalizedName
            + ": <prompt>`, `"
            + normalizedName
            + "(profile=name,root=true,ask=true) <prompt>`, `"
            + getAskCommandName(commandName)
            + " <question>`, `"
            + getAskCommandName(commandName)
            + ": <question>`, `"
            + getPlanCommandName(commandName)
            + " <prompt>` or `"
            + getPlanCommandName(commandName)
            + "(profile=name) <prompt>`, `"
            + normalizedName
            + " -plan <prompt>` or `"
            + normalizedName
            + " -plan(profile=name) <prompt>`.";
    }

    private static Pattern buildExecutePattern(String commandName, int patternFlags) {
        return Pattern.compile("^" + Pattern.quote(normalizeCommandName(commandName))
            + "(?:\\s*\\(([^)]*)\\))?(?:(?:\\s*:\\s*)|\\s+)(.+)$", patternFlags);
    }

    private static Pattern buildAskPattern(String commandName, int patternFlags) {
        return Pattern.compile("^" + Pattern.quote(getAskCommandName(commandName))
            + "(?:(?:\\s*:\\s*)|\\s+)(.+)$", patternFlags);
    }

    private static Pattern buildPlanPattern(String commandName, int patternFlags) {
        return Pattern.compile("^" + Pattern.quote(getPlanCommandName(commandName))
            + "(?:\\s*\\(([^)]*)\\))?(?:(?:\\s*:\\s*)|\\s+)(.+)$", patternFlags);
    }

    private static Pattern buildPlanFlagPattern(String commandName, int patternFlags) {
        return Pattern.compile("^" + Pattern.quote(normalizeCommandName(commandName))
            + "(?:\\s*\\(([^)]*)\\))?\\s+-plan(?:\\s*\\(([^)]*)\\))?(?:(?:\\s*:\\s*)|\\s+)(.+)$", patternFlags);
    }

    private static String combineInlineOptions(String first, String second) {
        String left = first != null ? first.trim() : "";
        String right = second != null ? second.trim() : "";
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "," + right;
    }

    private static InlineOptions parseInlineOptions(String rawOptions) {
        if (rawOptions == null || rawOptions.isBlank()) {
            return new InlineOptions(null, false, false);
        }
        String profileName = null;
        boolean ask = false;
        boolean root = false;
        for (String token : rawOptions.split(",")) {
            String[] parts = token.split("=", 2);
            String key = parts[0].trim().toLowerCase(Locale.ROOT);
            String value = parts.length > 1 ? parts[1].trim() : "";
            switch (key) {
                case "profile" -> profileName = value.isEmpty() ? null : value;
                case "ask" -> ask = isTruthy(value);
                case "root" -> root = isTruthy(value);
                default -> {
                }
            }
        }
        return new InlineOptions(profileName, ask, root);
    }

    private static boolean isTruthy(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1")
            || normalized.equals("true")
            || normalized.equals("yes")
            || normalized.equals("y")
            || normalized.equals("on");
    }

    private record InlineOptions(String profileName, boolean askConfirmationBeforeEveryCommand, boolean autoApproveRootCommands) {
    }
}
