package de.kortty.ui;

import de.kortty.core.TerminalPathToken;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Which files a link in terminal output may name: a path printed as plain text, or an OSC 8
 * {@code file:} target checked as strictly as a web link (no network share, no control or invisible
 * characters, a plain host), whose host must be the session's. Toolkit-free.
 */
public class TerminalFileLinkTest {

    @Test
    public void aPrintedPathKeepsItsPositionApartFromThePath() {
        TerminalFileLink link = TerminalFileLink.printed("src/main/App.java:42:7");

        assertThat(link.path()).isEqualTo("src/main/App.java");
        assertThat(link.token()).isEqualTo(new TerminalPathToken("src/main/App.java", 42, 7));
        assertThat(link.fromOsc8()).isFalse();
        assertThat(link.host()).isNull();
        assertThat(link.copyText()).isEqualTo("src/main/App.java");
        assertThat(link.displayTarget()).isEqualTo("src/main/App.java");
    }

    @Test
    public void aFileTargetBecomesItsDecodedAbsolutePath() {
        TerminalFileLink local = TerminalFileLink.fromFileUri("file:///home/daniel/My%20Notes/todo.md").orElseThrow();
        TerminalFileLink named = TerminalFileLink.fromFileUri("file://web01/var/log/syslog#L12").orElseThrow();
        TerminalFileLink noAuthority = TerminalFileLink.fromFileUri("file:/etc/hosts").orElseThrow();

        assertThat(local.path()).isEqualTo("/home/daniel/My Notes/todo.md");
        assertThat(local.host()).isNull();
        assertThat(local.fromOsc8()).isTrue();
        assertThat(named.path()).isEqualTo("/var/log/syslog");
        assertThat(named.host()).isEqualTo("web01");
        assertThat(noAuthority.path()).isEqualTo("/etc/hosts");
        // The file:// target as the browser form shows it, so its text cannot hide another file.
        assertThat(named.displayTarget()).isEqualTo("file://web01/var/log/syslog#L12");
        assertThat(local.copyText()).isEqualTo("/home/daniel/My Notes/todo.md");
    }

    @Test
    public void aWindowsDriveTargetIsCopiedWithoutTheLeadingSlash() {
        TerminalFileLink drive = TerminalFileLink.fromFileUri("file:///C:/Users/daniel/app.log").orElseThrow();

        assertThat(drive.path()).isEqualTo("/C:/Users/daniel/app.log");
        assertThat(drive.copyText()).isEqualTo("C:/Users/daniel/app.log");
    }

    @DataProvider
    public Object[][] refusedTargets() {
        return new Object[][] {
            {"file:////fileserver/share/setup.exe"},     // a network share (UNC on Windows)
            {"file://localhost//fileserver/share/x"},    // the same behind a host
            {"file:///C:%5CUsers%5Cx.txt"},              // a backslash, which Windows reads as a separator
            {"file:///tmp/a%00b"},                       // NUL
            {"file:///tmp/a%0Ab"},                       // a line feed
            {"file:///tmp/%E2%80%AEevil.txt"},           // a bidi override
            {"file:///tmp/a b"},                         // whitespace in the target itself
            {"file://user@host/etc/passwd"},             // a user name
            {"file://host:22/etc/passwd"},               // a port
            {"file://ho%2Fst/etc/passwd"},               // a host that is not a plain name
            {"file:relative.txt"},                       // opaque
            {"https://example.com/"},                    // another scheme
            {"file:///" + "a".repeat(TerminalLinkOpener.MAX_URI_LENGTH)},
            {""},
            {null},
        };
    }

    @Test(dataProvider = "refusedTargets")
    public void unsafeFileTargetsAreNoLinks(String target) {
        assertWithMessage(String.valueOf(target)).that(TerminalFileLink.fromFileUri(target)).isEmpty();
    }

    @Test
    public void aTargetWithoutHostOrWithLocalhostIsOnTheSessionsOwnMachine() {
        assertThat(TerminalFileLink.fromFileUri("file:///etc/hosts").orElseThrow().hostAccepted(List.of())).isTrue();
        assertThat(TerminalFileLink.fromFileUri("file://localhost/etc/hosts").orElseThrow().hostAccepted(List.of())).isTrue();
        assertThat(TerminalFileLink.fromFileUri("file://LOCALHOST./etc/hosts").orElseThrow().hostAccepted(List.of())).isTrue();
        assertThat(TerminalFileLink.fromFileUri("file://127.0.0.1/etc/hosts").orElseThrow().hostAccepted(List.of())).isTrue();
        assertThat(TerminalFileLink.fromFileUri("file://[::1]/etc/hosts").orElseThrow().hostAccepted(List.of())).isTrue();
        assertThat(TerminalFileLink.printed("/etc/hosts").hostAccepted(List.of())).isTrue();
    }

    @Test
    public void aNamedHostMustBeTheSessionsByItsFirstLabel() {
        TerminalFileLink web01 = TerminalFileLink.fromFileUri("file://web01/var/log/syslog").orElseThrow();
        TerminalFileLink fqdn = TerminalFileLink.fromFileUri("file://Web01.example.com/var/log/syslog").orElseThrow();
        TerminalFileLink other = TerminalFileLink.fromFileUri("file://db02/var/log/syslog").orElseThrow();

        assertThat(web01.hostAccepted(List.of("web01.example.com"))).isTrue();
        assertThat(fqdn.hostAccepted(List.of("web01"))).isTrue();
        assertThat(other.hostAccepted(List.of("web01.example.com"))).isFalse();
        // A server reached by IP address names itself in the prompt, which the pane adds.
        assertThat(web01.hostAccepted(java.util.Arrays.asList("10.0.0.5", null, "web01"))).isTrue();
        assertThat(web01.hostAccepted(List.of("10.0.0.5"))).isFalse();
        assertThat(other.hostAccepted(List.of())).isFalse();
    }

    @Test
    public void addressesAreComparedWhole() {
        TerminalFileLink address = TerminalFileLink.fromFileUri("file://10.0.0.5/etc/hosts").orElseThrow();

        assertThat(address.hostAccepted(List.of("10.0.0.5"))).isTrue();
        assertThat(address.hostAccepted(List.of("10.1.1.1"))).isFalse();
        assertThat(TerminalFileLink.fromFileUri("file://10/etc/hosts").orElseThrow().hostAccepted(List.of("10.0.0.5")))
            .isFalse();
    }

    @Test
    public void aFileLinkKeepsItsTargetForTheMismatchQuestion() {
        TerminalFileLink link = TerminalFileLink.fromFileUri("file:///etc/passwd").orElseThrow();

        assertThat(link.uri()).isEqualTo(URI.create("file:///etc/passwd"));
        // Text that shows a web address is not where a file link goes.
        assertThat(TerminalLinkOpener.visibleHostMismatch("https://example.com/login", link.uri()))
            .hasValue("example.com");
        assertThat(TerminalLinkOpener.visibleHostMismatch("passwd", link.uri())).isEmpty();
    }
}
