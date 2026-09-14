package de.kortty.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The built-in help, rendered from {@link CliCommands}' table.
 *
 * <p>Rendering the help from the same table the parser validates against is the point: a flag cannot
 * appear in {@code --help} and then be refused, or be accepted and go undocumented. The CLI is
 * English-only and has no message bundle, so every string here is a literal — a German user-facing
 * string would have nowhere to come from and nothing to keep it in step with the eight bundles the
 * application ships.
 *
 * <p>None of the three methods throws. Help is asked for on the error path as often as not, and a
 * second diagnostic instead of a usage screen helps nobody: an unknown group falls back to the
 * top-level screen and an unknown verb to its group's.
 *
 * <p>Pure, any thread.
 */
public final class CliUsage {

    private static final String PROGRAM = "kortty-cli";

    private static final String NEWLINE = System.lineSeparator();

    private CliUsage() {
    }

    /** The top-level screen: the synopsis, the global flags, and every group and command. */
    public static String top() {
        StringBuilder text = new StringBuilder();
        text.append("Usage: ").append(PROGRAM)
            .append(" [global flags] <group> <verb> [selector] [flags] [operands]").append(NEWLINE)
            .append(NEWLINE)
            .append("Drives a running korTTY through its local control API. One call, one line of")
            .append(NEWLINE)
            .append("JSON on stdout; failures go to stderr and set the exit code.").append(NEWLINE)
            .append(NEWLINE)
            .append("Global flags:").append(NEWLINE);
        appendRow(text, "--config-dir <path>", "look for control/endpoint.json below <path>"
            + " instead of ~/.kortty");
        appendRow(text, "--timeout <ms>", "client deadline for the whole call (default 5000;"
            + " a waiting verb adds its own wait)");
        appendRow(text, "--raw", "print plain text for the verbs that carry text"
            + " (pane read, agent explain)");
        appendRow(text, "--pretty", "indent the JSON instead of printing one compact line");
        appendRow(text, "-q, --quiet", "print nothing at all; communicate through the exit code");
        appendRow(text, "--version", "print the version and exit without contacting korTTY");
        appendRow(text, "-h, --help", "print this screen, a group's, or a verb's");
        text.append(NEWLINE).append("There is deliberately no --token flag: the token is read from")
            .append(NEWLINE)
            .append("<config-dir>/control/endpoint.json only, so it never reaches argv or a log.")
            .append(NEWLINE).append(NEWLINE)
            .append("Selectors (a pane verb needs exactly one):").append(NEWLINE);
        appendRow(text, "--pane <id>", "a pane id or a w1:t..:p.. address from 'pane list'");
        appendRow(text, "--tab <id>", "that tab's focused pane");
        appendRow(text, "--focused", "whatever pane the user is looking at");
        appendRow(text, "--current", "the pane this process is running in; never falls back");
        text.append(NEWLINE).append("Commands:").append(NEWLINE);
        for (String group : groups()) {
            List<CliCommands.Command> commands = CliCommands.inGroup(group);
            if (commands.size() == 1 && commands.get(0).verb() == null) {
                appendRow(text, commands.get(0).synopsis(), commands.get(0).summary());
            } else {
                appendRow(text, group + " <verb>", verbs(commands));
            }
        }
        text.append(NEWLINE).append("Exit codes: 0 result, 1 failed, 2 usage, 3 unreachable,")
            .append(" 4 wait expired.").append(NEWLINE);
        return text.toString();
    }

    /** One group's screen, or {@link #top()} when the group is unknown. */
    public static String group(String group) {
        List<CliCommands.Command> commands = CliCommands.inGroup(group);
        if (commands.isEmpty()) {
            return top();
        }
        if (commands.size() == 1 && commands.get(0).verb() == null) {
            return verbScreen(commands.get(0));
        }
        StringBuilder text = new StringBuilder();
        text.append("Usage: ").append(PROGRAM).append(' ').append(group).append(" <verb> [flags]")
            .append(NEWLINE).append(NEWLINE);
        for (CliCommands.Command command : commands) {
            appendRow(text, command.synopsis(), command.summary());
        }
        text.append(NEWLINE).append("Run '").append(PROGRAM).append(' ').append(group)
            .append(" <verb> --help' for one verb.").append(NEWLINE);
        return text.toString();
    }

    /** One verb's screen, or the group's when the verb is unknown. */
    public static String verb(String group, String verb) {
        CliCommands.Command command = CliCommands.find(group, verb);
        return command == null ? group(group) : verbScreen(command);
    }

    private static String verbScreen(CliCommands.Command command) {
        StringBuilder text = new StringBuilder();
        text.append("Usage: ").append(PROGRAM).append(' ').append(command.synopsis()).append(NEWLINE)
            .append(NEWLINE).append("  ").append(command.summary()).append(NEWLINE);
        if (command.reserved() != null) {
            return text.toString();
        }
        text.append(NEWLINE).append("  Method: ")
            .append(command.method() == null ? "<the method operand>" : command.method())
            .append(NEWLINE);
        if (command.selector() != CliCommands.Selector.NONE) {
            text.append("  Selector: exactly one of --pane <id>, --tab <id>, --focused, --current")
                .append(NEWLINE);
        }
        if (command.operands() != null) {
            text.append("  Operands: ").append(command.operands()).append(NEWLINE);
        }
        List<String> required = new ArrayList<>();
        for (Set<String> alternatives : command.required()) {
            List<String> names = new ArrayList<>(alternatives);
            names.sort(String::compareTo);
            required.add(names.stream().map(name -> "--" + name)
                .reduce((a, b) -> a + " or " + b).orElse(""));
        }
        if (!required.isEmpty()) {
            text.append("  Required: ").append(String.join(", ", required)).append(NEWLINE);
        }
        return text.toString();
    }

    private static List<String> groups() {
        Set<String> groups = new LinkedHashSet<>();
        for (CliCommands.Command command : CliCommands.commands()) {
            groups.add(command.group());
        }
        return List.copyOf(groups);
    }

    private static String verbs(List<CliCommands.Command> commands) {
        List<String> verbs = new ArrayList<>();
        for (CliCommands.Command command : commands) {
            if (command.verb() != null && command.reserved() == null) {
                verbs.add(command.verb());
            }
        }
        return String.join(", ", verbs);
    }

    private static void appendRow(StringBuilder text, String left, String right) {
        text.append("  ").append(left);
        if (left.length() < 26) {
            text.append(" ".repeat(26 - left.length()));
            text.append(right).append(NEWLINE);
            return;
        }
        text.append(NEWLINE).append("      ").append(right).append(NEWLINE);
    }
}
