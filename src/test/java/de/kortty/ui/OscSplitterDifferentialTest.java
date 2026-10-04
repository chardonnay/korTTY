package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.TtyConnector;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.testng.annotations.Test;

/**
 * {@link ShellIntegrationTtyConnector} takes only sequences SithTermFX ignores: the same output
 * read by the real emulator once directly and once through the wrapper leaves the same screen,
 * scrollback, styles, cursor, title and bells. A splitter that read the stream differently from
 * SithTermFX would cut text out of the screen or print sequence bodies onto it.
 */
class OscSplitterDifferentialTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";
    private static final char C1_ST = '\u009C';
    private static final char C1_OSC = '\u009D';
    private static final String TEXT_CHARS = "abcdefghijklmnopqrstuvwxyz ABCXYZ0123456789-_/.:;,[]{}()<>|\\'\"~=+*&%$#!?éü€─";

    @Test
    void generatedOutputLooksTheSameWithAndWithoutTheWrapper() throws IOException {
        Random random = new Random(0x5EED_133L);
        int streams = 2_000;
        int streamsWithEvents = 0;
        for (int stream = 0; stream < streams; stream++) {
            String output = generate(random, 10 + random.nextInt(60));
            long chunkSeed = random.nextLong();
            int maxChunk = 1 + random.nextInt(random.nextBoolean() ? 8 : 200);
            // A narrow screen cuts runs of text at the margin far more often.
            int columns = stream % 2 == 0 ? 20 : 7;
            List<ShellIntegrationEvent> events = new ArrayList<>();

            String direct = render(output, connector -> connector, chunkSeed, maxChunk, columns);
            String wrapped = render(output, connector -> new ShellIntegrationTtyConnector(connector, events::add),
                chunkSeed, maxChunk, columns);

            assertWithMessage("stream %s: %s", stream, visible(output)).that(wrapped).isEqualTo(direct);
            if (!events.isEmpty()) {
                streamsWithEvents++;
            }
        }
        assertWithMessage("the generator exercises the owned sequences")
            .that(streamsWithEvents).isAtLeast(streams * 3 / 4);
    }

    @Test
    void handPickedEdgeCasesLookTheSame() throws IOException {
        List<String> outputs = List.of(
            // A stray U+009D in a CSI opens a foreign OSC that runs through the next mark.
            ESC + "[" + C1_OSC + "m" + ESC + "]133;A" + BEL + "after\r\nmore",
            // A stray ESC in a CSI, then a mark that SithTermFX prints as text.
            ESC + "[1" + ESC + "]133;A" + BEL + "after",
            // tmux passthrough: the DCS ends at the first BEL.
            "x" + ESC + "Ptmux;" + ESC + ESC + "]133;A" + BEL + ESC + "\\y",
            // A bare ESC ] inside a title is part of the title.
            ESC + "]2;title" + ESC + "]133;A" + BEL + "z",
            // U+009D inside printable text is printed, unless the run ends right before it.
            "ab" + C1_OSC + "cd\r\n",
            "12345678901234567890" + C1_OSC + "x" + ESC + "]133;A" + BEL + "visible" + ESC + "]133;B" + BEL + "y",
            // Marks right at the last column, at every wrap.
            "12345678901234567890" + ESC + "]133;B" + BEL + "x" + ESC + "]133;C" + ST + "\r\n",
            // An oversize mark and a foreign OSC that is just as long.
            ESC + "]133;A" + "z".repeat(10_000) + BEL + "a" + ESC + "]2;" + "y".repeat(10_000) + BEL + "b",
            // The alternate screen.
            ESC + "[?1049h" + ESC + "]133;A" + BEL + "full screen" + ESC + "[?1049l" + ESC + "]133;D;0" + BEL + "back");
        for (String output : outputs) {
            for (int maxChunk : new int[] {1, 2, 3, 7, 1024}) {
                String direct = render(output, connector -> connector, 7L, maxChunk, 20);
                String wrapped = render(output, connector -> new ShellIntegrationTtyConnector(connector, event -> { }),
                    7L, maxChunk, 20);
                assertWithMessage("%s in chunks of up to %s", visible(output), maxChunk).that(wrapped).isEqualTo(direct);
            }
        }
    }

    @Test
    void theRemoteAgentSequenceStillReachesOnlyTheEmulator() throws IOException {
        String agent = ESC + "]777;korTTY-agent;execute;" + base64("/tmp") + ";" + base64("(root=true) id") + BEL;
        List<ShellIntegrationEvent> events = new ArrayList<>();

        String wrapped = render("before" + agent + "after",
            connector -> new ShellIntegrationTtyConnector(connector, events::add), 3L, 5, 20);

        assertThat(events).isEmpty();
        assertThat(wrapped).isEqualTo(render("before" + agent + "after", connector -> connector, 3L, 5, 20));
    }

    private static String render(String output, Function<ScriptedConnector, TtyConnector> wrap,
                                 long chunkSeed, int maxChunk, int columns) throws IOException {
        Screen screen = columns >= 20 ? new Screen(columns, 6, 30) : new Screen(columns, 3, 5);
        screen.run(wrap.apply(ScriptedConnector.inRandomChunks(output, new Random(chunkSeed), maxChunk)));
        return screen.dump();
    }

    // ---------------------------------------------------------------------------------------------

    /** Well-formed output: text, controls, CSI, ESC sequences, OSCs (owned or not), DCS and C1 forms. */
    private static String generate(Random random, int segments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            switch (random.nextInt(23)) {
                case 0, 1, 2 -> out.append(text(random, 1 + random.nextInt(30)));
                case 3 -> out.append(pick(random, "\r\n", "\n", "\r", "\t", "\b", BEL));
                case 4 -> out.append(ESC).append('[').append(pick(random,
                    "0m", "1m", "31m", "38;5;196m", "48;2;1;2;3m", "0;1;4m", "7m", "3A", "2C", "H", "5;10H", "K", "2K",
                    "J", "?25l", "?25h", "?2004h", "?2004l", "1;6A", "2 q", ">4;1m", "!p", "S", "T", "3L", "2M", "4P",
                    "2@", "6n", "?1049h", "?1049l", "r", "2;5r"));
                case 5 -> out.append(ESC).append(pick(random, "7", "8", "(B", "(0", ")0", "=", ">", "M", "D", "E",
                    "#8", " F", "%G", "H"));
                case 6 -> out.append(osc(random, "0;" + text(random, random.nextInt(12))));
                case 7 -> out.append(osc(random, "2;" + text(random, random.nextInt(12))));
                case 8 -> out.append(osc(random, "7;file://host/srv/" + text(random, 3).replace(' ', '_')));
                case 9 -> out.append(osc(random, "8;;https://example.com/" + random.nextInt(100)))
                    .append(text(random, 1 + random.nextInt(8)))
                    .append(osc(random, "8;;"));
                case 10, 11, 12 -> out.append(osc(random, "133;" + pick(random,
                    "A", "B", "C", "D", "D;0", "D;1", "D;130;aid=1", "A;cl=m;aid=7", "P;k=i", "")));
                case 13 -> out.append(osc(random, "9;" + pick(random,
                    "Build done", "4;1;50", "4;0", "", text(random, random.nextInt(20)))));
                case 14 -> out.append(osc(random, "777;" + pick(random,
                    "notify;Title;Body;with;semis", "notify;Only", "notify;;", "preexec",
                    "korTTY-agent;execute;" + base64("/tmp") + ";" + base64("id"))));
                case 15 -> out.append(osc(random, "52;" + pick(random, "c", "", "p", "s") + ";"
                    + pick(random, base64("copied"), "?")));
                case 16 -> out.append(ESC).append('P').append(pick(random, "$qm", "1$r0m", "tmux;" + ESC + ESC + "]0;x"))
                    .append(pick(random, ST, BEL, String.valueOf(C1_ST)));
                case 17 -> {
                    // C1 OSC where SithTermFX reads a char on its own: after a control char.
                    out.append('\r').append(C1_OSC).append(pick(random, "133;A", "133;D;2", "0;c1 title", "9;c1"))
                        .append(pick(random, BEL, String.valueOf(C1_ST), ST));
                }
                case 18 -> out.append(text(random, 2)).append(C1_OSC).append(text(random, 2)).append("\r\n");
                case 19 -> out.append(osc(random, "104;1"));
                case 20 -> out.append(osc(random, "1337;SetMark"));
                case 21 -> {
                    // A CSI with chars SithTermFX cannot place: they are read again after it.
                    out.append(ESC).append('[').append(pick(random, "1", "", "?", "38:5"))
                        .append(pick(random, String.valueOf(C1_OSC), BEL, ESC, " ", "\r", ESC + "(", "x" + C1_OSC))
                        .append(pick(random, "m", "A", "]", "\\", "q"));
                }
                default -> out.append("\r\n").append(text(random, 25));
            }
        }
        return out.toString();
    }

    private static String osc(Random random, String body) {
        return ESC + "]" + body + pick(random, BEL, ST, String.valueOf(C1_ST));
    }

    private static String text(Random random, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(TEXT_CHARS.charAt(random.nextInt(TEXT_CHARS.length())));
        }
        return text.toString();
    }

    private static String pick(Random random, String... choices) {
        return choices[random.nextInt(choices.length)];
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String visible(String output) {
        StringBuilder out = new StringBuilder();
        for (char c : output.toCharArray()) {
            out.append(c < 0x20 || (c >= 0x7F && c <= 0x9F) ? String.format("<%02X>", (int) c) : String.valueOf(c));
        }
        return out.length() > 600 ? out.substring(0, 600) + "..." : out.toString();
    }
}
