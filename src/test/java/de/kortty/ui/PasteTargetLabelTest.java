package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.LocalShellTtyConnector;
import de.kortty.core.Mosh4jTtyConnector;
import de.kortty.core.NativeMoshTtyConnector;
import de.kortty.core.SshTtyConnector;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * A paste confirmation names the pane it pastes into by the connection that pane runs. A pane opened
 * with Split (new connection) can run a connection to another server than the tab's, and asking
 * "Paste this text into prod-db?" for a pane on staging, or the other way round, would defeat the
 * point of asking.
 */
class PasteTargetLabelTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    @Test
    void everyTerminalConnectorSaysWhichConnectionItRuns() {
        ServerConnection ssh = connection("staging-web", ConnectionProtocol.SSH_TCP);
        ServerConnection mosh = connection("laptop", ConnectionProtocol.MOSH);
        ServerConnection nativeMosh = connection("edge", ConnectionProtocol.MOSH_CLIENT);
        ServerConnection local = connection("zsh", ConnectionProtocol.LOCAL_SHELL);

        SshTtyConnector sshConnector = TerminalView.sshConnectorWithVault(ssh, "pw", null, null);
        assertThat(TerminalView.connectionOf(sshConnector)).isSameInstanceAs(ssh);
        assertThat(TerminalView.connectionOf(new Mosh4jTtyConnector(mosh, "pw"))).isSameInstanceAs(mosh);
        assertThat(TerminalView.connectionOf(new NativeMoshTtyConnector(nativeMosh, "pw")))
            .isSameInstanceAs(nativeMosh);
        assertThat(TerminalView.connectionOf(new LocalShellTtyConnector(local))).isSameInstanceAs(local);
        assertThat(TerminalView.connectionOf(null)).isNull();
    }

    @Test
    void thePanesOwnConnectionNamesItNotTheTabs() {
        ServerConnection tab = connection("prod-db-01", ConnectionProtocol.SSH_TCP);
        ServerConnection pane = connection("staging-web", ConnectionProtocol.SSH_TCP);

        assertThat(TerminalView.paneConnectionLabel(pane, tab)).isEqualTo("staging-web");
        assertWithMessage("a pane that is not connected yet").that(TerminalView.paneConnectionLabel(null, tab))
            .isEqualTo("prod-db-01");
        assertThat(TerminalView.paneConnectionLabel(null, null)).isEmpty();
    }

    @Test
    void theConfirmationAsksWithThePanesLabel() throws IOException {
        String view = Files.readString(TERMINAL_VIEW, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(view).contains(
            "korttyWidget.describePasteTarget(() -> pasteTargetLabel(korttyWidget), () -> connectorCharset(korttyWidget),");
        assertWithMessage("the tab's connection would name the wrong server for a Split (new connection) pane")
            .that(view).doesNotContain("describePasteTarget(connection::getDisplayName");
        int start = view.indexOf("private String pasteTargetLabel(SithTermFxWidget widget) {");
        assertThat(start).isAtLeast(0);
        assertThat(view.substring(start, view.indexOf("\n    }\n", start)))
            .contains("unwrapTerminalEffectConnector(widget.getTtyConnector())");
    }

    private static ServerConnection connection(String name, ConnectionProtocol protocol) {
        ServerConnection connection = new ServerConnection(name, "host.example", 22, "daniel");
        connection.setProtocol(protocol);
        return connection;
    }
}
