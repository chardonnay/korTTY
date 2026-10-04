package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.shellintegration.ShellIntegrationEvent.CommandStart;
import de.kortty.shellintegration.ShellIntegrationEvent.PromptStart;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

/**
 * The read contract of {@link ShellIntegrationTtyConnector}: SithTermFX takes a non-positive read
 * for the end of the session and asks for more only once it has interpreted everything, so text
 * must come up to the next event, the event at the start of the following read, and nothing but
 * the delegate's end of stream may end the stream.
 */
class ShellIntegrationTtyConnectorTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String PROMPT_START = ESC + "]133;A" + BEL;
    private static final String COMMAND_START = ESC + "]133;B" + BEL;

    @Test
    void aReadNeverReturnsZeroBeforeTheEndOfTheStream() throws IOException {
        List<ShellIntegrationEvent> events = new ArrayList<>();
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
            new ScriptedConnector(List.of("abc", PROMPT_START, COMMAND_START, "def")), events::add);

        List<Integer> counts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[1024];
        int count;
        while ((count = connector.read(buffer, 0, buffer.length)) > 0) {
            counts.add(count);
            text.append(buffer, 0, count);
        }

        assertThat(count).isEqualTo(-1);
        assertWithMessage("the two event-only chunks are not returned as empty reads")
            .that(counts).containsExactly(3, 3).inOrder();
        assertThat(text.toString()).isEqualTo("abcdef");
        assertThat(events).containsExactly(new PromptStart(), new CommandStart()).inOrder();
    }

    @Test
    void anEventIsDeliveredOnlyAfterEveryCharBeforeItWasReturned() throws IOException {
        for (int readSize : new int[] {1, 2, 3, 4, 5, 8, 64}) {
            StringBuilder returned = new StringBuilder();
            List<String> seenAtEvent = new ArrayList<>();
            ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
                new ScriptedConnector(List.of("abc" + PROMPT_START + "defg" + COMMAND_START + "hi")),
                event -> seenAtEvent.add(event.summary() + "@" + returned));

            char[] buffer = new char[readSize];
            int count;
            while ((count = connector.read(buffer, 0, buffer.length)) > 0) {
                int before = returned.length();
                int eventsBefore = seenAtEvent.size();
                returned.append(buffer, 0, count);
                for (int position : new int[] {3, 7}) {
                    assertWithMessage("read size %s: no read crosses the event at %s", readSize, position)
                        .that(before < position && position < returned.length()).isFalse();
                }
                assertThat(eventsBefore).isAtMost(seenAtEvent.size());
            }

            assertWithMessage("read size %s", readSize).that(returned.toString()).isEqualTo("abcdefghi");
            assertWithMessage("read size %s: each event sees exactly the text before it", readSize)
                .that(seenAtEvent).containsExactly("PromptStart@abc", "CommandStart@abcdefg").inOrder();
        }
    }

    @Test
    void anEventIsDeliveredAtTheStartOfTheReadAfterItsText() throws IOException {
        List<String> log = new ArrayList<>();
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
            new ScriptedConnector(List.of("abc" + PROMPT_START + "de")), event -> log.add(event.summary()));
        char[] buffer = new char[64];

        log.add(read(connector, buffer));
        log.add(read(connector, buffer));

        assertThat(log).containsExactly("abc", "PromptStart", "de").inOrder();
    }

    @Test
    void aChunkOfOnlyEventsWaitsForMoreOutput() throws Exception {
        BlockingConnector delegate = new BlockingConnector();
        CountDownLatch delivered = new CountDownLatch(1);
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(delegate, event -> delivered.countDown());
        delegate.send("$ ");
        delegate.send(COMMAND_START);
        char[] buffer = new char[1024];
        assertThat(connector.read(buffer, 0, buffer.length)).isEqualTo(2);

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> nextRead = reader.submit(() -> connector.read(buffer, 0, buffer.length));
            assertWithMessage("the event is delivered as soon as the emulator asks again")
                .that(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                nextRead.get(200, TimeUnit.MILLISECONDS);
                throw new AssertionError("the read returned without output");
            } catch (TimeoutException expected) {
                // still waiting for the shell
            }

            delegate.send("ls");
            assertThat(nextRead.get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(new String(buffer, 0, 2)).isEqualTo("ls");
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void readyReportsPendingTextButNotAnEventAlone() throws IOException {
        List<ShellIntegrationEvent> events = new ArrayList<>();
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
            new ScriptedConnector(List.of("a" + PROMPT_START + "b", "c" + COMMAND_START)), events::add);
        char[] buffer = new char[64];

        assertThat(connector.ready()).isTrue();
        assertThat(read(connector, buffer)).isEqualTo("a");
        assertThat(connector.ready()).isTrue();
        assertThat(read(connector, buffer)).isEqualTo("b");
        assertThat(read(connector, buffer)).isEqualTo("c");
        assertWithMessage("the delegate is drained and only an event is left: nothing to read")
            .that(connector.ready()).isFalse();
        assertThat(events).containsExactly(new PromptStart());

        assertThat(connector.read(buffer, 0, buffer.length)).isEqualTo(-1);
        assertThat(events).containsExactly(new PromptStart(), new CommandStart()).inOrder();
    }

    @Test
    void readyReportsTextBehindAnEventWhenTheDelegateHasNothing() throws IOException {
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
            new ScriptedConnector(List.of("a" + PROMPT_START + "b")), event -> { });
        char[] buffer = new char[64];

        assertThat(read(connector, buffer)).isEqualTo("a");
        assertWithMessage("\"b\" waits behind the event, though the delegate is drained")
            .that(connector.ready()).isTrue();
        assertThat(read(connector, buffer)).isEqualTo("b");
        assertThat(connector.ready()).isFalse();
    }

    @Test
    void theEndOfTheStreamComesThroughAfterThePendingEvents() throws IOException {
        List<ShellIntegrationEvent> events = new ArrayList<>();
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
            new ScriptedConnector(List.of("bye" + PROMPT_START)), events::add);
        char[] buffer = new char[16];

        assertThat(connector.read(buffer, 0, buffer.length)).isEqualTo(3);
        assertThat(events).isEmpty();
        assertThat(connector.read(buffer, 0, buffer.length)).isEqualTo(-1);
        assertThat(events).containsExactly(new PromptStart());

        ShellIntegrationTtyConnector zero = new ShellIntegrationTtyConnector(new ZeroConnector(), events::add);
        assertWithMessage("a zero count is the delegate's to give").that(zero.read(buffer, 0, 4)).isEqualTo(0);
        assertThat(zero.read(buffer, 0, 0)).isEqualTo(0);
    }

    @Test
    void twoWrappersOverOneDelegateKeepTheirOwnState() throws IOException {
        // A Mosh recovery re-decorates a live pane while its old emulator may still read the old chain.
        ScriptedConnector delegate = new ScriptedConnector(List.of("x" + ESC + "]133;", "A" + BEL + "y", "z" + PROMPT_START));
        List<ShellIntegrationEvent> first = new ArrayList<>();
        List<ShellIntegrationEvent> second = new ArrayList<>();
        ShellIntegrationTtyConnector oldChain = new ShellIntegrationTtyConnector(delegate, first::add);
        ShellIntegrationTtyConnector newChain = new ShellIntegrationTtyConnector(delegate, second::add);
        char[] buffer = new char[64];

        assertThat(read(oldChain, buffer)).isEqualTo("x");
        assertWithMessage("the half sequence the old wrapper holds is not the new wrapper's")
            .that(read(newChain, buffer)).isEqualTo("A" + BEL + "y");
        assertThat(read(newChain, buffer)).isEqualTo("z");
        assertThat(newChain.read(buffer, 0, buffer.length)).isEqualTo(-1);

        assertThat(first).isEmpty();
        assertThat(second).containsExactly(new PromptStart());
    }

    @Test
    void aFailingEventHandlerDoesNotStopTheStream() throws IOException {
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(
            new ScriptedConnector(List.of("a" + PROMPT_START + "b")),
            event -> {
                throw new IllegalStateException("handler bug");
            });
        char[] buffer = new char[8];

        assertThat(read(connector, buffer)).isEqualTo("a");
        assertThat(read(connector, buffer)).isEqualTo("b");
    }

    @Test
    void inputAndLifecycleGoStraightToTheDelegate() throws Exception {
        ScriptedConnector delegate = new ScriptedConnector(List.of());
        ShellIntegrationTtyConnector connector = new ShellIntegrationTtyConnector(delegate, event -> { });

        connector.write("ls\r");
        connector.write("pwd\r".getBytes(StandardCharsets.UTF_8));
        connector.resize(new TermSize(100, 30));

        assertThat(delegate.written()).containsExactly("ls\r", "pwd\r").inOrder();
        assertThat(connector.getName()).isEqualTo("scripted");
        assertThat(connector.waitFor()).isEqualTo(0);
        assertThat(connector.isConnected()).isTrue();
        assertThat(connector.delegate()).isSameInstanceAs(delegate);
        connector.close();
        assertThat(delegate.isConnected()).isFalse();
    }

    private static String read(ShellIntegrationTtyConnector connector, char[] buffer) throws IOException {
        int count = connector.read(buffer, 0, buffer.length);
        assertThat(count).isGreaterThan(0);
        return new String(buffer, 0, count);
    }

    /** Output arrives when the test sends it, as from a shell waiting for input. */
    private static final class BlockingConnector extends AbstractTestConnector {
        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();

        void send(String text) {
            output.add(text);
        }

        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            try {
                String chunk = output.take();
                chunk.getChars(0, chunk.length(), buf, offset);
                return chunk.length();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
        }

        @Override
        public boolean ready() {
            return !output.isEmpty();
        }
    }

    private static final class ZeroConnector extends AbstractTestConnector {
        @Override
        public int read(char[] buf, int offset, int length) {
            return 0;
        }

        @Override
        public boolean ready() {
            return false;
        }
    }

    private abstract static class AbstractTestConnector implements TtyConnector {
        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public void close() {
        }
    }
}
