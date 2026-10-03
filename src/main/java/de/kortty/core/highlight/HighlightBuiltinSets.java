package de.kortty.core.highlight;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;

import java.util.List;

/**
 * The read-only highlight rule sets korTTY ships. They are defined here in code rather than stored, so
 * a release can improve a pattern without migrating anyone's settings, and they use ids under the
 * reserved {@value #ID_PREFIX} prefix, which a user set may not take.
 *
 * <p>Design rules every built-in follows (and {@code HighlightBuiltinSetsTest} pins):
 * <ul>
 *   <li>Colors are theme colors ({@code ansi:N}), so they follow the pane's palette and the
 *       per-connection colors instead of fighting them.</li>
 *   <li>Color is never the only cue: every rule also sets bold or underline, which still reads for a
 *       color-blind user and in a monochrome theme.</li>
 *   <li>Every pattern is linear-time — bounded repetitions, look-arounds that stop a scan inside a
 *       run of digits or hex — so no line a server can print makes a built-in hit its budget. A
 *       look-ahead guard on the first one or two characters rejects most start positions before any
 *       branch of an alternation is tried, which keeps the cost per character low as well as
 *       linear.</li>
 * </ul>
 *
 * <p>Every call returns fresh copies; callers may mutate them (the editor's Duplicate does).
 */
public final class HighlightBuiltinSets {

    /** Reserved for built-in set ids. */
    public static final String ID_PREFIX = "builtin.";

    public static final String ERRORS = "builtin.errors";

    public static final String NETWORK = "builtin.network";

    public static final String NETWORK_DEVICES = "builtin.network-devices";

    /** The built-in ids, in menu order. */
    public static final List<String> IDS = List.of(ERRORS, NETWORK, NETWORK_DEVICES);

    private static final String RED = "ansi:1";
    private static final String GREEN = "ansi:2";
    private static final String YELLOW = "ansi:3";
    private static final String MAGENTA = "ansi:5";
    private static final String CYAN = "ansi:6";

    /** One hex group of an IPv6 address. */
    private static final String H = "[0-9A-Fa-f]{1,4}";

    /** One IPv4 octet, 0 to 255, without leading zeros. */
    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

    private static final String IPV4 =
        "(?<![\\w.])(?=\\d)(?:" + OCTET + "\\.){3}" + OCTET + "(?:/(?:3[0-2]|[12]?\\d))?(?!\\w|\\.\\d)";

    /**
     * An IPv6 address: eight groups, or up to seven groups on each side of one {@code ::}, plus an
     * optional zone and prefix. Looser than the RFC grammar (it does not cap the total of both sides
     * at seven), which no real output trips over, and far cheaper than spelling out the eight
     * compressed forms one by one.
     */
    private static final String IPV6 =
        "(?<![\\w:.])(?=[0-9A-Fa-f]{0,4}:)(?:(?:" + H + ":){7}" + H
            + "|(?:" + H + "(?::" + H + "){0,6})?::(?:" + H + "(?::" + H + "){0,6})?)"
            + "(?:%[\\w.-]{1,32})?(?:/(?:12[0-8]|1[01]\\d|[1-9]?\\d))?(?![\\w:]|\\.\\d)";

    /** Six hex pairs with one consistent separator, colon or dash. */
    private static final String MAC =
        "(?<![\\w:-])[0-9A-Fa-f]{2}([:-])[0-9A-Fa-f]{2}(?:\\1[0-9A-Fa-f]{2}){4}(?![\\w:-])";

    /** Cisco's dotted notation, aabb.ccdd.eeff. */
    private static final String MAC_DOTTED =
        "(?<![\\w.])[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}\\.[0-9A-Fa-f]{4}(?!\\w|\\.\\w)";

    private static final String SUBINTERFACE = "\\d+(?:/\\d+){0,3}(?:\\.\\d+)?";

    /**
     * Interface names as Cisco, Arista and Juniper print them, short and long. The guard in front
     * rejects a start whose first two letters no name begins with before any branch is tried.
     */
    private static final String INTERFACE =
        "(?=[aefghlmptvx][aegilotuw])(?:"
            + "(?:gi(?:gabitethernet)?|te(?:ngig(?:e|abitethernet))?|tw(?:entyfivegig(?:e|abitethernet))?"
            + "|fo(?:rtygig(?:e|abitethernet))?|hu(?:ndredgig(?:e|abitethernet))?|fa(?:stethernet)?"
            + "|et(?:h(?:ernet)?)?|po(?:rt-channel)?|vlan|lo(?:opback)?|tu(?:nnel)?|mgmt|ma(?:nagement)?)"
            + SUBINTERFACE
            + "|(?:xe|ge|et)-" + SUBINTERFACE
            + "|ae\\d+(?:\\.\\d+)?)";

    private HighlightBuiltinSets() {
    }

    /** Fresh copies of every built-in set, in menu order. */
    public static List<HighlightRuleSet> all() {
        return List.of(errors(), network(), networkDevices());
    }

    /** A fresh copy of the built-in set with this id, or {@code null} when there is none. */
    public static HighlightRuleSet byId(String id) {
        if (id == null) {
            return null;
        }
        return switch (id) {
            case ERRORS -> errors();
            case NETWORK -> network();
            case NETWORK_DEVICES -> networkDevices();
            default -> null;
        };
    }

    /** True for an id of a set korTTY ships. */
    public static boolean isBuiltin(String id) {
        return id != null && IDS.contains(id);
    }

    /** True for every id under {@value #ID_PREFIX}, shipped or not: no user set may take one. */
    public static boolean isReservedId(String id) {
        return id != null && id.startsWith(ID_PREFIX);
    }

    /** The i18n key of a built-in set's display name: {@code highlight.} plus the id. */
    public static String nameKey(String id) {
        return "highlight." + id;
    }

    private static HighlightRuleSet errors() {
        return new HighlightRuleSet(ERRORS, "Errors and warnings", List.of(
            rule(ERRORS + ".error",
                "(?=[cdefprst][aerx])(?:errors?|fail(?:s|ed|ure|ures)?|fatal|critical|denied|refused|panic"
                    + "|exceptions?|traceback|segmentation fault)",
                true, true, RED, true, false),
            rule(ERRORS + ".exception-class", "\\b[A-Z][A-Za-z0-9_]*(?:Exception|Error)\\b",
                false, false, RED, true, false),
            rule(ERRORS + ".warning", "(?=[dtw][aei])(?:warn(?:s|ings?)?|deprecated|timed out)",
                true, true, YELLOW, false, true)));
    }

    private static HighlightRuleSet network() {
        return new HighlightRuleSet(NETWORK, "Network addresses", List.of(
            rule(NETWORK + ".ipv4", IPV4, false, false, CYAN, false, true),
            rule(NETWORK + ".ipv6", IPV6, false, false, CYAN, false, true),
            rule(NETWORK + ".mac", MAC, false, false, MAGENTA, false, true),
            rule(NETWORK + ".mac-dotted", MAC_DOTTED, false, false, MAGENTA, false, true)));
    }

    private static HighlightRuleSet networkDevices() {
        // Down comes first so "not connected" is claimed as down before "connected" can claim it as up.
        return new HighlightRuleSet(NETWORK_DEVICES, "Network devices", List.of(
            rule(NETWORK_DEVICES + ".down",
                "(?=[adeinsu][dinoru])(?:err-disabled|notconnect|not connected|administratively down|down|disabled"
                    + "|inactive|suspended|unreachable)",
                true, true, RED, true, false),
            rule(NETWORK_DEVICES + ".up", "(?=[cefu][ops])(?:up|connected|established|forwarding)",
                true, true, GREEN, true, false),
            rule(NETWORK_DEVICES + ".interface", INTERFACE, true, true, CYAN, false, true)));
    }

    private static HighlightRule rule(String id, String pattern, boolean ignoreCase, boolean wholeWord,
                                      String foreground, boolean bold, boolean underline) {
        HighlightRule rule = new HighlightRule(pattern, true);
        rule.setId(id);
        rule.setIgnoreCase(ignoreCase);
        rule.setWholeWord(wholeWord);
        rule.setForeground(foreground);
        rule.setBold(bold);
        rule.setUnderline(underline);
        return rule;
    }
}
