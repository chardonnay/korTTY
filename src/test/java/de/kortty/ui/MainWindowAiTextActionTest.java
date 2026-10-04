package de.kortty.ui;

import de.kortty.core.SessionJournalRedactor;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The rules {@code MainWindow.handleAiTextAction} applies to a selection and to "Summarize Recent
 * Output" ({@link AiTextActionInput#prepare}); the wiring itself is pinned by
 * {@link AiSelectionRedactionWiringTest}.
 */
class MainWindowAiTextActionTest {

    private static AiProfile cloudProfile() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.invalid/v1");
        return profile;
    }

    private static AiProfile trustedLocalProfile() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("http://127.0.0.1:1234/v1");
        profile.setTrustedLocalEndpoint(true);
        return profile;
    }

    private static SessionJournalRedactor secrets(String password) {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret(password);
        return redactor;
    }

    @Test
    void aBlankSelectionStillReturnsQuietly() {
        assertThat(AiTextActionInput.prepare(AiTextActionInput.Origin.SELECTION, cloudProfile(), "  \n", 100, null).status())
            .isEqualTo(AiTextActionInput.Status.SKIP);
        assertThat(AiTextActionInput.prepare(AiTextActionInput.Origin.SELECTION, cloudProfile(), null, 100, null).status())
            .isEqualTo(AiTextActionInput.Status.SKIP);
    }

    @Test
    void recentOutputDoesNotNeedASelection() {
        AiTextActionInput.Prepared prepared = AiTextActionInput.prepare(
            AiTextActionInput.Origin.RECENT_OUTPUT, cloudProfile(), "make: *** [all] Error 2", 100, null);

        assertThat(prepared.status()).isEqualTo(AiTextActionInput.Status.READY);
        assertThat(prepared.outboundText()).isEqualTo("make: *** [all] Error 2");
        // Empty recent output is reported, not dropped silently.
        assertThat(AiTextActionInput.prepare(AiTextActionInput.Origin.RECENT_OUTPUT, cloudProfile(), " ", 100, null)
            .status()).isEqualTo(AiTextActionInput.Status.NO_OUTPUT);
    }

    @Test
    void masksRecentOutputForACloudProfile() {
        AiTextActionInput.Prepared prepared = AiTextActionInput.prepare(AiTextActionInput.Origin.RECENT_OUTPUT,
            cloudProfile(), "login ok with Sup3rSecret!\nAKIAABCDEFGHIJKLMNOP", 1000, secrets("Sup3rSecret!"));

        assertThat(prepared.status()).isEqualTo(AiTextActionInput.Status.READY);
        assertThat(prepared.outboundText()).doesNotContain("Sup3rSecret!");
        assertThat(prepared.outboundText()).doesNotContain("ABCDEFGHIJKLMNOP");
        assertThat(prepared.count()).isEqualTo(2);
    }

    @Test
    void masksTheSelectionTheSameWay() {
        AiTextActionInput.Prepared prepared = AiTextActionInput.prepare(AiTextActionInput.Origin.SELECTION,
            cloudProfile(), "pw hunter22", 1000, secrets("hunter22"));

        assertThat(prepared.outboundText()).isEqualTo("pw ***");
        assertThat(prepared.count()).isEqualTo(1);
    }

    @Test
    void aMissingProfileFailsClosedAndATrustedLocalEndpointIsExempt() {
        assertThat(AiTextActionInput.prepare(AiTextActionInput.Origin.RECENT_OUTPUT, null, "pw hunter22", 1000,
            secrets("hunter22")).outboundText()).isEqualTo("pw ***");
        assertThat(AiTextActionInput.prepare(AiTextActionInput.Origin.RECENT_OUTPUT, trustedLocalProfile(),
            "pw hunter22", 1000, secrets("hunter22")).outboundText()).isEqualTo("pw hunter22");
    }

    @Test
    void anOversizedSelectionIsRefusedButRecentOutputKeepsItsEnd() {
        assertThat(AiTextActionInput.prepare(AiTextActionInput.Origin.SELECTION, cloudProfile(), "abcdefghij", 4, null)
            .status()).isEqualTo(AiTextActionInput.Status.TOO_LARGE);

        AiTextActionInput.Prepared recent = AiTextActionInput.prepare(
            AiTextActionInput.Origin.RECENT_OUTPUT, cloudProfile(), "abcdefghij", 4, null);
        assertThat(recent.status()).isEqualTo(AiTextActionInput.Status.READY);
        assertThat(recent.outboundText()).isEqualTo("ghij");
        assertThat(recent.shortened()).isTrue();
    }
}
