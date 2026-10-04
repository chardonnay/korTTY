package de.kortty.shellintegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The shell snippets korTTY ships for shell integration, from {@code src/main/resources/shell-integration}:
 * what <i>Set Up Shell Integration…</i> shows and copies, and what the guide page prints.
 * {@code ShellIntegrationSnippetsTest} pins that the window and the guide show the same text.
 *
 * <p>FX-free, so the text the window offers is testable without a JavaFX toolkit.</p>
 */
public enum ShellIntegrationSnippet {

    BASH("bash", "kortty.bash"),
    ZSH("zsh", "kortty.zsh"),
    FISH("fish", "kortty.fish");

    private static final String RESOURCE_DIRECTORY = "/shell-integration/";

    private static final String INSTRUCTIONS_KEY_PREFIX = "terminal.shellIntegration.setup.";

    private final String shellName;

    private final String fileName;

    ShellIntegrationSnippet(String shellName, String fileName) {
        this.shellName = shellName;
        this.fileName = fileName;
    }

    /** The shell's name as it is typed, which is also the tab label: {@code bash}, {@code zsh} or {@code fish}. */
    public @NotNull String shellName() {
        return shellName;
    }

    /** The classpath resource the snippet is read from. */
    public @NotNull String resource() {
        return RESOURCE_DIRECTORY + fileName;
    }

    /** The i18n key of the text that says where the snippet goes and which versions of the shell it needs. */
    public @NotNull String instructionsKey() {
        return INSTRUCTIONS_KEY_PREFIX + shellName;
    }

    /**
     * The snippet as shipped, with LF line endings and exactly one line break at its end.
     *
     * @throws IllegalStateException when the resource is missing from the build
     * @throws UncheckedIOException when it cannot be read
     */
    public @NotNull String text() {
        try (InputStream in = ShellIntegrationSnippet.class.getResourceAsStream(resource())) {
            if (in == null) {
                throw new IllegalStateException("Missing shell-integration snippet " + resource());
            }
            return normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read shell-integration snippet " + resource(), e);
        }
    }

    /**
     * The snippet for {@code shellName} ({@code "bash"}, {@code "/bin/zsh"}, {@code "-fish"},
     * {@code "C:\Git\bin\bash.exe"}: the last path element counts, without a login shell's leading
     * dash or an {@code .exe}, case ignored), or null for any other shell.
     */
    public static @Nullable ShellIntegrationSnippet forShell(@Nullable String shellName) {
        if (shellName == null || shellName.isBlank()) {
            return null;
        }
        String name = shellName.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).toLowerCase(Locale.ROOT);
        if (name.startsWith("-")) {
            name = name.substring(1);
        }
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - ".exe".length());
        }
        for (ShellIntegrationSnippet snippet : values()) {
            if (snippet.shellName.equals(name)) {
                return snippet;
            }
        }
        return null;
    }

    /**
     * CRLF and a lone CR become LF, since bash, zsh and fish would read a carriage return as part of
     * every command; trailing blank lines become exactly one line break, so text pasted at the end
     * of a startup file ends its last line.
     */
    static @NotNull String normalize(@NotNull String raw) {
        String lf = raw.replace("\r\n", "\n").replace('\r', '\n');
        return lf.stripTrailing() + "\n";
    }
}
