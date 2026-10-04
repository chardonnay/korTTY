package de.kortty.ui;

import com.sithtermfx.ui.split.PaneLayout;
import de.kortty.model.SplitPaneState;
import javafx.geometry.Orientation;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static javafx.geometry.Orientation.HORIZONTAL;
import static javafx.geometry.Orientation.VERTICAL;

/**
 * How a saved split layout is rebuilt one split at a time around a tab's first pane. Each plan is
 * replayed on a model of TerminalSplitPane's split, which keeps the split pane first and puts the new
 * pane second, and must give back the saved tree. The old restore took "the last pane in the list"
 * as each new pane, which was already wrong for three panes whose left half is split, because the
 * list is in tree order and a new pane follows its source rather than going to the end.
 */
class SplitLayoutRestorePlanTest {

    private static final IntPredicate ALL_OPEN = pane -> true;

    @Test
    void aSinglePaneNeedsNoSplit() {
        for (SplitPaneState state : new SplitPaneState[] {null, leaf(0), new SplitPaneState()}) {
            SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(state);
            assertThat(plan.steps()).isEmpty();
            assertThat(plan.paneCount()).isEqualTo(1);
            assertThat(plan.layout().isLeaf()).isTrue();
            assertThat(plan.truncated()).isFalse();
        }
    }

    @Test
    void twoPanesAreOneSplitOfTheFirstPane() {
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(split(HORIZONTAL, 0.25, leaf(0), leaf(1, "server-b")));

        assertThat(plan.steps()).containsExactly(new SplitLayoutRestorePlan.SplitStep(0, HORIZONTAL, 1, "server-b"));
        assertThat(plan.layout().divider()).isEqualTo(0.25);
        assertRebuildsTheSavedTree(plan);
    }

    @Test
    void threePanesWithASplitLeftHalfComeBackInTheirPlaces() {
        // (a / b) | c: the left half is split. The outer split must come first, so c lands beside the
        // whole left group, and the inner split then divides pane a inside its place.
        SplitPaneState saved = split(HORIZONTAL, 0.6,
            split(VERTICAL, 0.3, leaf(0), leaf(1)),
            leaf(2));

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(saved);

        assertThat(plan.steps()).containsExactly(
            new SplitLayoutRestorePlan.SplitStep(0, HORIZONTAL, 2, null),
            new SplitLayoutRestorePlan.SplitStep(0, VERTICAL, 1, null)).inOrder();
        assertThat(plan.layout().panes()).containsExactly(0, 1, 2).inOrder();
        assertRebuildsTheSavedTree(plan);

        // The order the old restore assumed would have built (a | c) / b.
        PaneLayout<Integer> innerFirst = replay(List.of(
            new SplitLayoutRestorePlan.SplitStep(0, VERTICAL, 1, null),
            new SplitLayoutRestorePlan.SplitStep(0, HORIZONTAL, 2, null)), ALL_OPEN);
        assertThat(innerFirst.sameShapeAs(plan.layout())).isFalse();
    }

    @Test
    void threePanesWithASplitRightHalfSplitTheNewPane() {
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(split(HORIZONTAL, 0.5,
            leaf(0),
            split(VERTICAL, 0.5, leaf(1), leaf(2))));

        assertThat(plan.steps()).containsExactly(
            new SplitLayoutRestorePlan.SplitStep(0, HORIZONTAL, 1, null),
            new SplitLayoutRestorePlan.SplitStep(1, VERTICAL, 2, null)).inOrder();
        assertRebuildsTheSavedTree(plan);
    }

    @Test
    void deeplyNestedLayoutsComeBackWithEveryDivider() {
        // ((a / (b | c)) | ((d / e) | f)) / g
        SplitPaneState saved = split(VERTICAL, 0.8,
            split(HORIZONTAL, 0.4,
                split(VERTICAL, 0.3, leaf(0), split(HORIZONTAL, 0.7, leaf(1), leaf(2, "b"))),
                split(HORIZONTAL, 0.55, split(VERTICAL, 0.2, leaf(3, "c"), leaf(4)), leaf(5))),
            leaf(6));

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(saved);

        assertThat(plan.paneCount()).isEqualTo(7);
        assertThat(plan.steps()).hasSize(6);
        assertThat(plan.layout().panes()).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder();
        assertRebuildsTheSavedTree(plan);
        assertThat(dividers(plan.layout())).containsExactly(0.8, 0.4, 0.3, 0.7, 0.55, 0.2).inOrder();
        for (SplitLayoutRestorePlan.SplitStep step : plan.steps()) {
            assertWithMessage("a new pane is always a pane not opened before").that(step.newLeafId()).isGreaterThan(0);
        }
    }

    @Test
    void eachPaneKeepsItsOwnConnection() {
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(split(HORIZONTAL, 0.5,
            split(VERTICAL, 0.5, leaf(0), leaf(1, "server-b")),
            split(VERTICAL, 0.5, leaf(2, "server-c"), leaf(3, "  "))));

        assertThat(plan.connectionIdOf(0)).isNull();
        assertThat(plan.connectionIdOf(1)).isEqualTo("server-b");
        assertThat(plan.connectionIdOf(2)).isEqualTo("server-c");
        assertWithMessage("a blank id is the tab's connection").that(plan.connectionIdOf(3)).isNull();
        for (SplitLayoutRestorePlan.SplitStep step : plan.steps()) {
            assertThat(step.connectionId()).isEqualTo(plan.connectionIdOf(step.newLeafId()));
        }
    }

    @Test
    void aPaneThatDoesNotOpenLeavesOutThePanesSplitFromIt() {
        // a | ((b / c) | d): b does not open. c and d are both split from b's place, so they stay out
        // with it, and the tab keeps a alone.
        SplitPaneState saved = split(HORIZONTAL, 0.3,
            leaf(0),
            split(HORIZONTAL, 0.5, split(VERTICAL, 0.5, leaf(1), leaf(2)), leaf(3)));
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(saved);
        IntPredicate opens = pane -> pane != 1;

        PaneLayout<Integer> built = replay(plan.steps(), opens);
        Set<Integer> opened = new HashSet<>(built.panes());
        PaneLayout<Integer> realized = plan.realized(opened::contains);

        assertThat(built.panes()).containsExactly(0).inOrder();
        assertThat(realized.sameShapeAs(built)).isTrue();

        SplitLayoutRestorePlan.Summary summary = plan.summarize(opened::contains,
            Map.of(1, SplitLayoutRestorePlan.SkipReason.VAULT_LOCKED));
        assertThat(summary.plannedPanes()).isEqualTo(4);
        assertThat(summary.restoredPanes()).isEqualTo(1);
        assertThat(summary.missingPanes()).isEqualTo(3);
        assertThat(summary.reasons()).containsExactly(SplitLayoutRestorePlan.SkipReason.VAULT_LOCKED);
    }

    @Test
    void aFailedInnerSplitKeepsTheOuterOne() {
        SplitPaneState saved = split(HORIZONTAL, 0.6,
            split(VERTICAL, 0.3, leaf(0), leaf(1, "gone")),
            split(VERTICAL, 0.4, leaf(2), leaf(3, "blocked")));
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(saved);
        IntPredicate opens = pane -> pane != 1 && pane != 3;

        PaneLayout<Integer> built = replay(plan.steps(), opens);
        Set<Integer> opened = new HashSet<>(built.panes());
        PaneLayout<Integer> realized = plan.realized(opened::contains);

        assertThat(built.panes()).containsExactly(0, 2).inOrder();
        assertThat(realized.sameShapeAs(built)).isTrue();
        assertWithMessage("the outer divider is the saved one").that(realized.divider()).isEqualTo(0.6);

        SplitLayoutRestorePlan.Summary summary = plan.summarize(opened::contains, Map.of(
            3, SplitLayoutRestorePlan.SkipReason.BLOCKED,
            1, SplitLayoutRestorePlan.SkipReason.MISSING));
        assertThat(summary.missingPanes()).isEqualTo(2);
        assertWithMessage("each reason once, in a fixed order")
            .that(summary.reasons())
            .containsExactly(SplitLayoutRestorePlan.SkipReason.BLOCKED, SplitLayoutRestorePlan.SkipReason.MISSING)
            .inOrder();
        assertThat(summary.reasonsText(key -> key.substring(key.lastIndexOf('.') + 1)))
            .isEqualTo("blocked, missing");
    }

    @Test
    void aCompleteRestoreMissesNothing() {
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(split(HORIZONTAL, 0.5, leaf(0), leaf(1)));
        SplitLayoutRestorePlan.Summary summary = plan.summarize(ALL_OPEN, Map.of());

        assertThat(summary.missingPanes()).isEqualTo(0);
        assertThat(summary.reasons()).isEmpty();
        assertThat(summary.reasonsText(key -> key)).isEmpty();
        assertThat(plan.realized(ALL_OPEN).sameShapeAs(plan.layout())).isTrue();
    }

    @Test
    void aHandMadeFileCannotOpenMoreThanTheLimit() {
        // A chain of 40 panes, each split off the right of the one before.
        SplitPaneState chain = leaf(39);
        for (int i = 38; i >= 0; i--) {
            chain = split(HORIZONTAL, 0.5, leaf(i), chain);
        }

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(chain);

        assertThat(plan.truncated()).isTrue();
        assertThat(plan.paneCount()).isAtMost(SplitLayoutRestorePlan.MAX_PANES);
        assertThat(plan.steps()).hasSize(plan.paneCount() - 1);
        assertRebuildsTheSavedTree(plan);
    }

    @Test
    void aHandMadeFileWithManyBranchesStopsAtTheLimit() {
        SplitPaneState wide = balanced(7, new int[] {0});

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(wide);

        assertThat(plan.truncated()).isTrue();
        assertThat(plan.paneCount()).isEqualTo(SplitLayoutRestorePlan.MAX_PANES);
        assertThat(plan.steps()).hasSize(SplitLayoutRestorePlan.MAX_PANES - 1);
        assertRebuildsTheSavedTree(plan);
    }

    @Test
    void aHandMadeFileCannotNestDeeperThanTheLimit() {
        // Nested on the left a thousand times: every level is one more split of pane 0.
        SplitPaneState deep = leaf(0);
        for (int i = 0; i < 1000; i++) {
            deep = split(VERTICAL, 0.5, deep, leaf(i + 1));
        }

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(deep);

        assertThat(plan.truncated()).isTrue();
        assertThat(plan.paneCount()).isAtMost(SplitLayoutRestorePlan.MAX_PANES);
        assertRebuildsTheSavedTree(plan);
    }

    @Test
    void oddValuesFromAFileAreTamed() {
        SplitPaneState unknownOrientation = split(HORIZONTAL, 0.5, leaf(0), leaf(1));
        unknownOrientation.setOrientation("DIAGONAL");
        assertWithMessage("an orientation that names none is a single pane")
            .that(SplitLayoutRestorePlan.plan(unknownOrientation).steps()).isEmpty();

        SplitPaneState halfSplit = split(HORIZONTAL, 0.5, leaf(0), leaf(1));
        halfSplit.setRightChild(null);
        assertThat(SplitLayoutRestorePlan.plan(halfSplit).steps()).isEmpty();

        SplitPaneState noDivider = split(HORIZONTAL, 0.5, leaf(0), leaf(1));
        noDivider.setDividerPosition(null);
        assertThat(SplitLayoutRestorePlan.plan(noDivider).layout().divider()).isEqualTo(0.5);
        noDivider.setDividerPosition(Double.NaN);
        assertThat(SplitLayoutRestorePlan.plan(noDivider).layout().divider()).isEqualTo(0.5);
        noDivider.setDividerPosition(7.0);
        assertThat(SplitLayoutRestorePlan.plan(noDivider).layout().divider()).isEqualTo(1.0);
        noDivider.setDividerPosition(-1.0);
        assertThat(SplitLayoutRestorePlan.plan(noDivider).layout().divider()).isEqualTo(0.0);
    }

    @Test
    void aLiveLayoutIsSavedWithItsPanesNumberedAndItsConnections() {
        PaneLayout<String> live = PaneLayout.split(HORIZONTAL, 0.35,
            PaneLayout.split(VERTICAL, 0.65, PaneLayout.leaf("first"), PaneLayout.leaf("on-b")),
            PaneLayout.leaf("third"));
        Map<String, String> connections = Map.of("on-b", "server-b");

        SplitPaneState saved = SplitLayoutRestorePlan.capture(live, connections::get);

        assertThat(saved.isSplit()).isTrue();
        assertThat(saved.getOrientation()).isEqualTo("HORIZONTAL");
        assertThat(saved.getDividerPosition()).isEqualTo(0.35);
        assertThat(saved.getLeftChild().getLeftChild().getWidgetIndex()).isEqualTo(0);
        assertThat(saved.getLeftChild().getRightChild().getWidgetIndex()).isEqualTo(1);
        assertThat(saved.getLeftChild().getRightChild().getConnectionId()).isEqualTo("server-b");
        assertThat(saved.getLeftChild().getLeftChild().getConnectionId()).isNull();
        assertThat(saved.getRightChild().getWidgetIndex()).isEqualTo(2);
        assertThat(SplitLayoutRestorePlan.capture(null, connections::get)).isNull();

        // Saved and planned again, the layout is the same tree with the same dividers and servers.
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(saved);
        assertThat(plan.layout().sameShapeAs(live.map(List.of("first", "on-b", "third")::indexOf))).isTrue();
        assertThat(dividers(plan.layout())).containsExactly(0.35, 0.65).inOrder();
        assertThat(plan.connectionIdOf(1)).isEqualTo("server-b");
        assertRebuildsTheSavedTree(plan);
    }

    @Test
    void aProjectCaptureNamesNoDirectoryAndASessionCaptureNamesEachLocalShells() {
        PaneLayout<String> live = PaneLayout.split(HORIZONTAL, 0.5,
            PaneLayout.split(VERTICAL, 0.5, PaneLayout.leaf("local"), PaneLayout.leaf("remote")),
            PaneLayout.leaf("unsafe"));
        Map<String, String> directories = Map.of("local", "/home/me/src", "unsafe", "relative/dir");

        SplitPaneState project = SplitLayoutRestorePlan.capture(live, pane -> null);
        assertThat(project.getLeftChild().getLeftChild().getCurrentDirectory()).isNull();

        SplitPaneState session = SplitLayoutRestorePlan.capture(live, pane -> null, directories::get);
        assertThat(session.getLeftChild().getLeftChild().getCurrentDirectory()).isEqualTo("/home/me/src");
        assertWithMessage("a remote pane names no directory")
            .that(session.getLeftChild().getRightChild().getCurrentDirectory()).isNull();
        assertThat(session.getRightChild().getCurrentDirectory()).isNull();

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(session);
        assertThat(plan.directoryOf(0)).isEqualTo("/home/me/src");
        assertThat(plan.directoryOf(1)).isNull();
        assertThat(plan.directoryOf(2)).isNull();
    }

    @Test
    void onlyASessionCaptureNamesEachPanesSavedOutputAndThePlanPassesOnlyPlainNames() {
        PaneLayout<String> live = PaneLayout.split(HORIZONTAL, 0.5,
            PaneLayout.split(VERTICAL, 0.5, PaneLayout.leaf("first"), PaneLayout.leaf("quiet")),
            PaneLayout.leaf("crafted"));
        Map<String, String> refs = Map.of("first", "ref-1", "crafted", "../x");

        SplitPaneState project = SplitLayoutRestorePlan.capture(live, pane -> null);
        assertThat(project.getLeftChild().getLeftChild().getScrollbackRef()).isNull();
        SplitPaneState withoutOutput = SplitLayoutRestorePlan.capture(live, pane -> null, pane -> "/tmp");
        assertThat(withoutOutput.getLeftChild().getLeftChild().getScrollbackRef()).isNull();

        SplitPaneState session = SplitLayoutRestorePlan.capture(live, pane -> null, pane -> null, refs::get);
        assertThat(session.getLeftChild().getLeftChild().getScrollbackRef()).isEqualTo("ref-1");
        assertWithMessage("a pane without output names no file")
            .that(session.getLeftChild().getRightChild().getScrollbackRef()).isNull();
        assertThat(session.getRightChild().getScrollbackRef()).isNull();

        session.getRightChild().setScrollbackRef("../../.ssh/id_ed25519");
        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(session);
        assertThat(plan.scrollbackRefOf(0)).isEqualTo("ref-1");
        assertThat(plan.scrollbackRefOf(1)).isNull();
        assertWithMessage("a crafted reference is never passed on").that(plan.scrollbackRefOf(2)).isNull();
    }

    @Test
    void aPlanIgnoresAnUnsafeSavedDirectory() {
        SplitPaneState first = leaf(0);
        SplitPaneState second = leaf(1);
        second.setCurrentDirectory("/tmp/a\nrm -rf ~");
        SplitPaneState third = leaf(2);
        third.setCurrentDirectory("/srv/work");

        SplitLayoutRestorePlan plan = SplitLayoutRestorePlan.plan(split(HORIZONTAL, 0.5, first,
            split(VERTICAL, 0.5, second, third)));

        assertThat(plan.directoryOf(1)).isNull();
        assertThat(plan.directoryOf(2)).isEqualTo("/srv/work");
    }

    @Test
    void everySignInStatusHasAReason() {
        assertThat(SplitLayoutRestorePlan.reasonFor(ConnectionAuthResolver.Status.NEEDS_PASSWORD))
            .isEqualTo(SplitLayoutRestorePlan.SkipReason.SIGN_IN);
        assertThat(SplitLayoutRestorePlan.reasonFor(ConnectionAuthResolver.Status.NEEDS_TEMP_KEY))
            .isEqualTo(SplitLayoutRestorePlan.SkipReason.SIGN_IN);
        assertThat(SplitLayoutRestorePlan.reasonFor(ConnectionAuthResolver.Status.NEEDS_UNLOCK))
            .isEqualTo(SplitLayoutRestorePlan.SkipReason.VAULT_LOCKED);
        assertThat(SplitLayoutRestorePlan.reasonFor(ConnectionAuthResolver.Status.BLOCKED))
            .isEqualTo(SplitLayoutRestorePlan.SkipReason.BLOCKED);
        assertThat(SplitLayoutRestorePlan.reasonFor(ConnectionAuthResolver.Status.MISSING))
            .isEqualTo(SplitLayoutRestorePlan.SkipReason.MISSING);
        assertThat(SplitLayoutRestorePlan.reasonFor(ConnectionAuthResolver.Status.READY))
            .isEqualTo(SplitLayoutRestorePlan.SkipReason.FAILED);
    }

    /** Replays the plan on a model of the split pane and checks it gives back the planned tree. */
    private static void assertRebuildsTheSavedTree(SplitLayoutRestorePlan plan) {
        PaneLayout<Integer> built = replay(plan.steps(), ALL_OPEN);
        assertWithMessage("replayed: %s, planned: %s", built, plan.layout())
            .that(built.sameShapeAs(plan.layout())).isTrue();
        assertWithMessage("the panes are numbered in tree order, as getAllWidgets() lists them")
            .that(built.panes()).isEqualTo(java.util.stream.IntStream.range(0, plan.paneCount()).boxed().toList());
    }

    /**
     * TerminalSplitPane.splitWidget in miniature: the split pane's leaf becomes a branch with the
     * split pane first and the new pane second. Steps whose pane is not open, or whose new pane does
     * not open, change nothing.
     */
    private static PaneLayout<Integer> replay(List<SplitLayoutRestorePlan.SplitStep> steps, IntPredicate opens) {
        PaneLayout<Integer> tree = PaneLayout.leaf(0);
        Set<Integer> open = new HashSet<>(Set.of(0));
        for (SplitLayoutRestorePlan.SplitStep step : steps) {
            if (!open.contains(step.sourceLeafId()) || !opens.test(step.newLeafId())) {
                continue;
            }
            tree = replaceLeaf(tree, step.sourceLeafId(), step.orientation(), step.newLeafId());
            open.add(step.newLeafId());
        }
        return tree;
    }

    private static PaneLayout<Integer> replaceLeaf(PaneLayout<Integer> node, int pane, Orientation orientation,
                                                   int newPane) {
        if (node.isLeaf()) {
            return node.pane() == pane
                ? PaneLayout.split(orientation, 0.5, node, PaneLayout.leaf(newPane))
                : node;
        }
        return PaneLayout.split(node.orientation(), node.divider(),
            replaceLeaf(node.first(), pane, orientation, newPane),
            replaceLeaf(node.second(), pane, orientation, newPane));
    }

    private static List<Double> dividers(PaneLayout<Integer> node) {
        List<Double> dividers = new java.util.ArrayList<>();
        collectDividers(node, dividers);
        return dividers;
    }

    private static void collectDividers(PaneLayout<Integer> node, List<Double> dividers) {
        if (node.isLeaf()) {
            return;
        }
        dividers.add(node.divider());
        collectDividers(node.first(), dividers);
        collectDividers(node.second(), dividers);
    }

    private static SplitPaneState balanced(int depth, int[] next) {
        if (depth == 0) {
            return leaf(next[0]++);
        }
        return split(depth % 2 == 0 ? HORIZONTAL : VERTICAL, 0.5, balanced(depth - 1, next), balanced(depth - 1, next));
    }

    private static SplitPaneState leaf(int index) {
        return SplitPaneState.createLeaf(index);
    }

    private static SplitPaneState leaf(int index, String connectionId) {
        return SplitPaneState.createLeaf(index, connectionId);
    }

    private static SplitPaneState split(Orientation orientation, double divider, SplitPaneState first,
                                        SplitPaneState second) {
        return SplitPaneState.createSplit(orientation, divider, first, second);
    }
}
