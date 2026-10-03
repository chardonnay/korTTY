package de.kortty.core.highlight;

import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The style a highlighted terminal cell is drawn with: the program's own style (the <em>base</em>)
 * with one rule's colors and emphasis laid over it.
 *
 * <p>The highlighter stores ("bakes") this style into the cells it matched, the way SithTermFX
 * stores hyperlinks. Because the style is stored in the cell, it moves with the line into the
 * scrollback and through a width reflow. It remembers its base, so the highlighter can restore the
 * program's style exactly when a rule stops matching or the rule set is switched off, and it
 * remembers the rule and the {@linkplain #generation() generation} it was derived for, so a pass
 * can tell a cell that is already right from one that needs rewriting without comparing styles.
 *
 * <p>Deriving keeps what the program asked for unless the rule overrides it:
 * <ul>
 *   <li>The rule's foreground and background replace the base's only where the rule sets one;
 *       {@code null} keeps the program's color.</li>
 *   <li>Bold, italic and underline are added, never removed.</li>
 *   <li>{@link Option#HIDDEN} (concealed text, SGR 8) is always kept, so a rule can never reveal
 *       what a program hid — not even a whole-line background rule. {@link Option#DIM} and the
 *       blink options are kept as well.</li>
 *   <li>{@link Option#INVERSE} is cleared only when the rule sets a color: the renderer swaps
 *       foreground and background of an inverse cell, so the rule's text color would otherwise
 *       paint the cell's background. A matched character in a reverse-video bar is therefore drawn
 *       in normal video with the rule's colors.</li>
 * </ul>
 *
 * <p>Equality is by value — the visible style plus base, rule and generation — like every
 * {@link TextStyle}. Immutable and safe to share between threads.
 */
public final class HighlightTextStyle extends TextStyle {

    private final TextStyle base;

    private final int ruleIndex;

    private final int generation;

    private HighlightTextStyle(TerminalColor foreground, TerminalColor background, EnumSet<Option> options,
                               TextStyle base, int ruleIndex, int generation) {
        super(foreground, background, options);
        this.base = base;
        this.ruleIndex = ruleIndex;
        this.generation = generation;
    }

    /**
     * Lays {@code rule} over {@code base}. A base that is itself a highlight is unwrapped first, so a
     * derived style always points at the program's own style.
     *
     * @param generation the highlighter generation the rule belongs to (see {@link #isFor(int, int)})
     */
    public static HighlightTextStyle derive(TextStyle base, CompiledHighlightSet.Rule rule, int generation) {
        Objects.requireNonNull(rule, "rule");
        TextStyle plain = baseOf(Objects.requireNonNull(base, "base"));
        EnumSet<Option> options = EnumSet.noneOf(Option.class);
        for (Option option : Option.values()) {
            if (plain.hasOption(option)) {
                options.add(option);
            }
        }
        if (rule.bold()) {
            options.add(Option.BOLD);
        }
        if (rule.italic()) {
            options.add(Option.ITALIC);
        }
        if (rule.underline()) {
            options.add(Option.UNDERLINED);
        }
        boolean recolors = rule.foreground() != null || rule.background() != null;
        if (recolors) {
            options.remove(Option.INVERSE);
        }
        TerminalColor foreground = rule.foreground() != null ? rule.foreground() : plain.getForeground();
        TerminalColor background = rule.background() != null ? rule.background() : plain.getBackground();
        return new HighlightTextStyle(foreground, background, options, plain, rule.index(), generation);
    }

    /** The program's own style behind {@code style}: its base for a highlight, otherwise the style itself. */
    public static TextStyle baseOf(TextStyle style) {
        return style instanceof HighlightTextStyle highlight ? highlight.base : style;
    }

    /** The program's own style this highlight was laid over. */
    public TextStyle base() {
        return base;
    }

    /** Index of the rule in its {@link CompiledHighlightSet}. */
    public int ruleIndex() {
        return ruleIndex;
    }

    /** The highlighter generation (one per rule-set switch) this style was derived for. */
    public int generation() {
        return generation;
    }

    /** True when this is the highlight of rule {@code ruleIndex} in {@code generation}, so the cell needs no rewrite. */
    public boolean isFor(int ruleIndex, int generation) {
        return this.ruleIndex == ruleIndex && this.generation == generation;
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        HighlightTextStyle other = (HighlightTextStyle) o;
        return ruleIndex == other.ruleIndex && generation == other.generation && base.equals(other.base);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), base, ruleIndex, generation);
    }

    /**
     * Derived styles keyed by value, so a long session does not grow without bound. The emulator
     * creates a new {@link TextStyle} object for every attribute change, and every cell that needs
     * the same look must still get the same derived object, or the line would split into one entry
     * per cell. The key is the base's value (which includes its class) and the rule index; the map
     * is cleared when the generation changes and evicts its least recently used entry beyond
     * {@link #MAX_ENTRIES}.
     *
     * <p>Not thread-safe: one cache belongs to one highlighter, whose passes never overlap.
     */
    public static final class Cache {

        /** Most derived styles kept at once. */
        public static final int MAX_ENTRIES = 4_096;

        private record Key(TextStyle base, int ruleIndex) {
        }

        private final Map<Key, HighlightTextStyle> styles = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, HighlightTextStyle> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        private int generation = Integer.MIN_VALUE;

        /** The derived style of {@code rule} over {@code base} in {@code generation}, shared where possible. */
        public HighlightTextStyle derive(TextStyle base, CompiledHighlightSet.Rule rule, int generation) {
            if (generation != this.generation) {
                styles.clear();
                this.generation = generation;
            }
            TextStyle plain = baseOf(base);
            Key key = new Key(plain, rule.index());
            HighlightTextStyle derived = styles.get(key);
            if (derived == null) {
                derived = HighlightTextStyle.derive(plain, rule, generation);
                styles.put(key, derived);
            }
            return derived;
        }

        public int size() {
            return styles.size();
        }

        public void clear() {
            styles.clear();
        }
    }
}
