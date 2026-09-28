package de.kortty.ui;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** {@code FXCollections} lists work without a toolkit, so this is a plain unit test. */
class ObservableListSyncTest {

    private record Row(String id, int usage) {
    }

    private static Row row(String id) {
        return new Row(id, 0);
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    /** Records every change as "op:from-to" and fails on a wholesale replace ({@code setAll}). */
    private static List<String> observe(ObservableList<Row> list) {
        List<String> ops = new ArrayList<>();
        list.addListener((ListChangeListener<Row>) change -> {
            while (change.next()) {
                if (change.wasPermutated()) {
                    ops.add("permute:" + change.getFrom() + "-" + change.getTo());
                } else if (change.wasReplaced()) {
                    ops.add("replace:" + change.getFrom() + "-" + change.getTo());
                } else if (change.wasRemoved()) {
                    ops.add("remove:" + change.getFrom() + "x" + change.getRemovedSize());
                } else if (change.wasAdded()) {
                    ops.add("add:" + change.getFrom() + "-" + change.getTo());
                }
            }
        });
        return ops;
    }

    @Test
    void identicalListsProduceNoChangeAtAll() {
        ObservableList<Row> target = FXCollections.observableArrayList(row("a"), row("b"), row("c"));
        List<String> ops = observe(target);

        ObservableListSync.sync(target, List.of(target.get(0), target.get(1), target.get(2)), Row::id);

        assertThat(ops).isEmpty();
        assertThat(ids(target)).containsExactly("a", "b", "c").inOrder();
    }

    @Test
    void removesOnlyTheVanishedItems() {
        ObservableList<Row> target = FXCollections.observableArrayList(row("a"), row("b"), row("c"), row("d"));
        List<String> ops = observe(target);

        ObservableListSync.sync(target, List.of(target.get(0), target.get(2)), Row::id);

        assertThat(ids(target)).containsExactly("a", "c").inOrder();
        assertThat(ops).containsExactly("remove:1x1", "remove:2x1").inOrder();
    }

    @Test
    void insertsNewItemsAtTheirPosition() {
        ObservableList<Row> target = FXCollections.observableArrayList(row("a"), row("c"));
        List<String> ops = observe(target);

        ObservableListSync.sync(target, List.of(row("x"), target.get(0), row("b"), target.get(1), row("z")), Row::id);

        assertThat(ids(target)).containsExactly("x", "a", "b", "c", "z").inOrder();
        assertThat(ops).containsExactly("add:0-1", "add:2-3", "add:4-5").inOrder();
    }

    @Test
    void movesAnItemThatChangedPositionWithoutTouchingTheRest() {
        ObservableList<Row> target = FXCollections.observableArrayList(row("a"), row("b"), row("c"), row("d"));
        List<String> ops = observe(target);

        ObservableListSync.sync(target, List.of(target.get(3), target.get(0), target.get(1), target.get(2)), Row::id);

        assertThat(ids(target)).containsExactly("d", "a", "b", "c").inOrder();
        assertThat(ops).containsExactly("remove:3x1", "add:0-1").inOrder();
    }

    @Test
    void replacesASurvivorWhoseInstanceChangedInPlace() {
        Row oldB = new Row("b", 1);
        Row newB = new Row("b", 7);
        ObservableList<Row> target = FXCollections.observableArrayList(row("a"), oldB, row("c"));
        List<String> ops = observe(target);

        ObservableListSync.sync(target, List.of(target.get(0), newB, target.get(2)), Row::id);

        assertThat(target.get(1)).isSameInstanceAs(newB);
        assertThat(ops).containsExactly("replace:1-2");
    }

    @Test
    void neverUsesAWholesaleReplaceForAMixedChange() {
        ObservableList<Row> target = FXCollections.observableArrayList(
            row("a"), row("b"), row("c"), row("d"), row("e"));
        List<String> ops = observe(target);

        ObservableListSync.sync(target, List.of(target.get(4), target.get(0), row("n"), target.get(2)), Row::id);

        assertThat(ids(target)).containsExactly("e", "a", "n", "c").inOrder();
        assertThat(ops).doesNotContain("replace:0-4");
        assertThat(ops).doesNotContain("remove:0x5");
    }

    @Test
    void dropsDuplicateKeysKeepingTheFirstOccurrence() {
        ObservableList<Row> target = FXCollections.observableArrayList(row("a"), row("a"), row("b"));

        ObservableListSync.sync(target, List.of(new Row("b", 1), new Row("b", 2), row("a")), Row::id);

        assertThat(target).containsExactly(new Row("b", 1), row("a")).inOrder();
    }

    @Test
    void emptyTargetAndEmptyFreshAreHandled() {
        ObservableList<Row> empty = FXCollections.observableArrayList();
        ObservableListSync.sync(empty, List.of(row("a")), Row::id);
        assertThat(ids(empty)).containsExactly("a");

        ObservableListSync.sync(empty, List.of(), Row::id);
        assertThat(empty).isEmpty();
    }

    @Test
    void reconcileOrderKeepsSurvivorsInPlaceAndAppendsNewOnesWhenNotResorting() {
        List<Row> current = List.of(row("c"), row("a"), row("b"));
        Row freshA = new Row("a", 9);
        List<Row> freshSorted = List.of(freshA, row("n1"), row("c"), row("n2"));

        List<Row> ordered = ObservableListSync.reconcileOrder(current, freshSorted, Row::id, false);

        assertThat(ids(ordered)).containsExactly("c", "a", "n1", "n2").inOrder(); // b vanished
        assertThat(ordered.get(1)).isSameInstanceAs(freshA); // instances come from the fresh list
    }

    @Test
    void reconcileOrderReturnsTheFreshOrderWhenResorting() {
        List<Row> current = List.of(row("c"), row("a"));
        List<Row> freshSorted = List.of(row("a"), row("b"), row("c"));

        List<Row> ordered = ObservableListSync.reconcileOrder(current, freshSorted, Row::id, true);

        assertThat(ids(ordered)).containsExactly("a", "b", "c").inOrder();
        assertThat(ordered).isNotSameInstanceAs(freshSorted);
    }

    @Test
    void usageBumpDoesNotMoveTheRowUntilAResort() {
        // The manager's default order is usage desc: after copying "b" twice it would sort first.
        ObservableList<Row> table = FXCollections.observableArrayList(new Row("a", 5), new Row("b", 4));
        List<Row> fresh = List.of(new Row("b", 6), new Row("a", 5));

        ObservableListSync.sync(table, ObservableListSync.reconcileOrder(table, fresh, Row::id, false), Row::id);
        assertThat(ids(table)).containsExactly("a", "b").inOrder();
        assertThat(table.get(1).usage()).isEqualTo(6);

        ObservableListSync.sync(table, ObservableListSync.reconcileOrder(table, fresh, Row::id, true), Row::id);
        assertThat(ids(table)).containsExactly("b", "a").inOrder();
    }
}
