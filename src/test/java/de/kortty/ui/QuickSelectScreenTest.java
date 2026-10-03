package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import de.kortty.core.TerminalLinkDetector;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.ui.QuickSelectScreen.Hit;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * What quick select offers: the matches on the rows a pane shows at its scroll origin, with their
 * cells, found in a real SithTermFX buffer. A match that wraps is found whole, one that runs on past
 * the visible rows is left out, and a double-width character takes two cells but appears once in
 * the text.
 */
public class QuickSelectScreenTest {

    private static EmulatorTextBufferFixture fixture(int width, int height) {
        return new EmulatorTextBufferFixture(width, height, 100, new KorttyOsc8LinkInfoProvider());
    }

    @Test
    public void everyKindOnScreenIsCapturedInReadingOrderWithItsCells() {
        EmulatorTextBufferFixture fixture = fixture(60, 4);
        fixture.write("go https://example.com/a to 10.0.0.1:22\r\n");
        fixture.write("id 123e4567-e89b-12d3-a456-426614174000\r\n");
        fixture.write("c0ffee1 ops@example.com 12345\r\n");
        fixture.write("/var/log/app.log:12");

        List<Hit> hits = QuickSelectScreen.capture(fixture.buffer, 0, 4);

        assertThat(hits.stream().map(Hit::kind).toList()).containsExactly(Kind.URL, Kind.IPV4, Kind.UUID,
            Kind.GIT_HASH, Kind.EMAIL, Kind.NUMBER, Kind.PATH).inOrder();
        assertThat(hits.stream().map(Hit::text).toList()).containsExactly("https://example.com/a", "10.0.0.1:22",
            "123e4567-e89b-12d3-a456-426614174000", "c0ffee1", "ops@example.com", "12345", "/var/log/app.log:12")
            .inOrder();
        assertThat(hits.get(0).start()).isEqualTo(new Point(3, 0));
        assertThat(hits.get(0).end()).isEqualTo(new Point(23, 0));
        assertThat(hits.get(1).start()).isEqualTo(new Point(28, 0));
        assertThat(hits.get(1).end()).isEqualTo(new Point(38, 0));
        assertThat(hits.get(5).start()).isEqualTo(new Point(24, 2));
        assertThat(hits.get(6).end()).isEqualTo(new Point(18, 3));
    }

    @Test
    public void scrolledBackOnlyTheVisibleRowsAreCaptured() {
        EmulatorTextBufferFixture fixture = fixture(30, 3);
        fixture.write("n 1001\r\nn 1002\r\nn 1003\r\nn 1004\r\nn 1005\r\nn 1006");
        assertThat(fixture.buffer.getHistoryLinesCount()).isEqualTo(3);

        List<Hit> hits = QuickSelectScreen.capture(fixture.buffer, -2, 3);

        assertThat(hits.stream().map(Hit::text).toList()).containsExactly("1002", "1003", "1004").inOrder();
        assertThat(hits.stream().map(hit -> hit.start().y).toList()).containsExactly(-2, -1, 0).inOrder();
        // Rows outside the buffer are skipped rather than read.
        assertThat(QuickSelectScreen.capture(fixture.buffer, -10, 20).stream().map(Hit::text).toList())
            .containsExactly("1001", "1002", "1003", "1004", "1005", "1006").inOrder();
        assertThat(QuickSelectScreen.capture(fixture.buffer, -10, 9).stream().map(Hit::text).toList())
            .containsExactly("1001", "1002").inOrder();
    }

    @Test
    public void aWrappedMatchIsFoundWholeWithTheCellsOfBothRows() {
        EmulatorTextBufferFixture fixture = fixture(20, 4);
        fixture.writeWrapping("see https://example.com/some/long/path ok");

        List<Hit> hits = QuickSelectScreen.capture(fixture.buffer, 0, 4);

        assertThat(hits).containsExactly(new Hit(Kind.URL, "https://example.com/some/long/path",
            new Point(4, 0), new Point(17, 1)));
    }

    @Test
    public void aMatchThatRunsOnPastTheVisibleRowsIsLeftOut() {
        EmulatorTextBufferFixture fixture = fixture(20, 4);
        fixture.writeWrapping("see https://example.com/some/long/path ok 4711");

        // Only the first row shows: the URL runs on into the second.
        assertThat(QuickSelectScreen.capture(fixture.buffer, 0, 1)).isEmpty();
        // The second and third rows show: the URL started on the first.
        assertThat(QuickSelectScreen.capture(fixture.buffer, 1, 2).stream().map(Hit::text).toList())
            .containsExactly("4711");
    }

    @Test
    public void aDoubleWidthCharacterTakesTwoCellsButIsOneCharacterOfTheText() {
        EmulatorTextBufferFixture fixture = fixture(40, 2);
        fixture.write("日本 https://例え.jp/x 1234");

        List<Hit> hits = QuickSelectScreen.capture(fixture.buffer, 0, 2);

        assertThat(hits).containsExactly(
            new Hit(Kind.URL, "https://例え.jp/x", new Point(5, 0), new Point(21, 0)),
            new Hit(Kind.NUMBER, "1234", new Point(23, 0), new Point(26, 0))).inOrder();
        assertThat(QuickSelectScreen.stillShows(fixture.buffer, hits.get(0))).isTrue();
    }

    @Test
    public void aLogicalLineLongerThanOneDetectorPassIsReadInSlices() {
        int width = 100;
        int height = 200;
        EmulatorTextBufferFixture fixture = fixture(width, height);
        int urlOffset = 190 * width + 10;
        StringBuilder text = new StringBuilder();
        while (text.length() < urlOffset) {
            text.append("ab ");
        }
        text.setLength(urlOffset - 1);
        text.append(' ').append("https://example.com/far").append(" end");
        assertThat(text.length()).isGreaterThan(TerminalLinkDetector.MAX_INPUT_CHARS);
        fixture.writeWrapping(text.toString());

        List<Hit> hits = QuickSelectScreen.capture(fixture.buffer, 0, height);

        assertThat(hits).containsExactly(new Hit(Kind.URL, "https://example.com/far",
            new Point(10, 190), new Point(32, 190)));
    }

    @Test
    public void aMatchStopsShowingOnceTheProgramPrintsOverIt() {
        EmulatorTextBufferFixture fixture = fixture(40, 3);
        fixture.write("ping 192.168.1.20 ok");
        Hit hit = QuickSelectScreen.capture(fixture.buffer, 0, 3).get(0);
        assertThat(hit.text()).isEqualTo("192.168.1.20");
        assertThat(QuickSelectScreen.stillShows(fixture.buffer, hit)).isTrue();

        fixture.write("\rping 192.168.1.21 ok");

        assertThat(QuickSelectScreen.stillShows(fixture.buffer, hit)).isFalse();
    }

    @Test
    public void aMatchStopsShowingOnceTheScreenScrollsItAway() {
        EmulatorTextBufferFixture fixture = fixture(40, 2);
        fixture.write("hash c0ffee1\r\nnext");
        Hit hit = QuickSelectScreen.capture(fixture.buffer, 0, 2).get(0);
        assertThat(hit.start()).isEqualTo(new Point(5, 0));

        fixture.write("\r\nmore");

        assertThat(QuickSelectScreen.stillShows(fixture.buffer, hit)).isFalse();
        assertThat(QuickSelectScreen.stillShows(fixture.buffer,
            new Hit(Kind.GIT_HASH, "c0ffee1", new Point(5, 9), new Point(11, 9)))).isFalse();
    }

    @Test
    public void aHitKeepsItsCellsWhateverTheCallerDoesWithThem() {
        Point start = new Point(1, 2);
        Hit hit = new Hit(Kind.NUMBER, "1234", start, new Point(4, 2));

        start.x = 9;
        hit.start().y = 7;

        assertThat(hit.start()).isEqualTo(new Point(1, 2));
    }
}
