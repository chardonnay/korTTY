package de.kortty.core;

/**
 * Backwards-compatible facade for snippet code formatting.
 * New UI code should use {@link CodeFormatterService} directly.
 */
public final class SnippetCodeFormatter {

    public record FormatterInfo(
        String command,
        String[] stdinArgs,
        String[] fileArgs,
        String installHint,
        String fileExtension,
        boolean available,
        String providerDisplayName) {

        public boolean isExternal() {
            return command != null && !command.isBlank();
        }

        public String displayName() {
            return providerDisplayName != null && !providerDisplayName.isBlank()
                ? providerDisplayName
                : (isExternal() ? command : "integrated");
        }
    }

    public static final class FormatterException extends Exception {
        public FormatterException(String message) {
            super(message);
        }

        public FormatterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static String format(String text, String language) {
        return CodeFormatterService.format(text, language);
    }

    public static String formatOrThrow(String text, String language) throws FormatterException {
        try {
            return CodeFormatterService.formatOrThrow(text, language);
        } catch (CodeFormatterService.FormatterException e) {
            throw new FormatterException(e.getMessage(), e);
        }
    }

    public static boolean isSupported(String language) {
        return CodeFormatterService.isSupported(language);
    }

    public static FormatterInfo getFormatterInfo(String language) {
        CodeFormatterService.FormatterInfo info = CodeFormatterService.getFormatterInfo(language);
        if (info == null) {
            return null;
        }
        return new FormatterInfo(
            info.commandName(),
            info.executionMode() == CodeFormatterService.ExecutionMode.STDIN ? info.commandLine().toArray(String[]::new) : null,
            info.executionMode() == CodeFormatterService.ExecutionMode.FILE_APPEND ? info.commandLine().toArray(String[]::new) : null,
            info.unavailableReason() != null ? info.unavailableReason() : info.installHint(),
            info.fileExtension(),
            info.available(),
            info.displayName());
    }

    public static boolean isFormatterAvailable(FormatterInfo info) {
        return info != null && info.available();
    }

    private SnippetCodeFormatter() {}
}
