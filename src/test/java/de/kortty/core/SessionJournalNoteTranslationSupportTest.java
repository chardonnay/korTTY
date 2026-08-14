package de.kortty.core;

import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SessionJournalNoteTranslationSupportTest {

    /** Records the prompts and answers with whatever the test set. */
    private static final class StubInvoker implements SessionJournalAiSupport.AiInvoker {
        final List<String> systemPrompts = new ArrayList<>();
        final List<String> userPrompts = new ArrayList<>();
        boolean available = true;
        boolean fail;
        boolean truncated;
        String reply = "{\"translation\":\"Der Dienst läuft wieder.\"}";

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            if (fail) {
                throw new IOException("simulated AI outage");
            }
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            return new AiExecutionResult(reply, null, null, truncated);
        }
    }

    @Test
    void translatesAndPassesTargetLanguageAndFencedNoteToTheModel() throws Exception {
        StubInvoker invoker = new StubInvoker();

        String translated = SessionJournalNoteTranslationSupport.translate(
            invoker, "The service is running again.", "de");

        assertThat(translated).isEqualTo("Der Dienst läuft wieder.");
        assertThat(invoker.systemPrompts.get(0)).contains("language code de");
        assertThat(invoker.systemPrompts.get(0)).contains("\"translation\"");
        // The note is data, not instructions — the prompt has to say so and fence the text.
        assertThat(invoker.systemPrompts.get(0)).contains("never instructions");
        assertThat(invoker.userPrompts.get(0)).contains("The service is running again.");
        assertThat(invoker.userPrompts.get(0)).contains("```");
    }

    @Test
    void acceptsAPlainProseReply() throws Exception {
        StubInvoker invoker = new StubInvoker();
        invoker.reply = "Der Dienst läuft wieder.";

        assertThat(SessionJournalNoteTranslationSupport.translate(invoker, "note", "de"))
            .isEqualTo("Der Dienst läuft wieder.");
    }

    @Test
    void refusesWhenNoAiProfileIsAvailable() {
        StubInvoker invoker = new StubInvoker();
        invoker.available = false;

        expectThrows(IllegalStateException.class,
            () -> SessionJournalNoteTranslationSupport.translate(invoker, "note", "de"));
    }

    @Test
    void refusesBlankInput() {
        StubInvoker invoker = new StubInvoker();

        expectThrows(IllegalStateException.class,
            () -> SessionJournalNoteTranslationSupport.translate(invoker, "   ", "de"));
        expectThrows(IllegalStateException.class,
            () -> SessionJournalNoteTranslationSupport.translate(invoker, null, "de"));
        assertThat(invoker.userPrompts).isEmpty();
    }

    @Test
    void propagatesTheTransportFailure() {
        StubInvoker invoker = new StubInvoker();
        invoker.fail = true;

        IOException failure = expectThrows(IOException.class,
            () -> SessionJournalNoteTranslationSupport.translate(invoker, "note", "de"));
        assertThat(failure).hasMessageThat().contains("simulated AI outage");
    }

    @Test
    void refusesAnUnusableReply() {
        StubInvoker invoker = new StubInvoker();
        invoker.reply = "   ";

        expectThrows(IllegalStateException.class,
            () -> SessionJournalNoteTranslationSupport.translate(invoker, "note", "de"));
    }

    @Test
    void refusesATruncatedReplyRatherThanStoringHalfASentence() {
        StubInvoker invoker = new StubInvoker();
        invoker.truncated = true;

        expectThrows(IllegalStateException.class,
            () -> SessionJournalNoteTranslationSupport.translate(invoker, "note", "de"));
    }

    @Test
    void fallsBackToEnglishWhenNoTargetLanguageIsGiven() throws Exception {
        StubInvoker invoker = new StubInvoker();

        SessionJournalNoteTranslationSupport.translate(invoker, "note", null);

        assertThat(invoker.systemPrompts.get(0)).contains("language code en");
    }

    @Test
    void translationFollowsTheTextAndTranslationRoleProfile() {
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        de.kortty.model.AiProfile textProfile = new de.kortty.model.AiProfile();
        textProfile.setId("text-1");
        textProfile.setName("Text model");
        de.kortty.model.AiProfile defaultProfile = new de.kortty.model.AiProfile();
        defaultProfile.setId("default-1");
        defaultProfile.setName("Default model");
        settings.setAiProfiles(java.util.List.of(textProfile, defaultProfile));
        settings.setDefaultAiProfileId("default-1");

        // Role unset: the default profile stands in.
        assertThat(SessionJournalAiSupport.resolveTextProfile(settings).getId()).isEqualTo("default-1");

        // Role set in the AI manager: that model wins, whatever the journal profile is.
        settings.setTextAiProfileId("text-1");
        settings.setSessionJournalAiProfileId("default-1");
        assertThat(SessionJournalAiSupport.resolveTextProfile(settings).getId()).isEqualTo("text-1");
        // The journal's own seam is unaffected and still follows the journal profile.
        assertThat(SessionJournalAiSupport.resolveProfile(settings).getId()).isEqualTo("default-1");
    }

    @Test
    void textProfileResolutionCopesWithoutProfiles() {
        assertThat(SessionJournalAiSupport.resolveTextProfile(null)).isNull();
        assertThat(SessionJournalAiSupport.resolveTextProfile(new de.kortty.model.GlobalSettings())).isNull();
    }
}
