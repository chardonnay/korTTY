package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.ServerConnection;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyUiSupport;
import de.kortty.policy.ServerAccessPolicy;
import de.kortty.teamwork.TeamworkRecycleBinService;
import de.kortty.teamwork.TeamworkSyncService;
import de.kortty.ui.actions.ConnectionPaletteSource;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Connects the command palette's connection rows ({@link ConnectionPaletteSource}) to the running
 * application: the saved connections of the configuration, the teamwork connections of the sync
 * service without the recycle bin's, only while {@link PolicyManager#effective() the policy} allows
 * teamwork, and the server policy's {@link ServerAccessPolicy#firstBlockedTarget blocked targets}.
 */
final class ConnectionPaletteRows {

    /** The texts these rows use, the badge new and the rest shared; pinned by CommandPaletteI18nCoverageTest. */
    static final List<String> KEYS = List.of("palette.detail.teamwork", "protocol.localShell",
        "policy.server.blocked.message");

    private ConnectionPaletteRows() {
    }

    /**
     * The connection rows of {@code app}'s palette.
     *
     * @param connect opens a tab for the chosen connection
     */
    static ConnectionPaletteSource source(KorTTYApplication app, Consumer<ServerConnection> connect) {
        return new ConnectionPaletteSource(
            new ConnectionPaletteSource.Connections(
                () -> app.getConfigManager().getConnections(),
                () -> PolicyManager.effective().teamworkAllowed(),
                () -> teamworkConnections(app),
                () -> deletedTeamworkIds(app)),
            ServerAccessPolicy::firstBlockedTarget,
            new ConnectionPaletteSource.Texts() {
                @Override
                public String teamwork() {
                    return I18n.get("palette.detail.teamwork");
                }

                @Override
                public String localShell() {
                    return I18n.get("protocol.localShell");
                }

                @Override
                public String blocked(String target) {
                    return blockedReason(target);
                }
            },
            connect);
    }

    /** What the palette's footer says about a connection the server policy blocks, as its dialog does. */
    static String blockedReason(String target) {
        return I18n.get("policy.server.blocked.message", target) + " " + PolicyUiSupport.managedByOrganizationText();
    }

    private static List<ServerConnection> teamworkConnections(KorTTYApplication app) {
        TeamworkSyncService sync = app.getTeamworkSyncService();
        return sync != null ? sync.getTeamworkConnections() : List.of();
    }

    private static Set<String> deletedTeamworkIds(KorTTYApplication app) {
        TeamworkRecycleBinService recycleBin = app.getTeamworkRecycleBinService();
        return recycleBin != null ? recycleBin.getDeletedIds() : Set.of();
    }
}
