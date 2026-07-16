package de.kortty.ai.huggingface;

import java.util.Optional;

/** Supplies an optional Hugging Face access token without coupling HTTP clients to the UI. */
@FunctionalInterface
public interface HuggingFaceTokenProvider {

    Optional<String> token();

    static HuggingFaceTokenProvider anonymous() {
        return Optional::empty;
    }

    static HuggingFaceTokenProvider fixed(String token) {
        if (token == null || token.isBlank()) {
            return anonymous();
        }
        String normalized = token.trim();
        return () -> Optional.of(normalized);
    }
}
