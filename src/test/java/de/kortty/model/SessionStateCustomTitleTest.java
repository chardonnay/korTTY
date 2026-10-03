package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

/**
 * A renamed terminal tab keeps its name in a project through the existing {@code tabTitle}
 * element, so no new schema is needed and projects saved before carry no name.
 */
public class SessionStateCustomTitleTest {

    private static JAXBContext context() throws Exception {
        return JAXBContext.newInstance(
                SessionState.class,
                ConnectionSettings.class,
                SplitPaneState.class,
                TerminalTimestampEntry.class);
    }

    @Test
    void aTerminalSessionsCustomTitleRoundTripsThroughXml() throws Exception {
        SessionState state = new SessionState("session-1", "connection-1");
        state.setTabType(SessionState.TabType.TERMINAL);
        state.setGroup("Ops");
        state.setTabTitle("Billing primary");

        StringWriter writer = new StringWriter();
        context().createMarshaller().marshal(state, writer);
        SessionState reloaded = (SessionState) context().createUnmarshaller()
                .unmarshal(new StringReader(writer.toString()));

        assertThat(writer.toString()).contains("<tabTitle>Billing primary</tabTitle>");
        assertThat(reloaded.getTabType()).isEqualTo(SessionState.TabType.TERMINAL);
        assertThat(reloaded.getTabTitle()).isEqualTo("Billing primary");
        assertThat(reloaded.getGroup()).isEqualTo("Ops");
    }

    @Test
    void aTabWithoutCustomTitleWritesNoTabTitle() throws Exception {
        SessionState state = new SessionState("session-1", "connection-1");
        state.setTabType(SessionState.TabType.TERMINAL);

        StringWriter writer = new StringWriter();
        context().createMarshaller().marshal(state, writer);

        assertThat(writer.toString()).doesNotContain("tabTitle");
    }

    @Test
    void aTerminalSessionSavedBeforeLoadsWithoutCustomTitle() throws Exception {
        String saved = """
                <session>
                    <tabType>TERMINAL</tabType>
                    <sessionId>session-1</sessionId>
                    <connectionId>connection-1</connectionId>
                    <group>Ops</group>
                </session>
                """;

        SessionState reloaded = (SessionState) context().createUnmarshaller().unmarshal(new StringReader(saved));

        assertThat(reloaded.getConnectionId()).isEqualTo("connection-1");
        assertThat(reloaded.getGroup()).isEqualTo("Ops");
        assertThat(reloaded.getTabTitle()).isNull();
    }
}
