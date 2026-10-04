package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.ui.ConnectionAuthResolver.Resolution;
import de.kortty.ui.ConnectionAuthResolver.Status;
import de.kortty.ui.TabRestoreTriage.Classification;
import de.kortty.ui.TabRestoreTriage.Outcome;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The list behind a window's restore bar: saved order, also for a tab that goes back in after a
 * cancelled question; what Connect… asks about first; which tabs share one question; when the bar
 * offers Connect… and Unlock Vault…; and its texts, which need no plural forms.
 */
class RestoreAttentionTest {

    private static final ServerConnection WEB = new ServerConnection("web", "web", 22, "me");
    private static final ServerConnection DB = new ServerConnection("db", "db", 22, "me");

    @Test
    void tabsKeepTheirSavedOrderAlsoWhenOneGoesBackIn() {
        RestoreAttention<String> list = new RestoreAttention<>();
        RestoreAttention.Item<String> third = item("c", 2, WEB, Status.NEEDS_PASSWORD);
        RestoreAttention.Item<String> first = item("a", 0, DB, Status.BLOCKED);
        RestoreAttention.Item<String> second = item("b", 1, WEB, Status.NEEDS_UNLOCK);
        list.add(third);
        list.add(first);
        list.add(second);

        assertThat(labels(list)).containsExactly("a", "b", "c").inOrder();

        assertThat(list.remove(second)).isTrue();
        assertThat(list.remove(second)).isFalse();
        list.add(second.with(Outcome.of(Resolution.needs(Status.NEEDS_PASSWORD, WEB))));
        assertThat(labels(list)).containsExactly("a", "b", "c").inOrder();
        assertThat(list.items().get(1).classification()).isEqualTo(Classification.NEEDS_CREDENTIALS);
    }

    @Test
    void theSameItemIsListedOnceAndAReadyTabNeverWaits() {
        RestoreAttention<String> list = new RestoreAttention<>();
        RestoreAttention.Item<String> web = item("web", 0, WEB, Status.NEEDS_PASSWORD);
        list.add(web);
        list.add(web);

        assertThat(list.items()).hasSize(1);
        assertThrows(IllegalArgumentException.class,
            () -> new RestoreAttention.Item<>("ready", 0, "ready", null, Outcome.LOCAL_FILE_READY));
    }

    @Test
    void connectAsksAboutTheFirstTabThatWaitsForTheUserAndSkipsTheListedOnes() {
        RestoreAttention<String> list = new RestoreAttention<>();
        list.add(item("blocked", 0, DB, Status.BLOCKED));
        list.add(new RestoreAttention.Item<>("gone", 1, "gone", null, Outcome.of(Resolution.missing())));
        RestoreAttention.Item<String> vault = item("vault", 2, WEB, Status.NEEDS_UNLOCK);
        list.add(vault);

        assertThat(list.nextToConnect()).hasValue(vault);
        assertThat(list.offersConnect()).isTrue();

        list.remove(vault);
        assertThat(list.nextToConnect()).isEmpty();
        assertThat(list.offersConnect()).isFalse();
        assertThat(list.isEmpty()).isFalse();
    }

    @Test
    void tabsOfTheSameConnectionShareOneQuestion() {
        RestoreAttention<String> list = new RestoreAttention<>();
        RestoreAttention.Item<String> terminal = item("terminal", 0, WEB, Status.NEEDS_PASSWORD);
        RestoreAttention.Item<String> sftp = item("sftp", 1, WEB, Status.NEEDS_PASSWORD);
        RestoreAttention.Item<String> other = item("other", 2, DB, Status.NEEDS_PASSWORD);
        RestoreAttention.Item<String> blockedWeb = item("blocked", 3, WEB, Status.BLOCKED);
        for (RestoreAttention.Item<String> item : List.of(terminal, sftp, other, blockedWeb)) {
            list.add(item);
        }

        assertThat(list.sameConnection(terminal)).containsExactly(sftp);
        assertThat(list.sameConnection(other)).isEmpty();
        assertThat(list.sameConnection(new RestoreAttention.Item<>("file", 9, "file", null,
            Outcome.LOCAL_FILE_MISSING))).isEmpty();
    }

    @Test
    void unlockIsOfferedOnlyForTabsWaitingForAStillLockedVault() {
        RestoreAttention<String> list = new RestoreAttention<>();
        list.add(item("web", 0, WEB, Status.NEEDS_PASSWORD));
        assertThat(list.offersUnlock(true)).isFalse();

        RestoreAttention.Item<String> vault = item("db", 1, DB, Status.NEEDS_UNLOCK);
        list.add(vault);
        assertThat(list.offersUnlock(true)).isTrue();
        assertThat(list.offersUnlock(false)).isFalse();
        assertThat(list.waiting(Classification.NEEDS_UNLOCK)).containsExactly(vault);
    }

    @Test
    void theSummaryCountsEachClassInTheOrderTheUserCanActOnThem() {
        RestoreAttention<String> list = new RestoreAttention<>();
        list.add(new RestoreAttention.Item<>("gone", 0, "gone", null, Outcome.LOCAL_FILE_MISSING));
        list.add(item("blocked", 1, DB, Status.BLOCKED));
        list.add(item("vault", 2, WEB, Status.NEEDS_UNLOCK));
        list.add(item("password", 3, WEB, Status.NEEDS_PASSWORD));
        list.add(item("key", 4, DB, Status.NEEDS_TEMP_KEY));

        assertThat(list.summary(RestoreAttentionTest::texts)).isEqualTo(
            "[session.restore.attention|[session.restore.count.credentials|2] · [session.restore.count.unlock|1]"
                + " · [session.restore.count.blocked|1] · [session.restore.count.missing|1]]");

        RestoreAttention<String> onlyVault = new RestoreAttention<>();
        onlyVault.add(item("vault", 0, WEB, Status.NEEDS_UNLOCK));
        assertThat(onlyVault.summary(RestoreAttentionTest::texts))
            .isEqualTo("[session.restore.attention|[session.restore.count.unlock|1]]");
    }

    @Test
    void eachDetailsLineNamesTheTabAndWhyWithTheBlockedTarget() {
        RestoreAttention<String> list = new RestoreAttention<>();
        RestoreAttention.Item<String> blocked = new RestoreAttention.Item<>("prod", 0, "prod", DB.getId(),
            Outcome.of(Resolution.blocked(DB, "prod.example:22")));
        RestoreAttention.Item<String> key = item("web", 1, WEB, Status.NEEDS_TEMP_KEY);

        assertThat(list.itemText(blocked, RestoreAttentionTest::texts))
            .isEqualTo("[session.restore.item|prod|[session.restore.reason.blocked|prod.example:22]]");
        assertThat(list.itemText(key, RestoreAttentionTest::texts))
            .isEqualTo("[session.restore.item|web|[session.restore.reason.temporaryKey]]");
    }

    @Test
    void clearForgetsEveryTab() {
        RestoreAttention<String> list = new RestoreAttention<>();
        list.add(item("web", 0, WEB, Status.NEEDS_PASSWORD));

        list.clear();

        assertThat(list.isEmpty()).isTrue();
        assertThat(list.offersConnect()).isFalse();
    }

    @Test
    void everyTextKeyIsARestoreBarKey() {
        assertThat(RestoreAttention.KEYS).containsNoDuplicates();
        for (String key : RestoreAttention.KEYS) {
            assertThat(key).startsWith("session.restore.");
        }
        for (TabRestoreTriage.Reason reason : TabRestoreTriage.Reason.values()) {
            assertThat(RestoreAttention.KEYS).contains(reason.i18nKey());
        }
    }

    private static RestoreAttention.Item<String> item(String label, int order, ServerConnection connection, Status status) {
        Resolution auth = status == Status.BLOCKED
            ? Resolution.blocked(connection, connection.getHost() + ":22")
            : Resolution.needs(status, connection);
        return new RestoreAttention.Item<>(label, order, label, connection.getId(), Outcome.of(auth));
    }

    private static List<String> labels(RestoreAttention<String> list) {
        return list.items().stream().map(RestoreAttention.Item::label).toList();
    }

    private static String texts(String key, Object... args) {
        StringBuilder text = new StringBuilder("[").append(key);
        for (Object arg : args) {
            text.append('|').append(arg);
        }
        return text.append(']').toString();
    }
}
