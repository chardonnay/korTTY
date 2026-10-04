package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.paste.PasteWarningMode;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringReader;
import java.io.StringWriter;
import org.testng.annotations.Test;

/**
 * A connection's own paste protection ({@code pasteWarningMode}, {@code pasteLineDelayMs}): {@code null}
 * follows Settings → Terminal → Paste protection, any other value is the connection's own. It is a
 * per-connection override like the encoding and the highlighting rule set, so it survives every copy of
 * the connection and a round trip through {@code connections.xml}, and a file written before it existed
 * loads as "follow Settings".
 */
public class ServerConnectionPasteOverrideTest {

    @Test
    void aNewConnectionFollowsTheGlobalPasteProtection() {
        ServerConnection connection = new ServerConnection();

        assertThat(connection.getPasteWarningMode()).isNull();
        assertThat(connection.getPasteLineDelayMs()).isNull();
    }

    @Test
    void theWarningModeIsStoredByItsStableIdAndNullFollowsSettings() throws Exception {
        ServerConnection connection = new ServerConnection();

        for (PasteWarningMode mode : PasteWarningMode.values()) {
            connection.setPasteWarningMode(mode);
            assertThat(connection.getPasteWarningMode()).isEqualTo(mode);
            assertThat(storedWarningMode(connection)).isEqualTo(mode.id());
        }
        connection.setPasteWarningMode(null);
        assertThat(connection.getPasteWarningMode()).isNull();
        assertThat(storedWarningMode(connection)).isNull();
    }

    @Test
    void anUnknownStoredWarningModeFollowsSettingsInsteadOfSwitchingProtectionOff() throws Exception {
        ServerConnection connection = unmarshal(
            "<connection id=\"c1\"><name>future</name><pasteWarningMode>paranoid</pasteWarningMode></connection>");

        assertThat(connection.getPasteWarningMode()).isNull();
    }

    @Test
    void theLineDelayIsClampedAndZeroIsAValueOfItsOwn() {
        ServerConnection connection = new ServerConnection();

        connection.setPasteLineDelayMs(50);
        assertThat(connection.getPasteLineDelayMs()).isEqualTo(50);
        connection.setPasteLineDelayMs(0);
        assertThat(connection.getPasteLineDelayMs()).isEqualTo(0);
        connection.setPasteLineDelayMs(-5);
        assertThat(connection.getPasteLineDelayMs()).isEqualTo(0);
        connection.setPasteLineDelayMs(5_000);
        assertThat(connection.getPasteLineDelayMs()).isEqualTo(1000);
        connection.setPasteLineDelayMs(null);
        assertThat(connection.getPasteLineDelayMs()).isNull();
    }

    @Test
    void anOutOfRangeLineDelayInAFileIsClampedWhenRead() throws Exception {
        ServerConnection connection = unmarshal(
            "<connection id=\"c1\"><name>hand-edited</name><pasteLineDelayMs>99999</pasteLineDelayMs></connection>");

        assertThat(connection.getPasteLineDelayMs()).isEqualTo(1000);
    }

    @Test
    void everyCopyCarriesTheOverridesAndTheirAbsence() {
        ServerConnection production = new ServerConnection("prod", "db.example.com", 22, "root");
        production.setPasteWarningMode(PasteWarningMode.ALWAYS);
        ServerConnection console = new ServerConnection("console", "con.example.com", 22, "admin");
        console.setPasteWarningMode(PasteWarningMode.OFF);
        console.setPasteLineDelayMs(0);
        ServerConnection paced = new ServerConnection("switch", "sw1.example.com", 22, "admin");
        paced.setPasteLineDelayMs(120);
        ServerConnection inheriting = new ServerConnection("dev", "dev.example.com", 22, "root");

        for (ServerConnection source : new ServerConnection[] {production, console, paced, inheriting}) {
            PasteWarningMode mode = source.getPasteWarningMode();
            Integer delay = source.getPasteLineDelayMs();
            for (ServerConnection copy : new ServerConnection[] {
                    ServerConnection.copyForAuth(source),
                    ServerConnection.copyForDuplicate(source),
                    ServerConnection.copyForExport(source, false, false, false, false)}) {
                assertThat(copy.getPasteWarningMode()).isEqualTo(mode);
                assertThat(copy.getPasteLineDelayMs()).isEqualTo(delay);
            }
        }
    }

    @Test
    void theOverridesSurviveAnXmlRoundTrip() throws Exception {
        ServerConnection connection = new ServerConnection("prod", "db.example.com", 22, "root");
        connection.setPasteWarningMode(PasteWarningMode.ALWAYS);
        connection.setPasteLineDelayMs(0);

        String xml = marshal(connection);
        assertThat(xml).contains("<pasteWarningMode>always</pasteWarningMode>");
        assertThat(xml).contains("<pasteLineDelayMs>0</pasteLineDelayMs>");

        ServerConnection loaded = unmarshal(xml);
        assertThat(loaded.getPasteWarningMode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(loaded.getPasteLineDelayMs()).isEqualTo(0);
    }

    @Test
    void aConnectionWithoutOverridesWritesNoElementAndAnOldFileLoadsAsFollowSettings() throws Exception {
        ServerConnection connection = new ServerConnection("dev", "dev.example.com", 22, "root");

        String xml = marshal(connection);
        assertThat(xml).doesNotContain("pasteWarningMode");
        assertThat(xml).doesNotContain("pasteLineDelayMs");
        ServerConnection loaded = unmarshal(xml);
        assertThat(loaded.getPasteWarningMode()).isNull();
        assertThat(loaded.getPasteLineDelayMs()).isNull();
    }

    private static String storedWarningMode(ServerConnection connection) throws Exception {
        var field = ServerConnection.class.getDeclaredField("pasteWarningMode");
        field.setAccessible(true);
        return (String) field.get(connection);
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
