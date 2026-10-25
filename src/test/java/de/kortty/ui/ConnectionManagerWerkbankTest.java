package de.kortty.ui;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The Connection Manager's library, list and inspector: the search, the texts of a connection's
 * row, and the strings the redesign added, which exist in every bundle with their placeholders.
 */
class ConnectionManagerWerkbankTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties", "messages_de.properties", "messages_it.properties", "messages_es.properties",
        "messages_pt.properties", "messages_fr.properties", "messages_hr.properties", "messages_nl.properties");

    private static final List<String> SOURCES = List.of("ConnectionManagerDialog.java", "ConnectionMapView.java",
        "ConnectionInspectorPane.java", "ConnectionRowGraphics.java", "ConnectionManagerTreeView.java",
        "OsBadgeSupport.java", "OsBadgeManagerDialog.java", "ConnectionOverviewExportDialog.java");

    private static ServerConnection connection(String name, String host, String tag) {
        ServerConnection connection = new ServerConnection();
        connection.setName(name);
        connection.setHost(host);
        connection.setTag(tag);
        return connection;
    }

    @Test
    void theSearchMatchesNameHostOrTagIgnoringCase() {
        var search = ConnectionManagerDialog.searchPredicate("PROD");
        assertThat(search.test(connection("web-01", "web-01.prod.example", null))).isTrue();
        assertThat(search.test(connection("db", "db.example", "prod"))).isTrue();
        assertThat(search.test(connection("devbox", "devbox.lab.example", "dev"))).isFalse();
        assertWithMessage("no text, no filter").that(ConnectionManagerDialog.searchPredicate("  ")).isNull();
    }

    @Test
    void aStarMatchesTheWholeValueAndEverythingElseIsLiteral() {
        var glob = ConnectionManagerDialog.searchPredicate("web-*");
        assertThat(glob.test(connection("web-01", "x.example", null))).isTrue();
        assertThat(glob.test(connection("old-web-01", "x.example", null))).isFalse();
        var dotted = ConnectionManagerDialog.searchPredicate("192.0.2.*");
        assertThat(dotted.test(connection("switch", "192.0.2.10", null))).isTrue();
        assertWithMessage("the dot is no wildcard").that(dotted.test(connection("switch", "192x0x2x10", null))).isFalse();
        var brackets = ConnectionManagerDialog.searchPredicate("[lab]*");
        assertThat(brackets.test(connection("[lab] box", "box.example", null))).isTrue();
    }

    @Test
    void aRowShowsUserHostAndAnUnusualPort() {
        ServerConnection web = connection("web-01", "web-01.prod.example", null);
        web.setUsername("deploy");
        assertThat(ConnectionRowGraphics.address(web)).isEqualTo("deploy@web-01.prod.example");
        web.setPort(2222);
        assertThat(ConnectionRowGraphics.address(web)).isEqualTo("deploy@web-01.prod.example:2222");
        assertThat(ConnectionRowGraphics.glyphFor(web)).isEqualTo("server");

        ServerConnection mosh = connection("devbox", "devbox.lab.example", null);
        mosh.setProtocol(ConnectionProtocol.MOSH);
        assertThat(ConnectionRowGraphics.glyphFor(mosh)).isEqualTo("mosh");
        ServerConnection shell = connection("zsh", null, null);
        shell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        shell.setLocalShellCommand("/bin/zsh");
        assertThat(ConnectionRowGraphics.glyphFor(shell)).isEqualTo("localShell");
        assertThat(ConnectionRowGraphics.address(shell)).isEqualTo("/bin/zsh");
    }

    @Test
    void onlyAnEnabledJumpServerAndEnabledTunnelsCount() {
        ServerConnection db = connection("db", "db-01.prod.example", null);
        JumpServer jump = new JumpServer("bastion.example", 2222, "ops");
        db.setJumpServer(jump);
        assertWithMessage("a configured but disabled jump server is no route").that(ConnectionRowGraphics.jumpTarget(db)).isNull();
        jump.setEnabled(true);
        assertThat(ConnectionRowGraphics.jumpTarget(db)).isEqualTo("bastion.example:2222");

        SSHTunnel on = new SSHTunnel();
        on.setEnabled(true);
        on.setType(TunnelType.LOCAL);
        on.setLocalPort(15432);
        on.setRemoteHost("localhost");
        on.setRemotePort(5432);
        SSHTunnel off = new SSHTunnel();
        db.setSshTunnels(new ArrayList<>(List.of(on, off)));
        assertThat(ConnectionRowGraphics.enabledTunnels(db)).containsExactly(on);
        assertThat(ConnectionRowGraphics.describeTunnel(on)).isEqualTo("L 15432 → localhost:5432");
        assertThat(ConnectionRowGraphics.tunnelSummary(db)).isEqualTo("L 15432 → localhost:5432");
    }

    @Test
    void everyStringOfTheRedesignExistsInEveryBundleWithItsPlaceholders() throws IOException {
        List<String> keys = usedKeys();
        assertWithMessage("the redesign's sources name their strings").that(keys.size()).isGreaterThan(60);
        Properties english = bundle("messages.properties");
        for (String name : BUNDLES) {
            Properties localized = bundle(name);
            for (String key : keys) {
                String value = localized.getProperty(key);
                assertWithMessage(name + " is missing " + key).that(value).isNotNull();
                assertWithMessage(name + " has a blank " + key).that(value.isBlank()).isFalse();
                assertWithMessage(name + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
                if (english.getProperty(key).contains("{0}")) {
                    assertWithMessage(name + " lost the placeholder of " + key).that(value).contains("{0}");
                }
            }
        }
    }

    /** The {@code connectionManager.*} keys the redesign's sources ask I18n for, and the ones built from names. */
    private static List<String> usedKeys() throws IOException {
        List<String> keys = sourceKeys();
        for (de.kortty.core.ConnectionOverviewExport.Part part : de.kortty.core.ConnectionOverviewExport.Part.values()) {
            keys.add("connectionManager.overview.part." + part.name());
        }
        keys.add("connEdit.osBadge");
        return keys;
    }

    private static List<String> sourceKeys() throws IOException {
        Pattern call = Pattern.compile("\"(connectionManager\\.[A-Za-z.]+)\"");
        List<String> keys = new ArrayList<>();
        for (String source : SOURCES) {
            Matcher matcher = call.matcher(Files.readString(Path.of("src/main/java/de/kortty/ui", source)));
            while (matcher.find()) {
                if (!keys.contains(matcher.group(1)) && !matcher.group(1).endsWith(".")) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (var in = ConnectionManagerWerkbankTest.class.getResourceAsStream("/i18n/" + name)) {
            assertWithMessage(name + " is on the classpath").that(in).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
