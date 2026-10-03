package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The Settings &rarr; Terminal &rarr; Links checkbox reaches every pane and takes effect without
 * reopening one: each pane gets the plain-text link kinds as a supplier that reads the global
 * setting on every click, and the dialog loads, saves and reports the setting like its neighbours.
 * Both classes need a JavaFX toolkit, so this reads their source (CRLF-safe, whitespace-insensitive).
 */
public class TerminalLinkDetectionWiringTest {

    @Test
    public void everyPaneGetsTheKindsFromTheLiveSetting() throws IOException {
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");

        int configurator = view.indexOf("splitPane = new TerminalSplitPane(providerFactory, connectorFactory, widget -> {");
        int wiring = view.indexOf("configurePlainTextLinks(widget);", configurator);
        assertThat(configurator).isAtLeast(0);
        assertThat(wiring).isGreaterThan(configurator);
        assertThat(wiring).isLessThan(view.indexOf("}, widget -> gutterMap.get(widget)", configurator));
        // A supplier, not a value captured when the pane opens: the setting is read on every click.
        // File paths only in a pane that opens files.
        assertThat(view).contains("korttyWidget.setPlainTextLinkKinds(() -> !isTerminalLinkDetectionEnabled() ? Set.of() "
            + ": opensFileLinks(widget) ? TerminalLinkResolver.WEB_AND_PATH_LINK_KINDS "
            + ": TerminalLinkResolver.WEB_LINK_KINDS);");
        assertThat(view).contains("return gs == null || gs.isTerminalLinkDetectionEnabled();");
    }

    @Test
    public void theSettingsDialogLoadsSavesAndTracksTheSetting() throws IOException {
        String dialog = source("src/main/java/de/kortty/ui/SettingsDialog.java");

        assertThat(dialog).contains("terminalLinkDetectionCheck.setSelected(globalSettings == null "
            + "|| globalSettings.isTerminalLinkDetectionEnabled());");
        assertThat(dialog).contains(
            "globalSettings.setTerminalLinkDetectionEnabled(terminalLinkDetectionCheck.isSelected());");
        assertThat(dialog).contains(
            "tracked.add(new TrackedSetting(\"terminal\", \"link_detection\", gs::isTerminalLinkDetectionEnabled, true));");
        // Under its own Links header, ahead of the SSH keep-alive section.
        int header = dialog.indexOf("new Label(I18n.get(\"settings.terminal.links.header\"))");
        int checkbox = dialog.indexOf("terminalGrid.add(terminalLinkDetectionCheck, 0, terminalRow++, 2, 1);");
        int keepAlive = dialog.indexOf("terminalGrid.add(sshKeepAliveCheck, 0, terminalRow++, 2, 1);");
        assertThat(header).isAtLeast(0);
        assertThat(checkbox).isGreaterThan(header);
        assertThat(keepAlive).isGreaterThan(checkbox);
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
