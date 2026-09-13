package de.kortty.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One parsed command line, before it becomes a wire call.
 *
 * <p>This is the boundary between the two pure halves of the CLI: {@link CliArguments#parse}
 * produces it from {@code argv} without touching a socket, and {@link CliCommands#toCall} consumes it
 * without touching one either. Keeping the five global flags as their own components and everything
 * else in {@code flags} is what lets {@code toCall} switch on the verb alone — a verb never has to
 * re-derive whether the user asked for {@code --pretty}.
 *
 * <p>The selectors live in {@code flags} rather than in a component of their own because their
 * meaning is verb-dependent: {@code --tab} addresses a tab's focused pane for {@code pane get} but
 * filters a list for {@code pane list}, and only the verb knows which.
 *
 * <p>Pure, any thread. {@code flags} and {@code operands} are unmodifiable.
 *
 * @param group the first positional token — a group such as {@code pane}, or a verb-less command
 *     such as {@code ping}; null for a bare {@code --help} or {@code --version}
 * @param verb the second positional token for the groups that have verbs; null otherwise
 * @param flags every non-global flag by name without its dashes, a value-less flag mapped to
 *     {@code "true"}; {@code help} and {@code version} appear here too so {@link KorttyCli} can
 *     answer them before any command mapping happens
 * @param operands the remaining positional tokens: key names for the {@code send-keys} verbs, the
 *     method and params for {@code raw}
 * @param configDir the directory {@code control/endpoint.json} is looked up in; never null
 * @param timeoutMillis the client deadline for the whole call, or 0 for no client deadline, which
 *     only {@code events} without an explicit {@code --timeout} uses
 * @param raw whether to print the text form of a text-bearing verb instead of JSON
 * @param pretty whether to indent the JSON instead of emitting one compact line
 * @param quiet whether to print nothing at all and communicate through the exit code only
 */
public record CliInvocation(String group, String verb, Map<String, String> flags,
                            List<String> operands, Path configDir, long timeoutMillis, boolean raw,
                            boolean pretty, boolean quiet) {

    public CliInvocation {
        flags = flags == null ? Map.of() : Map.copyOf(flags);
        operands = operands == null ? List.of() : List.copyOf(operands);
    }

    /** Whether {@code name} was given, whatever its value. */
    public boolean has(String name) {
        return flags.containsKey(name);
    }

    /** The value of {@code name}, or {@code fallback} when it was not given. */
    public String flag(String name, String fallback) {
        return flags.getOrDefault(name, fallback);
    }

    /**
     * {@code "<group> <verb>"}, or just the group for a verb-less command, or the program name when
     * there is neither — which is what a bare {@code --help} looks like.
     */
    public String commandName() {
        if (group == null) {
            return "kortty-cli";
        }
        return verb == null ? group : group + " " + verb;
    }
}
