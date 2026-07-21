package de.kortty.core;

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
}
