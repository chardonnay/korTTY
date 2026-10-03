package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

class AiProfileTrustedLocalEndpointTest {

    @Test
    void isOffByDefaultAndSurvivesTheCopyConstructor() {
        AiProfile profile = new AiProfile();
        assertThat(profile.isTrustedLocalEndpoint()).isFalse();

        profile.setTrustedLocalEndpoint(true);
        assertThat(new AiProfile(profile).isTrustedLocalEndpoint()).isTrue();
    }

    @Test
    void roundTripsThroughXmlAndIsOmittedWhileOff() throws Exception {
        JAXBContext context = JAXBContext.newInstance(AiProfile.class);
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");

        StringWriter off = new StringWriter();
        context.createMarshaller().marshal(profile, off);
        assertThat(off.toString()).doesNotContain("trustedLocalEndpoint");

        profile.setTrustedLocalEndpoint(true);
        StringWriter on = new StringWriter();
        context.createMarshaller().marshal(profile, on);
        AiProfile restored = (AiProfile) context.createUnmarshaller().unmarshal(new StringReader(on.toString()));
        assertThat(restored.isTrustedLocalEndpoint()).isTrue();
    }
}
