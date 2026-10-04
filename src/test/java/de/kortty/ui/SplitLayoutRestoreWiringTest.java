package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How opening a project rebuilds a tab's split panes. It used to wait one second with
 * {@code Thread.sleep} on the JavaFX thread, then split through the interactive path (a modal
 * "Connecting" stage per pane, half a second of sleep each) with the split pane's private fields set
 * by reflection, and it put every pane on the tab's server. Proving the new path live needs a server
 * and a shown window, so like {@code TerminalSplitPaneZoomTest} this test pins the sources,
 * line-ending agnostic; the plan itself is tested in {@link SplitLayoutRestorePlanTest}.
 */
class SplitLayoutRestoreWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");
    private static final Path TERMINAL_TAB = Path.of("src/main/java/de/kortty/ui/TerminalTab.java");
    private static final Path SPLIT_PANE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    @Test
    void openingAProjectRestoresTheSplitsOnTheFirstConnectWithoutSleeping() throws IOException {
        String loadProject = methodBody(read(MAIN_WINDOW), "private void restoreSavedTab(");

        assertThat(loadProject).doesNotContain("Thread.sleep");
        assertThat(loadProject).contains("restoredTab.addOnFirstConnected(() -> restoredTab.getTerminalView()");
        assertThat(loadProject).contains(".restoreSplitLayout(splitState,");
        assertThat(read(MAIN_WINDOW)).doesNotContain("restoreSplitState(");
        assertWithMessage("an incomplete restore is reported in the status bar")
            .that(methodBody(read(MAIN_WINDOW), "private void reportSplitLayoutRestore("))
            .contains("I18n.get(\"project.splitRestore.incomplete\"");
    }

    @Test
    void theFirstConnectActionsRunOnceAndDieWithTheTab() throws IOException {
        String tab = read(TERMINAL_TAB);

        assertThat(tab).contains("new OneShotRunnables(Platform::runLater)");
        assertThat(methodBody(tab, "void releaseResources(")).contains("firstConnectedActions.clear();");
        String connected = tab.substring(tab.indexOf("terminalView.setOnConnectedCallback(() -> {"));
        connected = connected.substring(0, connected.indexOf("terminalView.setOnCloseTabRequest("));
        assertThat(connected).contains("firstConnectedActions.fire();");
    }

    @Test
    void thePanesConnectInTheBackgroundAndAttachInOneHop() throws IOException {
        String view = read(TERMINAL_VIEW);
        String restore = methodBody(view, "public void restoreSplitLayout(");

        assertThat(restore).contains("SplitLayoutRestorePlan.plan(state)");
        assertThat(restore).contains("new Thread(() -> {");
        assertThat(restore).contains("worker.setDaemon(true);");
        assertThat(restore).contains("prepareRestoredSplitPane(step, tab, tabConnectionId)");
        assertThat(restore).contains("attachRestoredSplitPane(source, step.orientation(), prepared)");
        assertWithMessage("a closed tab stops the restore before its next pane").that(restore).contains("if (cleanedUp) {");
        assertThat(methodBody(view, "public void cleanup(")).contains("cleanedUp = true;");

        String attach = methodBody(view, "private @Nullable SithTermFxWidget attachRestoredSplitPane(");
        int expect = attach.indexOf("paneOrigins.expect(connector, prepared.ownOrigin());");
        int split = attach.indexOf("attachSplitPane(source, orientation, connector)");
        assertWithMessage("the origin is recorded before the decorator binds the new pane").that(expect).isAtLeast(0);
        assertThat(split).isGreaterThan(expect);
        assertThat(attach).contains("discardRestoredSplitConnector(connector);");

        assertThat(view).doesNotContain("Thread.sleep(500)");
        assertThat(view).doesNotContain("restoreSplitRecursive");
    }

    @Test
    void anUnexpectedFailureCostsOnePaneAndTheRestoreStillFinishes() throws IOException {
        String restore = methodBody(read(TERMINAL_VIEW), "public void restoreSplitLayout(");

        int prepare = restore.indexOf("prepared = prepareRestoredSplitPane(step, tab, tabConnectionId);");
        int caught = restore.indexOf("} catch (RuntimeException e) {", prepare);
        assertWithMessage("a failing pane is skipped, not the worker thread killed").that(prepare).isAtLeast(0);
        assertThat(caught).isGreaterThan(prepare);
        assertThat(restore.substring(caught)).contains("PreparedSplitPane.skipped(SplitLayoutRestorePlan.SkipReason.FAILED)");

        String finallyBlock = restore.substring(restore.lastIndexOf("} finally {"));
        assertWithMessage("the tab always stops waiting for its layout and the status bar always hears the outcome")
            .that(finallyBlock).contains("runOnFxThread(() -> finishSplitLayoutRestore(plan, opened, skipped, onDone));");
    }

    @Test
    void aRestoredPaneNeverAsksAndPassesThePolicy() throws IOException {
        String prepare = methodBody(read(TERMINAL_VIEW), "private PreparedSplitPane prepareRestoredSplitPane(");

        assertThat(prepare).contains("ConnectionAuthResolver.NO_PROMPTS");
        assertThat(prepare).contains(".resolveById(connectionId, false)");
        assertThat(prepare).contains("SplitConnectionPolicy.blockedTarget(origin.connection())");
        assertThat(prepare).contains("!origin.temporaryKey().isValid()");
        assertWithMessage("the tab's access-reason memory is set by createConnectorForConnection")
            .that(prepare).contains("createConnectorForConnection(origin.connection(), origin.password())");
        assertThat(prepare).contains("connectConnector(connector)");
        for (String modal : new String[] {"connectingStage", "showAndWait", "Alert", "showBlockedServerDialog"}) {
            assertWithMessage("a restore shows no dialog of its own: " + modal).that(prepare).doesNotContain(modal);
        }
    }

    @Test
    void theSavedDividersAreSetAfterTheSplitControlsResetThem() throws IOException {
        String finish = methodBody(read(TERMINAL_VIEW), "private void finishSplitLayoutRestore(");

        assertThat(finish).contains("Platform.runLater(() -> Platform.runLater(() -> {");
        assertThat(finish).contains("tree.applyDividerPositions(target)");
        assertThat(finish).contains("plan.realized(opened::containsKey).map(opened::get)");
        assertThat(finish.indexOf("tree.applyDividerPositions(target)"))
            .isGreaterThan(finish.indexOf("Platform.runLater(() -> Platform.runLater(() -> {"));
    }

    @Test
    void aNewPaneRunsItsBeforeStartStepJustBeforeItsSession() throws IOException {
        String createWidget = methodBody(read(SPLIT_PANE), "private @NotNull SithTermFxWidget createWidget(@Nullable SplitRequest request,\n");

        int set = createWidget.indexOf("widget.setTtyConnector(");
        int before = createWidget.indexOf("beforeStart.accept(widget);");
        int start = createWidget.indexOf("widget.start();");
        assertThat(set).isAtLeast(0);
        assertThat(before).isGreaterThan(set);
        assertThat(start).isGreaterThan(before);
    }

    private static String read(Path path) throws IOException {
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
