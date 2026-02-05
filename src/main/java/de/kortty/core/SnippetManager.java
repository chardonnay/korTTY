package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import javafx.scene.input.Clipboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages code snippets: CRUD, search, placeholder resolution, and JSON import/export.
 */
public class SnippetManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SnippetManager.class);
    private static final String SNIPPETS_FILE = "snippets.xml";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    
    private final Path configDir;
    private final List<Snippet> snippets = new ArrayList<>();
    private final List<SnippetCategory> categories = new ArrayList<>();
    
    public SnippetManager(Path configDir) {
        this.configDir = configDir;
    }
    
    // ---- Load / Save (XML) ----
    
    public void load() throws Exception {
        Path file = configDir.resolve(SNIPPETS_FILE);
        if (!Files.exists(file)) {
            logger.info("No snippets file found, starting with empty list");
            return;
        }
        
        try {
            JAXBContext context = JAXBContext.newInstance(
                SnippetsWrapper.class, Snippet.class, SnippetCategory.class
            );
            Unmarshaller unmarshaller = context.createUnmarshaller();
            SnippetsWrapper wrapper = (SnippetsWrapper) unmarshaller.unmarshal(file.toFile());
            
            snippets.clear();
            if (wrapper.getSnippets() != null) {
                snippets.addAll(wrapper.getSnippets());
            }
            
            categories.clear();
            if (wrapper.getCategories() != null) {
                categories.addAll(wrapper.getCategories());
            }
            
            logger.info("Loaded {} snippets and {} categories from {}", snippets.size(), categories.size(), file);
        } catch (Exception e) {
            logger.error("Failed to load snippets from " + file, e);
            throw e;
        }
    }
    
    public void save() throws Exception {
        Path file = configDir.resolve(SNIPPETS_FILE);
        
        try {
            SnippetsWrapper wrapper = new SnippetsWrapper();
            wrapper.setSnippets(new ArrayList<>(snippets));
            wrapper.setCategories(new ArrayList<>(categories));
            
            JAXBContext context = JAXBContext.newInstance(
                SnippetsWrapper.class, Snippet.class, SnippetCategory.class
            );
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            
            Files.createDirectories(configDir);
            marshaller.marshal(wrapper, file.toFile());
            
            logger.info("Saved {} snippets and {} categories to {}", snippets.size(), categories.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save snippets to " + file, e);
            throw e;
        }
    }
    
    // ---- Snippet CRUD ----
    
    public void addSnippet(Snippet snippet) {
        snippets.add(snippet);
        logger.info("Added snippet: {}", snippet.getName());
    }
    
    public void removeSnippet(Snippet snippet) {
        snippets.remove(snippet);
        logger.info("Removed snippet: {}", snippet.getName());
    }
    
    public void updateSnippet(Snippet snippet) {
        int index = snippets.indexOf(snippet);
        if (index >= 0) {
            snippets.set(index, snippet);
            logger.info("Updated snippet: {}", snippet.getName());
        }
    }
    
    public List<Snippet> getAllSnippets() {
        return new ArrayList<>(snippets);
    }
    
    public Optional<Snippet> findById(String id) {
        return snippets.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst();
    }
    
    public void incrementUsage(Snippet snippet) {
        snippet.touch();
        logger.debug("Snippet '{}' usage count: {}", snippet.getName(), snippet.getUsageCount());
    }
    
    // ---- Category CRUD ----
    
    public void addCategory(SnippetCategory category) {
        categories.add(category);
        logger.info("Added snippet category: {}", category.getName());
    }
    
    public void removeCategory(SnippetCategory category) {
        categories.remove(category);
        logger.info("Removed snippet category: {}", category.getName());
    }
    
    public void updateCategory(SnippetCategory category) {
        int index = categories.indexOf(category);
        if (index >= 0) {
            categories.set(index, category);
        }
    }
    
    public List<SnippetCategory> getAllCategories() {
        return new ArrayList<>(categories);
    }
    
    public Optional<SnippetCategory> findCategoryByName(String name) {
        return categories.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst();
    }
    
    // ---- Search ----
    
    /**
     * Searches snippets by name, tags, content, and category (case-insensitive).
     */
    public List<Snippet> search(String query) {
        if (query == null || query.isBlank()) {
            return getAllSnippets();
        }
        String lowerQuery = query.toLowerCase();
        return snippets.stream()
                .filter(s -> matchesQuery(s, lowerQuery))
                .collect(Collectors.toList());
    }
    
    private boolean matchesQuery(Snippet snippet, String lowerQuery) {
        // Search in name
        if (snippet.getName() != null && snippet.getName().toLowerCase().contains(lowerQuery)) {
            return true;
        }
        // Search in tags
        if (snippet.getTags() != null) {
            for (String tag : snippet.getTags()) {
                if (tag.toLowerCase().contains(lowerQuery)) {
                    return true;
                }
            }
        }
        // Search in content
        if (snippet.getContent() != null && snippet.getContent().toLowerCase().contains(lowerQuery)) {
            return true;
        }
        // Search in category
        if (snippet.getCategory() != null && snippet.getCategory().toLowerCase().contains(lowerQuery)) {
            return true;
        }
        return false;
    }
    
    // ---- Placeholder Resolution ----
    
    /**
     * Result of variable resolution: the resolved text and the cursor offset (if ${cursor} was present).
     */
    public record ResolvedSnippet(String text, int cursorOffset) {}
    
    /**
     * Resolves built-in variables in snippet content.
     * Built-in: ${date}, ${time}, ${datetime}, ${hostname}, ${username}, ${clipboard}
     * ${cursor} is removed and its position returned as cursorOffset.
     * Any remaining ${...} variables are returned as-is for interactive prompting.
     *
     * @param content the snippet content with placeholders
     * @return resolved content (custom variables still present)
     */
    public ResolvedSnippet resolveBuiltInVariables(String content) {
        if (content == null) return new ResolvedSnippet("", -1);
        
        Map<String, String> builtins = new HashMap<>();
        builtins.put("date", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        builtins.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        builtins.put("datetime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        builtins.put("username", System.getProperty("user.name", "unknown"));
        
        try {
            builtins.put("hostname", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            builtins.put("hostname", "localhost");
        }
        
        // Clipboard content (must be called on FX thread or cached before)
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasString()) {
                builtins.put("clipboard", clipboard.getString());
            } else {
                builtins.put("clipboard", "");
            }
        } catch (Exception e) {
            builtins.put("clipboard", "");
        }
        
        // First pass: replace built-in variables
        String resolved = content;
        for (Map.Entry<String, String> entry : builtins.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        
        // Handle ${cursor} - find position and remove
        int cursorOffset = -1;
        int cursorIndex = resolved.indexOf("${cursor}");
        if (cursorIndex >= 0) {
            cursorOffset = cursorIndex;
            resolved = resolved.replace("${cursor}", "");
        }
        
        return new ResolvedSnippet(resolved, cursorOffset);
    }
    
    /**
     * Finds all custom (non-built-in) variable names in the content.
     */
    public List<String> findCustomVariables(String content) {
        Set<String> builtins = Set.of("date", "time", "datetime", "hostname", "username", "clipboard", "cursor");
        List<String> customVars = new ArrayList<>();
        
        if (content == null) return customVars;
        
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!builtins.contains(varName) && !customVars.contains(varName)) {
                customVars.add(varName);
            }
        }
        return customVars;
    }
    
    /**
     * Replaces custom variables with provided values.
     */
    public String replaceCustomVariables(String content, Map<String, String> values) {
        String result = content;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
    
    // ---- JSON Import / Export ----
    
    /**
     * Exports snippets to a JSON file.
     */
    public void exportToJson(Path file, List<Snippet> snippetsToExport) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"exportDate\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        json.append("  \"snippets\": [\n");
        
        for (int i = 0; i < snippetsToExport.size(); i++) {
            Snippet s = snippetsToExport.get(i);
            json.append("    {\n");
            json.append("      \"name\": ").append(escapeJson(s.getName())).append(",\n");
            json.append("      \"content\": ").append(escapeJson(s.getContent())).append(",\n");
            json.append("      \"language\": ").append(escapeJson(s.getLanguage())).append(",\n");
            json.append("      \"category\": ").append(escapeJson(s.getCategory())).append(",\n");
            json.append("      \"tags\": [");
            List<String> tags = s.getTags();
            for (int j = 0; j < tags.size(); j++) {
                json.append(escapeJson(tags.get(j)));
                if (j < tags.size() - 1) json.append(", ");
            }
            json.append("]\n");
            json.append("    }");
            if (i < snippetsToExport.size() - 1) json.append(",");
            json.append("\n");
        }
        
        json.append("  ]\n");
        json.append("}\n");
        
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        logger.info("Exported {} snippets to {}", snippetsToExport.size(), file);
    }
    
    /**
     * Imports snippets from a JSON file.
     */
    public List<Snippet> importFromJson(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<Snippet> imported = new ArrayList<>();
        
        // Simple JSON parsing (no external library needed)
        // Find the snippets array
        int snippetsStart = content.indexOf("\"snippets\"");
        if (snippetsStart < 0) {
            throw new IOException("Invalid snippet JSON: 'snippets' array not found");
        }
        
        int arrayStart = content.indexOf('[', snippetsStart);
        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) {
            throw new IOException("Invalid snippet JSON: malformed array");
        }
        
        String arrayContent = content.substring(arrayStart + 1, arrayEnd);
        
        // Split into individual snippet objects
        List<String> objects = splitJsonObjects(arrayContent);
        
        for (String obj : objects) {
            Snippet snippet = new Snippet();
            snippet.setName(extractJsonString(obj, "name"));
            snippet.setContent(extractJsonString(obj, "content"));
            snippet.setLanguage(extractJsonString(obj, "language"));
            snippet.setCategory(extractJsonString(obj, "category"));
            
            // Parse tags array
            List<String> tags = extractJsonStringArray(obj, "tags");
            snippet.setTags(tags);
            
            // Ensure category exists
            String cat = snippet.getCategory();
            if (cat != null && !cat.isEmpty() && findCategoryByName(cat).isEmpty()) {
                addCategory(new SnippetCategory(cat));
            }
            
            imported.add(snippet);
        }
        
        logger.info("Imported {} snippets from {}", imported.size(), file);
        return imported;
    }
    
    // ---- XML Export / Import ----
    
    /**
     * Exports snippets to an XML file.
     */
    public void exportToXml(Path file, List<Snippet> snippetsToExport) throws Exception {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<snippetExport version=\"1\" exportDate=\"")
           .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\">\n");
        
        for (Snippet s : snippetsToExport) {
            xml.append("  <snippet>\n");
            xml.append("    <name>").append(escapeXml(s.getName())).append("</name>\n");
            xml.append("    <content>").append(escapeXml(s.getContent())).append("</content>\n");
            xml.append("    <language>").append(escapeXml(s.getLanguage())).append("</language>\n");
            if (s.getCategory() != null) {
                xml.append("    <category>").append(escapeXml(s.getCategory())).append("</category>\n");
            }
            if (s.getTags() != null && !s.getTags().isEmpty()) {
                xml.append("    <tags>\n");
                for (String tag : s.getTags()) {
                    xml.append("      <tag>").append(escapeXml(tag)).append("</tag>\n");
                }
                xml.append("    </tags>\n");
            }
            xml.append("  </snippet>\n");
        }
        
        xml.append("</snippetExport>\n");
        Files.writeString(file, xml.toString(), StandardCharsets.UTF_8);
        logger.info("Exported {} snippets to XML: {}", snippetsToExport.size(), file);
    }
    
    /**
     * Imports snippets from an XML file.
     */
    public List<Snippet> importFromXml(Path file) throws Exception {
        // Use JAXB with a dedicated export wrapper
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<Snippet> imported = new ArrayList<>();
        
        // Simple XML parsing for the export format
        int searchFrom = 0;
        while (true) {
            int snippetStart = content.indexOf("<snippet>", searchFrom);
            if (snippetStart < 0) break;
            int snippetEnd = content.indexOf("</snippet>", snippetStart);
            if (snippetEnd < 0) break;
            
            String block = content.substring(snippetStart, snippetEnd + "</snippet>".length());
            
            Snippet snippet = new Snippet();
            snippet.setName(extractXmlValue(block, "name"));
            snippet.setContent(unescapeXml(extractXmlValue(block, "content")));
            snippet.setLanguage(extractXmlValue(block, "language"));
            snippet.setCategory(extractXmlValue(block, "category"));
            
            // Parse tags
            List<String> tags = new ArrayList<>();
            int tagSearch = 0;
            while (true) {
                int tagStart = block.indexOf("<tag>", tagSearch);
                if (tagStart < 0) break;
                int tagEnd = block.indexOf("</tag>", tagStart);
                if (tagEnd < 0) break;
                tags.add(unescapeXml(block.substring(tagStart + "<tag>".length(), tagEnd)));
                tagSearch = tagEnd + "</tag>".length();
            }
            snippet.setTags(tags);
            
            // Ensure category exists
            String cat = snippet.getCategory();
            if (cat != null && !cat.isEmpty() && findCategoryByName(cat).isEmpty()) {
                addCategory(new SnippetCategory(cat));
            }
            
            imported.add(snippet);
            searchFrom = snippetEnd + "</snippet>".length();
        }
        
        logger.info("Imported {} snippets from XML: {}", imported.size(), file);
        return imported;
    }
    
    // ---- YAML Export / Import ----
    
    /**
     * Exports snippets to a YAML file.
     */
    public void exportToYaml(Path file, List<Snippet> snippetsToExport) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# KorTTY Snippet Export\n");
        yaml.append("version: 1\n");
        yaml.append("exportDate: \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\"\n");
        yaml.append("snippets:\n");
        
        for (Snippet s : snippetsToExport) {
            yaml.append("  - name: ").append(escapeYaml(s.getName())).append("\n");
            yaml.append("    language: ").append(escapeYaml(s.getLanguage())).append("\n");
            if (s.getCategory() != null && !s.getCategory().isEmpty()) {
                yaml.append("    category: ").append(escapeYaml(s.getCategory())).append("\n");
            }
            if (s.getTags() != null && !s.getTags().isEmpty()) {
                yaml.append("    tags:\n");
                for (String tag : s.getTags()) {
                    yaml.append("      - ").append(escapeYaml(tag)).append("\n");
                }
            }
            // Multi-line content with YAML literal block scalar
            yaml.append("    content: |\n");
            if (s.getContent() != null) {
                for (String line : s.getContent().split("\n", -1)) {
                    yaml.append("      ").append(line).append("\n");
                }
            }
        }
        
        Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
        logger.info("Exported {} snippets to YAML: {}", snippetsToExport.size(), file);
    }
    
    /**
     * Imports snippets from a YAML file.
     */
    public List<Snippet> importFromYaml(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Snippet> imported = new ArrayList<>();
        
        Snippet current = null;
        String currentField = null;
        StringBuilder contentBuilder = null;
        boolean inTags = false;
        boolean inContent = false;
        int contentIndent = -1;
        
        for (String line : lines) {
            // Detect snippet list item start
            if (line.matches("\\s{2}- name:.*")) {
                // Save previous snippet
                if (current != null) {
                    if (contentBuilder != null) {
                        current.setContent(trimTrailingNewline(contentBuilder.toString()));
                    }
                    finishImportedSnippet(current);
                    imported.add(current);
                }
                current = new Snippet();
                contentBuilder = null;
                inTags = false;
                inContent = false;
                current.setName(unescapeYaml(extractYamlValue(line)));
                continue;
            }
            
            if (current == null) continue;
            
            // Inside content block
            if (inContent) {
                // Check if this line is still part of the content block (indented)
                if (line.isEmpty() || (line.length() > contentIndent && line.substring(0, contentIndent).isBlank())) {
                    if (contentBuilder == null) contentBuilder = new StringBuilder();
                    else contentBuilder.append("\n");
                    contentBuilder.append(line.length() > contentIndent ? line.substring(contentIndent) : "");
                    continue;
                } else {
                    // Content block ended
                    inContent = false;
                    if (contentBuilder != null) {
                        current.setContent(trimTrailingNewline(contentBuilder.toString()));
                    }
                }
            }
            
            // Inside tags list
            if (inTags) {
                if (line.matches("\\s{6}-\\s.*")) {
                    current.getTags().add(unescapeYaml(line.replaceFirst("^\\s+- ", "").trim()));
                    continue;
                } else {
                    inTags = false;
                }
            }
            
            String trimmed = line.trim();
            if (trimmed.startsWith("language:")) {
                current.setLanguage(unescapeYaml(extractYamlValue(line)));
            } else if (trimmed.startsWith("category:")) {
                current.setCategory(unescapeYaml(extractYamlValue(line)));
            } else if (trimmed.equals("tags:")) {
                inTags = true;
                current.setTags(new ArrayList<>());
            } else if (trimmed.startsWith("content:")) {
                String value = extractYamlValue(line);
                if (value.equals("|") || value.isEmpty()) {
                    // Literal block scalar - content starts on next line
                    inContent = true;
                    contentIndent = line.indexOf("content:") + 2; // expected 6 spaces
                    contentBuilder = new StringBuilder();
                } else {
                    current.setContent(unescapeYaml(value));
                }
            }
        }
        
        // Save last snippet
        if (current != null) {
            if (contentBuilder != null && inContent) {
                current.setContent(trimTrailingNewline(contentBuilder.toString()));
            }
            finishImportedSnippet(current);
            imported.add(current);
        }
        
        logger.info("Imported {} snippets from YAML: {}", imported.size(), file);
        return imported;
    }
    
    private void finishImportedSnippet(Snippet snippet) {
        String cat = snippet.getCategory();
        if (cat != null && !cat.isEmpty() && findCategoryByName(cat).isEmpty()) {
            addCategory(new SnippetCategory(cat));
        }
    }
    
    private String trimTrailingNewline(String s) {
        while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        return s;
    }
    
    // ---- XML Helpers ----
    
    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
    
    private String unescapeXml(String value) {
        if (value == null) return null;
        return value.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'");
    }
    
    private String extractXmlValue(String xml, String tag) {
        String openTag = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        int start = xml.indexOf(openTag);
        if (start < 0) return null;
        int end = xml.indexOf(closeTag, start);
        if (end < 0) return null;
        return xml.substring(start + openTag.length(), end);
    }
    
    // ---- YAML Helpers ----
    
    private String escapeYaml(String value) {
        if (value == null) return "\"\"";
        // Quote if it contains special characters
        if (value.contains(":") || value.contains("#") || value.contains("\"")
                || value.contains("'") || value.contains("\n") || value.contains("{")
                || value.contains("}") || value.contains("[") || value.contains("]")
                || value.startsWith(" ") || value.endsWith(" ")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        return value;
    }
    
    private String unescapeYaml(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        // Remove surrounding quotes
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }
    
    private String extractYamlValue(String line) {
        int colonIndex = line.indexOf(':');
        if (colonIndex < 0) return "";
        String value = line.substring(colonIndex + 1).trim();
        // Remove surrounding quotes
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
    
    // ---- JSON Helpers ----
    
    private String escapeJson(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
    
    private String unescapeJson(String value) {
        if (value == null) return null;
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
    
    private int findMatchingBracket(String text, int openPos) {
        if (openPos < 0 || openPos >= text.length()) return -1;
        char open = text.charAt(openPos);
        char close = open == '[' ? ']' : '}';
        int depth = 1;
        boolean inString = false;
        for (int i = openPos + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }
    
    private List<String> splitJsonObjects(String arrayContent) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = -1;
        
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '"' && (i == 0 || arrayContent.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        objects.add(arrayContent.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return objects;
    }
    
    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;
        
        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;
        
        // Skip whitespace
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length()) return null;
        
        if (json.charAt(valueStart) == 'n') {
            // null value
            return null;
        }
        
        if (json.charAt(valueStart) != '"') return null;
        
        // Find end of string
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(c);
                sb.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return unescapeJson(sb.toString());
    }
    
    private List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return result;
        
        int bracketStart = json.indexOf('[', keyIndex);
        if (bracketStart < 0) return result;
        int bracketEnd = findMatchingBracket(json, bracketStart);
        if (bracketEnd < 0) return result;
        
        String arrayContent = json.substring(bracketStart + 1, bracketEnd);
        // Extract quoted strings
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '"' && (i == 0 || arrayContent.charAt(i - 1) != '\\')) {
                if (inString) {
                    result.add(unescapeJson(current.toString()));
                    current = new StringBuilder();
                }
                inString = !inString;
            } else if (inString) {
                current.append(c);
            }
        }
        return result;
    }
    
    // ---- JAXB Wrapper ----
    
    @XmlRootElement(name = "snippets")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class SnippetsWrapper {
        @XmlElement(name = "snippet")
        private List<Snippet> snippets;
        
        @XmlElement(name = "category")
        private List<SnippetCategory> categories;
        
        public List<Snippet> getSnippets() { return snippets; }
        public void setSnippets(List<Snippet> snippets) { this.snippets = snippets; }
        
        public List<SnippetCategory> getCategories() { return categories; }
        public void setCategories(List<SnippetCategory> categories) { this.categories = categories; }
    }
}
