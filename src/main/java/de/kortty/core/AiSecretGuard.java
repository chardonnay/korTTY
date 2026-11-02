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
 *       that looks like a real secret, and a cue followed directly by such a value, as in "Passwort hunter2".</li>
 *   <li>High-entropy tokens of 20 characters or more: base64 at 4.5 bits per character, hex at 3.0
 *       bits, as detect-secrets does.</li>
 *   <li>Known secret values, for example the saved passwords of an unlocked vault.</li>
 * </ol>
 *
 * <p>Three kinds of value are not secrets: a number of up to six digits after a token, API key or secret name ({@code max
 * tokens: 4096} is a count); a category word after a plural credential word followed by "sind", "are" or "were"
 * ({@code Passwords are managed by IT} names a category, while "Passwords are Swordfish" names a value); and the value of
 * PWD, the shell's working directory, when it is a path of path characters ({@code PWD=/tmp}, {@code PWD=~/bin}).</p>
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
        "(?iu)(?<cue>passw[oö]rt\\w{0,3}|kennw[oö]rt\\w{0,3}|password\\w{0,3}|passwd"
            + "|(?<![\\p{L}\\p{N}])pwd|pin|passcode|passphrase"
            + "|zugangsdaten|credentials|api[ -]?keys?|secrets?|tokens?)"
            + "(?:(?<sentence>\\s+(?:ist|lautet|lauten|is|sind|are|were)(?:\\s*:\\s*|\\s+))|\\s*[=:]\\s*)"
            + "(?<value><[^<>\\n]*>|\\{\\{[^{}\\n]*}}|\\S+)");

    /**
     * A cue word with no connector, as in "Passwort hunter2" or "Zugangsdaten root / hunter2". The value is the next
     * word, or the word after one user name and a "/" or "," separator; it counts only when it looks like a secret
     * (a digit or a symbol such as "@" inside the word), so "Passwort ändern" and "Pin NuGet" stay prose.
     */
    private static final Pattern BARE_CUE = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}])(?<cue>passw[oö]rt\\w{0,3}|kennw[oö]rt\\w{0,3}|password\\w{0,3}|passwd|pwd|passphrase"
            + "|passcode|pin|zugangsdaten|credentials|api[ -]?keys?|secrets?|tokens?)\\s+"
            + "(?!(?:ist|is|lautet|lauten|sind|are|were)\\b)"
            + "(?:[^\\s/&,<>{}\\[\\]]+\\s*[/&,]\\s*)?(?<value>[^\\s<>{}\\[\\]]+)");

    private static final Pattern SECRET_ASSIGNMENT_NAME = Pattern.compile(
        "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z0-9_]*(?:password|passwd|passphrase|secret|token|api[_-]?key|access_?key"
            + "|secret_?key|private_?key|(?:encryption|signing|session)[_-]?key)"
            + "|(?:[A-Za-z0-9_-]*[_-])?(?:pass|pw|pwd))[\"']?\\s*[=:]");

    /**
     * A path as a value: {@code /usr/local/bin}, {@code ~/bin/x}, {@code ./run/x}. It needs a second slash and only
     * path characters, so {@code /Sommer2026!} and {@code ~Sommer2026} are values, not paths.
     */
    private static final Pattern PATH_SHAPED = Pattern.compile(
        "(?:\\./|\\.\\./|[/~])[A-Za-z0-9._~-]*/[A-Za-z0-9._~/-]*");

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
     * Code expressions, judged on the value as written (trailing {@code ;}, {@code ,} or {@code .} aside): a Perl or shell
     * variable with a letters-only name and its subscripts ({@code $1}, {@code $ARGV[0]}, {@code $self->{token}}), or a
     * call or subscript on a letters-only, possibly dotted name ({@code get_token()}, {@code getpass.getpass()},
     * {@code config["api_key"]}). A call whose argument starts with a digit ({@code Winter(2024)}), a digit in the name
     * ({@code $Hunter2Pass}, {@code Summer2026(!)}) or text after the closing bracket ({@code Winter(2024)!}) makes it a
     * secret. A dotted name without a call or subscript is code only when its first part is a lower-case name, as in
     * {@code var.db_password} or {@code config.DB_PASSWORD}; a capitalised first part ({@code Qwerty.Admin}) is a value.
     */
    private static final Pattern CODE_EXPRESSION = Pattern.compile(
        "(?:\\$(?:[A-Za-z_]+|\\d+|[@#!?*])|[@%][A-Za-z_]+|@_)"
            + "(?:(?:->)?(?:\\{[^{}]*}|\\[[^\\[\\]]*])|->[A-Za-z_]\\w*(?:\\([^()]*\\)?)?)*"
            + "|[A-Za-z_]+(?:(?:\\.|::|->)[A-Za-z_]+)*"
            + "(?:\\((?!\\d)[^()]*\\)?|\\[[^\\[\\]]*]?)"
            + "(?:(?:\\.|->)[A-Za-z_]\\w*|\\((?!\\d)[^()]*\\)?|\\[[^\\[\\]]*]?)*"
            + "|[a-z_]\\w*(?:(?:\\.|::)[A-Za-z_]\\w*)+");

    /** Shorter assigned values are operators or code fragments ({@code =>}, {@code $1}); the vault floor. */
    private static final int MIN_ASSIGNED_VALUE_LENGTH = 4;

    private static final Pattern MASK_ONLY = Pattern.compile("[*xX.\\u2026_-]+");

    /**
     * A number with an optional short unit: a count or a size, such as {@code 4096}, {@code 8k}, {@code 1_000},
     * {@code 2.5} or {@code 128MB}. After a token, API key or secret cue it is not a secret when it has at most
     * {@link #MAX_COUNT_DIGITS} digits: {@code secret: 8472901635} is a value.
     */
    private static final Pattern NUMERIC_VALUE = Pattern.compile("(?i)\\d[\\d_.,']*(?:[kmgt]i?b?|b)?");
    /** The most digits a count or a size may have; a longer number after a secret-like name is a value. */
    private static final int MAX_COUNT_DIGITS = 6;
    /** A shell working directory as a value: an absolute path or a path from the home directory, of path characters. */
    private static final Pattern WORKING_DIRECTORY_VALUE = Pattern.compile("/[A-Za-z0-9._~/-]*|~(?:/[A-Za-z0-9._~/-]*)?");

    /** The plural names of a password set or credentials, in the normalised form of {@link #pluralCredentialCue}. */
    private static final Pattern PLURAL_CREDENTIAL_CUE = Pattern.compile(
        "passw(?:ords|orter|ortern|orte)|kennw(?:orter|ortern|orte)|credentials|zugangsdaten");

    /** The "/", "&amp;" or "," that joins a user name to its password, as in "root / Sommer2026!", and the word after it. */
    private static final Pattern CREDENTIAL_PAIR_TAIL = Pattern.compile("[ \\t]*[/&,][ \\t]*([^\\s<>{}\\[\\]]+)");

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
        "auch", "noch", "dann", "wie", "so", "im", "vom", "zum", "zur", "bei",
        "wrong", "correct", "right", "valid", "invalid", "expired", "missing", "known", "unknown", "falsch", "richtig",
        "gültig", "ungültig", "abgelaufen", "bekannt", "unbekannt", "fehlt");

    /**
     * Words that name what happens to a set of passwords or credentials, as in "Passwords are managed by IT". Only after a
     * plural credential word and "sind", "are" or "were" is one of them an ordinary word; any other value is checked.
     */
    private static final Set<String> CATEGORY_WORDS = Set.of(
        "managed", "handled", "reset", "changed", "rotated", "protected", "encrypted", "hashed", "locked", "enforced",
        "verwaltet", "geändert", "zurückgesetzt", "geschützt", "verschlüsselt", "gehasht", "gesperrt", "erneuert",
        "rotiert");

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
        SecretTokenPatterns.findNamedValues(line, named -> {
            String cleaned = clean(named.value());
            if (cleaned.codePointCount(0, cleaned.length()) >= MIN_ASSIGNED_VALUE_LENGTH
                && !isPlaceholderOrCode(named.value())
                && !isCountOrSizeAssignment(named, cleaned)) {
                hits.add(new Hit(assignment, named.value()));
            }
        });
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

    /** One prose or bare-cue secret: where its cue starts and where its value lies in the text. */
    private record ProseMatch(int cueStart, int valueStart, int valueEnd, String value) {
    }

    /**
     * Prose cues, matched over the whole text so that a value on the line after its cue is seen too. A cue
     * followed by "ist", "is", "sind" or "are" needs a value that is not only letters, unless the cue is a token or
     * API-key cue: "the token is valid" is prose, "Passwort ist Sommer2026" and "The password is swordfish" are not.
     */
    private static List<Finding> proseFindings(String text, List<KnownSecret> known) {
        List<Finding> findings = new ArrayList<>();
        List<ProseMatch> matches = proseMatches(text);
        if (matches.isEmpty()) {
            return findings;
        }
        String[] lines = text.split("\n", -1);
        int[] starts = lineStarts(text);
        for (ProseMatch match : matches) {
            int line = lineOf(starts, match.cueStart());
            findings.add(new Finding(Category.PROSE_SECRET, line,
                excerpt(lineAt(lines, line), List.of(match.value()), known)));
        }
        return findings;
    }

    /** The prose and bare-cue matches that count as secrets, in text order. */
    private static List<ProseMatch> proseMatches(String text) {
        List<ProseMatch> matches = new ArrayList<>();
        Matcher cue = PROSE_CUE.matcher(text);
        while (cue.find()) {
            String value = cue.group("value");
            String cleaned = clean(value);
            String name = cue.group("cue");
            if (cleaned.codePointCount(0, cleaned.length()) < MIN_PROSE_VALUE_LENGTH
                || isPlaceholderOrCode(value)
                || PROSE_STOPWORDS.contains(cleaned.toLowerCase(Locale.ROOT))
                || isCountOrSizeValue(name, cleaned)
                || isWorkingDirectoryAssignment(text, cue.start("cue"), cue.end("cue"), value)
                || (isOrIst(cue.group("sentence")) && proseOnlyCue(name)
                    && cleaned.chars().allMatch(Character::isLetter))
                || (isPluralVerb(cue.group("sentence")) && pluralCredentialCue(name)
                    && CATEGORY_WORDS.contains(cleaned.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            int valueEnd = pairEnd(text, cue.end("value"));
            matches.add(new ProseMatch(cue.start(), cue.start("value"), valueEnd,
                text.substring(cue.start("value"), valueEnd)));
        }
        Matcher bare = BARE_CUE.matcher(text);
        while (bare.find()) {
            String value = bare.group("value");
            String cleaned = clean(value);
            String name = bare.group("cue");
            if (cleaned.codePointCount(0, cleaned.length()) < MIN_PROSE_VALUE_LENGTH
                || isPlaceholderOrCode(value)
                || PROSE_STOPWORDS.contains(cleaned.toLowerCase(Locale.ROOT))
                || isCountOrSizeValue(name, cleaned)
                || isWorkingDirectoryAssignment(text, bare.start("cue"), bare.end("cue"), value)
                || !looksLikeBareSecret(cleaned)) {
                continue;
            }
            matches.add(new ProseMatch(bare.start(), bare.start("value"), bare.end("value"), value));
        }
        return matches;
    }

    /**
     * A number after a token, API key or secret name is a count or a size, not a secret: "max tokens: 4096" is prose.
     * The password, PIN and passcode names still check a number, so "PIN: 1234" is refused.
     */
    private static boolean isCountOrSizeValue(String name, String cleaned) {
        return isCountFamilyName(name) && isCountNumber(cleaned);
    }

    /** The same rule for a {@code NAME=value} assignment, judged by the name in front of the value. */
    private static boolean isCountOrSizeAssignment(SecretTokenPatterns.NamedValue named, String cleaned) {
        return SecretTokenPatterns.SECRET_ASSIGNMENT_RULE.equals(named.rule())
            && isCountFamilyName(named.keep())
            && isCountNumber(cleaned);
    }

    /** A short number with an optional unit: at most {@link #MAX_COUNT_DIGITS} digits. */
    private static boolean isCountNumber(String cleaned) {
        return NUMERIC_VALUE.matcher(cleaned).matches()
            && cleaned.chars().filter(Character::isDigit).count() <= MAX_COUNT_DIGITS;
    }

    /** Token, API key and secret names without any password word: the names whose number is a count or a size. */
    private static boolean isCountFamilyName(String name) {
        String letters = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return (letters.contains("token") || letters.contains("apikey") || letters.contains("secret"))
            && !letters.contains("pass") && !letters.contains("pw");
    }

    /**
     * PWD named on its own, as the shell's working directory: {@code PWD=/tmp}, {@code export PWD=~/bin} or
     * {@code PWD: /srv}. The value must be a path of path characters, so {@code PWD=/Sommer2026!} and
     * {@code pwd: ~Winter2025x} are values. A name that merely ends in PWD ({@code MYSQL_PWD}) is a password name. OLDPWD
     * never reaches here, because its PWD has a letter in front of it. {@code raw} is the value as matched.
     */
    private static boolean isWorkingDirectoryAssignment(String text, int start, int end, String raw) {
        if (end - start != 3 || !text.regionMatches(true, start, "pwd", 0, 3)) {
            return false;
        }
        if (start > 0) {
            char before = text.charAt(start - 1);
            if (Character.isLetterOrDigit(before) || before == '_' || before == '-') {
                return false;
            }
        }
        String path = raw.replaceAll("^[\"'`*(\\[]+", "").replaceAll("[\"'`,;]+$", "").strip();
        return WORKING_DIRECTORY_VALUE.matcher(path).matches();
    }

    /**
     * The end of a prose value that goes on past its first word: in "root / Sommer2026!" the second word has a digit or a
     * symbol and is part of the secret, so the mask covers both. A second word of plain letters is left out of the mask.
     */
    private static int pairEnd(String text, int valueEnd) {
        Matcher pair = CREDENTIAL_PAIR_TAIL.matcher(text);
        pair.region(valueEnd, text.length());
        if (pair.lookingAt() && !clean(pair.group(1)).chars().allMatch(Character::isLetter)) {
            return pair.end();
        }
        return valueEnd;
    }

    /** The plural password and credential names: "passwords", "Passwörter", "Kennwörter", "credentials", "Zugangsdaten". */
    private static boolean pluralCredentialCue(String cue) {
        String name = cue == null ? "" : cue.toLowerCase(Locale.ROOT).replace('ö', 'o').replaceAll("[^a-z]", "");
        return PLURAL_CREDENTIAL_CUE.matcher(name).matches();
    }

    /**
     * The text with the value of every prose or bare-cue secret replaced by the mask, so that outbound text never
     * carries "Das Passwort lautet: …" in clear. Unchanged when there is none.
     */
    static RedactionResult maskProseSecrets(String text, java.util.function.Consumer<String> maskedValues) {
        if (text == null || text.isEmpty()) {
            return RedactionResult.unchanged(text);
        }
        List<ProseMatch> matches = proseMatches(text).stream()
            .sorted(Comparator.comparingInt(ProseMatch::valueStart))
            .toList();
        StringBuilder masked = new StringBuilder();
        int position = 0;
        int count = 0;
        for (ProseMatch match : matches) {
            if (match.valueStart() < position) {
                continue;
            }
            masked.append(text, position, match.valueStart()).append(MASK);
            position = match.valueEnd();
            count++;
            if (maskedValues != null) {
                maskedValues.accept(match.value());
            }
        }
        if (count == 0) {
            return RedactionResult.unchanged(text);
        }
        masked.append(text, position, text.length());
        return new RedactionResult(masked.toString(), count);
    }

    /** A value after a cue without connector: a digit or a symbol that passwords use ("hunter2", "p@ss"), never letters alone. */
    private static boolean looksLikeBareSecret(String cleaned) {
        for (int index = 0; index < cleaned.length(); index++) {
            char c = cleaned.charAt(index);
            if (Character.isDigit(c) || "@#%+=!~^".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Multi-line secrets, such as a private-key block, are only visible in the whole text. */
    private static List<Finding> multiLineFindings(String text, List<KnownSecret> known) {
        if (text.indexOf('\n') < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        SecretTokenPatterns.findValues(text, values::add);
        List<Finding> findings = new ArrayList<>();
        int[] starts = lineStarts(text);
        for (String value : new LinkedHashSet<>(values)) {
            int start = value.indexOf('\n') >= 0 ? text.indexOf(value) : -1;
            if (start < 0) {
                continue;
            }
            int line = lineOf(starts, start);
            String masked = text.substring(0, start) + MASK + text.substring(start + value.length());
            findings.add(new Finding(Category.TOKEN_FORMAT, line,
                excerpt(lineAt(masked.split("\n", -1), line), List.of(), known)));
        }
        return findings;
    }

    /** Offset of the first character of every line; {@link #lineOf} maps an offset to its 1-based line by search. */
    private static int[] lineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int index = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[index++] = i + 1;
            }
        }
        return starts;
    }

    /** The 1-based line that holds {@code offset}. */
    private static int lineOf(int[] starts, int offset) {
        int index = Arrays.binarySearch(starts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /**
     * The random-looking tokens of a line. A token that joins a name and a value with {@code =} ({@code data=<hex>},
     * {@code sha256=<hex>}) is judged by its parts, so the value is not diluted by the name and the hex threshold applies.
     */
    private static List<String> entropyTokens(String line) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = ENTROPY_TOKEN.matcher(line);
        while (matcher.find()) {
            for (String part : matcher.group().split("=")) {
                if (!part.isEmpty()) {
                    tokens.add(part);
                }
            }
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

    /** Whether the connector is "ist", "is", "sind", "are" or "were": the forms that can introduce an ordinary word. */
    private static boolean isOrIst(String sentence) {
        String connector = connector(sentence);
        return connector.equals("ist") || connector.equals("is") || connector.equals("sind")
            || connector.equals("are") || connector.equals("were");
    }

    /** Whether the connector is a plural verb: "sind", "are" or "were", as in "Passwords are" or "Zugangsdaten sind". */
    private static boolean isPluralVerb(String sentence) {
        String connector = connector(sentence);
        return connector.equals("sind") || connector.equals("are") || connector.equals("were");
    }

    /** The connector word of a prose match, lower-cased, without spaces and colons; empty when there is none. */
    private static String connector(String sentence) {
        return sentence == null ? "" : sentence.replaceAll("[\\s:]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Token, API-key and secret cues: the cues whose value may be an ordinary word after "is" ("the token is valid").
     * Password cues always check their value.
     */
    private static boolean proseOnlyCue(String cue) {
        String name = cue == null ? "" : cue.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return name.startsWith("token") || name.startsWith("apikey") || name.startsWith("secret");
    }

    /** {@code raw} is the value as matched; placeholders are judged without quotes and punctuation. */
    private static boolean isPlaceholderOrCode(String raw) {
        // The mask is judged before clean() strips its trailing stars: "ghp_***" is a masked token, not a value.
        if (SecretTokenPatterns.isAlreadyMasked(raw.strip())) {
            return true;
        }
        String cleaned = clean(raw);
        if (cleaned.isEmpty() || CODE_OR_PLACEHOLDER.matcher(cleaned).matches()) {
            return true;
        }
        if (CODE_EXPRESSION.matcher(raw.strip().replaceAll("[;,.]+$", "")).matches()) {
            return true;
        }
        if (MASK_ONLY.matcher(cleaned).matches()
            || SecretTokenPatterns.isAlreadyMasked(cleaned)
            || PLACEHOLDER_WORDS.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return PATH_SHAPED.matcher(cleaned).matches();
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

    private static String lineAt(String[] lines, int lineNumber) {
        return lineNumber - 1 < lines.length ? stripCarriageReturn(lines[lineNumber - 1]) : "";
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

}
