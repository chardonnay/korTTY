package de.kortty.core;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import de.kortty.model.AiTokenizerType;

/**
 * Token counting helpers for AI prompts.
 */
public final class AiTokenCounter {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final int CHAT_MESSAGE_OVERHEAD_TOKENS = 12;

    private AiTokenCounter() {
    }

    public static int countTextTokens(String text, AiTokenizerType tokenizerType) {
        String safeText = text != null ? text : "";
        if (safeText.isEmpty()) {
            return 0;
        }
        if (tokenizerType == null || tokenizerType == AiTokenizerType.ESTIMATE) {
            return estimateTokens(safeText);
        }
        try {
            Encoding encoding = switch (tokenizerType) {
                case CL100K_BASE -> REGISTRY.getEncoding(EncodingType.CL100K_BASE);
                case O200K_BASE -> REGISTRY.getEncoding(EncodingType.O200K_BASE);
                case P50K_BASE -> REGISTRY.getEncoding(EncodingType.P50K_BASE);
                case R50K_BASE -> REGISTRY.getEncoding(EncodingType.R50K_BASE);
                case ESTIMATE -> null;
            };
            return encoding != null ? encoding.countTokens(safeText) : estimateTokens(safeText);
        } catch (Exception ignored) {
            return estimateTokens(safeText);
        }
    }

    public static int countRequestTokens(AiRequest request, AiTokenizerType tokenizerType) {
        if (request == null) {
            return 0;
        }
        int systemTokens = countTextTokens(AiPromptBuilder.buildSystemPrompt(request), tokenizerType);
        int userTokens = countTextTokens(AiPromptBuilder.buildUserPrompt(request), tokenizerType);
        return systemTokens + userTokens + CHAT_MESSAGE_OVERHEAD_TOKENS;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int charBased = (int) Math.ceil(text.codePointCount(0, text.length()) / 4.0);
        int wordBased = (int) Math.ceil(text.trim().split("\\s+").length * 1.35);
        return Math.max(1, Math.max(charBased, wordBased));
    }
}
