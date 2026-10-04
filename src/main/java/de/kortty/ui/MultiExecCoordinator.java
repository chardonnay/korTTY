package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.split.InputMirror;
import com.sithtermfx.ui.split.TerminalSplitPane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * korTTY's multi-exec: what you type in one of the panes you chose also goes to the other chosen
 * panes, in any tab of any window, as broadcast mode does for the panes of one tab. One instance
 * serves the whole application ({@link #shared()}); every {@link TerminalView} registers its panes
 * here and installs it as its split pane's {@link InputMirror}, so the split panes ask it at every
 * key which panes take part and which split pane holds a pane of another tab.
 *
 * <p>Only typed keys are mirrored, and only from a member into the other members, each through
 * the guard of the split pane that holds it (a pane that paces a paste, that an AI agent run drives
 * or whose coding agent waits for a decision gets nothing), the source's password rule and the
 * target's own key encoding. Paste, snippets, input-method text and Control API text stay in their
 * pane. A pane leaves when it closes, so closing a tab or a window takes its panes out; a tab
 * dragged to another window keeps them, since panes are told apart by reference.
 *
 * <p>Every change to the members redraws the pane markers of every split pane that holds a
 * registered pane, then tells the listeners, which keep the tab markers, the status bar of every
 * window, the dashboard and the menus up to date. Use it on the FX thread only.
 *
 * <p>An organization's policy can deny multi-exec ({@code [rule.features] multi-exec = "deny"}): then
 * no pane can join, whichever menu, palette entry or shortcut asks, while leaving and {@link #stop()}
 * keep working.
 */
public final class MultiExecCoordinator implements InputMirror {

    private static final Logger logger = LoggerFactory.getLogger(MultiExecCoordinator.class);

    private static final MultiExecCoordinator SHARED = new MultiExecCoordinator();

    private final MultiExecMembership<SithTermFxWidget> membership = new MultiExecMembership<>();
    // Every open pane and how to reach the split pane that holds it now; the supplier reads the
    // owner's field, because a tab's first pane is set up before its split pane is assigned.
    private final Map<SithTermFxWidget, Supplier<? extends TerminalSplitPane>> owners = new IdentityHashMap<>();

    // Asked at every join, so the policy is the gate rather than any one menu item.
    private final BooleanSupplier joinAllowed;

    MultiExecCoordinator() {
        this(() -> de.kortty.policy.PolicyManager.effective().multiExecAllowed());
    }

    /** @param joinAllowed whether panes may join now; the organization's policy in the application */
    MultiExecCoordinator(@NotNull BooleanSupplier joinAllowed) {
        this.joinAllowed = joinAllowed;
        // Registered first, so the pane markers are redrawn before any listener reads them.
        membership.addListener(this::refreshPaneMarkers);
    }

    /** The application's multi-exec. */
    public static @NotNull MultiExecCoordinator shared() {
        return SHARED;
    }

    // ---- panes --------------------------------------------------------------------------------

    /**
     * Makes {@code pane} known: it can join, and {@code owner} gives the split pane that holds it,
     * whose guard and key encoding apply to it. A tab registers every pane it creates.
     */
    public void register(@NotNull SithTermFxWidget pane, @NotNull Supplier<? extends TerminalSplitPane> owner) {
        owners.put(pane, owner);
    }

    /** Forgets {@code pane}, which closed: it leaves multi-exec and is never mirrored into again. */
    public void forget(@Nullable SithTermFxWidget pane) {
        if (pane == null) {
            return;
        }
        owners.remove(pane);
        membership.remove(pane);
    }

    /** Whether {@code pane} is known, that is open in a tab. */
    public boolean isRegistered(@Nullable SithTermFxWidget pane) {
        return pane != null && owners.containsKey(pane);
    }

    // ---- InputMirror ----------------------------------------------------------------------------

    @Override
    public boolean isMember(@NotNull SithTermFxWidget widget) {
        return membership.contains(widget);
    }

    @Override
    public @NotNull List<SithTermFxWidget> otherMembers(@NotNull SithTermFxWidget widget) {
        List<SithTermFxWidget> others = membership.othersBesides(widget);
        others.removeIf(member -> !owners.containsKey(member));
        return others;
    }

    @Override
    public @Nullable TerminalSplitPane ownerOf(@NotNull SithTermFxWidget widget) {
        Supplier<? extends TerminalSplitPane> owner = owners.get(widget);
        return owner != null ? owner.get() : null;
    }

    // ---- choosing the panes -----------------------------------------------------------------------

    /**
     * Lets {@code pane} take part, or no longer: the opposite of what it does now. A pane that is
     * not open in a tab cannot join.
     *
     * @return whether it takes part afterwards
     */
    public boolean togglePane(@Nullable SithTermFxWidget pane) {
        if (pane == null || (!membership.contains(pane) && !owners.containsKey(pane))) {
            return false;
        }
        if (!membership.contains(pane) && joinRefused()) {
            // Joining is what the policy denies; leaving always works.
            return false;
        }
        return membership.toggle(pane);
    }

    /**
     * Lets every open pane of {@code panes} take part, or none of them, as for a tab or a window.
     *
     * @return whether any pane changed
     */
    public boolean setPanes(@NotNull Collection<SithTermFxWidget> panes, boolean included) {
        if (included && joinRefused()) {
            return false;
        }
        List<SithTermFxWidget> open = new ArrayList<>(panes.size());
        for (SithTermFxWidget pane : panes) {
            if (pane != null && (!included || owners.containsKey(pane))) {
                open.add(pane);
            }
        }
        return membership.setAll(open, included);
    }

    /**
     * Lets the panes of a tab take part when not all of them do yet, else none of them: what
     * <i>Multi-exec: Include All Panes of This Tab</i> does.
     *
     * @return whether all of them take part afterwards
     */
    public boolean toggleAll(@NotNull Collection<SithTermFxWidget> panes) {
        boolean include = !membership.includesAll(panes);
        setPanes(panes, include);
        return include && membership.includesAll(panes);
    }

    /** Whether panes may join now; false while the organization's policy denies multi-exec. */
    public boolean joinAllowed() {
        return joinAllowed.getAsBoolean();
    }

    private boolean joinRefused() {
        if (joinAllowed()) {
            return false;
        }
        logger.info("Multi-exec join refused: denied by the organization's policy");
        return true;
    }

    /** Stops multi-exec: no pane takes part any more. */
    public void stop() {
        if (membership.clear()) {
            logger.info("Multi-exec stopped");
        }
    }

    // ---- what the markers show ----------------------------------------------------------------------

    /** The number of panes that take part. */
    public int memberCount() {
        return membership.size();
    }

    /** The panes that take part, in the order they joined. */
    public @NotNull List<SithTermFxWidget> members() {
        return membership.members();
    }

    /**
     * The multi-exec session {@code pane} takes part in, or {@code null} when it takes part in none:
     * the same object for every member from the first pane joining until the last one leaves. A
     * command typed once runs in every member, so the notifications use the session as the members'
     * shared slot and report that command once.
     */
    public @Nullable Object sessionOf(@Nullable SithTermFxWidget pane) {
        return membership.sessionOf(pane);
    }

    /** How many of {@code panes} take part, such as the panes of one tab. */
    public int countIn(@NotNull Collection<SithTermFxWidget> panes) {
        return membership.countIn(panes);
    }

    /** Whether {@code panes} has a pane and every one of them takes part. */
    public boolean includesAll(@NotNull Collection<SithTermFxWidget> panes) {
        return membership.includesAll(panes);
    }

    /**
     * How many members get nothing now although they are connected, because the guard of the split
     * pane that holds them holds them back: one that paces a paste, that an AI agent run drives or
     * whose coding agent waits for a decision.
     */
    public int countHeld() {
        int held = 0;
        for (SithTermFxWidget member : membership.members()) {
            TerminalSplitPane owner = safeOwnerOf(member);
            if (owner != null && owner.isHeldMirrorTarget(member)) {
                held++;
            }
        }
        return held;
    }

    /**
     * How far multi-exec reaches: the members, and how many tabs (split panes) and windows hold them.
     *
     * @param windowOf the window that holds a split pane, or {@code null} when it is not known
     */
    public @NotNull MultiExecMembership.Counts counts(@NotNull Function<? super TerminalSplitPane, ?> windowOf) {
        return membership.counts(this::safeOwnerOf, member -> {
            TerminalSplitPane owner = safeOwnerOf(member);
            return owner != null ? windowOf.apply(owner) : null;
        });
    }

    /**
     * Tells {@code listener}, on the FX thread, about every change to the members, after the pane
     * markers were redrawn, and whenever {@link #refreshMarkers} asks for it; until the handle is
     * closed.
     */
    public @NotNull AutoCloseable addListener(@NotNull Runnable listener) {
        return membership.addListener(listener);
    }

    /**
     * Redraws the pane markers and tells the listeners although the members did not change, because
     * what the markers show did: a tab's broadcast mode was switched, or a tab moved to another window.
     */
    public void refreshMarkers() {
        membership.fireChanged();
    }

    /** Redraws the markers of every split pane that holds a registered pane. */
    private void refreshPaneMarkers() {
        Set<TerminalSplitPane> splitPanes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SithTermFxWidget pane : List.copyOf(owners.keySet())) {
            TerminalSplitPane owner = safeOwnerOf(pane);
            if (owner != null && splitPanes.add(owner)) {
                try {
                    owner.refreshMirrorMarkers();
                } catch (RuntimeException e) {
                    logger.debug("Could not redraw the multi-exec markers of a tab: {}", e.toString());
                }
            }
        }
    }

    private @Nullable TerminalSplitPane safeOwnerOf(@NotNull SithTermFxWidget pane) {
        try {
            return ownerOf(pane);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
