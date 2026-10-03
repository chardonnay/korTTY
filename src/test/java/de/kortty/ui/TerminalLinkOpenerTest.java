package de.kortty.ui;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Pins the scheme allowlist and the strict parsing that every link from terminal output passes
 * before korTTY opens it, and the form in which the hover tooltip and the host-mismatch question
 * show a target. Toolkit-free: the opener is a spy, so nothing is ever launched.
 */
public class TerminalLinkOpenerTest {

    @DataProvider
    public Object[][] allowedTargets() {
        return new Object[][] {
            {"http://example.com"},
            {"https://example.com/docs/page.html?q=1#top"},
            {"HTTPS://Example.com/"},
            {"ftp://ftp.example.com/pub/file.txt"},
            {"ftps://user@ftp.example.com:990/"},
            {"mailto:someone@example.com"},
            {"mailto:someone@example.com?Subject=Build%20failed&body=Log&cc=a@example.com&bcc=b@example.com&"},
            {"mailto:?to=someone@example.com&in-reply-to=%3Cid@example.com%3E"},
            {"https://[::1]:8443/"},
            {"https://gcc.gnu.org/onlinedocs/gcc/Warning-Options.html#index-Wunused-variable"},
        };
    }

    @Test(dataProvider = "allowedTargets")
    public void allowsWebAndMailLinks(String target) {
        assertWithMessage(target).that(TerminalLinkOpener.allowedBrowseUri(target)).isPresent();
    }

    @DataProvider
    public Object[][] refusedTargets() {
        return new Object[][] {
            // Schemes outside the allowlist, file: above all: SithTermFX launched those with Desktop.open.
            {"file:///Applications/Calculator.app"},
            {"file://localhost/etc/passwd"},
            {"file:////host/share/x.exe"},
            {"FILE:///C:/Windows/System32/calc.exe"},
            {"news:comp.lang.java"},
            {"javascript:alert(1)"},
            {"data:text/html,<script>alert(1)</script>"},
            {"vbscript:msgbox(1)"},
            {"smb://host/share"},
            // Mail fields beyond recipients, subject and body; attach= makes some clients attach a local file.
            {"mailto:someone@example.com?attach=/home/user/.ssh/id_ed25519"},
            {"mailto:someone@example.com?subject=hi&Attachment=file:///etc/passwd"},
            {"mailto:someone@example.com?%61ttach=~/.ssh/id_rsa"},
            {"mailto:someone@example.com?X-Mailer=x"},
            {"mailto://someone@example.com"},
            // No scheme, no host, or not a valid URI at all.
            {"example.com/path"},
            {"//example.com/path"},
            {"http:example.com"},
            {"http:///path"},
            {"https://"},
            {"mailto:"},
            {"http://exa\\mple.com/"},
            {"https://example.com/%zz"},
            {"http://under_score.example/"},
            {""},
            // Whitespace, controls and invisible format characters.
            {"https://example.com/a b"},
            {" https://example.com/"},
            {"https://example.com/\t"},
            {"https://example.com/\n"},
            {"https://example.com/\u0007"},
            {"https://example.com/\u007f"},
            {"https://example.com/\u009b"},
            {"https://example.com/\u00a0"},
            {"https://example.com/\u2028"},
            {"https://exa\u202emple.com/"},
            {"https://example.com/\u2066x\u2069"},
            {"https://example.com/\u200f"},
            {"https://example.com/\u061c"},
            {"https://exa\u200bmple.com/"},
            {"https://example.com/\ufeff"},
            {"https://example.com/\ud800"},
        };
    }

    @Test(dataProvider = "refusedTargets")
    public void refusesEverythingElse(String target) {
        assertWithMessage(target).that(TerminalLinkOpener.allowedBrowseUri(target)).isEmpty();
    }

    @Test
    public void refusesNull() {
        assertThat(TerminalLinkOpener.allowedBrowseUri(null)).isEmpty();
    }

    @Test
    public void capsTheLengthAtEightKilobytes() {
        String prefix = "https://example.com/";
        String atLimit = prefix + "a".repeat(TerminalLinkOpener.MAX_URI_LENGTH - prefix.length());
        assertThat(atLimit).hasLength(8 * 1024);

        assertThat(TerminalLinkOpener.allowedBrowseUri(atLimit)).isPresent();
        assertThat(TerminalLinkOpener.allowedBrowseUri(atLimit + "a")).isEmpty();
    }

    @Test
    public void convertsAnInternationalisedHostToPunycode() {
        Optional<URI> uri = TerminalLinkOpener.allowedBrowseUri("https://b\u00fccher.example:8443/a?q=1#f");

        assertThat(uri).isPresent();
        assertThat(uri.get().getHost()).isEqualTo("xn--bcher-kva.example");
        assertThat(uri.get().getPort()).isEqualTo(8443);
        assertThat(uri.get().toString()).isEqualTo("https://xn--bcher-kva.example:8443/a?q=1#f");
    }

    @Test
    public void openHandsTheAsciiFormToTheOpener() {
        List<String> opened = new ArrayList<>();
        TerminalLinkOpener opener = new TerminalLinkOpener(opened::add);

        URI uri = TerminalLinkOpener.allowedBrowseUri("https://example.com/stra\u00dfe?q=\u00e4").orElseThrow();

        assertThat(opener.open(uri)).isTrue();
        assertThat(opened).containsExactly(uri.toASCIIString());
        assertThat(opened.get(0)).isEqualTo("https://example.com/stra%C3%9Fe?q=%C3%A4");
    }

    @Test
    public void openRefusesALinkOutsideTheAllowlist() {
        List<String> opened = new ArrayList<>();
        TerminalLinkOpener opener = new TerminalLinkOpener(opened::add);

        assertThat(opener.open(URI.create("file:///bin/sh"))).isFalse();
        assertThat(opener.open(URI.create("news:comp.lang.java"))).isFalse();
        assertThat(opener.open(null)).isFalse();
        assertThat(opened).isEmpty();
    }

    @Test
    public void openReportsAFailingOpenerWithoutThrowing() {
        TerminalLinkOpener opener = new TerminalLinkOpener(target -> {
            throw new IllegalStateException("no browser");
        });

        assertThat(opener.open(URI.create("https://example.com/"))).isFalse();
    }

    @Test
    public void displayTargetShowsAnOrdinaryTargetAsItIs() {
        assertThat(TerminalLinkOpener.displayTarget(URI.create("https://example.com/docs?q=1#top")))
            .isEqualTo("https://example.com/docs?q=1#top");
        assertThat(TerminalLinkOpener.displayTarget(URI.create("https://[::1]:8443/x")))
            .isEqualTo("https://[::1]:8443/x");
        assertThat(TerminalLinkOpener.displayTarget(URI.create("ftp://user@ftp.example.com:21/pub")))
            .isEqualTo("ftp://user@ftp.example.com:21/pub");
    }

    @Test
    public void displayTargetShowsAnInternationalisedHostInPunycode() {
        assertThat(TerminalLinkOpener.displayTarget(URI.create("https://b\u00fccher.example:8443/a?q=1")))
            .isEqualTo("https://xn--bcher-kva.example:8443/a?q=1");
        // A Cyrillic "a" in a familiar name cannot pass for the Latin one.
        String lookAlike = TerminalLinkOpener.displayTarget(URI.create("https://ex\u0430mple.com/login"));
        assertThat(lookAlike).isEqualTo("https://" + IDN.toASCII("ex\u0430mple.com") + "/login");
        assertThat(lookAlike).startsWith("https://xn--");
        assertThat(TerminalLinkOpener.displayTarget(URI.create("mailto:ops@b\u00fccher.example?subject=Hi")))
            .isEqualTo("mailto:ops@xn--bcher-kva.example?subject=Hi");
    }

    @Test
    public void displayTargetEscapesControlAndBidiCharacters() throws URISyntaxException {
        // The multi-argument constructor keeps format characters such as U+202E as they are.
        URI uri = new URI("https", "example.com", "/a\u202eb\u0007c\u00e4", null);

        String shown = TerminalLinkOpener.displayTarget(uri);

        assertThat(shown).isEqualTo("https://example.com/a%E2%80%AEb%07c%C3%A4");
        for (char c : shown.toCharArray()) {
            assertWithMessage(shown).that(c >= 0x21 && c < 0x7f).isTrue();
        }
        assertThat(TerminalLinkOpener.asciiForDisplay("a\ud800b \t")).isEqualTo("a\\uD800b%20%09");
    }

    @Test
    public void displayTargetShortensTheRestButNeverTheSchemeOrTheHost() {
        String longPath = TerminalLinkOpener.displayTarget(URI.create("https://example.com/" + "a".repeat(500)));
        assertThat(longPath).hasLength(TerminalLinkOpener.MAX_DISPLAY_LENGTH);
        assertThat(longPath).startsWith("https://example.com/aaa");
        assertThat(longPath).endsWith(TerminalLinkOpener.ELLIPSIS);

        // A long user name in front of the host must not push the host out of sight.
        String userInfo = TerminalLinkOpener.displayTarget(
            URI.create("https://" + "example.com.".repeat(30) + "@evil.example/" + "p".repeat(300)));
        assertThat(userInfo).contains(TerminalLinkOpener.ELLIPSIS + "@evil.example/ppp");
        assertThat(userInfo.length()).isAtMost(TerminalLinkOpener.MAX_DISPLAY_LENGTH);

        String label = "a".repeat(60);
        String host = label + "." + label + "." + label + ".example";
        String longHost = TerminalLinkOpener.displayTarget(URI.create("https://" + host + "/" + "p".repeat(300)));
        assertThat(longHost).startsWith("https://" + host + "/");
    }

    @Test
    public void shorteningNeverSplitsAnEscape() {
        String shown = TerminalLinkOpener.displayTarget(URI.create("https://example.com/" + "\u00e4".repeat(200)));

        assertThat(shown.length()).isAtMost(TerminalLinkOpener.MAX_DISPLAY_LENGTH);
        assertThat(shown).matches("https://example\\.com/(%C3%A4)*(%C3)?" + TerminalLinkOpener.ELLIPSIS);
        assertThat(TerminalLinkOpener.shorten("ab%C3%A4cd", 5)).isEqualTo("ab" + TerminalLinkOpener.ELLIPSIS);
        assertThat(TerminalLinkOpener.shorten("ab\\uD800cd", 6)).isEqualTo("ab" + TerminalLinkOpener.ELLIPSIS);
        assertThat(TerminalLinkOpener.shorten("abc", 3)).isEqualTo("abc");
    }

    @DataProvider
    public Object[][] mismatchedHosts() {
        return new Object[][] {
            {"https://example.com/login", "https://evil.example/login", "example.com"},
            // The user-info trick: everything before @ is a user name, the host is evil.example.
            {"https://example.com", "https://example.com@evil.example/", "example.com"},
            {"see https://a.example and https://b.example", "https://a.example/", "b.example"},
            {"https://example.com", "mailto:ops@example.com", "example.com"},
            {"https://docs.example.com", "https://example.com/", "docs.example.com"},
            {"https://b\u00fccher.example", "https://example.com", "xn--bcher-kva.example"},
        };
    }

    @Test(dataProvider = "mismatchedHosts")
    public void aLinkWhoseTextShowsAnotherHostIsAMismatch(String text, String target, String shownHost) {
        assertWithMessage(text + " -> " + target)
            .that(TerminalLinkOpener.visibleHostMismatch(text, URI.create(target)))
            .isEqualTo(Optional.of(shownHost));
    }

    @DataProvider
    public Object[][] matchingHosts() {
        return new Object[][] {
            {"docs-link", "https://evil.example/"},
            {"example.com", "https://evil.example/"},
            {"https://example.com/a", "https://example.com/b?c=d"},
            {"HTTPS://EXAMPLE.COM/", "https://example.com/"},
            {"https://www.example.com", "https://example.com/x"},
            {"https://example.com", "https://www.example.com./"},
            {"https://b\u00fccher.example/", "https://xn--bcher-kva.example/"},
            {"mailto:ops@example.com", "https://evil.example/"},
            {"", "https://evil.example/"},
            {"   ", "https://evil.example/"},
        };
    }

    @Test(dataProvider = "matchingHosts")
    public void aLinkWhoseTextShowsNoOtherHostIsNoMismatch(String text, String target) {
        assertWithMessage(text + " -> " + target)
            .that(TerminalLinkOpener.visibleHostMismatch(text, URI.create(target))).isEmpty();
        assertThat(TerminalLinkOpener.visibleHostMismatch(null, URI.create(target))).isEmpty();
    }
}
