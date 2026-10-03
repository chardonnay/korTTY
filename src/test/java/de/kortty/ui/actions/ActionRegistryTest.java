package de.kortty.ui.actions;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** The per-window action registry: explicit registrations, lazy contributors, and running. */
class ActionRegistryTest {

    private static AppAction action(String id, String label, BooleanSupplier enabled, Runnable run) {
        return new AppAction(id, label, "Test", null, List.of(), enabled, null, run, true, false);
    }

    private static AppAction action(String id, String label) {
        return action(id, label, () -> true, () -> { });
    }

    private static List<String> ids(List<AppAction> actions) {
        return actions.stream().map(AppAction::id).toList();
    }

    @Test
    void registeredActionCanBeFoundById() {
        ActionRegistry registry = new ActionRegistry();
        AppAction nextTab = action("palette.action.nextTab", "Next Tab");

        registry.register(nextTab);

        assertThat(registry.find("palette.action.nextTab")).hasValue(nextTab);
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void duplicateExplicitIdIsRejected() {
        ActionRegistry registry = new ActionRegistry();
        registry.register(action("same", "First"));

        assertThrows(IllegalArgumentException.class, () -> registry.register(action("same", "Second")));
        assertThat(registry.find("same").orElseThrow().label()).isEqualTo("First");
    }

    @Test
    void contributorsAreAskedAgainForEverySnapshot() {
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger calls = new AtomicInteger();
        List<AppAction> current = new ArrayList<>(List.of(action("menu.a", "A")));
        registry.addContributor(() -> {
            calls.incrementAndGet();
            return List.copyOf(current);
        });

        assertThat(ids(registry.snapshot())).containsExactly("menu.a");
        current.add(action("menu.b", "B"));
        assertThat(ids(registry.snapshot())).containsExactly("menu.a", "menu.b").inOrder();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void explicitActionWinsOverAContributedOneWithTheSameId() {
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> List.of(action("clash", "Contributed"), action("other", "Other")));
        registry.register(action("clash", "Explicit"));

        List<AppAction> snapshot = registry.snapshot();

        assertThat(ids(snapshot)).containsExactly("clash", "other").inOrder();
        assertThat(snapshot.get(0).label()).isEqualTo("Explicit");
        assertThat(registry.find("clash").orElseThrow().label()).isEqualTo("Explicit");
    }

    @Test
    void earlierContributorWinsOverALaterOne() {
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> List.of(action("clash", "First contributor")));
        registry.addContributor(() -> List.of(action("clash", "Second contributor"), action("only-second", "X")));

        assertThat(ids(registry.snapshot())).containsExactly("clash", "only-second").inOrder();
        assertThat(registry.find("clash").orElseThrow().label()).isEqualTo("First contributor");
        assertThat(registry.find("only-second")).isPresent();
    }

    @Test
    void snapshotKeepsRegistrationOrderExplicitFirst() {
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> List.of(action("menu.z", "Z"), action("menu.y", "Y")));
        registry.register(action("c", "C"));
        registry.register(action("a", "A"));
        registry.register(action("b", "B"));
        registry.addContributor(() -> List.of(action("menu.x", "X")));

        assertThat(ids(registry.snapshot())).containsExactly("c", "a", "b", "menu.z", "menu.y", "menu.x").inOrder();
    }

    @Test
    void nullContributionsAreIgnored() {
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> null);
        List<AppAction> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(action("kept", "Kept"));
        registry.addContributor(() -> withNull);

        assertThat(ids(registry.snapshot())).containsExactly("kept");
        assertThat(registry.find("kept")).isPresent();
    }

    @Test
    void runChecksTheEnabledStateAtRunTime() {
        ActionRegistry registry = new ActionRegistry();
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger runs = new AtomicInteger();
        registry.register(action("toggle", "Toggle", enabled::get, runs::incrementAndGet));

        AppAction fromSnapshot = registry.snapshot().get(0);
        enabled.set(false);

        assertThat(registry.run("toggle")).isFalse();
        assertThat(ActionRegistry.runIfEnabled(fromSnapshot)).isFalse();
        assertThat(runs.get()).isEqualTo(0);

        enabled.set(true);
        assertThat(registry.run("toggle")).isTrue();
        assertThat(ActionRegistry.runIfEnabled(fromSnapshot)).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void runOfAnUnknownIdReportsFalse() {
        ActionRegistry registry = new ActionRegistry();

        assertThat(registry.run("nothing")).isFalse();
        assertThat(ActionRegistry.runIfEnabled(null)).isFalse();
    }

    @Test
    void contributedActionsRunThroughTheRegistry() {
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.addContributor(() -> List.of(action("menu.run", "Run", () -> true, runs::incrementAndGet)));

        assertThat(registry.run("menu.run")).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void actionRequiresAnIdAndCopiesItsKeywords() {
        assertThrows(IllegalArgumentException.class, () -> action(" ", "Blank"));
        assertThrows(IllegalArgumentException.class, () -> action(null, "Null"));

        List<String> keywords = new ArrayList<>(List.of("one"));
        AppAction withKeywords = new AppAction("k", "K", null, null, keywords, () -> true, null, () -> { }, false, false);
        keywords.add("two");

        assertThat(withKeywords.keywords()).containsExactly("one");
        assertThat(withKeywords.category()).isEmpty();
        assertThat(withKeywords.isCheckable()).isFalse();
        assertThat(withKeywords.isChecked()).isFalse();
    }
}
