package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AccessReasonMemoryTest {

    private static final String PROMPT = "Reason for this operation:";

    @Test
    void aSecondPromptInTheSameTabReplaysTheAnswerWithoutAsking() {
        AccessReasonMemory memory = new AccessReasonMemory();
        List<String> asked = new ArrayList<>();

        String first = memory.answer("root@vault.example:22", PROMPT, () -> {
            asked.add(PROMPT);
            return "ticket 4711";
        });
        String replayed = memory.answer("root@vault.example:22", PROMPT, () -> {
            asked.add(PROMPT);
            return "asked again";
        });

        assertThat(first).isEqualTo("ticket 4711");
        // The reason is still delivered — a server that asks for one drops a connection that
        // answers with nothing.
        assertThat(replayed).isEqualTo("ticket 4711");
        assertThat(asked).hasSize(1);
    }

    @Test
    void anotherTargetOrPromptIsStillAsked() {
        AccessReasonMemory memory = new AccessReasonMemory();
        memory.answer("root@vault.example:22", PROMPT, () -> "ticket 4711");

        assertThat(memory.answer("root@other.example:22", PROMPT, () -> "second reason"))
            .isEqualTo("second reason");
        assertThat(memory.answer("root@vault.example:22", "Change ticket?", () -> "third reason"))
            .isEqualTo("third reason");
    }

    @Test
    void aCancelledDialogIsNotRemembered() {
        AccessReasonMemory memory = new AccessReasonMemory();

        assertThat(memory.answer("root@vault.example:22", PROMPT, () -> "")).isEmpty();
        assertThat(memory.answer("root@vault.example:22", PROMPT, () -> "   ")).isEqualTo("   ");
        assertThat(memory.answer("root@vault.example:22", PROMPT, () -> null)).isEmpty();

        assertThat(memory.remembers("root@vault.example:22", PROMPT)).isFalse();
        assertThat(memory.answer("root@vault.example:22", PROMPT, () -> "ticket 4711"))
            .isEqualTo("ticket 4711");
    }

    @Test
    void answersNeverCrossTabs() {
        AccessReasonMemory oneTab = new AccessReasonMemory();
        AccessReasonMemory anotherTab = new AccessReasonMemory();
        oneTab.answer("root@vault.example:22", PROMPT, () -> "ticket 4711");

        assertThat(anotherTab.remembers("root@vault.example:22", PROMPT)).isFalse();
        assertThat(anotherTab.answer("root@vault.example:22", PROMPT, () -> "own reason"))
            .isEqualTo("own reason");
    }

    @Test
    void aRefusedReasonIsForgottenSoTheTabCanAskForAFreshOne() {
        // A ticket number is only valid for a while, and the tab it was typed in outlives it.
        AccessReasonMemory memory = new AccessReasonMemory();
        memory.answer("root@vault.example:22", PROMPT, () -> "ticket 4711");

        memory.forget("root@vault.example:22", PROMPT);

        assertThat(memory.remembers("root@vault.example:22", PROMPT)).isFalse();
        assertThat(memory.answer("root@vault.example:22", PROMPT, () -> "ticket 4712"))
            .isEqualTo("ticket 4712");
    }

    @Test
    void forgettingAnAnswerNoTabEverGaveIsHarmless() {
        AccessReasonMemory memory = new AccessReasonMemory();

        memory.forget("root@vault.example:22", PROMPT);

        assertThat(memory.remembers("root@vault.example:22", PROMPT)).isFalse();
    }

    @Test
    void theConnectorAnswersFromItsTabMemoryInsteadOfOpeningTheDialog() {
        ServerConnection connection = new ServerConnection("Vault", "vault.example", 2222, "root");
        AccessReasonMemory memory = new AccessReasonMemory();
        SshTtyConnector connector = new SshTtyConnector(connection, null);
        connector.setAccessReasonMemory(memory);

        assertThat(connector.accessReasonTarget()).isEqualTo("root@vault.example:2222");
        memory.answer(connector.accessReasonTarget(), PROMPT, () -> "ticket 4711");

        // Reaching the dialog would need a JavaFX toolkit, so a returned answer is proof that the
        // remembered one was used.
        assertThat(connector.resolveAccessReason("CyberArk", PROMPT)).isEqualTo("ticket 4711");
    }
}
