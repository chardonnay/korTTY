package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringReader;
import java.io.StringWriter;
import org.testng.annotations.Test;

/**
 * A connection's keyword highlighting rule set ({@code highlightRuleSetId}): {@code null} follows the
 * global default, {@code "none"} switches highlighting off for the connection, any other value names a
 * set. It is a per-connection override like the terminal effect, so it survives every copy of the
 * connection and a round trip through {@code connections.xml}, and a file written before it existed
 * loads as "follow the default".
 */
public class ServerConnectionHighlightRuleSetTest {

    @Test
    void aNewConnectionFollowsTheDefault() {
        assertThat(new ServerConnection().getHighlightRuleSetId()).isNull();
    }

    @Test
    void blankMeansFollowTheDefaultAndValuesAreTrimmed() {
        ServerConnection connection = new ServerConnection();

        connection.setHighlightRuleSetId("  builtin.network  ");
        assertThat(connection.getHighlightRuleSetId()).isEqualTo("builtin.network");
        connection.setHighlightRuleSetId("none");
        assertThat(connection.getHighlightRuleSetId()).isEqualTo("none");
        connection.setHighlightRuleSetId("   ");
        assertThat(connection.getHighlightRuleSetId()).isNull();
        connection.setHighlightRuleSetId(null);
        assertThat(connection.getHighlightRuleSetId()).isNull();
    }

    @Test
    void everyCopyCarriesTheSetAndItsAbsence() {
        ServerConnection chosen = new ServerConnection("switch", "sw1.example.com", 22, "admin");
        chosen.setHighlightRuleSetId("builtin.network-devices");
        ServerConnection off = new ServerConnection("quiet", "db.example.com", 22, "root");
        off.setHighlightRuleSetId("none");
        ServerConnection inheriting = new ServerConnection("dev", "dev.example.com", 22, "root");

        for (ServerConnection source : new ServerConnection[] {chosen, off, inheriting}) {
            String expected = source.getHighlightRuleSetId();
            assertThat(ServerConnection.copyForAuth(source).getHighlightRuleSetId()).isEqualTo(expected);
            assertThat(ServerConnection.copyForDuplicate(source).getHighlightRuleSetId()).isEqualTo(expected);
            assertThat(ServerConnection.copyForExport(source, false, false, false, false).getHighlightRuleSetId())
                .isEqualTo(expected);
        }
    }

    @Test
    void theSetSurvivesAnXmlRoundTrip() throws Exception {
        ServerConnection connection = new ServerConnection("switch", "sw1.example.com", 22, "admin");
        connection.setHighlightRuleSetId("2f9c0f3e-user-set");

        String xml = marshal(connection);
        assertThat(xml).contains("<highlightRuleSetId>2f9c0f3e-user-set</highlightRuleSetId>");

        ServerConnection loaded = unmarshal(xml);
        assertThat(loaded.getHighlightRuleSetId()).isEqualTo("2f9c0f3e-user-set");
    }

    @Test
    void aConnectionWithoutASetWritesNoElementAndAnOldFileLoadsAsFollowTheDefault() throws Exception {
        ServerConnection connection = new ServerConnection("dev", "dev.example.com", 22, "root");

        String xml = marshal(connection);
        assertThat(xml).doesNotContain("highlightRuleSetId");
        assertThat(unmarshal(xml).getHighlightRuleSetId()).isNull();
    }

    private static String marshal(ServerConnection connection) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(ServerConnection.class).createMarshaller();
        StringWriter writer = new StringWriter();
        marshaller.marshal(connection, writer);
        return writer.toString();
    }

    private static ServerConnection unmarshal(String xml) throws Exception {
        return (ServerConnection) JAXBContext.newInstance(ServerConnection.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }
}
