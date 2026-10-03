package de.kortty.ui.actions;

import de.kortty.core.FuzzyMatcher;
import de.kortty.ui.actions.PaletteEntry.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the command palette lists for what the user typed. FX-free.
 *
 * <p>{@link #open()} reads every {@link PaletteSource} once; the queries that follow rank those rows
 * and never ask the sources again, so typing stays cheap and the list does not change under the
 * cursor.
 *
 * <p>A query whose first character is the {@link Kind#scopePrefix() scope prefix} of a kind that has
 * a source ({@code >} commands, {@code #} tabs, {@code @} connections, {@code $} snippets) lists that
 * kind only; for a kind without a source the character is just part of the query.
 *
 * <p>Typed text is matched fuzzily ({@link FuzzyMatcher}) against the title (weighted
 * {@value #TITLE_WEIGHT} times), the detail followed by the title, and the detail alone. For a menu
 * command the detail is its menu path, so "journal left" finds View › Live Journal › Dock Left. A
 * recent choice gets a boost that shrinks with its age. Rows that are not enabled come after all
 * enabled ones; equal scores go by kind, then by title. Tabs are no recent choices of their own: the
 * tab source lists them in the order they were last used, and equally good tab matches keep that
 * order.
 *
 * <p>With nothing typed the palette lists up to {@value #RECENT_ON_EMPTY_QUERY} recent choices, then
 * the open tabs, then the menu commands grouped by menu. Connections only show up there as recent
 * choices, and snippets never do: a snippet runs a command, so it is only listed once asked for. With
 * a scope prefix and nothing else, every row of that kind is listed, recent ones first.
 *
 * <p>Neither the query nor anything derived from it is logged.
 */
public final class CommandPaletteModel {

    /** The most rows a query returns. */
    public static final int DEFAULT_LIMIT = 60;
    /** The most recent choices listed while nothing is typed. */
    public static final int RECENT_ON_EMPTY_QUERY = 8;
    /** The weight of a title match against a match in the detail. */
    static final int TITLE_WEIGHT = 3;
    /** The boost of a recent choice per place it is ahead of the end of the recent list. */
    static final int RECENT_BOOST_STEP = 4;

    private static final Logger logger = LoggerFactory.getLogger(CommandPaletteModel.class);

    private final List<PaletteSource> sources;
    private final MruList<String> recent;
    private List<PaletteEntry> snapshot;

    /**
     * @param sources the row sources, in the order their rows are listed
     * @param recent  the keys of the recent choices; {@link #chosen} adds to it
     */
    public CommandPaletteModel(List<? extends PaletteSource> sources, MruList<String> recent) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.recent = Objects.requireNonNull(recent, "recent");
    }

    /** What a query asks for: one kind or all ({@code scope} is {@code null}), and the text to match. */
    public record Query(Kind scope, String terms) {
    }

    /** Reads every source once. A source that fails is left out of this opening. */
    public void open() {
        List<PaletteEntry> entries = new ArrayList<>();
        for (PaletteSource source : sources) {
            List<PaletteEntry> sourceEntries;
            try {
                sourceEntries = source.entries();
            } catch (RuntimeException e) {
                // The class only: a message could name a host or a snippet.
                logger.debug("A command palette source failed: {}", e.getClass().getName());
                continue;
            }
            if (sourceEntries == null) {
                continue;
            }
            for (PaletteEntry entry : sourceEntries) {
                if (entry != null && entry.kind() == source.kind()) {
                    entries.add(entry);
                }
            }
        }
        snapshot = List.copyOf(entries);
    }

    /** The kinds that have a source; only their scope prefixes are recognized. */
    public Set<Kind> kinds() {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (PaletteSource source : sources) {
            kinds.add(source.kind());
        }
        return kinds;
    }

    /** Splits a leading scope prefix off {@code text}, when its kind has a source. */
    public Query parse(String text) {
        String typed = text != null ? text.strip() : "";
        if (!typed.isEmpty()) {
            Kind kind = Kind.ofScopePrefix(typed.charAt(0));
            if (kind != null && kinds().contains(kind)) {
                return new Query(kind, typed.substring(1).strip());
            }
        }
        return new Query(null, typed);
    }

    /** The rows for {@code text}, at most {@link #DEFAULT_LIMIT}. */
    public List<PaletteEntry> query(String text) {
        return query(text, DEFAULT_LIMIT);
    }

    /** The rows for {@code text}, best first, at most {@code limit} (0 or less: no limit). */
    public List<PaletteEntry> query(String text, int limit) {
        if (snapshot == null) {
            open();
        }
        Query query = parse(text);
        List<PaletteEntry> candidates = query.scope() == null
            ? snapshot
            : snapshot.stream().filter(entry -> entry.kind() == query.scope()).toList();
        List<PaletteEntry> rows = query.terms().isEmpty()
            ? browse(candidates, query.scope())
            : rank(candidates, query.terms());
        return limit > 0 && rows.size() > limit ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    }

    /**
     * Remembers {@code entry} as the most recent choice. A tab is not remembered here: selecting it
     * already makes it its window's most recently used tab, and listing it again among the recent
     * choices would put the tab the user is in at the top.
     */
    public void chosen(PaletteEntry entry) {
        if (entry != null && entry.kind() != Kind.TAB) {
            recent.add(entry.key());
        }
    }

    private List<PaletteEntry> rank(List<PaletteEntry> candidates, String terms) {
        record Scored(PaletteEntry entry, int score, int index) {
        }
        List<Scored> matches = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            PaletteEntry entry = candidates.get(i);
            int score = FuzzyMatcher.bestScore(terms, fields(entry));
            if (score != FuzzyMatcher.NO_MATCH) {
                matches.add(new Scored(entry, score + recentBoost(entry.key()), i));
            }
        }
        matches.sort(Comparator.comparing((Scored scored) -> !scored.entry().enabled())
            .thenComparing(Comparator.comparingInt(Scored::score).reversed())
            .thenComparing(scored -> scored.entry().kind())
            // Tabs come in the order they were last used; the other kinds go by title.
            .thenComparingInt(scored -> scored.entry().kind() == Kind.TAB ? scored.index() : 0)
            .thenComparing(scored -> scored.entry().title(), String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(Scored::index));
        return matches.stream().map(Scored::entry).toList();
    }

    private static List<FuzzyMatcher.Field> fields(PaletteEntry entry) {
        String detail = entry.detail();
        String pathAndTitle = detail.isEmpty() ? entry.title() : detail + " " + entry.title();
        return List.of(
            new FuzzyMatcher.Field(entry.title(), TITLE_WEIGHT),
            new FuzzyMatcher.Field(pathAndTitle, 1),
            new FuzzyMatcher.Field(detail, 1));
    }

    private int recentBoost(String key) {
        int index = recent.indexOf(key);
        return index < 0 ? 0 : Math.max(0, recent.capacity() - index) * RECENT_BOOST_STEP;
    }

    private List<PaletteEntry> browse(List<PaletteEntry> candidates, Kind scope) {
        Map<String, PaletteEntry> byKey = new HashMap<>();
        for (PaletteEntry entry : candidates) {
            byKey.putIfAbsent(entry.key(), entry);
        }
        List<PaletteEntry> rows = new ArrayList<>();
        Set<String> listed = new HashSet<>();

        int recentLimit = scope == null ? RECENT_ON_EMPTY_QUERY : Integer.MAX_VALUE;
        for (String key : recent.items()) {
            if (listed.size() >= recentLimit) {
                break;
            }
            PaletteEntry entry = byKey.get(key);
            if (entry == null || (scope == null && entry.kind() == Kind.SNIPPET)) {
                continue;
            }
            if (listed.add(key)) {
                rows.add(entry);
            }
        }

        if (scope == null) {
            addInOrder(rows, listed, candidates, Kind.TAB);
            addByCategory(rows, listed, candidates);
        } else if (scope == Kind.ACTION) {
            addByCategory(rows, listed, candidates);
        } else {
            addInOrder(rows, listed, candidates, scope);
        }
        return rows;
    }

    private static void addInOrder(List<PaletteEntry> rows, Set<String> listed, List<PaletteEntry> candidates,
                                   Kind kind) {
        for (PaletteEntry entry : candidates) {
            if (entry.kind() == kind && listed.add(entry.key())) {
                rows.add(entry);
            }
        }
    }

    /** The commands, each menu's together, the menus in the order they first appear. */
    private static void addByCategory(List<PaletteEntry> rows, Set<String> listed, List<PaletteEntry> candidates) {
        Map<String, List<PaletteEntry>> byCategory = new LinkedHashMap<>();
        for (PaletteEntry entry : candidates) {
            if (entry.kind() == Kind.ACTION) {
                byCategory.computeIfAbsent(entry.detail(), category -> new ArrayList<>()).add(entry);
            }
        }
        for (List<PaletteEntry> group : byCategory.values()) {
            for (PaletteEntry entry : group) {
                if (listed.add(entry.key())) {
                    rows.add(entry);
                }
            }
        }
    }
}
