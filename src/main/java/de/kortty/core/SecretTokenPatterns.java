package de.kortty.core;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Well-known secret formats that can turn up in terminal output: private-key blocks, cloud and
 * forge access tokens, bearer tokens, JWTs, passwords in URLs and {@code *_PASSWORD=} style
 * assignments.
 *
 * <p>Each match keeps a harmless prefix — the key's BEGIN line, a token's type prefix such as
 * {@code ghp_}, the variable name, the URL's user — and replaces the secret part with
 * {@link SessionJournalRedactor#REPLACEMENT}, so the masked text still says what was there. A
 * value that already contains the replacement (masked by an earlier pass or rule) is left alone
 * and not counted again.</p>
 *
 * <p>This is pattern matching, not a guarantee: a secret in a format the patterns do not know
 * passes through. The rules are ordered from the most specific to the most generic, so for
 * example {@code GITHUB_TOKEN=ghp_...} becomes {@code GITHUB_TOKEN=ghp_***} and counts once.</p>
 */
public final class SecretTokenPatterns {

    private static final String KEEP = "keep";
    private static final String SECRET = "secret";
    private static final String TAIL = "tail";

    /** The name of the rule that finds {@code NAME=value} and {@code name: value} assignments of secret-named keys. */
    static final String SECRET_ASSIGNMENT_RULE = "secret-assignment";

    /**
     * A secret part with the text in front of it on the same match ({@code keep}, such as {@code max_token = }) and the
     * name of the rule that matched it, for a caller that judges a value by the name it is assigned to.
     */
    record NamedValue(String rule, String keep, String value) {
    }

    /**
     * One pattern with the named groups {@code keep} (left as is), {@code secret} (masked) and,
     * optionally, {@code tail} (left as is, after the mask).
     */
    private record Rule(String name, Pattern pattern, boolean hasTail) {

        static Rule of(String name, String regex) {
            Pattern pattern = Pattern.compile(regex);
            return new Rule(name, pattern, pattern.namedGroups().containsKey(TAIL));
        }
    }

    private static final List<Rule> RULES = List.of(
        // PEM, OpenSSH and PGP private keys. An unterminated block (selection ends inside the key)
        // is masked to the end of the text.
        Rule.of("private-key-block",
            "(?<keep>-----BEGIN (?<kind>(?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?)-----\\r?\\n?)"
                + "(?<secret>[\\s\\S]*?)"
                + "(?<tail>\\r?\\n?-----END \\k<kind>-----|\\z)"),
        // "Authorization: Bearer|Basic|Token <credentials>", also as a curl -H argument or JSON.
        Rule.of("authorization-header",
            "(?i)(?<keep>\\bauthorization[\"']?[ \\t]*[:=][ \\t]*[\"']?(?:bearer|basic|token|bot)[ \\t]+)"
                + "(?<secret>[^\\s\"']+)"),
        Rule.of("aws-access-key-id",
            "(?<![A-Za-z0-9])(?<keep>AKIA|ASIA|ABIA|ACCA)(?<secret>[A-Z0-9]{16})(?![A-Za-z0-9])"),
        Rule.of("aws-secret-access-key",
            "(?i)(?<keep>\\baws_?secret_?access_?key[\"']?[ \\t]*[=:][ \\t]*[\"']?)"
                + "(?<secret>[A-Za-z0-9/+=]{16,})"),
        Rule.of("github-token",
            "(?<![A-Za-z0-9_])(?<keep>gh[pousr]_|github_pat_)(?<secret>[A-Za-z0-9_]{20,})"),
        Rule.of("gitlab-token",
            "(?<![A-Za-z0-9_-])(?<keep>gl(?:pat|rt|dt|ptt)-)(?<secret>[A-Za-z0-9_-]{20,}(?:\\.[A-Za-z0-9_-]+)*)"),
        Rule.of("slack-token",
            "(?<![A-Za-z0-9])(?<keep>xox[abposr]-)(?<secret>[A-Za-z0-9-]{10,})"),
        // OpenAI-style and Anthropic provider keys (sk-..., sk-proj-..., sk-ant-...).
        Rule.of("provider-api-key",
            "(?<![A-Za-z0-9_-])(?<keep>sk-(?:ant-|proj-)?)(?<secret>[A-Za-z0-9_-]{20,})"),
        Rule.of("jwt",
            "(?<![A-Za-z0-9_-])(?<keep>eyJ)(?<secret>[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*)"),
        // A bare "Bearer <token>" outside a header. 20+ token characters, so the word in prose
        // ("the bearer of the key") never matches.
        Rule.of("bearer-token",
            "(?<keep>\\b(?i:bearer)[ \\t]+)(?<secret>[A-Za-z0-9._~+/-]{20,}=*)"),
        // scheme://user:password@host keeps the scheme and the user. The match starts at "://":
        // matching the scheme too ([A-Za-z][A-Za-z0-9+.-]*) re-scanned every dotted run from each
        // word boundary, quadratic on a long "a.b.c..." line. The password runs to the last '@'
        // before the path, so an unencoded '@' in it does not leave its tail in the clear.
        Rule.of("url-credentials",
            "(?<keep>://[^\\s:/@\"'<>]*:)(?<secret>[^\\s/\"'<>]+)(?<tail>@)"),
        // NAME=value / name: value where the name ends in a secret word. PASSWORD_MIN_LENGTH=8
        // does not match (the name must end in the word), and OLDPWD is deliberately absent.
        // "pass", "pw" and "pwd" are names only as KEY=value (PASS=, --pass=, MYSQL_PWD=), as
        // DB_PASS: or as a YAML key at the start of a line; "make runs pass: keep" is prose.
        // A quoted value may contain spaces; a bare one ends at whitespace, a quote, ',' or ';'.
        // "api-key" also takes the hyphen, for the X-Api-Key header and --api-key= flags.
        Rule.of(SECRET_ASSIGNMENT_RULE,
            "(?im)(?<![A-Za-z0-9_])(?<keep>"
                + "(?:[A-Za-z0-9_]*(?:password|passwd|passphrase|secret|token|api[_-]?key|access_?key|secret_?key"
                + "|private_?key|(?:encryption|signing|session)[_-]?key)[\"']?[ \\t]*[=:][ \\t]*[\"']?"
                + "|(?:[A-Za-z0-9_-]*[_-])?(?:pass|pw)[\"']?[ \\t]*=[ \\t]*[\"']?"
                + "|[A-Za-z0-9_-]*[_-](?:pass|pw)[\"']?[ \\t]*:[ \\t]*[\"']?"
                + "|^[ \\t]*(?:-[ \\t]*)?(?:pass|pw)[\"']?[ \\t]*:[ \\t]*[\"']?)"
                + ")"
                + "(?<secret>(?<=[\"'])[^\"'\\r\\n]+|(?<![\"'])[^\\s\"'`,;]+)"),
        // PWD is also the shell's working directory: a value that is a path of path characters (PWD=/tmp, export PWD=~/bin,
        // PWD: /srv/app) is not a secret. The lookahead exempts the name PWD alone and only for a value that ends at a
        // delimiter, so a PWD value with a "!" or another character no path has stays a secret. OLDPWD is never matched,
        // because "PWD" in it has a letter in front. MYSQL_PWD= is a password variable and stays a secret name.
        Rule.of("pwd-assignment",
            "(?i)(?<![A-Za-z0-9_])"
                + "(?!pwd[\"']?[ \\t]*[=:][ \\t]*[\"']?(?:/[A-Za-z0-9._~/-]*|~(?:/[A-Za-z0-9._~/-]*)?)(?![^\\s\"'`,;]))"
                + "(?<keep>(?:[A-Za-z0-9_-]*[_-])?pwd[\"']?[ \\t]*[=:][ \\t]*[\"']?)"
                + "(?<secret>(?<=[\"'])[^\"'\\r\\n]+|(?<![\"'])[^\\s\"'`,;]+)"));

    /**
     * A value an earlier pass already masked: nothing but mask characters, or a token-type prefix followed by the
     * mask, as in {@code ghp_***}. A value that merely contains the mask, such as {@code Ab***789}, is still a secret.
     */
    private static final Pattern ALREADY_MASKED = Pattern.compile(
        "(?:gh[pousr]_|github_pat_|gl(?:pat|rt|dt|ptt)-|xox[abposr]-|sk-(?:ant-|proj-)?|AKIA|ASIA|ABIA|ACCA|eyJ)?"
            + "[*xX._\\-\\u2026]*\\*\\*\\*[*xX._\\-\\u2026]*");

    private SecretTokenPatterns() {
    }

    /**
     * Whether a value an earlier pass already masked: nothing but mask characters, or a token-type prefix followed by
     * the mask. A value that merely contains the mask is still a secret.
     */
    static boolean isAlreadyMasked(String value) {
        return value != null && ALREADY_MASKED.matcher(value).matches();
    }

    /** Masks every known secret format in the text and reports how many matches were masked. */
    public static RedactionResult redact(String text) {
        return redact(text, null);
    }

    /**
     * Same as {@link #redact(String)}, and hands every masked secret part to {@code maskedValues}
     * — for a caller that counts distinct secrets across several texts. The values are secrets:
     * the consumer must not keep or log them as they are.
     */
    public static RedactionResult redact(String text, java.util.function.Consumer<String> maskedValues) {
        if (text == null || text.isEmpty()) {
            return RedactionResult.unchanged(text);
        }
        String result = text;
        int total = 0;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            if (!matcher.find()) {
                continue;
            }
            matcher.reset();
            int[] masked = {0};
            result = matcher.replaceAll(match -> Matcher.quoteReplacement(mask(rule, match, masked, maskedValues)));
            total += masked[0];
        }
        return new RedactionResult(result, total);
    }

    /**
     * Hands every secret part the patterns match to {@code values}, whether or not it already contains the
     * replacement. For a caller that decides by itself what a value may be, such as the AI secret guard: a
     * value that is only partly masked is still a value to be judged, not one to skip.
     */
    public static void findValues(String text, java.util.function.Consumer<String> values) {
        findNamedValues(text, named -> values.accept(named.value()));
    }

    /**
     * Hands every secret part the patterns match to {@code values} with the text in front of it and the name of the
     * rule, as {@link #findValues} does otherwise. The same values, in the same order.
     */
    static void findNamedValues(String text, java.util.function.Consumer<NamedValue> values) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                String secret = matcher.group(SECRET);
                if (secret != null && !secret.isEmpty()) {
                    values.accept(new NamedValue(rule.name(), matcher.group(KEEP), secret));
                }
            }
        }
    }

    private static String mask(
        Rule rule, MatchResult match, int[] masked, java.util.function.Consumer<String> maskedValues) {
        String secret = match.group(SECRET);
        if (secret == null || secret.isEmpty() || isAlreadyMasked(secret)) {
            return match.group();
        }
        masked[0]++;
        if (maskedValues != null) {
            maskedValues.accept(secret);
        }
        String tail = rule.hasTail() ? match.group(TAIL) : null;
        return match.group(KEEP) + SessionJournalRedactor.REPLACEMENT + (tail != null ? tail : "");
    }
}
