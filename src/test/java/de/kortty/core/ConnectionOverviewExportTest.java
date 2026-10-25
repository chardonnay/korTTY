package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.core.ConnectionOverviewExport.Context;
import de.kortty.core.ConnectionOverviewExport.Format;
import de.kortty.core.ConnectionOverviewExport.Part;
import de.kortty.isolation.IsolationLevel;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSource;
import de.kortty.model.JumpServer;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.testng.annotations.Test;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The connection overview: all four formats carry the same entries, a part is written only when it is
 * ticked, and no secret reaches a file whatever is ticked. Each format is read back with the parser it
 * has (Gson, tomlj, the JDK DOM parser). No YAML parser is on the classpath, so the YAML block is matched
 * line by line, and its quoted strings are decoded to compare them with the originals.
 */
public class ConnectionOverviewExportTest {

    private static final Instant EXPORTED_AT = Instant.parse("2026-10-25T10:00:00Z");
    private static final Set<Part> ALL = EnumSet.allOf(Part.class);
    /** Names, quotes, colons, a hash, a line break, markup, an umlaut and a tab, all in one name. */
    private static final String SPECIAL_NAME = "Quote \"q\": colon # hash\nline <&> Müller ]]>\tTab\r\n";
    private static final String SPECIAL_DESCRIPTION = "Postgres \"prod\": #1 <&> ]]> \r\nline2";
    private static final List<String> NAMES = List.of(
            "Prod DB", "Mosh box", "zsh", "Team Staging ümlaut", "123", SPECIAL_NAME);

    private static final Context CONTEXT = new Context("2.23.0", EXPORTED_AT,
            id -> "cred-prod".equals(id) ? "CredName-MARK" : null,
            id -> "key-laptop".equals(id) ? "SshKeyName-MARK" : null,
            connection -> connection.getName() != null && connection.getName().startsWith("Prod"),
            connection -> connection.getName() != null && connection.getName().startsWith("Prod")
                    ? "ubuntu-MARK" : null,
            connection -> "TeamShare-MARK");

    /** Each part and a value that only that part writes. None of these may show up when the part is unticked. */
    private static final Map<Part, List<String>> PART_MARKERS = Map.of(
            Part.FOLDER, List.of("Group-MARK", "tag-MARK", "D32F2F", "favorite"),
            Part.AUTHENTICATION, List.of("CredName-MARK", "SshKeyName-MARK", "path-MARK"),
            Part.JUMP_SERVER, List.of("jump-MARK.example.org"),
            Part.TUNNELS, List.of("db-MARK.internal", "tunnel"),
            Part.TERMINAL, List.of("highlight-MARK", "effect-MARK", "connectionTimeoutSeconds"),
            Part.SECURITY, List.of("sandbox", "strictTerminalMode"),
            Part.USAGE, List.of("usageCount", "2026-09-21T14:13:20Z"),
            Part.SOURCE, List.of("TeamShare-MARK", "reader-MARK", "origin"),
            Part.OS_BADGE, List.of("ubuntu-MARK"));

    /** Secret values and the inline key path, plus a jump server's auto command and a disabled tunnel. */
    private static final List<String> NEVER_WRITTEN = List.of(
            "ENC-PW-SECRET", "PASSPHRASE-SECRET", "JUMP-ENC-SECRET", "TEMP-KEY-SECRET", "TEMPORARY:",
            "ssh-autocommand-MARK", "disabled-host");

    @Test
    void headerIsTheSameInEveryFormat() {
        JsonObject json = json(write(ALL, Format.JSON));
        assertThat(json.get("generator").getAsString()).isEqualTo("korTTY");
        assertThat(json.get("version").getAsString()).isEqualTo("2.23.0");
        assertThat(json.get("exportedAt").getAsString()).isEqualTo("2026-10-25T10:00:00Z");
        assertThat(json.get("count").getAsInt()).isEqualTo(6);

        TomlParseResult toml = parseToml(write(ALL, Format.TOML));
        assertThat(toml.getString("generator")).isEqualTo("korTTY");
        assertThat(toml.getString("version")).isEqualTo("2.23.0");
        assertThat(toml.getLong("count")).isEqualTo(6L);

        Element root = xml(write(ALL, Format.XML)).getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("korttyOverview");
        assertThat(root.getAttribute("generator")).isEqualTo("korTTY");
        assertThat(root.getAttribute("version")).isEqualTo("2.23.0");
        assertThat(root.getAttribute("exportedAt")).isEqualTo("2026-10-25T10:00:00Z");
        assertThat(root.getAttribute("count")).isEqualTo("6");

        assertThat(lines(write(ALL, Format.YAML)))
                .containsAtLeast("generator: korTTY", "version: \"2.23.0\"",
                        "exportedAt: \"2026-10-25T10:00:00Z\"", "count: 6");
    }

    @Test
    void everyFormatHoldsEveryConnectionInOrderAndSkipsPlaceholders() {
        List<String> fromJson = new ArrayList<>();
        for (JsonElement each : json(write(ALL, Format.JSON)).getAsJsonArray("connections")) {
            fromJson.add(each.getAsJsonObject().get("name").getAsString());
        }
        assertThat(fromJson).isEqualTo(NAMES);

        TomlArray array = parseToml(write(ALL, Format.TOML)).getArray("connections");
        List<String> fromToml = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            fromToml.add(array.getTable(i).getString("name"));
        }
        assertThat(fromToml).isEqualTo(NAMES);

        NodeList nodes = xml(write(ALL, Format.XML)).getDocumentElement().getElementsByTagName("connection");
        List<String> fromXml = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            fromXml.add(childText((Element) nodes.item(i), "name"));
        }
        assertThat(fromXml).isEqualTo(NAMES);

        List<String> fromYaml = new ArrayList<>();
        for (String line : lines(write(ALL, Format.YAML))) {
            if (line.startsWith("  - name: ")) {
                fromYaml.add(unquoteYaml(line.substring("  - name: ".length())));
            }
        }
        assertThat(fromYaml).isEqualTo(NAMES);

        // The placeholder that keeps an empty folder visible in the tree is not a server.
        for (Format format : Format.values()) {
            assertThat(write(ALL, format)).doesNotContain("(Ordner");
        }
    }

    @Test
    void jsonCarriesTheValuesAndLeavesOutWhatIsUnknownOrUnticked() {
        JsonArray connections = json(write(ALL, Format.JSON)).getAsJsonArray("connections");

        JsonObject prod = connections.get(0).getAsJsonObject();
        assertThat(prod.get("protocol").getAsString()).isEqualTo("sshTcp");
        assertThat(prod.get("folder").getAsString()).isEqualTo("Group-MARK/Databases");
        assertThat(prod.get("tabColor").getAsString()).isEqualTo("#D32F2F");
        assertThat(prod.get("favorite").getAsBoolean()).isTrue();
        assertThat(prod.getAsJsonObject("authentication").get("credential").getAsString()).isEqualTo("CredName-MARK");
        assertThat(prod.getAsJsonObject("authentication").get("sshKey").getAsString()).isEqualTo("SshKeyName-MARK");
        assertThat(prod.getAsJsonObject("jumpServer").get("port").getAsInt()).isEqualTo(2200);
        JsonArray tunnels = prod.getAsJsonArray("tunnels");
        assertThat(tunnels.size()).isEqualTo(1);
        assertThat(tunnels.get(0).getAsJsonObject().get("localPort").getAsInt()).isEqualTo(15432);
        assertThat(prod.getAsJsonObject("usage").get("lastUsed").getAsString()).isEqualTo("2026-09-21T14:13:20Z");
        assertThat(prod.getAsJsonObject("usage").get("usageCount").getAsInt()).isEqualTo(42);
        assertThat(prod.getAsJsonObject("security").get("isolationLevel").getAsString()).isEqualTo("sandbox");
        assertThat(prod.getAsJsonObject("security").get("hostKeyCheck").getAsString()).isEqualTo("default");
        assertThat(prod.get("osBadge").getAsString()).isEqualTo("ubuntu-MARK");

        JsonObject mosh = connections.get(1).getAsJsonObject();
        assertThat(mosh.get("protocol").getAsString()).isEqualTo("mosh");
        assertThat(mosh.getAsJsonObject("security").get("hostKeyCheck").getAsString()).isEqualTo("verify");
        assertThat(mosh.has("lastUsed")).isFalse();
        assertThat(mosh.has("favorite")).isFalse();
        assertThat(mosh.has("jumpServer")).isFalse();

        JsonObject shell = connections.get(2).getAsJsonObject();
        assertThat(shell.get("protocol").getAsString()).isEqualTo("localShell");
        assertThat(shell.get("localShellCommand").getAsString()).isEqualTo("/bin/zsh -l");
        assertThat(shell.get("localShellWorkingDirectory").getAsString()).isEqualTo("/Users/me/work");
        assertThat(shell.has("host")).isFalse();
        assertThat(shell.has("port")).isFalse();
        assertThat(shell.getAsJsonObject("authentication").get("method").getAsString()).isEqualTo("none");

        JsonObject team = connections.get(3).getAsJsonObject();
        assertThat(team.getAsJsonObject("source").get("origin").getAsString()).isEqualTo("teamwork");
        assertThat(team.getAsJsonObject("source").get("teamworkSource").getAsString()).isEqualTo("TeamShare-MARK");
        assertThat(team.getAsJsonObject("source").get("role").getAsString()).isEqualTo("reader-MARK");
        // A credential the caller cannot name is written as its id.
        assertThat(team.getAsJsonObject("authentication").get("credential").getAsString()).isEqualTo("cred-unnamed");
        assertThat(team.getAsJsonObject("authentication").has("sshKey")).isFalse();

        JsonObject temp = connections.get(4).getAsJsonObject();
        assertThat(temp.getAsJsonObject("authentication").get("method").getAsString()).isEqualTo("temporaryKey");
        assertThat(temp.getAsJsonObject("authentication").has("privateKeyPath")).isFalse();
        assertThat(temp.has("tabColor")).isFalse();
        assertThat(temp.getAsJsonObject("security").get("hostKeyCheck").getAsString()).isEqualTo("acceptNew");
    }

    @Test
    void tomlUsesATableForEachConnectionAndArraysForTunnels() {
        String toml = write(ALL, Format.TOML);
        TomlParseResult result = parseToml(toml);
        assertThat(countOf(toml, "[[connections]]")).isEqualTo(6);
        assertThat(toml).contains("\n[connections.jumpServer]\n");
        assertThat(toml).contains("\n[[connections.tunnels]]\n");

        TomlTable prod = result.getArray("connections").getTable(0);
        assertThat(prod.getString("tabColor")).isEqualTo("#D32F2F");
        assertThat(prod.getTable("jumpServer").getString("host")).isEqualTo("jump-MARK.example.org");
        assertThat(prod.getArray("tunnels").getTable(0).getLong("localPort")).isEqualTo(15432L);
        assertThat(prod.getTable("security").getString("isolationLevel")).isEqualTo("sandbox");
    }

    @Test
    void xmlNestsSectionsAndWritesListsAsRepeatedElements() {
        Document document = xml(write(ALL, Format.XML));
        Element prod = (Element) document.getElementsByTagName("connection").item(0);
        Element tunnels = child(prod, "tunnels");
        assertThat(tunnels.getElementsByTagName("tunnel").getLength()).isEqualTo(1);
        Element tunnel = child(tunnels, "tunnel");
        assertThat(childText(tunnel, "localPort")).isEqualTo("15432");
        assertThat(childText(tunnel, "description")).isEqualTo(SPECIAL_DESCRIPTION);
        assertThat(childText(child(prod, "jumpServer"), "host")).isEqualTo("jump-MARK.example.org");
        assertThat(childText(prod, "host")).isEqualTo("db.example.org");
    }

    @Test
    void yamlIsBlockStyleWithSequencesOfMappings() {
        String expected = """
                connections:
                  - name: Prod DB
                    protocol: sshTcp
                    host: db.example.org
                    port: 2222
                    username: ops
                    folder: Group-MARK/Databases
                    tag: tag-MARK
                    tabColor: "#D32F2F"
                    favorite: true
                    authentication:
                      method: password
                      credential: CredName-MARK
                      sshKey: SshKeyName-MARK
                      privateKeyPath: /keys/path-MARK_ed25519
                    jumpServer:
                      host: jump-MARK.example.org
                      port: 2200
                      username: jumper
                      method: password
                    tunnels:
                      - type: local
                        localHost: localhost
                        localPort: 15432
                        remoteHost: db-MARK.internal
                        remotePort: 5432
                """;
        assertThat(write(ALL, Format.YAML)).contains(expected);
    }

    @Test
    void yamlQuotesOnlyWhatYamlWouldMisread() {
        String yaml = write(ALL, Format.YAML);
        // Names and values that YAML would read as numbers, booleans or nulls are quoted.
        assertThat(yaml).contains("\n  - name: \"123\"\n");
        assertThat(yaml).contains("\n    host: \"yes\"\n");
        assertThat(yaml).contains("\n    username: \"null\"\n");
        assertThat(yaml).contains("\n    host: \"10.0.0.5\"\n");
        assertThat(yaml).contains("\n    username: \" spaced \"\n");
        assertThat(yaml).contains("version: \"2.23.0\"\n");
        assertThat(yaml).contains("exportedAt: \"2026-10-25T10:00:00Z\"\n");
        assertThat(yaml).contains("    tabColor: \"#D32F2F\"\n");
        // Plain text stays plain.
        assertThat(yaml).contains("\n  - name: Prod DB\n");
        assertThat(yaml).contains("\n    username: root\n");
        assertThat(yaml).contains("\n    localShellCommand: /bin/zsh -l\n");
    }

    @Test
    void specialCharactersRoundTripInEveryFormat() {
        JsonObject jsonTunnel = json(write(ALL, Format.JSON)).getAsJsonArray("connections").get(0).getAsJsonObject()
                .getAsJsonArray("tunnels").get(0).getAsJsonObject();
        assertThat(jsonTunnel.get("description").getAsString()).isEqualTo(SPECIAL_DESCRIPTION);

        TomlTable tomlTunnel = parseToml(write(ALL, Format.TOML)).getArray("connections").getTable(0)
                .getArray("tunnels").getTable(0);
        assertThat(tomlTunnel.getString("description")).isEqualTo(SPECIAL_DESCRIPTION);

        Element xmlTunnel = child(child((Element) xml(write(ALL, Format.XML)).getDocumentElement()
                .getElementsByTagName("connection").item(0), "tunnels"), "tunnel");
        assertThat(childText(xmlTunnel, "description")).isEqualTo(SPECIAL_DESCRIPTION);

        String descriptionLine = null;
        for (String line : lines(write(ALL, Format.YAML))) {
            if (line.startsWith("        description: ")) {
                descriptionLine = line.substring("        description: ".length());
            }
        }
        assertThat(descriptionLine).isNotNull();
        assertThat(unquoteYaml(descriptionLine)).isEqualTo(SPECIAL_DESCRIPTION);
    }

    @Test
    void aPartIsWrittenOnlyWhenTicked() {
        for (Format format : Format.values()) {
            String everything = write(ALL, format);
            for (Map.Entry<Part, List<String>> part : PART_MARKERS.entrySet()) {
                String without = write(EnumSet.complementOf(EnumSet.of(part.getKey())), format);
                for (String marker : part.getValue()) {
                    assertWithMessage("%s written by %s with the part ticked", marker, part.getKey())
                            .that(everything).contains(marker);
                    assertWithMessage("%s written by %s without the part, in %s", marker, part.getKey(), format)
                            .that(without).doesNotContain(marker);
                }
            }
        }
    }

    @Test
    void noSecretAppearsInAnyFormatWhateverIsTicked() {
        for (Format format : Format.values()) {
            for (Set<Part> parts : List.of(ALL, EnumSet.noneOf(Part.class))) {
                String text = write(parts, format);
                for (String secret : NEVER_WRITTEN) {
                    assertWithMessage("%s in %s", secret, format).that(text).doesNotContain(secret);
                }
            }
        }
    }

    @Test
    void anEmptyOverviewIsValidInEveryFormat() {
        assertThat(json(ConnectionOverviewExport.write(List.of(), ALL, Format.JSON, CONTEXT))
                .getAsJsonArray("connections").size()).isEqualTo(0);
        assertThat(parseToml(ConnectionOverviewExport.write(List.of(), ALL, Format.TOML, CONTEXT))
                .getArray("connections").size()).isEqualTo(0);
        assertThat(ConnectionOverviewExport.write(List.of(), ALL, Format.YAML, CONTEXT))
                .contains("count: 0\nconnections: []\n");
        Element root = xml(ConnectionOverviewExport.write(List.of(), ALL, Format.XML, CONTEXT)).getDocumentElement();
        assertThat(root.getAttribute("count")).isEqualTo("0");
        assertThat(root.getElementsByTagName("connection").getLength()).isEqualTo(0);
    }

    @Test
    void localShellWritesItsCommandAndDirectoryInsteadOfHostAndPort() {
        Map<String, Object> shell = ConnectionOverviewExport.entry(connections().get(2), ALL, CONTEXT);
        assertThat(shell).containsEntry("localShellCommand", "/bin/zsh -l");
        assertThat(shell).doesNotContainKey("host");
        assertThat(shell).doesNotContainKey("port");
    }

    @Test
    void dynamicTunnelHasNoRemoteEnd() {
        ServerConnection socks = new ServerConnection("socks", "host.example.org", 22, "user");
        socks.setSshTunnels(List.of(enabledTunnel(TunnelType.DYNAMIC, 1080)));
        Map<String, Object> entry = ConnectionOverviewExport.entry(socks, EnumSet.of(Part.TUNNELS), CONTEXT);
        @SuppressWarnings("unchecked")
        Map<String, Object> tunnel = (Map<String, Object>) ((List<?>) entry.get("tunnels")).get(0);
        assertThat(tunnel).containsEntry("localPort", 1080);
        assertThat(tunnel).doesNotContainKey("remoteHost");
        assertThat(tunnel).doesNotContainKey("remotePort");
    }

    // ----- fixture -------------------------------------------------------------------------------

    /** Six servers, in the order {@link #NAMES} lists them, plus a placeholder that must be skipped. */
    private static List<ServerConnection> connections() {
        ServerConnection prod = new ServerConnection();
        prod.setName("Prod DB");
        prod.setHost("db.example.org");
        prod.setPort(2222);
        prod.setUsername("ops");
        prod.setAuthMethod(AuthMethod.PASSWORD);
        prod.setCredentialId("cred-prod");
        prod.setSshKeyId("key-laptop");
        prod.setEncryptedPassword("ENC-PW-SECRET");
        prod.setPrivateKeyPassphrase("PASSPHRASE-SECRET");
        prod.setPrivateKeyPath("/keys/path-MARK_ed25519");
        prod.setGroup("Group-MARK/Databases");
        prod.setTag("tag-MARK");
        prod.setTabColor("#d32f2f");
        prod.setHighlightRuleSetId("highlight-MARK");
        prod.setTerminalEffectPluginId("effect-MARK");
        prod.setEncoding("ISO-8859-15");
        prod.setStrictTerminalMode(true);
        prod.setIsolationLevel(IsolationLevel.SANDBOX);
        prod.setIncognito(true);
        prod.setLastUsed(Instant.parse("2026-09-21T14:13:20Z").toEpochMilli());
        prod.setUsageCount(42);
        JumpServer jump = new JumpServer("jump-MARK.example.org", 2200, "jumper");
        jump.setEnabled(true);
        jump.setEncryptedPassword("JUMP-ENC-SECRET");
        jump.setAutoCommand("ssh-autocommand-MARK");
        prod.setJumpServer(jump);
        SSHTunnel tunnel = new SSHTunnel(TunnelType.LOCAL, 15432, "db-MARK.internal", 5432);
        tunnel.setEnabled(true);
        tunnel.setDescription(SPECIAL_DESCRIPTION);
        SSHTunnel disabled = new SSHTunnel(TunnelType.REMOTE, 9999, "disabled-host", 1);
        prod.setSshTunnels(List.of(tunnel, disabled));

        ServerConnection mosh = new ServerConnection("Mosh box", "10.0.0.5", 22, "root");
        mosh.setProtocol(ConnectionProtocol.MOSH);
        mosh.setAuthMethod(AuthMethod.KEYBOARD_INTERACTIVE);
        mosh.setDisableHostKeyCheck(false);

        ServerConnection shell = new ServerConnection();
        shell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        shell.setLocalShellCommand("/bin/zsh -l");
        shell.setLocalShellWorkingDirectory("/Users/me/work");

        ServerConnection team = new ServerConnection("Team Staging ümlaut", "staging.example.org", 22, "deploy");
        team.setConnectionSource(ConnectionSource.TEAMWORK);
        team.setTeamworkSourceId("source-1");
        team.setTeamworkRole("reader-MARK");
        team.setCredentialId("cred-unnamed");

        ServerConnection temp = new ServerConnection("123", "yes", 22, "null");
        temp.setTemporaryKeyContent("TEMP-KEY-SECRET");
        temp.setPrivateKeyPath("TEMPORARY:TEMP-KEY-SECRET");
        temp.setTabColor("red");
        temp.setDisableHostKeyCheck(true);

        ServerConnection special = new ServerConnection(SPECIAL_NAME, "example.org", 22, " spaced ");

        ServerConnection placeholder = new ServerConnection("(Ordner: Empty)", "placeholder", 22, "");

        return List.of(prod, mosh, shell, team, temp, special, placeholder);
    }

    private static SSHTunnel enabledTunnel(TunnelType type, int localPort) {
        SSHTunnel tunnel = new SSHTunnel(type, localPort, "unused.example.org", 1);
        tunnel.setEnabled(true);
        return tunnel;
    }

    // ----- helpers -------------------------------------------------------------------------------

    private static String write(Set<Part> parts, Format format) {
        return ConnectionOverviewExport.write(connections(), parts, format, CONTEXT);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static TomlParseResult parseToml(String text) {
        TomlParseResult result = Toml.parse(text);
        assertWithMessage("TOML parse errors").that(result.hasErrors()).isFalse();
        return result;
    }

    private static Document xml(String text) {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(text)));
        } catch (Exception e) {
            throw new AssertionError("XML does not parse: " + e.getMessage(), e);
        }
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\n"));
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static Element child(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tag.equals(element.getTagName())) {
                return element;
            }
        }
        throw new AssertionError("no <" + tag + "> under <" + parent.getTagName() + ">");
    }

    private static String childText(Element parent, String tag) {
        return child(parent, tag).getTextContent();
    }

    /** Decodes a YAML scalar as written by the exporter: plain text, or a double-quoted string with escapes. */
    private static String unquoteYaml(String raw) {
        if (!raw.startsWith("\"")) {
            return raw;
        }
        assertThat(raw.endsWith("\"")).isTrue();
        String body = raw.substring(1, raw.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = body.charAt(++i);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new AssertionError("unexpected YAML escape \\" + escape + " in " + raw);
            }
        }
        return out.toString();
    }
}
