package de.kortty.core;

import de.kortty.memory.MemoryBlock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Collects what one AI request sends besides the user's text, on the thread that sends it, so that every request gets
 * its own {@link AiSentContext}. Requests of one tab can overlap (a follow-up while the previous answer is still
 * coming), and the skill support of a tab is shared between them; reading "the last block" later would mix them up.
 *
 * <p>A request opens a {@link Scope} around its one synchronous call. The prompt is built on the same thread, and the
 * prompt builder reports the memory and the skills it sends into the innermost open scope. A report made where no
 * scope is open is dropped, so previews that build the same text count nothing.</p>
 */
public final class AiSentContextCollector {

    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * The outcome of one request: the result and what it sent.
     *
     * @param result the answer, or {@code null} when the request returned none
     * @param sent   what the request sent; never {@code null}
     */
    public record Reply(AiExecutionResult result, AiSentContext sent) {

        public Reply {
            sent = sent == null ? AiSentContext.empty() : sent;
        }
    }

    private AiSentContextCollector() {
    }

    /** Opens a collection for one request on this thread. Close it on every path, through try-with-resources. */
    public static Scope open() {
        Frame frame = new Frame();
        FRAMES.get().push(frame);
        return new Scope(frame);
    }

    /**
     * Runs one request on this thread and returns its answer together with what the request sent. The request must send
     * on the calling thread, as every transport does. A request that fails throws a {@link Failed} that carries the same
     * summary, so the failure can still show what the request was built with.
     */
    public static Reply capture(Callable<AiExecutionResult> request) throws Exception {
        try (Scope scope = open()) {
            AiExecutionResult result;
            try {
                result = request.call();
            } catch (Exception failure) {
                throw new Failed(failure, scope.summary());
            }
            return new Reply(result, scope.summary());
        }
    }

    /**
     * A request that failed, with what it was built with. The message and the cause are those of the failure.
     */
    public static final class Failed extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient AiSentContext sent;

        /** The failure of a request, with the summary that was collected before it failed. */
        public Failed(Exception failure, AiSentContext sent) {
            super(failure.getMessage(), failure);
            this.sent = sent == null ? AiSentContext.empty() : sent;
        }

        /** What the failed request was built with: the memory and the skills, which it sent or tried to send. */
        public AiSentContext sent() {
            return sent;
        }
    }

    /** Whether a request on this thread currently collects what it sends. */
    public static boolean isActive() {
        return top() != null;
    }

    /** Records the memory block the request on this thread sends; a later block of the same request replaces it. */
    public static void reportMemory(MemoryBlock block) {
        Frame frame = top();
        if (frame != null) {
            frame.memory = block != null ? block : MemoryBlock.empty();
        }
    }

    /** Records a skill the request on this thread sends; a skill that is reported twice counts once. */
    public static void reportSkill(AiSentContext.Skill skill) {
        Frame frame = top();
        if (frame != null && skill != null) {
            frame.skills.putIfAbsent(keyOf(skill), skill);
        }
    }

    private static Frame top() {
        return FRAMES.get().peek();
    }

    private static String keyOf(AiSentContext.Skill skill) {
        return skill.id().isBlank() ? skill.name() : skill.id();
    }

    /**
     * One open collection. Its {@link #summary()} must be read on the thread that opened it, before the scope closes.
     */
    public static final class Scope implements AutoCloseable {

        private final Frame frame;
        private boolean closed;

        private Scope(Frame frame) {
            this.frame = frame;
        }

        /** What the request has sent so far. */
        public AiSentContext summary() {
            return new AiSentContext(frame.memory, List.copyOf(frame.skills.values()));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Deque<Frame> frames = FRAMES.get();
            frames.remove(frame);
            if (frames.isEmpty()) {
                FRAMES.remove();
            }
        }
    }

    private static final class Frame {
        private MemoryBlock memory = MemoryBlock.empty();
        private final Map<String, AiSentContext.Skill> skills = new LinkedHashMap<>();
    }
}
