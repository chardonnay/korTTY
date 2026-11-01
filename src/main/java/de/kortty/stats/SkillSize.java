package de.kortty.stats;

import com.google.gson.Gson;
import de.kortty.core.AiTokenCounter;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiTokenizerType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The size of one skill as the statistics measure it: the tokens of its content and, when they are counted, its
 * learned rules, and the fingerprint that changes whenever any of the text that goes into a prompt changes. The token
 * count is cached per fingerprint, tokenizer and rule choice, so the request path only pays for hashing the skill text.
 *
 * @param fingerprint a short SHA-256 prefix over the name, description, tags, content and learned rules
 * @param tokens      the token count of the content and the learned rules
 */
public record SkillSize(String fingerprint, int tokens) {

    private static final int CACHE_SIZE = 256;
    private static final int FINGERPRINT_CHARS = 16;
    private static final Gson GSON = new Gson();
    private static final Map<String, Integer> TOKENS_BY_KEY = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** The size of {@code skill} counted with {@code tokenizer}, learned rules included; a null tokenizer counts with the estimate. */
    public static SkillSize of(AiSkill skill, AiTokenizerType tokenizer) {
        return of(skill, tokenizer, true);
    }

    /**
     * The size of {@code skill} counted with {@code tokenizer}. {@code includeLearnedRules} is false when the prompt that
     * carries the skill leaves the rules out, so that the size of what was sent is what gets counted.
     */
    public static SkillSize of(AiSkill skill, AiTokenizerType tokenizer, boolean includeLearnedRules) {
        AiTokenizerType counter = tokenizer != null ? tokenizer : AiTokenizerType.ESTIMATE;
        String fingerprint = fingerprint(skill);
        String key = fingerprint + '|' + counter.name() + (includeLearnedRules ? "" : "|content");
        Integer tokens;
        synchronized (TOKENS_BY_KEY) {
            tokens = TOKENS_BY_KEY.get(key);
        }
        if (tokens == null) {
            tokens = AiTokenCounter.countTextTokens(countedText(skill, includeLearnedRules), counter);
            synchronized (TOKENS_BY_KEY) {
                TOKENS_BY_KEY.put(key, tokens);
            }
        }
        return new SkillSize(fingerprint, tokens);
    }

    /** The fingerprint alone, for the callers that only compare skills. */
    public static String fingerprint(AiSkill skill) {
        if (skill == null) {
            return "";
        }
        MessageDigest digest = sha256();
        update(digest, skill.getName());
        update(digest, skill.getDescription());
        update(digest, GSON.toJson(skill.getTags() != null ? skill.getTags() : List.of()));
        update(digest, skill.getContent());
        for (AiSkillLearnedRule rule : skill.getLearnedRules()) {
            update(digest, rule != null ? rule.getText() : null);
        }
        return HexFormat.of().formatHex(digest.digest(), 0, FINGERPRINT_CHARS / 2);
    }

    private static String countedText(AiSkill skill, boolean includeLearnedRules) {
        StringBuilder text = new StringBuilder(skill.getContent() != null ? skill.getContent() : "");
        for (AiSkillLearnedRule rule : includeLearnedRules ? skill.getLearnedRules() : List.<AiSkillLearnedRule>of()) {
            if (rule != null && rule.getText() != null && !rule.getText().isBlank()) {
                text.append("\n- ").append(rule.getText().strip());
            }
        }
        return text.toString();
    }

    private static void update(MessageDigest digest, String value) {
        // A separator byte keeps "ab"+"c" and "a"+"bc" apart.
        digest.update((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
