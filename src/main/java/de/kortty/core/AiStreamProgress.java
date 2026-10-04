package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Turns the raw bytes of an OpenAI-compatible SSE stream into throttled {@link AiStreamListener}
 * snapshots while the regular body read goes on unchanged.
 *
 * <p>One instance belongs to one {@link AiRequest} execution and is only touched by the thread
 * that runs it. The final answer is still produced by the service's own aggregation; this class
 * only previews it, so a malformed chunk is skipped here exactly as the aggregation skips it.</p>
 */
public final class AiStreamProgress {

    private static final Logger logger = LoggerFactory.getLogger(AiStreamProgress.class);

    /** About 20 snapshots per second. */
    static final long MIN_EMIT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final AiStreamListener listener;
    private final LongSupplier nanoClock;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private boolean dirty;
    private boolean emittedSinceRestart;
    private long lastEmitNanos;

    private AiStreamProgress(AiStreamListener listener, LongSupplier nanoClock) {
        this.listener = listener;
        this.nanoClock = nanoClock;
    }

    /** @return a progress tracker for {@code listener}, or {@code null} when nobody listens. */
    static AiStreamProgress of(AiStreamListener listener) {
        return of(listener, System::nanoTime);
    }

    static AiStreamProgress of(AiStreamListener listener, LongSupplier nanoClock) {
        return listener != null ? new AiStreamProgress(listener, nanoClock) : null;
    }

    /**
     * Starts one more attempt of the same request. When the previous attempt already showed
     * something, the listener is told to discard it first.
     */
    void beginAttempt() {
        if (emittedSinceRestart || content.length() > 0 || reasoning.length() > 0) {
            restart();
        }
    }

    /** Discards the current snapshot and tells the listener that the request is sent again. */
    void restart() {
        content.setLength(0);
        reasoning.setLength(0);
        dirty = false;
        emittedSinceRestart = false;
        restartQuietly(listener);
    }

    /** @return {@code body}, observed line by line for {@code data:} chunks as it is read. */
    InputStream observe(InputStream body) {
        return body != null ? new ObservingInputStream(body) : null;
    }

    /** Applies one SSE line; emits a snapshot when the throttle allows it. */
    void acceptLine(String rawLine) {
        String line = rawLine.trim();
        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        JsonObject chunk;
        try {
            chunk = JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return;
        }
        JsonArray choices = chunk.get("choices") instanceof JsonArray array ? array : null;
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        boolean isDelta = choice.has("delta") && choice.get("delta").isJsonObject();
        JsonElement part = isDelta ? choice.get("delta") : choice.get("message");
        if (part == null || !part.isJsonObject()) {
            return;
        }
        JsonObject delta = part.getAsJsonObject();
        boolean changed = append(content, delta.get("content"), isDelta);
        for (String field : new String[] {"reasoning_content", "reasoning"}) {
            changed |= append(reasoning, delta.get(field), isDelta);
        }
        if (changed) {
            dirty = true;
            long now = nanoClock.getAsLong();
            // The first snapshot of an attempt is shown at once, later ones at most every 50 ms.
            if (!emittedSinceRestart || now - lastEmitNanos >= MIN_EMIT_INTERVAL_NANOS) {
                emit(now);
            }
        }
    }

    private static boolean append(StringBuilder buffer, JsonElement piece, boolean isDelta) {
        if (piece == null || !piece.isJsonPrimitive()) {
            return false;
        }
        int before = buffer.length();
        String previous = isDelta ? null : buffer.toString();
        OpenAiCompatibleAiService.appendStreamed(buffer, piece.getAsString(), isDelta);
        return buffer.length() != before || (previous != null && !previous.contentEquals(buffer));
    }

    /** Delivers the latest snapshot if the throttle held it back. */
    void flush() {
        if (dirty) {
            emit(nanoClock.getAsLong());
        }
    }

    /** A buffered (non-streamed) answer: shown once, as a single snapshot. */
    void emitOnce(String answer, String thoughts) {
        content.setLength(0);
        reasoning.setLength(0);
        if (answer != null) {
            content.append(answer);
        }
        if (thoughts != null) {
            reasoning.append(thoughts);
        }
        emit(nanoClock.getAsLong());
    }

    /** The request succeeded: flushes the last snapshot and tells the listener. */
    void complete() {
        flush();
        try {
            listener.onComplete();
        } catch (RuntimeException e) {
            logger.warn("AI stream listener failed in onComplete; ignoring it.", e);
        }
    }

    private void emit(long now) {
        dirty = false;
        emittedSinceRestart = true;
        lastEmitNanos = now;
        try {
            listener.onProgress(content.toString(), reasoning.toString());
        } catch (RuntimeException e) {
            logger.warn("AI stream listener failed in onProgress; ignoring it.", e);
        }
    }

    /** Calls {@link AiStreamListener#onRestart()} on a possibly {@code null} listener, never throwing. */
    public static void restartQuietly(AiStreamListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onRestart();
        } catch (RuntimeException e) {
            logger.warn("AI stream listener failed in onRestart; ignoring it.", e);
        }
    }

    /** Passes every byte through and feeds each complete line to {@link #acceptLine(String)}. */
    private final class ObservingInputStream extends FilterInputStream {

        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        ObservingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                observe((byte) value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            for (int i = 0; i < read; i++) {
                observe(buffer[offset + i]);
            }
            return read;
        }

        private void observe(byte value) {
            if (value != '\n') {
                line.write(value);
                return;
            }
            // A line never splits a UTF-8 sequence: 0x0A cannot occur inside a multi-byte one.
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            acceptLine(text);
        }
    }
}
