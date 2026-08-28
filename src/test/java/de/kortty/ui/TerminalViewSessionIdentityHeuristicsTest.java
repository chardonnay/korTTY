package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.ui.TerminalView.SessionIdentityVerdict.FOREIGN;
import static de.kortty.ui.TerminalView.SessionIdentityVerdict.NATIVE_CONFIRMED;
import static de.kortty.ui.TerminalView.SessionIdentityVerdict.UNKNOWN;

class TerminalViewSessionIdentityHeuristicsTest {

    @Test
    void extractsUserFromCommonPromptShapes() {
        assertThat(TerminalView.extractPromptUserFromPromptLine("daniel@fedora:~/Dokumente$")).isEqualTo("daniel");
        assertThat(TerminalView.extractPromptUserFromPromptLine("root@server:/etc/nginx#")).isEqualTo("root");
        assertThat(TerminalView.extractPromptUserFromPromptLine("[root@host ~]#")).isEqualTo("root");
        assertThat(TerminalView.extractPromptUserFromPromptLine("(venv) daniel@fedora:~$")).isEqualTo("daniel");
        assertThat(TerminalView.extractPromptUserFromPromptLine("daniel@MacBook ~ %")).isEqualTo("daniel");
    }

    @Test
    void extractsNoUserFromPromptsWithoutIdentity() {
        assertThat(TerminalView.extractPromptUserFromPromptLine("sh-4.4#")).isNull();
        assertThat(TerminalView.extractPromptUserFromPromptLine("bash-5.1$")).isNull();
        assertThat(TerminalView.extractPromptUserFromPromptLine("PS C:\\Users\\Daniel>")).isNull();
        assertThat(TerminalView.extractPromptUserFromPromptLine("#")).isNull();
        assertThat(TerminalView.extractPromptUserFromPromptLine(null)).isNull();
    }

    @Test
    void extractsHostFromCommonPromptShapes() {
        assertThat(TerminalView.extractPromptHostFromPromptLine("daniel@fedora:~$")).isEqualTo("fedora");
        assertThat(TerminalView.extractPromptHostFromPromptLine("[root@web01 tmp]#")).isEqualTo("web01");
        assertThat(TerminalView.extractPromptHostFromPromptLine("daniel@host.example.com:~$"))
            .isEqualTo("host.example.com");
    }

    @Test
    void hostLabelsMatchOnFirstDnsLabel() {
        assertThat(TerminalView.hostLabelsMatchLeniently("host", "host.example.com")).isTrue();
        assertThat(TerminalView.hostLabelsMatchLeniently("Host.example.com", "host")).isTrue();
        assertThat(TerminalView.hostLabelsMatchLeniently(null, "host")).isTrue();
        assertThat(TerminalView.hostLabelsMatchLeniently("host", null)).isTrue();
        assertThat(TerminalView.hostLabelsMatchLeniently("other", "host")).isFalse();
    }

    @Test
    void ipAddressesRequireFullEquality() {
        assertThat(TerminalView.hostLabelsMatchLeniently("192.168.1.5", "192.168.1.5")).isTrue();
        assertThat(TerminalView.hostLabelsMatchLeniently("192.168.1.5", "192.168.2.7")).isFalse();
        assertThat(TerminalView.hostLabelsMatchLeniently("fedora", "192.168.1.5")).isFalse();
    }

    @Test
    void confirmsNativeIdentityForMatchingPrompt() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "daniel@fedora:~$ ", "daniel", "fedora.example.com")).isEqualTo(NATIVE_CONFIRMED);
    }

    @Test
    void detectsForeignUserAfterSu() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "daniel@fedora:~$ su - root\nPasswort: \n[root@fedora ~]# ",
            "daniel", "fedora")).isEqualTo(FOREIGN);
    }

    @Test
    void detectsForeignRootPromptWithoutUserInfo() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "daniel@fedora:~$ su\nsh-4.4# ", "daniel", "fedora")).isEqualTo(FOREIGN);
    }

    @Test
    void rootPromptIsNativeWhenRootIsTheExpectedUser() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "[root@host ~]# ", "root", "host")).isEqualTo(NATIVE_CONFIRMED);
    }

    @Test
    void passwordPromptStaysUnknown() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "daniel@fedora:~$ su - root\nPassword: ", "daniel", "fedora")).isEqualTo(UNKNOWN);
    }

    @Test
    void stalePromptAboveOutputDoesNotConfirmNativeIdentity() {
        // The last line is command output; the native prompt above it must not be trusted.
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "daniel@fedora:~$ cat data.txt\nsome file contents", "daniel", "fedora"))
            .isEqualTo(UNKNOWN);
    }

    @Test
    void hostMismatchWithMatchingUserStaysUnknown() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "daniel@otherhost:~$ ", "daniel", "fedora")).isEqualTo(UNKNOWN);
    }

    @Test
    void unknownExpectedUserNeverConfirms() {
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "root@host:~# ", null, null)).isEqualTo(UNKNOWN);
        assertThat(TerminalView.evaluatePromptSessionIdentity(
            "sh-4.4# ", null, null)).isEqualTo(UNKNOWN);
    }

    @Test
    void blankScreenStaysUnknown() {
        assertThat(TerminalView.evaluatePromptSessionIdentity("", "daniel", "fedora")).isEqualTo(UNKNOWN);
        assertThat(TerminalView.evaluatePromptSessionIdentity(null, "daniel", "fedora")).isEqualTo(UNKNOWN);
    }
}
