package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Settings › AI › AI chat code blocks in the terminal in {@code global-settings.xml}: every choice survives the
 * round trip by its stable id, and a file from before the setting existed, or with a value korTTY does not know,
 * gets the default, Insert and Run.
 */
class GlobalSettingsAiChatTerminalActionsTest {

    @Test
    void aSettingsFileFromBeforeTheSettingExistedGetsInsertAndRun() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.getAiChatTerminalActions()).isEqualTo(AiChatTerminalActions.INSERT_AND_RUN);
        assertThat(new GlobalSettings().getAiChatTerminalActions()).isEqualTo(AiChatTerminalActions.DEFAULT);
    }

    @Test
    void everyChoiceSurvivesTheRoundTripByItsId() throws Exception {
        for (AiChatTerminalActions value : AiChatTerminalActions.values()) {
            GlobalSettings settings = new GlobalSettings();
            settings.setAiChatTerminalActions(value);

            String xml = marshal(settings);

            assertThat(xml).contains("<aiChatTerminalActions>" + value.id() + "</aiChatTerminalActions>");
            assertThat(unmarshal(xml).getAiChatTerminalActions()).isEqualTo(value);
        }
    }

    @Test
    void unknownBlankAndOddlyWrittenValuesAreRead() throws Exception {
        assertWithMessage("a value from a newer korTTY")
            .that(unmarshal(xml("ask_every_time")).getAiChatTerminalActions())
            .isEqualTo(AiChatTerminalActions.INSERT_AND_RUN);
        assertThat(unmarshal(xml("")).getAiChatTerminalActions()).isEqualTo(AiChatTerminalActions.INSERT_AND_RUN);
        assertThat(unmarshal(xml("  OFF ")).getAiChatTerminalActions()).isEqualTo(AiChatTerminalActions.OFF);
        assertThat(unmarshal(xml("Insert_Only")).getAiChatTerminalActions())
            .isEqualTo(AiChatTerminalActions.INSERT_ONLY);

        GlobalSettings settings = new GlobalSettings();
        settings.setAiChatTerminalActions(AiChatTerminalActions.OFF);
        settings.setAiChatTerminalActions(null);
        assertThat(settings.getAiChatTerminalActions()).isEqualTo(AiChatTerminalActions.INSERT_AND_RUN);
    }

    @Test
    void theChoicesSayWhatTheyAllow() {
        assertThat(AiChatTerminalActions.OFF.allowsInsert()).isFalse();
        assertThat(AiChatTerminalActions.OFF.allowsRun()).isFalse();
        assertThat(AiChatTerminalActions.INSERT_ONLY.allowsInsert()).isTrue();
        assertThat(AiChatTerminalActions.INSERT_ONLY.allowsRun()).isFalse();
        assertThat(AiChatTerminalActions.INSERT_AND_RUN.allowsInsert()).isTrue();
        assertThat(AiChatTerminalActions.INSERT_AND_RUN.allowsRun()).isTrue();
    }

    private static String xml(String value) {
        return "<globalSettings><aiChatTerminalActions>" + value + "</aiChatTerminalActions></globalSettings>";
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        StringWriter writer = new StringWriter();
        JAXBContext.newInstance(GlobalSettings.class).createMarshaller().marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
            .createUnmarshaller().unmarshal(new StringReader(xml));
    }
}
