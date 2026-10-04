package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Starting a saved Local Shell connection from Quick Connect: the form loads the saved shell, custom
 * command and start directory, and the copy Quick Connect launches carries them. Before, the form
 * never loaded them and the copy never set them, so a saved shell such as fish started the platform
 * default instead. The decisions are checked on the toolkit-free helpers; the dialog wiring, which
 * no headless test can build, is pinned in the source.
 */
class QuickConnectLocalShellTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void aSavedFishShellStartsFishInItsStartDirectory() {
        ServerConnection saved = localShell("/usr/bin/fish", "/srv/work");

        LocalShellPresetSupport.Selection shown = shownFor(saved, null, null, null);
        ServerConnection launched = launch(saved, ConnectionProtocol.LOCAL_SHELL, shown, null, null, null);

        assertWithMessage("fish is never a preset, so the form shows it as the custom command")
            .that(shown.preset()).isEqualTo(LocalShellPresetSupport.CUSTOM);
        assertThat(shown.customCommand()).isEqualTo("/usr/bin/fish");
        assertThat(shown.workingDirectory()).isEqualTo("/srv/work");
        assertThat(launched.getLocalShellCommand()).isEqualTo("/usr/bin/fish");
        assertThat(launched.getLocalShellWorkingDirectory()).isEqualTo("/srv/work");
    }

    @Test
    void aSavedShellWithoutACommandKeepsThePlatformDefault() {
        ServerConnection saved = localShell(null, null);

        ServerConnection launched = launch(saved, ConnectionProtocol.LOCAL_SHELL,
            shownFor(saved, null, null, null), null, null, null);

        assertWithMessage("the form shows the first preset, but the saved connection names no shell")
            .that(launched.getLocalShellCommand()).isNull();
        assertThat(launched.getLocalShellWorkingDirectory()).isNull();
    }

    @Test
    void anUntouchedPresetKeepsArgumentsThePresetListCannotShow() {
        // On Windows these map to the WSL and Git Bash presets, whose own commands have no
        // arguments; elsewhere they are custom commands. Either way the saved command must start.
        String wsl = "wsl.exe -d Ubuntu";
        ServerConnection savedWsl = localShell(wsl, null);
        String gitBash = "\"C:\\Program Files\\Git\\bin\\bash.exe\" --login -i";
        String detectedGitBash = "C:\\Program Files\\Git\\bin\\bash.exe";
        ServerConnection savedGitBash = localShell(gitBash, null);

        ServerConnection launchedWsl = launch(savedWsl, ConnectionProtocol.LOCAL_SHELL,
            shownFor(savedWsl, null, null, "wsl.exe"), null, null, "wsl.exe");
        ServerConnection launchedGitBash = launch(savedGitBash, ConnectionProtocol.LOCAL_SHELL,
            shownFor(savedGitBash, detectedGitBash, null, null), detectedGitBash, null, null);

        assertThat(launchedWsl.getLocalShellCommand()).isEqualTo(wsl);
        assertThat(launchedGitBash.getLocalShellCommand()).isEqualTo(gitBash);
    }

    @Test
    void aShellChosenInTheFormIsWhatStarts() {
        ServerConnection saved = localShell("/usr/bin/fish", "/srv/work");
        String firstPreset = LocalShellPresetSupport.presetsForCurrentOs(null, null, null).get(0);

        ServerConnection presetChosen = launch(saved, ConnectionProtocol.LOCAL_SHELL,
            new LocalShellPresetSupport.Selection(firstPreset, "/usr/bin/fish", "/srv/work"), null, null, null);
        ServerConnection customChosen = launch(saved, ConnectionProtocol.LOCAL_SHELL,
            new LocalShellPresetSupport.Selection(LocalShellPresetSupport.CUSTOM, "  /bin/dash ", "  /tmp/other "),
            null, null, null);

        assertThat(presetChosen.getLocalShellCommand()).isEqualTo(firstPreset);
        assertThat(customChosen.getLocalShellCommand()).isEqualTo("/bin/dash");
        assertThat(customChosen.getLocalShellWorkingDirectory()).isEqualTo("/tmp/other");
    }

    @Test
    void aClearedStartDirectoryIsDropped() {
        ServerConnection saved = localShell("/usr/bin/fish", "/srv/work");

        ServerConnection launched = launch(saved, ConnectionProtocol.LOCAL_SHELL,
            new LocalShellPresetSupport.Selection(LocalShellPresetSupport.CUSTOM, "/usr/bin/fish", "   "),
            null, null, null);

        assertThat(launched.getLocalShellCommand()).isEqualTo("/usr/bin/fish");
        assertThat(launched.getLocalShellWorkingDirectory()).isNull();
    }

    @Test
    void aSavedSshConnectionSwitchedToLocalShellStartsTheShownPreset() {
        ServerConnection saved = new ServerConnection("Server", "server.example.test", 22, "demo");
        String firstPreset = LocalShellPresetSupport.presetsForCurrentOs(null, null, null).get(0);

        ServerConnection launched = launch(saved, ConnectionProtocol.LOCAL_SHELL,
            shownFor(saved, null, null, null), null, null, null);

        assertWithMessage("nothing to keep: the saved connection was no local shell")
            .that(launched.getLocalShellCommand()).isEqualTo(firstPreset);
    }

    @Test
    void sshAndMoshCopiesCarryTheSavedShellUnchanged() {
        ServerConnection saved = localShell("/usr/bin/fish", "/srv/work");

        for (ConnectionProtocol protocol : new ConnectionProtocol[] {
                ConnectionProtocol.SSH_TCP, ConnectionProtocol.MOSH, ConnectionProtocol.MOSH_CLIENT}) {
            ServerConnection launched = launch(saved, protocol,
                new LocalShellPresetSupport.Selection(LocalShellPresetSupport.CUSTOM, "/bin/dash", ""),
                null, null, null);

            assertWithMessage("%s", protocol).that(launched.getLocalShellCommand()).isEqualTo("/usr/bin/fish");
            assertWithMessage("%s", protocol).that(launched.getLocalShellWorkingDirectory()).isEqualTo("/srv/work");
        }
    }

    @Test
    void aLocalShellWithoutHostOrUsernameStillMatchesItsForm() {
        // createIndividualResult compared selected.getHost().equals(...), which threw for a saved
        // connection without a host.
        ServerConnection saved = localShell("/usr/bin/fish", null);
        saved.setHost(null);
        saved.setUsername(null);

        assertThat(QuickConnectDialog.formMatchesSaved(saved, "", saved.getPort(), "")).isTrue();
        assertThat(QuickConnectDialog.formMatchesSaved(saved, null, saved.getPort(), null)).isTrue();
        assertThat(QuickConnectDialog.formMatchesSaved(saved, "", saved.getPort() + 1, "")).isFalse();
        assertThat(QuickConnectDialog.formMatchesSaved(saved, "other.example.test", saved.getPort(), "")).isFalse();
    }

    @Test
    void anSshFormMatchesOnHostPortAndUsername() {
        ServerConnection saved = new ServerConnection("Server", "server.example.test", 2222, "demo");

        assertThat(QuickConnectDialog.formMatchesSaved(saved, "  server.example.test ", 2222, " demo ")).isTrue();
        assertThat(QuickConnectDialog.formMatchesSaved(saved, "server.example.test", 22, "demo")).isFalse();
        assertThat(QuickConnectDialog.formMatchesSaved(saved, "server.example.test", 2222, "root")).isFalse();
    }

    @Test
    void choosingASavedConnectionLoadsItsShellIntoTheForm() throws IOException {
        String dialog = source("QuickConnectDialog.java");
        String fill = region(dialog, "private void fillFormWithConnection(ServerConnection conn) {", "\n    }\n");
        String load = region(dialog, "private void loadLocalShellSelection(ServerConnection conn) {", "\n    }\n");

        assertThat(fill).contains("loadLocalShellSelection(conn);");
        assertWithMessage("the connect button's listener trims the host text, so it must never be null")
            .that(fill).contains("hostField.setText(Objects.requireNonNullElse(conn.getHost(), \"\"));");
        assertThat(load).contains("LocalShellPresetSupport.selectionFor(");
        assertWithMessage("every control is set, so nothing is left over from the connection chosen before")
            .that(load).contains("shellPresetCombo.setValue(shown.preset());");
        assertThat(load).contains("customShellCommandField.setText(shown.customCommand());");
        assertThat(load).contains("shellWorkingDirField.setText(shown.workingDirectory());");
    }

    @Test
    void everySavedConnectionBranchCarriesTheShell() throws IOException {
        String dialog = source("QuickConnectDialog.java");
        String baseCopy = region(dialog, "private ServerConnection baseCopyOf(", "\n    }\n");
        String individual = region(dialog, "private ConnectionResult createIndividualResult() {", "\n    }\n");

        int protocol = baseCopy.indexOf("modified.setProtocol(");
        int shell = baseCopy.indexOf("applyLocalShellSelection(modified, selected, shownLocalShellSelection(),");
        assertThat(protocol).isAtLeast(0);
        assertWithMessage("the shell is decided by the copy's protocol, so it is set after it")
            .that(shell).isGreaterThan(protocol);
        assertThat(individual).contains("formMatchesSaved(selected,");
        assertThat(individual).doesNotContain("selected.getHost().equals(");
        assertWithMessage("a typed-in local shell reads the same controls")
            .that(individual).contains("LocalShellPresetSupport.Selection shown = shownLocalShellSelection();");
    }

    @Test
    void theConnectionManagerImportKeepsTheProtocolAndShell() throws IOException {
        String importer = region(source("ConnectionManagerDialog.java"),
            "private List<ServerConnection> importConnectionsFromFile(", "\n    }\n");

        assertWithMessage("copyForImport reads back the protocol and shell; ServerConnectionCopyPolicyTest pins it")
            .that(importer).contains("ServerConnection.copyForImport(conn,");
        assertThat(importer).doesNotContain("new ServerConnection()");
    }

    private static ServerConnection localShell(String command, String workingDirectory) {
        ServerConnection connection = new ServerConnection();
        connection.setName("Local");
        connection.setHost("");
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand(command);
        connection.setLocalShellWorkingDirectory(workingDirectory);
        return connection;
    }

    /** What fillFormWithConnection puts into the shell controls for {@code saved}. */
    private static LocalShellPresetSupport.Selection shownFor(ServerConnection saved,
            String gitBash, String cygwin, String wsl) {
        return LocalShellPresetSupport.selectionFor(saved.getLocalShellCommand(),
            saved.getLocalShellWorkingDirectory(), gitBash, cygwin, wsl);
    }

    /** The copy baseCopyOf builds, reduced to its protocol and shell. */
    private static ServerConnection launch(ServerConnection saved, ConnectionProtocol protocol,
            LocalShellPresetSupport.Selection shown, String gitBash, String cygwin, String wsl) {
        ServerConnection target = new ServerConnection();
        target.setProtocol(protocol);
        QuickConnectDialog.applyLocalShellSelection(target, saved, shown, gitBash, cygwin, wsl);
        return target;
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
