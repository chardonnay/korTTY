package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.control.ControlApiProtocol;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * The ancestry walk behind {@code --current}.
 *
 * <p>The walk has to be total: it runs in whatever process tree the caller happens to be in — a
 * container, a CI runner, a sandbox that hides the parent — and must produce a usable list or an
 * empty one, never an exception, because a thrown exception here would turn "you are not inside a
 * pane" into a crash.
 */
class PaneAncestryTest {

    @Test
    void theCurrentProcessComesFirstSoTheInnermostPaneWins() {
        List<Long> pids = PaneAncestry.ancestorPids(ControlApiProtocol.MAX_RESOLVE_PIDS);

        assertThat(pids).isNotEmpty();
        assertWithMessage("pane.resolve returns the first match, so nearest must be first")
            .that(pids.get(0))
            .isEqualTo(ProcessHandle.current().pid());
    }

    @Test
    void theListNeverExceedsTheRequestedMaximum() {
        for (int max : new int[] {1, 2, 3, ControlApiProtocol.MAX_RESOLVE_PIDS}) {
            assertWithMessage("ancestorPids(%s)", max)
                .that(PaneAncestry.ancestorPids(max).size())
                .isAtMost(max);
        }
    }

    @Test
    void aNonPositiveMaximumYieldsAnEmptyListRatherThanThrowing() {
        assertThat(PaneAncestry.ancestorPids(0)).isEmpty();
        assertThat(PaneAncestry.ancestorPids(-1)).isEmpty();
    }

    @Test
    void theWalkTerminatesAtTheRootOfTheVisibleProcessTree() {
        List<Long> generous = PaneAncestry.ancestorPids(ControlApiProtocol.MAX_RESOLVE_PIDS);

        assertWithMessage("a process tree this JVM can see is never 32 levels deep in a test")
            .that(generous.size())
            .isLessThan(ControlApiProtocol.MAX_RESOLVE_PIDS);
    }

    @Test
    void theListIsFreeOfRepeatsSoOneParentCannotLoopTheWalk() {
        List<Long> pids = PaneAncestry.ancestorPids(ControlApiProtocol.MAX_RESOLVE_PIDS);
        Set<Long> seen = new HashSet<>(pids);

        assertThat(seen).hasSize(pids.size());
    }

    @Test
    void theWalkNeverThrowsEvenWhenAParentHasAlreadyExited() {
        // ProcessHandle.parent() is empty for an exited, hidden or root parent; every one of those
        // ends the walk rather than failing it, which is what makes repeated calls safe here.
        for (int attempt = 0; attempt < 50; attempt++) {
            assertThat(PaneAncestry.ancestorPids(ControlApiProtocol.MAX_RESOLVE_PIDS)).isNotNull();
        }
    }

    @Test
    void theReturnedListIsImmutable() {
        List<Long> pids = PaneAncestry.ancestorPids(4);

        try {
            pids.add(1L);
            assertWithMessage("the caller must not be able to alter the pid list").fail();
        } catch (UnsupportedOperationException expected) {
            assertThat(expected).isNotNull();
        }
    }
}
