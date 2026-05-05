package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

public class SessionStateTerminalEffectTest {

    @Test
    void terminalEffectPluginIdRoundTripsThroughXml() throws Exception {
        SessionState state = new SessionState("session-1", "connection-1");
        state.setTerminalEffectPluginId("mother");
        state.setTerminalEffectAnimationSpeed(2.0);

        JAXBContext context = JAXBContext.newInstance(
                SessionState.class,
                ConnectionSettings.class,
                SplitPaneState.class,
                TerminalTimestampEntry.class);
        StringWriter writer = new StringWriter();
        context.createMarshaller().marshal(state, writer);

        SessionState reloaded = (SessionState) context.createUnmarshaller()
                .unmarshal(new StringReader(writer.toString()));

        assertThat(writer.toString()).contains("<terminalEffectPluginId>mother</terminalEffectPluginId>");
        assertThat(writer.toString()).contains("<terminalEffectAnimationSpeed>2.0</terminalEffectAnimationSpeed>");
        assertThat(reloaded.getTerminalEffectPluginId()).isEqualTo("mother");
        assertThat(reloaded.getTerminalEffectAnimationSpeed()).isEqualTo(2.0);
    }
}
