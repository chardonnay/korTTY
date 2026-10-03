package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.ui.ClosedTabHistory.ClosedTab;
import de.kortty.ui.ClosedTabHistory.Entry;
import de.kortty.ui.ClosedTabHistory.Target;
import de.kortty.ui.ConnectionAuthResolver.Status;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The history behind Reopen Closed Tab and Recently Closed: newest first, capped, never holding a
 * temporary SSH key, and reopening the saved connection rather than the remembered copy when there
 * is one. Plain data, no JavaFX toolkit.
 */
class ClosedTabHistoryTest {

    private static final String TEMPORARY_KEY =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----";

    // ---- order and cap -----------------------------------------------------------------------

    @Test
    void theNewestCloseComesBackFirst() {
        ClosedTabHistory history = new ClosedTabHistory();
        Entry first = entry("first");
        Entry second = entry("second");
        Entry third = entry("third");

        history.push(first);
        history.push(second);
        history.push(third);

        assertThat(history.entries()).containsExactly(third, second, first).inOrder();
        assertThat(history.latest()).hasValue(third);
        assertThat(history.pollLatest()).hasValue(third);
        assertThat(history.pollLatest()).hasValue(second);
        assertThat(history.pollLatest()).hasValue(first);
        assertThat(history.isEmpty()).isTrue();
    }

    @Test
    void anEmptyHistoryHasNothingToReopen() {
        ClosedTabHistory history = new ClosedTabHistory();

        assertThat(history.latest()).isEmpty();
        assertThat(history.pollLatest()).isEmpty();
        assertThat(history.entries()).isEmpty();
        assertThat(history.size()).isEqualTo(0);
    }

    @Test
    void theOldestCloseIsForgottenBeyondTheCap() {
        ClosedTabHistory history = new ClosedTabHistory();
        List<Entry> pushed = new ArrayList<>();
        for (int i = 0; i <= ClosedTabHistory.MAX_ENTRIES; i++) {
            Entry entry = entry("tab-" + i);
            pushed.add(entry);
            history.push(entry);
        }

        assertThat(ClosedTabHistory.MAX_ENTRIES).isEqualTo(25);
        assertThat(history.size()).isEqualTo(ClosedTabHistory.MAX_ENTRIES);
        assertThat(history.latest()).hasValue(pushed.get(ClosedTabHistory.MAX_ENTRIES));
        assertWithMessage("the 26th close pushes out the first")
            .that(history.entries()).doesNotContain(pushed.get(0));
        assertThat(history.entries().get(ClosedTabHistory.MAX_ENTRIES - 1)).isSameInstanceAs(pushed.get(1));
    }

    @Test
    void anEntryIsTakenByIdentityAndPutBackWhereItWas() {
        ClosedTabHistory history = new ClosedTabHistory();
        ClosedTab web = closedTab(connection("web"));
        Entry older = new Entry(List.of(web), false);
        Entry equalButNewer = new Entry(List.of(web), false);
        Entry newest = entry("newest");
        history.push(older);
        history.push(equalButNewer);
        history.push(newest);
        assertThat(older).isEqualTo(equalButNewer);

        int position = history.take(older);

        assertWithMessage("records compare by value; the menu item's own entry is the one taken")
            .that(position).isEqualTo(2);
        assertThat(history.entries()).containsExactly(newest, equalButNewer).inOrder();
        assertThat(history.entries().get(1)).isSameInstanceAs(equalButNewer);
        assertWithMessage("an entry another window reopened already is no longer there")
            .that(history.take(older)).isEqualTo(-1);

        history.putBack(position, older);
        assertThat(history.entries().get(2)).isSameInstanceAs(older);
    }

    @Test
    void putBackLandsAtTheEndWhenTheHistoryShrankAndRespectsTheCap() {
        ClosedTabHistory history = new ClosedTabHistory();
        Entry kept = entry("kept");
        history.push(kept);

        Entry cancelled = entry("cancelled");
        history.putBack(7, cancelled);
        assertThat(history.entries()).containsExactly(kept, cancelled).inOrder();

        ClosedTabHistory full = new ClosedTabHistory();
        for (int i = 0; i < ClosedTabHistory.MAX_ENTRIES; i++) {
            full.push(entry("tab-" + i));
        }
        Entry back = entry("back");
        full.putBack(0, back);
        assertThat(full.size()).isEqualTo(ClosedTabHistory.MAX_ENTRIES);
        assertThat(full.latest()).hasValue(back);
    }

    @Test
    void clearForgetsEverything() {
        ClosedTabHistory history = new ClosedTabHistory();
        history.push(entry("a"));
        history.push(entry("b"));

        history.clear();

        assertThat(history.isEmpty()).isTrue();
        assertThat(history.latest()).isEmpty();
    }

    @Test
    void anEntryHoldsAtLeastOneTabAndItsOwnCopyOfTheList() {
        assertThrows(IllegalArgumentException.class, () -> new Entry(List.of(), false));

        List<ClosedTab> tabs = new ArrayList<>(List.of(closedTab(connection("web"))));
        Entry entry = new Entry(tabs, true);
        tabs.add(closedTab(connection("db")));

        assertThat(entry.tabs()).hasSize(1);
        assertThrows(UnsupportedOperationException.class, () -> entry.tabs().clear());
        assertThat(entry.withTabs(List.of(closedTab(connection("db")))).window()).isTrue();
    }

    // ---- sanitize ----------------------------------------------------------------------------

    @Test
    void aClosedTabKeepsNoTemporaryKeyButRemembersItUsedOne() {
        ServerConnection quickConnect = connection("quick");
        quickConnect.setAuthMethod(AuthMethod.PUBLIC_KEY);
        quickConnect.setTemporaryKeyContent(TEMPORARY_KEY);
        quickConnect.setTemporaryKeyExpirationMinutes(15L);
        quickConnect.setTemporaryKeyPermanent(true);
        quickConnect.setPrivateKeyPath("TEMPORARY:" + TEMPORARY_KEY);

        ClosedTab closed = ClosedTab.capture(quickConnect, false, null, null, null, null);

        ServerConnection snapshot = closed.snapshot();
        assertThat(snapshot).isNotSameInstanceAs(quickConnect);
        assertThat(snapshot.getTemporaryKeyContent()).isNull();
        assertThat(snapshot.getTemporaryKeyExpirationMinutes()).isNull();
        assertThat(snapshot.isTemporaryKeyPermanent()).isFalse();
        assertThat(snapshot.getPrivateKeyPath()).isNull();
        assertThat(closed.usedTemporaryKey()).isTrue();
        assertThat(closed.connectionId()).isEqualTo(quickConnect.getId());
        assertWithMessage("the tab's own connection is left as it was")
            .that(quickConnect.getTemporaryKeyContent()).isEqualTo(TEMPORARY_KEY);
    }

    @Test
    void aTabOpenedWithAKeyOfItsOwnCountsAsATemporaryKeyTab() {
        ClosedTab closed = ClosedTab.capture(connection("web"), true, null, null, null, null);

        assertThat(closed.usedTemporaryKey()).isTrue();
    }

    @Test
    void anOrdinaryKeyPathTheEncryptedPasswordAndTheCredentialStay() {
        ServerConnection web = connection("web");
        web.setPrivateKeyPath("/home/me/.ssh/id_ed25519");
        web.setEncryptedPassword("enc:s3cret");
        web.setCredentialId("cred-1");

        ClosedTab closed = ClosedTab.capture(web, false, "prod", "Web 1", "mother", 1.5);

        assertThat(closed.usedTemporaryKey()).isFalse();
        assertThat(closed.snapshot().getPrivateKeyPath()).isEqualTo("/home/me/.ssh/id_ed25519");
        assertThat(closed.snapshot().getEncryptedPassword()).isEqualTo("enc:s3cret");
        assertThat(closed.snapshot().getCredentialId()).isEqualTo("cred-1");
        assertThat(closed.tabGroup()).isEqualTo("prod");
        assertThat(closed.customTitle()).isEqualTo("Web 1");
        assertThat(closed.terminalEffectPluginId()).isEqualTo("mother");
        assertThat(closed.terminalEffectSpeed()).isEqualTo(1.5);
    }

    @Test
    void laterChangesToTheTabsConnectionDoNotReachTheEntry() {
        ServerConnection web = connection("web");
        ClosedTab closed = ClosedTab.capture(web, false, null, null, null, null);

        web.setHost("elsewhere");
        web.setTemporaryKeyContent(TEMPORARY_KEY);

        assertThat(closed.snapshot().getHost()).isEqualTo("web");
        assertThat(closed.snapshot().getTemporaryKeyContent()).isNull();
    }

    @Test
    void noEffectMeansNoEffectSpeed() {
        ClosedTab closed = ClosedTab.capture(connection("web"), false, null, null, null, 2.0);

        assertThat(closed.terminalEffectPluginId()).isNull();
        assertThat(closed.terminalEffectSpeed()).isNull();
    }

    // ---- resolveConnection -------------------------------------------------------------------

    @Test
    void reopenPrefersTheSavedConnectionSoEditsApply() {
        ServerConnection atClose = connection("web");
        ServerConnection saved = connection("web-edited");
        ClosedTab closed = ClosedTab.capture(atClose, true, null, null, null, null);

        Target target = ClosedTabHistory.resolveConnection(closed, Map.of(atClose.getId(), saved)::get);

        assertThat(target.connection()).isSameInstanceAs(saved);
        assertWithMessage("the saved connection's own login applies; no key is asked for up front")
            .that(target.needsNewTemporaryKey()).isFalse();
    }

    @Test
    void anUnsavedConnectionReopensFromAFreshCopyOfTheSnapshot() {
        ClosedTab closed = ClosedTab.capture(connection("quick"), false, null, null, null, null);

        Target target = ClosedTabHistory.resolveConnection(closed, id -> null);

        assertThat(target.connection()).isNotSameInstanceAs(closed.snapshot());
        assertThat(target.connection().getHost()).isEqualTo("quick");
        assertThat(target.connection().getId()).isEqualTo(closed.connectionId());
        assertThat(target.needsNewTemporaryKey()).isFalse();

        // A new temporary key set during the reopen stays on the copy, never in the history.
        target.connection().setTemporaryKeyContent(TEMPORARY_KEY);
        assertThat(closed.snapshot().getTemporaryKeyContent()).isNull();
        assertThat(ClosedTabHistory.resolveConnection(closed, id -> null).connection().getTemporaryKeyContent())
            .isNull();
    }

    @Test
    void anUnsavedTemporaryKeySessionAsksForANewKey() {
        ServerConnection quickConnect = connection("quick");
        quickConnect.setAuthMethod(AuthMethod.PUBLIC_KEY);
        quickConnect.setTemporaryKeyContent(TEMPORARY_KEY);
        quickConnect.setPrivateKeyPath("TEMPORARY:" + TEMPORARY_KEY);
        ClosedTab closed = ClosedTab.capture(quickConnect, false, null, null, null, null);

        Target target = ClosedTabHistory.resolveConnection(closed, id -> null);

        assertThat(target.needsNewTemporaryKey()).isTrue();
        assertThat(ConnectionAuthResolver.carriesTemporaryKey(target.connection())).isFalse();
    }

    @Test
    void aTabWithoutAnIdReopensFromTheSnapshot() {
        ClosedTab closed = new ClosedTab(null, connection("shell"), false, null, null, null, null);

        Target target = ClosedTabHistory.resolveConnection(closed, id -> {
            throw new AssertionError("no lookup without an id");
        });

        assertThat(target.connection().getHost()).isEqualTo("shell");
    }

    @Test
    void onlyACancelledQuestionKeepsATabInTheHistory() {
        for (Status status : Status.values()) {
            boolean keeps = switch (status) {
                case NEEDS_PASSWORD, NEEDS_TEMP_KEY, NEEDS_UNLOCK -> true;
                case READY, BLOCKED, MISSING -> false;
            };
            assertWithMessage(status.name()).that(ClosedTabHistory.keepsEntry(status)).isEqualTo(keeps);
        }
    }

    // ---- labels ------------------------------------------------------------------------------

    @Test
    void theMenuShowsTheTabsOwnNameElseTheConnections() {
        ServerConnection web = connection("web");
        web.setName("Web server");

        assertThat(ClosedTab.capture(web, false, null, "Deploy", null, null).label()).isEqualTo("Deploy");
        assertThat(ClosedTab.capture(web, false, null, null, null, null).label()).isEqualTo("Web server");
        assertThat(ClosedTab.capture(connection("db"), false, null, " ", null, null).label()).isEqualTo("me@db");
    }

    @Test
    void aSharedConnectionsNameCannotReorderOrBreakTheMenu() {
        ServerConnection shared = connection("web");
        shared.setName("prod‮txt.exe\u0007\nnext");

        String label = ClosedTab.capture(shared, false, null, null, null, null).label();

        assertThat(label).isEqualTo("prodtxt.exe next");
        ServerConnection longName = connection("web");
        longName.setName("x".repeat(200));
        assertThat(ClosedTab.capture(longName, false, null, null, null, null).label())
            .hasLength(ClosedTabHistory.LABEL_LENGTH);
    }

    @Test
    void severalTabsAreNamedUpToTheLimitAndCountedBeyondIt() {
        Entry two = new Entry(List.of(titled("a"), titled("b")), false);
        Entry five = new Entry(List.of(titled("a"), titled("b"), titled("c"), titled("d"), titled("e")), true);

        assertThat(new Entry(List.of(titled("solo")), false).names(3)).isEqualTo("solo");
        assertThat(entry("web").names(3)).isEqualTo("me@web");
        assertThat(two.names(3)).isEqualTo("a, b");
        assertThat(five.names(3)).isEqualTo("a, b, c, +2");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Entry entry(String host) {
        return new Entry(List.of(closedTab(connection(host))), false);
    }

    private static ClosedTab titled(String title) {
        return ClosedTab.capture(connection("host"), false, null, title, null, null);
    }

    private static ClosedTab closedTab(ServerConnection connection) {
        return ClosedTab.capture(connection, false, null, null, null, null);
    }

    private static ServerConnection connection(String host) {
        ServerConnection connection = new ServerConnection(null, host, 22, "me");
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        connection.setAuthMethod(AuthMethod.PASSWORD);
        return connection;
    }
}
