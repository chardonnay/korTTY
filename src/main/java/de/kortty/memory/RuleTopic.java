package de.kortty.memory;

import de.kortty.core.AiSkillMarkdownCodec;
import de.kortty.core.BuiltinAiSkillCatalog;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language or tool a rule is about, found locally. A hint from the caller wins. Otherwise a leading
 * {@code Word:} that names a known topic wins, then the first known topic word in the text. A leading word
 * that is not known is used as a topic too, so a user skill tagged with it can still match. Words of one or two
 * letters are not searched in the text: {@code go} or {@code r} in an ordinary sentence would name a language.
 */
public final class RuleTopic {

    /** Languages and tools that are not in the built-in catalog; a new skill may be created for them. */
    static final Set<String> KNOWN_LANGUAGES_AND_TOOLS = Set.of(
        "elixir", "erlang", "haskell", "scala", "clojure", "ocaml", "f#", "zig", "nim", "julia", "fortran",
        "cobol", "lisp", "racket", "crystal", "matlab", "octave", "pascal", "delphi", "prolog", "solidity",
        "objective-c", "docker", "kubernetes", "terraform", "helm", "git", "cmake", "gradle", "maven", "npm",
        "pip", "cargo", "nginx", "postgresql", "mysql", "sqlite", "redis", "mongodb", "vim", "emacs", "tmux",
        "awk", "sed", "graphql", "protobuf", "bazel", "podman");

    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}#+._-]+");
    private static final Pattern LEADING_WORD = Pattern.compile("^\\s*([\\p{L}\\p{N}#+._-]{2,30})\\s*:");

    private RuleTopic() {
    }

    /**
     * The topic of {@code proposal}, lower case, or empty when the rule names none. {@code builtinTopics} are the
     * topic words of the built-in skills.
     */
    public static Optional<String> of(RuleProposal proposal, Collection<String> builtinTopics) {
        if (proposal.topicHint() != null) {
            return Optional.ofNullable(normalize(proposal.topicHint()));
        }
        Set<String> known = known(builtinTopics);
        String leading = leadingWord(proposal.text());
        if (leading != null && known.contains(leading)) {
            return Optional.of(leading);
        }
        for (String raw : WORD_SPLIT.split(proposal.text())) {
            String word = normalize(raw);
            if (word != null && isSearchable(word) && known.contains(word)) {
                return Optional.of(word);
            }
        }
        return Optional.ofNullable(leading);
    }

    /** Whether {@code topic} is a built-in topic or one of the known languages and tools. */
    public static boolean isLanguageOrTool(String topic, Collection<String> builtinTopics) {
        String normalized = normalize(topic);
        return normalized != null && known(builtinTopics).contains(normalized);
    }

    /** The topic lower case without surrounding space or trailing dots, or {@code null} when nothing is left. */
    public static String normalize(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.strip().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        return normalized.isEmpty() ? null : normalized;
    }

    /** The topic with a capital first letter, for skill names: {@code elixir} becomes {@code Elixir}. */
    public static String displayName(String topic) {
        String normalized = normalize(topic);
        if (normalized == null) {
            return "";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    /** The topic words of the built-in skills, lower case; empty when there is no catalog. */
    public static Set<String> builtinTopicsOf(BuiltinAiSkillCatalog catalog) {
        Set<String> topics = new HashSet<>();
        if (catalog != null) {
            for (AiSkillMarkdownCodec.BundledAiSkill entry : catalog.entries()) {
                for (String topic : entry.skill().getBuiltinTopics()) {
                    String normalized = normalize(topic);
                    if (normalized != null) {
                        topics.add(normalized);
                    }
                }
            }
        }
        return topics;
    }

    /** The name of the skill korTTY creates for {@code topic}: "Elixir (korTTY)". */
    public static String skillName(String topic) {
        return displayName(topic) + " (korTTY)";
    }

    private static Set<String> known(Collection<String> builtinTopics) {
        Set<String> known = new HashSet<>(KNOWN_LANGUAGES_AND_TOOLS);
        for (String topic : builtinTopics != null ? builtinTopics : List.<String>of()) {
            String normalized = normalize(topic);
            if (normalized != null) {
                known.add(normalized);
            }
        }
        return known;
    }

    private static String leadingWord(String text) {
        Matcher matcher = LEADING_WORD.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : null;
    }

    /** A word in the text counts when it has three letters, or a symbol such as {@code c#} or {@code c++}. */
    private static boolean isSearchable(String word) {
        return word.length() >= 3 || (word.length() == 2 && word.chars().anyMatch(c -> c == '#' || c == '+' || c == '.'));
    }
}
