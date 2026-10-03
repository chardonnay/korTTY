package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.ServerAccessPolicy;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Enterprise server-policy gate for a split pane that connects to another server than its tab
 * ("Split with new connection"). Tab opens and Quick Connect refuse a policy-blocked target or jump
 * host before anything connects; {@code MainWindow.requestNewConnectionForSplit} asks this seam the
 * same question right after the connection dialog and returns {@code null} when it is blocked, which
 * {@code TerminalView} treats like a cancelled dialog, so no connector is ever built for it. Free of
 * UI so the decision is unit-testable; later restore paths for panes on other servers reuse it.
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
