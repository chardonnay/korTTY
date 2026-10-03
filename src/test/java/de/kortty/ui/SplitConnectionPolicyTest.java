package de.kortty.ui;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyLoadResult;
import de.kortty.policy.PolicyLoader;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * "Split with new connection" must honour the enterprise server policy like Quick Connect: a
 * blocked target or jump host is refused before any connector is built. The decision is checked
 * against real deny-list policies; the wiring is pinned in the MainWindow and TerminalView sources,
 * because the split dialog cannot run without a live stage.
 */
class SplitConnectionPolicyTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    @Test
    void aBlockedTargetIsRefusedWithItsHostAndPort() throws IOException {
        EffectivePolicy policy = denyList("vault.acme.com");
        assertThat(SplitConnectionPolicy.blockedTarget(ssh("vault.acme.com", 2222), policy))
            .hasValue("vault.acme.com:2222");
        assertThat(SplitConnectionPolicy.blockedTarget(ssh("web.acme.com", 22), policy)).isEmpty();
    }

    @Test
    void aBlockedJumpHostIsRefusedEvenWhenTheTargetIsAllowed() throws IOException {
        EffectivePolicy policy = denyList("bastion.blocked.com");
        ServerConnection viaJump = ssh("web.acme.com", 22);
        JumpServer jump = new JumpServer("bastion.blocked.com", 22, "ops");
        jump.setEnabled(true);
        viaJump.setJumpServer(jump);
        assertThat(SplitConnectionPolicy.blockedTarget(viaJump, policy)).hasValue("bastion.blocked.com:22");

        // A disabled jump server is never contacted, so it does not block the split.
        jump.setEnabled(false);
        assertThat(SplitConnectionPolicy.blockedTarget(viaJump, policy)).isEmpty();
    }

    @Test
    void localShellsAndMissingConnectionsAreNeverBlocked() {
        ServerConnection localShell = new ServerConnection();
        localShell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        localShell.setHost("anything");
        assertThat(SplitConnectionPolicy.blockedTarget(localShell, EffectivePolicy.lockdown())).isEmpty();
        assertThat(SplitConnectionPolicy.blockedTarget(null, EffectivePolicy.lockdown())).isEmpty();
        assertThat(SplitConnectionPolicy.blockedTarget(ssh("web.acme.com", 22), EffectivePolicy.lockdown()))
            .hasValue("web.acme.com:22");
        assertThat(SplitConnectionPolicy.blockedTarget(ssh("web.acme.com", 22), EffectivePolicy.unrestricted()))
            .isEmpty();
    }

    @Test
    void theSplitDialogRefusesABlockedTargetBeforeAnyConnectorIsBuilt() throws IOException {
        String split = methodBody(source("MainWindow.java"),
            "private TerminalView.ConnectionResult requestNewConnectionForSplit(");
        int gate = split.indexOf("SplitConnectionPolicy.blockedTarget(result.connection())");
        assertWithMessage("requestNewConnectionForSplit asks the split policy seam").that(gate).isAtLeast(0);
        int dialog = split.indexOf("PolicyUiSupport.showBlockedServerDialog(", gate);
        int refuse = split.indexOf("return null;", dialog);
        assertThat(dialog).isGreaterThan(gate);
        assertThat(refuse).isGreaterThan(dialog);
        // Nothing is prompted, persisted or handed to TerminalView before the gate.
        assertThat(split.indexOf("ensurePasswordForConnection(")).isGreaterThan(refuse);
        assertThat(split.indexOf("addConnection(")).isGreaterThan(refuse);
        assertThat(split.indexOf("new TerminalView.ConnectionResult(")).isGreaterThan(refuse);

        // TerminalView treats the refused (null) result as cancelled and builds no connector.
        String connect = methodBody(source("TerminalView.java"),
            "private @Nullable TtyConnector doCreateNewConnectionForSplit(");
        int cancelled = connect.indexOf("if (connResult == null)");
        assertThat(cancelled).isAtLeast(0);
        assertThat(connect.indexOf("return null;", cancelled))
            .isLessThan(connect.indexOf("createConnectorForConnection("));
    }

    private static ServerConnection ssh(String host, int port) {
        ServerConnection connection = new ServerConnection();
        connection.setHost(host);
        connection.setPort(port);
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        return connection;
    }

    private static EffectivePolicy denyList(String host) throws IOException {
        Path file = Files.createTempFile("kortty-split-policy", ".toml");
        try {
            Files.writeString(file, """
                [meta]
                schema-version = 1

                [[rule]]
                [rule.servers]
                mode = "deny"
                hosts = ["%s"]
                """.formatted(host));
            PolicyLoadResult result = PolicyLoader.load(file);
            assertWithMessage("policy errors: %s", result.errors()).that(result.isValid()).isTrue();
            return EffectivePolicy.resolve(result.file(), new PolicyIdentity() {
                @Override
                public String userName() {
                    return "tester";
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static String source(String fileName) throws IOException {
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The body of the method declared with {@code declaration}, up to its matching brace. */
    private static String methodBody(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertWithMessage("declaration %s", declaration).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        throw new AssertionError("unterminated method " + declaration);
    }
}
