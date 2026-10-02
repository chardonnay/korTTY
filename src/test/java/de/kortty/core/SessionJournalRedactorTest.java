package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalRedactorTest {

    @Test
    void replacesKnownSecrets() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret("s3cretPass!");
        assertThat(redactor.redact("sshpass -p s3cretPass! ssh root@host"))
            .isEqualTo("sshpass -p *** ssh root@host");
    }

    @Test
    void replacesAllOccurrencesOfAllSecrets() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret("alpha-secret");
        redactor.addSecret("beta-secret");
        assertThat(redactor.redact("alpha-secret beta-secret alpha-secret"))
            .isEqualTo("*** *** ***");
    }

    @Test
    void ignoresShortAndBlankSecrets() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret("ab");
        redactor.addSecret("   ");
        redactor.addSecret(null);
        assertThat(redactor.redact("ab is fine")).isEqualTo("ab is fine");
    }

    @Test
    void handlesNullAndEmptyText() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret("something-secret");
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
    }

    @Test
    void redactCountingMasksLikeRedactAndCountsSecretsAndPolicyMatches() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret("alpha-secret");
        redactor.setReplacements(java.util.List.of(
            de.kortty.model.SessionJournalReplacement.literal("internal.example", "[host]")));
        String text = "alpha-secret on internal.example, alpha-secret again";

        RedactionResult result = redactor.redactCounting(text);

        assertThat(result.text()).isEqualTo(redactor.redact(text));
        assertThat(result.text()).isEqualTo("*** on [host], *** again");
        assertThat(result.count()).isEqualTo(3);
        assertThat(redactor.redactCounting("nothing here").count()).isEqualTo(0);
        assertThat(redactor.redactCounting(null).text()).isNull();
    }
}
