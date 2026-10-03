package de.kortty.ui.actions;

import de.kortty.ui.actions.PaletteEntry.Kind;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * What the command palette lists for what is typed: scope prefixes, fuzzy ranking over title and
 * menu path, recent choices, the list shown before anything is typed, and the cleaning of row texts.
 * Toolkit-free: the menus are plain Menu/MenuItem/CheckMenuItem, and shortcuts are shown through an
 * injected function instead of KeyCombination.getDisplayText.
 */
class CommandPaletteModelTest {

    private static final Runnable NOTHING = () -> { };

    private static PaletteEntry entry(Kind kind, String key, String title, String detail) {
        return new PaletteEntry(kind, key, title, detail, "", true, "", false, NOTHING);
    }

    private static PaletteEntry action(String title, String category) {
        return entry(Kind.ACTION, "action:" + category + "/" + title, title, category);
    }

    private static PaletteEntry disabledAction(String title, String category) {
        return new PaletteEntry(Kind.ACTION, "action:" + category + "/" + title, title, category, "", false,
            "Not available right now", false, NOTHING);
    }

    private static PaletteSource source(Kind kind, PaletteEntry... entries) {
        return source(kind, () -> List.of(entries));
    }

    private static PaletteSource source(Kind kind, Supplier<List<PaletteEntry>> entries) {
        return new PaletteSource() {
            @Override
            public Kind kind() {
                return kind;
            }

            @Override
            public List<PaletteEntry> entries() {
                return entries.get();
            }
        };
    }

    private static CommandPaletteModel model(MruList<String> recent, PaletteSource... sources) {
        CommandPaletteModel model = new CommandPaletteModel(List.of(sources), recent);
        model.open();
        return model;
    }

    private static CommandPaletteModel model(PaletteSource... sources) {
        return model(new MruList<>(MruList.DEFAULT_CAPACITY), sources);
    }

    private static List<String> titles(List<PaletteEntry> entries) {
        return entries.stream().map(PaletteEntry::title).toList();
    }

    /** One source per kind, so every scope prefix is live. */
    private static PaletteSource[] allKinds() {
        return new PaletteSource[] {
            source(Kind.ACTION, action("Find", "Edit"), action("Close Tab", "File")),
            source(Kind.TAB, entry(Kind.TAB, "tab:1", "web-01", "admin@web-01")),
            source(Kind.CONNECTION, entry(Kind.CONNECTION, "conn:7", "db-01", "postgres@db-01")),
            source(Kind.SNIPPET, entry(Kind.SNIPPET, "snippet:3", "Disk usage", "Run in web-01 (first pane)"))
        };
    }

    // ---- scopes ---------------------------------------------------------------------------------

    @Test
    void aScopePrefixListsOneKindOnly() {
        CommandPaletteModel model = model(allKinds());

        assertThat(titles(model.query(">"))).containsExactly("Find", "Close Tab").inOrder();
        assertThat(titles(model.query("#"))).containsExactly("web-01");
        assertThat(titles(model.query("@"))).containsExactly("db-01");
        assertThat(titles(model.query("$"))).containsExactly("Disk usage");
        assertThat(titles(model.query("> find"))).containsExactly("Find");
        assertThat(titles(model.query("#web"))).containsExactly("web-01");
        assertThat(model.query("@ find")).isEmpty();
    }

    @Test
    void aPrefixWithoutASourceIsPartOfTheQuery() {
        CommandPaletteModel model = model(source(Kind.ACTION, action("C# Format", "Tools"), action("Find", "Edit")));

        assertThat(model.kinds()).containsExactly(Kind.ACTION);
        assertThat(model.parse("#").scope()).isNull();
        assertThat(model.parse("#").terms()).isEqualTo("#");
        assertThat(titles(model.query("#"))).containsExactly("C# Format");
        assertThat(model.parse(" > find ")).isEqualTo(new CommandPaletteModel.Query(Kind.ACTION, "find"));
    }

    // ---- ranking --------------------------------------------------------------------------------

    @Test
    void aTitleMatchBeatsADetailMatch() {
        CommandPaletteModel model = model(source(Kind.ACTION,
            action("Close Tab", "Find and replace"),
            action("Find", "Edit")));

        assertThat(titles(model.query("find"))).containsExactly("Find", "Close Tab").inOrder();
    }

    @Test
    void theMenuPathMakesAmbiguousLabelsFindable() {
        Menu view = new Menu("View");
        Menu agentPanel = new Menu("AI Agent Panel");
        agentPanel.getItems().addAll(new MenuItem("Dock Left"), new MenuItem("Dock Right"));
        Menu journal = new Menu("Live Journal");
        journal.getItems().addAll(new MenuItem("Dock Left"), new MenuItem("Dock Right"), new MenuItem("Show/Hide"));
        view.getItems().addAll(agentPanel, journal);
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> MenuActionHarvester.harvest(List.of(view)));
        CommandPaletteModel model = model(actionSource(registry));

        List<PaletteEntry> found = model.query("journal left");

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).title()).isEqualTo("Dock Left");
        assertThat(found.get(0).detail()).isEqualTo("View › Live Journal");
        assertThat(found.stream().map(PaletteEntry::detail).toList()).doesNotContain("View › AI Agent Panel");
    }

    @Test
    void aRecentChoiceGoesFirstAmongEqualMatches() {
        CommandPaletteModel model = model(source(Kind.ACTION, action("Open A", "File"), action("Open B", "File")));
        assertThat(titles(model.query("open"))).containsExactly("Open A", "Open B").inOrder();

        model.chosen(model.query("open b").get(0));

        assertThat(titles(model.query("open"))).containsExactly("Open B", "Open A").inOrder();
    }

    @Test
    void disabledRowsComeAfterEveryEnabledMatch() {
        CommandPaletteModel model = model(source(Kind.ACTION,
            disabledAction("Session Journals", "Tools"),
            action("Toggle Session Journal", "Tools")));

        List<PaletteEntry> found = model.query("session journal");

        assertThat(titles(found)).containsExactly("Toggle Session Journal", "Session Journals").inOrder();
        assertThat(found.get(1).enabled()).isFalse();
        assertThat(found.get(1).disabledReason()).isEqualTo("Not available right now");
    }

    @Test
    void equalScoresGoByKindThenTitle() {
        CommandPaletteModel model = model(
            source(Kind.TAB, entry(Kind.TAB, "tab:1", "Logs", "")),
            source(Kind.ACTION, action("Logs", "View")));

        List<PaletteEntry> found = model.query("logs");

        assertThat(found.stream().map(PaletteEntry::kind).toList()).containsExactly(Kind.ACTION, Kind.TAB).inOrder();
    }

    // ---- nothing typed --------------------------------------------------------------------------

    @Test
    void withNothingTypedRecentChoicesComeFirstThenTabsThenCommandsByMenu() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        recent.add("snippet:3");
        recent.add("conn:7");
        recent.add("action:View/Zoom In");
        CommandPaletteModel model = model(recent,
            source(Kind.ACTION, action("New Tab", "File"), action("Zoom In", "View"), action("Find", "Edit"),
                action("Close Tab", "File")),
            source(Kind.TAB, entry(Kind.TAB, "tab:1", "web-01", ""), entry(Kind.TAB, "tab:2", "db-01", "")),
            source(Kind.CONNECTION, entry(Kind.CONNECTION, "conn:7", "router", ""),
                entry(Kind.CONNECTION, "conn:8", "switch", "")),
            source(Kind.SNIPPET, entry(Kind.SNIPPET, "snippet:3", "Disk usage", "")));

        assertThat(titles(model.query(""))).containsExactly(
            "Zoom In", "router",              // recent, without the snippet
            "web-01", "db-01",                // the open tabs
            "New Tab", "Close Tab", "Find")    // the commands, each menu together
            .inOrder();
    }

    @Test
    void atMostEightRecentChoicesLeadTheEmptyList() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        List<PaletteEntry> actions = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            PaletteEntry entry = action("Command " + i, "Menu " + i);
            actions.add(entry);
            recent.add(entry.key());
        }
        CommandPaletteModel model = model(recent, source(Kind.ACTION, () -> actions));

        List<String> listed = titles(model.query(""));

        assertThat(listed.subList(0, CommandPaletteModel.RECENT_ON_EMPTY_QUERY)).containsExactly(
            "Command 11", "Command 10", "Command 9", "Command 8", "Command 7", "Command 6", "Command 5", "Command 4")
            .inOrder();
        assertThat(listed).hasSize(12);
    }

    @Test
    void snippetsAreListedOnlyWhenAskedFor() {
        CommandPaletteModel model = model(allKinds());

        assertThat(model.query("").stream().map(PaletteEntry::kind).toList()).doesNotContain(Kind.SNIPPET);
        assertThat(titles(model.query("$"))).containsExactly("Disk usage");
        assertThat(titles(model.query("disk"))).containsExactly("Disk usage");
    }

    @Test
    void theLimitIsHonoured() {
        List<PaletteEntry> actions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            actions.add(action("Command " + i, "Menu"));
        }
        CommandPaletteModel model = model(source(Kind.ACTION, () -> actions));

        assertThat(model.query("")).hasSize(CommandPaletteModel.DEFAULT_LIMIT);
        assertThat(model.query("command")).hasSize(CommandPaletteModel.DEFAULT_LIMIT);
        assertThat(model.query("command", 5)).hasSize(5);
        assertThat(model.query("command", 0)).hasSize(100);
    }

    // ---- sources --------------------------------------------------------------------------------

    @Test
    void theSourcesAreReadOncePerOpening() {
        AtomicInteger reads = new AtomicInteger();
        List<PaletteEntry> current = new ArrayList<>(List.of(action("Find", "Edit")));
        CommandPaletteModel model = new CommandPaletteModel(List.of(source(Kind.ACTION, () -> {
            reads.incrementAndGet();
            return List.copyOf(current);
        })), new MruList<>(MruList.DEFAULT_CAPACITY));

        model.query("");                 // the first query opens on its own
        model.query("f");
        model.query("fi");
        assertThat(reads.get()).isEqualTo(1);

        current.add(action("Find Next", "Edit"));
        assertThat(titles(model.query("find"))).containsExactly("Find");
        model.open();
        assertThat(reads.get()).isEqualTo(2);
        assertThat(titles(model.query("find"))).containsExactly("Find", "Find Next").inOrder();
    }

    @Test
    void aFailingSourceAndRowsOfAnotherKindAreLeftOut() {
        CommandPaletteModel model = model(
            source(Kind.TAB, () -> {
                throw new IllegalStateException("db-01 is gone");
            }),
            source(Kind.ACTION, action("Find", "Edit"), entry(Kind.SNIPPET, "snippet:1", "Sneaky", "")),
            source(Kind.CONNECTION, () -> null));

        assertThat(titles(model.query(""))).containsExactly("Find");
        assertThat(model.query("sneaky")).isEmpty();
    }

    @Test
    void choosingARowRemembersItsKey() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        CommandPaletteModel model = model(recent, source(Kind.ACTION, action("Find", "Edit")));

        model.chosen(model.query("find").get(0));
        model.chosen(null);

        assertThat(recent.items()).containsExactly("action:Edit/Find");
    }

    @Test
    void aChosenTabIsNoRecentChoiceSoTheTabYouAreInDoesNotLeadTheList() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        // The tab source lists the previous tab first and the current one ("web-01") last.
        CommandPaletteModel model = model(recent, source(Kind.TAB,
            entry(Kind.TAB, "tab:2", "db-01", ""), entry(Kind.TAB, "tab:1", "web-01", "Current tab")));

        model.chosen(model.query("web").get(0));

        assertThat(recent.items()).isEmpty();
        assertThat(titles(model.query("#"))).containsExactly("db-01", "web-01").inOrder();
    }

    @Test
    void equallyGoodTabMatchesKeepTheTabOrder() {
        CommandPaletteModel model = model(source(Kind.TAB,
            entry(Kind.TAB, "tab:3", "db-02", ""), entry(Kind.TAB, "tab:1", "db-01", ""),
            entry(Kind.TAB, "tab:2", "db-03", "")));

        assertThat(titles(model.query("db"))).containsExactly("db-02", "db-01", "db-03").inOrder();
    }

    @Test
    void equallyGoodConnectionMatchesKeepTheSourceOrderSoTheLastUsedComesFirst() {
        CommandPaletteModel model = model(source(Kind.CONNECTION,
            entry(Kind.CONNECTION, "conn:3", "db-03", ""), entry(Kind.CONNECTION, "conn:1", "db-01", ""),
            entry(Kind.CONNECTION, "conn:2", "db-02", "")));

        assertThat(titles(model.query("db"))).containsExactly("db-03", "db-01", "db-02").inOrder();
        assertThat(titles(model.query("@"))).containsExactly("db-03", "db-01", "db-02").inOrder();
    }

    @Test
    void equallyGoodSnippetMatchesKeepTheSourceOrderSoTheLastUsedComesFirst() {
        CommandPaletteModel model = model(source(Kind.SNIPPET,
            entry(Kind.SNIPPET, "snippet:3", "deploy-c", ""), entry(Kind.SNIPPET, "snippet:1", "deploy-a", ""),
            entry(Kind.SNIPPET, "snippet:2", "deploy-b", "")));

        assertThat(titles(model.query("deploy"))).containsExactly("deploy-c", "deploy-a", "deploy-b").inOrder();
    }

    @Test
    void aRowWithASearchDetailIsFoundByItAndNotByTheDetailItShows() {
        PaletteEntry snippet = new PaletteEntry(Kind.SNIPPET, "snippet:1", "release.sh", "Run in web-01", "", true,
            "", false, NOTHING, "kubernetes helm", null);
        CommandPaletteModel model = model(
            source(Kind.TAB, entry(Kind.TAB, "tab:1", "web-01", "")),
            source(Kind.SNIPPET, snippet));

        assertThat(titles(model.query("web"))).containsExactly("web-01");
        assertThat(titles(model.query("helm"))).containsExactly("release.sh");
        assertThat(snippet.detail()).isEqualTo("Run in web-01");
        assertThat(entry(Kind.ACTION, "action:x", "Find", "Edit").searchDetail()).isEqualTo("Edit");
        assertThat(entry(Kind.ACTION, "action:x", "Find", "Edit").alternate()).isNull();
    }

    @Test
    void aSnippetChosenOnceIsRememberedButStillNotListedBeforeAnythingIsTyped() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        CommandPaletteModel model = model(recent, allKinds());

        model.chosen(model.query("$disk").get(0));

        assertThat(recent.items()).containsExactly("snippet:3");
        assertThat(model.query("").stream().map(PaletteEntry::kind).toList()).doesNotContain(Kind.SNIPPET);
        assertThat(titles(model.query("$"))).containsExactly("Disk usage");
    }

    @Test
    void aChosenConnectionIsARecentChoice() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        CommandPaletteModel model = model(recent,
            source(Kind.ACTION, action("Find", "Edit")),
            source(Kind.CONNECTION, entry(Kind.CONNECTION, "conn:1", "db-01", ""),
                entry(Kind.CONNECTION, "conn:2", "db-02", "")));

        model.chosen(model.query("@db-02").get(0));

        assertThat(recent.items()).containsExactly("conn:2");
        assertThat(titles(model.query(""))).containsExactly("db-02", "Find").inOrder();
        assertThat(titles(model.query("@"))).containsExactly("db-02", "db-01").inOrder();
    }

    // ---- row texts ------------------------------------------------------------------------------

    @Test
    void rowTextsLoseControlAndBidiCharactersAndAreCut() {
        PaletteEntry entry = new PaletteEntry(Kind.CONNECTION, "conn:1", "prod‮10.0.0.1‬\u0007",
            "root@⁦web⁩\nlog", "Ctrl+\u0000K", false, "Managed\u001B[31m", false, NOTHING);

        assertThat(entry.title()).isEqualTo("prod10.0.0.1");
        assertThat(entry.detail()).isEqualTo("root@web log");
        assertThat(entry.shortcut()).isEqualTo("Ctrl+K");
        assertThat(entry.disabledReason()).isEqualTo("Managed[31m");
        assertThat(new PaletteEntry(Kind.ACTION, "k", "x".repeat(500), null, null, true, null, false, NOTHING)
            .title()).hasLength(PaletteText.MAX_LENGTH);
        assertThat(PaletteText.clean(null)).isEmpty();
    }

    @Test
    void anEntryNeedsAKindAKeyAndSomethingToRun() {
        assertThrows(NullPointerException.class,
            () -> new PaletteEntry(null, "k", "t", "", "", true, "", false, NOTHING));
        assertThrows(IllegalArgumentException.class,
            () -> new PaletteEntry(Kind.ACTION, " ", "t", "", "", true, "", false, NOTHING));
        assertThrows(NullPointerException.class,
            () -> new PaletteEntry(Kind.ACTION, "k", "t", "", "", true, "", false, null));
        assertThat(Kind.ofScopePrefix('>')).isEqualTo(Kind.ACTION);
        assertThat(Kind.ofScopePrefix('#')).isEqualTo(Kind.TAB);
        assertThat(Kind.ofScopePrefix('@')).isEqualTo(Kind.CONNECTION);
        assertThat(Kind.ofScopePrefix('$')).isEqualTo(Kind.SNIPPET);
        assertThat(Kind.ofScopePrefix('!')).isNull();
    }

    // ---- menu commands --------------------------------------------------------------------------

    private static ActionPaletteSource actionSource(ActionRegistry registry) {
        return new ActionPaletteSource(registry, combination -> "<" + combination.getName() + ">",
            () -> "Managed by your organization", () -> "Not available right now");
    }

    @Test
    void menuCommandsBecomeRowsKeyedByTheirActionId() {
        MenuItem find = ActionIds.tag(new MenuItem("Find..."), "menu.edit.find");
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        CheckMenuItem dashboard = ActionIds.tag(new CheckMenuItem("Show Dashboard"), "menu.view.dashboard");
        dashboard.setSelected(true);
        MenuItem journals = ActionIds.markPolicyLocked(ActionIds.tag(new MenuItem("Session Journals"),
            "menu.tools.sessionJournals"));
        journals.setDisable(true);
        MenuItem unlock = ActionIds.tag(new MenuItem("Unlock Vault..."), "menu.security.unlockVault");
        unlock.setDisable(true);
        Menu edit = new Menu("Edit");
        edit.getItems().add(find);
        Menu view = new Menu("View");
        view.getItems().add(dashboard);
        Menu tools = new Menu("Tools");
        tools.getItems().addAll(journals, unlock);
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> MenuActionHarvester.harvest(List.of(edit, view, tools)));

        List<PaletteEntry> rows = actionSource(registry).entries();

        assertThat(rows.stream().map(PaletteEntry::key).toList()).containsExactly(
            "action:menu.edit.find", "action:menu.view.dashboard", "action:menu.tools.sessionJournals",
            "action:menu.security.unlockVault").inOrder();
        PaletteEntry findRow = rows.get(0);
        assertThat(findRow.kind()).isEqualTo(Kind.ACTION);
        assertThat(findRow.title()).isEqualTo("Find");
        assertThat(findRow.detail()).isEqualTo("Edit");
        assertThat(findRow.shortcut()).isEqualTo("<" + find.getAccelerator().getName() + ">");
        assertThat(findRow.enabled()).isTrue();
        assertThat(rows.get(1).checked()).isTrue();
        assertThat(rows.get(1).shortcut()).isEmpty();
        assertThat(rows.get(2).enabled()).isFalse();
        assertThat(rows.get(2).disabledReason()).isEqualTo("Managed by your organization");
        assertThat(rows.get(3).enabled()).isFalse();
        assertThat(rows.get(3).disabledReason()).isEqualTo("Not available right now");
    }

    @Test
    void aRowRunsItsCommandOnlyIfItIsStillEnabled() {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean enabled = new AtomicBoolean(true);
        ActionRegistry registry = new ActionRegistry();
        registry.register(new AppAction("palette.action.nextTab", "Next Tab", "Tabs", null, List.of(),
            enabled::get, null, runs::incrementAndGet, true, false));
        PaletteEntry row = actionSource(registry).entries().get(0);

        row.run().run();
        enabled.set(false);
        row.run().run();

        assertThat(runs.get()).isEqualTo(1);
        assertThat(row.enabled()).isTrue();
    }
}
