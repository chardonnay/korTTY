package de.kortty.core;

import java.util.Locale;

/**
 * Moves inline chain-of-thought out of streamed answer snapshots before they reach the listener,
 * the streaming counterpart of {@link LocalAiReplySupport#separateInlineReasoning}.
 *
 * <p>Local sidecars sometimes deliver a reasoning model's thoughts inside the content: a leading
 * {@code <think>} block or gpt-oss harmony channels. Because every snapshot is the whole answer so
 * far, each one is split with the same start-anchored rules
 * ({@link AiResponseSanitizer#extractInlineReasoning}); a marker in the middle of the answer stays
 * answer text. While the beginning of the content could still turn into a marker
 * ({@code "<thi"}, or a harmony turn whose first {@code <|message|>} has not arrived), the
 * content is held back as empty, so no thought ever flashes up as answer text.</p>
 */
public final class ThinkTagStreamSplitter implements AiStreamListener {

    private static final String THINK_OPEN = "<think>";
    private static final String HARMONY_MESSAGE = "<|message|>";

    private final AiStreamListener delegate;

    public ThinkTagStreamSplitter(AiStreamListener delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate");
        }
        this.delegate = delegate;
    }

    /** @return {@code listener} wrapped in a splitter, or {@code null} for no listener. */
    public static AiStreamListener wrap(AiStreamListener listener) {
        return listener == null || listener instanceof ThinkTagStreamSplitter
            ? listener
            : new ThinkTagStreamSplitter(listener);
    }

    @Override
    public void onProgress(String contentSoFar, String reasoningSoFar) {
        Split split = split(contentSoFar, reasoningSoFar);
        delegate.onProgress(split.content(), split.reasoning());
    }

    @Override
    public void onRestart() {
        delegate.onRestart();
    }

    @Override
    public void onComplete() {
        delegate.onComplete();
    }

    /** One snapshot split into answer and reasoning (both never {@code null}). */
    record Split(String content, String reasoning) {
    }

    static Split split(String contentSoFar, String reasoningSoFar) {
        String content = contentSoFar != null ? contentSoFar : "";
        String reasoning = reasoningSoFar != null ? reasoningSoFar : "";
        String leading = content.stripLeading();
        if (leading.isEmpty()) {
            return new Split(content, reasoning);
        }
        if (mayStillBecomeMarker(leading)) {
            return new Split("", reasoning);
        }
        AiResponseSanitizer.InlineReasoning inline = AiResponseSanitizer.extractInlineReasoning(content);
        if (inline.reasoning() == null) {
            return new Split(content, reasoning);
        }
        String answer = stripPartialTrailingToken(inline.content());
        String thoughts = stripPartialTrailingToken(inline.reasoning());
        String merged = reasoning.isBlank() ? thoughts
            : thoughts.isBlank() ? reasoning
            : reasoning + "\n\n" + thoughts;
        return new Split(answer, merged);
    }

    /**
     * True while the content start is a strict prefix of {@code <think>}, or opens a harmony
     * turn ({@code <|...}) whose first message marker has not arrived yet.
     */
    private static boolean mayStillBecomeMarker(String leading) {
        String lower = leading.toLowerCase(Locale.ROOT);
        if (lower.length() < THINK_OPEN.length() && THINK_OPEN.startsWith(lower)) {
            return true;
        }
        return lower.startsWith("<|") && !lower.contains(HARMONY_MESSAGE);
    }

    /**
     * Drops a marker that is still being written at the end of a split part — {@code "</thi"} or
     * {@code "<|retu"} — so it never shows for a moment before the next chunk completes it.
     */
    private static String stripPartialTrailingToken(String text) {
        int open = text.lastIndexOf('<');
        if (open < 0 || text.indexOf('>', open) >= 0) {
            return text;
        }
        String tail = text.substring(open).toLowerCase(Locale.ROOT);
        if ("</think>".startsWith(tail) || tail.startsWith("<|")) {
            return text.substring(0, open).stripTrailing();
        }
        return text;
    }
}
