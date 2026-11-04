package de.kortty.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;

/**
 * The korTTY placeholders a snippet uses, grouped the way copying, inserting and sending treat them
 * (see {@link SnippetPlaceholderResolver}): built-ins, declared variables with a stored value,
 * declared variables without one (asked for on every use), and undeclared simple names, which stay
 * as written and block a scheduled or swarm run. Shell forms ({@code ${1:-x}}, {@code ${#a[@]}}),
 * the standard environment names and escaped {@code $${name}} are not placeholders and are left out.
 *
 * <p>Only the status is reported, never a stored value: values are plain text and may be secrets.
 * Pure Java.
 */
public final class SnippetPlaceholderScanner {

    /** How a use of the snippet treats the placeholder. */
    public enum Status {
        /** {@code ${date}}, {@code ${time}}, …, {@code ${cursor}}. */
        BUILT_IN,
        /** Declared in the Variable Manager with a stored value: inserted without asking. */
        STORED,
        /** Declared without a value: asked for every time. */
        ASKS,
        /** Not declared: stays as written, and a scheduled or swarm run refuses the snippet. */
        UNDECLARED
    }

    /**
     * One placeholder name with the number of its (unescaped) occurrences and the position of the
     * first one: {@code start}/{@code end} cover the whole {@code ${name}}, {@code line} is 1-based.
     */
    public record Placeholder(String name, Status status, int occurrences, int start, int end, int line) {
    }

    private SnippetPlaceholderScanner() {
    }

    /**
     * The placeholders of {@code content} in order of first appearance; a declared name is listed
     * once however it is cased.
     *
     * @param declarations which names the Variable Manager declares; {@code null} means none
     * @param values       the stored value of a declared name, or {@code null}/empty when it has none
     */
    public static List<Placeholder> scan(String content, SnippetPlaceholderResolver.Declarations declarations,
                                         Function<String, String> values) {
        List<Placeholder> out = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return out;
        }
        SnippetPlaceholderResolver.Declarations declared = declarations != null
            ? declarations : SnippetPlaceholderResolver.Declarations.none();
        Set<String> undeclared = new HashSet<>(SnippetPlaceholderResolver.undeclaredSimpleNames(content, declared));
        Map<String, int[]> found = new LinkedHashMap<>();
        Map<String, String> shownName = new LinkedHashMap<>();
        Map<String, Status> statusOf = new LinkedHashMap<>();
        Matcher matcher = SnippetPlaceholderResolver.PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                continue;
            }
            String name = matcher.group(2);
            Status status = statusOf(name, declared, values, undeclared);
            if (status == null) {
                continue;
            }
            String key = status == Status.STORED || status == Status.ASKS ? name.toLowerCase(Locale.ROOT) : name;
            int[] entry = found.get(key);
            if (entry == null) {
                found.put(key, new int[] {1, matcher.start(), matcher.end()});
                shownName.put(key, name);
                statusOf.put(key, status);
            } else {
                entry[0]++;
            }
        }
        for (Map.Entry<String, int[]> entry : found.entrySet()) {
            int[] data = entry.getValue();
            out.add(new Placeholder(shownName.get(entry.getKey()), statusOf.get(entry.getKey()), data[0], data[1],
                data[2], lineAt(content, data[1])));
        }
        return out;
    }

    private static Status statusOf(String name, SnippetPlaceholderResolver.Declarations declared,
                                   Function<String, String> values, Set<String> undeclared) {
        if (SnippetPlaceholderResolver.BUILT_IN_NAMES.contains(name) || SnippetPlaceholderResolver.CURSOR.equals(name)) {
            return Status.BUILT_IN;
        }
        if (declared.isDeclared(name)) {
            String value = values != null ? values.apply(name) : null;
            return value != null && !value.isEmpty() ? Status.STORED : Status.ASKS;
        }
        return undeclared.contains(name) ? Status.UNDECLARED : null;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
