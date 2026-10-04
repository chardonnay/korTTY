package de.kortty.core.highlight;

import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The application's keyword highlighting: the compiled rule sets, the one thread every pane's
 * {@link TerminalOutputHighlighter} runs on, and the rule that decides which set a pane shows.
 *
 * <p><b>Which set a pane shows</b> — the most specific choice wins:
 * <ol>
 *   <li>the pane's own runtime choice ({@link PaneSelection#paneOverride()}),</li>
 *   <li>the connection's choice ({@link PaneSelection#connectionSetId()}),</li>
 *   <li>the global default ({@link GlobalSettings#getDefaultHighlightRuleSetId()}),</li>
 *   <li>otherwise none.</li>
 * </ol>
 * {@value #NONE_ID} at any level switches highlighting off for the pane, whatever the levels below
 * say. An id that names no set (a deleted set, or a shared connection file from another machine)
 * is skipped, so the next level decides. The master switch
 * ({@link GlobalSettings#isTerminalHighlightingEnabled()}) off means none everywhere.
 *
 * <p><b>Cost.</b> All passes run on one daemon thread, {@value #THREAD_NAME}, and together keep it
 * busy at most {@link #MAX_DUTY} of the time: after a pass that took {@code t}, the next one waits
 * at least {@code t}. One pane's flood therefore cannot starve the others or the machine.
 */
public final class TerminalHighlightService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TerminalHighlightService.class);

    /** Name of the daemon thread created by {@link #defaultScheduler()}. */
    public static final String THREAD_NAME = "kortty-highlighter";

    /** The set id that means "explicitly no highlighting" at the level it is set on. */
    public static final String NONE_ID = "none";

    /** Largest share of time all highlighter passes together may keep their thread busy. */
    public static final double MAX_DUTY = 0.5;

    /**
     * Where a pane's choice of rule set comes from. Read whenever the pane's set is resolved; must be
     * cheap and thread-safe.
     */
    @FunctionalInterface
    public interface PaneSelection {

        /** The pane's runtime choice: a set id, {@value #NONE_ID}, or {@code null} to inherit. */
        String paneOverride();

        /** The connection's choice: a set id, {@value #NONE_ID}, or {@code null} to inherit. */
        default String connectionSetId() {
            return null;
        }
    }

    /** The level that decided which set a pane shows ({@link #decidingLevel}). */
    public enum Level {
        /** The pane's own runtime choice. */
        PANE,
        /** The connection's rule set ({@code ServerConnection.highlightRuleSetId}). */
        CONNECTION,
        /** The global default rule set. */
        DEFAULT,
        /** No level applies, or the master switch is off: the pane shows nothing. */
        NONE
    }

    /** Everything resolution needs, swapped as one value on {@link #reload}. */
    private record Catalog(boolean enabled, String defaultSetId, boolean alternateScreen,
                           Map<String, CompiledHighlightSet> sets, Map<String, String> signatures,
                           Map<String, String> names) {
    }

    /** The built-ins are constant, so they are compiled once. */
    private static final class Builtins {

        private static final Map<String, CompiledHighlightSet> SETS = compileBuiltins();

        private static Map<String, CompiledHighlightSet> compileBuiltins() {
            Map<String, CompiledHighlightSet> sets = new LinkedHashMap<>();
            for (HighlightRuleSet set : HighlightBuiltinSets.all()) {
                sets.put(set.getId(), CompiledHighlightSet.compile(set));
            }
            return Collections.unmodifiableMap(sets);
        }
    }

    /**
     * The duty cap shared by every pane: after a pass that kept the thread busy for {@code t}, no
     * pass may start for {@code t * (1 - duty) / duty}.
     */
    static final class DutyCycle {

        private final double duty;

        private long notBefore;

        private boolean armed;

        DutyCycle(double duty) {
            if (!(duty > 0.0 && duty <= 1.0)) {
                throw new IllegalArgumentException("duty must be in (0, 1]: " + duty);
            }
            this.duty = duty;
        }

        synchronized long delayNanos(long nowNanos) {
            if (!armed) {
                return 0L;
            }
            return Math.max(0L, notBefore - nowNanos);
        }

        synchronized void record(long startNanos, long endNanos) {
            long busy = Math.max(0L, endNanos - startNanos);
            notBefore = endNanos + (long) (busy * (1.0 - duty) / duty);
            armed = true;
        }
    }

    private final ScheduledExecutorService executor;

    private final DutyCycle duty = new DutyCycle(MAX_DUTY);

    private final AtomicReference<Catalog> catalog;

    private final Map<TerminalOutputHighlighter, PaneSelection> panes = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final TerminalOutputHighlighter.PassScheduler passScheduler = new TerminalOutputHighlighter.PassScheduler() {
        @Override
        public void schedule(Runnable task, long delayMillis) {
            executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public long dutyDelayNanos(long nowNanos) {
            return duty.delayNanos(nowNanos);
        }

        @Override
        public void passFinished(long startNanos, long endNanos) {
            duty.record(startNanos, endNanos);
        }
    };

    /** A single-thread scheduled executor whose daemon thread is named {@link #THREAD_NAME}. */
    public static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /**
     * Starts with the built-in sets, highlighting enabled and no default set; {@link #reload} reads
     * the user's settings.
     *
     * @param executor the executor this service owns and shuts down in {@link #stop()}
     */
    public TerminalHighlightService(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.catalog = new AtomicReference<>(new Catalog(true, null, false, Builtins.SETS, Map.of(), Map.of()));
    }

    /**
     * Reads the highlighting settings and user sets, recompiles what changed and moves every pane to
     * the set it now resolves to. A set whose rules did not change keeps its compiled instance, so
     * panes showing it are not swept again. User sets with a missing, reserved or duplicate id, and
     * those beyond {@link HighlightRuleValidator#MAX_USER_SETS}, are ignored (logged by id). Runs at
     * startup and every time the settings are saved, on the FX thread.
     */
    public void reload(GlobalSettings settings) {
        Catalog previous = catalog.get();
        Map<String, CompiledHighlightSet> sets = new LinkedHashMap<>(Builtins.SETS);
        Map<String, String> signatures = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        boolean enabled = true;
        String defaultSetId = null;
        boolean alternateScreen = false;
        if (settings != null) {
            enabled = settings.isTerminalHighlightingEnabled();
            defaultSetId = settings.getDefaultHighlightRuleSetId();
            alternateScreen = settings.isTerminalHighlightAlternateScreen();
            int userSets = 0;
            for (HighlightRuleSet set : settings.getHighlightRuleSets()) {
                if (set == null) {
                    continue;
                }
                String id = set.getId() != null ? set.getId().trim() : "";
                if (id.isEmpty() || HighlightBuiltinSets.isReservedId(id) || NONE_ID.equals(id) || sets.containsKey(id)) {
                    logger.warn("Highlight rule set with id '{}' is ignored: the id is missing, reserved or used twice", id);
                    continue;
                }
                if (++userSets > HighlightRuleValidator.MAX_USER_SETS) {
                    logger.warn("More than {} highlight rule sets; set {} and the rest are ignored",
                        HighlightRuleValidator.MAX_USER_SETS, id);
                    break;
                }
                String signature = signature(set);
                CompiledHighlightSet compiled = signature.equals(previous.signatures().get(id))
                    ? previous.sets().get(id) : null;
                if (compiled == null) {
                    compiled = CompiledHighlightSet.compile(set);
                }
                sets.put(id, compiled);
                signatures.put(id, signature);
                if (set.getName() != null && !set.getName().isBlank()) {
                    names.put(id, set.getName().trim());
                }
            }
        }
        catalog.set(new Catalog(enabled, defaultSetId, alternateScreen,
            Collections.unmodifiableMap(sets), Collections.unmodifiableMap(signatures),
            Collections.unmodifiableMap(names)));
        refreshAll(alternateScreen != previous.alternateScreen());
    }

    /**
     * Attaches a highlighter to a pane's buffer, starting on the set the pane resolves to.
     *
     * @param selection where the pane's choice comes from
     * @param repaint asks the pane to redraw (any thread)
     * @param onRestyled called after a pass changed what the pane shows
     * @param findActive whether the pane's Find bar shows results
     * @return the highlighter, or {@code null} once the service is stopped
     */
    public TerminalOutputHighlighter attach(TerminalTextBuffer buffer, PaneSelection selection, Runnable repaint,
                                            Runnable onRestyled, BooleanSupplier findActive) {
        return attach(buffer, selection, repaint, onRestyled, findActive, null);
    }

    /**
     * Attaches a highlighter as {@link #attach(TerminalTextBuffer, PaneSelection, Runnable, Runnable,
     * BooleanSupplier)} does, reporting the pane's triggers to {@code triggerSink}.
     *
     * @param triggerSink where the pane's triggers go (on the highlighter thread), or {@code null}
     */
    public TerminalOutputHighlighter attach(TerminalTextBuffer buffer, PaneSelection selection, Runnable repaint,
                                            Runnable onRestyled, BooleanSupplier findActive,
                                            TerminalOutputHighlighter.TriggerSink triggerSink) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(selection, "selection");
        if (closed.get()) {
            return null;
        }
        TerminalOutputHighlighter highlighter = new TerminalOutputHighlighter(buffer, resolve(selection),
            repaint, onRestyled, findActive, () -> catalog.get().alternateScreen(), passScheduler, triggerSink);
        panes.put(highlighter, selection);
        if (closed.get()) {
            panes.remove(highlighter);
            highlighter.close();
            return null;
        }
        return highlighter;
    }

    /** Detaches and closes a highlighter; {@code null} is ignored. */
    public void detach(TerminalOutputHighlighter highlighter) {
        if (highlighter == null) {
            return;
        }
        panes.remove(highlighter);
        highlighter.close();
    }

    /** Moves one pane to the set it resolves to now, after its choice changed. */
    public void refresh(TerminalOutputHighlighter highlighter) {
        PaneSelection selection = highlighter != null ? panes.get(highlighter) : null;
        if (selection != null) {
            highlighter.setRuleSet(resolve(selection));
        }
    }

    /** The id of the set {@code selection} resolves to, or {@code null} for none. */
    public String resolveSetId(PaneSelection selection) {
        Catalog current = catalog.get();
        if (selection == null) {
            return resolveSetId(current.enabled(), current.sets()::containsKey, current.defaultSetId());
        }
        return resolveSetId(current.enabled(), current.sets()::containsKey,
            selection.paneOverride(), selection.connectionSetId(), current.defaultSetId());
    }

    /**
     * Which level decides what {@code selection} shows: the first one that names a known set or
     * {@value #NONE_ID}, as {@link #resolveSetId(PaneSelection)} picks it; {@link Level#NONE} when no
     * level applies or the master switch is off.
     */
    public Level decidingLevel(PaneSelection selection) {
        Catalog current = catalog.get();
        int index = selection == null
            ? decidingIndex(current.enabled(), current.sets()::containsKey, null, null, current.defaultSetId())
            : decidingIndex(current.enabled(), current.sets()::containsKey,
                selection.paneOverride(), selection.connectionSetId(), current.defaultSetId());
        return switch (index) {
            case 0 -> Level.PANE;
            case 1 -> Level.CONNECTION;
            case 2 -> Level.DEFAULT;
            default -> Level.NONE;
        };
    }

    /** The compiled set {@code selection} resolves to, {@link CompiledHighlightSet#NONE} for none. */
    public CompiledHighlightSet resolve(PaneSelection selection) {
        String id = resolveSetId(selection);
        CompiledHighlightSet set = id != null ? catalog.get().sets().get(id) : null;
        return set != null ? set : CompiledHighlightSet.NONE;
    }

    /** True when {@code id} names a set the panes can show right now (built-in or user). */
    public boolean isKnownSet(String id) {
        return id != null && catalog.get().sets().containsKey(id.trim());
    }

    /** The master switch: false means no pane shows any set, whatever is chosen. */
    public boolean isEnabled() {
        return catalog.get().enabled();
    }

    /**
     * The stored name of a user set, or {@code null} for a built-in (whose name is translated from
     * {@link HighlightBuiltinSets#nameKey}), an unnamed set or an unknown id.
     */
    public String userSetName(String id) {
        return id != null ? catalog.get().names().get(id.trim()) : null;
    }

    /** The ids of every set the panes can show: built-ins first, then user sets in their stored order. */
    public List<String> setIds() {
        return List.copyOf(catalog.get().sets().keySet());
    }

    /**
     * Decides which set applies: the first non-blank candidate that is {@value #NONE_ID} (off) or a
     * known id; unknown ids are skipped. Pure.
     *
     * @param enabled the master switch; false always means none
     * @param known which ids name a set
     * @param candidates the levels, most specific first; {@code null} entries mean "inherit"
     * @return the chosen id, or {@code null} for none
     */
    public static String resolveSetId(boolean enabled, Predicate<String> known, String... candidates) {
        int index = decidingIndex(enabled, known, candidates);
        if (index < 0) {
            return null;
        }
        String id = candidates[index].trim();
        return NONE_ID.equals(id) ? null : id;
    }

    /**
     * The index of the candidate that decides: the first non-blank one that is {@value #NONE_ID} or a
     * known id; {@code -1} when none does or {@code enabled} is false. Pure.
     */
    static int decidingIndex(boolean enabled, Predicate<String> known, String... candidates) {
        if (!enabled || candidates == null) {
            return -1;
        }
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i];
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String id = candidate.trim();
            if (NONE_ID.equals(id) || known.test(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The connection level of a pane: the rule set stored on the saved connection with the pane's
     * connection id, so a choice changed in the Connection Manager reaches panes opened from a copy of it
     * (Quick Connect, a teamwork default login); a connection that is not saved here (a teamwork
     * connection, an unsaved Quick Connect session) uses its own. Pure.
     *
     * @param paneConnection the connection the pane's session was opened for, or {@code null}
     * @param savedById looks a saved connection up by id; may be {@code null} or return {@code null}
     * @return a set id, {@value #NONE_ID}, or {@code null} to inherit the global default
     */
    public static String connectionSetId(ServerConnection paneConnection,
                                         Function<String, ServerConnection> savedById) {
        if (paneConnection == null) {
            return null;
        }
        ServerConnection saved = paneConnection.getId() != null && savedById != null
            ? savedById.apply(paneConnection.getId())
            : null;
        return (saved != null ? saved : paneConnection).getHighlightRuleSetId();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Closes every highlighter and stops the thread. Idempotent. */
    public void stop() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TerminalOutputHighlighter highlighter : Set.copyOf(panes.keySet())) {
            highlighter.close();
        }
        panes.clear();
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Moves every pane to the set it resolves to now, after something outside the settings changed what
     * a pane inherits — a connection's rule set saved in the Connection Manager. Panes whose set did not
     * change keep their highlights. FX thread.
     */
    public void refreshAll() {
        refreshAll(false);
    }

    /** Number of attached panes (a test probe). */
    int attachedCount() {
        return panes.size();
    }

    /**
     * Moves every pane to the set it resolves to now. When the full-screen option changed, every pane
     * is also marked dirty, so a full-screen program that is running now is highlighted without waiting
     * for its next output. Highlights already drawn inside such a program stay until it redraws them.
     */
    private void refreshAll(boolean alternateScreenChanged) {
        for (Map.Entry<TerminalOutputHighlighter, PaneSelection> pane : panes.entrySet()) {
            TerminalOutputHighlighter highlighter = pane.getKey();
            highlighter.setRuleSet(resolve(pane.getValue()));
            if (alternateScreenChanged) {
                highlighter.markDirty();
            }
        }
    }

    /**
     * Everything about a set's rules that changes how it matches, looks or acts; equal signatures compile
     * alike.
     */
    public static String signature(HighlightRuleSet set) {
        StringBuilder signature = new StringBuilder();
        for (HighlightRule rule : set.getRules()) {
            if (rule == null) {
                signature.append("null;");
                continue;
            }
            signature.append(rule.getId()).append('\u0001')
                .append(rule.isEnabled()).append('\u0001')
                .append(rule.getPattern()).append('\u0001')
                .append(rule.isRegex()).append(rule.isIgnoreCase()).append(rule.isWholeWord()).append('\u0001')
                .append(rule.getScope()).append('\u0001')
                .append(rule.getForeground()).append('\u0001')
                .append(rule.getBackground()).append('\u0001')
                .append(rule.isBold()).append(rule.isItalic()).append(rule.isUnderline()).append('\u0001')
                .append(rule.getAction()).append(rule.isNotifyWithText()).append('\u0001')
                .append(rule.getName()).append('\u0002');
        }
        return signature.toString();
    }
}
