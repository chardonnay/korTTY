package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringReader;
import java.io.StringWriter;
import org.testng.annotations.Test;

/**
 * A connection's KI-Agent design combination ({@code aiAgentDesignId}): {@code null} follows the global
 * default. It stays with the connection on this computer (sign-in copies, duplicates, connections.xml)
 * but is not exported, because own combinations live in this computer's global settings.
 */
public class ServerConnectionAiAgentDesignTest {

    @Test
    void aNewConnectionFollowsTheDefaultAndBlankMeansDefault() {
        ServerConnection connection = new ServerConnection();
        assertThat(connection.getAiAgentDesignId()).isNull();

        connection.setAiAgentDesignId("  classic ");
        assertThat(connection.getAiAgentDesignId()).isEqualTo("classic");
        connection.setAiAgentDesignId("  ");
        assertThat(connection.getAiAgentDesignId()).isNull();
    }

    @Test
    void localCopiesCarryItButExportsDoNot() {
        ServerConnection source = new ServerConnection("web", "web.example.com", 22, "admin");
        source.setAiAgentDesignId("custom-1a2b3c4d");

        assertThat(ServerConnection.copyForAuth(source).getAiAgentDesignId()).isEqualTo("custom-1a2b3c4d");
        assertThat(ServerConnection.copyForDuplicate(source).getAiAgentDesignId()).isEqualTo("custom-1a2b3c4d");
        assertThat(ServerConnection.copyForExport(source, false, false, false, false).getAiAgentDesignId()).isNull();
    }

    @Test
    void itSurvivesAnXmlRoundTripAndOldFilesFollowTheDefault() throws Exception {
        ServerConnection connection = new ServerConnection("web", "web.example.com", 22, "admin");
        connection.setAiAgentDesignId("classic");
        JAXBContext context = JAXBContext.newInstance(ServerConnection.class);
        Marshaller marshaller = context.createMarshaller();
        StringWriter xml = new StringWriter();
        marshaller.marshal(connection, xml);
        assertThat(xml.toString()).contains("<aiAgentDesignId>classic</aiAgentDesignId>");

        ServerConnection back = (ServerConnection) context.createUnmarshaller().unmarshal(new StringReader(xml.toString()));
        assertThat(back.getAiAgentDesignId()).isEqualTo("classic");

        ServerConnection old = (ServerConnection) context.createUnmarshaller().unmarshal(new StringReader(
            xml.toString().replace("<aiAgentDesignId>classic</aiAgentDesignId>", "")));
        assertThat(old.getAiAgentDesignId()).isNull();
    }
}
