package de.kortty.core.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.RedactionResult;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.model.AiProfile;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * What one terminal-agent run (or one planning step) may send to its AI profile: the masking
 * state of that run, handed down to the agent's single AI choke point.
 *
 * <p>One {@code TerminalAgentService} serves parallel swarm agents, so this state is never kept in
 * a field of the service: every run creates its own context and passes it along.</p>
 *
 * <p>Masking reuses {@link AiOutboundRedaction}: the same exemptions (integrated llama.cpp and MLX
 * models, a trusted loopback endpoint), the same fail-closed rule for a missing profile, the run's
 * known secrets first, then the well-known token formats. Only what leaves for the AI is masked;
 * the local transcript, the activity panel, the approval dialog and the journal keep the raw
 * text.</p>
 *
 * <p>The count is the number of <em>distinct</em> masked values of the run, because the agent
 * resends its whole history every turn. The values themselves are not kept, only a SHA-256
 * fingerprint of each.</p>
 */
public final class AgentOutboundContext {

    private static final Gson GSON = new Gson();

    private final AiProfile profile;
    private final SessionJournalRedactor knownSecrets;
    private final Set<String> distinctMasked;
    private final IntConsumer onDistinctCountChanged;

    /**
     * @param profile                the profile the run sends to; {@code null} masks (fail closed)
     * @param knownSecrets           the run's own redactor (never shared with a terminal or another
     *                               run: a sudo password typed during the run is added to it);
     *                               {@code null} starts one with the organisation's rules only
     * @param distinctMasked         fingerprints of the values masked so far; {@code null} starts
     *                               an empty set
     * @param onDistinctCountChanged told the new distinct count whenever it grows; may be
     *                               {@code null}
     */
    public AgentOutboundContext(
        AiProfile profile,
        SessionJournalRedactor knownSecrets,
        Set<String> distinctMasked,
        IntConsumer onDistinctCountChanged) {
        this.profile = profile;
        this.knownSecrets = knownSecrets != null ? knownSecrets : AiOutboundRedaction.newPolicyRedactor();
        this.distinctMasked = distinctMasked != null ? distinctMasked : ConcurrentHashMap.newKeySet();
        this.onDistinctCountChanged = onDistinctCountChanged;
    }

    /**
     * A context for one run, with a fresh redactor that holds the organisation's rules plus the
     * known secrets of every given source (the terminal tab, the command runner). The sources
     * themselves are not changed.
     */
    public static AgentOutboundContext forRun(
        AiProfile profile, IntConsumer onDistinctCountChanged, SessionJournalRedactor... sources) {
        return new AgentOutboundContext(profile, combine(sources), null, onDistinctCountChanged);
    }

    /** A fresh redactor with the organisation's rules and the known secrets of all sources. */
    public static SessionJournalRedactor combine(SessionJournalRedactor... sources) {
        SessionJournalRedactor combined = AiOutboundRedaction.newPolicyRedactor();
        if (sources != null) {
            for (SessionJournalRedactor source : sources) {
                combined.addSecretsFrom(source);
            }
        }
        return combined;
    }

    public AiProfile profile() {
        return profile;
    }

    /** True when text bound for this run's profile is masked before it is sent. */
    public boolean masks() {
        return AiOutboundRedaction.appliesTo(profile);
    }

    /**
     * Adds a secret that became known during the run — the sudo password once the user typed it
     * — so that later prompts mask it too.
     */
    public void addSecret(char[] secret) {
        if (secret == null || secret.length == 0) {
            return;
        }
        knownSecrets.addSecret(new String(secret));
    }

    /** Masks one prompt for the run's profile, or returns it unchanged when the profile is exempt. */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int before = distinctMasked.size();
        RedactionResult result = AiOutboundRedaction.redactFor(profile, text, knownSecrets, this::remember);
        notifyIfGrown(before);
        return result.text();
    }

    /**
     * A copy of {@code value} with every string inside it masked, for data the agent serializes
     * into its prompts as JSON (the probe snapshot, the command history). Masking the strings
     * before serialization matters: JSON escapes a line break as {@code \n}, which hides a token
     * at the start of the next output line from the token patterns' word-boundary checks.
     */
    public <T> T maskStructured(T value, Type type) {
        if (value == null || !masks()) {
            return value;
        }
        int before = distinctMasked.size();
        JsonElement masked = maskElement(GSON.toJsonTree(value, type));
        notifyIfGrown(before);
        return GSON.fromJson(masked, type);
    }

    /** Number of distinct values masked in this run so far. */
    public int distinctMaskedCount() {
        return distinctMasked.size();
    }

    private JsonElement maskElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return primitive;
            }
            return new JsonPrimitive(Objects.requireNonNullElse(
                AiOutboundRedaction.redactFor(profile, primitive.getAsString(), knownSecrets, this::remember).text(),
                ""));
        }
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                copy.add(maskElement(item));
            }
            return copy;
        }
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            copy.add(entry.getKey(), maskElement(entry.getValue()));
        }
        return copy;
    }

    private void remember(String maskedValue) {
        if (maskedValue != null && !maskedValue.isEmpty()) {
            distinctMasked.add(fingerprint(maskedValue));
        }
    }

    private void notifyIfGrown(int before) {
        int after = distinctMasked.size();
        if (after > before && onDistinctCountChanged != null) {
            onDistinctCountChanged.accept(after);
        }
    }

    private static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
