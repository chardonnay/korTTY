package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

/**
 * The input histories never remember text {@code global-settings.xml} cannot hold. The terminal agent's
 * history is fed by bracketed paste and by an OSC sequence a remote host can print, neither of which
 * filters control characters the way a JavaFX text field does.
 */
class GlobalSettingsHistoryStorageTest {

    private static GlobalSettings roundTrip(GlobalSettings settings) throws Exception {
        JAXBContext context = JAXBContext.newInstance(GlobalSettings.class);
        Marshaller marshaller = context.createMarshaller();
        StringWriter writer = new StringWriter();
        marshaller.marshal(settings, writer);
        return (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader(writer.toString()));
    }

    @Test
    void anEntryTheFileCannotHoldIsNotRememberedSoTheSettingsStillLoad() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalHighlightingEnabled(false);
        settings.addTerminalAgentInput("list the disks");
        settings.addTerminalAgentInput("explain \u001b[31mERROR\u001b[0m");
        settings.addAiPromptHistoryEntry("why\u0007");
        settings.addAiPromptHistoryEntry("summarize the log");
        settings.addGuideAskHistoryEntry("half\uD800");
        settings.addWorkflowInstructionsHistoryEntry("end￾");
        settings.addAccessReason("INC-1\u0000");
        settings.addAccessReason("INC-2");

        assertThat(settings.getTerminalAgentInputHistory()).containsExactly("list the disks");
        assertThat(settings.getAiPromptHistory()).containsExactly("summarize the log");
        assertThat(settings.getGuideAskHistory()).isEmpty();
        assertThat(settings.getWorkflowInstructionsHistory()).isEmpty();
        assertThat(settings.getAccessReasonHistory()).containsExactly("INC-2");

        GlobalSettings restored = roundTrip(settings);
        assertThat(restored.isTerminalHighlightingEnabled()).isFalse();
        assertThat(restored.getTerminalAgentInputHistory()).containsExactly("list the disks");
        assertThat(restored.getAccessReasonHistory()).containsExactly("INC-2");
    }

    @Test
    void tabsNewlinesAndCharactersOutsideTheBmpAreRemembered() {
        GlobalSettings settings = new GlobalSettings();
        settings.addAiPromptHistoryEntry("line one\n\tline two 🚀");

        assertThat(settings.getAiPromptHistory()).containsExactly("line one\n\tline two 🚀");
    }
}
