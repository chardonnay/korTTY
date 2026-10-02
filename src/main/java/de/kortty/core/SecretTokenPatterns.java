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
        // scheme://user:password@host keeps the user.
        Rule.of("url-credentials",
            "(?<keep>\\b[A-Za-z][A-Za-z0-9+.-]*://[^\\s:/@\"'<>]*:)(?<secret>[^\\s@/\"'<>]+)(?<tail>@)"),
        // NAME=value / name: value where the name ends in a secret word. PASSWORD_MIN_LENGTH=8
        // does not match (the name must end in the word), and PWD/OLDPWD are deliberately absent.
        // A quoted value may contain spaces; a bare one ends at whitespace, a quote, ',' or ';'.
        Rule.of("secret-assignment",
            "(?i)(?<![A-Za-z0-9_])(?<keep>[A-Za-z0-9_]*"
                + "(?:password|passwd|passphrase|secret|token|api_?key|access_?key|secret_?key|private_?key)"
                + "[\"']?[ \\t]*[=:][ \\t]*[\"']?)"
                + "(?<secret>(?<=[\"'])[^\"'\\r\\n]+|(?<![\"'])[^\\s\"'`,;]+)"));

    private SecretTokenPatterns() {
    }

    /** Masks every known secret format in the text and reports how many matches were masked. */
    public static RedactionResult redact(String text) {
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
            result = matcher.replaceAll(match -> Matcher.quoteReplacement(mask(rule, match, masked)));
            total += masked[0];
        }
        return new RedactionResult(result, total);
    }

    private static String mask(Rule rule, MatchResult match, int[] masked) {
        String secret = match.group(SECRET);
        if (secret == null || secret.isEmpty() || secret.contains(SessionJournalRedactor.REPLACEMENT)) {
            return match.group();
        }
        masked[0]++;
        String tail = rule.hasTail() ? match.group(TAIL) : null;
        return match.group(KEEP) + SessionJournalRedactor.REPLACEMENT + (tail != null ? tail : "");
    }
}
