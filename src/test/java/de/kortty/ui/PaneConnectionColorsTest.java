package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The colors of a tab's split panes: a pane that runs a connection of its own (its {@link PaneOrigin})
 * whose tab color differs from the tab's is framed in that color, listed in the tab's tooltip and
 * named for screen readers; every other pane is not marked. The resolver is pure and checked with
 * plain objects standing in for panes; the wiring into the window, the tab and the terminal view,
 * which need a live stage, is pinned in their sources, line-ending agnostic.
 */
class PaneConnectionColorsTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    private static final String RED = "#D32F2F";
    private static final String GREEN = "#388E3C";

    // ---- which panes are marked ---------------------------------------------------------------

    @Test
    void aPaneThatRunsTheTabsConnectionIsNeverMarked() {
        Object first = new Object();
        Object sameServerSplit = new Object();

        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(RED, List.of(
                new PaneConnectionColors.PaneInput<>(first, null, null),
                new PaneConnectionColors.PaneInput<>(sameServerSplit, null, GREEN)), true);

        assertWithMessage("a pane without an origin of its own runs the tab's connection and sits in its frame")
                .that(mixed).isEmpty();
    }

    @Test
    void aProductionPaneInAnUncoloredTabGetsARedFrame() {
        Object first = new Object();
        Object production = new Object();

        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(null, List.of(
                new PaneConnectionColors.PaneInput<>(first, null, null),
                new PaneConnectionColors.PaneInput<>(production, connection("db-prod", "root", "db1"), RED)), true);

        assertThat(mixed).hasSize(1);
        assertThat(mixed.get(0).pane()).isSameInstanceAs(production);
        assertThat(mixed.get(0).connectionName()).isEqualTo("db-prod");
        assertThat(mixed.get(0).hex()).isEqualTo(RED);
        assertThat(mixed.get(0).frameHex()).isEqualTo(RED);
    }

    @Test
    void aPaneOfAnotherColorInAColoredTabGetsItsOwnFrame() {
        Object staging = new Object();

        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(RED, List.of(
                new PaneConnectionColors.PaneInput<>(staging, connection("staging", "deploy", "st1"), GREEN)), true);

        assertThat(mixed).hasSize(1);
        assertThat(mixed.get(0).frameHex()).isEqualTo(GREEN);
    }

    @Test
    void aPaneOfAnotherConnectionWithTheTabsColorIsNotMarked() {
        Object otherProduction = new Object();

        assertWithMessage("the tab's frame already shows the right color")
                .that(PaneConnectionColors.mixedPanes(RED, List.of(new PaneConnectionColors.PaneInput<>(
                        otherProduction, connection("db-prod", "root", "db1"), "#d32f2f")), true))
                .isEmpty();
        assertWithMessage("two connections without a color are not mixed")
                .that(PaneConnectionColors.mixedPanes(null, List.of(new PaneConnectionColors.PaneInput<>(
                        otherProduction, connection("dev", "anna", "dev1"), null)), true))
                .isEmpty();
    }

    @Test
    void anUncoloredPaneInAColoredTabIsNamedButGetsNoFrame() {
        Object dev = new Object();

        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(RED, List.of(
                new PaneConnectionColors.PaneInput<>(dev, connection("dev-box", "anna", "dev1"), null)), true);

        assertWithMessage("a pane inside a red tab that is no production server is still pointed out")
                .that(mixed).hasSize(1);
        assertThat(mixed.get(0).hex()).isNull();
        assertWithMessage("no default colors: a connection without a color never gets a frame")
                .that(mixed.get(0).frameHex()).isNull();
    }

    @Test
    void switchingTheFrameOffKeepsTheMarksButDrawsNoFrame() {
        Object production = new Object();

        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(null, List.of(
                new PaneConnectionColors.PaneInput<>(production, connection("db-prod", "root", "db1"), RED)), false);

        assertThat(mixed).hasSize(1);
        assertThat(mixed.get(0).hex()).isEqualTo(RED);
        assertThat(mixed.get(0).frameHex()).isNull();
    }

    @Test
    void colorsAreComparedAsNormalizedHexAndAnInvalidValueCountsAsNone() {
        Object pane = new Object();

        assertThat(PaneConnectionColors.mixedPanes("#f00", List.of(new PaneConnectionColors.PaneInput<>(
                pane, connection("a", "u", "h"), " #FF0000 ")), true)).isEmpty();
        // A teamwork file can carry anything; a value that is not a hex color never reaches a frame.
        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(RED, List.of(
                new PaneConnectionColors.PaneInput<>(pane, connection("a", "u", "h"), "red; -fx-effect: x")), true);
        assertThat(mixed).hasSize(1);
        assertThat(mixed.get(0).hex()).isNull();
        assertThat(mixed.get(0).frameHex()).isNull();
    }

    @Test
    void theMarkedPanesKeepThePanesOrder() {
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();

        List<PaneConnectionColors.MixedPane<Object>> mixed = PaneConnectionColors.mixedPanes(null, List.of(
                new PaneConnectionColors.PaneInput<>(a, connection("one", "u", "h1"), GREEN),
                new PaneConnectionColors.PaneInput<>(b, null, null),
                new PaneConnectionColors.PaneInput<>(c, connection("two", "u", "h2"), RED)), true);

        assertThat(mixed.stream().map(PaneConnectionColors.MixedPane::pane).toList()).containsExactly(a, c).inOrder();
        assertThat(PaneConnectionColors.mixedPanes(RED, null, true)).isEmpty();
    }

    // ---- with the real resolver --------------------------------------------------------------

    @Test
    void aSplitToASavedProductionServerIsFramedInItsSavedColor() {
        ServerConnection tab = connection("dev", "anna", "dev1");
        tab.setId("dev-id");
        ServerConnection saved = connection("db-prod", "root", "db1");
        saved.setId("prod-id");
        saved.setTabColor(RED);
        // The split dialog hands back a copy that carries the sign-in, not the color.
        ServerConnection paneCopy = connection("db-prod", "root", "db1");
        paneCopy.setId("prod-id");
        Map<String, ServerConnection> byId = Map.of("dev-id", tab, "prod-id", saved);
        Function<ServerConnection, String> colorOf = connection -> {
            ConnectionColorSupport.TabColor color =
                    ConnectionColorSupport.effectiveTabColor(connection, byId::get, null, null);
            return color != null ? color.hex() : null;
        };
        PaneConnectionColors.Scheme scheme = new PaneConnectionColors.Scheme(colorOf.apply(tab), true, colorOf);

        // The tab's pane has no origin of its own; the split recorded db-prod as its pane's.
        PaneOrigins<Object, Object> origins = new PaneOrigins<>();
        Object first = new Object();
        Object split = new Object();
        Object connector = new Object();
        origins.expect(connector, new PaneOrigin(paneCopy, null, null));
        origins.bind(split, connector);

        List<PaneConnectionColors.PaneInput<Object>> panes = List.of(
                PaneConnectionColors.input(first, connectionOf(origins, first), scheme),
                PaneConnectionColors.input(split, connectionOf(origins, split), scheme));
        List<PaneConnectionColors.MixedPane<Object>> mixed =
                PaneConnectionColors.mixedPanes(scheme.tabHex(), panes, scheme.frameEnabled());

        assertThat(scheme.tabHex()).isNull();
        assertThat(mixed).hasSize(1);
        assertThat(mixed.get(0).pane()).isSameInstanceAs(split);
        assertThat(mixed.get(0).frameHex()).isEqualTo(RED);

        // Closing the split forgets its origin: nothing is marked any more.
        origins.forget(split);
        assertThat(PaneConnectionColors.mixedPanes(scheme.tabHex(), List.of(
                PaneConnectionColors.input(first, connectionOf(origins, first), scheme)), true)).isEmpty();
    }

    @Test
    void aResolverThatFailsOrIsMissingMeansNoColor() {
        ServerConnection connection = connection("db-prod", "root", "db1");

        assertThat(new PaneConnectionColors.Scheme(RED, true, null).colorOf(connection)).isNull();
        assertThat(new PaneConnectionColors.Scheme(RED, true, c -> {
            throw new IllegalStateException("vault locked");
        }).colorOf(connection)).isNull();
        assertThat(new PaneConnectionColors.Scheme(RED, true, c -> "#abc").colorOf(connection)).isEqualTo("#AABBCC");
        assertThat(new PaneConnectionColors.Scheme(RED, true, c -> RED).colorOf(null)).isNull();
        assertWithMessage("the tab's color is normalized too")
                .that(new PaneConnectionColors.Scheme("#d32f2f", true, null).tabHex()).isEqualTo(RED);
    }

    // ---- what the marks say ------------------------------------------------------------------

    @Test
    void connectionNamesAreSanitizedCappedAndFallBackToUserAtHost() {
        ServerConnection named = connection("db‮prod\u0007", "root", "db1");
        assertThat(PaneConnectionColors.connectionName(named)).isEqualTo("dbprod");

        ServerConnection unnamed = connection(null, "root", "db1");
        assertThat(PaneConnectionColors.connectionName(unnamed)).isEqualTo("root@db1");

        ServerConnection invisible = connection("‮\u0007", "root", "db1");
        assertThat(PaneConnectionColors.connectionName(invisible)).isEqualTo("root@db1");

        ServerConnection longName = connection("x".repeat(200), "root", "db1");
        assertThat(PaneConnectionColors.connectionName(longName))
                .hasLength(PaneConnectionColors.MAX_CONNECTION_NAME_LENGTH);
    }

    @Test
    void screenReadersHearTheConnectionAndItsColorOrThatItHasNone() {
        String red = I18n.get(TabColorPresentation.familyKey(ConnectionColorSupport.Family.RED));

        assertThat(PaneConnectionColors.accessibleText(new PaneConnectionColors.MixedPane<>(new Object(), "db-prod", RED, RED)))
                .isEqualTo(I18n.get("terminal.pane.connectionColor", "db-prod", red, RED));
        assertWithMessage("the color is named, so the frame is not color alone")
                .that(PaneConnectionColors.accessibleText(new PaneConnectionColors.MixedPane<>(new Object(), "db-prod", RED, null)))
                .contains(red);
        assertThat(PaneConnectionColors.accessibleText(new PaneConnectionColors.MixedPane<>(new Object(), "dev-box", null, null)))
                .isEqualTo(I18n.get("terminal.pane.connectionNoColor", "dev-box"));
    }

    @Test
    void theTooltipLineNamesEachMixedConnectionOnce() {
        String red = I18n.get(TabColorPresentation.familyKey(ConnectionColorSupport.Family.RED));
        List<PaneConnectionColors.MixedPane<Object>> mixed = List.of(
                new PaneConnectionColors.MixedPane<>(new Object(), "db-prod", RED, RED),
                new PaneConnectionColors.MixedPane<>(new Object(), "dev-box", null, null),
                new PaneConnectionColors.MixedPane<>(new Object(), "db-prod", RED, RED));

        String expected = I18n.get("tab.tooltip.mixedConnections",
                I18n.get("tab.tooltip.mixedConnection.color", "db-prod", red, RED)
                        + PaneConnectionColors.TOOLTIP_SEPARATOR
                        + I18n.get("tab.tooltip.mixedConnection.noColor", "dev-box"));
        assertThat(PaneConnectionColors.tooltipLine(mixed)).isEqualTo(expected);
        assertThat(PaneConnectionColors.tooltipLine(List.of())).isNull();
        assertThat(PaneConnectionColors.tooltipLine(null)).isNull();
    }

    // ---- wiring ------------------------------------------------------------------------------

    @Test
    void theWindowGivesEveryTabThePaneResolverWithItsColorAndTheFrameSwitch() throws IOException {
        String apply = methodBody(source("MainWindow.java"), "private void applyConnectionColor(TerminalTab tab) {");

        assertThat(apply).contains("boolean showFrame = TabColorPresentation.frameEnabled(app.getGlobalSettingsManager().getSettings());");
        assertThat(apply).contains("tab.applyConnectionColor(color != null ? color.hex() : null,\n"
                + "                color != null ? color.source() : null, colorSourceName(color), showFrame);");
        assertThat(apply).contains("tab.applyPaneConnectionColors(color != null ? color.hex() : null, showFrame, paneConnection -> {");
        assertWithMessage("a pane's color is resolved like the tab's: saved connection, own, group, credential environment")
                .that(apply.substring(apply.indexOf("paneConnection -> {")))
                .contains("ConnectionColorSupport.TabColor paneColor = effectiveTabColor(paneConnection);");
        assertThat(apply).contains("ConnectionColorSupport.TabColor color = effectiveTabColor(tab.getConnection());");
        assertWithMessage("the refresh after saving connections, credentials or settings reaches the panes too")
                .that(methodBody(source("MainWindow.java"), "static void refreshConnectionColorsInAllWindows() {"))
                .contains("window.applyConnectionColor(terminalTab);");
    }

    @Test
    void theTerminalViewMarksPanesFromTheirOriginsWhenSplitAndClosed() throws IOException {
        String view = source("TerminalView.java");

        String refresh = methodBody(view, "private void refreshPaneConnectionColors(@Nullable SithTermFxWidget closing) {");
        assertThat(refresh).contains("PaneOrigin recorded = paneOrigins.recorded(pane);");
        assertThat(refresh).contains("if (pane == closing) {");
        assertThat(refresh).contains("split.setPaneConnectionMarks(marks);");
        assertThat(refresh).contains("PaneConnectionColors.accessibleText(pane)");
        assertThat(refresh).contains("listener.accept(PaneConnectionColors.tooltipLine(mixed));");
        assertThat(refresh).contains("Platform.runLater(() -> refreshPaneConnectionColors(closing));");

        int splitHook = view.indexOf("splitPane.setOnWidgetSplitCreated((widget, request) -> {");
        assertThat(splitHook).isAtLeast(0);
        assertWithMessage("a new pane's origin is bound before the split hook runs, so it is marked at once")
                .that(view.substring(splitHook, view.indexOf("});", splitHook)))
                .contains("refreshPaneConnectionColors(null);");
        assertThat(methodBody(view, "private void onPaneClosed(SithTermFxWidget widget) {"))
                .contains("refreshPaneConnectionColors(widget);");

        String cleanup = methodBody(view, "public void cleanup() {");
        int cleared = cleanup.indexOf("paneColorScheme = null;");
        assertWithMessage("closing the tab must not recolor its panes one by one")
                .that(cleared).isAtLeast(0);
        assertThat(cleared).isLessThan(cleanup.indexOf("splitPane.closeAll();"));
        assertThat(cleanup).contains("paneConnectionsListener = null;");
    }

    @Test
    void theTabListsThePanesInItsTooltipAndNeverFramesThemItself() throws IOException {
        String tab = source("TerminalTab.java");

        assertThat(tab).contains("this.terminalView.setPaneConnectionsListener(this::setPaneConnectionsLine);");
        assertThat(methodBody(tab, "private void refreshTooltip() {"))
                .contains("connectionColorLine, paneConnectionsLine, attentionLine);");
        assertThat(methodBody(tab, "private void setPaneConnectionsLine(String line) {")).contains("refreshTooltip();");
        assertThat(methodBody(tab, "void applyPaneConnectionColors(String tabHex, boolean showFrame,"))
                .contains("terminalView.applyPaneConnectionColors(ConnectionColorSupport.normalizeHex(tabHex), showFrame, colorOf);");
    }

    private static ServerConnection connection(String name, String user, String host) {
        ServerConnection connection = new ServerConnection();
        connection.setName(name);
        connection.setUsername(user);
        connection.setHost(host);
        return connection;
    }

    private static ServerConnection connectionOf(PaneOrigins<Object, Object> origins, Object pane) {
        PaneOrigin recorded = origins.recorded(pane);
        return recorded != null ? recorded.connection() : null;
    }

    private static String source(String file) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
