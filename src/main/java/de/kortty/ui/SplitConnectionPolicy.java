package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.ServerAccessPolicy;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Enterprise server-policy gate for split panes. Tab opens and Quick Connect refuse a policy-blocked
 * target or jump host before anything connects; a split asks this seam the same question before it
 * builds a connector:
 *
 * <ul>
 *   <li>"Split with new connection": {@code MainWindow.requestNewConnectionForSplit} right after the
 *       connection dialog, returning {@code null} when it is blocked, which {@code TerminalView}
 *       treats like a cancelled dialog.</li>
 *   <li>"Split with same server": {@code TerminalView.doCreateSameServerConnection}, for the
 *       connection of the pane being split ({@link PaneOrigin}), because the connection editor
 *       changes a saved connection in place, so an open pane's host or jump server can have been
 *       edited to a blocked one after the pane passed the gate.</li>
 * </ul>
 *
 * <p>Free of UI so the decision is unit-testable; later restore paths for panes on other servers
 * reuse it.
 */
final class SplitConnectionPolicy {

    private SplitConnectionPolicy() {
    }

    /**
     * The first policy-blocked target of {@code connection} (jump host first, then the target) as
     * {@code host:port}, or empty when the active policy allows it.
     */
    static Optional<String> blockedTarget(@Nullable ServerConnection connection) {
        return blockedTarget(connection, PolicyManager.effective());
    }

    /** {@link #blockedTarget(ServerConnection)} against an explicit {@code policy}. */
    static Optional<String> blockedTarget(@Nullable ServerConnection connection, EffectivePolicy policy) {
        return ServerAccessPolicy.firstBlockedTarget(connection, policy);
    }
}
