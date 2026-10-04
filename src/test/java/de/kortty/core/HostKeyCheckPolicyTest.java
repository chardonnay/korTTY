package de.kortty.core;

import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class HostKeyCheckPolicyTest {

    private static ServerConnection conn(String group, Boolean perConnection) {
        ServerConnection c = new ServerConnection("n", "h", 22, "u");
        c.setGroup(group);
        c.setDisableHostKeyCheck(perConnection);
        return c;
    }

    /** A connection as a teamwork source delivers it: written by whoever edits the shared file. */
    private static ServerConnection shared(String group, Boolean perConnection) {
        ServerConnection c = conn(group, perConnection);
        c.setConnectionSource(ConnectionSource.TEAMWORK);
        return c;
    }

    @Test
    void defaultIsStrictWhenNothingIsConfigured() {
        assertThat(HostKeyCheckPolicy.resolve(conn(null, null), false, Set.of()))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void globalDisableRelaxesAnInheritingConnection() {
        assertThat(HostKeyCheckPolicy.resolve(conn(null, null), true, Set.of()))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }

    @Test
    void groupDisableRelaxesOnlyItsGroup() {
        assertThat(HostKeyCheckPolicy.resolve(conn("prod", null), false, Set.of("prod")))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
        assertThat(HostKeyCheckPolicy.resolve(conn("dev", null), false, Set.of("prod")))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void perConnectionOverrideWinsOverGroupAndGlobal() {
        // Force strict on a critical host even though its group AND global relaxed it.
        assertThat(HostKeyCheckPolicy.resolve(conn("prod", Boolean.FALSE), true, Set.of("prod")))
            .isEqualTo(HostKeyCheckMode.STRICT);
        // Relax one host even though group and global are strict.
        assertThat(HostKeyCheckPolicy.resolve(conn("prod", Boolean.TRUE), false, Set.of()))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }

    @Test
    void groupOverridesGlobalWhenTheConnectionInherits() {
        // Global strict, but the group disabled -> accept-new.
        assertThat(HostKeyCheckPolicy.resolve(conn("lab", null), false, List.of("lab")))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }

    @Test
    void blankOrNullGroupNeverMatchesTheDisabledSet() {
        assertThat(HostKeyCheckPolicy.resolve(conn("", null), false, Set.of("")))
            .isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(HostKeyCheckPolicy.resolve(conn(null, null), false, Set.of("x")))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void aNullConnectionResolvesStrict() {
        assertThat(HostKeyCheckPolicy.resolve(null, true, Set.of("x")))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void aLocalFolderExemptionDoesNotReachASharedConnectionOfTheSameGroupName() {
        assertThat(HostKeyCheckPolicy.resolve(shared("Lab", null), false, Set.of("Lab")))
            .isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(HostKeyCheckPolicy.resolve(conn("Lab", null), false, Set.of("Lab")))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }

    @Test
    void aSharedFileCannotTurnVerificationOff() {
        assertThat(HostKeyCheckPolicy.resolve(shared(null, Boolean.TRUE), false, Set.of()))
            .isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(HostKeyCheckPolicy.resolve(shared("Lab", Boolean.TRUE), false, Set.of("Lab")))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void aSharedFileCanStillForceVerification() {
        assertThat(HostKeyCheckPolicy.resolve(shared("Lab", Boolean.FALSE), true, Set.of("Lab")))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void theGlobalSettingAppliesToSharedConnectionsToo() {
        // "Disable host key verification for all connections" is the user's own choice on this machine.
        assertThat(HostKeyCheckPolicy.resolve(shared("Lab", null), true, Set.of()))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
        assertThat(HostKeyCheckPolicy.resolve(shared("Lab", Boolean.TRUE), true, Set.of()))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }

    @Test
    void anExplicitlyLocalConnectionKeepsItsRelaxations() {
        ServerConnection local = conn("Lab", Boolean.TRUE);
        local.setConnectionSource(ConnectionSource.LOCAL);
        assertThat(HostKeyCheckPolicy.resolve(local, false, Set.of()))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
        local.setDisableHostKeyCheck(null);
        assertThat(HostKeyCheckPolicy.resolve(local, false, Set.of("Lab")))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }
}
