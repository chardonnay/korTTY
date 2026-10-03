package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The fixtures are assembled from pieces at run time, so this file never contains a string that a
 * secret scanner would take for a real token.
 */
class SecretTokenPatternsTest {

    private static final String AWS_KEY_ID = "AKIA" + "IOSFODNN7EXAMPLE";
    private static final String AWS_SECRET = "wJalrXUtnFEMI/K7MDENG/" + "bPxRfiCYEXAMPLEKEY";
    private static final String JWT = "eyJ" + "hbGciOiJIUzI1NiJ9" + ".eyJ" + "zdWIiOiIxMjM0NTY3ODkwIn0"
        + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

    private static String begin(String kind) {
        return "-----BEGIN " + kind + "-----";
    }

    private static String end(String kind) {
        return "-----END " + kind + "-----";
    }

    private static void assertMasked(String input, String expected, int count) {
        RedactionResult result = SecretTokenPatterns.redact(input);
        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.count()).isEqualTo(count);
    }

    private static void assertUntouched(String input) {
        assertMasked(input, input, 0);
    }

    @Test
    void masksMultiLineOpenSshKeyBlockButKeepsItsBeginAndEndLines() {
        String kind = "OPENSSH PRIVATE KEY";
        assertMasked(
            "before\n" + begin(kind) + "\nb3BlbnNzaC1rZXktdjEAAAAA\nAAAABG5vbmUAAAAEbm9uZQ\n" + end(kind) + "\nafter",
            "before\n" + begin(kind) + "\n***\n" + end(kind) + "\nafter",
            1);
    }

    @Test
    void masksPemAndPgpPrivateKeyBlocks() {
        String rsa = "RSA PRIVATE KEY";
        String pgp = "PGP PRIVATE KEY BLOCK";
        assertMasked(
            begin(rsa) + "\r\nMIIEowIBAAKCAQEA\r\n" + end(rsa) + "\n" + begin(pgp) + "\n\nlQOYBF\n" + end(pgp),
            begin(rsa) + "\r\n***\r\n" + end(rsa) + "\n" + begin(pgp) + "\n***\n" + end(pgp),
            2);
    }

    @Test
    void masksAnUnterminatedKeyBlockToTheEndOfTheText() {
        String kind = "PRIVATE KEY";
        assertMasked(
            "cat id.pem\n" + begin(kind) + "\nMIIEvQIBADANBgkqhkiG9w0B\nAQEFAASCBKcwggSjAgEAAoIB",
            "cat id.pem\n" + begin(kind) + "\n***",
            1);
    }

    @Test
    void leavesPublicKeysAndCertificatesAlone() {
        assertUntouched(begin("PUBLIC KEY") + "\nMIIBIjANBgkqhkiG9w0B\n" + end("PUBLIC KEY"));
        assertUntouched(begin("CERTIFICATE") + "\nMIIDdzCCAl+gAwIBAgIE\n" + end("CERTIFICATE"));
    }

    @Test
    void masksAwsAccessKeyIdKeepingItsPrefix() {
        assertMasked("aws_access_key_id = " + AWS_KEY_ID, "aws_access_key_id = AKIA***", 1);
    }

    @Test
    void masksAwsSecretAccessKeyKeepingTheName() {
        assertMasked("aws_secret_access_key = " + AWS_SECRET, "aws_secret_access_key = ***", 1);
        assertMasked("export AWS_SECRET_ACCESS_KEY=" + AWS_SECRET, "export AWS_SECRET_ACCESS_KEY=***", 1);
    }

    @Test
    void masksGitHubTokensAndCountsAnAssignedTokenOnce() {
        assertMasked("export GITHUB_TOKEN=" + "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8",
            "export GITHUB_TOKEN=ghp_***", 1);
        assertMasked("git clone https://" + "github_pat_" + "11ABCDEFG0abcdefghij_klmnopqrstuvwxyz@github.com/o/r",
            "git clone https://github_pat_***@github.com/o/r", 1);
    }

    @Test
    void masksGitLabTokens() {
        assertMasked("PRIVATE-TOKEN " + "glpat-" + "Ab1Cd2Ef3Gh4Ij5Kl6Mn", "PRIVATE-TOKEN glpat-***", 1);
    }

    @Test
    void masksSlackTokens() {
        assertMasked("slack " + "xoxb-" + "123456789012-1234567890123-AbCdEfGhIjKl", "slack xoxb-***", 1);
    }

    @Test
    void masksProviderApiKeysKeepingTheirTypePrefix() {
        String body = "Abc123Def456Ghi789Jkl012Mno345";
        assertMasked("OPENAI=" + "sk-" + body, "OPENAI=sk-***", 1);
        assertMasked("key " + "sk-" + "proj-" + body, "key sk-proj-***", 1);
        assertMasked("key " + "sk-" + "ant-" + "api03-" + body, "key sk-ant-***", 1);
    }

    @Test
    void masksAuthorizationHeaderWhateverTheTokenLooksLike() {
        assertMasked("curl -H 'Authorization: Bearer abc.def-123' https://api.example.com",
            "curl -H 'Authorization: Bearer ***' https://api.example.com", 1);
        assertMasked("authorization: basic dXNlcjpwYXNz", "authorization: basic ***", 1);
    }

    @Test
    void masksBareBearerToken() {
        assertMasked("token is Bearer " + "q1w2e3r4t5y6u7i8o9p0a1s2d3f4", "token is Bearer ***", 1);
    }

    @Test
    void masksJwtKeepingItsPrefix() {
        assertMasked("decoded " + JWT + " ok", "decoded eyJ*** ok", 1);
        // In a header, the header rule takes the token and the JWT is not counted a second time.
        assertMasked("Authorization: Bearer " + JWT, "Authorization: Bearer ***", 1);
    }

    @Test
    void masksPasswordInUrlKeepingTheUser() {
        assertMasked("DATABASE_URL=postgres://admin:s3cr3t-pw@db.internal:5432/app",
            "DATABASE_URL=postgres://admin:***@db.internal:5432/app", 1);
        assertMasked("redis://:s3cr3t@cache:6379/0", "redis://:***@cache:6379/0", 1);
    }

    @Test
    void masksAnUnencodedAtSignInAUrlPasswordToo() {
        // Stopping at the first '@' would leave "ss" of the password in the clear.
        assertMasked("mysql://root:p@ss@db.internal/app", "mysql://root:***@db.internal/app", 1);
    }

    @Test(timeOut = 5_000)
    void scansALongDottedLineInLinearTime() {
        // The URL rule used to re-scan each dotted run from every word boundary: ~45 s for this
        // line, and the masking runs on the JavaFX thread.
        String dotted = "a.".repeat(100_000);
        assertUntouched(dotted);
        assertUntouched("x-y+z.".repeat(40_000));
    }

    @Test
    void masksSecretAssignmentsKeepingTheName() {
        assertMasked("DB_PASSWORD=hunter2 ./start.sh", "DB_PASSWORD=*** ./start.sh", 1);
        assertMasked("API_KEY='open sesame'", "API_KEY='***'", 1);
        assertMasked("{\"client_secret\": \"xyz-123\"}", "{\"client_secret\": \"***\"}", 1);
        assertMasked("mysql --password=hunter2 app", "mysql --password=*** app", 1);
        assertMasked("SERVICE_TOKEN: abc123", "SERVICE_TOKEN: ***", 1);
        assertMasked("curl -H 'X-Api-Key: abc123def456' https://api.example.com",
            "curl -H 'X-Api-Key: ***' https://api.example.com", 1);
        assertMasked("tool --api-key=abc123def456 run", "tool --api-key=*** run", 1);
    }

    @Test
    void countsEveryMaskedSecretInOneText() {
        String text = "id " + AWS_KEY_ID + "\n"
            + "DB_PASSWORD=hunter2\n"
            + "url https://u:p4ss@host/x\n"
            + begin("EC PRIVATE KEY") + "\nMHcCAQEE\n" + end("EC PRIVATE KEY");
        RedactionResult result = SecretTokenPatterns.redact(text);
        assertThat(result.count()).isEqualTo(4);
        assertThat(result.text()).doesNotContain("IOSFODNN7EXAMPLE");
        assertThat(result.text()).doesNotContain("hunter2");
        assertThat(result.text()).doesNotContain("p4ss");
        assertThat(result.text()).doesNotContain("MHcCAQEE");
    }

    @Test
    void leavesGitShaUuidAndLongBase64Alone() {
        assertUntouched("commit 3f786850e387550fdab836ed7e6dc881de23001b");
        assertUntouched("request id 123e4567-e89b-12d3-a456-426614174000");
        assertUntouched("blob dGhpcyBpcyBub3QgYSBqc29uIHdlYiB0b2tlbiBhdCBhbGwsIGp1c3QgdGV4dA==");
        // One base64 JSON segment without the dot-separated payload and signature is no JWT.
        assertUntouched("header eyJub3QiOiJhIGp3dCBhdCBhbGwifQ");
    }

    @Test
    void leavesTheWordBearerInProseAlone() {
        assertUntouched("The bearer of this card must sign it.");
        assertUntouched("Send a Bearer token in the Authorization header.");
    }

    @Test
    void leavesNamesThatDoNotEndInASecretWordAlone() {
        assertUntouched("PASSWORD_MIN_LENGTH=8");
        assertUntouched("TOKEN_LIMIT=4096 max_tokens=512");
        assertUntouched("PWD=/home/daniel OLDPWD=/tmp");
    }

    @Test
    void leavesPasswordPromptsAndUrlsWithoutPasswordAlone() {
        assertUntouched("daniel@host's password: \nLast login: Mon Oct  2 10:00:00");
        assertUntouched("[sudo] password for daniel: ");
        assertUntouched("git clone ssh://git@github.com/org/repo.git");
        assertUntouched("open http://localhost:8080/path?x=1");
        assertUntouched("task-1234567890abcdefghijklmnop and disk-abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void doesNotMaskOrCountWhatAnEarlierPassAlreadyMasked() {
        assertUntouched("DB_PASSWORD=*** and https://admin:***@db/app");
    }

    @Test
    void handlesNullAndEmptyText() {
        assertThat(SecretTokenPatterns.redact(null).text()).isNull();
        assertThat(SecretTokenPatterns.redact(null).count()).isEqualTo(0);
        assertThat(SecretTokenPatterns.redact("").text()).isEmpty();
    }
}
