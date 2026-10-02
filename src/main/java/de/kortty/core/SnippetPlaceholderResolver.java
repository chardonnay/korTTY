package de.kortty.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the {@code ${...}} placeholders of a snippet in one pass, with the same rules on every
 * path that uses a snippet (copy, insert, terminal sends, scheduled and swarm runs, script headers).
 *
 * <ul>
 *   <li>A built-in ({@code ${date}}, {@code ${time}}, {@code ${datetime}}, {@code ${hostname}},
 *       {@code ${username}}, {@code ${clipboard}}) gets its value; a built-in wins over a declared
 *       variable of the same name.</li>
 *   <li>{@code ${cursor}} is removed; the output offset of its first occurrence is reported.</li>
 *   <li>A name declared in the Variable Manager gets its supplied value (looked up ignoring case);
 *       without a supplied value it stays as written.</li>
 *   <li>{@code $${name}} produces the literal {@code ${name}}.</li>
 *   <li>Everything else stays exactly as written: undeclared names such as {@code ${HOME}} and the
 *       shell's own forms {@code ${1:-default}}, {@code ${#array[@]}}, {@code ${file%%.*}}.</li>
 * </ul>
 *
 * Inserted values are never scanned again, so a value that itself contains {@code ${...}} reaches
 * the output unchanged. Pure Java, no JavaFX.
 */
public final class SnippetPlaceholderResolver {

    /** Built-in placeholder names, matched case-sensitively. */
    public static final Set<String> BUILT_IN_NAMES =
        Set.of("date", "time", "datetime", "hostname", "username", "clipboard");

    /** The placeholder that marks where the caret goes when a snippet is inserted into an editor. */
    public static final String CURSOR = "cursor";

    /**
     * Standard environment names a scheduled or swarm run hands to the shell without them being
     * declared. Every other undeclared simple name blocks such a run (see {@link #undeclaredSimpleNames}).
     */
    public static final Set<String> HEADLESS_ENVIRONMENT_NAMES = Set.of("HOME", "USER", "PATH", "PWD");

    /**
     * {@code ${name}} or the escaped {@code $${name}}. The body excludes braces, so in
     * {@code ${a:-${b}}} only the inner {@code ${b}} is a placeholder.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$(\\$)?\\{([^{}]+)}");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SnippetPlaceholderResolver() {
    }

    /** Which names the Variable Manager declares; a declared name may have an empty value. */
    @FunctionalInterface
    public interface Declarations {
        boolean isDeclared(String name);

        static Declarations none() {
            return name -> false;
        }
    }

    /**
     * The resolved text and the offset of the first {@code ${cursor}} in it, or {@code -1} when the
     * snippet has none.
     */
    public record ResolvedSnippet(String text, int cursorOffset) {
    }

    /**
     * Resolves {@code content} in a single pass.
     *
     * @param builtIns     values of the built-ins (by exact name); a built-in without a value stays as written
     * @param values       values of declared variables, looked up ignoring case
     * @param declarations which names are declared; {@code null} means none
     */
    public static ResolvedSnippet resolve(
        String content,
        Map<String, String> builtIns,
        Map<String, String> values,
        Declarations declarations) {

        if (content == null || content.isEmpty()) {
            return new ResolvedSnippet("", -1);
        }
        Declarations declared = declarations != null ? declarations : Declarations.none();
        Map<String, String> declaredValues = caseInsensitive(values);
        Map<String, String> builtInValues = builtIns != null ? builtIns : Map.of();

        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder out = new StringBuilder(content.length());
        int cursorOffset = -1;
        int last = 0;
        while (matcher.find()) {
            out.append(content, last, matcher.start());
            last = matcher.end();
            String name = matcher.group(2);
            if (matcher.group(1) != null) {
                out.append("${").append(name).append('}');
            } else if (CURSOR.equals(name)) {
                if (cursorOffset < 0) {
                    cursorOffset = out.length();
                }
            } else if (BUILT_IN_NAMES.contains(name) && builtInValues.get(name) != null) {
                out.append(builtInValues.get(name));
            } else if (!BUILT_IN_NAMES.contains(name)
                && declared.isDeclared(name)
                && declaredValues.get(name) != null) {
                out.append(declaredValues.get(name));
            } else {
                out.append(matcher.group());
            }
        }
        out.append(content, last, content.length());
        return new ResolvedSnippet(out.toString(), cursorOffset);
    }

    /**
     * Declared variable names the content uses, in order of first appearance, without duplicates
     * (ignoring case), built-ins, {@code ${cursor}} and escaped {@code $${...}} occurrences. These
     * are the names an interactive use asks for when they have no stored value.
     */
    public static List<String> declaredVariables(String content, Declarations declarations) {
        List<String> names = new ArrayList<>();
        if (content == null || declarations == null) {
            return names;
        }
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Matcher matcher = PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (matcher.group(1) == null && !isBuiltInOrCursor(name)
                && declarations.isDeclared(name) && seen.add(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Undeclared simple names ({@code ${target}}, not {@code ${1:-x}} or {@code ${#a[@]}}) in order
     * of first appearance, without built-ins, escaped occurrences and the standard environment names
     * {@link #HEADLESS_ENVIRONMENT_NAMES}. A scheduled or swarm run cannot ask for a value, and such
     * a name is most likely a korTTY variable that was never stored, so these runs refuse it instead
     * of letting the shell expand it to an empty string.
     */
    public static List<String> undeclaredSimpleNames(String content, Declarations declarations) {
        List<String> names = new ArrayList<>();
        if (content == null) {
            return names;
        }
        Declarations declared = declarations != null ? declarations : Declarations.none();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (matcher.group(1) == null
                && SIMPLE_NAME.matcher(name).matches()
                && !isBuiltInOrCursor(name)
                && !HEADLESS_ENVIRONMENT_NAMES.contains(name)
                && !declared.isDeclared(name)
                && seen.add(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** The built-in names the content uses (not escaped), so only those values need to be computed. */
    public static Set<String> referencedBuiltIns(String content) {
        Set<String> names = new LinkedHashSet<>();
        if (content == null) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            if (matcher.group(1) == null && BUILT_IN_NAMES.contains(matcher.group(2))) {
                names.add(matcher.group(2));
            }
        }
        return names;
    }

    private static boolean isBuiltInOrCursor(String name) {
        return BUILT_IN_NAMES.contains(name) || CURSOR.equals(name);
    }

    private static Map<String, String> caseInsensitive(Map<String, String> values) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (values != null) {
            values.forEach((name, value) -> {
                if (name != null && value != null) {
                    map.put(name, value);
                }
            });
        }
        return map;
    }
}
