package de.kortty.ui.actions;

import de.kortty.model.Snippet;
import de.kortty.ui.actions.PaletteEntry.Kind;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;

/**
 * The command palette's snippet rows: every row names the terminal the snippet would run in, and its
 * first pane when the tab is split; without a terminal the rows cannot run and say why, but
 * Alt+Enter still opens them; a row runs in exactly the terminal it named when the palette opened;
 * the last used come first; and a row is found by its name, folder, category and tags but never by
 * the terminal it names.
 */
class SnippetPaletteSourceTest {

    private static final String NO_TERMINAL = "No terminal tab open";
    private static final String NO_TERMINAL_REASON = "No terminal tab is open. Alt+Enter opens it.";

    private static final SnippetPaletteSource.Texts TEXTS = new SnippetPaletteSource.Texts() {
        @Override
        public String runIn(String target) {
            return "Run in " + target;
        }

        @Override
        public String runInFirstPane(String target) {
            return "Run in the first pane of " + target;
        }

        @Override
        public String noTerminal() {
            return NO_TERMINAL;
        }

        @Override
        public String noTerminalReason() {
            return NO_TERMINAL_REASON;
        }

        @Override
        public String unnamed() {
            return "(unnamed)";
        }
    };

    private static Snippet snippet(String id, String name, long lastUsed) {
        Snippet snippet = new Snippet(name, "echo " + name, "bash");
        snippet.setId(id);
        snippet.setLastUsed(lastUsed);
        return snippet;
    }

    private static SnippetPaletteSource source(List<Snippet> snippets, AtomicReference<SnippetPaletteSource.Target> target,
                                               List<String> opened) {
        return new SnippetPaletteSource(
            new SnippetPaletteSource.Library(() -> snippets,
                snippet -> Map.of("f1", "deploy/scripts").getOrDefault(snippet.getFolderId(), "")),
            target::get, TEXTS, snippet -> opened.add(snippet.getName()));
    }

    private static SnippetPaletteSource.Target terminal(String name, boolean split, List<String> sent) {
        return new SnippetPaletteSource.Target(name, split, snippet -> sent.add(name + ": " + snippet.getName()));
    }

    private static List<String> titles(List<PaletteEntry> entries) {
        return entries.stream().map(PaletteEntry::title).toList();
    }

    @Test
    void everyRowNamesTheTerminalAndItsFirstPaneWhenTheTabIsSplit() {
        List<String> sent = new ArrayList<>();
        AtomicReference<SnippetPaletteSource.Target> target =
            new AtomicReference<>(terminal("web-01 (admin@web-01)", false, sent));
        SnippetPaletteSource source = source(List.of(snippet("s1", "Disk usage", 0)), target, new ArrayList<>());

        PaletteEntry single = source.entries().get(0);
        target.set(terminal("web-01 (admin@web-01)", true, sent));
        PaletteEntry split = source.entries().get(0);

        assertThat(source.kind()).isEqualTo(Kind.SNIPPET);
        assertThat(single.kind()).isEqualTo(Kind.SNIPPET);
        assertThat(single.key()).isEqualTo("snippet:s1");
        assertThat(single.title()).isEqualTo("Disk usage");
        assertThat(single.detail()).isEqualTo("Run in web-01 (admin@web-01)");
        assertThat(single.enabled()).isTrue();
        assertThat(single.disabledReason()).isEmpty();
        assertThat(single.shortcut()).isEmpty();
        assertThat(split.detail()).isEqualTo("Run in the first pane of web-01 (admin@web-01)");
    }

    @Test
    void aRowRunsTheSnippetInTheTerminalItNamedWhenThePaletteOpened() {
        List<String> sent = new ArrayList<>();
        AtomicReference<SnippetPaletteSource.Target> target = new AtomicReference<>(terminal("web-01", false, sent));
        List<String> opened = new ArrayList<>();
        SnippetPaletteSource source = source(List.of(snippet("s1", "Disk usage", 0)), target, opened);

        PaletteEntry row = source.entries().get(0);
        // Another terminal tab is selected after the palette opened: the row keeps its own.
        target.set(terminal("db-01", false, sent));
        row.run().run();

        assertThat(sent).containsExactly("web-01: Disk usage");
        assertThat(opened).isEmpty();
    }

    @Test
    void withoutATerminalTheRowsAreListedButCannotRunAndSayWhyYetAltEnterStillOpensThem() {
        List<String> opened = new ArrayList<>();
        SnippetPaletteSource source = source(List.of(snippet("s1", "Disk usage", 0)), new AtomicReference<>(),
            opened);

        PaletteEntry row = source.entries().get(0);

        assertThat(row.enabled()).isFalse();
        assertThat(row.detail()).isEqualTo(NO_TERMINAL);
        assertThat(row.disabledReason()).isEqualTo(NO_TERMINAL_REASON);
        row.run().run();
        assertThat(opened).isEmpty();
        assertThat(row.alternate()).isNotNull();
        row.alternate().run();
        assertThat(opened).containsExactly("Disk usage");
    }

    @Test
    void altEnterOpensTheSnippetInsteadOfRunningIt() {
        List<String> sent = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        SnippetPaletteSource source = source(List.of(snippet("s1", "Disk usage", 0)),
            new AtomicReference<>(terminal("web-01", false, sent)), opened);

        source.entries().get(0).alternate().run();

        assertThat(opened).containsExactly("Disk usage");
        assertThat(sent).isEmpty();
    }

    @Test
    void theLastUsedComeFirstTheOthersByNameAndSnippetsWithoutAnIdAreLeftOut() {
        Snippet noId = snippet("x", "Orphan", 9_000);
        noId.setId(null);
        Snippet blankId = snippet(" ", "Blank", 9_000);
        Snippet unnamed = snippet("s5", null, 0);
        List<Snippet> snippets = List.of(snippet("s1", "backup", 0), snippet("s2", "Restart nginx", 2_000), noId,
            snippet("s3", "Archive logs", 0), snippet("s4", "Disk usage", 5_000), blankId, unnamed);

        List<PaletteEntry> rows = source(snippets, new AtomicReference<>(), new ArrayList<>()).entries();

        assertThat(titles(rows)).containsExactly("Disk usage", "Restart nginx", "(unnamed)", "Archive logs", "backup")
            .inOrder();
        assertThat(rows.stream().map(PaletteEntry::key).toList())
            .containsExactly("snippet:s4", "snippet:s2", "snippet:s5", "snippet:s3", "snippet:s1").inOrder();
    }

    @Test
    void noSnippetsMeanNoRowsAndTheTerminalIsNotAskedFor() {
        AtomicInteger asked = new AtomicInteger();
        SnippetPaletteSource source = new SnippetPaletteSource(
            new SnippetPaletteSource.Library(List::of, snippet -> ""),
            () -> {
                asked.incrementAndGet();
                return null;
            }, TEXTS, snippet -> { });

        assertThat(source.entries()).isEmpty();
        assertThat(asked.get()).isEqualTo(0);
    }

    @Test
    void aRowIsFoundByItsNameFolderCategoryAndTagsButNotByTheTerminalItNames() {
        Snippet deploy = snippet("s1", "release.sh", 0);
        deploy.setFolderId("f1");
        deploy.setCategory("Kubernetes");
        deploy.setTags(new ArrayList<>(List.of("rollout", "helm")));
        Snippet disk = snippet("s2", "Disk usage", 0);
        AtomicReference<SnippetPaletteSource.Target> target =
            new AtomicReference<>(terminal("web-01 (admin@web-01)", false, new ArrayList<>()));
        PaletteSource tabs = new TabPaletteSource(
            () -> new TabPaletteSource.WindowTabs(null, List.of(
                new TabPaletteSource.TabRow("t1", "web-01", "admin@web-01", () -> { })), null),
            List::of, () -> "Current tab");
        CommandPaletteModel model = new CommandPaletteModel(
            List.of(tabs, source(List.of(deploy, disk), target, new ArrayList<>())),
            new MruList<>(MruList.DEFAULT_CAPACITY));
        model.open();

        assertThat(titles(model.query("web"))).containsExactly("web-01");
        assertThat(titles(model.query("$web"))).isEmpty();
        assertThat(titles(model.query("$deploy/scripts"))).containsExactly("release.sh");
        assertThat(titles(model.query("$kubernetes"))).containsExactly("release.sh");
        assertThat(titles(model.query("helm"))).containsExactly("release.sh");
        assertThat(titles(model.query("$usage"))).containsExactly("Disk usage");
        assertThat(model.query("").stream().map(PaletteEntry::kind).toList()).doesNotContain(Kind.SNIPPET);
        assertThat(titles(model.query("$"))).containsExactly("Disk usage", "release.sh").inOrder();
    }

    @Test
    void theTerminalIsNamedByTheConnectionsUserAtHostAndThenItsTitle() {
        assertThat(SnippetPaletteSource.targetName("prod-db", "root", "db-01.example.org", "prod-db"))
            .isEqualTo("root@db-01.example.org (prod-db)");
        // A title a program set cannot hide the server: the connection is named before it.
        assertThat(SnippetPaletteSource.targetName("admin@web-01", "root", "db-01", "prod-db"))
            .isEqualTo("root@db-01 (admin@web-01)");
        assertThat(SnippetPaletteSource.targetName("root@db-01", "root", "db-01", "root@db-01"))
            .isEqualTo("root@db-01");
        assertThat(SnippetPaletteSource.targetName("db-01", null, "db-01", "db-01")).isEqualTo("db-01");
        assertThat(SnippetPaletteSource.targetName(" ", "root", "db-01", "db-01")).isEqualTo("root@db-01");
        // A local shell has no host: the connection's name stands in for it.
        assertThat(SnippetPaletteSource.targetName("build", "dan", null, "Local Shell"))
            .isEqualTo("Local Shell (build)");
        assertThat(SnippetPaletteSource.targetName("Local Shell", "dan", null, "Local Shell"))
            .isEqualTo("Local Shell");
        assertThat(SnippetPaletteSource.targetName("build", null, null, null)).isEqualTo("build");
    }

    /**
     * A program in the terminal can set a title of 80 characters that imitates another server. The
     * row still names the real one: it comes first, so cutting the row to the palette's length (or
     * an ellipsis on screen) only ever cuts the title.
     */
    @Test
    void aLongTitleTheServerSetCannotPushTheRealServerOutOfTheRow() {
        String fake = ("prod-db (root@db-01.example.org) " + "x".repeat(80)).substring(0, 80);
        List<String> sent = new ArrayList<>();
        SnippetPaletteSource source = source(List.of(snippet("s1", "Disk usage", 0)),
            new AtomicReference<>(terminal(SnippetPaletteSource.targetName(fake, "evil", "attacker.example", "x"),
                true, sent)),
            new ArrayList<>());

        String detail = source.entries().get(0).detail();

        assertThat(detail).startsWith("Run in the first pane of evil@attacker.example (prod-db (root@db-01");
        assertThat(detail.length()).isAtMost(PaletteText.MAX_LENGTH);
    }

    @Test
    void rowTextsAreCleaned() {
        Snippet snippet = snippet("s1", "deploy‮exe.sh\u0007", 0);
        snippet.setTags(new ArrayList<>(List.of("ro⁦ll⁩out")));
        Consumer<Snippet> nothing = s -> { };
        SnippetPaletteSource source = new SnippetPaletteSource(
            new SnippetPaletteSource.Library(() -> List.of(snippet), s -> ""),
            () -> new SnippetPaletteSource.Target("web‮-01", false, nothing), TEXTS, nothing);

        PaletteEntry row = source.entries().get(0);

        assertThat(row.title()).isEqualTo("deployexe.sh");
        assertThat(row.detail()).isEqualTo("Run in web-01");
        assertThat(row.searchDetail()).isEqualTo("rollout");
    }
}
