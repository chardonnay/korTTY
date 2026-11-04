package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises commands that only show help — {@code ls --help}, {@code docker run --help},
 * {@code git help log}, {@code man crontab} — so the journal leaves them out like a typo: they
 * tell the reader nothing about the session.
 *
 * <p>{@code -h} and {@code -?} count only when the output looks like help, because {@code -h}
 * means "human-readable" for {@code df}, {@code du}, {@code free} and {@code ls}. A command line
 * with a pipe, a redirection or a second command is never help-only.</p>
 */
final class SessionJournalHelpCommands {

    /** Flags that ask for help whatever the program does with its other options. */
    private static final Set<String> HELP_FLAGS = Set.of("--help", "-help", "--usage");
    /** Flags that mean help only for some programs: the output decides. */
    private static final Set<String> MAYBE_HELP_FLAGS = Set.of("-h", "-?", "/?");
    /** Programs that only show documentation. */
    private static final Set<String> HELP_PROGRAMS = Set.of("man", "info", "tldr", "help", "whatis", "apropos");
    /** Programs whose {@code help} subcommand shows help; for others ({@code grep help notes.txt}) the output decides. */
    private static final Set<String> HELP_SUBCOMMAND_PROGRAMS = Set.of("git", "go", "docker", "podman", "cargo",
        "rustup", "npm", "pnpm", "yarn", "pip", "pip3", "kubectl", "helm", "gh", "terraform", "dotnet", "dnf",
        "yum", "apt", "zypper", "flatpak", "snap", "brew", "nmcli", "virsh", "openssl", "make");
    /** Wrappers in front of the program that change nothing about what it shows. */
    private static final Set<String> PREFIXES = Set.of("sudo", "command", "builtin");
    private static final Pattern SHELL_OPERATOR = Pattern.compile("[|;&<>`]|\\$\\(");
    /** A usage or options heading, in the languages programs print them in. */
    private static final Pattern HELP_OUTPUT = Pattern.compile(
        "(?iu)(?:^|\\s)(?:usage|aufruf|verwendung|benutzung|utilisation|uso|gebruik|upotreba|synopsis)\\s*:"
            + "|^\\s*(?:options|optionen|flags|commands|befehle|available commands|global options)\\s*:?\\s*$"
            + "|^\\s*(?:synopsis|übersicht)\\s*$");
    /** How many output lines are searched for a usage heading. */
    private static final int OUTPUT_LINES_CHECKED = 40;

    private SessionJournalHelpCommands() {
    }

    /** True when {@code command} only showed help, judged by its words and its {@code output}. */
    static boolean isHelpOnly(String command, List<String> output) {
        if (command == null || command.isBlank() || SHELL_OPERATOR.matcher(command).find()) {
            return false;
        }
        List<String> words = new ArrayList<>(List.of(command.strip().split("\\s+")));
        while (!words.isEmpty() && PREFIXES.contains(words.get(0))) {
            words.remove(0);
        }
        if (words.isEmpty()) {
            return false;
        }
        String program = programName(words.get(0));
        if (HELP_PROGRAMS.contains(program)) {
            return true;
        }
        if (words.size() < 2) {
            return false;
        }
        String last = words.get(words.size() - 1).toLowerCase(Locale.ROOT);
        List<String> between = words.subList(1, words.size() - 1);
        if (between.stream().anyMatch(word -> word.startsWith("-"))) {
            return false; // other options: the command does something besides showing help
        }
        if ("help".equals(words.get(1))) {
            // git help log, go help build, docker help
            return HELP_SUBCOMMAND_PROGRAMS.contains(program) || looksLikeHelp(output);
        }
        if (HELP_FLAGS.contains(last)) {
            return true;
        }
        return MAYBE_HELP_FLAGS.contains(last) && looksLikeHelp(output);
    }

    private static String programName(String word) {
        int slash = word.lastIndexOf('/');
        return (slash >= 0 ? word.substring(slash + 1) : word).toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeHelp(List<String> output) {
        if (output == null) {
            return false;
        }
        int checked = 0;
        for (String line : output) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (HELP_OUTPUT.matcher(line).find()) {
                return true;
            }
            if (++checked >= OUTPUT_LINES_CHECKED) {
                break;
            }
        }
        return false;
    }
}
