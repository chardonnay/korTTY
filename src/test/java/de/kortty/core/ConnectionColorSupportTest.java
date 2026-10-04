package de.kortty.core;

import de.kortty.core.ConnectionColorSupport.Family;
import de.kortty.core.ConnectionColorSupport.Source;
import de.kortty.core.ConnectionColorSupport.TabColor;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The tab color of a connection: only hex colors are accepted (the value can come from a shared
 * teamwork file), the saved connection wins over a tab's copy of it, a connection without a color
 * of its own takes the color of its group (or the nearest group above it with one), then the color
 * of its stored credential's environment, and every color has a family
 * name for the tooltip, so the color is never the only cue.
 */
public class ConnectionColorSupportTest {

    @Test
    void normalizeHexAcceptsShortAndLongHexInAnyCase() {
        assertThat(ConnectionColorSupport.normalizeHex("#abc")).isEqualTo("#AABBCC");
        assertThat(ConnectionColorSupport.normalizeHex("#d32f2f")).isEqualTo("#D32F2F");
        assertThat(ConnectionColorSupport.normalizeHex("  #1976D2\n")).isEqualTo("#1976D2");
    }

    @Test
    void normalizeHexRejectsEverythingElse() {
        for (String value : new String[] {
                null, "", "   ", "red", "#12345", "#1234567", "D32F2F", "#GGGGGG", "#d32f2f80",
                "rgb(211,47,47)", "#D32F2F; -fx-background-image: url(x)", "#D32F2F\n#000000"}) {
            assertWithMessage("normalizeHex(%s)", value)
                    .that(ConnectionColorSupport.normalizeHex(value)).isNull();
        }
    }

    @Test
    void everyPresetIsANormalizedHexColor() {
        assertThat(ConnectionColorSupport.PRESETS).isNotEmpty();
        for (String preset : ConnectionColorSupport.PRESETS) {
            assertThat(ConnectionColorSupport.normalizeHex(preset)).isEqualTo(preset);
        }
    }

    @Test
    void theSavedConnectionWinsSoEditsReachTabsOpenedFromACopy() {
        ServerConnection saved = connection("prod-db", "#D32F2F");
        // Quick Connect and the teamwork default login open a copy that keeps the id.
        ServerConnection tabCopy = ServerConnection.copyForAuth(saved);
        tabCopy.setTabColor(null);
        Map<String, ServerConnection> store = new HashMap<>(Map.of(saved.getId(), saved));

        assertThat(ConnectionColorSupport.tabColorOf(tabCopy, store::get)).isEqualTo("#D32F2F");

        saved.setTabColor("#388e3c");
        assertThat(ConnectionColorSupport.tabColorOf(tabCopy, store::get)).isEqualTo("#388E3C");

        saved.setTabColor(null);
        tabCopy.setTabColor("#1976D2");
        assertWithMessage("a color removed in the Connection Manager must disappear from the tab")
                .that(ConnectionColorSupport.tabColorOf(tabCopy, store::get)).isNull();
    }

    @Test
    void aConnectionThatIsNotSavedHereUsesItsOwnColor() {
        ServerConnection teamwork = connection("shared-db", "#7b1fa2");

        assertThat(ConnectionColorSupport.tabColorOf(teamwork, id -> null)).isEqualTo("#7B1FA2");
        assertThat(ConnectionColorSupport.tabColorOf(teamwork, null)).isEqualTo("#7B1FA2");
    }

    @Test
    void noConnectionOrAnInvalidStoredValueMeansNoColor() {
        ServerConnection spoofed = connection("shared", "red; -fx-background-color: #8B0000");

        assertThat(ConnectionColorSupport.tabColorOf(null, id -> null)).isNull();
        assertThat(ConnectionColorSupport.tabColorOf(spoofed, id -> null)).isNull();
        assertThat(ConnectionColorSupport.tabColorOf(connection("plain", null), id -> null)).isNull();
    }

    // ---- environment fallback -----------------------------------------------------------------

    /** Credentials by id with their environment, and environment colors, as the stores hold them. */
    private static final Map<String, String> CREDENTIAL_ENVIRONMENTS = Map.of(
            "cred-prod", "PRODUCTION",
            "cred-lab", "custom-lab",
            "cred-test", "TEST");
    private static final Map<String, String> ENVIRONMENT_COLORS = Map.of(
            "PRODUCTION", "#d32f2f",
            "custom-lab", "#7B1FA2",
            "TEST", "yellow; -fx-background-color: #8B0000");

    @Test
    void theConnectionsOwnColorComesBeforeTheEnvironmentColor() {
        ServerConnection colored = connection("prod-db", "#388E3C");
        colored.setCredentialId("cred-prod");

        TabColor color = ConnectionColorSupport.effectiveTabColor(colored, id -> null,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get);

        assertThat(color).isEqualTo(new TabColor("#388E3C", Source.CONNECTION, null));
    }

    @Test
    void aConnectionWithoutAColorTakesItsCredentialsEnvironmentColor() {
        ServerConnection plain = connection("prod-db", null);
        plain.setCredentialId("cred-prod");
        ServerConnection lab = connection("lab-box", null);
        lab.setCredentialId("cred-lab");

        assertThat(ConnectionColorSupport.effectiveTabColor(plain, id -> null,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get))
                .isEqualTo(new TabColor("#D32F2F", Source.ENVIRONMENT, "PRODUCTION"));
        assertThat(ConnectionColorSupport.effectiveTabColor(lab, id -> null,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get))
                .isEqualTo(new TabColor("#7B1FA2", Source.ENVIRONMENT, "custom-lab"));
    }

    @Test
    void noCredentialAMissingCredentialOrAnEnvironmentWithoutAColorMeanNoColor() {
        ServerConnection keyAuth = connection("key-host", null);
        ServerConnection blankCredential = connection("blank", null);
        blankCredential.setCredentialId(" ");
        ServerConnection deletedCredential = connection("orphan", null);
        deletedCredential.setCredentialId("cred-deleted");
        ServerConnection uncolored = connection("staging", null);
        uncolored.setCredentialId("cred-staging");
        Map<String, String> withStaging = new HashMap<>(CREDENTIAL_ENVIRONMENTS);
        withStaging.put("cred-staging", "STAGING");

        for (ServerConnection connection : List.of(keyAuth, blankCredential, deletedCredential, uncolored)) {
            assertWithMessage("tab color of %s", connection.getName())
                    .that(ConnectionColorSupport.effectiveTabColor(connection, id -> null,
                            withStaging::get, ENVIRONMENT_COLORS::get))
                    .isNull();
        }
        assertThat(ConnectionColorSupport.effectiveTabColor(null, id -> null,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get)).isNull();
    }

    @Test
    void anInvalidStoredEnvironmentColorIsIgnored() {
        ServerConnection test = connection("test-box", null);
        test.setCredentialId("cred-test");

        assertThat(ConnectionColorSupport.effectiveTabColor(test, id -> null,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get)).isNull();
    }

    @Test
    void theEnvironmentComesFromTheCredentialTheTabSignedInWith() {
        // A teamwork connection names no credential; the teamwork default login fills one into the
        // tab's copy, which keeps the id. The saved connection must not take it away again.
        ServerConnection shared = connection("shared-db", null);
        ServerConnection tabCopy = ServerConnection.copyForAuth(shared);
        tabCopy.setCredentialId("cred-prod");
        Map<String, ServerConnection> store = new HashMap<>(Map.of(shared.getId(), shared));

        assertThat(ConnectionColorSupport.effectiveTabColor(tabCopy, store::get,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get))
                .isEqualTo(new TabColor("#D32F2F", Source.ENVIRONMENT, "PRODUCTION"));

        shared.setTabColor("#1976D2");
        assertWithMessage("a color set on the saved connection still comes first")
                .that(ConnectionColorSupport.effectiveTabColor(tabCopy, store::get,
                        CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get))
                .isEqualTo(new TabColor("#1976D2", Source.CONNECTION, null));
    }

    @Test
    void withoutTheCredentialOrEnvironmentStoresOnlyTheConnectionColorCounts() {
        ServerConnection plain = connection("prod-db", null);
        plain.setCredentialId("cred-prod");

        assertThat(ConnectionColorSupport.effectiveTabColor(plain, id -> null, null, ENVIRONMENT_COLORS::get)).isNull();
        assertThat(ConnectionColorSupport.effectiveTabColor(plain, id -> null, CREDENTIAL_ENVIRONMENTS::get, null)).isNull();
        assertThat(ConnectionColorSupport.effectiveTabColor(connection("own", "#616161"), null, null, null))
                .isEqualTo(new TabColor("#616161", Source.CONNECTION, null));
    }

    // ---- group colors ------------------------------------------------------------------------

    /** Folder colors as the global settings hold them, by group path. */
    private static final Map<String, String> GROUP_COLORS = Map.of(
            "Production", "#d32f2f",
            "Production/Lab", "#7B1FA2",
            "Production/Lab/Broken", "red; -fx-background-color: #8B0000",
            "Development", "#388E3C");

    private static TabColor resolve(ServerConnection connection, Function<String, ServerConnection> savedById) {
        return ConnectionColorSupport.effectiveTabColor(connection, savedById, GROUP_COLORS::get,
                CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get);
    }

    @Test
    void theOrderIsConnectionThenGroupThenCredentialEnvironment() {
        ServerConnection connection = connection("db-1", null);
        connection.setGroup("Development/DB");
        connection.setCredentialId("cred-prod");

        assertWithMessage("the group color comes before the credential environment's")
                .that(resolve(connection, id -> null))
                .isEqualTo(new TabColor("#388E3C", Source.GROUP, null, "Development"));

        connection.setTabColor("#1976d2");
        assertWithMessage("a color set on the connection comes first")
                .that(resolve(connection, id -> null))
                .isEqualTo(new TabColor("#1976D2", Source.CONNECTION, null));

        connection.setTabColor(null);
        connection.setGroup("Staging");
        assertWithMessage("a group without a color leaves the credential environment's")
                .that(resolve(connection, id -> null))
                .isEqualTo(new TabColor("#D32F2F", Source.ENVIRONMENT, "PRODUCTION"));
    }

    @Test
    void aKeyAuthProductionHostWithoutACredentialTakesItsGroupColor() {
        ServerConnection keyHost = connection("web-1", null);
        keyHost.setGroup("Production");

        assertThat(resolve(keyHost, id -> null))
                .isEqualTo(new TabColor("#D32F2F", Source.GROUP, null, "Production"));
    }

    @Test
    void theNearestGroupWithAColorWinsAndNamesItself() {
        ServerConnection lab = connection("lab-1", null);
        lab.setGroup("Production/Lab/EU");
        ServerConnection broken = connection("broken-1", null);
        broken.setGroup("Production/Lab/Broken");
        ServerConnection other = connection("other-1", null);
        other.setGroup("Production/Web");

        assertThat(resolve(lab, id -> null)).isEqualTo(new TabColor("#7B1FA2", Source.GROUP, null, "Production/Lab"));
        assertWithMessage("a stored value that is not a hex color is skipped and the search goes up")
                .that(resolve(broken, id -> null))
                .isEqualTo(new TabColor("#7B1FA2", Source.GROUP, null, "Production/Lab"));
        assertThat(resolve(other, id -> null)).isEqualTo(new TabColor("#D32F2F", Source.GROUP, null, "Production"));
    }

    @Test
    void groupsAreMatchedByWholeTrimmedSegmentsAndCaseMatters() {
        ServerConnection spaced = connection("spaced", null);
        spaced.setGroup(" Production / DB ");
        ServerConnection lookalike = connection("lookalike", null);
        lookalike.setGroup("Production2");
        ServerConnection lowerCase = connection("lower", null);
        lowerCase.setGroup("production");

        assertThat(resolve(spaced, id -> null)).isEqualTo(new TabColor("#D32F2F", Source.GROUP, null, "Production"));
        assertThat(resolve(lookalike, id -> null)).isNull();
        assertThat(resolve(lowerCase, id -> null)).isNull();
    }

    @Test
    void theSavedConnectionsGroupWinsSoMovingItRecolorsItsOpenTabs() {
        ServerConnection saved = connection("db-prod", null);
        saved.setGroup("Production");
        ServerConnection tabCopy = ServerConnection.copyForAuth(saved);
        Map<String, ServerConnection> store = new HashMap<>(Map.of(saved.getId(), saved));

        assertThat(resolve(tabCopy, store::get)).isEqualTo(new TabColor("#D32F2F", Source.GROUP, null, "Production"));

        saved.setGroup("Development");
        assertThat(resolve(tabCopy, store::get)).isEqualTo(new TabColor("#388E3C", Source.GROUP, null, "Development"));

        saved.setGroup(null);
        assertWithMessage("a connection moved out of every group loses the group color")
                .that(resolve(tabCopy, store::get)).isNull();
    }

    @Test
    void anUnsavedQuickConnectSessionUsesItsOwnGroup() {
        ServerConnection quick = connection("quick", null);
        quick.setGroup("Production/Lab");

        assertThat(resolve(quick, id -> null)).isEqualTo(new TabColor("#7B1FA2", Source.GROUP, null, "Production/Lab"));
    }

    @Test
    void teamworkConnectionsNeverTakeAGroupColor() {
        ServerConnection shared = connection("shared-db", null);
        shared.setGroup("Production");
        shared.setConnectionSource(ConnectionSource.TEAMWORK);

        assertWithMessage("the shared file decides the group, so it must not pick one of your colors")
                .that(resolve(shared, id -> null)).isNull();

        shared.setCredentialId("cred-lab");
        assertWithMessage("the credential environment still applies")
                .that(resolve(shared, id -> null))
                .isEqualTo(new TabColor("#7B1FA2", Source.ENVIRONMENT, "custom-lab"));

        ServerConnection local = connection("local", null);
        ServerConnection sharedWithTheSameId = connection("shared", null);
        sharedWithTheSameId.setId(local.getId());
        sharedWithTheSameId.setGroup("Production");
        sharedWithTheSameId.setConnectionSource(ConnectionSource.TEAMWORK);
        local.setGroup("Production");
        assertThat(resolve(local, Map.of(local.getId(), sharedWithTheSameId)::get)).isNull();
    }

    @Test
    void withoutAGroupLookupOrAGroupThereIsNoGroupColor() {
        ServerConnection grouped = connection("grouped", null);
        grouped.setGroup("Production");
        ServerConnection ungrouped = connection("ungrouped", null);
        ungrouped.setGroup(" / ");

        assertWithMessage("the four-argument overload knows no group colors")
                .that(ConnectionColorSupport.effectiveTabColor(grouped, id -> null,
                        CREDENTIAL_ENVIRONMENTS::get, ENVIRONMENT_COLORS::get)).isNull();
        assertThat(ConnectionColorSupport.effectiveTabColor(grouped, id -> null, null, null, null)).isNull();
        assertThat(resolve(ungrouped, id -> null)).isNull();
        assertThat(new TabColor("#D32F2F", Source.CONNECTION, null).groupPath()).isNull();
    }

    @Test
    void thePresetsAreNamedAsTheyLook() {
        List<Family> expected = List.of(Family.RED, Family.ORANGE, Family.YELLOW, Family.GREEN,
                Family.BLUE, Family.PURPLE, Family.GRAY);

        for (int i = 0; i < expected.size(); i++) {
            String preset = ConnectionColorSupport.PRESETS.get(i);
            assertWithMessage("family of preset %s", preset)
                    .that(ConnectionColorSupport.family(preset)).isEqualTo(expected.get(i));
        }
    }

    @Test
    void familyCoversTheWholeColorWheelAndTheGrays() {
        assertThat(ConnectionColorSupport.family("#FF0000")).isEqualTo(Family.RED);
        assertThat(ConnectionColorSupport.family("#FF0030")).isEqualTo(Family.RED);
        assertThat(ConnectionColorSupport.family("#FF8000")).isEqualTo(Family.ORANGE);
        assertThat(ConnectionColorSupport.family("#FFFF00")).isEqualTo(Family.YELLOW);
        assertThat(ConnectionColorSupport.family("#00FF00")).isEqualTo(Family.GREEN);
        assertThat(ConnectionColorSupport.family("#00FFFF")).isEqualTo(Family.CYAN);
        assertThat(ConnectionColorSupport.family("#0000FF")).isEqualTo(Family.BLUE);
        assertThat(ConnectionColorSupport.family("#8000FF")).isEqualTo(Family.PURPLE);
        assertThat(ConnectionColorSupport.family("#FF00FF")).isEqualTo(Family.PINK);
        assertThat(ConnectionColorSupport.family("#000000")).isEqualTo(Family.BLACK);
        assertThat(ConnectionColorSupport.family("#1A0000")).isEqualTo(Family.BLACK);
        assertThat(ConnectionColorSupport.family("#FFFFFF")).isEqualTo(Family.WHITE);
        assertThat(ConnectionColorSupport.family("#808080")).isEqualTo(Family.GRAY);
        assertThat(ConnectionColorSupport.family("#fff")).isEqualTo(Family.WHITE);
    }

    @Test
    void familyOfAnInvalidValueIsNull() {
        assertThat(ConnectionColorSupport.family("red")).isNull();
        assertThat(ConnectionColorSupport.family(null)).isNull();
    }

    private static ServerConnection connection(String name, String tabColor) {
        ServerConnection connection = new ServerConnection(name, name + ".example.com", 22, "root");
        connection.setTabColor(tabColor);
        return connection;
    }
}
