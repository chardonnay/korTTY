package de.kortty.core;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

class LocalShellDirectoryChangeTrackerTest {

    @DataProvider(name = "directoryChangeCommands")
    Object[][] directoryChangeCommands() {
        return new Object[][] {
            {"cd /tmp"},
            {"chdir /tmp"},
            {"pushd /tmp"},
            {"popd"},
            {"Push-Location C:\\work"},
            {"Pop-Location"},
            {"Set-Location C:\\work"},
            {"SL C:\\work"},
            {"D:"},
            {"echo ok && cd /tmp"},
            {"cd.."},
            {"cd\\work"},
            {"builtin cd /tmp"}
        };
    }

    @Test(dataProvider = "directoryChangeCommands")
    void recognizesPotentialDirectoryChangeCommands(String command) {
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory(command)).isTrue();
    }

    @Test
    void ignoresDirectoryWordsThatAreNotCommands() {
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory("echo cd /tmp")).isFalse();
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory("echo 'cd /tmp'")).isFalse();
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory("printf set-location")).isFalse();
    }

    @Test
    void tracksCommandsAcrossWriteChunksAndEdits() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("c".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept(new byte[] {'x', '\b', 'd', ' ', '/'})).isFalse();
        assertThat(tracker.accept("tmp\r".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void ctrlUClearsAnAbandonedDirectoryChange() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("cd /wrong".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept(new byte[] {0x15})).isFalse();
        assertThat(tracker.accept("echo safe\r".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void bracketedPasteIsInspectedOnlyWhenSubmitted() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("\u001b[20".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept("0~echo first\ncd '/tmp/space dir'\u001b[201~"
            .getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept(new byte[] {'\r'})).isTrue();
    }

    @Test
    void splitUtf8PathDoesNotLoseAsciiCommandPrefix() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        byte[] command = "cd /tmp/Grüße\r".getBytes(StandardCharsets.UTF_8);
        byte[] first = java.util.Arrays.copyOfRange(command, 0, 11);
        byte[] second = java.util.Arrays.copyOfRange(command, 11, command.length);
        assertThat(tracker.accept(first)).isFalse();
        assertThat(tracker.accept(second)).isTrue();
    }

    @Test
    void crlfSubmitsOnlyOneLogicalLine() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("cd /tmp\r\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(tracker.accept("echo safe\r\n".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    private static final class RecordingListener
        implements LocalShellDirectoryChangeTracker.SubmittedLineListener {
        private final java.util.List<String> lines = new java.util.ArrayList<>();
        private int endOfFileCount;

        @Override
        public void onSubmittedLine(String line) {
            lines.add(line);
        }

        @Override
        public void onEndOfFileOnEmptyLine() {
            endOfFileCount++;
        }
    }

    @Test
    void listenerReceivesTheAssembledLineAfterEdits() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        RecordingListener listener = new RecordingListener();
        tracker.setSubmittedLineListener(listener);
        tracker.accept("ssX".getBytes(StandardCharsets.UTF_8));
        tracker.accept(new byte[] {'\b', 'h', ' '});
        tracker.accept("host\r".getBytes(StandardCharsets.UTF_8));
        assertThat(listener.lines).containsExactly("ssh host");
    }

    @Test
    void listenerReceivesPastedMultiLineInputOnSubmit() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        RecordingListener listener = new RecordingListener();
        tracker.setSubmittedLineListener(listener);
        tracker.accept("\u001b[200~echo first\nssh host\u001b[201~".getBytes(StandardCharsets.UTF_8));
        assertThat(listener.lines).isEmpty();
        tracker.accept(new byte[] {'\r'});
        assertThat(listener.lines).containsExactly("echo first\nssh host");
    }

    @Test
    void ctrlDOnEmptyLineNotifiesEndOfFile() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        RecordingListener listener = new RecordingListener();
        tracker.setSubmittedLineListener(listener);
        tracker.accept(new byte[] {0x04});
        assertThat(listener.endOfFileCount).isEqualTo(1);
    }

    @Test
    void ctrlDInTheMiddleOfALineIsIgnored() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        RecordingListener listener = new RecordingListener();
        tracker.setSubmittedLineListener(listener);
        tracker.accept("cat file".getBytes(StandardCharsets.UTF_8));
        tracker.accept(new byte[] {0x04});
        assertThat(listener.endOfFileCount).isEqualTo(0);
    }

    @Test
    void ctrlUClearsTheLineBeforeTheListenerSeesIt() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        RecordingListener listener = new RecordingListener();
        tracker.setSubmittedLineListener(listener);
        tracker.accept("ssh host".getBytes(StandardCharsets.UTF_8));
        tracker.accept(new byte[] {0x15});
        tracker.accept("ls\r".getBytes(StandardCharsets.UTF_8));
        assertThat(listener.lines).containsExactly("ls");
    }
}
