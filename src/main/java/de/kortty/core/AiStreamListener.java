package de.kortty.core;

/**
 * Receives the growing answer of a streamed AI request.
 *
 * <p>Every call carries a <em>snapshot</em> — the whole answer and the whole reasoning received
 * so far — rather than a delta. Some endpoints repeat the complete reply as a replacement at the
 * end of a stream (MiniMax), and a local service may move leaked {@code <think>} text out of the
 * answer after the fact; with snapshots a consumer simply replaces what it shows and never has to
 * reconcile pieces.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #onProgress} is throttled by the service to about 20 calls per second, plus one
 *       final call when a stream ends. Both arguments are never {@code null} (empty when
 *       nothing arrived yet).</li>
 *   <li>{@link #onRestart} means everything shown so far is void: the service retries the request
 *       (a cut stream, an endpoint that rejected streaming, a local retry). Snapshots start again
 *       from empty.</li>
 *   <li>{@link #onComplete} is called once when the request succeeded. A wrapping service that
 *       rejects the answer afterwards may still follow it with {@link #onRestart}.</li>
 *   <li>Callbacks run on the thread that reads the HTTP response — never assume the JavaFX
 *       application thread. They must return quickly; a slow listener slows the read.</li>
 *   <li>An exception thrown by a listener is logged and ignored; it never fails the request.</li>
 *   <li>The returned {@link AiExecutionResult} stays the authoritative answer. Snapshots are a
 *       preview of the raw stream (untrimmed, before any post-processing).</li>
 * </ul>
 *
 * <p>Only the OpenAI-compatible transport — and the embedded llama.cpp and MLX services built on
 * it — streams. Anthropic, LM Studio native and local CLI services emit nothing.</p>
 */
public interface AiStreamListener {

    /** The answer and the reasoning received so far (both never {@code null}). */
    void onProgress(String contentSoFar, String reasoningSoFar);

    /** Discard what was shown: the request is being sent again. */
    default void onRestart() {
    }

    /** The request finished successfully; the final snapshot was already delivered. */
    default void onComplete() {
    }
}
