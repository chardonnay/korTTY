package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Two corpora of natural German and English sentences about tokens, keys, secrets, passwords, PINs, PWD and configuration.
 * The first is ordinary text that must pass the guard and must stay unchanged by the prose masking of outbound AI text. The
 * second is real secrets, which must be refused and whose value must not reach the model after the outbound masking.
 */
class AiSecretGuardCorpusTest {

    /** Ordinary rule and chat sentences: none is refused, and none is changed by the prose masking. */
    private static final List<String> FALSE_POSITIVES = List.of(
        "Setze max tokens: 4096 in der Konfiguration.",
        "max_tokens: 4096",
        "Passwords are managed by IT.",
        "export PWD=/tmp",
        "Das Passwort ist abgelaufen, bitte neu setzen.",
        "The password is required for sudo.",
        "Passwörter sind im Tresor gespeichert.",
        "my $password = shift;",
        "password = os.environ['DB_PASSWORD']",
        "Use the secret from the vault, never hardcode it.",
        "The API key is stored in ~/.config/app/key.",
        "Kennwort ändern: passwd username",
        "context window: 128k tokens",
        "num_tokens = 512",
        "Der Token ist ungültig.",
        "The token expires after one hour.",
        "Enter the PIN in the dialog.",
        "Die Zugangsdaten sind im Tresor abgelegt.",
        "Credentials are rotated every quarter.",
        "Use a token of 20 characters or more.",
        "Max secret size: 512 bytes",
        "token_limit = 128MB",
        "Lege das API-Token im Tresor ab.",
        "The secret key rotation runs every 90 days.",
        "Set the PASSWORD environment variable before running the job.",
        "Run pwd to print the directory.",
        "PWD: /home/user/project",
        "Die Kennwörter sind nicht im Klartext zu speichern.",
        "PASSWORD_MIN_LENGTH=8",
        "max token 4096 pro Anfrage",
        "Set the token limit to 4096 tokens per request.",
        "The API key rotates every 90 days.",
        "Passwords: none configured",
        "Zugangsdaten werden nicht im Log gespeichert.",
        "Max tokens 8k per request, see the docs.",
        "The credentials were rotated last week.",
        "tokens: 1_000 per minute",
        "Der Token ist gespeichert.",
        "The secret is encrypted at rest by korTTY.",
        "Passwort ändern und Token rotieren, sobald der Dienst läuft.",
        "Die Passphrase ist falsch, bitte erneut eingeben.",
        "cd - sets OLDPWD=/tmp when you return.",
        "The token is valid for one hour.",
        "If the password is wrong, retry the login.");

    /** A real secret and the value that must not reach the model. */
    private record Secret(String text, String value) {
    }

    private static final List<Secret> SECRETS = List.of(
        new Secret("PIN: 1234", "1234"),
        new Secret("Das Passwort lautet: Sommer2026!", "Sommer2026!"),
        new Secret("The password is swordfish", "swordfish"),
        new Secret("DB_PASS=hunter2Secret", "hunter2Secret"),
        new Secret("Passwörter: Sommer2026!", "Sommer2026!"),
        new Secret("mysql --pass=Sommer2026!", "Sommer2026!"),
        new Secret("export PGPASSWORD=Sommer2026!", "Sommer2026!"),
        new Secret("api_key = sk-test-ABCdef1234567890xyz", "ABCdef1234567890xyz"),
        new Secret("Zugangsdaten sind root/Sommer2026!", "Sommer2026!"),
        new Secret("The credentials are root / Sommer2026!", "Sommer2026!"),
        new Secret("PWD=hunter2Secret", "hunter2Secret"),
        new Secret("Das Passwort ist Kartoffel", "Kartoffel"),
        new Secret("secret: 8472901635", "8472901635"),
        new Secret("api_key = 4711081500123456", "4711081500123456"),
        new Secret("password=Qwerty.Admin", "Qwerty.Admin"),
        new Secret("password=Winter(2024)", "Winter(2024)"),
        new Secret("PWD=/Sommer2026!", "Sommer2026!"),
        new Secret("Passwords are Swordfish", "Swordfish"),
        new Secret("Passwörter sind Sonnenblume", "Sonnenblume"));

    @AfterMethod
    void removeKnownSecretsSource() {
        AiSecretGuard.installKnownSecretsSource(null);
    }

    @Test
    void theFalsePositiveCorpusIsLargeEnough() {
        assertThat(FALSE_POSITIVES.size()).isAtLeast(30);
    }

    @Test
    void ordinarySentencesAreNotRefused() {
        for (String text : FALSE_POSITIVES) {
            AiSecretGuard.Result result = AiSecretGuard.check(text, List.of());
            assertWithMessage("refused: " + text + " -> " + result.findings()).that(result.ok()).isTrue();
        }
    }

    @Test
    void ordinarySentencesAreNotChangedByTheOutboundProseMasking() {
        for (String text : FALSE_POSITIVES) {
            RedactionResult masked = AiSecretGuard.maskProseSecrets(text, null);
            assertWithMessage("masked: " + text).that(masked.text()).isEqualTo(text);
            assertWithMessage("masked count: " + text).that(masked.count()).isEqualTo(0);
        }
    }

    @Test
    void realSecretsAreRefused() {
        for (Secret secret : SECRETS) {
            AiSecretGuard.Result result = AiSecretGuard.check(secret.text(), List.of());
            assertWithMessage("accepted: " + secret.text()).that(result.ok()).isFalse();
        }
    }

    @Test
    void realSecretsDoNotReachTheModelAfterTheOutboundMasking() {
        for (Secret secret : SECRETS) {
            String outbound = AiSecretGuard.maskProseSecrets(
                SecretTokenPatterns.redact(secret.text()).text(), null).text();
            assertWithMessage("value still in outbound text: " + outbound)
                .that(outbound).doesNotContain(secret.value());
        }
    }
}
