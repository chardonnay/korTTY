package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

/** The AI memory, triage and statistics settings: their defaults, clamping and a round trip through the settings XML. */
class GlobalSettingsAiMemorySettingsTest {

    private static String marshal(GlobalSettings settings) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(GlobalSettings.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        GlobalSettings settings = new GlobalSettings();

        assertThat(settings.isAiMemoryEnabled()).isTrue();
        assertThat(settings.getAiMemoryMaxTokens()).isEqualTo(1500);
        assertThat(settings.getAiMemoryLearningMode()).isEqualTo(AiMemoryLearningMode.PROPOSE);
        assertThat(settings.isAiSkillAutoCreate()).isTrue();
        assertThat(settings.getAiAgentTriageMode()).isEqualTo(AiAgentTriageMode.AUTO);
        assertThat(settings.isAiSkillStatisticsEnabled()).isTrue();
    }

    @Test
    void aSettingsFileWithoutTheElementsGetsTheDefaults() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings/>");

        assertThat(settings.isAiMemoryEnabled()).isTrue();
        assertThat(settings.getAiMemoryMaxTokens()).isEqualTo(1500);
        assertThat(settings.getAiMemoryLearningMode()).isEqualTo(AiMemoryLearningMode.PROPOSE);
        assertThat(settings.isAiSkillAutoCreate()).isTrue();
        assertThat(settings.getAiAgentTriageMode()).isEqualTo(AiAgentTriageMode.AUTO);
        assertThat(settings.isAiSkillStatisticsEnabled()).isTrue();
    }

    @Test
    void aRoundTripKeepsEveryValue() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiMemoryEnabled(false);
        settings.setAiMemoryMaxTokens(3000);
        settings.setAiMemoryLearningMode(AiMemoryLearningMode.OFF);
        settings.setAiSkillAutoCreate(false);
        settings.setAiAgentTriageMode(AiAgentTriageMode.NEVER);
        settings.setAiSkillStatisticsEnabled(false);

        GlobalSettings loaded = unmarshal(marshal(settings));

        assertThat(loaded.isAiMemoryEnabled()).isFalse();
        assertThat(loaded.getAiMemoryMaxTokens()).isEqualTo(3000);
        assertThat(loaded.getAiMemoryLearningMode()).isEqualTo(AiMemoryLearningMode.OFF);
        assertThat(loaded.isAiSkillAutoCreate()).isFalse();
        assertThat(loaded.getAiAgentTriageMode()).isEqualTo(AiAgentTriageMode.NEVER);
        assertThat(loaded.isAiSkillStatisticsEnabled()).isFalse();
    }

    @Test
    void theMemoryBudgetIsClampedToItsRange() throws Exception {
        GlobalSettings settings = new GlobalSettings();

        settings.setAiMemoryMaxTokens(10);
        assertThat(settings.getAiMemoryMaxTokens()).isEqualTo(GlobalSettings.AI_MEMORY_TOKENS_MIN);
        settings.setAiMemoryMaxTokens(90_000);
        assertThat(settings.getAiMemoryMaxTokens()).isEqualTo(GlobalSettings.AI_MEMORY_TOKENS_MAX);
        assertThat(unmarshal("<globalSettings><aiMemoryMaxTokens>100</aiMemoryMaxTokens></globalSettings>")
            .getAiMemoryMaxTokens()).isEqualTo(GlobalSettings.AI_MEMORY_TOKENS_MIN);
    }

    @Test
    void aNullEnumFallsBackToItsDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiMemoryLearningMode(null);
        settings.setAiAgentTriageMode(null);

        assertThat(settings.getAiMemoryLearningMode()).isEqualTo(AiMemoryLearningMode.PROPOSE);
        assertThat(settings.getAiAgentTriageMode()).isEqualTo(AiAgentTriageMode.AUTO);
    }
}
