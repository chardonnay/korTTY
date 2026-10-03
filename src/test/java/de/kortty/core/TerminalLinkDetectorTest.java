package de.kortty.core;

import com.sithtermfx.core.util.CharUtils;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.core.TerminalLinkDetector.Match;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

public class TerminalLinkDetectorTest {

    private static final Set<Kind> ALL = EnumSet.allOf(Kind.class);

    private static final char DWC = CharUtils.DWC;

    private static List<String> texts(CharSequence line, Kind kind) {
        return TerminalLinkDetector.find(line, EnumSet.of(kind)).stream().map(Match::text).toList();
    }

    private static List<String> all(CharSequence line) {
        return TerminalLinkDetector.find(line, ALL).stream().map(m -> m.kind() + ":" + m.text()).toList();
    }

    @Test
    public void urlsLoseTrailingPunctuationAndQuotes() {
        assertThat(texts("see https://example.com/docs.", Kind.URL)).containsExactly("https://example.com/docs");
        assertThat(texts("Visit https://example.com/a?q=1&x=2!", Kind.URL)).containsExactly("https://example.com/a?q=1&x=2");
        assertThat(texts("'https://example.com/a', \"http://b.example/x\";", Kind.URL))
            .containsExactly("https://example.com/a", "http://b.example/x").inOrder();
        assertThat(texts("<https://example.com/a>", Kind.URL)).containsExactly("https://example.com/a");
        assertThat(texts("{\"u\":\"https://example.com/a?b=1\"}", Kind.URL)).containsExactly("https://example.com/a?b=1");
        assertThat(texts("https://example.com:8443/x:", Kind.URL)).containsExactly("https://example.com:8443/x");
    }

    @Test
    public void balancedBracketsStayAndUnbalancedOnesAreTrimmed() {
        assertThat(texts("(see https://en.wikipedia.org/wiki/Foo_(bar)).", Kind.URL))
            .containsExactly("https://en.wikipedia.org/wiki/Foo_(bar)");
        assertThat(texts("[docs](https://example.com/a)", Kind.URL)).containsExactly("https://example.com/a");
        assertThat(texts("[https://example.com/a]", Kind.URL)).containsExactly("https://example.com/a");
        assertThat(texts("{https://example.com/a}", Kind.URL)).containsExactly("https://example.com/a");
        assertThat(texts("http://[::1]:8080/x", Kind.URL)).containsExactly("http://[::1]:8080/x");
    }

    @Test
    public void onlyAllowlistedSchemesAreUrls() {
        assertThat(texts("HTTPS://EXAMPLE.COM ftp://a.example/f ftps://b.example/g http://c.example", Kind.URL))
            .containsExactly("HTTPS://EXAMPLE.COM", "ftp://a.example/f", "ftps://b.example/g", "http://c.example").inOrder();
        assertThat(texts("file:///etc/hosts javascript:alert(1) news:comp.lang.java data:text/html,x", Kind.URL)).isEmpty();
        assertThat(texts("smb://host/share vbscript:x xhttps://example.com", Kind.URL)).isEmpty();
        assertThat(texts("http:// https:/// mailto: ftp://.", Kind.URL)).isEmpty();
    }

    @Test
    public void urlsEndAtBoxDrawingAndPowerlineGlyphs() {
        assertThat(texts("│https://example.com/a│", Kind.URL)).containsExactly("https://example.com/a");
        assertThat(texts("https://example.com/a\uE0B0 main", Kind.URL)).containsExactly("https://example.com/a");
        assertThat(texts("https://de.wikipedia.org/wiki/Straße", Kind.URL))
            .containsExactly("https://de.wikipedia.org/wiki/Straße");
    }

    @Test
    public void mailtoIsAUrlAndHidesTheEmailInside() {
        assertThat(all("write to mailto:ops@example.com today")).containsExactly("URL:mailto:ops@example.com");
        assertThat(texts("MailTo:ops@example.com", Kind.URL)).containsExactly("MailTo:ops@example.com");
    }

    @Test
    public void emailsNeedADomainWithALetterTld() {
        assertThat(texts("contact ops.team+alerts@mail.example.com.", Kind.EMAIL))
            .containsExactly("ops.team+alerts@mail.example.com");
        assertThat(texts("<root@example.org>", Kind.EMAIL)).containsExactly("root@example.org");
        assertThat(texts("user@host:/srv/app", Kind.EMAIL)).isEmpty();
        assertThat(texts("a@b x@y.c x@1.23 .a@example.com a..b@example.com a@-x.com", Kind.EMAIL)).isEmpty();
    }

    @Test
    public void ipv4IsOctetValidatedWithAnOptionalPort() {
        assertThat(texts("ping 10.0.0.1 and 192.168.1.254:8080.", Kind.IPV4))
            .containsExactly("10.0.0.1", "192.168.1.254:8080").inOrder();
        assertThat(texts("256.1.1.1 1.2.3 1.2.3.4.5 v1.2.3.4 1.2.3.4a", Kind.IPV4)).isEmpty();
        assertThat(texts("10.0.0.1:99999", Kind.IPV4)).containsExactly("10.0.0.1");
        assertThat(texts("route 10.0.0.0/24 via 10.0.0.1", Kind.IPV4)).containsExactly("10.0.0.0", "10.0.0.1").inOrder();
        assertThat(texts("010.001.000.255", Kind.IPV4)).containsExactly("010.001.000.255");
    }

    @Test
    public void ipv6IsSyntacticAndRejectsTimesAndMacAddresses() {
        assertThat(texts("lo ::1 up", Kind.IPV6)).containsExactly("::1");
        assertThat(texts("fe80::1%en0.", Kind.IPV6)).containsExactly("fe80::1%en0");
        assertThat(texts("2001:db8:85a3:0:0:8a2e:370:7334", Kind.IPV6)).containsExactly("2001:db8:85a3:0:0:8a2e:370:7334");
        assertThat(texts("[2001:db8::1]:443", Kind.IPV6)).containsExactly("2001:db8::1");
        assertThat(texts("::ffff:192.168.0.1", Kind.IPV6)).containsExactly("::ffff:192.168.0.1");
        assertThat(texts("at 10:30:00 from 00:1a:2b:3c:4d:5e", Kind.IPV6)).isEmpty();
        assertThat(texts("std::string Abc::Def :: 1::2::3 1:2:3:4:5:6:7:8:9 ::ffff:999.1.1.1", Kind.IPV6)).isEmpty();
        assertThat(texts("fe80::1g", Kind.IPV6)).isEmpty();
        assertThat(texts("fe80::1% fe80::2%.", Kind.IPV6)).containsExactly("fe80::1", "fe80::2").inOrder(); // a bare % is no zone
    }

    @Test
    public void gitHashesNeedADigitAndALetter() {
        assertThat(texts("commit 3f2a1b7c9d (HEAD -> main)", Kind.GIT_HASH)).containsExactly("3f2a1b7c9d");
        assertThat(texts("git diff abc1234..def5678", Kind.GIT_HASH)).containsExactly("abc1234", "def5678").inOrder();
        assertThat(texts("deadbeef 1234567 abc123 ABC1234 x3f2a1b7", Kind.GIT_HASH)).isEmpty();
        assertThat(texts("0123456789abcdef0123456789abcdef01234567", Kind.GIT_HASH)).hasSize(1);
        assertThat(texts("0123456789abcdef0123456789abcdef012345678", Kind.GIT_HASH)).isEmpty();
    }

    @Test
    public void uuidsMatchInEitherCase() {
        assertThat(texts("id=550e8400-e29b-41d4-a716-446655440000,", Kind.UUID))
            .containsExactly("550e8400-e29b-41d4-a716-446655440000");
        assertThat(texts("550E8400-E29B-41D4-A716-446655440000", Kind.UUID)).hasSize(1);
        assertThat(texts("x550e8400-e29b-41d4-a716-446655440000 550e8400-e29b-41d4-a716-4466554400001", Kind.UUID)).isEmpty();
    }

    @Test
    public void numbersNeedFourDigitsAndStandAlone() {
        assertThat(texts("pid 48213 port 8080. ticket #12345", Kind.NUMBER))
            .containsExactly("48213", "8080", "12345").inOrder();
        assertThat(texts("123 3.14159 12345.67 v12345 12345abc", Kind.NUMBER)).isEmpty();
    }

    @Test
    public void absoluteHomeDotAndDrivePathsAreFound() {
        assertThat(texts("cat /etc/hosts ~/notes.txt ./run.sh ../lib/a.jar", Kind.PATH))
            .containsExactly("/etc/hosts", "~/notes.txt", "./run.sh", "../lib/a.jar").inOrder();
        assertThat(texts("C:\\Users\\me\\file.txt or C:/tmp/x.log", Kind.PATH))
            .containsExactly("C:\\Users\\me\\file.txt", "C:/tmp/x.log").inOrder();
        assertThat(texts("PATH=/usr/bin:/bin --out=/tmp/x.log", Kind.PATH))
            .containsExactly("/usr/bin", "/bin", "/tmp/x.log").inOrder();
        assertThat(texts("see /etc/hosts.", Kind.PATH)).containsExactly("/etc/hosts");
        assertThat(texts("node_modules/@types/node/index.d.ts", Kind.PATH)).containsExactly("node_modules/@types/node/index.d.ts");
        assertThat(texts("/ ./ ../ ~/ /. /--", Kind.PATH)).isEmpty();
    }

    @Test
    public void relativePathsNeedASlashAndAnExtensionOrALineSuffix() {
        assertThat(texts("src/main/Foo.java", Kind.PATH)).containsExactly("src/main/Foo.java");
        assertThat(texts("src/main/Foo.java:12: error", Kind.PATH)).containsExactly("src/main/Foo.java:12");
        assertThat(texts("src/main/Foo.java:12:5: warning", Kind.PATH)).containsExactly("src/main/Foo.java:12:5");
        assertThat(texts("src/App/Foo.cs(12,5): error", Kind.PATH)).containsExactly("src/App/Foo.cs(12,5)");
        assertThat(texts("lib/Makefile:3", Kind.PATH)).containsExactly("lib/Makefile:3");
        assertThat(texts("log/app.log.1 config/.env", Kind.PATH)).containsExactly("log/app.log.1", "config/.env").inOrder();
    }

    @Test
    public void proseWithSlashesIsNotAPath() {
        assertThat(texts("and/or TCP/IP 24/7 10/03 src/main km/h 1/2.5 v1.2/v1.3 I/O w/o", Kind.PATH)).isEmpty();
        assertThat(texts("Foo.java bare.txt", Kind.PATH)).isEmpty();
    }

    @Test
    public void uncAndDevicePathsAreRejected() {
        assertThat(texts("\\\\server\\share\\x.exe", Kind.PATH)).isEmpty();
        assertThat(texts("//server/share/x.exe", Kind.PATH)).isEmpty();
        assertThat(texts("\\\\?\\C:\\Windows\\x.exe \\\\.\\pipe\\x.y", Kind.PATH)).isEmpty();
        assertThat(texts("C://server/share/x.exe", Kind.PATH)).isEmpty();
        assertThat(texts("file:////server/share/x.exe", Kind.PATH)).isEmpty();
    }

    @Test
    public void nothingIsFoundInsideAUrl() {
        assertThat(all("open https://10.0.0.1:8080/repo/abc1234/file.html now"))
            .containsExactly("URL:https://10.0.0.1:8080/repo/abc1234/file.html");
    }

    @Test
    public void overlapsGoToTheHigherKindAndAreResolvedAmongRequestedKindsOnly() {
        assertThat(all("/srv/550e8400-e29b-41d4-a716-446655440000/log.txt"))
            .containsExactly("PATH:/srv/550e8400-e29b-41d4-a716-446655440000/log.txt");
        assertThat(all("::ffff:10.0.0.1")).containsExactly("IPV6:::ffff:10.0.0.1");
        assertThat(all("10.0.0.1/24")).containsExactly("IPV4:10.0.0.1");
        assertThat(texts("https://10.0.0.1/", Kind.IPV4)).containsExactly("10.0.0.1");
        assertThat(all("550e8400-e29b-41d4-a716-446655440000"))
            .containsExactly("UUID:550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    public void matchesAreOrderedAndNeverOverlap() {
        List<Match> matches = TerminalLinkDetector.find(
            "a 10.0.0.1 b https://x.example/y c /etc/hosts d 48213 e abc1234f ops@example.com", ALL);
        assertThat(matches.stream().map(Match::kind).toList()).containsExactly(
            Kind.IPV4, Kind.URL, Kind.PATH, Kind.NUMBER, Kind.GIT_HASH, Kind.EMAIL).inOrder();
        for (int i = 1; i < matches.size(); i++) {
            assertThat(matches.get(i).start()).isAtLeast(matches.get(i - 1).end());
        }
    }

    @Test
    public void nulAndInvisibleCharactersDelimitTokens() {
        assertThat(all("https://a.example\0\0\0/etc/hosts\0\0"))
            .containsExactly("URL:https://a.example", "PATH:/etc/hosts").inOrder();
        assertThat(all("\thttps://a.example\u00A0/etc/hosts\u3000")).hasSize(2);
        assertThat(texts("https://a.example/x\u202Eevil", Kind.URL)).containsExactly("https://a.example/x");
        assertThat(texts("https://a.example/x\u200Bevil", Kind.URL)).containsExactly("https://a.example/x");
        assertThat(texts("https://a.example/x\u001B[0m", Kind.URL)).containsExactly("https://a.example/x");
    }

    @Test
    public void offsetsAreCellOffsetsAfterWideCharacters() {
        String cjk = "日" + DWC + "本" + DWC + " https://x.example";
        Match url = TerminalLinkDetector.find(cjk, ALL).getFirst();
        assertThat(url.start()).isEqualTo(5);
        assertThat(url.end()).isEqualTo(cjk.length());

        // SithTerminal stores a wide emoji as high surrogate, DWC, low surrogate
        String emoji = "\uD83D" + DWC + "\uDE80 /tmp/a.txt";
        Match path = TerminalLinkDetector.find(emoji, ALL).getFirst();
        assertThat(path.start()).isEqualTo(4);
        assertThat(path.text()).isEqualTo("/tmp/a.txt");

        String pairFirst = "\uD83D\uDE80" + DWC + " /tmp/a.txt";
        assertThat(TerminalLinkDetector.find(pairFirst, ALL).getFirst().start()).isEqualTo(4);
    }

    @Test
    public void wideCharactersInsideATokenAreStrippedFromItsText() {
        String line = "ls /home/用" + DWC + "户" + DWC + "/a.txt";
        Match path = TerminalLinkDetector.find(line, EnumSet.of(Kind.PATH)).getFirst();
        assertThat(path.text()).isEqualTo("/home/用户/a.txt");
        assertThat(path.start()).isEqualTo(3);
        assertThat(path.end()).isEqualTo(line.length());

        String trailingWide = "https://x.example/用" + DWC + " next";
        Match url = TerminalLinkDetector.find(trailingWide, EnumSet.of(Kind.URL)).getFirst();
        assertThat(url.text()).isEqualTo("https://x.example/用");
        assertThat(url.end()).isEqualTo(trailingWide.indexOf(' '));

        String supplementary = "/tmp/\uD835\uDC00.txt";
        Match bold = TerminalLinkDetector.find(supplementary, EnumSet.of(Kind.PATH)).getFirst();
        assertThat(bold.text()).isEqualTo(supplementary);
        assertThat(bold.end()).isEqualTo(supplementary.length());
    }

    @Test
    public void onlyTheFirst16KiloBytesAreScanned() {
        String filler = " ".repeat(TerminalLinkDetector.MAX_INPUT_CHARS);
        assertThat(texts("https://early.example " + filler, Kind.URL)).containsExactly("https://early.example");
        assertThat(texts(filler + "https://late.example", Kind.URL)).isEmpty();

        String straddling = " ".repeat(TerminalLinkDetector.MAX_INPUT_CHARS - 10) + "https://straddle.example/x";
        assertThat(texts(straddling, Kind.URL)).isEmpty();

        String exact = " ".repeat(TerminalLinkDetector.MAX_INPUT_CHARS - 20) + "https://fits.example";
        assertThat(exact.length()).isEqualTo(TerminalLinkDetector.MAX_INPUT_CHARS);
        assertThat(texts(exact, Kind.URL)).containsExactly("https://fits.example");
    }

    @Test
    public void emptyInputsFindNothing() {
        assertThat(TerminalLinkDetector.find(null, ALL)).isEmpty();
        assertThat(TerminalLinkDetector.find("", ALL)).isEmpty();
        assertThat(TerminalLinkDetector.find("https://x.example", Set.of())).isEmpty();
        assertThat(TerminalLinkDetector.find("https://x.example", null)).isEmpty();
        assertThat(TerminalLinkDetector.find("\0\0\0" + DWC, ALL)).isEmpty();
    }

    @Test
    public void adversarialInputStaysFast() {
        int size = TerminalLinkDetector.MAX_INPUT_CHARS;
        List<String> inputs = List.of(
            "http://a.".repeat(size / 9 + 1),
            "/a".repeat(size / 2 + 1),
            "a/a.a:1".repeat(size / 7 + 1),
            "a".repeat(size + 1),
            "a@".repeat(size / 2 + 1),
            "a@a.".repeat(size / 4 + 1),
            "1:".repeat(size / 2 + 1),
            "1.".repeat(size / 2 + 1),
            "1".repeat(size + 1),
            "abc1234-".repeat(size / 8 + 1),
            "https://x(" + ")".repeat(size),
            "(".repeat(size / 2) + "https://x" + ")".repeat(size / 2),
            "::".repeat(size / 2 + 1),
            "a-".repeat(size / 2 + 1),
            "~/".repeat(size / 2 + 1),
            "C:\\".repeat(size / 3 + 1),
            "a.b ".repeat(size / 4) + "/",
            "a/b ".repeat(size / 4 + 1));
        for (String input : inputs) {
            TerminalLinkDetector.find(input, ALL); // warm up the JIT once per shape
        }
        long started = System.nanoTime();
        for (String input : inputs) {
            long one = System.nanoTime();
            TerminalLinkDetector.find(input, ALL);
            assertThat(Duration.ofNanos(System.nanoTime() - one).toMillis()).isLessThan(1_000L);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(3_000L);
    }

    @Test
    public void detectionNeverResolvesHostNames() throws Exception {
        String source = Files.readString(Path.of("src/main/java/de/kortty/core/TerminalLinkDetector.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(source).doesNotContain("InetAddress");
        assertThat(source).doesNotContain("java.net");
    }
}
