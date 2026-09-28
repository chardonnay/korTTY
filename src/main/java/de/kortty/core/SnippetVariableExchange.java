package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.SnippetVariable;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exports and imports snippet variables as JSON, XML or YAML. The XML shape matches the stored
 * {@code snippet-variables.xml}, so that file can be imported directly.
 */
public final class SnippetVariableExchange {

    public enum Format {
        JSON("json"),
        XML("xml"),
        YAML("yaml");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }

        /** The format for a file name's extension, or {@code null} when it is not supported. */
        public static Format fromFileName(String fileName) {
            String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json")) return JSON;
            if (lower.endsWith(".xml")) return XML;
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return YAML;
            return null;
        }
    }

    private SnippetVariableExchange() {
    }

    public static void export(Path file, List<SnippetVariable> variables, Format format) throws Exception {
        Files.writeString(file, write(variables, format), StandardCharsets.UTF_8);
    }

    public static List<SnippetVariable> importFile(Path file) throws Exception {
        Format format = Format.fromFileName(file.getFileName().toString());
        if (format == null) {
            throw new IOException("Unsupported variable file type: " + file.getFileName());
        }
        return read(Files.readString(file, StandardCharsets.UTF_8), format);
    }

    public static String write(List<SnippetVariable> variables, Format format) throws Exception {
        return switch (format) {
            case JSON -> writeJson(variables);
            case XML -> writeXml(variables);
            case YAML -> writeYaml(variables);
        };
    }

    /** Parses variables; entries without a name are skipped. */
    public static List<SnippetVariable> read(String content, Format format) throws Exception {
        List<SnippetVariable> parsed = switch (format) {
            case JSON -> readJson(content);
            case XML -> readXml(content);
            case YAML -> readYaml(content);
        };
        List<SnippetVariable> valid = new ArrayList<>();
        for (SnippetVariable variable : parsed) {
            if (variable != null && variable.getName() != null && !variable.getName().isBlank()) {
                valid.add(new SnippetVariable(variable.getName().trim(),
                        variable.getValue() != null ? variable.getValue() : ""));
            }
        }
        return valid;
    }

    // ---- JSON ----

    private static String writeJson(List<SnippetVariable> variables) {
        JsonArray array = new JsonArray();
        for (SnippetVariable variable : variables) {
            JsonObject object = new JsonObject();
            object.addProperty("name", variable.getName());
            object.addProperty("value", variable.getValue() != null ? variable.getValue() : "");
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.add("variables", array);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root) + "\n";
    }

    private static List<SnippetVariable> readJson(String content) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(content);
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON: " + e.getMessage(), e);
        }
        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("variables")
                && root.getAsJsonObject().get("variables").isJsonArray()) {
            array = root.getAsJsonObject().getAsJsonArray("variables");
        } else {
            throw new IOException("Invalid variable JSON: 'variables' array not found");
        }
        List<SnippetVariable> variables = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            variables.add(new SnippetVariable(stringOrNull(object, "name"), stringOrNull(object, "value")));
        }
        return variables;
    }

    private static String stringOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    // ---- XML ----

    private static String writeXml(List<SnippetVariable> variables) throws Exception {
        SnippetVariableManager.VariablesWrapper wrapper = new SnippetVariableManager.VariablesWrapper();
        wrapper.setVariables(new ArrayList<>(variables));
        Marshaller marshaller = xmlContext().createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        marshaller.marshal(wrapper, writer);
        return writer.toString();
    }

    private static List<SnippetVariable> readXml(String content) throws Exception {
        Object root = xmlContext().createUnmarshaller().unmarshal(new StringReader(content));
        if (!(root instanceof SnippetVariableManager.VariablesWrapper wrapper)) {
            throw new IOException("Invalid variable XML: <snippetVariables> root expected");
        }
        return wrapper.getVariables() != null ? wrapper.getVariables() : List.of();
    }

    private static JAXBContext xmlContext() throws Exception {
        return JAXBContext.newInstance(SnippetVariableManager.VariablesWrapper.class, SnippetVariable.class);
    }

    // ---- YAML (the small subset this exporter writes, plus plain and single-quoted scalars) ----

    private static String writeYaml(List<SnippetVariable> variables) {
        StringBuilder yaml = new StringBuilder("variables:\n");
        if (variables.isEmpty()) {
            return "variables: []\n";
        }
        for (SnippetVariable variable : variables) {
            yaml.append("  - name: ").append(quoteYaml(variable.getName())).append('\n');
            yaml.append("    value: ").append(quoteYaml(variable.getValue() != null ? variable.getValue() : ""))
                    .append('\n');
        }
        return yaml.toString();
    }

    /** Double-quoted YAML scalar; the escapes used are valid in both YAML and JSON strings. */
    private static String quoteYaml(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static List<SnippetVariable> readYaml(String content) throws IOException {
        List<SnippetVariable> variables = new ArrayList<>();
        SnippetVariable current = null;
        boolean inVariables = false;
        for (String rawLine : content.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.equals("---")) {
                continue;
            }
            if (!Character.isWhitespace(rawLine.charAt(0)) && !line.startsWith("-")) {
                inVariables = line.startsWith("variables:");
                continue;
            }
            if (!inVariables) {
                continue;
            }
            if (line.startsWith("- ") || line.equals("-")) {
                current = new SnippetVariable();
                variables.add(current);
                line = line.substring(1).strip();
                if (line.isEmpty()) {
                    continue;
                }
            }
            if (current == null) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = unquoteYaml(line.substring(colon + 1).strip());
            if ("name".equals(key)) {
                current.setName(value);
            } else if ("value".equals(key)) {
                current.setValue(value);
            }
        }
        if (!inVariables && variables.isEmpty() && !content.contains("variables:")) {
            throw new IOException("Invalid variable YAML: 'variables' list not found");
        }
        return variables;
    }

    private static String unquoteYaml(String scalar) throws IOException {
        if (scalar.length() >= 2 && scalar.startsWith("\"") && scalar.endsWith("\"")) {
            try {
                return JsonParser.parseString(scalar).getAsString();
            } catch (RuntimeException e) {
                throw new IOException("Invalid quoted YAML value: " + scalar, e);
            }
        }
        if (scalar.length() >= 2 && scalar.startsWith("'") && scalar.endsWith("'")) {
            return scalar.substring(1, scalar.length() - 1).replace("''", "'");
        }
        int comment = scalar.indexOf(" #");
        return comment >= 0 ? scalar.substring(0, comment).strip() : scalar;
    }
}
