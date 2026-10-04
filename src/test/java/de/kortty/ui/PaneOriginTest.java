package de.kortty.ui;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A "same server" split opens on the server of the pane it splits: a pane opened with "Split (new
 * connection)" to server B splits to B, not to the tab's server A, and so do the panes split from
 * it. Every pane without an origin of its own runs the tab's. The registry is exercised with plain
 * objects standing in for panes and connectors; the wiring into TerminalView, which needs a live
 * stage, is pinned in its source.
 */
class PaneOriginTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    private final PaneOrigin tab = new PaneOrigin(ssh("a.example.com"), "tab-secret", null);

    private final PaneOrigin serverB = new PaneOrigin(ssh("b.example.com"), "b-secret", null);

    @Test
    void aSameServerSplitOfAPaneOnServerBTargetsB() {
        PaneOrigins<Object, Object> origins = new PaneOrigins<>();
        Object paneB = new Object();
        Object connectorB = new Object();
        // "Split (new connection)" to B: the connector is built first, the pane bound to it after.
        origins.expect(connectorB, serverB);
        origins.bind(paneB, connectorB);
        assertThat(origins.resolve(paneB, tab)).isSameInstanceAs(serverB);

        // A same-server split of that pane connects to B and the new pane inherits B ...
        Object child = splitSameServer(origins, paneB);
        assertThat(origins.resolve(child, tab)).isSameInstanceAs(serverB);
        // ... and so does a split of the split.
        Object grandChild = splitSameServer(origins, child);
        assertThat(origins.resolve(grandChild, tab).connection().getHost()).isEqualTo("b.example.com");
        assertThat(origins.pendingConnectorCount()).isEqualTo(0);
    }

    @Test
    void aMissingOriginFallsBackToTheTabsConnection() {
        PaneOrigins<Object, Object> origins = new PaneOrigins<>();
        Object firstPane = new Object();
        assertThat(origins.recorded(firstPane)).isNull();
        assertThat(origins.resolve(firstPane, tab)).isSameInstanceAs(tab);
        assertThat(origins.resolve(null, tab)).isSameInstanceAs(tab);

        // A same-server split of the tab's pane records nothing: the new pane follows the tab as it
        // is when it is asked (its temporary key may be replaced later), not a copy taken now.
        Object child = splitSameServer(origins, firstPane);
        assertThat(origins.recorded(child)).isNull();
        assertThat(origins.recordedPaneCount()).isEqualTo(0);
        assertThat(origins.pendingConnectorCount()).isEqualTo(0);
        PaneOrigin newTab = new PaneOrigin(tab.connection(), "changed", null);
        assertThat(origins.resolve(child, newTab)).isSameInstanceAs(newTab);
    }

    @Test
    void aClosedPaneFallsBackToTheTab() {
        PaneOrigins<Object, Object> origins = new PaneOrigins<>();
        Object paneB = new Object();
        Object connectorB = new Object();
        origins.expect(connectorB, serverB);
        origins.bind(paneB, connectorB);

        origins.forget(paneB);
        assertThat(origins.recordedPaneCount()).isEqualTo(0);
        assertThat(origins.resolve(paneB, tab)).isSameInstanceAs(tab);
    }

    @Test
    void decoratingTheSameSessionAgainKeepsThePanesOrigin() {
        // A Mosh recovery or an effect change decorates a live pane again: nothing was expected for
        // that connector, so the pane stays on its server.
        PaneOrigins<Object, Object> origins = new PaneOrigins<>();
        Object paneB = new Object();
        Object connectorB = new Object();
        origins.expect(connectorB, serverB);
        origins.bind(paneB, connectorB);

        origins.bind(paneB, connectorB);
        origins.bind(paneB, new Object());
        origins.bind(paneB, null);
        origins.bind(null, connectorB);
        assertThat(origins.resolve(paneB, tab)).isSameInstanceAs(serverB);
    }

    @Test
    void anExpectedOriginGoesToThePaneOfItsOwnConnectorOnce() {
        PaneOrigins<Object, Object> origins = new PaneOrigins<>();
        Object connectorB = new Object();
        Object otherPane = new Object();
        origins.expect(connectorB, serverB);

        origins.bind(otherPane, new Object());
        assertThat(origins.recorded(otherPane)).isNull();
        assertThat(origins.pendingConnectorCount()).isEqualTo(1);

        Object paneB = new Object();
        origins.bind(paneB, connectorB);
        assertThat(origins.recorded(paneB)).isSameInstanceAs(serverB);
        Object lateBinder = new Object();
        origins.bind(lateBinder, connectorB);
        assertThat(origins.recorded(lateBinder)).isNull();
    }

    @Test
    void panesAndConnectorsAreKeyedByIdentityNotEquality() {
        PaneOrigins<String, String> origins = new PaneOrigins<>();
        String connector = new String("session");
        String equalConnector = new String("session");
        String pane = new String("pane");
        String equalPane = new String("pane");
        origins.expect(connector, serverB);

        origins.bind(pane, equalConnector);
        assertThat(origins.recorded(pane)).isNull();
        origins.bind(pane, connector);
        assertThat(origins.recorded(pane)).isSameInstanceAs(serverB);
        assertThat(origins.recorded(equalPane)).isNull();
    }

    @Test
    void anOriginNeverPrintsItsPasswordOrKey() {
        PaneOrigin origin = new PaneOrigin(ssh("b.example.com"), "hunter2", new TemporarySSHKey("KEY-MATERIAL", 5));
        assertThat(origin.toString()).contains("b.example.com");
        assertThat(origin.toString()).doesNotContain("hunter2");
        assertThat(origin.toString()).doesNotContain("KEY-MATERIAL");
    }

    @Test
    void theSplitConnectorUsesTheParentPanesOrigin() throws IOException {
        String view = source("TerminalView.java");
        String split = methodBody(view, "private @Nullable TtyConnector createSplitConnector(");
        int inherited = split.indexOf("PaneOrigin inherited = paneOrigins.recorded(request.getParentWidget());");
        assertWithMessage("a same-server split reads the origin of the pane it splits").that(inherited).isAtLeast(0);
        int connect = split.indexOf("createSameServerConnection(PaneOrigin.resolve(inherited, tabOrigin()))");
        assertThat(connect).isGreaterThan(inherited);
        assertThat(split.indexOf("paneOrigins.expect(connector, inherited);")).isGreaterThan(connect);

        String same = methodBody(view, "private @Nullable TtyConnector doCreateSameServerConnection(PaneOrigin origin)");
        assertWithMessage("the same-server split connects to the pane's origin, not the tab's connection")
            .that(same).contains("createConnectorForConnection(target, origin.password())");
        assertThat(same).doesNotContain("createConnectorForConnection(connection, password)");
        assertThat(same).contains("origin.temporaryKey()");
        assertThat(same).doesNotContain("temporarySSHKey");
    }

    @Test
    void aNewConnectionSplitRecordsItsOriginOnlyOnceConnected() throws IOException {
        String view = source("TerminalView.java");
        String connect = methodBody(view, "private @Nullable TtyConnector doCreateNewConnectionForSplit(");
        int failed = connect.indexOf("if (!connected)");
        int expect = connect.indexOf("paneOrigins.expect(newConnector,");
        assertThat(failed).isAtLeast(0);
        assertWithMessage("the origin is recorded after the failed-connect return").that(expect).isGreaterThan(failed);
        assertThat(connect).contains("connResult.temporarySSHKey");
        assertWithMessage("the split dialog hands the temporary key along")
            .that(methodBody(source("MainWindow.java"),
                "private TerminalView.ConnectionResult requestNewConnectionForSplit("))
            .contains("new TerminalView.ConnectionResult(result.connection(), finalPassword, result.temporarySSHKey())");
    }

    @Test
    void theDecoratorBindsAndAClosedPaneIsForgotten() throws IOException {
        String view = source("TerminalView.java");
        String decorate = methodBody(view,
            "private TtyConnector decorateTerminalConnector(SithTermFxWidget widget, TtyConnector connector)");
        int unwrap = decorate.indexOf("TtyConnector baseConnector = unwrapTerminalEffectConnector(connector);");
        int bind = decorate.indexOf("paneOrigins.bind(widget, baseConnector);");
        assertThat(unwrap).isAtLeast(0);
        assertWithMessage("the origin is keyed by the base connector, never a wrapper").that(bind).isGreaterThan(unwrap);
        assertThat(methodBody(view, "private void releasePaneState(SithTermFxWidget widget)"))
            .contains("paneOrigins.forget(widget);");
    }

    /** A same-server split as TerminalView makes it: inherit the parent's recorded origin. */
    private static Object splitSameServer(PaneOrigins<Object, Object> origins, Object parent) {
        Object connector = new Object();
        origins.expect(connector, origins.recorded(parent));
        Object pane = new Object();
        origins.bind(pane, connector);
        return pane;
    }

    private static ServerConnection ssh(String host) {
        ServerConnection connection = new ServerConnection();
        connection.setHost(host);
        connection.setPort(22);
        connection.setUsername("ops");
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        return connection;
    }

    private static String source(String fileName) throws IOException {
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The body of the method declared with {@code declaration}, up to its matching brace. */
    private static String methodBody(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertWithMessage("declaration %s", declaration).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        throw new AssertionError("unterminated method " + declaration);
    }
}
