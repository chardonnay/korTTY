package de.kortty.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * The process-ancestry walk behind {@code --current}.
 *
 * <p>{@code --current} means "the pane this process is running in", and the only honest way to answer
 * that is to hand korTTY the chain of process ids from here up to the session root and let
 * {@code pane.resolve} match it against every pane's local shell pid. An inherited environment
 * variable would be wrong by construction: it propagates into ssh sessions, sub-shells and
 * containers, so a script three hops away would confidently address a pane it is not in. The walk
 * fails loudly instead — no ancestor matches, and the CLI says so.
 *
 * <p>The nearest ancestor comes first, so the server returns the innermost pane when a korTTY pane
 * runs a shell that runs a script that runs this CLI.
 *
 * <p>Pure apart from reading this JVM's own process tree; any thread.
 */
public final class PaneAncestry {

    private PaneAncestry() {
    }

    /**
     * This process's id followed by its ancestors, nearest first, at most {@code max} entries.
     *
     * <p>Never throws. {@code ProcessHandle.parent()} is empty at the root of the visible process
     * tree and also when a parent has already exited, a sandbox hides it, or the platform simply does
     * not report it; every one of those ends the walk with whatever was collected, because a short
     * list still resolves correctly — it just matches fewer panes.
     *
     * @param max the cap, normally {@code ControlApiProtocol.MAX_RESOLVE_PIDS}; zero or less yields an
     *     empty list
     */
    public static List<Long> ancestorPids(int max) {
        if (max <= 0) {
            return List.of();
        }
        List<Long> pids = new ArrayList<>(Math.min(max, 32));
        try {
            ProcessHandle handle = ProcessHandle.current();
            while (handle != null && pids.size() < max) {
                pids.add(handle.pid());
                handle = handle.parent().orElse(null);
            }
        } catch (RuntimeException e) {
            // A platform that refuses to walk the tree yields what was collected; --current then
            // reports that it is not inside a pane, which is the correct answer there anyway.
            return List.copyOf(pids);
        }
        return List.copyOf(pids);
    }
}
