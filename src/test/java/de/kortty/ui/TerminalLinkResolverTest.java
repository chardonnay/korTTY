package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import com.sithtermfx.core.model.hyperlinks.LinkInfoProvider;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.ui.TerminalLinkClickPolicy.Hit;
import de.kortty.ui.TerminalLinkResolver.Link;
import de.kortty.ui.TerminalLinkResolver.Window;
import org.testng.annotations.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.AUTO;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.OSC8;
import static de.kortty.ui.TerminalLinkResolver.WEB_LINK_KINDS;

/**
 * A Cmd/Ctrl+click opens a web or e-mail address that a program printed as plain text, found on
 * demand on the text the buffer holds now. These cases pin what SithTermFX's own link filter got
 * wrong (a link written in pieces, a link wrapped over rows), the bounded window that keeps a click
 * on a line wrapped over thousands of rows cheap without ever reporting half a link, and that an
 * OSC 8 link wins over anything found in its text.
 */
public class TerminalLinkResolverTest {

    private static final int WIDTH = 40;
    private static final int HEIGHT = 4;

    private static EmulatorTextBufferFixture fixture() {
        return fixture(WIDTH, HEIGHT, 100);
    }

    private static EmulatorTextBufferFixture fixture(int width, int height, int history) {
        return new EmulatorTextBufferFixture(width, height, history, new KorttyOsc8LinkInfoProvider());
    }

    private static Link web(EmulatorTextBufferFixture fixture, int column, int line) {
        return TerminalLinkResolver.linkAt(fixture.buffer, new Point(column, line), WEB_LINK_KINDS);
    }

    @Test
    public void aPlainUrlIsALinkWithItsTargetAndCells() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("see https://example.com/docs. now");

        for (int column = 4; column <= 27; column++) {
            Link link = web(fixture, column, 0);
            assertWithMessage("column " + column).that(link).isNotNull();
            assertThat(link.kind()).isEqualTo(AUTO);
            assertThat(link.target()).isEqualTo(URI.create("https://example.com/docs"));
            assertThat(link.text()).isEqualTo("https://example.com/docs");
            assertThat(link.start()).isEqualTo(new Point(4, 0));
            assertThat(link.end()).isEqualTo(new Point(27, 0));
        }
        // The sentence's full stop, the words around it and empty cells are no link.
        for (int column : new int[] {0, 3, 28, 30, 39}) {
            assertWithMessage("column " + column).that(web(fixture, column, 0)).isNull();
        }
        assertThat(web(fixture, 5, 2)).isNull();
    }

    @Test
    public void aUrlWrittenInPiecesResolvesWhole() {
        // SithTermFX's link filter kept the target of the first piece it saw ("https://exa").
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("$ open https://exa");
        fixture.write("mple.com/pa");
        fixture.write("th?q=1");

        Link link = web(fixture, 9, 0);
        assertThat(link).isNotNull();
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/path?q=1"));
        assertThat(web(fixture, 34, 0).target()).isEqualTo(URI.create("https://example.com/path?q=1"));
    }

    @Test
    public void aUrlWrappedOverRowsResolvesFromEachRow() {
        EmulatorTextBufferFixture fixture = fixture();
        String url = "https://example.com/" + "a".repeat(45);
        fixture.writeWrapping("go " + url + " end");
        assertThat(fixture.buffer.getLine(0).isWrapped()).isTrue();

        for (Point cell : new Point[] {new Point(3, 0), new Point(39, 0), new Point(0, 1), new Point(27, 1)}) {
            Link link = TerminalLinkResolver.linkAt(fixture.buffer, cell, WEB_LINK_KINDS);
            assertWithMessage("cell " + cell).that(link).isNotNull();
            assertThat(link.target()).isEqualTo(URI.create(url));
            assertThat(link.start()).isEqualTo(new Point(3, 0));
            assertThat(link.end()).isEqualTo(new Point(27, 1));
        }
        assertThat(web(fixture, 29, 1)).isNull();
    }

    @Test
    public void aUrlInTheHistoryResolves() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("old https://example.com/old");
        for (int i = 0; i < HEIGHT; i++) {
            fixture.write("\r\nline " + i);
        }
        int line = -fixture.buffer.getHistoryLinesCount();
        assertThat(line).isLessThan(0);

        Link link = web(fixture, 6, line);
        assertThat(link).isNotNull();
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/old"));
        assertThat(link.start()).isEqualTo(new Point(4, line));
    }

    @Test
    public void cellOffsetsStayRightAfterAWideCharacter() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("見 https://example.com/x");

        Link link = web(fixture, 3, 0);
        assertThat(link).isNotNull();
        assertThat(link.start()).isEqualTo(new Point(3, 0));
        assertThat(link.end()).isEqualTo(new Point(23, 0));
        assertThat(link.text()).isEqualTo("https://example.com/x");
        assertThat(web(fixture, 0, 0)).isNull();
    }

    @Test
    public void anEmailAddressOpensAsMailto() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("ask ops@example.com today");

        Link link = web(fixture, 8, 0);
        assertThat(link).isNotNull();
        assertThat(link.kind()).isEqualTo(AUTO);
        assertThat(link.text()).isEqualTo("ops@example.com");
        assertThat(link.target()).isEqualTo(URI.create("mailto:ops@example.com"));
    }

    @Test
    public void aRefusedPlainLinkIsALinkWithoutATarget() {
        // Some mail programs attach the local file an attach field names.
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("mailto:a@example.com?attach=~/.ssh/id_rsa");

        Link link = web(fixture, 3, 0);
        assertThat(link).isNotNull();
        assertThat(link.kind()).isEqualTo(AUTO);
        assertThat(link.target()).isNull();
        assertThat(link.hit()).isEqualTo(new Hit(AUTO, null, link.text()));
    }

    @Test
    public void withDetectionOffOnlyOsc8LinksResolve() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("https://example.com/plain ");
        fixture.link("https://example.com/osc8", "osc8");

        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(3, 0), Set.of())).isNull();
        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(3, 0), null)).isNull();
        Link osc8 = TerminalLinkResolver.linkAt(fixture.buffer, new Point(27, 0), Set.of());
        assertThat(osc8).isNotNull();
        assertThat(osc8.kind()).isEqualTo(OSC8);
        assertThat(osc8.target()).isEqualTo(URI.create("https://example.com/osc8"));
        // The click gate gets the visible text too, to compare its host with the target's.
        assertThat(osc8.hit()).isEqualTo(new Hit(OSC8, URI.create("https://example.com/osc8"), "osc8"));
    }

    @Test
    public void theResolverAsksForTheKindsOnEveryClick() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("https://example.com/");
        AtomicInteger asked = new AtomicInteger();
        boolean[] enabled = {true};
        TerminalLinkResolver resolver = new TerminalLinkResolver(() -> {
            asked.incrementAndGet();
            return enabled[0] ? WEB_LINK_KINDS : Set.of();
        });

        assertThat(resolver.hitAt(fixture.buffer, new Point(2, 0)))
            .isEqualTo(new Hit(AUTO, URI.create("https://example.com/"), "https://example.com/"));
        enabled[0] = false;
        assertThat(resolver.hitAt(fixture.buffer, new Point(2, 0))).isEqualTo(Hit.NONE);
        assertThat(asked.get()).isEqualTo(2);
    }

    @Test
    public void anOsc8LinkWinsOverTheUrlItShows() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("see ");
        fixture.link("https://target.example/real", "https://shown.example/");
        fixture.write(" now");

        Link link = web(fixture, 10, 0);
        assertThat(link).isNotNull();
        assertThat(link.kind()).isEqualTo(OSC8);
        assertThat(link.target()).isEqualTo(URI.create("https://target.example/real"));
        assertThat(link.text()).isEqualTo("https://shown.example/");
        assertThat(link.start()).isEqualTo(new Point(4, 0));
        assertThat(link.end()).isEqualTo(new Point(25, 0));
    }

    @Test
    public void anOsc8CellIsALinkWithItsTarget() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("see ");
        fixture.link("https://example.com/docs", "docs-link");
        fixture.write(" now");

        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(6, 0), Set.of());
        assertThat(link.kind()).isEqualTo(OSC8);
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/docs"));
        assertThat(link.text()).isEqualTo("docs-link");
        assertThat(link.start()).isEqualTo(new Point(4, 0));
        assertThat(link.end()).isEqualTo(new Point(12, 0));
        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(4, 0), Set.of()).kind()).isEqualTo(OSC8);
        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(12, 0), Set.of()).kind()).isEqualTo(OSC8);

        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(3, 0), Set.of())).isNull();
        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(13, 0), Set.of())).isNull();
        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(30, 2), Set.of())).isNull();
    }

    @Test
    public void anOsc8LinkWrappedOverRowsCoversBothRows() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("x ");
        fixture.terminal.setLinkUriStarted("https://example.com/long");
        fixture.writeWrapping("L".repeat(45));
        fixture.terminal.setLinkUriFinished();
        fixture.write(" tail");

        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(1, 1), Set.of());
        assertThat(link.start()).isEqualTo(new Point(2, 0));
        assertThat(link.end()).isEqualTo(new Point(6, 1));
        assertThat(link.text()).isEqualTo("L".repeat(45));
    }

    @Test
    public void aRefusedOsc8LinkIsPlainText() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.link("file:////host/share/setup.exe", "setup.exe");

        assertThat(web(fixture, 2, 0)).isNull();
    }

    @Test
    public void anOsc8LinkOfAnotherOriginHasNoTarget() {
        LinkInfoProvider foreign = uri -> new LinkInfo(() -> {
            throw new AssertionError("must not navigate");
        });
        EmulatorTextBufferFixture fixture = new EmulatorTextBufferFixture(WIDTH, HEIGHT, 100, foreign);
        fixture.link("https://example.com/", "foreign");

        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(1, 0), WEB_LINK_KINDS);
        assertThat(link.kind()).isEqualTo(OSC8);
        assertThat(link.target()).isNull();
    }

    @Test
    public void cellsOutsideTheBufferAreNoLink() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("https://example.com/" + "x".repeat(WIDTH - 20));

        assertThat(web(fixture, WIDTH - 1, 0)).isNotNull();
        assertThat(web(fixture, WIDTH, 0)).isNull();
        assertThat(web(fixture, -1, 0)).isNull();
        assertThat(web(fixture, 0, HEIGHT)).isNull();
        assertThat(web(fixture, 0, -1)).isNull();
    }

    @Test
    public void historyOsc8LinksAreLinks() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.link("https://example.com/old", "old-link");
        for (int i = 0; i < HEIGHT; i++) {
            fixture.write("\r\nline " + i);
        }
        int line = -fixture.buffer.getHistoryLinesCount();

        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(0, line), Set.of());
        assertThat(link.kind()).isEqualTo(OSC8);
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/old"));
    }

    @Test
    public void theWindowStopsSixteenRowsAroundTheCell() {
        EmulatorTextBufferFixture fixture = fixture(WIDTH, HEIGHT, 500);
        fixture.writeWrapping("w".repeat(WIDTH * 100));
        fixture.write("\r\n");
        int first = -fixture.buffer.getHistoryLinesCount();

        Window middle = window(fixture, first + 50);
        assertThat(middle.start()).isEqualTo(first + 50 - TerminalLinkResolver.MAX_ROWS_AROUND);
        assertThat(middle.end()).isEqualTo(first + 50 + TerminalLinkResolver.MAX_ROWS_AROUND);
        assertThat(middle.continuesBefore()).isTrue();
        assertThat(middle.continuesAfter()).isTrue();

        Window top = window(fixture, first);
        assertThat(top.start()).isEqualTo(first);
        assertThat(top.end()).isEqualTo(first + TerminalLinkResolver.MAX_ROWS_AROUND);
        assertThat(top.continuesBefore()).isFalse();
        assertThat(top.continuesAfter()).isTrue();

        Window bottom = window(fixture, first + 99);
        assertThat(bottom.end()).isEqualTo(first + 99);
        assertThat(bottom.continuesAfter()).isFalse();
        assertThat(bottom.continuesBefore()).isTrue();
    }

    @Test
    public void theWindowStopsAtFourKilobytesAndGivesUnusedRowsToTheOtherSide() {
        int width = 200;
        EmulatorTextBufferFixture fixture = fixture(width, HEIGHT, 500);
        fixture.writeWrapping("w".repeat(width * 60));
        fixture.write("\r\n");
        int first = -fixture.buffer.getHistoryLinesCount();

        Window middle = window(fixture, first + 30);
        assertThat(middle.rows() * width).isAtMost(TerminalLinkResolver.MAX_WINDOW_CHARS);
        assertThat(middle.rows()).isEqualTo(TerminalLinkResolver.MAX_WINDOW_CHARS / width);

        // Two rows before the end of the line: the rows the end cannot use go to the start.
        Window nearEnd = window(fixture, first + 57);
        assertThat(nearEnd.end()).isEqualTo(first + 59);
        assertThat(nearEnd.start()).isEqualTo(first + 57 - TerminalLinkResolver.MAX_ROWS_AROUND);
        assertThat(nearEnd.rows() * width).isAtMost(TerminalLinkResolver.MAX_WINDOW_CHARS);
    }

    @Test
    public void aUrlLongerThanTheWindowIsNeverOpenedCutShort() {
        EmulatorTextBufferFixture fixture = fixture(WIDTH, HEIGHT, 500);
        String url = "https://example.com/" + "a".repeat(WIDTH * 40);
        fixture.writeWrapping(url);
        fixture.write("\r\n");
        int first = -fixture.buffer.getHistoryLinesCount();

        // Seen from its first row, the URL runs on past the window's last row.
        assertThat(web(fixture, 2, first)).isNull();
        // Seen from the middle, it runs on past both ends.
        assertThat(web(fixture, 2, first + 20)).isNull();
        // From its last row the window reaches back only 16 rows, not to the start of the URL.
        assertThat(web(fixture, 2, first + 40)).isNull();
    }

    @Test
    public void aUrlInMinifiedJsonWrappedOverManyRowsStillResolves() {
        EmulatorTextBufferFixture fixture = fixture(WIDTH, HEIGHT, 500);
        String filler = "{\"k\":\"" + "v".repeat(WIDTH * 30) + "\",";
        String json = filler + "\"url\":\"https://example.com/api?id=7\"," + filler.substring(1) + "\"end\":1}";
        fixture.writeWrapping(json);
        fixture.write("\r\n");
        int first = -fixture.buffer.getHistoryLinesCount();
        int offset = json.indexOf("https://") + 3;

        Link link = web(fixture, offset % WIDTH, first + offset / WIDTH);
        assertThat(link).isNotNull();
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/api?id=7"));
        assertThat(window(fixture, first + offset / WIDTH).continuesBefore()).isTrue();
        assertThat(window(fixture, first + offset / WIDTH).continuesAfter()).isTrue();
    }

    @Test
    public void resolvingOnAOneMegabyteWrappedJsonLineStaysCheap() {
        int width = 80;
        int rows = (1024 * 1024) / width;
        EmulatorTextBufferFixture fixture = fixture(width, 24, rows + 100);
        StringBuilder json = new StringBuilder(rows * width + 64).append('[');
        while (json.length() < rows * width) {
            json.append("{\"id\":").append(json.length()).append(",\"href\":\"https://example.com/i/")
                .append(json.length()).append("\",\"tags\":[\"a\",\"b\"]},");
        }
        json.setLength(rows * width - 1);
        json.append(']');
        fixture.writeWrapping(json.toString());
        fixture.write("\r\n");
        int first = -fixture.buffer.getHistoryLinesCount();
        assertThat(fixture.buffer.getLine(first + rows / 2).isWrapped()).isTrue();

        long start = System.nanoTime();
        int links = 0;
        for (int i = 0; i < 2_000; i++) {
            int line = first + (int) ((long) i * (rows - 1) / 2_000);
            if (web(fixture, i % width, line) != null) {
                links++;
            }
            assertThat(window(fixture, line).rows()).isAtMost(2 * TerminalLinkResolver.MAX_ROWS_AROUND + 1);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(links).isGreaterThan(0);
        // Well under a second on a laptop; the generous bound only catches a resolver that reads the whole line.
        assertWithMessage("2,000 resolves took " + elapsed).that(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    public void webLinkKindsAreUrlsAndEmailAddressesOnly() {
        assertThat(WEB_LINK_KINDS).containsExactly(Kind.URL, Kind.EMAIL);
        assertThat(TerminalLinkResolver.WEB_AND_PATH_LINK_KINDS).containsExactly(Kind.URL, Kind.EMAIL, Kind.PATH);
    }

    @Test
    public void aPrintedPathIsAFileLinkOnlyWhenPathsAreAskedFor() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("error in src/main/App.java:42:7 here");

        assertThat(web(fixture, 12, 0)).isNull();
        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(12, 0), TerminalLinkResolver.WEB_AND_PATH_LINK_KINDS);
        assertThat(link).isNotNull();
        assertThat(link.kind()).isEqualTo(AUTO);
        assertThat(link.target()).isNull();
        assertThat(link.opens()).isTrue();
        assertThat(link.text()).isEqualTo("src/main/App.java:42:7");
        assertThat(link.start()).isEqualTo(new Point(9, 0));
        assertThat(link.end()).isEqualTo(new Point(30, 0));
        assertThat(link.file()).isEqualTo(TerminalFileLink.printed("src/main/App.java:42:7"));
        assertThat(link.file().path()).isEqualTo("src/main/App.java");
        // The click policy sees the file too.
        assertThat(link.hit().file()).isEqualTo(link.file());
        assertThat(link.withoutFile().opens()).isFalse();
    }

    @Test
    public void aUrlIsNeverAlsoAPath() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("see https://example.com/a/b.txt");

        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(26, 0), TerminalLinkResolver.WEB_AND_PATH_LINK_KINDS);
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/a/b.txt"));
        assertThat(link.file()).isNull();
    }

    @Test
    public void anOsc8FileLinkCarriesItsFileWhereThePaneOpensFiles() {
        EmulatorTextBufferFixture fixture = new EmulatorTextBufferFixture(WIDTH, HEIGHT, 100,
            new KorttyOsc8LinkInfoProvider(() -> true));
        fixture.write("ls: ");
        fixture.link("file://web01/home/daniel/notes.txt", "notes.txt");

        // Found with no plain-text kinds at all: OSC 8 links do not depend on the detection setting.
        Link link = TerminalLinkResolver.linkAt(fixture.buffer, new Point(6, 0), Set.of());
        assertThat(link).isNotNull();
        assertThat(link.kind()).isEqualTo(OSC8);
        assertThat(link.target()).isNull();
        assertThat(link.text()).isEqualTo("notes.txt");
        assertThat(link.file()).isNotNull();
        assertThat(link.file().path()).isEqualTo("/home/daniel/notes.txt");
        assertThat(link.file().host()).isEqualTo("web01");
        assertThat(link.file().fromOsc8()).isTrue();
    }

    @Test
    public void anOsc8FileLinkIsPlainTextWhereThePaneOpensNoFiles() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("ls: ");
        fixture.link("file:///home/daniel/notes.txt", "notes.txt");

        assertThat(TerminalLinkResolver.linkAt(fixture.buffer, new Point(6, 0), Set.of())).isNull();
    }

    private static Window window(EmulatorTextBufferFixture fixture, int line) {
        fixture.buffer.lock();
        try {
            return TerminalLinkResolver.window(fixture.buffer, new Point(0, line));
        } finally {
            fixture.buffer.unlock();
        }
    }
}
