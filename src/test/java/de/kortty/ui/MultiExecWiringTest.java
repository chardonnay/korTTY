package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How multi-exec is wired into the panes, tabs and windows: every pane of every tab is registered
 * with the coordinator and forgotten when it closes, every split pane asks the coordinator as its
 * input mirror, only typed keys are mirrored (never paste, snippets or input-method text), the
 * markers follow every change in every window, and nothing new is sent to telemetry. Proving this
 * live needs windows and connections ({@code multiExecSmoke} does part of it), so the sources are
 * pinned, line-ending agnostic; the membership itself is tested in {@link MultiExecMembershipTest}.
 */
class MultiExecWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");
    private static final Path SPLIT_PANE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    void everyPaneOfEveryTabIsRegisteredAndForgottenWhenItCloses() throws IOException {
        String view = source(UI.resolve("TerminalView.java"));
        int configurator = view.indexOf("splitPane = new TerminalSplitPane(providerFactory, connectorFactory, widget -> {");
        assertThat(configurator).isAtLeast(0);
        assertWithMessage("the widget configurator runs for the first pane and every split, whatever made it")
            .that(view.substring(configurator, view.indexOf("});", configurator)))
            .contains("MultiExecCoordinator.shared().register(widget, () -> splitPane);");
        assertWithMessage("onPaneClosed runs for a closed split, every pane of a closed tab and a failed split")
            .that(methodBody(view, "private void releasePaneState(SithTermFxWidget widget) {"))
            .contains("MultiExecCoordinator.shared().forget(widget);");
        assertThat(view).contains("splitPane.setOnWidgetClosed(this::onPaneClosed);");
        assertThat(methodBody(view, "private void onPaneClosed(SithTermFxWidget widget) {"))
            .contains("releasePaneState(widget);");
    }

    @Test
    void everySplitPaneAsksTheCoordinatorAndOffersTheToggleInExtras() throws IOException {
        String view = source(UI.resolve("TerminalView.java"));
        assertThat(view).contains("splitPane.setInputMirror(MultiExecCoordinator.shared());");
        assertThat(view).contains("splitPane.setMirrorMenuItemsFactory(this::multiExecMenuItems);");
        String items = methodBody(view, "private List<javafx.scene.control.MenuItem> multiExecMenuItems(");
        assertWithMessage("the check mark shows the pane's membership").that(items)
            .contains("include.setSelected(multiExec.isMember(widget));");
        assertWithMessage("the members decide, never the flipped check item").that(items)
            .contains("include.setOnAction(event -> multiExec.togglePane(widget));");
        assertWithMessage("switching broadcast mode updates the tab markers and the status bars")
            .that(view).contains("MultiExecCoordinator.shared().refreshMarkers();");
    }

    @Test
    void onlyTypedKeysAreMirroredNeverPasteSnippetsOrInputMethodText() throws IOException {
        String splitPane = source(SPLIT_PANE);
        assertWithMessage("no input-method text reaches the mirror").that(splitPane).doesNotContain("InputMethodEvent");
        Matcher calls = Pattern.compile("broadcastToOthers\\(widget, ").matcher(splitPane);
        int count = 0;
        while (calls.find()) {
            count++;
            String around = splitPane.substring(Math.max(0, calls.start() - 2500), calls.start());
            assertWithMessage("every mirror call sits in a key filter")
                .that(around.contains("addEventFilter(KeyEvent.KEY_TYPED") || around.contains(
                    "private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {"))
                .isTrue();
        }
        assertWithMessage("typed characters, Enter/Backspace/Esc and the navigation keys").that(count).isEqualTo(3);
        assertWithMessage("a paste, a snippet and the Control API write to the pane's own connector only")
            .that(methodBody(splitPane, "private void broadcastToOthers(@NotNull SithTermFxWidget sourceWidget, @NotNull String data) {"))
            .contains("if (!isMirroring(sourceWidget)) return;");
    }

    @Test
    void theCoordinatorRedrawsThePaneMarkersBeforeTellingTheWindows() throws IOException {
        String coordinator = source(UI.resolve("MultiExecCoordinator.java"));
        assertThat(methodBody(coordinator, "MultiExecCoordinator(@NotNull BooleanSupplier joinAllowed) {"))
            .contains("membership.addListener(this::refreshPaneMarkers);");
        assertThat(methodBody(coordinator, "private void refreshPaneMarkers() {"))
            .contains("owner.refreshMirrorMarkers();");
        String toggle = methodBody(coordinator, "public boolean togglePane(@Nullable SithTermFxWidget pane) {");
        assertWithMessage("a pane that is not open in a tab cannot join")
            .that(toggle).contains("!owners.containsKey(pane)");
        assertWithMessage("a closed pane is not mirrored into")
            .that(methodBody(coordinator, "public @NotNull List<SithTermFxWidget> otherMembers("))
            .contains("others.removeIf(member -> !owners.containsKey(member));");
    }

    @Test
    void everyWindowShowsTheChipAndTheMarkersAndLetsGoWhenItCloses() throws IOException {
        String window = source(UI.resolve("MainWindow.java"));
        String install = methodBody(window, "private void installMultiExecStatusBar(Region statusSpacer) {");
        assertThat(install).contains("multiExecStatusBar.setOnStop(() -> MultiExecCoordinator.shared().stop());");
        assertThat(install).contains("multiExecListener = MultiExecCoordinator.shared().addListener(this::onMultiExecChanged);");
        String changed = methodBody(window, "private void onMultiExecChanged() {");
        for (String step : new String[] {"refreshMultiExecStatus();", "refreshMirrorTabMarkers();",
                "syncMultiExecMenuItems();", "dashboardView.refreshRows();"}) {
            assertThat(changed).contains(step);
        }
        String close = window.substring(window.indexOf("recordClosedWindow(willCloseApplication());"));
        assertWithMessage("the window's panes leave with its tabs before it unsubscribes")
            .that(close.indexOf("releaseMultiExecListener();")).isGreaterThan(close.indexOf("closeAllTabs();"));
        assertWithMessage("a tab moved to another window keeps its panes; the window counts are redrawn")
            .that(window).contains("MultiExecCoordinator.shared().refreshMarkers();");
        assertWithMessage("the held count follows coding agents that start or stop waiting")
            .that(methodBody(window, "public void onCodingAgentsChanged() {")).contains("refreshMultiExecStatus();");
    }

    @Test
    void theTabAndDashboardTogglesDecideFromTheMembersAndAreNotCounted() throws IOException {
        String window = source(UI.resolve("MainWindow.java"));
        String tabMenu = methodBody(window, "private void setupTabContextMenu(TerminalTab terminalTab) {");
        assertThat(tabMenu).contains("MultiExecCoordinator.shared().toggleAll(view.getOrderedWidgets());");
        assertThat(tabMenu).contains("multiExecItem.setSelected(multiExecView != null");

        String action = methodBody(window,
            "private void handleDashboardAction(TerminalTab terminalTab, DashboardView.DashboardAction action) {");
        assertWithMessage("multi-exec sends no telemetry in this release")
            .that(action.indexOf("DashboardView.DashboardAction.TOGGLE_MULTI_EXEC"))
            .isLessThan(action.indexOf("Telemetry.track("));
        String paneAction = methodBody(window, "private void handleDashboardPaneAction(");
        assertThat(paneAction.indexOf("DashboardView.PaneAction.TOGGLE_MULTI_EXEC"))
            .isLessThan(paneAction.indexOf("Telemetry.track("));
        assertThat(paneAction).contains("MultiExecCoordinator.shared().togglePane(widget);");
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the brace closing its body. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
