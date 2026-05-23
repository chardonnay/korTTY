package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import static com.google.common.truth.Truth.assertThat;

class SnippetDiffSelectionSupportTest {

    @Test
    void canDiffRequiresExactlyTwoSelectedSnippets() {
        Snippet first = snippet("first");
        Snippet second = snippet("second");
        Snippet third = snippet("third");

        assertThat(SnippetDiffSelectionSupport.canDiff(List.of())).isFalse();
        assertThat(SnippetDiffSelectionSupport.canDiff(List.of(first))).isFalse();
        assertThat(SnippetDiffSelectionSupport.canDiff(List.of(first, second))).isTrue();
        assertThat(SnippetDiffSelectionSupport.canDiff(List.of(first, second, third))).isFalse();
    }

    @Test
    void orderedPairUsesVisibleTableOrderNotSelectionOrder() {
        Snippet first = snippet("first");
        Snippet second = snippet("second");
        Snippet third = snippet("third");

        Optional<SnippetDiffSelectionSupport.SelectionPair> pair =
                SnippetDiffSelectionSupport.orderedPair(
                        List.of(first, second, third),
                        List.of(third, first));

        assertThat(pair).isPresent();
        assertThat(pair.get().left()).isEqualTo(first);
        assertThat(pair.get().right()).isEqualTo(third);
    }

    @Test
    void orderedPairReturnsEmptyWhenSelectionIsNotComparable() {
        Snippet first = snippet("first");
        Snippet second = snippet("second");
        Snippet third = snippet("third");

        assertThat(SnippetDiffSelectionSupport.orderedPair(List.of(first, second), List.of(first)))
                .isEmpty();
        assertThat(SnippetDiffSelectionSupport.orderedPair(
                List.of(first, second, third),
                List.of(first, second, third))).isEmpty();
    }

    private static Snippet snippet(String name) {
        return new Snippet(name + ".sh", "echo " + name, "bash");
    }
}
