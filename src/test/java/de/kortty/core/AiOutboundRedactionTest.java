package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.SessionJournalReplacement;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiOutboundRedactionTest {

    private static final String AWS_KEY_ID = "AKIA" + "ABCDEFGHIJKLMNOP";

    private static AiProfile profile(AiConnectionMode mode, String apiUrl) {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(mode);
        profile.setApiUrl(apiUrl);
        return profile;
    }

    private static AiProfile trusted(AiConnectionMode mode, String apiUrl) {
        AiProfile profile = profile(mode, apiUrl);
        profile.setTrustedLocalEndpoint(true);
        return profile;
    }

    private static SessionJournalRedactor knownSecrets(String password) {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret(password);
        return redactor;
    }

    @Test
    void masksTheKnownPasswordAndTokenFormatsAndAddsUpTheCount() {
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----";
        RedactionResult result = AiOutboundRedaction.redact(
            "echo Sup3rSecret! " + AWS_KEY_ID + "\n" + key, knownSecrets("Sup3rSecret!"));

        assertThat(result.text()).isEqualTo("echo *** AKIA***\n"
            + "-----BEGIN OPENSSH PRIVATE KEY-----\n***\n-----END OPENSSH PRIVATE KEY-----");
        assertThat(result.count()).isEqualTo(3);
    }

    @Test
    void countsEveryOccurrenceOfTheKnownPassword() {
        RedactionResult result = AiOutboundRedaction.redact(
            "pw hunter22, again hunter22", knownSecrets("hunter22"));
        assertThat(result.text()).isEqualTo("pw ***, again ***");
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void honoursTheMinimumLengthOfKnownSecrets() {
        assertThat(AiOutboundRedaction.redact("abc is short", knownSecrets("abc")).count()).isEqualTo(0);
        RedactionResult fourChars = AiOutboundRedaction.redact("abcd is not", knownSecrets("abcd"));
        assertThat(fourChars.text()).isEqualTo("*** is not");
        assertThat(fourChars.count()).isEqualTo(1);
    }

    @Test
    void appliesThePolicyReplacementsLiteralAndRegex() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.setReplacements(List.of(
            SessionJournalReplacement.literal("db.corp.internal", "[host]"),
            new SessionJournalReplacement("ticket-\\d+", "ticket-#", true, false, "tickets")));

        RedactionResult result = AiOutboundRedaction.redact(
            "ssh db.corp.internal ticket-4711 ticket-42 db.corp.internal", redactor);

        assertThat(result.text()).isEqualTo("ssh [host] ticket-# ticket-# [host]");
        assertThat(result.count()).isEqualTo(4);
    }

    @Test
    void doesNotCountAMaskedPasswordAgainAsAnAssignment() {
        // The known-secret pass turns the value into ***, so the token pass must not count it again.
        RedactionResult result = AiOutboundRedaction.redact("DB_PASSWORD=Sup3rSecret!", knownSecrets("Sup3rSecret!"));
        assertThat(result.text()).isEqualTo("DB_PASSWORD=***");
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    void masksTokenFormatsWithoutASession() {
        RedactionResult result = AiOutboundRedaction.redact("key " + AWS_KEY_ID, null);
        assertThat(result.text()).isEqualTo("key AKIA***");
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    void leavesTextWithoutSecretsUnchanged() {
        RedactionResult result = AiOutboundRedaction.redact("ls -la /var/log", knownSecrets("Sup3rSecret!"));
        assertThat(result.text()).isEqualTo("ls -la /var/log");
        assertThat(result.masked()).isFalse();
        assertThat(AiOutboundRedaction.redact(null, null).text()).isNull();
    }

    @Test
    void onlyIntegratedModelsAreExemptByDefault() {
        assertThat(AiOutboundRedaction.appliesTo(profile(AiConnectionMode.EMBEDDED_LLAMA_CPP, null))).isFalse();
        assertThat(AiOutboundRedaction.appliesTo(profile(AiConnectionMode.EMBEDDED_MLX, null))).isFalse();
        // A loopback HTTP endpoint may be a proxy or an ssh -L forward to a cloud API.
        assertThat(AiOutboundRedaction.appliesTo(
            profile(AiConnectionMode.HTTP_API, "http://127.0.0.1:1234/v1/chat/completions"))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(
            profile(AiConnectionMode.HTTP_API, "http://localhost:4000/v1/chat/completions"))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(
            profile(AiConnectionMode.HTTP_API, "https://api.openai.com/v1/chat/completions"))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(
            profile(AiConnectionMode.HTTP_API, "https://api.anthropic.com/v1/messages"))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(profile(AiConnectionMode.LOCAL_CLI, null))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(null)).isTrue();
    }

    @Test
    void aTrustedLocalEndpointIsExemptOnlyOnALoopbackHttpUrl() {
        assertThat(AiOutboundRedaction.appliesTo(
            trusted(AiConnectionMode.HTTP_API, "http://127.0.0.1:1234/v1/chat/completions"))).isFalse();
        assertThat(AiOutboundRedaction.appliesTo(
            trusted(AiConnectionMode.HTTP_API, "http://localhost:1234/v1/chat/completions"))).isFalse();
        // A flag left over from an earlier URL never exempts a remote endpoint.
        assertThat(AiOutboundRedaction.appliesTo(
            trusted(AiConnectionMode.HTTP_API, "https://api.openai.com/v1/chat/completions"))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(
            trusted(AiConnectionMode.HTTP_API, "http://192.168.1.20:1234/v1/chat/completions"))).isTrue();
        assertThat(AiOutboundRedaction.appliesTo(trusted(AiConnectionMode.LOCAL_CLI, null))).isTrue();
    }

    @Test
    void redactForPassesTextUnchangedToAnExemptProfile() {
        RedactionResult result = AiOutboundRedaction.redactFor(
            profile(AiConnectionMode.EMBEDDED_LLAMA_CPP, null), "echo Sup3rSecret! " + AWS_KEY_ID,
            knownSecrets("Sup3rSecret!"));
        assertThat(result.text()).isEqualTo("echo Sup3rSecret! " + AWS_KEY_ID);
        assertThat(result.count()).isEqualTo(0);

        RedactionResult cloud = AiOutboundRedaction.redactFor(
            profile(AiConnectionMode.HTTP_API, "https://api.openai.com/v1/chat/completions"),
            "echo Sup3rSecret! " + AWS_KEY_ID, knownSecrets("Sup3rSecret!"));
        assertThat(cloud.text()).isEqualTo("echo *** AKIA***");
        assertThat(cloud.count()).isEqualTo(2);
    }

    @Test
    void masksAnAttachmentAndKeepsItsNameAndPath() {
        AiFileAttachment attachment = new AiFileAttachment("deploy.sh", "/srv/app/deploy.sh",
            "export DB_PASSWORD=hunter2\nssh -p Sup3rSecret! host\n");
        AiOutboundRedaction.MaskedAttachment masked = AiOutboundRedaction.redactAttachmentFor(
            profile(AiConnectionMode.LOCAL_CLI, null), attachment, knownSecrets("Sup3rSecret!"));

        assertThat(masked.count()).isEqualTo(2);
        assertThat(masked.attachment().fileName()).isEqualTo("deploy.sh");
        assertThat(masked.attachment().sourcePath()).isEqualTo("/srv/app/deploy.sh");
        assertThat(masked.attachment().content()).isEqualTo("export DB_PASSWORD=***\nssh -p *** host\n");
    }

    @Test
    void keepsAnAttachmentWithoutSecretsOrForAnExemptProfile() {
        AiFileAttachment clean = new AiFileAttachment("notes.txt", null, "nothing to hide");
        AiOutboundRedaction.MaskedAttachment unchanged = AiOutboundRedaction.redactAttachmentFor(
            profile(AiConnectionMode.LOCAL_CLI, null), clean, null);
        assertThat(unchanged.attachment()).isSameInstanceAs(clean);
        assertThat(unchanged.count()).isEqualTo(0);

        AiFileAttachment secret = new AiFileAttachment("env", null, "DB_PASSWORD=hunter2");
        assertThat(AiOutboundRedaction.redactAttachmentFor(profile(AiConnectionMode.EMBEDDED_MLX, null), secret, null)
            .attachment()).isSameInstanceAs(secret);

        AiOutboundRedaction.MaskedAttachment none = AiOutboundRedaction.redactAttachmentFor(null, null, null);
        assertThat(none.attachment()).isNull();
        assertThat(none.count()).isEqualTo(0);
    }
}
