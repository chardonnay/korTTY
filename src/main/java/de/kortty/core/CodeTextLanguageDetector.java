package de.kortty.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which natural language a script's comments and messages are written in.
 *
 * <p>korTTY's AI actions rewrite whole scripts, and until now every rewrite forced that prose into
 * the configured <em>Text language</em>. For a user running korTTY in German that silently
 * translated an English script's comments and output on the first Apply — a change nobody asked
 * for, in a file that may be shared with people who do not read German. The default is now to keep
 * the language the script already uses, which first has to be established.</p>
 *
 * <p>Detection is deliberately conservative. It answers "which language, and how sure am I", and
 * an unsure answer is meant to reach the user as a question rather than a silent guess: rewriting
 * a script's prose into the wrong language is not a mistake the user can easily spot in a diff of
 * their own code.</p>
 *
 * <p>Only the languages korTTY itself speaks can be recognised. A script commented in a language
 * outside that set scores near zero everywhere and comes back {@link Confidence#UNKNOWN}, which is
 * the honest answer — and still lets the user name it in the prompt that follows.</p>
 */
public final class CodeTextLanguageDetector {

    /** Below this many natural-language words there is nothing worth judging. */
    static final int MIN_WORDS = 12;

    /** The winner must carry at least this share of all matched weight to stand on its own. */
    static final double MIN_WINNER_SHARE = 0.45;

    /** ...and must beat the runner-up by this factor, or the two are treated as indistinguishable. */
    static final double MIN_MARGIN = 1.6;

    /** How sure the detector is — anything but {@link #CONFIDENT} means "ask the user". */
    public enum Confidence {
        /** One language clearly dominates the script's prose. */
        CONFIDENT,
        /** Natural-language text exists, but two or more languages score too closely to choose. */
        AMBIGUOUS,
        /** Too little prose to judge, or nothing matching a language korTTY knows. */
        UNKNOWN
    }

    /**
     * @param languageCode the winning ISO code, or {@code null} when there is no usable winner
     * @param confidence whether {@code languageCode} may be used without asking
     * @param wordCount how many natural-language words the sample held, for diagnostics
     */
    public record Detection(String languageCode, Confidence confidence, int wordCount) {

        /** Whether the caller may proceed without asking the user which language to write. */
        public boolean isUsable() {
            return confidence == Confidence.CONFIDENT && languageCode != null;
        }
    }

    private CodeTextLanguageDetector() {
    }

    /**
     * The language a snippet's comments and user-facing strings are written in.
     *
     * @param source the full script
     * @param snippetLanguage korTTY's code-language id, which decides how comments are recognised
     */
    public static Detection detect(String source, String snippetLanguage) {
        String prose = extractProse(source, snippetLanguage);
        List<String> words = words(prose);
        if (words.size() < MIN_WORDS) {
            return new Detection(null, Confidence.UNKNOWN, words.size());
        }
        return score(words, prose, words.size());
    }

    // ---- scoring ---------------------------------------------------------------------------------

    /** Package-private so the arithmetic can be exercised on a word list directly. */
    static Detection score(List<String> words, String prose, int wordCount) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : MARKERS.entrySet()) {
            double hits = 0;
            for (String word : words) {
                if (entry.getValue().contains(word)) {
                    hits++;
                }
            }
            scores.put(entry.getKey(), hits);
        }
        // Script-specific characters are strong evidence and survive even a short sample: no amount
        // of English function words explains an "ß" or a "ć".
        for (Map.Entry<String, String> entry : DISTINCTIVE_CHARACTERS.entrySet()) {
            double bonus = 0;
            for (int i = 0; i < prose.length(); i++) {
                if (entry.getValue().indexOf(Character.toLowerCase(prose.charAt(i))) >= 0) {
                    bonus += 1.5;
                }
            }
            scores.merge(entry.getKey(), bonus, Double::sum);
        }

        double total = scores.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return new Detection(null, Confidence.UNKNOWN, wordCount);
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((left, right) -> Double.compare(right.getValue(), left.getValue()));

        double best = ranked.get(0).getValue();
        double runnerUp = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        if (best <= 0) {
            return new Detection(null, Confidence.UNKNOWN, wordCount);
        }
        boolean dominant = best / total >= MIN_WINNER_SHARE
            && (runnerUp <= 0 || best >= runnerUp * MIN_MARGIN);
        return new Detection(
            ranked.get(0).getKey(),
            dominant ? Confidence.CONFIDENT : Confidence.AMBIGUOUS,
            wordCount);
    }

    /** Alphabetic tokens of two or more letters, lower-cased; digits and punctuation are dropped. */
    static List<String> words(String prose) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(prose != null ? prose : "");
        while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    // ---- prose extraction ------------------------------------------------------------------------

    /**
     * The natural-language part of a script: its comments plus its string literals, which is where
     * user-facing messages live. Code between them is left out, so the word counts are not diluted
     * by identifiers.
     *
     * <p>Comment syntax follows the snippet's code language. Getting it slightly wrong is not
     * dangerous here — a missed comment style only lowers the word count, and too few words is
     * already a "do not guess" answer.</p>
     */
    static String extractProse(String source, String snippetLanguage) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String language = snippetLanguage != null ? snippetLanguage.toLowerCase(Locale.ROOT) : "";
        StringBuilder prose = new StringBuilder();

        for (Pattern pattern : commentPatterns(language)) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                prose.append(matcher.group(matcher.groupCount())).append('\n');
            }
        }
        Matcher strings = STRING_LITERAL.matcher(source);
        while (strings.find()) {
            String literal = strings.group(1) != null ? strings.group(1) : strings.group(2);
            // A literal without a space is a path, a flag or a format token, never a message.
            if (literal != null && literal.contains(" ")) {
                prose.append(literal).append('\n');
            }
        }
        return prose.toString();
    }

    private static List<Pattern> commentPatterns(String language) {
        List<Pattern> patterns = new ArrayList<>();
        if (C_STYLE.contains(language)) {
            patterns.add(SLASH_COMMENT);
            patterns.add(BLOCK_COMMENT);
        } else if (MARKUP.contains(language)) {
            patterns.add(MARKUP_COMMENT);
        } else if (SQL_LIKE.contains(language)) {
            patterns.add(DASH_COMMENT);
            patterns.add(BLOCK_COMMENT);
        } else if (language.isEmpty()) {
            // Unknown code language: cast the net wide rather than miss every comment. A pattern
            // that does not apply simply finds nothing.
            patterns.add(HASH_COMMENT);
            patterns.add(SLASH_COMMENT);
            patterns.add(BLOCK_COMMENT);
            patterns.add(MARKUP_COMMENT);
        } else {
            patterns.add(HASH_COMMENT);
        }
        return patterns;
    }

    private static final Set<String> C_STYLE = Set.of(
        "java", "javascript", "typescript", "groovy", "c", "cpp", "csharp", "go", "rust", "php",
        "kotlin", "scala", "swift", "css");
    private static final Set<String> MARKUP = Set.of("xml", "html", "markdown");
    private static final Set<String> SQL_LIKE = Set.of("sql", "lua");

    private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");
    private static final Pattern HASH_COMMENT = Pattern.compile("(?m)#(.*)$");
    private static final Pattern SLASH_COMMENT = Pattern.compile("(?m)//(.*)$");
    private static final Pattern DASH_COMMENT = Pattern.compile("(?m)--(.*)$");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*(.*?)\\*/");
    private static final Pattern MARKUP_COMMENT = Pattern.compile("(?s)<!--(.*?)-->");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\n]{4,})\"|'([^'\\n]{4,})'");

    /**
     * A marker set that tolerates a repeated word. {@code Set.of} throws on a duplicate, which for
     * a hand-maintained word list means a typo takes the whole class down at load time rather than
     * being the harmless no-op it actually is.
     */
    private static Set<String> markers(String... words) {
        return Set.copyOf(java.util.Arrays.asList(words));
    }

    /**
     * High-frequency function words per language. Function words are used because they are what a
     * writer cannot avoid — unlike topic vocabulary, which in a script is mostly English regardless
     * of the language the comments are written in.
     */
    private static final Map<String, Set<String>> MARKERS = new LinkedHashMap<>(Map.of(
        "en", markers("the", "and", "not", "for", "this", "with", "from", "that", "will", "must",
            "can", "only", "each", "when", "then", "than", "have", "has", "are", "was", "were",
            "into", "there", "which", "would", "should", "before", "after", "because", "otherwise",
            "file", "error", "output", "value", "check", "does", "used", "using", "all", "any"),
        "de", markers("der", "die", "das", "und", "nicht", "wird", "ist", "ein", "eine", "einen",
            "mit", "auf", "von", "sich", "oder", "wenn", "dann", "muss", "kann", "nur", "noch",
            "schon", "alle", "wurde", "werden", "aber", "auch", "durch", "bei", "zum", "zur",
            "dem", "den", "des", "damit", "sowie", "keine", "wurden", "wird", "datei", "fehler"),
        "nl", markers("het", "een", "van", "niet", "wordt", "zijn", "voor", "met", "aan", "door",
            "maar", "ook", "deze", "dit", "naar", "worden", "moet", "kan", "alle", "geen",
            "bestand", "fout", "waarde", "als", "dan", "bij", "over", "uit", "tot", "nog"),
        "fr", markers("les", "des", "une", "est", "pas", "pour", "dans", "sur", "avec", "que",
            "qui", "par", "plus", "cette", "sont", "être", "faire", "tous", "toutes", "aux",
            "fichier", "erreur", "valeur", "doit", "peut", "mais", "donc", "ainsi", "lorsque"),
        "es", markers("los", "las", "una", "por", "para", "con", "que", "del", "este", "esta",
            "son", "ser", "hacer", "todos", "todas", "más", "pero", "como", "cuando", "sin",
            "archivo", "error", "valor", "debe", "puede", "desde", "hasta", "entre", "aunque"),
        "it", markers("gli", "una", "per", "con", "che", "del", "della", "questo", "questa",
            "sono", "essere", "fare", "tutti", "tutte", "più", "come", "quando", "senza",
            "file", "errore", "valore", "deve", "può", "dopo", "prima", "anche", "perché"),
        "pt", markers("dos", "das", "uma", "para", "com", "que", "não", "este", "esta", "são",
            "ser", "fazer", "todos", "todas", "mais", "mas", "como", "quando", "sem",
            "arquivo", "ficheiro", "erro", "valor", "deve", "pode", "desde", "até", "entre"),
        "hr", markers("koji", "koja", "koje", "nije", "ili", "kada", "ako", "sve", "svi", "samo",
            "mora", "može", "datoteka", "greška", "greska", "vrijednost", "sa", "za", "iz",
            "prije", "poslije", "nakon", "ovo", "ova", "ovaj", "nema", "biti", "treba")));

    /** Characters that only occur in one of the supported languages, weighted heavily. */
    private static final Map<String, String> DISTINCTIVE_CHARACTERS = Map.of(
        "de", "äöüß",
        "es", "ñ¿¡",
        "pt", "ãõ",
        "hr", "čćžšđ");
}
