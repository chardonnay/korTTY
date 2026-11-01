package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The fixtures that look like credentials are assembled from pieces, so this file never contains
 * a string a secret scanner would report.
 */
class AiSecretGuardTest {

    private static final String GITHUB_TOKEN = "ghp_" + "a8Kd3Lq9Zr2Tx7Wv4Bn6Mc1Pf5Gh0Js8Ke3Ut";
    private static final String AWS_KEY_ID = "AKIA" + "IOSFODNN7EXAMPLE";
    private static final String BEARER = "7f9K2mQx8Lp3Zr5TvN1WbY6Hc";
    private static final String PEM_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PEM_END = "-----END RSA PRIVATE KEY-----";

    @AfterMethod
    void removeKnownSecretsSource() {
        AiSecretGuard.installKnownSecretsSource(null);
    }

    private static void assertRefused(String text) {
        assertWithMessage("expected a refusal for: " + text).that(AiSecretGuard.check(text).ok()).isFalse();
    }

    private static void assertAccepted(String text) {
        AiSecretGuard.Result result = AiSecretGuard.check(text);
        assertWithMessage("expected acceptance for: " + text + " but got " + result.findings())
            .that(result.ok()).isTrue();
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = AiSecretGuardTest.class.getClassLoader().getResourceAsStream(name)) {
            assertWithMessage("missing test resource " + name).that(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void refusesLiteralSecretAssignments() {
        assertRefused("$password = hunter2;");
        assertRefused("password=hunter2; # db");
        assertRefused("db_password = s3cr3tValue");
        assertRefused("\"password\": \"hunter2hunter2\"");
    }

    @Test
    void acceptsCodeThatReadsASecretAndPlaceholders() {
        assertAccepted("my $password = shift;");
        assertAccepted("my $password = <STDIN>;");
        assertAccepted("my $password = $ENV{DB_PASSWORD};");
        assertAccepted("password = os.environ[\"DB_PASSWORD\"]");
        assertAccepted("password: ${DB_PASSWORD}");
        assertAccepted("password: \"{{ vault_password }}\"");
        assertAccepted("password=****");
        assertAccepted("PASSWORD_MIN_LENGTH=8");
    }

    @Test
    void refusesGermanAndEnglishProseSecrets() {
        assertRefused("Passwort: hunter2");
        assertRefused("das Passwort ist hunter2");
        assertRefused("Zugangsdaten: hunter2");
        assertRefused("The password is hunter2 for the lab box");
        assertRefused("API-Key: 3f9a7c2e1b8d4f6a9c0e");
    }

    @Test
    void aValueWithParenthesesOrADollarSignIsASecretUnlessItIsAReaderForm() {
        assertRefused("Mein Passwort ist Summer2026(!)");
        assertRefused("password is Summer2026(!)");
        assertRefused("Passwort: Winter2026(Muc)");
        assertRefused("password = Tr0ub4dor(x)");
        assertRefused("Mein Passwort lautet Winter(2024)!");
        assertRefused("password=Tr0ub4dor(3)");
        assertRefused("Passwort ist $Hunter2Pass");
        assertRefused("db_password = $Hunter2Pass");
        assertRefused("Passwort lautet sonnenschein");
        assertAccepted("password = getenv(\"DB_PASSWORD\")");
    }

    @Test
    void codeThatReadsASecretIsAccepted() {
        assertAccepted("token = getpass()");
        assertAccepted("password = getpass.getpass()");
        assertAccepted("token = get_token()");
        assertAccepted("token = request.headers.get(\"Authorization\")");
        assertAccepted("api_key = config[\"api_key\"]");
        assertAccepted("token = response.json()[\"access_token\"]");
        assertAccepted("secret = keyring.get_password(\"svc\", user)");
        assertAccepted("password = input(\"Password: \")");
        assertAccepted("password = ENV.fetch(\"DB_PASSWORD\")");
        assertAccepted("token := os.Getenv(\"TOKEN\")");
        assertAccepted("$password = $ARGV[0];");
        assertAccepted("$token = $self->{token};");
        assertAccepted("password => $opts{password},");
        assertAccepted("PASSWORD=$1");
        assertAccepted("PGPASSWORD=$PGPASS psql");
        assertAccepted("local token=\"$1\"");
        assertAccepted("api_key: str = Field(...)");
    }

    @Test
    void aKeyCompoundWithAGermanOrEnglishCueIsStillAssigned() {
        assertRefused("db_passwort = Sommer2026");
        assertRefused("meinPasswort=Sommer2026");
        assertRefused("DB_PASSWORD=Sommer2026");
    }

    @Test
    void aProseCueWhoseValueFollowsOnTheNextLineIsRefused() {
        AiSecretGuard.Result result = AiSecretGuard.check("Notiz\nMein Passwort ist\nSummer2026!\n", List.of());
        assertThat(result.ok()).isFalse();
        assertThat(result.first().orElseThrow().line()).isEqualTo(2);
        assertRefused("Passwort:\nSommer2026\n");
    }

    @Test
    void aMaskedValueIsNotAPlaceholderUnlessItIsOnlyTheMask() {
        assertRefused("api_key=abcd***efgh");
        assertRefused("secret_key=abc***defg");
        assertAccepted("password=****");
    }

    @Test
    void sentencesWithTokenSecretOrPasswordAndAnEverydayWordAreAccepted() {
        assertAccepted("The token is valid for one hour.");
        assertAccepted("The API token is valid for 24 hours.");
        assertAccepted("Der Token ist gültig, bis er abläuft.");
        assertAccepted("The secret is encrypted at rest by korTTY.");
        assertAccepted("Die Passphrase ist falsch, bitte erneut eingeben.");
        assertAccepted("Der Token ist abgelaufen.");
        assertAccepted("If the password is wrong, retry the login.");
    }

    @Test
    void aVaultValueIsRefusedEvenWhenItStartsWithASymbol() {
        assertThat(AiSecretGuard.check("user=alice!Secret1", List.of("!Secret1")).ok()).isFalse();
        assertThat(AiSecretGuard.check("user=alice!Secret1x", List.of("!Secret1")).ok()).isTrue();
    }

    @Test
    void aLongRandomDigitRunIsRefusedAsAHighEntropyToken() {
        assertRefused("pin-free blob 739184025661930482517364 stored");
        assertAccepted("order number 20260102030405060708 is listed");
    }

    @Test
    void acceptsOrdinaryProseAndPlaceholderProse() {
        assertAccepted("Passwort ändern und Token rotieren, sobald der Dienst läuft.");
        assertAccepted("Passwort: <dein Passwort>");
        assertAccepted("The token is used for authentication.");
        assertAccepted("Das Token ist gespeichert.");
        assertAccepted("Set the PASSWORD environment variable before running the job.");
        assertAccepted("Use `SecureRandom` for tokens and salts, never `java.util.Random`.");
    }

    @Test
    void refusesKnownTokenFormats() {
        assertRefused(GITHUB_TOKEN);
        assertRefused("aws key " + AWS_KEY_ID);
        assertRefused("Authorization: Bearer " + BEARER);
        assertRefused("Authorization: Bearer <real-looking token>");
        assertRefused("https://admin:hunter2@example.org/repo");
    }

    @Test
    void acceptsAPlaceholderBearerToken() {
        assertAccepted("Authorization: Bearer <token>");
    }

    @Test
    void refusesHighEntropyTokensButNotPathsOrIdentifiers() {
        assertRefused("session 3f9a7c2e1b8d4f6a9c0e5b7d1f3a9c2e stored");
        assertRefused("blob Zx9Qw2Lp7Rt4Vb8Nm3Kj6Hd1Fg5Sa0Yu");
        assertAccepted("See src/main/java/de/kortty/memory/MemoryStore.java for the writer.");
        assertAccepted("class AiSkillPromptSupportTestCaseWithLongName");
    }

    @Test
    void refusesKnownVaultValuesOnlyAsWholeWords() {
        AiSecretGuard.Result hit = AiSecretGuard.check("connect with hunter22 today", List.of("hunter22"));
        assertThat(hit.ok()).isFalse();
        assertThat(hit.first().orElseThrow().category()).isEqualTo(AiSecretGuard.Category.VAULT_VALUE);
        assertAccepted("the hunter22x value");
        assertThat(AiSecretGuard.check("use abc here", List.of("abc")).ok()).isTrue();
    }

    @Test
    void installedKnownSecretsSourceIsUsedWithoutArguments() {
        AiSecretGuard.installKnownSecretsSource(() -> List.of("vaultPass9"));
        assertThat(AiSecretGuard.check("log in with vaultPass9").ok()).isFalse();
        AiSecretGuard.installKnownSecretsSource(null);
        assertThat(AiSecretGuard.check("log in with vaultPass9").ok()).isTrue();
    }

    @Test
    void findingsReportTheLineAndNeverShowTheSecret() {
        AiSecretGuard.Result result = AiSecretGuard.check("first line\npassword=hunter2; # db\nlast", List.of());
        assertThat(result.ok()).isFalse();
        AiSecretGuard.Finding finding = result.first().orElseThrow();
        assertThat(finding.line()).isEqualTo(2);
        assertThat(finding.category()).isEqualTo(AiSecretGuard.Category.SECRET_ASSIGNMENT);
        assertThat(finding.maskedExcerpt()).doesNotContain("hunter2");
        assertThat(finding.maskedExcerpt()).contains("password=");
    }

    @Test
    void knownSecretsAreMaskedInTheExcerptOfEveryFinding() {
        AiSecretGuard.Result result = AiSecretGuard.check(
            "Passwort ist hunter22 und vault hunter22", List.of("hunter22"));
        assertThat(result.findings()).isNotEmpty();
        for (AiSecretGuard.Finding finding : result.findings()) {
            assertThat(finding.maskedExcerpt()).doesNotContain("hunter22");
        }
    }

    @Test
    void multiLinePrivateKeyIsRefusedAtItsFirstLine() {
        String key = "note\n" + PEM_BEGIN + "\nMIIEowIBAAKCAQEAabcdef\nMIIEvQIBADANBgkqhkiG9w0B\n" + PEM_END + "\n";
        AiSecretGuard.Result result = AiSecretGuard.check(key);
        assertThat(result.ok()).isFalse();
        AiSecretGuard.Finding finding = result.first().orElseThrow();
        assertThat(finding.line()).isEqualTo(3);
        assertThat(finding.maskedExcerpt()).doesNotContain("MIIEowIBAAKCAQEA");
    }

    @Test
    void everyBuiltinSkillPasses() throws IOException {
        List<String> names = resource("builtin-ai-skills/builtin-ai-skills.index").lines()
            .map(String::strip)
            .filter(name -> !name.isEmpty())
            .toList();
        assertThat(names).hasSize(39);
        for (String name : names) {
            AiSecretGuard.Result result = AiSecretGuard.check(resource("builtin-ai-skills/" + name));
            assertWithMessage(name + " is refused: " + result.findings()).that(result.ok()).isTrue();
        }
    }
}
