package de.kortty.ui;

import com.sithtermfx.ui.split.PaneLayout;
import de.kortty.model.SplitPaneState;
import javafx.geometry.Orientation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

/**
 * How a saved split layout ({@link SplitPaneState}) is rebuilt around a tab's first pane, one split
 * at a time, and how a live layout is saved.
 *
 * <p>The saved panes are numbered from left to right and top to bottom, the order
 * {@code TerminalSplitPane.getAllWidgets()} lists them in; pane 0 is the tab's first pane, which is
 * already open. A split keeps the pane it splits first (left or top) and puts the new pane second,
 * so a branch is rebuilt by splitting its first pane and the new pane becomes the first pane of the
 * branch's second child. Each {@link SplitStep} names the pane to split, the orientation and the
 * number the new pane gets. The steps come in an order in which every pane they split exists:
 * a branch before the branches inside its first child, which split the same pane again, so the new
 * pane of the outer split always lands beside the whole inner group.
 *
 * <p>A step whose pane does not open (no sign-in, the policy, a failed connect) leaves out its whole
 * second child: {@link #realized} is the layout of the panes that did open, the one whose dividers
 * are applied, and {@link #summarize} says how many panes stayed out and why.
 *
 * <p>A layout read from a file is untrusted: an orientation that names none makes the node a single
 * pane, a divider outside 0 to 1 is clamped, and at most {@value #MAX_PANES} panes and
 * {@value #MAX_DEPTH} levels are rebuilt, so a hand-made project cannot open hundreds of sessions.
 *
 * <p>Free of JavaFX scene classes and of the application, so it is unit-tested on its own.
 */
final class SplitLayoutRestorePlan {

    /** The most panes one tab's layout is rebuilt with. */
    static final int MAX_PANES = 32;

    /** The deepest nesting of splits that is rebuilt; deeper branches become a single pane. */
    static final int MAX_DEPTH = 32;

    /**
     * One split: split pane {@code sourceLeafId} with {@code orientation}; the new pane is pane
     * {@code newLeafId} and runs {@code connectionId}, or the tab's connection when that is null.
     */
    record SplitStep(int sourceLeafId, Orientation orientation, int newLeafId, @Nullable String connectionId) {

        SplitStep {
            Objects.requireNonNull(orientation, "orientation");
        }
    }

    /** Why a pane of the saved layout was not reopened. */
    enum SkipReason {
        /** The connection needs a password or a new temporary SSH key, which a restore never asks for. */
        SIGN_IN("project.splitRestore.reason.signIn"),
        /** The stored password is in the master-password vault, which is locked. */
        VAULT_LOCKED("project.splitRestore.reason.vaultLocked"),
        /** The enterprise server policy blocks the server or its jump host. */
        BLOCKED("project.splitRestore.reason.blocked"),
        /** The pane's saved connection no longer exists. */
        MISSING("project.splitRestore.reason.missing"),
        /** The connection was tried and did not open. */
        FAILED("project.splitRestore.reason.failed");

        private final String i18nKey;

        SkipReason(String i18nKey) {
            this.i18nKey = i18nKey;
        }

        String i18nKey() {
            return i18nKey;
        }
    }

    /**
     * What a restore achieved.
     *
     * @param plannedPanes  the panes of the saved layout (after the limits)
     * @param restoredPanes the panes the tab has now
     * @param reasons       why panes stayed out, each reason once, in the order of {@link SkipReason}
     */
    record Summary(int plannedPanes, int restoredPanes, List<SkipReason> reasons) {

        Summary {
            reasons = List.copyOf(reasons);
        }

        /** The panes of the saved layout that were not reopened. */
        int missingPanes() {
            return Math.max(0, plannedPanes - restoredPanes);
        }

        /** The reasons as one text, each translated by {@code translate} from its key. */
        String reasonsText(Function<String, String> translate) {
            return reasons.stream().map(reason -> translate.apply(reason.i18nKey())).collect(Collectors.joining(", "));
        }
    }

    private final PaneLayout<Integer> layout;
    private final List<SplitStep> steps;
    private final List<String> connectionIds;
    private final List<String> directories;
    private final List<String> scrollbackRefs;
    private final boolean truncated;

    private SplitLayoutRestorePlan(PaneLayout<Integer> layout, List<SplitStep> steps, List<String> connectionIds,
                                   List<String> directories, List<String> scrollbackRefs, boolean truncated) {
        this.layout = layout;
        this.steps = List.copyOf(steps);
        this.connectionIds = Collections.unmodifiableList(new ArrayList<>(connectionIds));
        this.directories = Collections.unmodifiableList(new ArrayList<>(directories));
        this.scrollbackRefs = Collections.unmodifiableList(new ArrayList<>(scrollbackRefs));
        this.truncated = truncated;
    }

    /** The plan for {@code state}; a missing or unsplit layout is a single pane and needs no step. */
    static SplitLayoutRestorePlan plan(@Nullable SplitPaneState state) {
        Builder builder = new Builder();
        PaneLayout<Integer> layout = builder.build(state, 0);
        return new SplitLayoutRestorePlan(layout, builder.steps, builder.connectionIds, builder.directories,
            builder.scrollbackRefs, builder.truncated);
    }

    /** The saved layout, with the pane numbers as panes. */
    PaneLayout<Integer> layout() {
        return layout;
    }

    /** The splits, in the order to make them. */
    List<SplitStep> steps() {
        return steps;
    }

    /** The number of panes of the saved layout. */
    int paneCount() {
        return connectionIds.size();
    }

    /** Whether the saved layout had more panes or levels than are rebuilt. */
    boolean truncated() {
        return truncated;
    }

    /** The saved connection pane {@code leafId} runs, or null for the tab's connection. */
    @Nullable String connectionIdOf(int leafId) {
        return connectionIds.get(leafId);
    }

    /**
     * The working directory the session snapshot saved for pane {@code leafId}'s local shell, or null.
     * It is only a wish: the pane uses it when it runs a local shell and the directory still exists
     * ({@link de.kortty.core.SessionWorkingDirectory#startDirectory}); a project file never has one.
     */
    @Nullable String directoryOf(int leafId) {
        return directories.get(leafId);
    }

    /**
     * The file of pane {@code leafId}'s saved output the session snapshot names, or null. Only a plain
     * name is passed on ({@link de.kortty.core.ProjectLeafFieldSanitizer#isValidScrollbackRef}); a
     * project file never has one.
     */
    @Nullable String scrollbackRefOf(int leafId) {
        return scrollbackRefs.get(leafId);
    }

    /**
     * The layout of the panes that opened: a branch whose new pane ({@code created} false for its
     * number) did not open is replaced by its first child. Pane 0 is always there.
     */
    PaneLayout<Integer> realized(IntPredicate created) {
        return realize(layout, created);
    }

    /**
     * What the restore achieved.
     *
     * @param created whether a pane opened, by its number
     * @param skipped why the new pane of a failed step stayed out, by the new pane's number
     */
    Summary summarize(IntPredicate created, Map<Integer, SkipReason> skipped) {
        EnumSet<SkipReason> reasons = EnumSet.noneOf(SkipReason.class);
        for (SplitStep step : steps) {
            if (!created.test(step.newLeafId())) {
                SkipReason reason = skipped.get(step.newLeafId());
                if (reason != null) {
                    reasons.add(reason);
                }
            }
        }
        return new Summary(paneCount(), realized(created).paneCount(), new ArrayList<>(reasons));
    }

    /** Why a pane on another connection stays out, from how far its sign-in got without asking. */
    static SkipReason reasonFor(ConnectionAuthResolver.Status status) {
        return switch (status) {
            case NEEDS_PASSWORD, NEEDS_TEMP_KEY -> SkipReason.SIGN_IN;
            case NEEDS_UNLOCK -> SkipReason.VAULT_LOCKED;
            case BLOCKED -> SkipReason.BLOCKED;
            case MISSING -> SkipReason.MISSING;
            // Ready to sign in, so the connection itself did not open.
            case READY -> SkipReason.FAILED;
        };
    }

    /**
     * The saved form of a live layout: the panes numbered from left to right and top to bottom, each
     * with the saved connection it runs when that is not the tab's.
     *
     * @param connectionIdOf the saved connection of a pane, or null for the tab's
     * @return the saved layout, or null for no layout
     */
    static <W> @Nullable SplitPaneState capture(@Nullable PaneLayout<W> layout,
                                                Function<? super W, String> connectionIdOf) {
        return capture(layout, connectionIdOf, pane -> null, pane -> null);
    }

    /**
     * {@link #capture(PaneLayout, Function)} for the session snapshot: each pane also names the working
     * directory of its local shell, a session-only field a project file never carries.
     *
     * @param directoryOf the working directory of a pane's local shell, or null (a remote pane)
     */
    static <W> @Nullable SplitPaneState capture(@Nullable PaneLayout<W> layout,
                                                Function<? super W, String> connectionIdOf,
                                                Function<? super W, String> directoryOf) {
        return capture(layout, connectionIdOf, directoryOf, pane -> null);
    }

    /**
     * {@link #capture(PaneLayout, Function, Function)} with each pane's saved-output file as well
     * (Settings › Window › Session Restore), another session-only field.
     *
     * @param scrollbackRefOf the file name of a pane's saved output, or null for none
     */
    static <W> @Nullable SplitPaneState capture(@Nullable PaneLayout<W> layout,
                                                Function<? super W, String> connectionIdOf,
                                                Function<? super W, String> directoryOf,
                                                Function<? super W, String> scrollbackRefOf) {
        if (layout == null) {
            return null;
        }
        Objects.requireNonNull(connectionIdOf, "connectionIdOf");
        Objects.requireNonNull(directoryOf, "directoryOf");
        Objects.requireNonNull(scrollbackRefOf, "scrollbackRefOf");
        return captureNode(layout, connectionIdOf, directoryOf, scrollbackRefOf, new int[] {0});
    }

    private static <W> SplitPaneState captureNode(PaneLayout<W> node, Function<? super W, String> connectionIdOf,
                                                  Function<? super W, String> directoryOf,
                                                  Function<? super W, String> scrollbackRefOf, int[] nextIndex) {
        if (node.isLeaf()) {
            SplitPaneState leaf = SplitPaneState.createLeaf(nextIndex[0]++,
                normalizedId(connectionIdOf.apply(node.pane())));
            leaf.setCurrentDirectory(de.kortty.core.SessionWorkingDirectory.forSnapshot(directoryOf.apply(node.pane())));
            leaf.setScrollbackRef(validRef(scrollbackRefOf.apply(node.pane())));
            return leaf;
        }
        SplitPaneState first = captureNode(node.first(), connectionIdOf, directoryOf, scrollbackRefOf, nextIndex);
        SplitPaneState second = captureNode(node.second(), connectionIdOf, directoryOf, scrollbackRefOf, nextIndex);
        return SplitPaneState.createSplit(node.orientation(), node.divider(), first, second);
    }

    private static PaneLayout<Integer> realize(PaneLayout<Integer> node, IntPredicate created) {
        if (node.isLeaf()) {
            return node;
        }
        PaneLayout<Integer> first = realize(node.first(), created);
        if (!created.test(firstPaneOf(node.second()))) {
            return first;
        }
        return PaneLayout.split(node.orientation(), node.divider(), first, realize(node.second(), created));
    }

    /** The first (leftmost, topmost) pane of {@code node}: the pane a split of its branch splits. */
    private static int firstPaneOf(PaneLayout<Integer> node) {
        PaneLayout<Integer> current = node;
        while (!current.isLeaf()) {
            current = current.first();
        }
        return current.pane();
    }

    private static @Nullable String validRef(@Nullable String ref) {
        return de.kortty.core.ProjectLeafFieldSanitizer.isValidScrollbackRef(ref) ? ref : null;
    }

    private static @Nullable String normalizedId(@Nullable String connectionId) {
        return connectionId == null || connectionId.isBlank() ? null : connectionId;
    }

    private static double sanitizedDivider(@Nullable Double divider) {
        if (divider == null || !Double.isFinite(divider)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, divider));
    }

    private static final class Builder {

        private final List<SplitStep> steps = new ArrayList<>();
        private final List<String> connectionIds = new ArrayList<>();
        private final List<String> directories = new ArrayList<>();
        private final List<String> scrollbackRefs = new ArrayList<>();
        private boolean truncated;

        PaneLayout<Integer> build(@Nullable SplitPaneState node, int depth) {
            Orientation orientation = node != null && node.isSplit() ? node.getOrientationEnum() : null;
            if (orientation == null) {
                return leaf(node);
            }
            if (depth >= MAX_DEPTH) {
                truncated = true;
                return leaf(firstLeafOf(node));
            }
            // This branch's split comes before the splits inside its first child: they split the
            // same pane again, and must do so inside the place this split gives it.
            int slot = steps.size();
            steps.add(null);
            PaneLayout<Integer> first = build(node.getLeftChild(), depth + 1);
            if (connectionIds.size() >= MAX_PANES) {
                truncated = true;
                // Only steps of the first child follow the slot, and they are complete already.
                steps.remove(slot);
                return first;
            }
            int newPane = connectionIds.size();
            PaneLayout<Integer> second = build(node.getRightChild(), depth + 1);
            steps.set(slot, new SplitStep(firstPaneOf(first), orientation, newPane, connectionIds.get(newPane)));
            return PaneLayout.split(orientation, sanitizedDivider(node.getDividerPosition()), first, second);
        }

        private PaneLayout<Integer> leaf(@Nullable SplitPaneState node) {
            int id = connectionIds.size();
            connectionIds.add(normalizedId(node != null ? node.getConnectionId() : null));
            directories.add(node != null
                ? de.kortty.core.SessionWorkingDirectory.forSnapshot(node.getCurrentDirectory())
                : null);
            scrollbackRefs.add(node != null ? validRef(node.getScrollbackRef()) : null);
            return PaneLayout.leaf(id);
        }

        /** The first leaf below {@code node}, without recursion: what a branch too deep is reduced to. */
        private static @Nullable SplitPaneState firstLeafOf(SplitPaneState node) {
            SplitPaneState current = node;
            while (current != null && current.isSplit()) {
                current = current.getLeftChild();
            }
            return current;
        }
    }
}
