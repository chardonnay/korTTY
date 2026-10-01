package de.kortty.core.agent;

import de.kortty.core.AutomationJournalRecorder;
import org.testng.annotations.Test;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;

class JournalingAgentCommandRunnerTest {

    private static final class FixedRunner implements AgentCommandRunner {
        int execs;

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                               BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
            execs++;
            return new ExecResult("out", "", 0, false, false);
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            return new ExecResult("os=linux", "", 0, false, false);
        }

        @Override
        public ShellKind shellKind() {
            return ShellKind.POSIX;
        }

        @Override
        public String currentWorkingDirectory() {
            return "/root";
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    @Test
    void wrapLeavesTheRunnerAloneWhenNothingIsRecorded() {
        FixedRunner runner = new FixedRunner();

        assertThat(JournalingAgentCommandRunner.wrap(runner, AutomationJournalRecorder.NOOP)).isSameInstanceAs(runner);
        assertThat(JournalingAgentCommandRunner.wrap(runner, null)).isSameInstanceAs(runner);
        assertThat(JournalingAgentCommandRunner.wrap(null, AutomationJournalRecorder.NOOP)).isNull();
    }

    @Test
    void delegatesEveryCallAndReturnsTheDelegatesResult() throws Exception {
        FixedRunner runner = new FixedRunner();
        JournalingAgentCommandRunner journaling = new JournalingAgentCommandRunner(runner, AutomationJournalRecorder.NOOP);

        AgentCommandRunner.ExecResult result = journaling.exec("df -h", null, null, () -> false, true);

        assertThat(result.stdout()).isEqualTo("out");
        assertThat(runner.execs).isEqualTo(1);
        assertThat(journaling.runProbe(true, () -> false).stdout()).isEqualTo("os=linux");
        assertThat(journaling.shellKind()).isEqualTo(AgentCommandRunner.ShellKind.POSIX);
        assertThat(journaling.currentWorkingDirectory()).isEqualTo("/root");
        assertThat(journaling.isConnected()).isTrue();
        assertThat(journaling.delegate()).isSameInstanceAs(runner);
    }
}
