package de.kortty.ui.actions;

import de.kortty.ui.actions.PaletteEntry.Kind;
import de.kortty.ui.actions.TabPaletteSource.TabRow;
import de.kortty.ui.actions.TabPaletteSource.WindowTabs;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The command palette's tab rows: this window's tabs in most-recently-used order with the shown tab
 * last, then the other windows' tabs named by their window, and the detail that says where a
 * terminal tab is connected.
 */
class TabPaletteSourceTest {

    private static final String CURRENT = "Current tab";

    private static TabRow row(String id, String title, String detail, List<String> chosen) {
        return new TabRow(id, title, detail, () -> chosen.add(id));
    }

    private static TabRow row(String id, String title, String detail) {
        return row(id, title, detail, new ArrayList<>());
    }

    private static TabPaletteSource source(WindowTabs own, List<WindowTabs> others) {
        return new TabPaletteSource(() -> own, () -> others, () -> CURRENT);
    }

    private static List<String> keys(List<PaletteEntry> entries) {
        return entries.stream().map(PaletteEntry::key).toList();
    }

    @Test
    void thisWindowsTabsComeInTheGivenOrderWithTheShownTabLast() {
        WindowTabs own = new WindowTabs(null, List.of(
            row("t3", "db-01", "root@db-01"),
            row("t1", "web-01", "admin@web-01"),
            row("t2", "notes.txt", "")), "t3");

        List<PaletteEntry> entries = source(own, List.of()).entries();

        assertThat(keys(entries)).containsExactly("tab:t1", "tab:t2", "tab:t3").inOrder();
        assertThat(entries.get(0).detail()).isEqualTo("admin@web-01");
        assertThat(entries.get(1).detail()).isEmpty();
        assertThat(entries.get(2).detail()).isEqualTo("root@db-01" + TabPaletteSource.DETAIL_SEPARATOR + CURRENT);
        for (PaletteEntry entry : entries) {
            assertThat(entry.kind()).isEqualTo(Kind.TAB);
            assertThat(entry.enabled()).isTrue();
            assertThat(entry.shortcut()).isEmpty();
            assertThat(entry.checked()).isFalse();
        }
    }

    @Test
    void theOnlyTabIsListedAsTheCurrentOne() {
        WindowTabs own = new WindowTabs(null, List.of(row("t1", "web-01", "")), "t1");

        List<PaletteEntry> entries = source(own, List.of()).entries();

        assertThat(keys(entries)).containsExactly("tab:t1");
        assertThat(entries.get(0).detail()).isEqualTo(CURRENT);
    }

    @Test
    void otherWindowsFollowWithTheirLabelAndTheirOwnOrder() {
        WindowTabs own = new WindowTabs(null, List.of(row("t1", "web-01", "admin@web-01")), "t1");
        WindowTabs second = new WindowTabs("Window 2", List.of(
            row("t7", "db-02", "root@db-02"),
            row("t5", "mail", "")), "t7");
        WindowTabs third = new WindowTabs("Window 3", List.of(row("t9", "build", "ci@build")), null);

        List<PaletteEntry> entries = source(own, List.of(second, third)).entries();

        assertThat(keys(entries)).containsExactly("tab:t1", "tab:t7", "tab:t5", "tab:t9").inOrder();
        // Another window's shown tab is not this window's current tab: no note, not moved.
        assertThat(entries.get(1).detail()).isEqualTo("Window 2" + TabPaletteSource.DETAIL_SEPARATOR + "root@db-02");
        assertThat(entries.get(2).detail()).isEqualTo("Window 2");
        assertThat(entries.get(3).detail()).isEqualTo("Window 3" + TabPaletteSource.DETAIL_SEPARATOR + "ci@build");
    }

    @Test
    void choosingARowRunsItsSelect() {
        List<String> chosen = new ArrayList<>();
        WindowTabs own = new WindowTabs(null, List.of(row("t1", "web-01", "", chosen)), null);
        WindowTabs other = new WindowTabs("Window 2", List.of(row("t2", "db-01", "", chosen)), null);

        List<PaletteEntry> entries = source(own, List.of(other)).entries();
        entries.get(1).run().run();
        entries.get(0).run().run();

        assertThat(chosen).containsExactly("t2", "t1").inOrder();
    }

    @Test
    void aTabListedTwiceKeepsItsFirstRow() {
        WindowTabs own = new WindowTabs(null, List.of(row("t1", "web-01", "")), null);
        WindowTabs other = new WindowTabs("Window 2", List.of(row("t1", "web-01", ""), row("t2", "db", "")), null);

        assertThat(keys(source(own, List.of(other)).entries())).containsExactly("tab:t1", "tab:t2").inOrder();
    }

    @Test
    void missingWindowsGiveNoRows() {
        assertThat(source(null, null).entries()).isEmpty();
        List<WindowTabs> others = new ArrayList<>();
        others.add(null);
        assertThat(source(new WindowTabs(null, null, null), others).entries()).isEmpty();
    }

    @Test
    void rowTextsAreCleaned() {
        WindowTabs own = new WindowTabs(null,
            List.of(row("t1", "prod‮10-bd", "root@db\u0007-01")), null);

        PaletteEntry entry = source(own, List.of()).entries().get(0);

        assertThat(entry.title()).isEqualTo("prod10-bd");
        assertThat(entry.detail()).isEqualTo("root@db-01");
    }

    @Test
    void aRowNeedsAnIdAndASelect() {
        assertThrows(IllegalArgumentException.class, () -> new TabRow(" ", "x", "", () -> { }));
        assertThrows(NullPointerException.class, () -> new TabRow("t1", "x", "", null));
    }

    @Test
    void theDetailNamesTheConnectionAndTheGroup() {
        assertThat(TabPaletteSource.connectionDetail("Production DB", "root", "db-01", "Production DB", "Ops"))
            .isEqualTo("root@db-01" + TabPaletteSource.DETAIL_SEPARATOR + "Ops");
        assertThat(TabPaletteSource.connectionDetail("vim notes.txt", null, "db-01", "db-01", null))
            .isEqualTo("db-01");
        // A local shell has no host: the connection's own name instead.
        assertThat(TabPaletteSource.connectionDetail("~/src", null, null, "Local shell (zsh)", ""))
            .isEqualTo("Local shell (zsh)");
    }

    @Test
    void theDetailDoesNotRepeatATitleThatIsAlreadyUserAtHost() {
        assertThat(TabPaletteSource.connectionDetail("root@db-01", "root", "db-01", "root@db-01", null)).isEmpty();
        assertThat(TabPaletteSource.connectionDetail("ROOT@DB-01", "root", "db-01", "", "Ops")).isEqualTo("Ops");
    }

    @Test
    void aShellTitleThatImitatesAnotherHostStillShowsTheRealOne() {
        // The program in the terminal may set any title; the detail comes from the connection.
        assertThat(TabPaletteSource.connectionDetail("admin@bastion", "root", "prod-db", "prod-db", null))
            .isEqualTo("root@prod-db");
    }

    @Test
    void withNothingTypedTheHashScopeOffersThePreviousTabFirst() {
        WindowTabs own = new WindowTabs(null, List.of(
            row("t2", "db-01", ""),
            row("t1", "web-01", ""),
            row("t3", "mail", "")), "t2");
        CommandPaletteModel model = new CommandPaletteModel(List.of(source(own, List.of())),
            new MruList<>(MruList.DEFAULT_CAPACITY));
        model.open();

        List<String> titles = model.query("#").stream().map(PaletteEntry::title).toList();

        assertThat(titles).containsExactly("web-01", "mail", "db-01").inOrder();
    }
}
