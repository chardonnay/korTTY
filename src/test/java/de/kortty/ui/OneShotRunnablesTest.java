package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The actions behind {@link TerminalTab#addOnFirstConnected}: opening a project rebuilds a tab's
 * split panes once its first session is up. They used to be rebuilt after a fixed one-second sleep
 * on the JavaFX thread; run from the tab's connected callback instead, they would be rebuilt again
 * after every automatic reconnect. They must run exactly once.
 */
class OneShotRunnablesTest {

    @Test
    void theActionsRunOnceInOrderAndNeverOnALaterEvent() {
        List<String> ran = new ArrayList<>();
        OneShotRunnables actions = new OneShotRunnables(Runnable::run);
        actions.add(() -> ran.add("splits"));
        actions.add(() -> ran.add("dividers"));

        assertThat(ran).isEmpty();
        assertThat(actions.pendingCount()).isEqualTo(2);
        assertThat(actions.fire()).isTrue();
        assertThat(ran).containsExactly("splits", "dividers").inOrder();

        // A reconnect: the tab connects again and nothing runs again.
        assertThat(actions.fire()).isFalse();
        assertThat(ran).containsExactly("splits", "dividers").inOrder();
        assertThat(actions.hasFired()).isTrue();
        assertThat(actions.pendingCount()).isEqualTo(0);
    }

    @Test
    void anActionAddedAfterTheEventRunsThroughTheLateRunner() {
        List<Runnable> later = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        OneShotRunnables actions = new OneShotRunnables(later::add);
        actions.fire();

        actions.add(() -> ran.add("late"));

        assertThat(ran).isEmpty();
        assertThat(later).hasSize(1);
        later.get(0).run();
        assertThat(ran).containsExactly("late");
        assertThat(actions.pendingCount()).isEqualTo(0);
    }

    @Test
    void aFailingActionDoesNotStopTheOthers() {
        List<String> ran = new ArrayList<>();
        OneShotRunnables actions = new OneShotRunnables(Runnable::run);
        actions.add(() -> {
            throw new IllegalStateException("boom");
        });
        actions.add(() -> ran.add("still runs"));

        assertThat(actions.fire()).isTrue();
        assertThat(ran).containsExactly("still runs");
    }

    @Test
    void anActionAddedWhileTheActionsRunWaitsForTheLateRunner() {
        List<Runnable> later = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        OneShotRunnables actions = new OneShotRunnables(later::add);
        actions.add(() -> actions.add(() -> ran.add("nested")));

        actions.fire();

        assertThat(ran).isEmpty();
        assertThat(later).hasSize(1);
    }

    @Test
    void closingTheTabDropsWhatWaits() {
        List<String> ran = new ArrayList<>();
        OneShotRunnables actions = new OneShotRunnables(Runnable::run);
        actions.add(() -> ran.add("never"));

        actions.clear();

        assertThat(actions.pendingCount()).isEqualTo(0);
        actions.fire();
        assertThat(ran).isEmpty();
    }
}
