package de.kortty.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether text may be stored in a file the AI reads back: AI skills and the korTTY memory.
 *
 * <p>Four layers. The first hit refuses the whole text, and there is no override:</p>
 * <ol>
 *   <li>Known secret formats from {@link SecretTokenPatterns}, except values that are code or a
 *       placeholder: {@code my $password = shift;} is fine, {@code $password = hunter2;} is not.</li>
 *   <li>Prose cues such as "Passwort ist …", "password: …" or "API key = …" followed by a value
 *       that looks like a real secret.</li>
 *   <li>High-entropy tokens of 20 characters or more: base64 at 4.5 bits per character, hex at 3.0
 *       bits, as detect-secrets does.</li>
 *   <li>Known secret values, for example the saved passwords of an unlocked vault.</li>
 * </ol>
 *
 * <p>Findings carry a 1-based line number and an excerpt in which every secret is masked. The
 * guard cannot recognise a password that has no keyword, no format and no vault entry; the docs
 * name that residual risk.</p>
 */
public final class AiSecretGuard {

    /** What kind of secret a finding reports. */
    public enum Category {
        /** A token in a known format: private key, provider or forge token, bearer or URL password. */
        TOKEN_FORMAT,
        /** A secret-named assignment such as {@code db_password=…} with a literal value. */
        SECRET_ASSIGNMENT,
        /** A German or English prose cue followed by a secret-looking value. */
        PROSE_SECRET,
        /** A long random-looking token. */
        HIGH_ENTROPY,
        /** A value saved in the vault of an unlocked connection. */
        VAULT_VALUE
    }

    /** One refusal: the category, the 1-based line and a masked excerpt that never shows the secret. */
    public record Finding(Category category, int line, String maskedExcerpt) {

        public Finding {
            maskedExcerpt = maskedExcerpt == null ? "" : maskedExcerpt;
        }
    }

    /** The outcome of a check. */
    public record Result(List<Finding> findings) {

        public Result {
            findings = List.copyOf(findings);
        }

        public boolean ok() {
            return findings.isEmpty();
        }

        public Optional<Finding> first() {
            return findings.isEmpty() ? Optional.empty() : Optional.of(findings.get(0));
        }
    }

    /** Shorter known values would match ordinary words; the same bound as the session journal. */
    static final int MIN_KNOWN_SECRET_LENGTH = 4;
    private static final int MIN_ENTROPY_TOKEN_LENGTH = 20;
    private static final double BASE64_ENTROPY_THRESHOLD = 4.5;
    private static final double HEX_ENTROPY_THRESHOLD = 3.0;
    private static final int MIN_PROSE_VALUE_LENGTH = 4;
    private static final int EXCERPT_LENGTH = 120;
    private static final String MASK = SessionJournalRedactor.REPLACEMENT;

    /**
     * A cue word, its connector and the value after it. The cue may be part of a longer key such as
     * {@code db_passwort}, and the value may follow on the next line.
     */
    private static final Pattern PROSE_CUE = Pattern.compile(
        "(?i)(passwort|kennwort|password|passwd|pwd|pin|passcode|passphrase"
            + "|zugangsdaten|credentials|api[ -]?key|secret|token)"
            + "(?:(?<sentence>\\s+(?:ist|lautet|is)\\s+)|\\s*[=:]\\s*)"
            + "(?<value><[^<>\\n]*>|\\{\\{[^{}\\n]*}}|\\S+)");

    private static final Pattern SECRET_ASSIGNMENT_NAME = Pattern.compile(
        "(?i)(password|passwd|passphrase|secret|token|api[_-]?key|access_?key|secret_?key|private_?key)"
            + "[\"']?\\s*[=:]");

    /**
     * Code that reads a secret instead of containing it, and the placeholder shapes. Only the forms the rules
     * name are listed: a value that merely starts with an identifier and a parenthesis is still a secret.
     */
    private static final Pattern CODE_OR_PLACEHOLDER = Pattern.compile(
        "(?i)shift"
            + "|<[^<>]*>"
            + "|\\{\\{.*}}"
            + "|\\$\\{.*}"
            + "|\\$ENV\\{.*}"
            + "|\\$\\(.*"
            + "|%ENV(?:\\{.*})?"
            + "|(?:System\\.)?getenv\\(.*"
            + "|os\\.environ\\[.*"
            + "|process\\.env\\.\\w+"
            + "|ENV\\[.*");

    /**
     * Code expressions, judged on the value as written (trailing {@code ;} or {@code ,} aside): a Perl or shell
     * variable with a letters-only name and its subscripts ({@code $1}, {@code $ARGV[0]}, {@code $self->{token}}),
     * or a call or subscript on a letters-only, possibly dotted name ({@code get_token()},
     * {@code getpass.getpass()}, {@code config["api_key"]}). A digit in the name ({@code $Hunter2Pass},
     * {@code Summer2026(!)}) or text after the closing bracket ({@code Winter(2024)!}) makes it a secret again.
     */
    private static final Pattern CODE_EXPRESSION = Pattern.compile(
        "(?:\\$(?:[A-Za-z_]+|\\d+|[@#!?*])|[@%][A-Za-z_]+|@_)"
            + "(?:(?:->)?(?:\\{[^{}]*}|\\[[^\\[\\]]*])|->[A-Za-z_]\\w*(?:\\([^()]*\\)?)?)*"
            + "|[A-Za-z_]+(?:(?:\\.|::|->)[A-Za-z_]+)*"
            + "(?:\\([^()]*\\)?|\\[[^\\[\\]]*]?)"
            + "(?:(?:\\.|->)[A-Za-z_]\\w*|\\([^()]*\\)?|\\[[^\\[\\]]*]?)*");

    /** Shorter assigned values are operators or code fragments ({@code =>}, {@code $1}); the vault floor. */
    private static final int MIN_ASSIGNED_VALUE_LENGTH = 4;

    private static final Pattern MASK_ONLY = Pattern.compile("[*xX.\\u2026_-]+");

    private static final Set<String> PLACEHOLDER_WORDS = Set.of(
        "dein", "deine", "deinem", "deinen", "deines", "your", "yours", "yourpassword", "your-password",
        "your_password", "passwort", "password", "kennwort", "token", "secret", "geheim", "xxx", "redacted",
        "hidden", "placeholder", "example", "beispiel", "none", "null", "nil", "empty", "leer", "value");

    /** Words that commonly follow "is" or "=" in ordinary prose and are not secrets. */
    private static final Set<String> PROSE_STOPWORDS = Set.of(
        "the", "a", "an", "is", "are", "was", "be", "been", "being", "used", "stored", "set", "not", "in",
        "on", "at", "for", "to", "of", "and", "or", "with", "from", "by", "as", "it", "this", "that", "here",
        "there", "required", "needed", "needs", "kept", "sent", "read", "written", "saved", "shown", "gesetzt",
        "gespeichert", "verwendet", "nicht", "nie", "immer", "kein", "keine", "keinen", "keiner", "auf",
        "der", "die", "das", "ein", "eine", "einem", "einen", "wird", "werden", "sind", "noetig", "erforderlich",
        "auch", "noch", "dann", "wie", "so", "im", "vom", "zum", "zur", "bei");

    private static final Pattern ENTROPY_TOKEN = Pattern.compile(
        "(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{20,}(?![A-Za-z0-9+/=_-])");
    private static final Pattern HEX_TOKEN = Pattern.compile("[0-9a-fA-F]{20,}");

    private static volatile Supplier<Collection<String>> knownSecretsSource;

    private AiSecretGuard() {
    }

    /**
     * Checks {@code text} with layers 1 to 3 and with the known secrets of the installed source.
     */
    public static Result check(String text) {
        return check(text, installedKnownSecrets());
    }

    /** The known secrets of the installed source, read now; empty when none is installed or the vault is locked. */
    static Collection<String> installedKnownSecrets() {
        Supplier<Collection<String>> source = knownSecretsSource;
        return source != null ? source.get() : List.of();
    }

    /**
     * Checks {@code text} with layers 1 to 3 and with the given known secrets (layer 4).
     */
    public static Result check(String text, Collection<String> knownSecrets) {
        if (text == null || text.isBlank()) {
            return new Result(List.of());
        }
        List<KnownSecret> known = compileKnown(knownSecrets);
        String[] lines = text.split("\n", -1);
        List<Finding> findings = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = stripCarriageReturn(lines[index]);
            List<Hit> hits = hitsIn(line, known);
            if (hits.isEmpty()) {
                continue;
            }
            List<String> values = hits.stream().map(Hit::value).toList();
            String excerpt = excerpt(line, values, known);
            for (Hit hit : hits) {
                findings.add(new Finding(hit.category(), index + 1, excerpt));
            }
        }
        findings.addAll(proseFindings(text, known));
        findings.addAll(multiLineFindings(text, known));
        findings.sort(Comparator.comparingInt(Finding::line));
        return new Result(findings);
    }

    /**
     * Sets the source of known secrets that {@link #check(String)} uses, for example the saved
     * passwords of the unlocked vault. Pass {@code null} to remove it. The source must return an
     * empty collection while the vault is locked.
     */
    public static void installKnownSecretsSource(Supplier<Collection<String>> source) {
        knownSecretsSource = source;
    }

    /** One match of one layer; the value is the literal text it matched. */
    private record Hit(Category category, String value) {
    }

    private record KnownSecret(String value, Pattern wholeWord) {
    }

    private static List<Hit> hitsIn(String line, List<KnownSecret> known) {
        List<Hit> hits = new ArrayList<>();
        Category assignment = SECRET_ASSIGNMENT_NAME.matcher(line).find()
            ? Category.SECRET_ASSIGNMENT
            : Category.TOKEN_FORMAT;
        List<String> formatHits = new ArrayList<>();
        SecretTokenPatterns.findValues(line, formatHits::add);
        for (String value : formatHits) {
            String cleaned = clean(value);
            if (cleaned.codePointCount(0, cleaned.length()) >= MIN_ASSIGNED_VALUE_LENGTH
                && !isPlaceholderOrCode(value)) {
                hits.add(new Hit(assignment, value));
            }
        }
        for (String token : entropyTokens(line)) {
            if (highEntropy(token)) {
                hits.add(new Hit(Category.HIGH_ENTROPY, token));
            }
        }
        for (KnownSecret secret : known) {
            if (secret.wholeWord().matcher(line).find()) {
                hits.add(new Hit(Category.VAULT_VALUE, secret.value()));
            }
        }
        return hits;
    }

    /**
     * Prose cues, matched over the whole text so that a value on the line after its cue is seen too. A cue
     * followed by "ist" or "is" needs a value with a digit or a symbol: "the token is valid" is prose,
     * "Passwort ist Sommer2026" is not. "lautet" only ever introduces the value, so any word after it counts.
     */
    private static List<Finding> proseFindings(String text, List<KnownSecret> known) {
        List<Finding> findings = new ArrayList<>();
        Matcher cue = PROSE_CUE.matcher(text);
        while (cue.find()) {
            String value = cue.group("value");
            String cleaned = clean(value);
            if (cleaned.codePointCount(0, cleaned.length()) < MIN_PROSE_VALUE_LENGTH
                || isPlaceholderOrCode(value)
                || PROSE_STOPWORDS.contains(cleaned.toLowerCase(Locale.ROOT))
                || (isOrIst(cue.group("sentence")) && cleaned.chars().allMatch(Character::isLetter))) {
                continue;
            }
            int line = countNewlines(text, cue.start()) + 1;
            findings.add(new Finding(Category.PROSE_SECRET, line, excerpt(lineAt(text, line), List.of(value), known)));
        }
        return findings;
    }

    /** Multi-line secrets, such as a private-key block, are only visible in the whole text. */
    private static List<Finding> multiLineFindings(String text, List<KnownSecret> known) {
        if (text.indexOf('\n') < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        SecretTokenPatterns.findValues(text, values::add);
        List<Finding> findings = new ArrayList<>();
        for (String value : new LinkedHashSet<>(values)) {
            int start = value.indexOf('\n') >= 0 ? text.indexOf(value) : -1;
            if (start < 0) {
                continue;
            }
            int line = countNewlines(text, start) + 1;
            String masked = text.substring(0, start) + MASK + text.substring(start + value.length());
            findings.add(new Finding(Category.TOKEN_FORMAT, line, excerpt(lineAt(masked, line), List.of(), known)));
        }
        return findings;
    }

    private static List<String> entropyTokens(String line) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = ENTROPY_TOKEN.matcher(line);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static boolean highEntropy(String token) {
        if (token.length() < MIN_ENTROPY_TOKEN_LENGTH) {
            return false;
        }
        double bits = shannonBits(token);
        return HEX_TOKEN.matcher(token).matches()
            ? bits >= HEX_ENTROPY_THRESHOLD
            : bits >= BASE64_ENTROPY_THRESHOLD;
    }

    private static double shannonBits(String text) {
        char[] sorted = text.toCharArray();
        Arrays.sort(sorted);
        double bits = 0;
        int start = 0;
        while (start < sorted.length) {
            int end = start;
            while (end < sorted.length && sorted[end] == sorted[start]) {
                end++;
            }
            double probability = (double) (end - start) / sorted.length;
            bits -= probability * (Math.log(probability) / Math.log(2));
            start = end;
        }
        return bits;
    }

    /** A value with quotes, emphasis or trailing punctuation removed, so it is judged by its content. */
    private static String clean(String raw) {
        return raw.replaceAll("^[\"'`*(\\[]+", "").replaceAll("[\"'`*)\\].,;:!?]+$", "").strip();
    }

    private static boolean isOrIst(String sentence) {
        return sentence != null && !sentence.strip().equalsIgnoreCase("lautet");
    }

    /** {@code raw} is the value as matched; placeholders are judged without quotes and punctuation. */
    private static boolean isPlaceholderOrCode(String raw) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty() || CODE_OR_PLACEHOLDER.matcher(cleaned).matches()) {
            return true;
        }
        if (CODE_EXPRESSION.matcher(raw.strip().replaceAll("[;,]+$", "")).matches()) {
            return true;
        }
        if (MASK_ONLY.matcher(cleaned).matches()
            || PLACEHOLDER_WORDS.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return cleaned.startsWith("/") || cleaned.startsWith("~") || cleaned.startsWith("./");
    }

    private static List<KnownSecret> compileKnown(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && value.strip().length() >= MIN_KNOWN_SECRET_LENGTH) {
                distinct.add(value.strip());
            }
        }
        return distinct.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(value -> new KnownSecret(value, Pattern.compile(wholeWordPattern(value))))
            .toList();
    }

    /**
     * The value as a regex that matches it where it is not glued to a word. A word boundary is required only on the
     * side where the value itself starts or ends with a word character, so {@code !Secret1} still matches after
     * {@code alice}.
     */
    private static String wholeWordPattern(String value) {
        String before = isWordCharacter(value.codePointAt(0)) ? "(?<![\\p{L}\\p{N}_])" : "";
        String after = isWordCharacter(value.codePointBefore(value.length())) ? "(?![\\p{L}\\p{N}_])" : "";
        return before + Pattern.quote(value) + after;
    }

    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    /** The line with every secret replaced by the mask, shortened so the excerpt stays readable. */
    private static String excerpt(String line, List<String> values, List<KnownSecret> known) {
        String masked = line;
        for (String value : values) {
            masked = masked.replace(value, MASK);
        }
        for (KnownSecret secret : known) {
            masked = masked.replace(secret.value(), MASK);
        }
        String compact = masked.strip();
        return compact.length() > EXCERPT_LENGTH ? compact.substring(0, EXCERPT_LENGTH - 3) + "..." : compact;
    }

    private static String lineAt(String text, int lineNumber) {
        String[] lines = text.split("\n", -1);
        return lineNumber - 1 < lines.length ? stripCarriageReturn(lines[lineNumber - 1]) : "";
    }

    private static int countNewlines(String text, int end) {
        int count = 0;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

}
