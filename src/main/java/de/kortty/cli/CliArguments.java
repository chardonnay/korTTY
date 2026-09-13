package de.kortty.cli;

import de.kortty.control.ControlApiProtocol;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pure argument parser: {@code argv} in, one {@link CliInvocation} or one
 * {@link CliSyntaxException} out.
 *
 * <p>It reads no file, opens no socket and consumes no stdin, which is what makes the entire flag
 * surface assertable in a unit test. {@code --stdin} is therefore only recorded here;
 * {@link KorttyCli} materialises it into the {@code text} flag before {@link CliCommands#toCall}
 * runs, so the mapper too stays pure.
 *
 * <p>Validation happens against {@link CliCommands}' table rather than against a second list kept
 * here, so a flag can never be accepted by the parser and then silently dropped by the mapper. What
 * this class owns on top of the table is the shape of the line: value flags versus switches,
 * {@code --name=value}, the {@code --} end-of-flags marker, the numeric checks, and the
 * mutually exclusive groups.
 *
 * <p>Pure, any thread. The one thing it reads outside its arguments is {@code user.home}, and only to
 * fill in the default configuration directory when {@code --config-dir} is absent; it never touches
 * that directory.
 */
public final class CliArguments {

    /** Asks for the version banner; answered without contacting the server. */
    static final String FLAG_VERSION = "version";

    /** Asks for usage; answered without contacting the server. */
    static final String FLAG_HELP = "help";

    /** Overrides {@code ~/.kortty} for endpoint discovery. */
    static final String FLAG_CONFIG_DIR = "config-dir";

    /** The client deadline for the whole call, in milliseconds. */
    static final String FLAG_TIMEOUT = "timeout";

    /** Reads the text payload from stdin instead of from a flag. */
    static final String FLAG_STDIN = "stdin";

    /** The name the CLI stores a materialised stdin payload under. */
    static final String FLAG_TEXT = "text";

    /** Default client deadline for a call that does not wait on the server. */
    static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;

    /** How much longer than its own server-side wait the client waits before giving up. */
    static final long WAIT_SLACK_MILLIS = 5_000L;

    /**
     * The {@code agent.start} wait default, which the server declares as {@code 300000}.
     *
     * <p>{@code ControlApiProtocol} publishes every other wait default as a constant but not this
     * one, so it is repeated here rather than guessed. If the two ever disagree the only symptom is a
     * client that gives up slightly early on {@code agent start --wait}, never a wrong call.
     */
    static final long AGENT_START_TIMEOUT_MILLIS = 300_000L;

    /** The global flags, accepted before, between and after the positional tokens. */
    private static final Set<String> GLOBAL_FLAGS = Set.of(FLAG_CONFIG_DIR, FLAG_TIMEOUT, "raw",
        "pretty", "quiet", FLAG_VERSION, FLAG_HELP);

    /** Flags that consume the following token, or the part after {@code =}. */
    private static final Set<String> VALUE_FLAGS = Set.of(FLAG_CONFIG_DIR, FLAG_TIMEOUT, "pane",
        "tab", "window", "method", "kinds", "panes", "count", "lines", "text", "command", "bracketed",
        "regex", "contains", "timeout-ms", "poll-ms", "state", "kind", "until", "wait-until", "alias",
        "prompt", "split-from", "title", "body");

    /** Flags whose value must be a whole number. */
    private static final Set<String> NUMBER_FLAGS = Set.of(FLAG_TIMEOUT, "lines", "count",
        "timeout-ms", "poll-ms");

    /** Flags that must be strictly positive; {@code --count 0} and {@code --timeout 0} mean "no limit". */
    private static final Set<String> POSITIVE_FLAGS = Set.of("lines", "timeout-ms", "poll-ms");

    /** Single-letter aliases, the only short flags the CLI has. */
    private static final Map<String, String> SHORT_FLAGS = Map.of("q", "quiet", "h", FLAG_HELP);

    /**
     * Groups of flags of which at most one may be given.
     *
     * <p>The selector group is the important one: two selectors on one line is the classic way to
     * address the wrong pane, so it is refused here rather than resolved by precedence.
     */
    private static final List<Set<String>> EXCLUSIVE = List.of(
        Set.of("pane", "focused", "current", "split-from"),
        Set.of("visible", "recent", "detection"),
        Set.of("vertical", "horizontal"),
        Set.of("regex", "contains"),
        Set.of(FLAG_TEXT, FLAG_STDIN));

    private static final Set<String> KNOWN_FLAGS = knownFlags();

    private CliArguments() {
    }

    /**
     * Parses one argument vector.
     *
     * @throws CliSyntaxException for an unknown flag, an unknown group or verb, a missing verb, a
     *     repeated flag, two mutually exclusive flags, a missing required flag, a flag the command
     *     does not accept, or a value that is not the whole number the flag needs
     */
    public static CliInvocation parse(String[] args) throws CliSyntaxException {
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        readTokens(args, flags, positionals);

        String group = positionals.isEmpty() ? null : positionals.get(0);
        boolean grouped = CliCommands.hasVerbs(group);
        String verb = grouped && positionals.size() > 1 ? positionals.get(1) : null;
        int firstOperand = Math.min(positionals.size(), grouped ? 2 : 1);
        List<String> operands = List.copyOf(positionals.subList(firstOperand, positionals.size()));

        if (flags.containsKey(FLAG_VERSION)) {
            // The release smoke command: it must answer on a machine where nothing else works.
            return invocation(group, verb, flags, operands, DEFAULT_TIMEOUT_MILLIS);
        }
        if (flags.containsKey(FLAG_HELP)) {
            requireKnownForHelp(group, verb);
            return invocation(group, verb, flags, operands, DEFAULT_TIMEOUT_MILLIS);
        }
        CliCommands.Command command = requireCommand(group, verb, grouped);
        checkFlags(command, flags);
        checkRequired(command, flags);
        checkOperands(command, operands);
        checkExclusive(flags);
        checkNumbers(flags);
        return invocation(group, verb, flags, operands, timeout(command, flags));
    }

    /**
     * A copy of {@code invocation} with one more flag.
     *
     * <p>{@link KorttyCli} uses it to fold a {@code --stdin} payload into {@code text} before the
     * mapping runs, which is how {@link CliCommands#toCall} can stay a pure function of its argument
     * while the CLI still supports piping a prompt in.
     */
    static CliInvocation withFlag(CliInvocation invocation, String name, String value) {
        Map<String, String> flags = new LinkedHashMap<>(invocation.flags());
        flags.put(name, value);
        return new CliInvocation(invocation.group(), invocation.verb(), flags, invocation.operands(),
            invocation.configDir(), invocation.timeoutMillis(), invocation.raw(), invocation.pretty(),
            invocation.quiet());
    }

    /** A copy of {@code invocation} with one more positional token; how {@code raw --stdin} works. */
    static CliInvocation withOperand(CliInvocation invocation, String operand) {
        List<String> operands = new ArrayList<>(invocation.operands());
        operands.add(operand);
        return new CliInvocation(invocation.group(), invocation.verb(), invocation.flags(), operands,
            invocation.configDir(), invocation.timeoutMillis(), invocation.raw(), invocation.pretty(),
            invocation.quiet());
    }

    // --- tokenising --------------------------------------------------------------------------

    private static void readTokens(String[] args, Map<String, String> flags, List<String> positionals)
            throws CliSyntaxException {
        if (args == null) {
            return;
        }
        boolean endOfFlags = false;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) {
                throw new CliSyntaxException("argument " + (index + 1) + " is null");
            }
            if (endOfFlags || !token.startsWith("-") || "-".equals(token)) {
                positionals.add(token);
                continue;
            }
            if ("--".equals(token)) {
                endOfFlags = true;
                continue;
            }
            String name;
            String inline = null;
            if (token.startsWith("--")) {
                int equals = token.indexOf('=');
                name = equals < 0 ? token.substring(2) : token.substring(2, equals);
                inline = equals < 0 ? null : token.substring(equals + 1);
            } else {
                name = SHORT_FLAGS.get(token.substring(1));
                if (name == null) {
                    throw new CliSyntaxException("unknown flag '" + token + "'; try 'kortty-cli --help'");
                }
            }
            if (!KNOWN_FLAGS.contains(name)) {
                throw new CliSyntaxException("unknown flag '--" + name + "'; try 'kortty-cli --help'");
            }
            String value;
            if (VALUE_FLAGS.contains(name)) {
                if (inline != null) {
                    value = inline;
                } else {
                    if (index + 1 >= args.length) {
                        throw new CliSyntaxException("--" + name + " needs a value");
                    }
                    value = args[++index];
                }
            } else {
                if (inline != null) {
                    throw new CliSyntaxException("--" + name + " is a switch and takes no value");
                }
                value = "true";
            }
            if (flags.put(name, value) != null) {
                throw new CliSyntaxException("--" + name + " was given twice");
            }
        }
    }

    // --- validation --------------------------------------------------------------------------

    private static CliCommands.Command requireCommand(String group, String verb, boolean grouped)
            throws CliSyntaxException {
        if (group == null) {
            throw new CliSyntaxException("no command given; try 'kortty-cli --help'");
        }
        if (!CliCommands.isGroup(group)) {
            throw new CliSyntaxException("unknown command '" + group + "'; try 'kortty-cli --help'");
        }
        if (grouped && verb == null) {
            throw new CliSyntaxException("'" + group + "' needs a verb: " + verbList(group));
        }
        CliCommands.Command command = CliCommands.find(group, verb);
        if (command == null) {
            throw new CliSyntaxException("unknown verb '" + verb + "' for '" + group + "': "
                + verbList(group));
        }
        return command;
    }

    private static void requireKnownForHelp(String group, String verb) throws CliSyntaxException {
        if (group == null) {
            return;
        }
        if (!CliCommands.isGroup(group)) {
            throw new CliSyntaxException("unknown command '" + group + "'; try 'kortty-cli --help'");
        }
        if (verb != null && CliCommands.find(group, verb) == null) {
            throw new CliSyntaxException("unknown verb '" + verb + "' for '" + group + "': "
                + verbList(group));
        }
    }

    private static void checkFlags(CliCommands.Command command, Map<String, String> flags)
            throws CliSyntaxException {
        for (String name : flags.keySet()) {
            if (GLOBAL_FLAGS.contains(name) || command.flags().contains(name)) {
                continue;
            }
            throw new CliSyntaxException("'" + command.name() + "' does not accept --" + name);
        }
    }

    private static void checkRequired(CliCommands.Command command, Map<String, String> flags)
            throws CliSyntaxException {
        for (Set<String> group : command.required()) {
            boolean satisfied = false;
            for (String name : group) {
                satisfied |= flags.containsKey(name);
            }
            if (!satisfied) {
                List<String> names = new ArrayList<>(group);
                names.sort(String::compareTo);
                throw new CliSyntaxException("'" + command.name() + "' needs "
                    + names.stream().map(name -> "--" + name).reduce((a, b) -> a + " or " + b).orElse("")
                    + "; usage: kortty-cli " + command.synopsis());
            }
        }
    }

    private static void checkOperands(CliCommands.Command command, List<String> operands)
            throws CliSyntaxException {
        if (command.operands() == null && !operands.isEmpty()) {
            throw new CliSyntaxException("'" + command.name() + "' takes no positional arguments, but "
                + operands.size() + " were given; usage: kortty-cli " + command.synopsis());
        }
    }

    private static void checkExclusive(Map<String, String> flags) throws CliSyntaxException {
        for (Set<String> group : EXCLUSIVE) {
            String first = null;
            for (String name : group) {
                if (!flags.containsKey(name)) {
                    continue;
                }
                if (first != null) {
                    List<String> pair = new ArrayList<>(List.of("--" + first, "--" + name));
                    pair.sort(String::compareTo);
                    throw new CliSyntaxException(pair.get(0) + " and " + pair.get(1)
                        + " cannot be combined");
                }
                first = name;
            }
        }
    }

    private static void checkNumbers(Map<String, String> flags) throws CliSyntaxException {
        for (String name : NUMBER_FLAGS) {
            String value = flags.get(name);
            if (value == null) {
                continue;
            }
            long number;
            try {
                number = Long.parseLong(value.strip());
            } catch (NumberFormatException e) {
                throw new CliSyntaxException("--" + name + " needs a whole number, but was '"
                    + value + "'");
            }
            boolean positive = POSITIVE_FLAGS.contains(name);
            if (number < 0 || (number == 0 && positive)) {
                throw new CliSyntaxException("--" + name + " must be "
                    + (positive ? "greater than zero" : "zero or greater") + ", but was " + number);
            }
        }
    }

    // --- derived values ----------------------------------------------------------------------

    /**
     * The client deadline.
     *
     * <p>A verb that parks on the server gets its own wait plus a slack, so the client never gives up
     * on a wait the server is still honouring; {@code events} without an explicit {@code --timeout}
     * gets no client deadline at all, because its documented end conditions are {@code --count} and
     * the user's interrupt.
     */
    private static long timeout(CliCommands.Command command, Map<String, String> flags) {
        String explicit = flags.get(FLAG_TIMEOUT);
        if (explicit != null) {
            return Long.parseLong(explicit.strip());
        }
        if (command.stream()) {
            return 0L;
        }
        String serverWait = flags.get("timeout-ms");
        if (serverWait != null) {
            return Long.parseLong(serverWait.strip()) + WAIT_SLACK_MILLIS;
        }
        return serverDefaultWait(command, flags) + WAIT_SLACK_MILLIS;
    }

    /**
     * The server-side default wait of a blocking verb, or zero for a verb that does not block.
     *
     * <p>{@code pane split} and {@code pane close} are in the list although they do not wait on
     * anything the caller asked for: their JavaFX hop has a ten-second budget, five times the usual
     * one, so a five-second client deadline would abandon a split the server was still performing.
     */
    private static long serverDefaultWait(CliCommands.Command command, Map<String, String> flags) {
        return switch (command.name()) {
            case "pane split", "pane close" -> ControlApiProtocol.UI_SPLIT_TIMEOUT_MILLIS;
            case "pane wait-output" -> ControlApiProtocol.WAIT_DEFAULT_MILLIS;
            case "agent wait" -> ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS;
            case "agent prompt" -> flags.containsKey("wait-until")
                ? ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS : 0L;
            case "agent start" -> flags.containsKey("wait")
                ? AGENT_START_TIMEOUT_MILLIS : ControlApiProtocol.AGENT_WAIT_DEFAULT_MILLIS;
            default -> 0L;
        };
    }

    private static CliInvocation invocation(String group, String verb, Map<String, String> flags,
                                            List<String> operands, long timeoutMillis)
            throws CliSyntaxException {
        return new CliInvocation(group, verb, flags, operands, configDir(flags), timeoutMillis,
            flags.containsKey("raw"), flags.containsKey("pretty"), flags.containsKey("quiet"));
    }

    private static Path configDir(Map<String, String> flags) throws CliSyntaxException {
        String value = flags.get(FLAG_CONFIG_DIR);
        if (value == null) {
            return ControlDiscovery.defaultConfigDir();
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new CliSyntaxException("--config-dir is not a usable path: " + e.getMessage());
        }
    }

    private static String verbList(String group) {
        List<String> verbs = new ArrayList<>();
        for (CliCommands.Command command : CliCommands.inGroup(group)) {
            if (command.verb() != null) {
                verbs.add(command.verb());
            }
        }
        return String.join(", ", verbs);
    }

    private static Set<String> knownFlags() {
        Set<String> names = new LinkedHashSet<>(GLOBAL_FLAGS);
        names.addAll(CliCommands.everyFlag());
        return Set.copyOf(names);
    }

    /**
     * Whether {@code argv} asks for silence.
     *
     * <p>{@link KorttyCli} needs the answer before {@link #parse} has run, because a syntax error must
     * also stay silent under {@code --quiet}: the contract is "exit code only", and a parser
     * diagnostic is still output. Scanning the raw tokens is exact here, since neither spelling of the
     * flag ever appears as a value — every value flag is consumed by name.
     */
    static boolean looksQuiet(String[] args) {
        if (args == null) {
            return false;
        }
        for (String token : args) {
            if (token == null) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if ("-q".equals(lower) || "--quiet".equals(lower)) {
                return true;
            }
        }
        return false;
    }
}
