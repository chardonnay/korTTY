package de.kortty.core;

import de.kortty.model.Snippet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Determines whether the current snippet table selection can be compared and
 * orders the two selected snippets by their visible table order.
 */
public final class SnippetDiffSelectionSupport {

    private SnippetDiffSelectionSupport() {
    }

    public record SelectionPair(Snippet left, Snippet right) {
    }

    public static boolean canDiff(Collection<Snippet> selectedSnippets) {
        return selectedSnippets != null && selectedSnippets.size() == 2;
    }

    public static Optional<SelectionPair> orderedPair(
            List<Snippet> visibleSnippets,
            Collection<Snippet> selectedSnippets) {

        if (!canDiff(selectedSnippets) || visibleSnippets == null) {
            return Optional.empty();
        }

        Set<Snippet> selected = new HashSet<>(selectedSnippets);
        List<Snippet> ordered = new ArrayList<>(2);
        for (Snippet snippet : visibleSnippets) {
            if (selected.contains(snippet)) {
                ordered.add(snippet);
                if (ordered.size() == 2) {
                    return Optional.of(new SelectionPair(ordered.get(0), ordered.get(1)));
                }
            }
        }
        return Optional.empty();
    }
}
