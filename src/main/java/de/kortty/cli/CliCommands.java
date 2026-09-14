package de.kortty.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.control.ControlApiException;
import de.kortty.control.ControlJson;
import de.kortty.control.ControlKeyTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The documented command table and the pure mapping from one {@link CliInvocation} to one wire call.
 *
 * <p>The table is the CLI's single source of truth: {@link CliArguments} validates flags, verbs and
 * required arguments against it, {@link CliUsage} renders help out of it, and {@link #toCall} maps
 * the result onto a method name and a parameter object. One table means a flag can never be accepted
 * by the parser and then silently ignored by the mapper, and it means {@code --help} describes
 * exactly what the parser will take.
 *
 * <p>Everything here is a pure function of its arguments. Nothing reads a socket, stdin or a file,
 * which is what makes the whole argument surface — including the local key-name check that keeps a
 * typo off the wire — assertable without a running korTTY.
 *
 * <p>Pure, any thread.
 */
public final class CliCommands {

    /**
     * One call, ready to be framed.
     *
     * @param method the wire method name
     * @param params the parameter object; {@link KorttyCli} may still substitute the pane id for a
     *     {@code --current} selector before it is sent
     * @param stream whether the caller must keep reading event notifications after the result, which
     *     only {@code events} does
     */
    public record Call(String method, JsonObject params, boolean stream) {

        public Call {
            Objects.requireNonNull(method, "method");
            params = params == null ? new JsonObject() : params;
        }
    }

    /** What the wire calls the pane the user is looking at. */
    static final String FOCUSED_SELECTOR = "@focused";

    /**
     * The client-side stand-in for {@code --current}.
     *
     * <p>It is deliberately not a wire selector: the pane a script is <em>running in</em> can only be
     * found by walking this process's ancestors and asking {@code pane.resolve}, so {@link #toCall}
     * emits the sentinel and {@link KorttyCli} replaces it with the resolved pane id once it has a
     * connection. Emitting a sentinel rather than omitting the parameter keeps {@code toCall} total:
     * every call it returns names its pane.
     */
    static final String CURRENT_SELECTOR = "@current";

    /** How a verb addresses a pane. */
    enum Selector {
        /** The verb addresses no pane; {@code --tab} and {@code --window} are plain filters. */
        NONE,
        /** Exactly one of {@code --pane}, {@code --tab}, {@code --focused}, {@code --current}. */
        PANE,
        /** Exactly one of those four or {@code --split-from}. */
        PANE_OR_SPLIT
    }

    /**
     * One documented command.
     *
     * @param group the first positional token
     * @param verb the second positional token, or null for a verb-less command
     * @param method the wire method, or null for {@code raw}, whose method is an operand
     * @param selector how the command addresses a pane
     * @param flags the non-global flags the command accepts, without their dashes
     * @param required groups of flags of which at least one must be given; a plain required flag is a
     *     one-element group
     * @param operands a short description of the accepted positional tokens, or null when the command
     *     takes none
     * @param stream whether the command keeps reading events after the result
     * @param reserved the refusal message for a verb that exists in the vocabulary but is out of
     *     scope in this version, or null
     * @param synopsis the usage line, without the program name
     * @param summary one sentence for the help output
     */
    record Command(String group, String verb, String method, Selector selector, Set<String> flags,
                   List<Set<String>> required, String operands, boolean stream, String reserved,
                   String synopsis, String summary) {

        Command {
            flags = Set.copyOf(flags);
            required = List.copyOf(required);
        }

        /** {@code "<group> <verb>"}, or just the group for a verb-less command. */
        String name() {
            return verb == null ? group : group + " " + verb;
        }
    }

    /** The four pane selectors, in the order the diagnostics list them. */
    static final List<String> SELECTOR_FLAGS = List.of("pane", "tab", "focused", "current");

    /** The read modes of {@code pane read} and {@code pane wait-output}. */
    private static final List<String> MODE_FLAGS = List.of("visible", "recent", "detection");

    /** The split orientations. */
    private static final List<String> ORIENTATION_FLAGS = List.of("vertical", "horizontal");

    private static final String SELECTOR_SYNTAX = "--pane <id> | --tab <id> | --focused | --current";

    private static final String RESERVED_TAB =
        "not implemented in this version; open the tab in korTTY and address it with --pane";

    private static final List<Command> TABLE = buildTable();

    private static final Map<String, Command> BY_NAME = byName();

    private static final Set<String> GROUPS_WITH_VERBS = groupsWithVerbs();

    private CliCommands() {
    }

    /**
     * Maps one parsed invocation onto one wire call.
     *
     * @throws CliSyntaxException when the command is reserved, when a pane verb has no selector or
     *     more than one, when two mutually exclusive mode flags are combined, or when a key name is
     *     not in {@code ControlKeyTable}'s vocabulary — the last of which is the whole point of the
     *     local check: a mistyped key must cost a syntax error, not a round trip and an audit line
     */
    public static Call toCall(CliInvocation invocation) throws CliSyntaxException {
        Objects.requireNonNull(invocation, "invocation");
        Command command = require(invocation);
        if (command.reserved() != null) {
            throw new CliSyntaxException(command.reserved());
        }
        if ("raw".equals(command.group())) {
            return rawCall(invocation);
        }
        JsonObject params = new JsonObject();
        switch (command.method()) {
            case "ping", "window.list", "pane.current" -> { /* no parameters at all */ }
            case "api.schema" -> copyString(invocation, params, "method", "method");
            case "events.subscribe" -> {
                copyList(invocation, params, "kinds", "kinds");
                copyList(invocation, params, "panes", "panes");
                copyFlag(invocation, params, "include-evidence", "include_evidence");
            }
            case "tab.list" -> copyString(invocation, params, "window", "window");
            case "tab.focus" -> params.addProperty("tab", invocation.flag("tab", null));
            case "pane.list" -> {
                copyString(invocation, params, "window", "window");
                copyString(invocation, params, "tab", "tab");
                copyFlag(invocation, params, "local-shell-only", "local_shell_only");
            }
            case "pane.get", "pane.close" -> params.addProperty("pane", paneRef(invocation, command));
            case "pane.focus" -> {
                params.addProperty("pane", paneRef(invocation, command));
                if (invocation.has("no-raise")) {
                    params.addProperty("raise", false);
                }
            }
            case "pane.read" -> {
                params.addProperty("pane", paneRef(invocation, command));
                putMode(invocation, params, MODE_FLAGS);
                copyNumber(invocation, params, "lines", "lines");
            }
            case "pane.send_text" -> {
                params.addProperty("pane", paneRef(invocation, command));
                params.addProperty("text", requireText(invocation, command));
                copyFlag(invocation, params, "submit", "submit");
                putChoice(invocation, params, "bracketed", "bracketed",
                    List.of("auto", "never", "always"));
                copyFlag(invocation, params, "allow-shortcut-conflict", "allow_shortcut_conflict");
            }
            case "pane.run" -> {
                params.addProperty("pane", paneRef(invocation, command));
                params.addProperty("command", invocation.flag("command", null));
            }
            case "pane.send_keys", "agent.send_keys" -> {
                params.addProperty("pane", paneRef(invocation, command));
                params.add("keys", keys(invocation, command));
            }
            case "pane.wait_output" -> {
                params.addProperty("pane", paneRef(invocation, command));
                copyString(invocation, params, "regex", "regex");
                copyString(invocation, params, "contains", "contains");
                putMode(invocation, params, List.of("visible", "recent"));
                copyNumber(invocation, params, "lines", "lines");
                copyNumber(invocation, params, "timeout-ms", "timeout_ms");
                copyNumber(invocation, params, "poll-ms", "poll_ms");
            }
            case "pane.split" -> {
                params.addProperty("pane", paneRef(invocation, command));
                putOrientation(invocation, params);
                if (invocation.has("no-focus")) {
                    params.addProperty("focus", false);
                }
            }
            case "agent.list" -> {
                copyString(invocation, params, "state", "state");
                copyString(invocation, params, "kind", "kind");
                copyString(invocation, params, "tab", "tab");
                copyString(invocation, params, "window", "window");
            }
            case "agent.get", "agent.explain" -> params.addProperty("pane", paneRef(invocation, command));
            case "agent.wait" -> {
                params.addProperty("pane", paneRef(invocation, command));
                putChoice(invocation, params, "until", "until",
                    List.of("blocked", "done", "working", "idle", "any"));
                copyNumber(invocation, params, "timeout-ms", "timeout_ms");
            }
            case "agent.prompt" -> {
                params.addProperty("pane", paneRef(invocation, command));
                params.addProperty("text", requireText(invocation, command));
                putChoice(invocation, params, "wait-until", "wait_until",
                    List.of("done", "idle", "blocked", "any"));
                copyNumber(invocation, params, "timeout-ms", "timeout_ms");
            }
            case "agent.rename" -> {
                params.addProperty("pane", paneRef(invocation, command));
                params.addProperty("alias", invocation.flag("alias", ""));
            }
            case "agent.start" -> agentStart(invocation, command, params);
            case "notification.show" -> {
                params.addProperty("title", invocation.flag("title", null));
                params.addProperty("body", invocation.flag("body", null));
            }
            default -> throw new CliSyntaxException("unknown command '" + command.name() + "'");
        }
        return new Call(command.method(), params, command.stream());
    }

    /**
     * The help text for one group, or the top-level help when {@code groupOrNull} is null.
     *
     * <p>An unknown group falls back to the top-level help rather than failing: the caller is already
     * on the error path and a usage screen is more useful there than a second diagnostic.
     */
    public static String usage(String groupOrNull) {
        return groupOrNull == null ? CliUsage.top() : CliUsage.group(groupOrNull);
    }

    // --- the table, shared with CliArguments and CliUsage -------------------------------------

    /** Every documented command, in the order the help lists them. */
    static List<Command> commands() {
        return TABLE;
    }

    /** The commands of one group, in table order; empty when the group is unknown. */
    static List<Command> inGroup(String group) {
        List<Command> result = new ArrayList<>();
        for (Command command : TABLE) {
            if (command.group().equals(group)) {
                result.add(command);
            }
        }
        return List.copyOf(result);
    }

    /** Whether {@code group} takes a verb as its second positional token. */
    static boolean hasVerbs(String group) {
        return group != null && GROUPS_WITH_VERBS.contains(group);
    }

    /** Whether {@code group} is a known group or verb-less command. */
    static boolean isGroup(String group) {
        if (group == null) {
            return false;
        }
        for (Command command : TABLE) {
            if (command.group().equals(group)) {
                return true;
            }
        }
        return false;
    }

    /** The command for a group and verb, or null when there is none. */
    static Command find(String group, String verb) {
        if (group == null) {
            return null;
        }
        return BY_NAME.get(verb == null ? group : group + " " + verb);
    }

    /** Every flag name any command accepts; what {@link CliArguments} calls an unknown flag against. */
    static Set<String> everyFlag() {
        Set<String> names = new LinkedHashSet<>();
        for (Command command : TABLE) {
            names.addAll(command.flags());
        }
        return Set.copyOf(names);
    }

    // --- mapping helpers ---------------------------------------------------------------------

    private static Command require(CliInvocation invocation) throws CliSyntaxException {
        Command command = find(invocation.group(), invocation.verb());
        if (command == null) {
            throw new CliSyntaxException("unknown command '" + invocation.commandName()
                + "'; try 'kortty-cli --help'");
        }
        return command;
    }

    private static Call rawCall(CliInvocation invocation) throws CliSyntaxException {
        List<String> operands = invocation.operands();
        if (operands.isEmpty()) {
            throw new CliSyntaxException("raw needs a method name, for example 'raw pane.list'");
        }
        if (operands.size() > 2) {
            throw new CliSyntaxException("raw takes a method and at most one JSON params object, but "
                + operands.size() + " operands were given");
        }
        JsonObject params = new JsonObject();
        if (operands.size() == 2) {
            try {
                params = ControlJson.parseObjectStrict(operands.get(1));
            } catch (ControlApiException e) {
                throw new CliSyntaxException("the params operand is not one JSON object: "
                    + e.getMessage());
            }
        }
        return new Call(operands.get(0), params, false);
    }

    private static void agentStart(CliInvocation invocation, Command command, JsonObject params)
            throws CliSyntaxException {
        if (invocation.has("split-from")) {
            for (String selector : SELECTOR_FLAGS) {
                if (invocation.has(selector)) {
                    throw new CliSyntaxException("agent start takes either a selector or"
                        + " --split-from, but --split-from and --" + selector + " were given");
                }
            }
            params.addProperty("split_from", invocation.flag("split-from", null));
            putOrientation(invocation, params);
        } else {
            params.addProperty("pane", paneRef(invocation, command));
        }
        params.addProperty("kind", invocation.flag("kind", null));
        if (invocation.has("command")) {
            JsonArray argv = new JsonArray();
            for (String word : invocation.flag("command", "").strip().split("\\s+")) {
                if (!word.isEmpty()) {
                    argv.add(word);
                }
            }
            params.add("command", argv);
        }
        copyString(invocation, params, "prompt", "prompt");
        copyFlag(invocation, params, "wait", "wait");
        putChoice(invocation, params, "until", "until", List.of("done", "idle"));
        copyNumber(invocation, params, "timeout-ms", "timeout_ms");
    }

    /**
     * The single pane reference a pane verb addresses.
     *
     * <p>{@code --current} becomes {@link #CURRENT_SELECTOR} rather than the focused pane. Falling
     * back to whatever the user happens to be looking at is exactly how a script ends up typing into
     * the wrong shell, so the sentinel travels on and {@link KorttyCli} fails loudly when no ancestor
     * process matches a pane.
     */
    private static String paneRef(CliInvocation invocation, Command command) throws CliSyntaxException {
        List<String> given = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (String selector : SELECTOR_FLAGS) {
            if (!invocation.has(selector)) {
                continue;
            }
            named.add("--" + selector);
            given.add(switch (selector) {
                case "focused" -> FOCUSED_SELECTOR;
                case "current" -> CURRENT_SELECTOR;
                default -> invocation.flag(selector, null);
            });
        }
        if (given.isEmpty()) {
            String extra = command.selector() == Selector.PANE_OR_SPLIT
                ? " or --split-from <selector>" : "";
            throw new CliSyntaxException(command.name() + " needs exactly one selector ("
                + SELECTOR_SYNTAX + extra + ")");
        }
        if (given.size() > 1) {
            throw new CliSyntaxException(command.name() + " accepts exactly one selector, but "
                + String.join(" and ", named) + " were given");
        }
        return given.get(0);
    }

    private static String requireText(CliInvocation invocation, Command command)
            throws CliSyntaxException {
        String text = invocation.flag("text", null);
        if (text == null) {
            throw new CliSyntaxException(command.name()
                + " needs --text <s> or --stdin; --stdin is read by kortty-cli before the call");
        }
        return text;
    }

    private static JsonArray keys(CliInvocation invocation, Command command) throws CliSyntaxException {
        if (invocation.operands().isEmpty()) {
            throw new CliSyntaxException(command.name()
                + " needs at least one key name, for example 'y enter'");
        }
        List<String> normalised;
        try {
            normalised = ControlKeyTable.normalise(invocation.operands());
        } catch (ControlApiException e) {
            throw new CliSyntaxException(e.getMessage() + "; known key names: "
                + String.join(", ", ControlKeyTable.knownKeys()));
        }
        JsonArray array = new JsonArray();
        normalised.forEach(array::add);
        return array;
    }

    private static void putMode(CliInvocation invocation, JsonObject params, List<String> modes)
            throws CliSyntaxException {
        String mode = exactlyOne(invocation, modes, "read mode");
        if (mode != null) {
            params.addProperty("mode", mode);
        }
    }

    private static void putOrientation(CliInvocation invocation, JsonObject params)
            throws CliSyntaxException {
        String orientation = exactlyOne(invocation, ORIENTATION_FLAGS, "orientation");
        if (orientation != null) {
            params.addProperty("orientation", orientation);
        }
    }

    /** The one flag of {@code candidates} that was given, or null for none; two is a syntax error. */
    private static String exactlyOne(CliInvocation invocation, List<String> candidates, String what)
            throws CliSyntaxException {
        String chosen = null;
        for (String candidate : candidates) {
            if (!invocation.has(candidate)) {
                continue;
            }
            if (chosen != null) {
                throw new CliSyntaxException("--" + chosen + " and --" + candidate
                    + " both set the " + what + "; give at most one");
            }
            chosen = candidate;
        }
        return chosen;
    }

    /**
     * Copies a closed-choice flag, rejecting a value outside the published set locally.
     *
     * <p>These are the only values the CLI second-guesses the server on, and only because the usage
     * text enumerates them: a mistyped {@code --until don} should read as a typo, not as an
     * {@code invalid_params} round trip. Open-ended filters such as {@code --state} and {@code --kind}
     * are passed straight through so the CLI cannot drift out of step with the server's vocabulary.
     */
    private static void putChoice(CliInvocation invocation, JsonObject params, String flag,
                                  String param, List<String> choices) throws CliSyntaxException {
        String value = invocation.flag(flag, null);
        if (value == null) {
            return;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!choices.contains(lower)) {
            throw new CliSyntaxException("--" + flag + " must be one of "
                + String.join(", ", choices) + ", but was '" + value + "'");
        }
        params.addProperty(param, lower);
    }

    private static void copyString(CliInvocation invocation, JsonObject params, String flag,
                                   String param) {
        String value = invocation.flag(flag, null);
        if (value != null) {
            params.addProperty(param, value);
        }
    }

    private static void copyNumber(CliInvocation invocation, JsonObject params, String flag,
                                   String param) throws CliSyntaxException {
        String value = invocation.flag(flag, null);
        if (value == null) {
            return;
        }
        try {
            params.addProperty(param, Long.valueOf(value.strip()));
        } catch (NumberFormatException e) {
            // CliArguments already rejects this; the guard keeps toCall total for a hand-built
            // invocation, so a caller can never turn a bad number into an unchecked failure.
            throw new CliSyntaxException("--" + flag + " needs a whole number, but was '"
                + value + "'");
        }
    }

    private static void copyFlag(CliInvocation invocation, JsonObject params, String flag,
                                 String param) {
        if (invocation.has(flag)) {
            params.addProperty(param, true);
        }
    }

    private static void copyList(CliInvocation invocation, JsonObject params, String flag,
                                 String param) {
        String value = invocation.flag(flag, null);
        if (value == null) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String item : value.split(",")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                array.add(trimmed);
            }
        }
        params.add(param, array);
    }

    // --- the table ---------------------------------------------------------------------------

    private static List<Command> buildTable() {
        List<Command> table = new ArrayList<>();
        Set<String> selectors = Set.copyOf(SELECTOR_FLAGS);

        table.add(simple("ping", "ping", Set.of(), "ping", "Checks that korTTY answers."));
        table.add(simple("schema", "api.schema", Set.of("method"), "schema [--method <name>]",
            "Prints the machine-readable API schema, or one method of it."));
        table.add(new Command("events", null, "events.subscribe", Selector.NONE,
            Set.of("kinds", "panes", "include-evidence", "count"), List.of(), null, true, null,
            "events [--kinds a,b] [--panes p1,p2] [--include-evidence] [--count N]",
            "Streams one agent event per line until --count, the timeout or SIGINT."));

        table.add(new Command("window", "list", "window.list", Selector.NONE, Set.of(), List.of(),
            null, false, null, "window list", "Lists the open korTTY windows."));

        table.add(new Command("tab", "list", "tab.list", Selector.NONE, Set.of("window"), List.of(),
            null, false, null, "tab list [--window <id>]", "Lists the tabs of one or every window."));
        table.add(new Command("tab", "focus", "tab.focus", Selector.NONE, Set.of("tab"),
            List.of(Set.of("tab")), null, false, null, "tab focus --tab <id>",
            "Raises the window and selects the tab."));
        table.add(reserved("tab", "create"));
        table.add(reserved("tab", "close"));
        table.add(reserved("tab", "rename"));

        table.add(new Command("pane", "list", "pane.list", Selector.NONE,
            Set.of("tab", "window", "local-shell-only"), List.of(), null, false, null,
            "pane list [--tab <id>] [--window <id>] [--local-shell-only]",
            "Lists the open panes."));
        table.add(new Command("pane", "current", "pane.current", Selector.NONE, Set.of(), List.of(),
            null, false, null, "pane current", "Prints the pane the user is looking at."));
        table.add(pane("get", "pane.get", Set.of(), List.of(), null,
            "pane get <selector>", "Prints one pane."));
        table.add(pane("focus", "pane.focus", Set.of("no-raise"), List.of(), null,
            "pane focus <selector> [--no-raise]", "Requests focus for a pane."));
        table.add(pane("read", "pane.read", Set.of("visible", "recent", "detection", "lines"),
            List.of(), null,
            "pane read <selector> [--visible|--recent|--detection] [--lines N]",
            "Reads a pane's screen, its recent scrollback or its agent detection."));
        table.add(pane("send-text", "pane.send_text",
            Set.of("text", "stdin", "submit", "bracketed", "allow-shortcut-conflict"),
            List.of(Set.of("text", "stdin")), null,
            "pane send-text <selector> --text <s>|--stdin [--submit]"
                + " [--bracketed auto|never|always] [--allow-shortcut-conflict]",
            "Types text into any pane, agent or not."));
        table.add(pane("run", "pane.run", Set.of("command"), List.of(Set.of("command")), null,
            "pane run <selector> --command <s>",
            "Types one single-line command and submits it."));
        table.add(pane("send-keys", "pane.send_keys", Set.of(), List.of(), "<key>...",
            "pane send-keys <selector> <key>...",
            "Sends key names; an unknown name is a local syntax error."));
        table.add(pane("wait-output", "pane.wait_output",
            Set.of("regex", "contains", "visible", "recent", "lines", "timeout-ms", "poll-ms"),
            List.of(Set.of("regex", "contains")), null,
            "pane wait-output <selector> (--regex R | --contains S) [--visible|--recent]"
                + " [--lines N] [--timeout-ms N] [--poll-ms N]",
            "Waits until a pane's output matches."));
        table.add(pane("split", "pane.split", Set.of("vertical", "horizontal", "no-focus"),
            List.of(), null, "pane split <selector> [--vertical|--horizontal] [--no-focus]",
            "Splits a local-shell pane and prints the new pane."));
        table.add(pane("close", "pane.close", Set.of(), List.of(), null, "pane close <selector>",
            "Closes a split pane; a tab's last pane is refused."));

        table.add(new Command("agent", "list", "agent.list", Selector.NONE,
            Set.of("state", "kind", "tab", "window"), List.of(), null, false, null,
            "agent list [--state S] [--kind K] [--tab <id>] [--window <id>]",
            "Lists the registered coding agents, most urgent first."));
        table.add(agent("get", "agent.get", Set.of(), List.of(), null, "agent get <selector>",
            "Prints one agent."));
        table.add(agent("explain", "agent.explain", Set.of(), List.of(), null,
            "agent explain <selector>", "Explains why an agent is in its state (--raw prints text)."));
        table.add(agent("wait", "agent.wait", Set.of("until", "timeout-ms"),
            List.of(Set.of("until")), null,
            "agent wait <selector> --until blocked|done|working|idle|any [--timeout-ms N]",
            "Waits for an agent state change; an expired wait exits 4."));
        table.add(agent("prompt", "agent.prompt",
            Set.of("text", "stdin", "wait-until", "timeout-ms"), List.of(Set.of("text", "stdin")),
            null, "agent prompt <selector> --text <s>|--stdin [--wait-until done] [--timeout-ms N]",
            "Prompts an agent and optionally waits for it."));
        table.add(agent("send-keys", "agent.send_keys", Set.of(), List.of(), "<key>...",
            "agent send-keys <selector> <key>...", "Answers an agent's prompt with key names."));
        table.add(agent("rename", "agent.rename", Set.of("alias"), List.of(Set.of("alias")), null,
            "agent rename <selector> --alias <s>", "Sets or clears an agent's alias."));
        Set<String> startFlags = new LinkedHashSet<>(selectors);
        startFlags.addAll(Set.of("split-from", "vertical", "horizontal", "kind", "command", "prompt",
            "wait", "until", "timeout-ms"));
        table.add(new Command("agent", "start", "agent.start", Selector.PANE_OR_SPLIT,
            Set.copyOf(startFlags), List.of(Set.of("kind")), null, false, null,
            "agent start (<selector> | --split-from <selector> [--vertical]) --kind K"
                + " [--command <s>] [--prompt <s>] [--wait] [--until done|idle] [--timeout-ms N]",
            "Launches a coding agent in a pane and waits until korTTY has registered it."));

        table.add(new Command("notify", null, "notification.show", Selector.NONE,
            Set.of("title", "body"), List.of(Set.of("title"), Set.of("body")), null, false, null,
            "notify --title <s> --body <s>", "Raises one desktop notification."));
        table.add(new Command("raw", null, null, Selector.NONE, Set.of("stdin"), List.of(),
            "<method> [<json>]", false, null, "raw <method> [<json>|--stdin]",
            "Escape hatch: calls any method with params from an argument or stdin."));

        return List.copyOf(table);
    }

    private static Command simple(String group, String method, Set<String> flags, String synopsis,
                                  String summary) {
        return new Command(group, null, method, Selector.NONE, flags, List.of(), null, false, null,
            synopsis, summary);
    }

    private static Command pane(String verb, String method, Set<String> extraFlags,
                                List<Set<String>> required, String operands, String synopsis,
                                String summary) {
        return selectorCommand("pane", verb, method, extraFlags, required, operands, synopsis, summary);
    }

    private static Command agent(String verb, String method, Set<String> extraFlags,
                                 List<Set<String>> required, String operands, String synopsis,
                                 String summary) {
        return selectorCommand("agent", verb, method, extraFlags, required, operands, synopsis,
            summary);
    }

    private static Command selectorCommand(String group, String verb, String method,
                                           Set<String> extraFlags, List<Set<String>> required,
                                           String operands, String synopsis, String summary) {
        Set<String> flags = new LinkedHashSet<>(SELECTOR_FLAGS);
        flags.addAll(extraFlags);
        return new Command(group, verb, method, Selector.PANE, Set.copyOf(flags), required, operands,
            false, null, synopsis, summary);
    }

    private static Command reserved(String group, String verb) {
        return new Command(group, verb, null, Selector.NONE, Set.of(), List.of(), null, false,
            RESERVED_TAB, group + " " + verb, "Reserved; " + RESERVED_TAB + ".");
    }

    private static Map<String, Command> byName() {
        Map<String, Command> map = new LinkedHashMap<>();
        for (Command command : TABLE) {
            map.put(command.name(), command);
        }
        return Map.copyOf(map);
    }

    private static Set<String> groupsWithVerbs() {
        Set<String> groups = new LinkedHashSet<>();
        for (Command command : TABLE) {
            if (command.verb() != null) {
                groups.add(command.group());
            }
        }
        return Set.copyOf(groups);
    }
}
