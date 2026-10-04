package de.kortty.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The server-side allowlist an MCP client ({@code client_kind = "mcp"}) is held to.
 *
 * <p>Pure, any thread. <strong>Fail-closed:</strong> a method is offered to an MCP client only when
 * it is classified {@link Access#READ} here, or {@link Access#WRITE} while the user has allowed MCP
 * write tools. Every other name — the agent verbs, events, layout changes, notifications, the
 * reserved verbs, and any verb added to the control API later — is refused with
 * {@link ControlErrorCode#METHOD_NOT_ALLOWED_FOR_MCP}. A new verb therefore stays invisible to MCP
 * clients until someone classifies it on purpose (and routes its text through the MCP masking);
 * {@code McpMethodAllowlistTest} insists every registered method is classified explicitly.
 *
 * <p>This narrows what the MCP tool surface exposes; it is not a sandbox. The client kind is declared
 * by the client, and any process of the same user can read the token and connect as a plain CLI
 * client.
 */
public final class McpMethodAllowlist {

    /** How a method is offered to MCP clients. */
    public enum Access {
        /** Offered whenever the MCP server is on: reads, lists and waits that change nothing. */
        READ,
        /** Offered only while MCP write tools are allowed: typing into a pane. */
        WRITE,
        /** Never offered to MCP clients. */
        REFUSED
    }

    /** Data reason: the method is not part of the MCP surface at all. */
    public static final String REASON_NOT_EXPOSED = "not_exposed";

    /** Data reason: a write verb, and MCP write tools are switched off. */
    public static final String REASON_WRITE_TOOLS_OFF = "write_tools_disabled";

    private static final String SCHEMA_METHOD = "api.schema";

    private static final Map<String, Access> CLASSIFIED = classified();

    private McpMethodAllowlist() {
    }

    private static Map<String, Access> classified() {
        Map<String, Access> map = new LinkedHashMap<>();
        // The handshake itself; ControlConnection handles it before the allowlist is consulted.
        map.put(ControlConnection.AUTH_METHOD, Access.READ);
        for (String read : Set.of("ping", SCHEMA_METHOD, "window.list", "tab.list", "pane.list",
                "pane.current", "pane.get", "pane.read", "pane.wait_output", "agent.list", "agent.get")) {
            map.put(read, Access.READ);
        }
        for (String write : Set.of("pane.send_text", "pane.run", "pane.send_keys")) {
            map.put(write, Access.WRITE);
        }
        // Listed explicitly so a review sees the decision, not only the default.
        for (String refused : Set.of("events.subscribe", "events.unsubscribe", "notification.show",
                "tab.focus", "tab.create", "tab.close", "tab.rename", "pane.focus", "pane.split",
                "pane.close", "pane.resolve", "agent.send_keys", "agent.prompt", "agent.start",
                "agent.explain", "agent.wait", "agent.rename", "agent.send_text", "agent.seen")) {
            map.put(refused, Access.REFUSED);
        }
        return Map.copyOf(map);
    }

    /** How {@code method} is offered to MCP clients; {@link Access#REFUSED} for every unknown name. */
    public static Access classify(String method) {
        return method == null ? Access.REFUSED : CLASSIFIED.getOrDefault(method, Access.REFUSED);
    }

    /** Whether {@code method} carries an explicit decision here, refused or not. */
    public static boolean isClassified(String method) {
        return method != null && CLASSIFIED.containsKey(method);
    }

    /** Whether an MCP client may call {@code method} now. */
    public static boolean allows(String method, boolean writesAllowed) {
        Access access = classify(method);
        return access == Access.READ || (access == Access.WRITE && writesAllowed);
    }

    /**
     * Refuses {@code method} unless an MCP client may call it now; for {@code api.schema} with a
     * {@code method} parameter, that named method has to be allowed as well, so the schema can never
     * describe a verb the client is refused.
     *
     * @throws ControlApiException {@link ControlErrorCode#METHOD_NOT_ALLOWED_FOR_MCP}, with
     *     {@code data.method} and {@code data.reason}
     */
    public static void check(String method, JsonObject params, boolean writesAllowed)
            throws ControlApiException {
        refuseUnlessAllowed(method, writesAllowed);
        if (SCHEMA_METHOD.equals(method) && params != null && params.has("method")
                && params.get("method").isJsonPrimitive()) {
            refuseUnlessAllowed(params.get("method").getAsString(), writesAllowed);
        }
    }

    private static void refuseUnlessAllowed(String method, boolean writesAllowed)
            throws ControlApiException {
        if (allows(method, writesAllowed)) {
            return;
        }
        boolean write = classify(method) == Access.WRITE;
        String name = method == null ? "" : method;
        throw new ControlApiException(ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP,
            write ? "MCP write tools are switched off in korTTY"
                : "This method is not offered to MCP clients",
            Map.of("method", name, "reason", write ? REASON_WRITE_TOOLS_OFF : REASON_NOT_EXPOSED));
    }

    /**
     * The {@code api.schema} document as an MCP client sees it: only the methods it may call, and no
     * reserved verbs. A single-method entry passes through unchanged, because {@link #check} already
     * refused a method the client may not see.
     */
    public static JsonElement filterSchema(JsonElement result, boolean writesAllowed) {
        if (result == null || !result.isJsonObject()) {
            return result;
        }
        JsonObject document = result.getAsJsonObject();
        JsonElement methods = document.get("methods");
        if (methods == null || !methods.isJsonArray()) {
            return result;
        }
        JsonObject filtered = document.deepCopy();
        JsonArray kept = new JsonArray();
        for (JsonElement entry : methods.getAsJsonArray()) {
            if (entry.isJsonObject() && entry.getAsJsonObject().has("name")
                    && allows(entry.getAsJsonObject().get("name").getAsString(), writesAllowed)) {
                kept.add(entry.deepCopy());
            }
        }
        filtered.add("methods", kept);
        if (filtered.has("reserved")) {
            filtered.add("reserved", new JsonArray());
        }
        return filtered;
    }

    /**
     * The {@code auth} hello as an MCP client sees it: only the method names it may call, only the
     * capabilities whose verb it may call, and {@code mcp_write_tools} saying whether the write verbs
     * are on.
     *
     * @param capabilityMethods capability name to the verb that provides it
     */
    public static JsonElement filterHello(JsonElement result, boolean writesAllowed,
                                          Map<String, String> capabilityMethods) {
        if (result == null || !result.isJsonObject()) {
            return result;
        }
        JsonObject hello = result.getAsJsonObject().deepCopy();
        JsonElement methods = hello.get("methods");
        if (methods != null && methods.isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement name : methods.getAsJsonArray()) {
                if (name.isJsonPrimitive() && allows(name.getAsString(), writesAllowed)) {
                    kept.add(name);
                }
            }
            hello.add("methods", kept);
        }
        JsonElement capabilities = hello.get("capabilities");
        if (capabilities != null && capabilities.isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement capability : capabilities.getAsJsonArray()) {
                String verb = capability.isJsonPrimitive()
                    ? capabilityMethods.get(capability.getAsString()) : null;
                if (verb != null && allows(verb, writesAllowed)) {
                    kept.add(capability);
                }
            }
            hello.add("capabilities", kept);
        }
        hello.addProperty("client_kind", ControlSession.ClientKind.MCP.wire());
        hello.addProperty("mcp_write_tools", writesAllowed);
        return hello;
    }
}
