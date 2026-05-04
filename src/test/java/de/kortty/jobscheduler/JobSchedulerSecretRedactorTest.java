package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerSecretRedactorTest {

    @Test
    void redactsKnownSecretsBeforeJournalStorage() {
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        redactor.addSecret("secret-password");

        assertThat(redactor.prepare("sudo failed for secret-password", JournalDetailMode.FULL))
            .isEqualTo("sudo failed for ***");
    }

    @Test
    void limitsTextInLimitedMode() {
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        String text = "x".repeat(4_100);

        assertThat(redactor.prepare(text, JournalDetailMode.LIMITED_REDACTED)).contains("truncated");
    }
}
