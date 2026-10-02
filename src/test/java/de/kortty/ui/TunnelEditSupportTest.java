package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

/** The tunnel editor's rules: per-type labels, the bind warning, the list text and the switch. */
class TunnelEditSupportTest {

    private Locale previous;

    @BeforeClass
    void english() {
        previous = LanguageManager.getInstance().getCurrentLocale();
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
    }

    @AfterClass(alwaysRun = true)
    void restoreLocale() {
        // The locale is global: leave it as found so later tests see their own expectations.
        if (previous != null) {
            LanguageManager.getInstance().setLocale(previous);
        }
    }

    private static SSHTunnel tunnel(TunnelType type, String localHost, int localPort, String remoteHost, int remotePort,
            boolean enabled) {
        SSHTunnel tunnel = new SSHTunnel();
        tunnel.setEnabled(enabled);
        tunnel.setType(type);
        tunnel.setLocalHost(localHost);
        tunnel.setLocalPort(localPort);
        tunnel.setRemoteHost(remoteHost);
        tunnel.setRemotePort(remotePort);
        return tunnel;
    }

    @Test
    void theListenerFieldIsLabelledAsTheBindAddressForEveryType() {
        TunnelEditSupport.FieldLabels local = TunnelEditSupport.fieldLabels(TunnelType.LOCAL);
        assertThat(local.localHostKey()).isEqualTo("tunnel.localBindAddress");
        assertThat(local.remoteHostKey()).isEqualTo("tunnel.remoteHost");
        assertThat(local.remoteFirst()).isFalse();
        assertThat(local.remoteUsed()).isTrue();

        // A remote tunnel listens on the server: its remote host is the bind address and comes
        // first, the local host is where the connections go.
        TunnelEditSupport.FieldLabels remote = TunnelEditSupport.fieldLabels(TunnelType.REMOTE);
        assertThat(remote.remoteHostKey()).isEqualTo("tunnel.remoteBindAddress");
        assertThat(remote.localHostKey()).isEqualTo("tunnel.localHost");
        assertThat(remote.remoteFirst()).isTrue();
        assertThat(remote.remoteUsed()).isTrue();

        TunnelEditSupport.FieldLabels dynamic = TunnelEditSupport.fieldLabels(TunnelType.DYNAMIC);
        assertThat(dynamic.localHostKey()).isEqualTo("tunnel.localBindAddress");
        assertThat(dynamic.remoteUsed()).isFalse();

        assertThat(TunnelEditSupport.fieldLabels(null)).isEqualTo(local);
    }

    @Test
    void everyLabelKeyResolvesToText() {
        for (TunnelType type : TunnelType.values()) {
            TunnelEditSupport.FieldLabels labels = TunnelEditSupport.fieldLabels(type);
            for (String key : List.of(labels.localHostKey(), labels.localPortKey(), labels.remoteHostKey(),
                    labels.remotePortKey(), TunnelEditSupport.typeLabelKey(type))) {
                assertThat(I18n.get(key)).isNotEqualTo(key);
            }
        }
        assertThat(I18n.get(TunnelEditSupport.typeLabelKey(TunnelType.DYNAMIC))).isEqualTo("Dynamic SOCKS proxy (-D)");
    }

    @Test
    void onlyANonLoopbackListenerIsWarnedAbout() {
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.LOCAL, "localhost", "0.0.0.0")).isNull();
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.LOCAL, "", "db")).isNull();
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.LOCAL, "[::1]", "db")).isNull();
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.LOCAL, "0.0.0.0", "db"))
            .isEqualTo("tunnel.nonLoopbackWarning");
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.DYNAMIC, "192.168.1.5", null))
            .isEqualTo("tunnel.nonLoopbackWarning");

        // The listener of a remote tunnel is on the server; the local host is only its target.
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.REMOTE, "0.0.0.0", "localhost")).isNull();
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.REMOTE, "localhost", " ")).isNull();
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.REMOTE, "localhost", "0.0.0.0"))
            .isEqualTo("tunnel.nonLoopbackWarning.remote");
        assertThat(TunnelEditSupport.bindWarningKey(TunnelType.REMOTE, "localhost", "*"))
            .isEqualTo("tunnel.nonLoopbackWarning.remote");
    }

    @Test
    void theListReadsListenerFirstAndFlagsAReachableListener() {
        SSHTunnel local = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432, true);
        local.setDescription(" Database ");
        assertThat(TunnelEditSupport.listText(local)).isEqualTo("✓ L localhost:8080 -> db:5432 - Database");

        // Remote tunnels used to be listed target first; the bind address on the server leads now.
        SSHTunnel imported = tunnel(TunnelType.REMOTE, "localhost", 3000, "0.0.0.0", 9090, false);
        assertThat(TunnelEditSupport.listText(imported))
            .isEqualTo("○ R 0.0.0.0:9090 -> localhost:3000 (may be reachable from other computers)");

        SSHTunnel dynamic = tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0, true);
        assertThat(TunnelEditSupport.listText(dynamic)).isEqualTo("✓ D localhost:1080 (SOCKS)");
        assertThat(TunnelEditSupport.listText(null)).isEmpty();
    }

    @Test
    void theSwitchReflectsHowManyTunnelsAreEnabled() {
        SSHTunnel on = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432, true);
        SSHTunnel off = tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0, false);

        assertThat(TunnelEditSupport.switchState(List.of())).isEqualTo(TunnelEditSupport.SwitchState.NONE);
        assertThat(TunnelEditSupport.switchState(null)).isEqualTo(TunnelEditSupport.SwitchState.NONE);
        assertThat(TunnelEditSupport.switchState(List.of(off))).isEqualTo(TunnelEditSupport.SwitchState.NONE);
        assertThat(TunnelEditSupport.switchState(List.of(on, off))).isEqualTo(TunnelEditSupport.SwitchState.SOME);
        assertThat(TunnelEditSupport.switchState(List.of(on))).isEqualTo(TunnelEditSupport.SwitchState.ALL);
    }

    @Test
    void unTickingTheSwitchDisablesEveryTunnelAndTickingEnablesThemAll() {
        List<SSHTunnel> tunnels = new ArrayList<>(List.of(
            tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432, true),
            tunnel(TunnelType.REMOTE, "localhost", 3000, "localhost", 9090, false)));
        tunnels.add(null);

        TunnelEditSupport.setAllEnabled(tunnels, false);
        assertThat(TunnelEditSupport.switchState(tunnels)).isEqualTo(TunnelEditSupport.SwitchState.NONE);

        TunnelEditSupport.setAllEnabled(tunnels, true);
        assertThat(TunnelEditSupport.switchState(tunnels)).isEqualTo(TunnelEditSupport.SwitchState.ALL);
    }

    @Test
    void theEditorWorksOnCopiesSoCancelLeavesTheConnectionAlone() {
        SSHTunnel stored = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432, true);
        stored.setDescription("db");
        List<SSHTunnel> storedList = new ArrayList<>(List.of(stored));

        List<SSHTunnel> working = TunnelEditSupport.workingCopies(storedList);
        TunnelEditSupport.setAllEnabled(working, false);
        working.add(tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0, true));

        assertThat(storedList).containsExactly(stored);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(working.get(0)).isNotSameInstanceAs(stored);
        assertThat(working.get(0).getDescription()).isEqualTo("db");
        assertThat(TunnelEditSupport.workingCopies(null)).isEmpty();
    }

    @Test
    void savingWritesTheTunnelsIntoTheSharedListInPlace() {
        ServerConnection connection = new ServerConnection();
        List<SSHTunnel> shared = connection.getSshTunnels();
        shared.add(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432, true));
        // A tab opened from a copy for authentication shares the same list.
        ServerConnection tabCopy = ServerConnection.copyForAuth(connection);

        List<SSHTunnel> working = TunnelEditSupport.workingCopies(shared);
        TunnelEditSupport.setAllEnabled(working, false);
        TunnelEditSupport.writeBack(connection, working);

        assertThat(connection.getSshTunnels()).isSameInstanceAs(shared);
        assertThat(tabCopy.getSshTunnels()).hasSize(1);
        assertThat(tabCopy.getSshTunnels().get(0).isEnabled()).isFalse();

        // Writing the list onto itself keeps it, and a list that cannot be changed is replaced.
        TunnelEditSupport.writeBack(connection, connection.getSshTunnels());
        assertThat(connection.getSshTunnels()).hasSize(1);
        connection.setSshTunnels(Collections.unmodifiableList(new ArrayList<>(working)));
        TunnelEditSupport.writeBack(connection, List.of());
        assertThat(connection.getSshTunnels()).isEmpty();
        connection.setSshTunnels(null);
        TunnelEditSupport.writeBack(connection, working);
        assertThat(connection.getSshTunnels()).hasSize(1);
    }
}
