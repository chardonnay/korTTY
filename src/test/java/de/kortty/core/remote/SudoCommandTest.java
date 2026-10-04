package de.kortty.core.remote;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

public class SudoCommandTest {

    @Test
    public void wrapUsesFreshNoncesAndKeepsThemOutOfTheTemplate() {
        SudoCommand first = SudoCommand.wrap("cat > '/etc/motd'");
        SudoCommand second = SudoCommand.wrap("cat > '/etc/motd'");

        assertThat(first.promptNonce()).isNotEqualTo(second.promptNonce());
        assertThat(first.readyNonce()).isNotEqualTo(first.promptNonce());
        assertThat(first.promptNonce()).matches("KORTTYP[0-9a-f]{32}");
        assertThat(first.commandLine()).startsWith("sudo -S -p '" + first.promptNonce() + "' sh -c '");
        assertThat(first.commandLine()).contains(first.readyNonce());
        assertThat(first.toString()).doesNotContain(first.promptNonce());
        assertThat(first.toString()).doesNotContain(first.readyNonce());
        assertThat(first.toString()).contains("cat > '/etc/motd'");
        assertThat(first.inner()).isEqualTo("cat > '/etc/motd'");
    }

    @Test
    public void blankCommandsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SudoCommand.wrap("  "));
    }

    @Test
    public void secretLineIsUtf8PlusNewline() {
        byte[] line = RemoteCommandRunner.encodeSecretLine("päss".toCharArray());

        assertThat(new String(line, StandardCharsets.UTF_8)).isEqualTo("päss\n");
    }

    @Test
    public void secretsWithLineBreaksAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RemoteCommandRunner.encodeSecretLine("a\nb".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> RemoteCommandRunner.encodeSecretLine("a\rb".toCharArray()));
    }

    @Test
    public void replaceAllRemovesEveryOccurrence() {
        byte[] data = "xxSECRETyySECRET".getBytes(StandardCharsets.US_ASCII);

        byte[] scrubbed = RemoteCommandRunner.replaceAll(data, "SECRET".getBytes(StandardCharsets.US_ASCII),
            "*".getBytes(StandardCharsets.US_ASCII));

        assertThat(new String(scrubbed, StandardCharsets.US_ASCII)).isEqualTo("xx*yy*");
    }
}
