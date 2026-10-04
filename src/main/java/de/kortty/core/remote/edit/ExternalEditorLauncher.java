package de.kortty.core.remote.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Opens a local copy of a remote file in a text editor (D12), never through a shell.
 *
 * <p>With an editor command configured ({@code GlobalSettings.sftpExternalEditorCommand}, such as
 * {@code code --wait {file}}), that command runs directly: the template is split into arguments
 * the way a shell would split words (double and single quotes group, nothing is expanded), and
 * every {@code {file}} becomes the file's absolute path inside one argument; without a
 * {@code {file}} the path is added as the last argument.
 *
 * <p>Without one, the operating system opens the file <em>as text</em>: macOS {@code open -t}, on
 * Windows {@code Desktop.edit} (the "edit" verb, which opens scripts in Notepad and fails for files
 * that have no editor instead of running them), on Linux {@code xdg-open} only for files whose type
 * is {@code text/*}; anything else asks for an editor command first. The default application
 * ({@code Desktop.open}) and {@code cmd /c start} are never used: both may run a downloaded script,
 * and {@code cmd} expands {@code %VAR%} in file names.
 */
public final class ExternalEditorLauncher {

    /** The placeholder for the file in an editor command. */
    public static final String FILE_PLACEHOLDER = "{file}";

    /** The operating systems the default differs on. */
    public enum Os {
        MAC, WINDOWS, LINUX;

        /** The system korTTY runs on; anything not macOS or Windows counts as Linux. */
        public static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("mac") || name.contains("darwin")) {
                return MAC;
            }
            if (name.contains("win")) {
                return WINDOWS;
            }
            return LINUX;
        }
    }

    /** How the editor will be started. */
    public sealed interface Plan permits Command, DesktopEdit, NeedsCommand {
    }

    /** Run {@code argv} directly, without a shell. */
    public record Command(List<String> argv) implements Plan {
        public Command {
            argv = List.copyOf(argv);
        }
    }

    /** Windows: {@code Desktop.edit(file)}. */
    public record DesktopEdit(Path file) implements Plan {
    }

    /** No safe default for this file: the user has to name an editor command first. */
    public record NeedsCommand(String contentType) implements Plan {
    }

    /** Starts a plan; replaced in tests. */
    @FunctionalInterface
    public interface Starter {
        void start(Plan plan) throws IOException;
    }

    private ExternalEditorLauncher() {
    }

    /**
     * How to open {@code file} on {@code os}.
     *
     * @param template the configured editor command, or {@code null}/blank for the system default
     * @param contentType the probed type of the file ({@code Files.probeContentType}), may be {@code null}
     * @throws IllegalArgumentException when the template cannot be parsed or names no program
     */
    public static Plan plan(Os os, String template, Path file, String contentType) {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath();
        if (template != null && !template.isBlank()) {
            return new Command(commandFromTemplate(template, absolute));
        }
        return switch (os) {
            case MAC -> new Command(List.of("open", "-t", absolute.toString()));
            case WINDOWS -> new DesktopEdit(absolute);
            case LINUX -> contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/")
                ? new Command(List.of("xdg-open", absolute.toString()))
                : new NeedsCommand(contentType);
        };
    }

    /** {@link #plan(Os, String, Path, String)} for this system, probing the file's type. */
    public static Plan plan(String template, Path file) {
        String contentType = null;
        Os os = Os.current();
        if (os == Os.LINUX && (template == null || template.isBlank())) {
            try {
                contentType = Files.probeContentType(file);
            } catch (IOException | SecurityException e) {
                contentType = null;
            }
        }
        return plan(os, template, file, contentType);
    }

    /** The argument list of {@code template} with every {@code {file}} replaced by {@code file}. */
    public static List<String> commandFromTemplate(String template, Path file) {
        List<String> words = parseTemplate(template);
        if (words.isEmpty() || words.get(0).isBlank() || words.get(0).contains(FILE_PLACEHOLDER)) {
            throw new IllegalArgumentException("The editor command names no program");
        }
        String path = file.toString();
        List<String> argv = new ArrayList<>(words.size() + 1);
        boolean placed = false;
        for (String word : words) {
            if (word.contains(FILE_PLACEHOLDER)) {
                argv.add(word.replace(FILE_PLACEHOLDER, path));
                placed = true;
            } else {
                argv.add(word);
            }
        }
        if (!placed) {
            argv.add(path);
        }
        return argv;
    }

    /**
     * Splits {@code template} into words: whitespace separates them, {@code "..."} and {@code '...'}
     * group (inside double quotes {@code \"} and {@code \\} stand for {@code "} and {@code \}), and a
     * backslash elsewhere is an ordinary character, so Windows paths need no escaping.
     *
     * @throws IllegalArgumentException on an unclosed quote
     */
    public static List<String> parseTemplate(String template) {
        List<String> words = new ArrayList<>();
        if (template == null) {
            return words;
        }
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        int length = template.length();
        while (i < length) {
            char c = template.charAt(i);
            if (c == '"') {
                inWord = true;
                i++;
                boolean closed = false;
                while (i < length) {
                    char q = template.charAt(i);
                    if (q == '\\' && i + 1 < length && (template.charAt(i + 1) == '"' || template.charAt(i + 1) == '\\')) {
                        current.append(template.charAt(i + 1));
                        i += 2;
                    } else if (q == '"') {
                        closed = true;
                        i++;
                        break;
                    } else {
                        current.append(q);
                        i++;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unclosed double quote in the editor command");
                }
            } else if (c == '\'') {
                inWord = true;
                int end = template.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed single quote in the editor command");
                }
                current.append(template, i + 1, end);
                i = end + 1;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
                i++;
            } else {
                current.append(c);
                inWord = true;
                i++;
            }
        }
        if (inWord) {
            words.add(current.toString());
        }
        return words;
    }

    /** Whether {@code template} parses and names a program; blank counts as valid (system default). */
    public static boolean isValidTemplate(String template) {
        if (template == null || template.isBlank()) {
            return true;
        }
        try {
            commandFromTemplate(template, Path.of("probe.txt"));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Starts {@code plan} for real: a process with its output discarded, or {@code Desktop.edit}. */
    public static void start(Plan plan) throws IOException {
        switch (plan) {
            case Command command -> {
                ProcessBuilder builder = new ProcessBuilder(command.argv());
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = builder.start();
                process.getOutputStream().close();
            }
            case DesktopEdit edit -> {
                if (!java.awt.Desktop.isDesktopSupported()
                        || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.EDIT)) {
                    throw new IOException("Opening files for editing is not supported on this system");
                }
                java.awt.Desktop.getDesktop().edit(edit.file().toFile());
            }
            case NeedsCommand needs -> throw new IOException("No editor command for this file type");
        }
    }
}
