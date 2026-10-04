package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

class AiProfileMaxOutputTokensTest {

    @Test
    void defaultsToAutomaticAndNormalizesNonPositiveValues() {
        AiProfile profile = new AiProfile();
        assertThat(profile.getMaxOutputTokens()).isNull();

        profile.setMaxOutputTokens(0);
        assertThat(profile.getMaxOutputTokens()).isNull();
        profile.setMaxOutputTokens(-5);
        assertThat(profile.getMaxOutputTokens()).isNull();

        profile.setMaxOutputTokens(12_000);
        assertThat(new AiProfile(profile).getMaxOutputTokens()).isEqualTo(12_000);
    }

    @Test
    void roundTripsThroughGlobalSettingsXml() throws Exception {
        JAXBContext context = JAXBContext.newInstance(GlobalSettings.class);
        AiProfile profile = new AiProfile();
        profile.setName("claude");
        profile.setMaxOutputTokens(8_192);
        GlobalSettings settings = new GlobalSettings();
        settings.getAiProfiles().add(profile);

        StringWriter xml = new StringWriter();
        context.createMarshaller().marshal(settings, xml);
        assertThat(xml.toString()).contains("<maxOutputTokens>8192</maxOutputTokens>");

        GlobalSettings restored = (GlobalSettings) context.createUnmarshaller()
            .unmarshal(new StringReader(xml.toString()));
        assertThat(restored.getAiProfiles()).hasSize(1);
        assertThat(restored.getAiProfiles().get(0).getMaxOutputTokens()).isEqualTo(8_192);
    }

    @Test
    void nullIsOmittedAndAnOldXmlLoadsAsAutomatic() throws Exception {
        JAXBContext context = JAXBContext.newInstance(AiProfile.class);
        AiProfile profile = new AiProfile();
        profile.setName("auto");

        StringWriter xml = new StringWriter();
        context.createMarshaller().marshal(profile, xml);
        assertThat(xml.toString()).doesNotContain("maxOutputTokens");

        String oldXml = "<aiProfile><name>old</name><model>claude-opus-4-5</model></aiProfile>";
        AiProfile restored = (AiProfile) context.createUnmarshaller().unmarshal(new StringReader(oldXml));
        assertThat(restored.getName()).isEqualTo("old");
        assertThat(restored.getMaxOutputTokens()).isNull();
    }
}
