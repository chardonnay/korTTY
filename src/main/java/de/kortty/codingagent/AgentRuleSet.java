package de.kortty.codingagent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The parsed rule file of one {@link CodingAgentKind} (schema version {@link #SCHEMA_VERSION}).
 * Rules are held sorted by priority descending; ties keep file order. {@code sourcePath} is null for
 * bundled files. Parsing is strict: unknown keys anywhere, a bad regex, a duplicate rule id or a rule
 * without any matcher fail the whole file with an {@link IOException}.
 */
public record AgentRuleSet(CodingAgentKind kind, int version, String comment, CodingAgentState fallbackState,
                           List<AgentRule> rules, Source source, Path sourcePath) {

    public static final int SCHEMA_VERSION = 1;

    /** Where a rule set was loaded from. */
    public enum Source { BUNDLED, USER_OVERRIDE }

    private static final Gson GSON = new Gson();
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("kind", "version", "comment", "fallbackState", "rules");
    private static final Set<String> RULE_KEYS = Set.of("id", "state", "priority", "region", "anyLineRegex", "regex",
        "contains", "notContains", "title", "alternateScreen");
    private static final Set<String> REGION_KEYS = Set.of("bottomNonEmptyLines");
    private static final Pattern VALID_RULE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    public AgentRuleSet {
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    /**
     * Strict tree parse of one rule file.
     *
     * @param json       the complete file content
     * @param source     where the file came from
     * @param sourcePath the file path for user overrides, null for bundled resources
     * @throws IOException on invalid JSON or any schema violation; the message names the offending key or rule
     */
    public static AgentRuleSet parse(String json, Source source, Path sourcePath) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            throw new IOException("Rule file is not valid JSON: " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("Rule file root must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        rejectUnknownKeys(root, TOP_LEVEL_KEYS, "rule file");

        CodingAgentKind kind = parseKind(requiredString(root, "kind", "rule file"));
        int version = requiredInt(root, "version", "rule file");
        if (version != SCHEMA_VERSION) {
            throw new IOException("Unsupported rule file version " + version + " (expected " + SCHEMA_VERSION + ")");
        }
        String comment = optionalString(root, "comment", "rule file");
        String fallbackName = optionalString(root, "fallbackState", "rule file");
        CodingAgentState fallbackState = fallbackName == null
            ? CodingAgentState.IDLE
            : parseState(fallbackName, "rule file field fallbackState", true);

        JsonElement rulesElement = root.get("rules");
        if (rulesElement == null || !rulesElement.isJsonArray()) {
            throw new IOException("Rule file field rules must be an array");
        }
        List<AgentRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (JsonElement element : rulesElement.getAsJsonArray()) {
            AgentRule rule = parseRule(element, index);
            if (!ids.add(rule.id())) {
                throw new IOException("Duplicate rule id '" + rule.id() + "'");
            }
            rules.add(rule);
            index++;
        }
        rules.sort(Comparator.comparingInt(AgentRule::priority).reversed());
        return new AgentRuleSet(kind, version, comment, fallbackState, rules, source, sourcePath);
    }

    private static AgentRule parseRule(JsonElement element, int index) throws IOException {
        String where = "rule #" + (index + 1);
        if (!element.isJsonObject()) {
            throw new IOException(where + " must be an object");
        }
        JsonObject rule = element.getAsJsonObject();
        rejectUnknownKeys(rule, RULE_KEYS, where);
        String id = requiredString(rule, "id", where);
        if (!VALID_RULE_ID.matcher(id).matches()) {
            throw new IOException(where + ": id '" + id + "' must match " + VALID_RULE_ID.pattern());
        }
        where = "rule '" + id + "'";
        CodingAgentState state = parseState(requiredString(rule, "state", where), where + " field state", false);
        int priority = requiredInt(rule, "priority", where);
        int bottomNonEmptyLines = 0;
        JsonObject region = optionalObject(rule, "region", where);
        if (region != null) {
            rejectUnknownKeys(region, REGION_KEYS, where + " region");
            if (region.has("bottomNonEmptyLines")) {
                bottomNonEmptyLines = requiredInt(region, "bottomNonEmptyLines", where + " region");
                if (bottomNonEmptyLines < 0) {
                    throw new IOException(where + " region field bottomNonEmptyLines must be >= 0");
                }
            }
        }
        List<String> anyLineSources = optionalStringArray(rule, "anyLineRegex", where);
        List<Pattern> anyLineRegex = new ArrayList<>(anyLineSources.size());
        for (String source : anyLineSources) {
            anyLineRegex.add(compile(source, Pattern.UNICODE_CASE, where + " field anyLineRegex"));
        }
        String regexSource = optionalString(rule, "regex", where);
        Pattern regex = regexSource == null
            ? null
            : compile(regexSource, Pattern.UNICODE_CASE | Pattern.MULTILINE | Pattern.DOTALL, where + " field regex");
        List<String> contains = optionalStringArray(rule, "contains", where);
        List<String> notContains = optionalStringArray(rule, "notContains", where);
        String titleSource = optionalString(rule, "title", where);
        Pattern titleRegex = titleSource == null
            ? null
            : compile(titleSource, Pattern.UNICODE_CASE, where + " field title");
        Boolean alternateScreen = optionalBoolean(rule, "alternateScreen", where);

        boolean hasMatcher = !anyLineRegex.isEmpty() || regex != null || !contains.isEmpty()
            || titleRegex != null || alternateScreen != null;
        if (!hasMatcher) {
            throw new IOException(where + " declares no matcher (one of anyLineRegex, regex, contains, title,"
                + " alternateScreen is required)");
        }
        return new AgentRule(id, state, priority, bottomNonEmptyLines, anyLineRegex, regex, contains, notContains,
            titleRegex, alternateScreen);
    }

    private static CodingAgentKind parseKind(String name) throws IOException {
        for (CodingAgentKind candidate : CodingAgentKind.values()) {
            if (candidate != CodingAgentKind.UNKNOWN && candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IOException("Rule file field kind '" + name + "' is not a known coding agent kind");
    }

    private static CodingAgentState parseState(String name, String where, boolean allowUnknown) throws IOException {
        for (CodingAgentState candidate : CodingAgentState.values()) {
            if (candidate.name().equals(name)) {
                if (candidate == CodingAgentState.UNKNOWN && !allowUnknown) {
                    break;
                }
                return candidate;
            }
        }
        throw new IOException(where + " '" + name + "' is not an allowed state");
    }

    private static Pattern compile(String source, int flags, String where) throws IOException {
        try {
            return Pattern.compile(source, flags);
        } catch (PatternSyntaxException e) {
            throw new IOException(where + " is not a valid regex: " + e.getDescription(), e);
        }
    }

    private static void rejectUnknownKeys(JsonObject object, Set<String> allowed, String where) throws IOException {
        Set<String> unknown = new TreeSet<>(object.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IOException(where + " has unknown key(s) " + unknown + " (allowed: " + new TreeSet<>(allowed) + ")");
        }
    }

    private static String requiredString(JsonObject object, String name, String where) throws IOException {
        String value = optionalString(object, name, where);
        if (value == null) {
            throw new IOException(where + " is missing the required field " + name);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name, String where) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException(where + " field " + name + " must be a string");
        }
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String name, String where) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new IOException(where + " is missing the required field " + name);
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException(where + " field " + name + " must be an integer");
        }
        BigDecimal value;
        try {
            value = element.getAsBigDecimal().stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new IOException(where + " field " + name + " must be an integer", e);
        }
        if (value.scale() > 0) {
            throw new IOException(where + " field " + name + " must be an integer");
        }
        BigInteger integer = value.toBigIntegerExact();
        if (integer.bitLength() >= Integer.SIZE) {
            throw new IOException(where + " field " + name + " is out of range");
        }
        return integer.intValue();
    }

    private static List<String> optionalStringArray(JsonObject object, String name, String where) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException(where + " field " + name + " must be an array of strings");
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IOException(where + " field " + name + " must contain only strings");
            }
            String value = item.getAsString();
            if (value.isEmpty()) {
                throw new IOException(where + " field " + name + " must not contain empty strings");
            }
            values.add(value);
        }
        return values;
    }

    private static Boolean optionalBoolean(JsonObject object, String name, String where) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException(where + " field " + name + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static JsonObject optionalObject(JsonObject object, String name, String where) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new IOException(where + " field " + name + " must be an object");
        }
        return element.getAsJsonObject();
    }
}
