package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.ShellIntegrationEvent.ClipboardWrite;
import de.kortty.shellintegration.ShellIntegrationEvent.CommandFinished;
import de.kortty.shellintegration.ShellIntegrationEvent.CommandStart;
import de.kortty.shellintegration.ShellIntegrationEvent.Oversize;
import de.kortty.shellintegration.ShellIntegrationEvent.OutputStart;
import de.kortty.shellintegration.ShellIntegrationEvent.PromptStart;
import de.kortty.shellintegration.ShellIntegrationEvent.RemoteNotification;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * What {@link OscEventSplitter} takes out of a pane's output and what it passes on. The splitter
 * must read the stream exactly as SithTermFX does, or it would cut text out of the screen;
 * {@code OscSplitterDifferentialTest} checks that against the real emulator.
 */
class OscEventSplitterTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";
    private static final String C1_ST = "\u009C";
    private static final String C1_OSC = "\u009D";
    /** Where a sequence without an event was taken out. */
    private static final Object TAKEN_OUT = new Object() {
        @Override
        public String toString() {
            return "<taken out>";
        }
    };

    @DataProvider
    Object[][] terminators() {
        return new Object[][] {{BEL}, {ST}, {C1_ST}};
    }

    @Test(dataProvider = "terminators")
    void everyTerminatorEndsAnOwnedSequence(String terminator) {
        Split split = split("a" + ESC + "]133;A" + terminator + "b");

        assertThat(split.parts).containsExactly("a", new PromptStart(), "b").inOrder();
    }

    @Test
    void theC1IntroducerStartsAnOscWhereSithTermFxReadsACharOnItsOwn() {
        assertThat(split("\r" + C1_OSC + "133;B" + BEL + "$ ").parts)
            .containsExactly("\r", new CommandStart(), "$ ").inOrder();
        assertThat(split(C1_OSC + "133;C" + C1_ST).parts).containsExactly(new OutputStart());
        assertThat(split(ESC + "[0m" + C1_OSC + "133;A" + BEL).parts)
            .containsExactly(ESC + "[0m", new PromptStart()).inOrder();
    }

    @Test
    void insidePrintableTextTheC1IntroducerIsText() {
        // SithTermFX reads a run of chars >= 0x20 in one go and prints U+009D with them.
        String output = "ab" + C1_OSC + "133;A" + BEL + "cd";

        assertThat(split(output).text()).isEqualTo(output);
        assertThat(split(output).events()).isEmpty();
    }

    @Test
    void afterAC1IntroducerInPrintableTextNothingIsTakenOutUpToTheNextTerminator() {
        // Where the run is cut right before it (right margin, end of a read), SithTermFX opens an
        // OSC there, and a mark inside would be part of that OSC. Unknowable here, so the mark
        // passes; the next terminator ends both readings.
        for (String terminator : List.of(BEL, ST)) {
            String ambiguous = "ab" + C1_OSC + "cd" + ESC + "]133;A" + terminator;
            Split split = split(ambiguous + ESC + "]133;B" + BEL + "x");
            assertWithMessage(visible(terminator))
                .that(split.parts).containsExactly(ambiguous, new CommandStart(), "x").inOrder();
        }
        String afterSt = "ab" + C1_OSC + "cd" + C1_ST + C1_OSC + "133;A" + BEL;
        assertWithMessage("printed, U+009C leaves the run open, so the next U+009D is no sure introducer")
            .that(split(afterSt).text()).isEqualTo(afterSt);
    }

    @Test
    void aBareEscKeepsAnOwnedSequenceOpen() {
        Split split = split(ESC + "]9;hello" + ESC + "[1mworld" + BEL + "after");

        assertThat(split.parts)
            .containsExactly(new RemoteNotification(OwnedOsc.NOTIFICATION, null, "hello" + ESC + "[1mworld"), "after")
            .inOrder();
        assertWithMessage("ESC ] inside an owned OSC is body, not a new sequence")
            .that(split(ESC + "]133;D;1" + ESC + "]0;x" + BEL + "z").parts)
            .containsExactly(new CommandFinished(null), "z").inOrder();
    }

    @Test
    void aDcsBodyPassesUntouched() {
        // tmux passthrough: the DCS ends at the first BEL, the trailing ESC \ is a separate escape.
        String tmux = ESC + "Ptmux;" + ESC + ESC + "]133;A" + BEL + ESC + "\\";
        String decrqss = ESC + "P$qm" + ESC + "\\";

        for (String output : List.of(tmux, decrqss, ESC + "P]133;A" + C1_ST)) {
            Split split = split(output + "x");
            assertWithMessage(visible(output)).that(split.text()).isEqualTo(output + "x");
            assertWithMessage(visible(output)).that(split.events()).isEmpty();
        }
    }

    @Test
    void aMarkInsideAForeignOscIsNotOwned() {
        for (String output : List.of(
                ESC + "]2;x]133;A" + BEL,
                ESC + "]2;" + ESC + "]133;A" + BEL,
                ESC + "]0;title" + C1_OSC + "133;A" + BEL,
                ESC + "]1337;File=]133;A" + ST)) {
            Split split = split(output + "x");
            assertWithMessage(visible(output)).that(split.text()).isEqualTo(output + "x");
            assertWithMessage(visible(output)).that(split.events()).isEmpty();
        }
    }

    @Test
    void anyChunkBoundaryGivesTheSameSplit() {
        String output = "pre" + ESC + "[1;32m" + ESC + "]133;A" + BEL + "user@host$ " + ESC + "]133;B" + ST
            + "ls\r\n" + ESC + "]133;C" + C1_ST + "file" + ESC + "]2;title" + BEL + "\r\n"
            + ESC + "]133;D;0" + BEL + ESC + "]9;done" + ST + ESC + "]777;notify;T;B" + BEL
            + ESC + "]8;;https://example.com" + ST + "x" + ESC + "]8;;" + ST + "\r" + C1_OSC + "133;A" + C1_ST + "end";
        Split whole = split(output);
        assertThat(whole.events()).hasSize(7);

        for (int cut = 0; cut <= output.length(); cut++) {
            Split twoReads = split(output.substring(0, cut), output.substring(cut));
            assertWithMessage("cut at %s", cut).that(twoReads.parts).isEqualTo(whole.parts);
        }
        String[] oneCharEach = output.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
        assertThat(split(oneCharEach).parts).isEqualTo(whole.parts);
    }

    @Test
    void marksCarryTheirExitStatus() {
        assertThat(events(ESC + "]133;D;0" + BEL)).containsExactly(new CommandFinished(0));
        assertThat(events(ESC + "]133;D;130" + BEL)).containsExactly(new CommandFinished(130));
        assertThat(events(ESC + "]133;D" + BEL)).containsExactly(new CommandFinished(null));
        assertThat(events(ESC + "]133;D;aid=12;1" + BEL)).containsExactly(new CommandFinished(1));
        assertThat(events(ESC + "]133;D;cl=m" + BEL)).containsExactly(new CommandFinished(null));
        assertThat(events(ESC + "]133;D;99999999999" + BEL)).containsExactly(new CommandFinished(null));
        assertThat(events(ESC + "]133;A;cl=m;aid=3" + BEL)).containsExactly(new PromptStart());
        assertThat(events(ESC + "]133;B;" + BEL)).containsExactly(new CommandStart());
    }

    @Test
    void anUnknownMarkIsTakenOutWithoutAnEvent() {
        Split split = split("a" + ESC + "]133;P;k=i" + BEL + ESC + "]133;" + BEL + "b");

        assertThat(split.parts).containsExactly("a", TAKEN_OUT, TAKEN_OUT, "b").inOrder();
    }

    @Test
    void osc9TextIsANotificationButConEmuSubcommandsAreNot() {
        assertThat(events(ESC + "]9;Build done; 3 warnings" + BEL))
            .containsExactly(new RemoteNotification(OwnedOsc.NOTIFICATION, null, "Build done; 3 warnings"));

        for (String subcommand : List.of("9;4;1;50", "9;4", "9;12", "9;")) {
            Split split = split(ESC + "]" + subcommand + BEL + "x");
            assertWithMessage(subcommand).that(split.parts).containsExactly(TAKEN_OUT, "x").inOrder();
        }
        String otherCode = ESC + "]99;x" + BEL;
        assertThat(split(otherCode).text()).isEqualTo(otherCode);
    }

    @Test
    void osc777NotifyIsANotificationAndEveryOtherSubcommandPassesThrough() {
        assertThat(events(ESC + "]777;notify;Title;Body;with;semicolons" + ST))
            .containsExactly(new RemoteNotification(OwnedOsc.NOTIFY, "Title", "Body;with;semicolons"));
        assertThat(events(ESC + "]777;notify;Only a title" + BEL))
            .containsExactly(new RemoteNotification(OwnedOsc.NOTIFY, null, "Only a title"));
        assertThat(events(ESC + "]777;notify;;" + BEL)).isEmpty();
        assertThat(split(ESC + "]777;notify;;" + BEL).parts).containsExactly(TAKEN_OUT);

        // The remote korTTY-agent sequence must reach nothing but SithTermFX, unchanged (UX-01).
        String agent = ESC + "]777;korTTY-agent;execute;" + base64("/tmp") + ";" + base64("(root=true) id") + BEL;
        for (String output : List.of(agent, ESC + "]777;preexec" + BEL, ESC + "]777;notifyx;a" + BEL)) {
            Split split = split(output + "x");
            assertWithMessage(visible(output)).that(split.text()).isEqualTo(output + "x");
            assertWithMessage(visible(output)).that(split.events()).isEmpty();
        }
    }

    @Test
    void osc52WritesAreTakenOutWithTheirDataAsSent() {
        for (String selection : List.of("", "c", "p", "q", "s", "0", "7", "cs")) {
            for (String terminator : List.of(BEL, ST, C1_ST)) {
                String write = ESC + "]52;" + selection + ";" + base64("copied") + terminator;
                assertWithMessage(selection + visible(terminator)).that(split("a" + write + "b").parts)
                    .containsExactly("a", new ClipboardWrite(selection, base64("copied")), "b").inOrder();
            }
        }
        String wrapped = "Y29w\naWVk\r\n";
        assertWithMessage("line breaks of MIME-wrapped base64 stay in the data; Osc52Support removes them")
            .that(events(ESC + "]52;c;" + wrapped + BEL)).containsExactly(new ClipboardWrite("c", wrapped));
    }

    @Test
    void osc52QueriesAndWritesKorttyCannotTakeAreTakenOutWithoutAnEvent() {
        // SithTermFX ignores OSC 52 as it ignores every OSC it does not know, so taking these out
        // changes nothing on screen; none of them may reach the clipboard, and a query is never answered.
        for (String output : List.of(
                ESC + "]52;c;?" + ST,
                ESC + "]52;;?" + BEL,
                ESC + "]52;c;" + BEL,
                ESC + "]52;x;" + base64("copied") + BEL,
                ESC + "]52;c+;" + base64("copied") + BEL,
                ESC + "]52;" + base64("copied") + BEL)) {
            assertWithMessage(visible(output)).that(split(output + "x").parts).containsExactly(TAKEN_OUT, "x").inOrder();
        }
        assertWithMessage("OSC 5, 520 and an OSC 52 without its separator are no clipboard writes")
            .that(split(ESC + "]5;1" + BEL + ESC + "]520;c;x" + BEL + ESC + "]52" + BEL).text())
            .isEqualTo(ESC + "]5;1" + BEL + ESC + "]520;c;x" + BEL + ESC + "]52" + BEL);
    }

    @Test
    void aClipboardWriteMayBeAsLongAsTheBase64OfItsCapWithLineBreaks() {
        // base64 without -w0 breaks its output every 76 chars; with CRLF that is the longest form.
        String encoded = Base64.getMimeEncoder().encodeToString(new byte[Osc52Support.MAX_DECODED_BYTES]);
        String payload = "c;" + encoded;
        assertThat(payload.length()).isAtMost(OwnedOsc.CLIPBOARD.maxPayloadLength());

        Split split = split(ESC + "]52;" + payload.substring(0, 100_000), payload.substring(100_000) + BEL + "after");
        assertThat(split.parts).containsExactly(new ClipboardWrite("c", encoded), "after").inOrder();

        String tooLong = "c;" + "A".repeat(OwnedOsc.CLIPBOARD.maxPayloadLength());
        assertThat(split(ESC + "]52;" + tooLong + BEL + "after").parts)
            .containsExactly(new Oversize(OwnedOsc.CLIPBOARD), "after").inOrder();
    }

    @Test
    void anOversizeSequenceIsDroppedUpToItsTerminatorAndReported() {
        int cap = OwnedOsc.NOTIFICATION.maxPayloadLength();
        String atCap = "x".repeat(cap);

        assertThat(events(ESC + "]9;" + atCap + BEL))
            .containsExactly(new RemoteNotification(OwnedOsc.NOTIFICATION, null, atCap));
        assertWithMessage("the ESC of ESC-backslash does not count against the cap")
            .that(events(ESC + "]9;" + atCap + ST))
            .containsExactly(new RemoteNotification(OwnedOsc.NOTIFICATION, null, atCap));
        assertThat(split(ESC + "]9;" + atCap + "y" + BEL + "after").parts)
            .containsExactly(new Oversize(OwnedOsc.NOTIFICATION), "after").inOrder();

        String huge = ESC + "]133;A" + "z".repeat(200_000);
        Split split = split(huge.substring(0, 5_000), huge.substring(5_000), ESC + "]0;still the body" + ST + "after");
        assertWithMessage("nothing of the oversize body is passed on; a bare ESC ] in it opens nothing, "
                + "and it ends at its terminator")
            .that(split.parts)
            .containsExactly(new Oversize(OwnedOsc.SHELL_INTEGRATION), "after")
            .inOrder();
    }

    @Test
    void osc7Osc8AndUnknownOscsAreByteIdentical() {
        for (String output : List.of(
                ESC + "]7;file://host/srv/app" + BEL,
                ESC + "]8;;https://example.com/a;b" + ST + "link" + ESC + "]8;;" + ST,
                ESC + "]0;title" + C1_ST,
                ESC + "]1337;SetMark" + BEL,
                ESC + "]13" + BEL,
                ESC + "]133" + BEL,
                ESC + "]9" + ST,
                ESC + "]777;notify" + BEL,
                ESC + "]" + BEL,
                ESC + "]104;1" + BEL)) {
            Split split = split(output + "x");
            assertWithMessage(visible(output)).that(split.text()).isEqualTo(output + "x");
            assertWithMessage(visible(output)).that(split.events()).isEmpty();
        }
    }

    @Test
    void ownershipIsDecidedWithinAFewHeldChars() {
        List<Object> parts = new ArrayList<>();
        OscEventSplitter splitter = new OscEventSplitter();
        feed(splitter, parts, "a" + ESC + "]777;notify");
        assertWithMessage("a prefix of an owned sequence waits for the next chars").that(parts).containsExactly("a");

        feed(splitter, parts, "X");
        assertThat(String.join("", texts(parts))).isEqualTo("a" + ESC + "]777;notifyX");

        List<Object> foreign = new ArrayList<>();
        String unterminated = ESC + "]1337;" + "q".repeat(100);
        new OscEventSplitter().feed(unterminated.toCharArray(), 0, unterminated.length(), sink(foreign));
        assertWithMessage("a foreign OSC streams through without waiting for its end")
            .that(String.join("", texts(foreign))).isEqualTo(unterminated);
    }

    @Test
    void nothingInsideACsiStartsAnOsc() {
        // The CSI runs to its final byte 'A'; SithTermFX then re-reads the stray U+009D, which opens
        // a foreign OSC that the BEL closes.
        String output = ESC + "[1;2" + C1_OSC + "133;A" + BEL + "x";
        assertThat(split(output).text()).isEqualTo(output);
        assertThat(split(output).events()).isEmpty();

        String strayEsc = ESC + "[1" + ESC + "]133;A" + BEL + "x";
        assertThat(split(strayEsc).text()).isEqualTo(strayEsc);
        assertThat(split(strayEsc).events()).isEmpty();
    }

    @Test
    void aStrayC1IntroducerInACsiOpensAForeignOscThatSwallowsTheNextMark() {
        // SithTermFX pushes the stray U+009D back and reads it as an OSC introducer: the OSC runs on
        // through the mark up to its BEL, so the mark is no mark at all.
        String output = ESC + "[" + C1_OSC + "m" + ESC + "]133;A" + BEL + "x";

        assertThat(split(output).text()).isEqualTo(output);
        assertThat(split(output).events()).isEmpty();
    }

    @Test
    void aCsiWithPrintableStrayCharsStillEndsInGround() {
        // DECSCUSR has a space SithTermFX cannot place; it re-reads it, then the CSI.
        assertThat(split(ESC + "[2 q" + ESC + "]133;A" + BEL).parts)
            .containsExactly(ESC + "[2 q", new PromptStart()).inOrder();
    }

    @Test
    void anEscapeEndsAtTheCharAfterEscWhateverItIs() {
        for (String output : List.of(
                ESC + C1_OSC + "133;A" + BEL,
                ESC + ESC + "]133;A" + BEL,
                ESC + "(" + C1_OSC + "133;A" + BEL,
                ESC + "(" + ESC + "]133;A" + BEL)) {
            Split split = split(output);
            assertWithMessage(visible(output)).that(split.events()).isEmpty();
            assertWithMessage(visible(output)).that(split.text()).isEqualTo(output);
        }
        assertThat(split(ESC + "(B" + ESC + "]133;A" + BEL).parts)
            .containsExactly(ESC + "(B", new PromptStart()).inOrder();
        assertThat(split(ESC + "7" + ESC + "]133;A" + BEL).parts)
            .containsExactly(ESC + "7", new PromptStart()).inOrder();
    }

    @Test
    void summariesNeverContainRemoteText() {
        RemoteNotification notification = new RemoteNotification(OwnedOsc.NOTIFY, "secret title", "secret body");

        assertThat(notification.summary()).doesNotContain("secret");
        assertThat(new ClipboardWrite("c", base64("secret")).summary()).isEqualTo("ClipboardWrite(8 chars)");
        assertThat(new CommandFinished(2).summary()).isEqualTo("CommandFinished(exit=2)");
        assertThat(new Oversize(OwnedOsc.SHELL_INTEGRATION).summary()).isEqualTo("Oversize(SHELL_INTEGRATION)");
    }

    // ---------------------------------------------------------------------------------------------

    /** The splitter's output: adjacent text merged into one String, events as they are. */
    private record Split(List<Object> parts) {
        String text() {
            return String.join("", texts(parts));
        }

        List<ShellIntegrationEvent> events() {
            return parts.stream()
                .filter(ShellIntegrationEvent.class::isInstance)
                .map(ShellIntegrationEvent.class::cast)
                .toList();
        }
    }

    private static Split split(String... reads) {
        OscEventSplitter splitter = new OscEventSplitter();
        List<Object> parts = new ArrayList<>();
        for (String read : reads) {
            feed(splitter, parts, read);
        }
        return new Split(parts);
    }

    private static List<ShellIntegrationEvent> events(String output) {
        return split(output).events();
    }

    private static void feed(OscEventSplitter splitter, List<Object> parts, String read) {
        // Pad the chunk so offsets other than 0 are exercised.
        char[] padded = ("##" + read + "##").toCharArray();
        splitter.feed(padded, 2, read.length(), sink(parts));
    }

    private static OscEventSplitter.Sink sink(List<Object> parts) {
        return new OscEventSplitter.Sink() {
            @Override
            public void text(char[] chars, int offset, int length) {
                assertThat(length).isGreaterThan(0);
                String text = new String(chars, offset, length);
                int last = parts.size() - 1;
                if (last >= 0 && parts.get(last) instanceof String previous) {
                    parts.set(last, previous + text);
                } else {
                    parts.add(text);
                }
            }

            @Override
            public void sequence(ShellIntegrationEvent event) {
                if (event != null) {
                    parts.add(event);
                } else {
                    parts.add(TAKEN_OUT);
                }
            }
        };
    }

    private static List<String> texts(List<Object> parts) {
        return parts.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String visible(String output) {
        StringBuilder out = new StringBuilder();
        for (char c : output.toCharArray()) {
            out.append(c < 0x20 || (c >= 0x7F && c <= 0x9F) ? String.format("<%02X>", (int) c) : String.valueOf(c));
        }
        return out.toString();
    }
}
