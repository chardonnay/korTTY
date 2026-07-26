package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a {@link TranslationService} so one malformed batch response doesn't abort an entire run.
 *
 * <p>{@link DynamicLanguageGenerator} has no retry logic of its own — a single batch that comes
 * back null or the wrong size throws and stops the whole bundle. A local 7B-class model
 * occasionally drops or mangles JSON on a large batch, so for benchmark purposes each failed
 * batch is retried, then split in half and retried recursively (mirroring the halving behaviour
 * {@link GuideTranslationGenerator} already has for the guide). A single item that still fails
 * falls back to the original source text rather than aborting.
 */
final class ResilientTranslationService implements TranslationService {

    private static final int RETRIES_PER_SIZE = 2;

    private final TranslationService delegate;
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();

    ResilientTranslationService(TranslationService delegate) {
        this.delegate = delegate;
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        List<String> result = translateBatch(List.of(text), sourceLang, targetLang);
        return result != null && result.size() == 1 ? result.getFirst() : text;
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        return translateBatch(texts, sourceLang, targetLang, RETRIES_PER_SIZE);
    }

    private List<String> translateBatch(List<String> texts, String sourceLang, String targetLang,
                                         int attemptsLeft) {
        List<String> result = delegate.translateBatch(texts, sourceLang, targetLang);
        if (result != null && result.size() == texts.size()) {
            return result;
        }
        retries.incrementAndGet();
        if (attemptsLeft > 0) {
            return translateBatch(texts, sourceLang, targetLang, attemptsLeft - 1);
        }
        if (texts.size() == 1) {
            refused.incrementAndGet();
            return List.of(texts.getFirst());
        }
        int mid = texts.size() / 2;
        List<String> first = translateBatch(texts.subList(0, mid), sourceLang, targetLang, RETRIES_PER_SIZE);
        List<String> second = translateBatch(texts.subList(mid, texts.size()), sourceLang, targetLang, RETRIES_PER_SIZE);
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }

    int retries() {
        return retries.get();
    }

    /** Keys that fell back to the untranslated source text after every retry was exhausted. */
    int refused() {
        return refused.get();
    }
}
