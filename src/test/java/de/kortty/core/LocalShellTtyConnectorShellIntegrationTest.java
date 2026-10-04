package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import de.kortty.shellintegration.ShellIntegrationInjection.Support;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Which local shells korTTY starts with its shell-integration wrapper, and that a real one gets it and
 * loses the wrapper folder again: only a local shell connection that opted in, never one shared through
 * Teamwork, never while shell integration is switched off, and never an SSH or Mosh connection, whose
 * connectors have no way to do it at all (commit 05f5a138).
 */
class LocalShellTtyConnectorShellIntegrationTest {

    private Path root;

    @BeforeMethod
    void setUp() throws IOException {
        root = Files.createTempDirectory("kortty-si-root");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void onlyALocalShellThatOptedInOutsideTeamworkWithShellIntegrationOn() {
        ServerConnection local = localShell("/bin/zsh");
        assertThat(LocalShellTtyConnector.wantsShellIntegrationInjection(local, true)).isFalse();

        local.setShellIntegrationAutoInject(true);
        assertThat(LocalShellTtyConnector.wantsShellIntegrationInjection(local, true)).isTrue();
        assertThat(LocalShellTtyConnector.wantsShellIntegrationInjection(local, false)).isFalse();
        assertThat(LocalShellTtyConnector.wantsShellIntegrationInjection(null, true)).isFalse();

        local.setConnectionSource(ConnectionSource.TEAMWORK);
        assertThat(LocalShellTtyConnector.wantsShellIntegrationInjection(local, true)).isFalse();

        for (ConnectionProtocol remote : new ConnectionProtocol[] {
                ConnectionProtocol.SSH_TCP, ConnectionProtocol.MOSH, ConnectionProtocol.MOSH_CLIENT}) {
            ServerConnection connection = localShell("/bin/zsh");
            connection.setShellIntegrationAutoInject(true);
            connection.setProtocol(remote);
            assertWithMessage("%s", remote)
                .that(LocalShellTtyConnector.wantsShellIntegrationInjection(connection, true)).isFalse();
        }
    }

    @Test
    void theRemoteConnectorsNeverReachTheWrapper() throws IOException {
        for (String connector : new String[] {"SshTtyConnector", "Mosh4jTtyConnector", "NativeMoshTtyConnector"}) {
            Path source = Path.of("src/main/java/de/kortty/core", connector + ".java");
            if (!Files.exists(source)) {
                continue;
            }
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertWithMessage(connector).that(text).doesNotContain("ShellIntegrationInjection");
            assertWithMessage(connector).that(text).doesNotContain("ShellIntegrationWrapperDirectory");
            assertWithMessage(connector).that(text).doesNotContain("ShellIntegrationAutoInject");
        }
    }

    @Test
    void theEditorAsksTheSameRulesTheShellStartsBy() {
        assertThat(LocalShellTtyConnector.shellIntegrationSupport("/bin/bash -c make")).isEqualTo(Support.OTHER_ARGUMENTS);
        assertThat(LocalShellTtyConnector.shellIntegrationSupport("ssh admin@host")).isEqualTo(Support.OTHER_SHELL);
        assertThat(LocalShellTtyConnector.shellIntegrationSupport("powershell.exe")).isEqualTo(Support.OTHER_SHELL);
        if (!LocalShellTtyConnector.isWindows()) {
            assertThat(LocalShellTtyConnector.shellIntegrationSupport("zsh")).isEqualTo(Support.SUPPORTED);
            assertThat(LocalShellTtyConnector.shellIntegrationSupport("/bin/bash --login")).isEqualTo(Support.SUPPORTED);
        }
    }

    @Test(timeOut = 60_000)
    void aTeamworkOrUnsupportedShellStartsWithoutAWrapperFolder() throws Exception {
        requireShell("/bin/bash");
        ServerConnection teamwork = localShell("/bin/bash");
        teamwork.setShellIntegrationAutoInject(true);
        teamwork.setConnectionSource(ConnectionSource.TEAMWORK);
        assertStartsWithoutWrapper(teamwork);

        ServerConnection norc = localShell("/bin/bash --norc");
        norc.setShellIntegrationAutoInject(true);
        assertStartsWithoutWrapper(norc);

        ServerConnection notOptedIn = localShell("/bin/bash");
        assertStartsWithoutWrapper(notOptedIn);
    }

    @Test(timeOut = 60_000)
    void aLocalZshGetsTheSnippetGivesZdotdirBackAndLosesItsFolderOnClose() throws Exception {
        requireShell("/bin/zsh");
        ServerConnection connection = localShell("/bin/zsh");
        connection.setShellIntegrationAutoInject(true);
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        connector.setShellIntegrationRoot(root);
        StringBuffer output = new StringBuffer();
        Path folder;
        try {
            assertThat(connector.connect()).isTrue();
            folder = connector.shellIntegrationWrapperPath();
            assertThat(folder).isNotNull();
            assertThat(folder.getParent()).isEqualTo(root);
            assertThat(Files.isRegularFile(folder.resolve("zsh/.zshrc"))).isTrue();
            drain(connector, output);

            // "KS""I" keeps the echoed command line from matching what the shell prints.
            connector.write("print -r -- \"KS\"\"I=${__kortty_si_loaded-none}=${ZDOTDIR-unset}=END\"\r");
            Matcher result = await(output, Pattern.compile("KSI=([^=\\r\\n]*)=([^\\r\\n]*?)=END"));
            assertThat(result.group(1)).isEqualTo("1");
            assertThat(result.group(2)).doesNotContain(folder.getFileName().toString());
            assertThat(output.toString()).contains("\u001b]133;");
        } finally {
            connector.close();
        }
        assertThat(Files.exists(folder)).isFalse();
    }

    @Test(timeOut = 60_000)
    void aLocalBashGetsTheSnippetWhenItIsRecentEnough() throws Exception {
        requireShell("/bin/bash");
        ServerConnection connection = localShell("/bin/bash");
        connection.setShellIntegrationAutoInject(true);
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        connector.setShellIntegrationRoot(root);
        StringBuffer output = new StringBuffer();
        Path folder;
        try {
            assertThat(connector.connect()).isTrue();
            folder = connector.shellIntegrationWrapperPath();
            assertThat(folder).isNotNull();
            assertThat(Files.isRegularFile(folder.resolve("bashrc"))).isTrue();
            drain(connector, output);

            connector.write("echo \"KS\"\"B=${__kortty_si_loaded-none}=${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}=END\"\r");
            Matcher result = await(output, Pattern.compile("KSB=([^=\\r\\n]*)=(\\d+)\\.(\\d+)=END"));
            int major = Integer.parseInt(result.group(2));
            int minor = Integer.parseInt(result.group(3));
            boolean recent = major > 4 || (major == 4 && minor >= 4);
            // bash 3.2, which macOS ships, ignores the snippet; the wrapper still ran.
            assertThat(result.group(1)).isEqualTo(recent ? "1" : "none");
        } finally {
            connector.close();
        }
        assertThat(Files.exists(folder)).isFalse();
    }

    private void assertStartsWithoutWrapper(ServerConnection connection) throws Exception {
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        connector.setShellIntegrationRoot(root);
        try {
            assertThat(connector.connect()).isTrue();
            assertThat(connector.shellIntegrationWrapperPath()).isNull();
            try (Stream<Path> entries = Files.list(root)) {
                assertThat(entries.count()).isEqualTo(0);
            }
        } finally {
            connector.close();
        }
    }

    private static void drain(LocalShellTtyConnector connector, StringBuffer output) {
        Thread reader = new Thread(() -> {
            char[] buffer = new char[4096];
            try {
                int count;
                while ((count = connector.read(buffer, 0, buffer.length)) >= 0) {
                    output.append(buffer, 0, count);
                }
            } catch (Exception ignored) {
                // closed
            }
        }, "test-shell-integration-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static Matcher await(StringBuffer output, Pattern pattern) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return matcher;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No " + pattern + " in the shell's output: " + output);
    }

    private static void requireShell(String path) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("POSIX shells only");
        }
        if (!Files.isExecutable(Path.of(path))) {
            throw new SkipException(path + " is not installed");
        }
    }

    private static ServerConnection localShell(String command) {
        ServerConnection connection = new ServerConnection();
        connection.setName("local");
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand(command);
        return connection;
    }
}
