package de.kortty.ui;

import de.kortty.core.SSHKeyManager;
import de.kortty.core.SshTtyConnector;
import de.kortty.model.AuthMethod;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the terminal's half of the jump-server fix. The terminal used to hand the master password
 * only to a {@code PUBLIC_KEY} target, so a password target behind a password jump server failed
 * with "vault is locked" although the vault was open.
 */
class TerminalViewSshVaultWiringTest {

    private static final char[] MASTER = "ui-master".toCharArray();

    @DataProvider
    Object[][] everyTargetAuthentication() {
        return new Object[][] {
            {AuthMethod.PASSWORD}, {AuthMethod.KEYBOARD_INTERACTIVE}, {AuthMethod.PUBLIC_KEY}};
    }

    @Test(dataProvider = "everyTargetAuthentication")
    void everySshTargetGetsTheMasterPasswordForItsJumpServer(AuthMethod targetAuth) throws Exception {
        SshTtyConnector connector = TerminalView.sshConnectorWithVault(
            targetBehindPasswordJump(targetAuth), "pw", keyManager(), MASTER);

        assertThat(field(connector, "masterPassword")).isSameInstanceAs(MASTER);
    }

    @Test
    void onlyAKeyTargetKeepsTheKeyManager() throws Exception {
        SSHKeyManager keyManager = keyManager();

        SshTtyConnector forKey = TerminalView.sshConnectorWithVault(
            targetBehindPasswordJump(AuthMethod.PUBLIC_KEY), null, keyManager, MASTER);
        SshTtyConnector withoutKey = TerminalView.sshConnectorWithVault(
            targetBehindPasswordJump(AuthMethod.PASSWORD), "pw", keyManager, MASTER);

        assertThat(field(forKey, "sshKeyManager")).isSameInstanceAs(keyManager);
        assertThat(field(withoutKey, "sshKeyManager")).isNull();
    }

    @Test
    void aLockedVaultLeavesTheConnectorWithoutAMasterPassword() throws Exception {
        SshTtyConnector connector = TerminalView.sshConnectorWithVault(
            targetBehindPasswordJump(AuthMethod.PASSWORD), "pw", keyManager(), null);

        assertThat(field(connector, "masterPassword")).isNull();
    }

    private static ServerConnection targetBehindPasswordJump(AuthMethod targetAuth) {
        ServerConnection connection = new ServerConnection("t", "127.0.0.1", 22, "u");
        connection.setAuthMethod(targetAuth);
        JumpServer jump = new JumpServer();
        jump.setEnabled(true);
        jump.setHost("127.0.0.1");
        jump.setUsername("jumpuser");
        jump.setAuthMethod(AuthMethod.PASSWORD);
        connection.setJumpServer(jump);
        return connection;
    }

    private static SSHKeyManager keyManager() throws Exception {
        return new SSHKeyManager(Files.createTempDirectory("kortty-ui-keys-"));
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
