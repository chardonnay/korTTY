package de.kortty.isolation;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.testng.annotations.Test;

/**
 * Which isolation a connection asks for: its own level, its folder's, Settings', the teamwork rule that
 * only tightens, and the organization's minimum on top.
 */
class IsolationSettingsTest {

    private static ServerConnection connection(String group, IsolationLevel own) {
        ServerConnection c = new ServerConnection();
        c.setGroup(group);
        c.setIsolationLevel(own);
        return c;
    }

    private static ServerConnection teamwork(String group, IsolationLevel own) {
        ServerConnection c = connection(group, own);
        c.setConnectionSource(ConnectionSource.TEAMWORK);
        return c;
    }

    private static Function<String, IsolationLevel> folders(Map<String, IsolationLevel> levels) {
        return levels::get;
    }

    @Test
    void withNothingSetTheDefaultIsNone() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(null, group -> null, connection(null, null), null);
        assertThat(r.level()).isEqualTo(IsolationLevel.NONE);
        assertThat(r.source()).isEqualTo(IsolationSettings.Source.GLOBAL);
    }

    @Test
    void theGlobalLevelAppliesWithoutAFolderOrOwnLevel() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(
            IsolationLevel.PROCESS, group -> null, connection("Work", null), null);
        assertThat(r.level()).isEqualTo(IsolationLevel.PROCESS);
        assertThat(r.source()).isEqualTo(IsolationSettings.Source.GLOBAL);
    }

    @Test
    void aFolderAboveIsInherited() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.NONE,
            folders(Map.of("Customers", IsolationLevel.SANDBOX)), connection("Customers/ACME/DB", null), null);
        assertThat(r.level()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(r.source()).isEqualTo(IsolationSettings.Source.FOLDER);
        assertThat(r.folderPath()).isEqualTo("Customers");
    }

    @Test
    void theNearestFolderWins() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.NONE,
            folders(Map.of("Customers", IsolationLevel.SANDBOX, "Customers/Lab", IsolationLevel.NONE)),
            connection("Customers/Lab", null), null);
        assertThat(r.level()).isEqualTo(IsolationLevel.NONE);
        assertThat(r.folderPath()).isEqualTo("Customers/Lab");
    }

    @Test
    void anOwnConnectionRelaxesItsFolder() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.NONE,
            folders(Map.of("Customers", IsolationLevel.SANDBOX)), connection("Customers", IsolationLevel.PROCESS), null);
        assertThat(r.level()).isEqualTo(IsolationLevel.PROCESS);
        assertThat(r.source()).isEqualTo(IsolationSettings.Source.CONNECTION);
    }

    @Test
    void aTeamworkConnectionOnlyTightens() {
        IsolationSettings.Resolution relaxed = IsolationSettings.resolve(IsolationLevel.PROCESS,
            group -> null, teamwork(null, IsolationLevel.NONE), null);
        assertThat(relaxed.level()).isEqualTo(IsolationLevel.PROCESS);
        assertThat(relaxed.source()).isEqualTo(IsolationSettings.Source.GLOBAL);

        IsolationSettings.Resolution stricter = IsolationSettings.resolve(IsolationLevel.PROCESS,
            group -> null, teamwork(null, IsolationLevel.SANDBOX), null);
        assertThat(stricter.level()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(stricter.source()).isEqualTo(IsolationSettings.Source.CONNECTION);
    }

    @Test
    void aFolderCannotRelaxATeamworkConnectionBelowSettings() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.PROCESS,
            folders(Map.of("Shared", IsolationLevel.NONE)), teamwork("Shared", null), null);
        assertThat(r.level()).isEqualTo(IsolationLevel.PROCESS);
    }

    @Test
    void thePolicyMinimumWinsOverEverything() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.NONE,
            folders(Map.of("Lab", IsolationLevel.NONE)), connection("Lab", IsolationLevel.NONE), IsolationLevel.SANDBOX);
        assertThat(r.level()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(r.enforcedByPolicy()).isTrue();
    }

    @Test
    void aChoiceAboveThePolicyMinimumKeepsItsSource() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.NONE, group -> null,
            connection(null, IsolationLevel.SANDBOX), IsolationLevel.PROCESS);
        assertThat(r.level()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(r.source()).isEqualTo(IsolationSettings.Source.CONNECTION);
    }

    @Test
    void whatEachProtocolCanGet() {
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.LOCAL_SHELL, false, false))
            .isEqualTo(IsolationLevel.SANDBOX);
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.MOSH_CLIENT, false, false))
            .isEqualTo(IsolationLevel.SANDBOX);
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.MOSH, true, true))
            .isEqualTo(IsolationLevel.SANDBOX);
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.MOSH, false, false))
            .isEqualTo(IsolationLevel.NONE);
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.SSH_TCP, false, false))
            .isEqualTo(IsolationLevel.NONE);
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.SSH_TCP, true, false))
            .isEqualTo(IsolationLevel.PROCESS);
        assertThat(IsolationSettings.strongestSupported(ConnectionProtocol.SSH_TCP, true, true))
            .isEqualTo(IsolationLevel.SANDBOX);
    }

    @Test
    void aRequestIsEnforcedOnlyWhenThePolicyDemandsItsLevel() {
        assertThat(new IsolationRequest(IsolationLevel.SANDBOX, IsolationLevel.SANDBOX).enforced()).isTrue();
        assertThat(new IsolationRequest(IsolationLevel.SANDBOX, IsolationLevel.PROCESS).enforced()).isFalse();
        assertThat(new IsolationRequest(IsolationLevel.PROCESS, IsolationLevel.PROCESS).enforced()).isTrue();
        assertThat(new IsolationRequest(IsolationLevel.SANDBOX, null).enforced()).isFalse();
        assertThat(IsolationRequest.NONE.enforced()).isFalse();
    }

    @Test
    void levelIdsRoundTripAndUnknownIdsAreNull() {
        for (IsolationLevel level : IsolationLevel.values()) {
            assertThat(IsolationLevel.parseId(" " + level.id().toUpperCase() + " ")).isEqualTo(level);
        }
        assertThat(IsolationLevel.parseId("container")).isNull();
        assertThat(IsolationLevel.fromId("container")).isEqualTo(IsolationLevel.NONE);
        assertThat(IsolationLevel.atLeastLevels(IsolationLevel.PROCESS))
            .containsExactly(IsolationLevel.PROCESS, IsolationLevel.SANDBOX).inOrder();
    }

    @Test
    void aTabShowsItsWeakestPaneButAlwaysAMissingSandbox() {
        assertThat(IsolationState.aggregate(List.of())).isEqualTo(IsolationState.NONE);
        assertThat(IsolationState.aggregate(List.of(IsolationState.SANDBOXED, IsolationState.PROCESS)))
            .isEqualTo(IsolationState.PROCESS);
        assertThat(IsolationState.aggregate(List.of(IsolationState.SANDBOXED, IsolationState.NONE)))
            .isEqualTo(IsolationState.NONE);
        assertThat(IsolationState.aggregate(List.of(IsolationState.NONE, IsolationState.DEGRADED)))
            .isEqualTo(IsolationState.DEGRADED);
        assertThat(IsolationState.aggregate(List.of(IsolationState.SANDBOXED, IsolationState.SANDBOXED)))
            .isEqualTo(IsolationState.SANDBOXED);
    }

    @Test
    void aRequestedLevelReplacesTheConnectionsOwnChoice() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.PROCESS,
            folders(Map.of("Customers", IsolationLevel.SANDBOX)), connection("Customers", IsolationLevel.SANDBOX),
            null, IsolationLevel.NONE);
        assertThat(r.level()).isEqualTo(IsolationLevel.NONE);
        assertThat(r.source()).isEqualTo(IsolationSettings.Source.REQUEST);
    }

    @Test
    void aRequestedLevelOnlyTightensATeamworkConnection() {
        IsolationSettings.Resolution relaxed = IsolationSettings.resolve(IsolationLevel.PROCESS,
            group -> null, teamwork(null, null), null, IsolationLevel.NONE);
        assertThat(relaxed.level()).isEqualTo(IsolationLevel.PROCESS);
        assertThat(relaxed.source()).isEqualTo(IsolationSettings.Source.GLOBAL);
        IsolationSettings.Resolution tightened = IsolationSettings.resolve(IsolationLevel.PROCESS,
            group -> null, teamwork(null, null), null, IsolationLevel.SANDBOX);
        assertThat(tightened.level()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(tightened.source()).isEqualTo(IsolationSettings.Source.REQUEST);
    }

    @Test
    void theOrganizationsMinimumStaysAboveARequestedLevel() {
        IsolationSettings.Resolution r = IsolationSettings.resolve(IsolationLevel.NONE, group -> null,
            connection(null, null), IsolationLevel.PROCESS, IsolationLevel.NONE);
        assertThat(r.level()).isEqualTo(IsolationLevel.PROCESS);
        assertThat(r.enforcedByPolicy()).isTrue();
    }
}
