package de.kortty.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.kortty.isolation.IsolationLevel;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Writes a readable inventory of the Connection Manager's servers as JSON, YAML, TOML or XML, for
 * documentation. The user picks the format and which {@link Part parts} of each entry to include.
 *
 * <p>This is not the korTTY export. The export is meant to be imported again and so carries
 * everything a connection needs to work; this overview only describes the servers, and nothing in it
 * is meant to be read back. Secrets are never written: the encrypted password, the key passphrase, a
 * temporary key's content, a jump server's password and auto command, and the {@code TEMPORARY:} value
 * some dialogs store in {@code privateKeyPath} (it carries the key content itself) are not read at all.
 * Credentials and SSH keys appear by the name the caller resolves through {@link Context}, so the file
 * shows what they are called, never what they contain. The local shell's command is written as the user
 * typed it, so review it before sharing a file.
 *
 * <p>Usage statistics, AI assignments, terminal logs, session journals and window geometry are never
 * part of the overview. Placeholder entries that only keep an empty folder visible are skipped.
 */
public final class ConnectionOverviewExport {

    /** The output format. Every writer renders the same tree, see {@link #entry}. */
    public enum Format {
        JSON("json"), YAML("yaml"), TOML("toml"), XML("xml");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        /** The file name extension, without the dot. */
        public String extension() {
            return extension;
        }
    }

    /** Parts of an entry the user can include; {@link #BASICS} is always written. */
    public enum Part {
        BASICS, FOLDER, AUTHENTICATION, JUMP_SERVER, TUNNELS, TERMINAL, SECURITY, USAGE, SOURCE, OS_BADGE
    }

    /**
     * What the exporter cannot read from the connection itself. Any function may return null, and a
     * null function behaves like one that returns null, so the value is left out. The exception is a
     * credential, SSH key or teamwork source the caller cannot name: it is written as its id, which is
     * a reference rather than a secret.
     */
    public record Context(String korttyVersion, Instant exportedAt,
                          Function<String, String> credentialName,
                          Function<String, String> sshKeyName,
                          Predicate<ServerConnection> favorite,
                          Function<ServerConnection, String> osBadge,
                          Function<ServerConnection, String> teamworkSource) { }

    private static final String GENERATOR = "korTTY";
    private static final String NOTE = "korTTY connection overview: an inventory, not an import file.";
    private static final Context NO_CONTEXT = new Context(null, null, null, null, null, null, null);
    /** Characters that cannot start a plain YAML scalar. A leading digit is quoted too, see yamlNeedsQuotes. */
    private static final String YAML_LEADING_INDICATORS = "-?:,[]{}#&*!|>'\"%@`+.";
    /** Plain words YAML 1.1 readers turn into booleans, nulls or merge keys instead of text. */
    private static final Set<String> YAML_RESERVED =
            Set.of("~", "null", "true", "false", "yes", "no", "on", "off", "y", "n", "=", "<<");
    private static final String TEMPORARY_KEY_PATH_PREFIX = "TEMPORARY:";

    private ConnectionOverviewExport() {
    }

    /**
     * The overview of {@code connections} in {@code format}, with the given {@code parts} of each entry.
     * Placeholder entries are skipped, the {@code count} header counts what is written, and the text
     * ends with a line break.
     */
    public static String write(List<ServerConnection> connections, Set<Part> parts, Format format, Context context) {
        Objects.requireNonNull(format, "format");
        Map<String, Object> document = document(connections, parts, context);
        return switch (format) {
            case JSON -> toJson(document);
            case YAML -> toYaml(document);
            case TOML -> toToml(document);
            case XML -> toXml(document);
        };
    }

    /**
     * The tree written for one connection: ordered maps, lists, strings, integers, booleans and
     * {@link Instant}s. Keys are stable lower-case camel case names; a key whose value is unknown or
     * blank is left out, and so is a whole section none of whose values are known.
     */
    static Map<String, Object> entry(ServerConnection connection, Set<Part> parts, Context context) {
        Context ctx = context != null ? context : NO_CONTEXT;
        Set<Part> wanted = parts != null ? parts : EnumSet.noneOf(Part.class);
        Map<String, Object> out = new LinkedHashMap<>();

        // BASICS: always written. A local shell has no host or port, only its command and directory.
        out.put("name", connection.getDisplayName());
        out.put("protocol", protocolId(connection.getProtocol()));
        if (!connection.isLocalShell()) {
            putText(out, "host", connection.getHost());
            out.put("port", connection.getPort());
        }
        putText(out, "username", connection.getUsername());
        if (connection.isLocalShell()) {
            putText(out, "localShellCommand", connection.getLocalShellCommand());
            putText(out, "localShellWorkingDirectory", connection.getLocalShellWorkingDirectory());
        }

        if (wanted.contains(Part.FOLDER)) {
            putText(out, "folder", connection.getGroup());
            putText(out, "tag", connection.getTag());
            putText(out, "tabColor", normalizedTabColor(connection.getTabColor()));
            if (ctx.favorite() != null && ctx.favorite().test(connection)) {
                out.put("favorite", true);
            }
        }

        if (wanted.contains(Part.AUTHENTICATION)) {
            Map<String, Object> auth = new LinkedHashMap<>();
            putText(auth, "method", authMethodId(connection));
            putText(auth, "credential", referenceName(ctx.credentialName(), connection.getCredentialId()));
            putText(auth, "sshKey", referenceName(ctx.sshKeyName(), connection.getSshKeyId()));
            putText(auth, "privateKeyPath", keyPathOf(connection));
            putSection(out, "authentication", auth);
        }

        JumpServer jump = connection.getJumpServer();
        if (wanted.contains(Part.JUMP_SERVER) && jump != null && jump.isEnabled()) {
            // A jump server's password and its auto command (free text that may carry one) are never written.
            Map<String, Object> jumpSection = new LinkedHashMap<>();
            putText(jumpSection, "host", jump.getHost());
            jumpSection.put("port", jump.getPort());
            putText(jumpSection, "username", jump.getUsername());
            putText(jumpSection, "method", authMethodId(jump.getAuthMethod()));
            putSection(out, "jumpServer", jumpSection);
        }

        if (wanted.contains(Part.TUNNELS)) {
            List<Map<String, Object>> tunnels = new ArrayList<>();
            List<SSHTunnel> configured = connection.getSshTunnels();
            for (SSHTunnel tunnel : configured != null ? configured : List.<SSHTunnel>of()) {
                if (tunnel != null && tunnel.isEnabled()) {
                    tunnels.add(tunnelSection(tunnel));
                }
            }
            if (!tunnels.isEmpty()) {
                out.put("tunnels", tunnels);
            }
        }

        if (wanted.contains(Part.TERMINAL)) {
            Map<String, Object> terminal = new LinkedHashMap<>();
            putText(terminal, "emulation", connection.getTerminalEmulationType());
            putText(terminal, "encoding", connection.getEncoding());
            putText(terminal, "highlightRuleSetId", connection.getHighlightRuleSetId());
            putText(terminal, "terminalEffectPluginId", connection.getTerminalEffectPluginId());
            terminal.put("connectionTimeoutSeconds", connection.getConnectionTimeoutSeconds());
            terminal.put("retryCount", connection.getRetryCount());
            putSection(out, "terminal", terminal);
        }

        if (wanted.contains(Part.SECURITY)) {
            Map<String, Object> security = new LinkedHashMap<>();
            IsolationLevel isolation = connection.getIsolationLevel();
            putText(security, "isolationLevel", isolation != null ? isolation.id() : null);
            security.put("incognito", connection.isIncognito());
            security.put("hostKeyCheck", hostKeyCheckId(connection.getDisableHostKeyCheck()));
            if (connection.getStrictTerminalMode() != null) {
                security.put("strictTerminalMode", connection.getStrictTerminalMode());
            }
            putSection(out, "security", security);
        }

        if (wanted.contains(Part.USAGE)) {
            Map<String, Object> usage = new LinkedHashMap<>();
            Instant lastUsed = connection.getLastUsed() > 0 ? instantOf(connection.getLastUsed()) : null;
            if (lastUsed != null) {
                usage.put("lastUsed", lastUsed);
            }
            usage.put("usageCount", connection.getUsageCount());
            putSection(out, "usage", usage);
        }

        if (wanted.contains(Part.SOURCE)) {
            Map<String, Object> source = new LinkedHashMap<>();
            boolean teamwork = connection.isTeamworkConnection();
            source.put("origin", teamwork ? "teamwork" : "local");
            if (teamwork) {
                String sourceName = resolve(ctx.teamworkSource(), connection);
                putText(source, "teamworkSource", hasText(sourceName) ? sourceName : connection.getTeamworkSourceId());
                putText(source, "role", connection.getTeamworkRole());
            }
            putSection(out, "source", source);
        }

        if (wanted.contains(Part.OS_BADGE)) {
            putText(out, "osBadge", resolve(ctx.osBadge(), connection));
        }
        return out;
    }

    private static Map<String, Object> document(List<ServerConnection> connections, Set<Part> parts, Context context) {
        Context ctx = context != null ? context : NO_CONTEXT;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ServerConnection connection : connections != null ? connections : List.<ServerConnection>of()) {
            if (connection != null && !connection.isPlaceholder()) {
                entries.add(entry(connection, parts, ctx));
            }
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("generator", GENERATOR);
        putText(document, "version", ctx.korttyVersion());
        if (ctx.exportedAt() != null) {
            document.put("exportedAt", ctx.exportedAt());
        }
        document.put("count", entries.size());
        document.put("connections", entries);
        return document;
    }

    private static Map<String, Object> tunnelSection(SSHTunnel tunnel) {
        Map<String, Object> section = new LinkedHashMap<>();
        TunnelType type = tunnel.getType();
        putText(section, "type", type != null ? type.name().toLowerCase(Locale.ROOT) : null);
        putText(section, "localHost", tunnel.getLocalHost());
        section.put("localPort", tunnel.getLocalPort());
        // A dynamic (SOCKS) tunnel has no remote end, so its remote fields would only be defaults.
        if (type != TunnelType.DYNAMIC) {
            putText(section, "remoteHost", tunnel.getRemoteHost());
            section.put("remotePort", tunnel.getRemotePort());
        }
        putText(section, "description", tunnel.getDescription());
        return section;
    }

    // ----- value mapping -------------------------------------------------------------------------

    private static String protocolId(ConnectionProtocol protocol) {
        return switch (protocol) {
            case SSH_TCP -> "sshTcp";
            case MOSH -> "mosh";
            case MOSH_CLIENT -> "moshClient";
            case LOCAL_SHELL -> "localShell";
        };
    }

    /** The auth method of a connection; a local shell authenticates nothing and a temporary key wins. */
    private static String authMethodId(ServerConnection connection) {
        if (connection.isLocalShell()) {
            return "none";
        }
        if (hasText(connection.getTemporaryKeyContent()) || isTemporaryKeyPath(connection.getPrivateKeyPath())) {
            return "temporaryKey";
        }
        return authMethodId(connection.getAuthMethod());
    }

    private static String authMethodId(AuthMethod method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case PASSWORD -> "password";
            case PUBLIC_KEY -> "publicKey";
            case KEYBOARD_INTERACTIVE -> "keyboardInteractive";
        };
    }

    /** {@code null} inherits the group or global setting; {@code true} accepts new keys; {@code false} verifies. */
    private static String hostKeyCheckId(Boolean disableHostKeyCheck) {
        if (disableHostKeyCheck == null) {
            return "default";
        }
        return disableHostKeyCheck ? "acceptNew" : "verify";
    }

    /** The private key file path, or null when the field holds inline key material or nothing. */
    private static String keyPathOf(ServerConnection connection) {
        String path = connection.getPrivateKeyPath();
        return !hasText(path) || isTemporaryKeyPath(path) ? null : path;
    }

    private static boolean isTemporaryKeyPath(String path) {
        return path != null && path.startsWith(TEMPORARY_KEY_PATH_PREFIX);
    }

    /** A tab color as {@code #RRGGBB}, or null when the stored value is not a hex color. */
    private static String normalizedTabColor(String tabColor) {
        return ConnectionColorSupport.normalizeHex(tabColor);
    }

    /**
     * The name the caller resolves for a credential or SSH key id. A reference no name is known for is
     * written as its id, so the file still shows that something is assigned.
     */
    private static String referenceName(Function<String, String> names, String id) {
        if (!hasText(id)) {
            return null;
        }
        String name = names != null ? names.apply(id) : null;
        return hasText(name) ? name : id;
    }

    private static <T> String resolve(Function<T, String> function, T argument) {
        return function != null ? function.apply(argument) : null;
    }

    private static Instant instantOf(long epochMillis) {
        try {
            return Instant.ofEpochMilli(epochMillis);
        } catch (DateTimeException tooLarge) {
            // A damaged connections file can hold a timestamp out of range; the value is then left out.
            return null;
        }
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private static void putSection(Map<String, Object> target, String key, Map<String, Object> section) {
        if (!section.isEmpty()) {
            target.put(key, section);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ----- JSON ----------------------------------------------------------------------------------

    private static String toJson(Map<String, Object> document) {
        JsonElement root = jsonOf(document);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static JsonElement jsonOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add((String) entry.getKey(), jsonOf(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object item : list) {
                array.add(jsonOf(item));
            }
            return array;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        return new JsonPrimitive(scalarText(value));
    }

    // ----- YAML ----------------------------------------------------------------------------------

    private static String toYaml(Map<String, Object> document) {
        StringBuilder out = new StringBuilder("# ").append(NOTE).append('\n');
        yamlMapping(out, document, "", null);
        return out.toString();
    }

    /**
     * Writes the entries of {@code map} at {@code indent}. When {@code firstLine} is given, the first key
     * is written there instead, which lets a mapping inside a sequence start on the {@code "- "} line.
     */
    private static void yamlMapping(StringBuilder out, Map<?, ?> map, String indent, String firstLine) {
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String lead = first && firstLine != null ? firstLine : indent;
            first = false;
            String key = (String) entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                out.append(lead).append(key).append(":\n");
                yamlMapping(out, nested, indent + "  ", null);
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                out.append(lead).append(key).append(":\n");
                yamlSequence(out, list, indent + "  ");
            } else {
                out.append(lead).append(key).append(": ").append(yamlScalar(value)).append('\n');
            }
        }
    }

    private static void yamlSequence(StringBuilder out, List<?> items, String indent) {
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                yamlMapping(out, map, indent + "  ", indent + "- ");
            } else if (item instanceof List<?> nested && !nested.isEmpty()) {
                out.append(indent).append("-\n");
                yamlSequence(out, nested, indent + "  ");
            } else {
                out.append(indent).append("- ").append(yamlScalar(item)).append('\n');
            }
        }
    }

    private static String yamlScalar(Object value) {
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return "{}";
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return "[]";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return yamlString(scalarText(value));
    }

    /**
     * A YAML scalar: plain when nothing in it can be misread, otherwise a double-quoted string with
     * escapes. Strings that start with a digit are quoted: YAML 1.1 readers take a timestamp for a date
     * and a version such as {@code 2.23} for a float.
     */
    private static String yamlString(String text) {
        if (!yamlNeedsQuotes(text)) {
            return text;
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (yamlUnsafe(cp)) {
                        quoted.append(String.format(Locale.ROOT, "\\u%04X", cp));
                    } else {
                        quoted.appendCodePoint(cp);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static boolean yamlNeedsQuotes(String text) {
        if (text.isEmpty() || !text.equals(text.strip())) {
            return true;
        }
        char first = text.charAt(0);
        if (YAML_LEADING_INDICATORS.indexOf(first) >= 0 || Character.isDigit(first)) {
            return true;
        }
        if (YAML_RESERVED.contains(text.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ':' || cp == '#' || yamlUnsafe(cp)) {
                return true;
            }
        }
        return false;
    }

    /** Control characters and the Unicode line and paragraph separators, which YAML would read as breaks. */
    private static boolean yamlUnsafe(int cp) {
        return cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F)
                || cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF || (cp >= 0xD800 && cp <= 0xDFFF);
    }

    // ----- TOML ----------------------------------------------------------------------------------

    private static String toToml(Map<String, Object> document) {
        StringBuilder out = new StringBuilder("# ").append(NOTE).append('\n');
        // Top-level keys must come before the first table header.
        for (Map.Entry<?, ?> entry : document.entrySet()) {
            if (!"connections".equals(entry.getKey())) {
                out.append(entry.getKey()).append(" = ").append(tomlValue(entry.getValue())).append('\n');
            }
        }
        List<?> connections = (List<?>) document.get("connections");
        if (connections.isEmpty()) {
            out.append("connections = []\n");
        }
        for (Object connection : connections) {
            out.append("\n[[connections]]\n");
            tomlTable(out, "connections", (Map<?, ?>) connection);
        }
        return out.toString();
    }

    /**
     * Writes one table: its scalar keys first, then its sub-tables and arrays of tables. TOML attaches a
     * header's keys to that header, so every key of a table has to come before its first sub-table.
     */
    private static void tomlTable(StringBuilder out, String path, Map<?, ?> table) {
        for (Map.Entry<?, ?> entry : table.entrySet()) {
            if (!isTomlTable(entry.getValue()) && !isTomlTableArray(entry.getValue())) {
                out.append(entry.getKey()).append(" = ").append(tomlValue(entry.getValue())).append('\n');
            }
        }
        for (Map.Entry<?, ?> entry : table.entrySet()) {
            String child = path + "." + entry.getKey();
            if (isTomlTable(entry.getValue())) {
                out.append("\n[").append(child).append("]\n");
                tomlTable(out, child, (Map<?, ?>) entry.getValue());
            } else if (isTomlTableArray(entry.getValue())) {
                for (Object item : (List<?>) entry.getValue()) {
                    out.append("\n[[").append(child).append("]]\n");
                    tomlTable(out, child, (Map<?, ?>) item);
                }
            }
        }
    }

    private static boolean isTomlTable(Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty();
    }

    private static boolean isTomlTableArray(Object value) {
        return value instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(item -> item instanceof Map<?, ?>);
    }

    private static String tomlValue(Object value) {
        if (value instanceof String text) {
            return tomlString(text);
        }
        if (value instanceof Instant instant) {
            // An RFC 3339 offset date-time, which TOML reads as a datetime rather than a string.
            return instant.toString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ConnectionOverviewExport::tomlValue).collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            return map.entrySet().stream()
                    .map(entry -> entry.getKey() + " = " + tomlValue(entry.getValue()))
                    .collect(Collectors.joining(", ", "{ ", " }"));
        }
        return String.valueOf(value);
    }

    private static String tomlString(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\t' -> quoted.append("\\t");
                case '\n' -> quoted.append("\\n");
                case '\f' -> quoted.append("\\f");
                case '\r' -> quoted.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        quoted.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    // ----- XML -----------------------------------------------------------------------------------

    private static String toXml(Map<String, Object> document) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<!-- ").append(NOTE).append(" -->\n");
        out.append("<korttyOverview");
        // The header values are attributes; the connections are child elements.
        for (Map.Entry<?, ?> entry : document.entrySet()) {
            if (!"connections".equals(entry.getKey())) {
                out.append(' ').append(entry.getKey()).append("=\"")
                        .append(xmlEscape(scalarText(entry.getValue()), true)).append('"');
            }
        }
        List<?> connections = (List<?>) document.get("connections");
        if (connections.isEmpty()) {
            return out.append("/>\n").toString();
        }
        out.append(">\n");
        for (Object connection : connections) {
            xmlElement(out, "connection", connection, 1);
        }
        return out.append("</korttyOverview>\n").toString();
    }

    private static void xmlElement(StringBuilder out, String name, Object value, int depth) {
        String pad = "  ".repeat(depth);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append(pad).append('<').append(name).append("/>\n");
                return;
            }
            out.append(pad).append('<').append(name).append(">\n");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                xmlElement(out, (String) entry.getKey(), entry.getValue(), depth + 1);
            }
            out.append(pad).append("</").append(name).append(">\n");
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append(pad).append('<').append(name).append("/>\n");
                return;
            }
            out.append(pad).append('<').append(name).append(">\n");
            for (Object item : list) {
                xmlElement(out, listItemName(name), item, depth + 1);
            }
            out.append(pad).append("</").append(name).append(">\n");
        } else {
            out.append(pad).append('<').append(name).append('>')
                    .append(xmlEscape(scalarText(value), false))
                    .append("</").append(name).append(">\n");
        }
    }

    /** The element name of one item in a list: {@code tunnels} holds {@code tunnel} elements. */
    private static String listItemName(String listName) {
        return listName.endsWith("s") && listName.length() > 1
                ? listName.substring(0, listName.length() - 1) : listName + "Item";
    }

    /**
     * Escapes text for an XML element or attribute. Carriage returns and tabs are written as character
     * references so a parser does not normalize them away, and characters XML 1.0 forbids are replaced
     * by U+FFFD: a stray control character in a name cannot be represented in the file at all.
     */
    private static String xmlEscape(String text, boolean attribute) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                case '\r' -> out.append("&#13;");
                case '\n' -> out.append(attribute ? "&#10;" : "\n");
                case '\t' -> out.append(attribute ? "&#9;" : "\t");
                default -> out.appendCodePoint(isXmlChar(cp) ? cp : 0xFFFD);
            }
        }
        return out.toString();
    }

    private static boolean isXmlChar(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static String scalarText(Object value) {
        return value instanceof Instant instant ? instant.toString() : String.valueOf(value);
    }
}
