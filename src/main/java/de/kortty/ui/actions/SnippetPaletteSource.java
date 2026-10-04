package de.kortty.ui.actions;

import de.kortty.model.Snippet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The command palette's rows for snippets ({@code $}): every snippet of the library, the most
 * recently used first and the others by name. Choosing a row runs the snippet in a terminal exactly
 * like Send to Terminal in the Snippet Manager: the variable prompt, the one-liner, the command
 * executed with Enter. Alt+Enter (Option+Enter on macOS) opens the snippet in the Snippet Manager
 * instead, which runs nothing. FX-free: the window supplies the snippets, the terminal and the texts.
 *
 * <p>Every row names its terminal, because a snippet executes there. That terminal is the tab Send
 * to Terminal picks (the selected terminal tab, or the terminal tab used last), and within it the
 * pane that has, or last had, the keyboard focus, else the tab's first pane: when the tab is split,
 * the row names the pane by its number. The terminal is named by the {@code user@host} of that
 * pane's connection, then its tab title ({@link #targetName}), since a program in the terminal can
 * set the title but not the connection. It is read once per palette opening, and the row runs the
 * snippet in exactly the pane it names. Without a terminal tab the rows are listed but cannot run, and say why;
 * Alt+Enter still opens them.
 *
 * <p>A row is found by the snippet's name, and besides by its folder, category and tags, never by
 * the terminal it names: that text is the same for every row, and matching it would list every
 * snippet for the name of a tab. The palette lists snippets only when something is typed or the
 * query starts with {@code $}. Each row is keyed {@code snippet:<id>}; snippets without an id are
 * left out.
 */
public final class SnippetPaletteSource implements PaletteSource {

    /** Prefix of a snippet row's key. */
    public static final String KEY_PREFIX = "snippet:";

    /** The most recently used first, the snippets never used by name. */
    private static final Comparator<Snippet> BY_LAST_USE =
        Comparator.comparingLong(Snippet::getLastUsed).reversed()
            .thenComparing(SnippetPaletteSource::name, String.CASE_INSENSITIVE_ORDER);

    /**
     * Where the snippets come from.
     *
     * @param snippets   every snippet of the library
     * @param folderPath the path of a snippet's folder, such as {@code deploy/scripts}, or
     *                   {@code ""} at the top level
     */
    public record Library(Supplier<List<Snippet>> snippets, Function<Snippet, String> folderPath) {
        public Library {
            Objects.requireNonNull(snippets, "snippets");
            Objects.requireNonNull(folderPath, "folderPath");
        }
    }

    /**
     * The terminal pane a snippet would run in.
     *
     * @param name how the rows name it, see {@link #targetName}
     * @param pane the pane's number (1-based) when its tab is split, so the rows say which one runs it;
     *             0 for a tab with one pane
     * @param send runs a snippet in that pane, as Send to Terminal does
     */
    public record Target(String name, int pane, Consumer<Snippet> send) {
        public Target {
            Objects.requireNonNull(send, "send");
        }

        /** Whether the pane's tab is split, so the rows name the pane. */
        public boolean split() {
            return pane > 0;
        }
    }

    /** The texts of the rows, in the UI language. */
    public interface Texts {
        /** The detail of a row whose terminal tab has one pane. */
        String runIn(String target);

        /** The detail of a row whose terminal tab is split: the snippet runs in pane {@code pane} of it. */
        String runInPane(String target, int pane);

        /** The detail of a row while there is no terminal tab. */
        String noTerminal();

        /** Why a row cannot run while there is no terminal tab, and what Alt+Enter does instead. */
        String noTerminalReason();

        /** The title of a snippet without a name. */
        String unnamed();
    }

    private final Library library;
    private final Supplier<Target> target;
    private final Texts texts;
    private final Consumer<Snippet> open;

    /**
     * @param target the terminal the snippets would run in now, or {@code null} supplied when there
     *               is none
     * @param open   opens a snippet in the Snippet Manager
     */
    public SnippetPaletteSource(Library library, Supplier<Target> target, Texts texts, Consumer<Snippet> open) {
        this.library = Objects.requireNonNull(library, "library");
        this.target = Objects.requireNonNull(target, "target");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.open = Objects.requireNonNull(open, "open");
    }

    @Override
    public PaletteEntry.Kind kind() {
        return PaletteEntry.Kind.SNIPPET;
    }

    @Override
    public List<PaletteEntry> entries() {
        List<Snippet> snippets = library.snippets().get();
        if (snippets == null || snippets.isEmpty()) {
            return List.of();
        }
        Target terminal = target.get();
        String detail = terminal == null ? texts.noTerminal()
            : terminal.split() ? texts.runInPane(terminal.name(), terminal.pane()) : texts.runIn(terminal.name());
        String reason = terminal == null ? texts.noTerminalReason() : "";

        List<Snippet> listed = new ArrayList<>();
        for (Snippet snippet : snippets) {
            if (snippet != null && snippet.getId() != null && !snippet.getId().isBlank()) {
                listed.add(snippet);
            }
        }
        listed.sort(BY_LAST_USE);

        List<PaletteEntry> entries = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Snippet snippet : listed) {
            String key = KEY_PREFIX + snippet.getId();
            if (!keys.add(key)) {
                continue;
            }
            String title = name(snippet);
            entries.add(new PaletteEntry(PaletteEntry.Kind.SNIPPET, key, title.isEmpty() ? texts.unnamed() : title,
                detail, "", terminal != null, reason, false,
                () -> {
                    if (terminal != null) {
                        terminal.send().accept(snippet);
                    }
                },
                searchDetail(snippet),
                () -> open.accept(snippet)));
        }
        return entries;
    }

    /**
     * How a row names the terminal: the connection's {@code user@host} (or {@code fallbackName},
     * such as a local shell's name, without a host), followed by the tab's {@code title} in brackets
     * unless the title already is exactly that. The connection comes first because a program in the
     * terminal can set the title, as long as the row allows: however a long row is cut, by the
     * palette's length limit or with an ellipsis on screen, the part cut off is the title, never
     * where the snippet runs.
     */
    public static String targetName(String title, String username, String host, String fallbackName) {
        String identity = TabPaletteSource.connectionDetail(title, username, host, fallbackName, null);
        String name = title != null ? title.strip() : "";
        if (identity.isEmpty()) {
            return name;
        }
        return name.isEmpty() ? identity : identity + " (" + name + ")";
    }

    /** What a row is found by besides its name: the folder, the category and the tags. */
    private String searchDetail(Snippet snippet) {
        List<String> parts = new ArrayList<>();
        String folder = snippet.getFolderId() != null ? library.folderPath().apply(snippet) : null;
        if (folder != null) {
            parts.add(folder);
        }
        parts.add(snippet.getCategory());
        if (snippet.getTags() != null) {
            parts.addAll(snippet.getTags());
        }
        return String.join(" ", parts.stream().filter(part -> part != null && !part.isBlank())
            .map(String::strip).toList());
    }

    private static String name(Snippet snippet) {
        String name = snippet.getName();
        return name != null ? name.strip() : "";
    }
}
