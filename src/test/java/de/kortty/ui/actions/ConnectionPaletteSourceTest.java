package de.kortty.ui.actions;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import de.kortty.ui.actions.PaletteEntry.Kind;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;

/**
 * The command palette's connection rows: saved connections the last used first, teamwork
 * connections only while the policy allows teamwork and never the deleted ones, blocked targets
 * listed but not choosable, and a detail that names {@code user@host} and the group but never a
 * secret.
 */
class ConnectionPaletteSourceTest {

    private static final String BADGE = "Shared (Teamwork)";
    private static final String LOCAL_SHELL = "Local Shell";

    private static final ConnectionPaletteSource.Texts TEXTS = new ConnectionPaletteSource.Texts() {
        @Override
        public String teamwork() {
            return BADGE;
        }

        @Override
        public String localShell() {
            return LOCAL_SHELL;
        }

        @Override
        public String blocked(String target) {
            return "Not allowed: " + target;
        }
    };

    private static final Function<ServerConnection, Optional<String>> NOTHING_BLOCKED = connection -> Optional.empty();

    private static ServerConnection saved(String id, String name, String user, String host, String group, long lastUsed) {
        ServerConnection connection = new ServerConnection(name, host, 22, user);
        connection.setId(id);
        connection.setGroup(group);
        connection.setLastUsed(lastUsed);
        return connection;
    }

    private static ServerConnection teamwork(String id, String name, String user, String host, String group) {
        ServerConnection connection = saved(id, name, user, host, group, 0);
        connection.setConnectionSource(ConnectionSource.TEAMWORK);
        return connection;
    }

    private static ConnectionPaletteSource source(List<ServerConnection> saved, boolean teamworkAllowed,
                                                  List<ServerConnection> teamwork, Set<String> deleted,
                                                  Function<ServerConnection, Optional<String>> policy,
                                                  List<ServerConnection> connected) {
        return new ConnectionPaletteSource(
            new ConnectionPaletteSource.Connections(() -> saved, () -> teamworkAllowed, () -> teamwork, () -> deleted),
            policy, TEXTS, connected::add);
    }

    private static ConnectionPaletteSource source(List<ServerConnection> saved) {
        return source(saved, false, List.of(), Set.of(), NOTHING_BLOCKED, new ArrayList<>());
    }

    private static List<String> keys(List<PaletteEntry> entries) {
        return entries.stream().map(PaletteEntry::key).toList();
    }

    @Test
    void savedConnectionsComeTheLastUsedFirstAndTheUnusedByName() {
        List<PaletteEntry> entries = source(List.of(
            saved("a", "zeta", "root", "zeta.example", "", 0),
            saved("b", "web-01", "admin", "web-01.example", "Web", 2_000),
            saved("c", "Alpha", "root", "alpha.example", "", 0),
            saved("d", "db-01", "postgres", "db-01.example", "Production", 9_000))).entries();

        assertThat(keys(entries)).containsExactly("conn:d", "conn:b", "conn:c", "conn:a").inOrder();
        PaletteEntry db = entries.get(0);
        assertThat(db.kind()).isEqualTo(Kind.CONNECTION);
        assertThat(db.title()).isEqualTo("db-01");
        assertThat(db.detail()).isEqualTo("postgres@db-01.example · Production");
        assertThat(db.enabled()).isTrue();
        assertThat(db.disabledReason()).isEmpty();
        assertThat(db.shortcut()).isEmpty();
        assertThat(db.checked()).isFalse();
    }

    @Test
    void aConnectionWithoutANameIsNotNamedTwice() {
        ServerConnection unnamed = saved("a", null, "root", "10.0.0.5", "Lab", 0);

        PaletteEntry entry = source(List.of(unnamed)).entries().get(0);

        assertThat(entry.title()).isEqualTo("root@10.0.0.5");
        assertThat(entry.detail()).isEqualTo("Lab");
    }

    @Test
    void aLocalShellSaysSoInsteadOfAHost() {
        ServerConnection shell = saved("a", "zsh here", null, null, "", 0);
        shell.setProtocol(ConnectionProtocol.LOCAL_SHELL);

        PaletteEntry entry = source(List.of(shell)).entries().get(0);

        assertThat(entry.title()).isEqualTo("zsh here");
        assertThat(entry.detail()).isEqualTo(LOCAL_SHELL);
    }

    @Test
    void choosingARowConnectsThatConnection() {
        ServerConnection web = saved("b", "web-01", "admin", "web-01.example", "", 0);
        List<ServerConnection> connected = new ArrayList<>();
        ConnectionPaletteSource source = source(List.of(web), false, List.of(), Set.of(), NOTHING_BLOCKED, connected);

        source.entries().get(0).run().run();

        assertThat(connected).containsExactly(web);
    }

    @Test
    void teamworkConnectionsAreNotEvenAskedForWhileThePolicyForbidsTeamwork() {
        AtomicInteger asked = new AtomicInteger();
        ConnectionPaletteSource source = new ConnectionPaletteSource(
            new ConnectionPaletteSource.Connections(
                () -> List.of(saved("a", "web-01", "admin", "web-01.example", "", 0)),
                () -> false,
                () -> {
                    asked.incrementAndGet();
                    return List.of(teamwork("t1", "shared-db", "dba", "db.team", ""));
                },
                () -> {
                    asked.incrementAndGet();
                    return Set.of();
                }),
            NOTHING_BLOCKED, TEXTS, connection -> { });

        assertThat(keys(source.entries())).containsExactly("conn:a");
        assertThat(asked.get()).isEqualTo(0);
    }

    @Test
    void teamworkConnectionsFollowByNameWithTheirBadgeAndWithoutTheRecycleBin() {
        List<ServerConnection> teamwork = List.of(
            teamwork("t2", "switch-core", "netops", "10.1.0.1", "Network"),
            teamwork("t1", "deleted-db", "dba", "db.team", ""),
            teamwork("t3", "build", "ci", "build.team", ""));

        List<PaletteEntry> entries = source(List.of(saved("a", "web-01", "admin", "web-01.example", "", 5)),
            true, teamwork, Set.of("t1"), NOTHING_BLOCKED, new ArrayList<>()).entries();

        assertThat(keys(entries)).containsExactly("conn:a", "conn:teamwork:t3", "conn:teamwork:t2").inOrder();
        assertThat(entries.get(0).detail()).doesNotContain(BADGE);
        assertThat(entries.get(1).detail()).isEqualTo(BADGE + " · ci@build.team");
        assertThat(entries.get(2).detail()).isEqualTo(BADGE + " · netops@10.1.0.1 · Network");
    }

    @Test
    void aTeamworkRowAlwaysSaysWhereItConnectsAndCannotTakeASavedRowsPlace() {
        // A shared file names its connection exactly like the user@host of another server, and
        // reuses the id of a connection saved here.
        ServerConnection saved = saved("same-id", "prod", "root", "prod.example", "", 1);
        ServerConnection imitation = teamwork("same-id", "root@prod.example", "root", "evil.example", "");

        List<PaletteEntry> entries = source(List.of(saved), true, List.of(imitation), Set.of(), NOTHING_BLOCKED,
            new ArrayList<>()).entries();

        assertThat(keys(entries)).containsExactly("conn:same-id", "conn:teamwork:same-id").inOrder();
        assertThat(entries.get(1).title()).isEqualTo("root@prod.example");
        assertThat(entries.get(1).detail()).isEqualTo(BADGE + " · root@evil.example");
    }

    @Test
    void aBlockedTargetIsListedButCannotBeChosenAndSaysWhy() {
        ServerConnection allowed = saved("a", "web-01", "admin", "web-01.example", "", 0);
        ServerConnection blocked = saved("b", "db-01", "postgres", "db-01.example", "", 0);
        ServerConnection sharedBlocked = teamwork("t1", "shared-db", "dba", "db.team", "");
        Function<ServerConnection, Optional<String>> policy = connection ->
            connection.getHost().startsWith("db") ? Optional.of(connection.getHost() + ":22") : Optional.empty();

        List<PaletteEntry> entries = source(List.of(allowed, blocked), true, List.of(sharedBlocked), Set.of(), policy,
            new ArrayList<>()).entries();

        assertThat(keys(entries)).containsExactly("conn:b", "conn:a", "conn:teamwork:t1").inOrder();
        PaletteEntry db = entries.get(0);
        assertThat(db.enabled()).isFalse();
        assertThat(db.disabledReason()).isEqualTo("Not allowed: db-01.example:22");
        assertThat(entries.get(1).enabled()).isTrue();
        assertThat(entries.get(2).enabled()).isFalse();
        assertThat(entries.get(2).disabledReason()).isEqualTo("Not allowed: db.team:22");
    }

    @Test
    void theRowsNeverShowASecret() {
        ServerConnection connection = saved("a", "web-01", "admin", "web-01.example", "Web", 0);
        connection.setEncryptedPassword("ENC(c2VjcmV0LXBhc3N3b3Jk)");
        connection.setCredentialId("cred-4711");
        connection.setSshKeyId("key-0815");
        connection.setPrivateKeyPath("/home/admin/.ssh/id_secret");
        connection.setPrivateKeyPassphrase("passphrase-1234");
        connection.setTemporaryKeyContent("-----BEGIN OPENSSH PRIVATE KEY-----TEMPKEY");
        ServerConnection shared = teamwork("t1", "shared", "dba", "db.team", "");
        shared.setCredentialId("cred-team-42");
        shared.setSshKeyId("key-team-99");

        List<PaletteEntry> entries = source(List.of(connection), true, List.of(shared), Set.of(), NOTHING_BLOCKED,
            new ArrayList<>()).entries();

        assertThat(entries).hasSize(2);
        for (PaletteEntry entry : entries) {
            String shown = String.join("\n", entry.key(), entry.title(), entry.detail(), entry.disabledReason());
            for (String secret : List.of("c2VjcmV0", "ENC(", "cred-", "key-0815", "key-team", "id_secret",
                    "passphrase", "BEGIN OPENSSH", "TEMPKEY")) {
                assertThat(shown).doesNotContain(secret);
            }
        }
    }

    @Test
    void placeholdersAndConnectionsWithoutAnIdAreLeftOut() {
        ServerConnection placeholder = saved("p", "(Ordner: Web)", "", "placeholder", "Web", 0);
        ServerConnection noId = saved("x", "lost", "root", "lost.example", "", 0);
        noId.setId(null);
        List<ServerConnection> saved = new ArrayList<>();
        saved.add(placeholder);
        saved.add(noId);
        saved.add(null);
        saved.add(saved("a", "web-01", "admin", "web-01.example", "", 0));

        assertThat(keys(source(saved).entries())).containsExactly("conn:a");
    }

    @Test
    void rowTextsFromASharedFileAreCleaned() {
        ServerConnection shared = teamwork("t1", "prod‮evil\u0007", "root", "db⁦.team", "Ops\u0000");

        PaletteEntry entry = source(List.of(), true, List.of(shared), Set.of(), NOTHING_BLOCKED, new ArrayList<>())
            .entries().get(0);

        assertThat(entry.title()).isEqualTo("prodevil");
        assertThat(entry.detail()).isEqualTo(BADGE + " · root@db.team · Ops");
    }

    @Test
    void connectionsAreAPaletteSourceOfTheirOwnKind() {
        assertThat(source(List.of()).kind()).isEqualTo(Kind.CONNECTION);
        assertThat(source(List.of()).entries()).isEmpty();
    }
}
