package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import jakarta.xml.bind.JAXBContext;
import java.io.StringReader;
import java.io.StringWriter;
import org.testng.annotations.Test;

/**
 * The per-connection KI-Agent switch ({@code aiAgentDisabled}): off by default, stored only when set, and
 * carried by every copy including export, so a shared production connection stays locked.
 */
public class ServerConnectionAiAgentDisabledTest {

    @Test
    void aNewConnectionAllowsTheAgent() {
        ServerConnection connection = new ServerConnection();
        assertThat(connection.isAiAgentDisabled()).isFalse();
        connection.setAiAgentDisabled(true);
        assertThat(connection.isAiAgentDisabled()).isTrue();
        connection.setAiAgentDisabled(false);
        assertThat(connection.isAiAgentDisabled()).isFalse();
    }

    @Test
    void everyCopyCarriesTheLock() {
        ServerConnection source = new ServerConnection("prod", "prod.example.com", 22, "admin");
        source.setAiAgentDisabled(true);

        assertThat(ServerConnection.copyForAuth(source).isAiAgentDisabled()).isTrue();
        assertThat(ServerConnection.copyForDuplicate(source).isAiAgentDisabled()).isTrue();
        ServerConnection exported = ServerConnection.copyForExport(source, false, false, false, false);
        assertThat(exported.isAiAgentDisabled()).isTrue();
        assertThat(ServerConnection.copyForImport(exported, false, false, false, false).isAiAgentDisabled()).isTrue();
    }

    @Test
    void onlyALockedConnectionWritesTheElement() throws Exception {
        JAXBContext context = JAXBContext.newInstance(ServerConnection.class);
        ServerConnection connection = new ServerConnection("prod", "prod.example.com", 22, "admin");
        StringWriter plain = new StringWriter();
        context.createMarshaller().marshal(connection, plain);
        assertThat(plain.toString()).doesNotContain("aiAgentDisabled");

        connection.setAiAgentDisabled(true);
        StringWriter locked = new StringWriter();
        context.createMarshaller().marshal(connection, locked);
        assertThat(locked.toString()).contains("<aiAgentDisabled>true</aiAgentDisabled>");
        ServerConnection back = (ServerConnection) context.createUnmarshaller()
            .unmarshal(new StringReader(locked.toString()));
        assertThat(back.isAiAgentDisabled()).isTrue();
    }
}
