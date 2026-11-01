package de.kortty.stats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the skills of one logical AI request on the calling thread and hands them to the
 * {@link SkillStatsStore} once the outermost request succeeds.
 *
 * <p>A request opens a {@link Scope} around its one synchronous call; the skill block is built on the same thread
 * before the call is sent, so the block's {@link #report} lands in that scope. A nested request (the hybrid
 * skill classifier, for instance) opens its own scope: it hands its skills to the enclosing scope when it succeeds
 * and drops them when it fails, so only the outermost scope ever commits. A skill is counted once per request, so
 * retries inside one request do not count twice. A failed or cancelled request commits nothing.</p>
 *
 * <p>When recording is off the scopes still nest, but they collect nothing: {@link #isActive()} is false and no
 * token count is taken.</p>
 */
public final class SkillUsageCollector {

    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private SkillUsageCollector() {
    }

    /** Opens a collection for one logical request on this thread. Close it on every path, through try-with-resources. */
    public static Scope open() {
        boolean active = SkillStatsStore.get().isRecordingAllowed();
        Frame frame = new Frame(active);
        FRAMES.get().push(frame);
        return new Scope(frame);
    }

    /** Whether the request on this thread currently collects skill usage. */
    public static boolean isActive() {
        Frame frame = top();
        return frame != null && frame.active;
    }

    /** Adds {@code event} to the innermost request on this thread; a no-op when nothing is collecting. */
    public static void report(SkillUsageEvent event) {
        Frame frame = top();
        if (event == null || frame == null || !frame.active) {
            return;
        }
        String key = event.skillId() != null && !event.skillId().isBlank() ? event.skillId() : event.skillName();
        frame.events.putIfAbsent(key != null ? key : "", event);
    }

    private static Frame top() {
        return FRAMES.get().peek();
    }

    /** One open request. Closing it without {@link #commit()} discards what it collected. */
    public static final class Scope implements AutoCloseable {

        private final Frame frame;
        private boolean closed;

        private Scope(Frame frame) {
            this.frame = frame;
        }

        /** Marks the request as successful; the collected skills are kept when the scope closes. */
        public void commit() {
            frame.committed = true;
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
            if (!frame.committed || frame.events.isEmpty()) {
                return;
            }
            Frame enclosing = frames.peek();
            if (enclosing != null) {
                for (Map.Entry<String, SkillUsageEvent> entry : frame.events.entrySet()) {
                    enclosing.events.putIfAbsent(entry.getKey(), entry.getValue());
                }
                return;
            }
            List<SkillUsageEvent> committed = new ArrayList<>(frame.events.values());
            SkillStatsStore.get().recordCommitted(committed);
        }
    }

    private static final class Frame {
        private final boolean active;
        private final Map<String, SkillUsageEvent> events = new LinkedHashMap<>();
        private boolean committed;

        private Frame(boolean active) {
            this.active = active;
        }
    }
}
