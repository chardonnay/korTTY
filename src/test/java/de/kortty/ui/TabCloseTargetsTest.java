package de.kortty.ui;

import javafx.scene.control.Tab;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Close Other Tabs and Close Tabs to the Right: which tabs they close, worked out on plain JavaFX
 * tabs (no toolkit needed), and how {@link MainWindow} wires them, pinned against the source since
 * the window cannot be built without a stage.
 */
class TabCloseTargetsTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    private Tab first;
    private Tab second;
    private Tab third;
    private Tab fourth;

    @BeforeMethod
    void freshTabs() {
        // TestNG shares one instance between the tests, and some switch closing off.
        first = new Tab("first");
        second = new Tab("second");
        third = new Tab("third");
        fourth = new Tab("fourth");
    }

    @Test
    void othersAreEveryTabButTheAnchorInTabOrder() {
        List<Tab> tabs = List.of(first, second, third, fourth);

        assertThat(TabCloseTargets.others(tabs, third)).containsExactly(first, second, fourth).inOrder();
        assertThat(TabCloseTargets.others(tabs, first)).containsExactly(second, third, fourth).inOrder();
    }

    @Test
    void theRightAreTheTabsAfterTheAnchorInTabOrder() {
        List<Tab> tabs = List.of(first, second, third, fourth);

        assertThat(TabCloseTargets.toTheRight(tabs, second)).containsExactly(third, fourth).inOrder();
        assertThat(TabCloseTargets.toTheRight(tabs, first)).containsExactly(second, third, fourth).inOrder();
    }

    @Test
    void tabsThatMayNotCloseAreSkipped() {
        second.setClosable(false);
        fourth.setClosable(false);
        List<Tab> tabs = List.of(first, second, third, fourth);

        assertThat(TabCloseTargets.others(tabs, first)).containsExactly(third);
        assertThat(TabCloseTargets.toTheRight(tabs, first)).containsExactly(third);
        assertWithMessage("only tabs that may not close are left to the right")
            .that(TabCloseTargets.toTheRight(tabs, third)).isEmpty();
    }

    @Test
    void anAnchorThatMayNotCloseStillAnchors() {
        second.setClosable(false);
        List<Tab> tabs = List.of(first, second, third);

        assertThat(TabCloseTargets.others(tabs, second)).containsExactly(first, third).inOrder();
        assertThat(TabCloseTargets.toTheRight(tabs, second)).containsExactly(third);
    }

    @Test
    void theLastTabHasNothingToItsRight() {
        List<Tab> tabs = List.of(first, second, third);

        assertThat(TabCloseTargets.toTheRight(tabs, third)).isEmpty();
        assertThat(TabCloseTargets.others(tabs, third)).containsExactly(first, second).inOrder();
    }

    @Test
    void aSingleTabHasNoOthers() {
        assertThat(TabCloseTargets.others(List.of(first), first)).isEmpty();
        assertThat(TabCloseTargets.toTheRight(List.of(first), first)).isEmpty();
    }

    @Test
    void aMissingAnchorClosesNothing() {
        // A menu still open for a tab that has since moved to another window or closed.
        List<Tab> tabs = List.of(first, second, third);

        assertThat(TabCloseTargets.others(tabs, fourth)).isEmpty();
        assertThat(TabCloseTargets.toTheRight(tabs, fourth)).isEmpty();
        assertThat(TabCloseTargets.others(tabs, null)).isEmpty();
        assertThat(TabCloseTargets.toTheRight(tabs, null)).isEmpty();
        assertThat(TabCloseTargets.others(List.of(), first)).isEmpty();
    }

    @Test
    void theAnchorIsFoundByIdentity() {
        // Two tabs with the same title are still two tabs.
        Tab twin = new Tab("first");
        List<Tab> tabs = List.of(first, twin, second);

        assertThat(TabCloseTargets.others(tabs, twin)).containsExactly(first, second).inOrder();
        assertThat(TabCloseTargets.toTheRight(tabs, twin)).containsExactly(second);
    }

    @Test
    void theTargetsAreASnapshot() {
        List<Tab> targets = TabCloseTargets.others(List.of(first, second), first);

        assertThat(targets).containsExactly(second);
        try {
            targets.add(third);
            throw new AssertionError("the targets must not be changed by the caller");
        } catch (UnsupportedOperationException expected) {
            // the funnel copies what it closes
        }
    }

    @Test
    void theSummaryAsksOnlyWhenABusyTerminalWouldHaveAsked() {
        assertThat(TabCloseTargets.needsSummaryConfirmation(1, false)).isTrue();
        assertThat(TabCloseTargets.needsSummaryConfirmation(3, false)).isTrue();
        assertWithMessage("idle terminals and other tabs close without a question, as with the close button")
            .that(TabCloseTargets.needsSummaryConfirmation(0, false)).isFalse();
        assertWithMessage("the setting that silences Close All Tabs silences this question too")
            .that(TabCloseTargets.needsSummaryConfirmation(2, true)).isFalse();
        assertThat(TabCloseTargets.needsSummaryConfirmation(0, true)).isFalse();
    }

    @Test
    void bothMenusOfferTheCommandsAndDecideTheirStateWhenTheyOpen() throws IOException {
        String window = source("MainWindow.java");

        String fileMenu = methodBody(window, "private Menu createFileMenu() {");
        assertThat(fileMenu).contains("menuItem(\"menu.file.closeOtherTabs\")");
        assertThat(fileMenu).contains("menuItem(\"menu.file.closeTabsToRight\")");
        assertThat(fileMenu).contains("closeOtherTabs(tabPane.getSelectionModel().getSelectedItem())");
        assertThat(fileMenu).contains("closeTabsToTheRight(tabPane.getSelectionModel().getSelectedItem())");
        String fileShowing = fileMenu.substring(fileMenu.indexOf("fileMenu.setOnShowing("));
        assertWithMessage("both macOS menu bars build their File menu here and set the state as it opens")
            .that(fileShowing).contains("syncTabCloseItems(selected, closeOthers, closeToRight);");
        assertThat(fileMenu).contains("closeTab, closeOthers, closeToRight, closeAllTabs");

        String contextMenu = methodBody(window, "private void setupTabContextMenu(TerminalTab terminalTab) {");
        assertThat(contextMenu).contains("I18n.get(\"tab.contextMenu.closeOthers\")");
        assertThat(contextMenu).contains("I18n.get(\"tab.contextMenu.closeToRight\")");
        assertThat(contextMenu).contains("closeOtherTabs(terminalTab)");
        assertThat(contextMenu).contains("closeTabsToTheRight(terminalTab)");
        String contextShowing = contextMenu.substring(contextMenu.indexOf("contextMenu.setOnShowing(e -> {"));
        assertThat(contextShowing).contains("syncTabCloseItems(terminalTab, closeOthersItem, closeToRightItem);");

        String sync = methodBody(window, "private void syncTabCloseItems(");
        assertThat(sync).contains("TabCloseTargets.others(tabPane.getTabs(), anchor).isEmpty()");
        assertThat(sync).contains("TabCloseTargets.toTheRight(tabPane.getTabs(), anchor).isEmpty()");
    }

    @Test
    void theCommandsCloseThroughTheUserCloseFunnelAroundTheirAnchor() throws IOException {
        String window = source("MainWindow.java");

        assertThat(methodBody(window, "private void closeOtherTabs(Tab anchor) {"))
            .contains("TabCloseTargets.others(tabPane.getTabs(), anchor), CloseCause.CLOSE_OTHERS");
        assertThat(methodBody(window, "private void closeTabsToTheRight(Tab anchor) {"))
            .contains("TabCloseTargets.toTheRight(tabPane.getTabs(), anchor), CloseCause.CLOSE_TO_RIGHT");
        String around = methodBody(window, "private void closeTabsAround(");
        assertThat(around).contains("closeTabsByUser(targets, anchor, cause)");
        assertThat(around).doesNotContain("getTabs().remove");

        String funnel = methodBody(window, "private boolean closeTabsByUser(List<Tab> tabs, Tab keepSelected, CloseCause cause) {");
        int confirm = funnel.indexOf("if (!confirmUserCloseAll(targets)) {");
        int select = funnel.indexOf("tabPane.getSelectionModel().select(keepSelected);");
        int dispose = funnel.indexOf("disposeTabContent(tab);");
        int remove = funnel.indexOf("tabPane.getTabs().removeAll(targets);");
        assertThat(confirm).isAtLeast(0);
        assertWithMessage("the anchor is selected only once every question passed")
            .that(select).isGreaterThan(confirm);
        assertWithMessage("the anchor is selected before the others go, so the selection does not wander")
            .that(select).isLessThan(dispose);
        assertWithMessage("all targets leave in a single list change")
            .that(funnel.indexOf("tabPane.getTabs().remove(")).isEqualTo(-1);
        assertThat(dispose).isLessThan(remove);
    }

    @Test
    void severalTabsAskOneQuestionForTheTerminalsAndThenTheHostedEditors() throws IOException {
        String window = source("MainWindow.java");

        String confirmAll = methodBody(window, "private boolean confirmUserCloseAll(List<Tab> targets) {");
        assertWithMessage("a single tab asks what its own close button asks")
            .that(confirmAll).contains("return confirmUserClose(targets.get(0));");
        int terminals = confirmAll.indexOf("confirmBusyTerminalsClose(targets)");
        int hosted = confirmAll.indexOf("HostedCloseGuards.confirmTabs(targets,");
        assertThat(terminals).isAtLeast(0);
        assertWithMessage("the editors' Save choice already saves, so they are asked last")
            .that(hosted).isGreaterThan(terminals);

        String summary = methodBody(window, "private boolean confirmBusyTerminalsClose(List<Tab> targets) {");
        assertWithMessage("the summary counts only the terminals whose close button would have asked")
            .that(summary).contains("terminalTab.needsCloseConfirmation()");
        assertThat(summary).contains("isCloseActiveTerminalWindowsWithoutConfirmation()");
        assertThat(summary).contains("TabCloseTargets.needsSummaryConfirmation(busyTerminals, closeActiveWithoutConfirmation)");
        assertThat(summary).contains("I18n.get(\"dialog.closeTabs.header\", targets.size())");
        assertThat(summary).contains("I18n.get(\"dialog.closeTabs.content\", busyTerminals)");
        assertWithMessage("a dismissed question keeps every tab open")
            .that(summary).contains("orElse(ButtonType.CANCEL) == ButtonType.OK");
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces in " + signature);
    }
}
