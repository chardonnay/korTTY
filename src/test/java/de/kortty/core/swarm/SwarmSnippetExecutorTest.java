package de.kortty.core.swarm;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;

class SwarmSnippetExecutorTest {

    /** Scriptable fake runner: result/exception per instance, optional start/finish latches. */
    private static final class FakeRunner implements AgentCommandRunner {
        private final ShellKind shellKind;
        private final boolean connected;
        private final AgentCommandRunner.ExecResult result;
        private final Exception failure;
        private final CountDownLatch releaseLatch;
        private final List<String> emitChunks;

        FakeRunner(ShellKind shellKind, boolean connected, ExecResult result,
                   Exception failure, CountDownLatch releaseLatch, List<String> emitChunks) {
            this.shellKind = shellKind;
            this.connected = connected;
            this.result = result;
            this.failure = failure;
            this.releaseLatch = releaseLatch;
            this.emitChunks = emitChunks != null ? emitChunks : List.of();
        }

        static FakeRunner completing(int exitCode, String stdout, String stderr) {
            return new FakeRunner(ShellKind.POSIX, true,
                new ExecResult(stdout, stderr, exitCode, false, false), null, null, null);
        }

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                               BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory)
            throws Exception {
            if (releaseLatch != null && !releaseLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release latch never opened");
            }
            for (String chunk : emitChunks) {
                if (outputConsumer != null) {
                    outputConsumer.accept(chunk);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ShellKind shellKind() {
            return shellKind;
        }

        @Override
        public String currentWorkingDirectory() {
            return null;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private static SwarmTarget target(String id, AgentCommandRunner runner) {
        return new SwarmTarget(id, new ServerConnection(id, id, 22, "root"), runner, null, "sess-" + id, id);
    }

    private static final class CollectingListener implements SwarmSnippetExecutor.Listener {
        final List<String> started = Collections.synchronizedList(new ArrayList<>());
        final List<String> chunks = Collections.synchronizedList(new ArrayList<>());
        final List<SwarmSnippetExecutor.TargetOutcome> finished = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch allDone = new CountDownLatch(1);
        volatile List<SwarmSnippetExecutor.TargetOutcome> ordered;

        @Override
        public void onTargetStarted(String agentId) {
            started.add(agentId);
        }

        @Override
        public void onTargetOutput(String agentId, String chunk) {
            chunks.add(agentId + ":" + chunk);
        }

        @Override
        public void onTargetFinished(SwarmSnippetExecutor.TargetOutcome outcome) {
            finished.add(outcome);
        }

        @Override
        public void onAllFinished(List<SwarmSnippetExecutor.TargetOutcome> outcomesInTargetOrder) {
            ordered = outcomesInTargetOrder;
            allDone.countDown();
        }

        List<SwarmSnippetExecutor.TargetOutcome> await() throws InterruptedException {
            assertThat(allDone.await(15, TimeUnit.SECONDS)).isTrue();
            return ordered;
        }
    }

    @Test(timeOut = 30_000)
    void runsAllTargetsAndReportsInTargetOrderDespiteShuffledCompletion() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        FakeRunner slow = new FakeRunner(AgentCommandRunner.ShellKind.POSIX, true,
            new AgentCommandRunner.ExecResult("slow-out", "", 0, false, false), null, releaseFirst, null);
        FakeRunner fast = FakeRunner.completing(0, "fast-out", "");
        CollectingListener listener = new CollectingListener();

        new SwarmSnippetExecutor().run(
            List.of(target("a", slow), target("b", fast)), "cmd", new SwarmRunControl(), listener);
        // let the fast one finish first, then release the slow one
        long deadline = System.currentTimeMillis() + 5_000;
        while (listener.finished.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        releaseFirst.countDown();

        List<SwarmSnippetExecutor.TargetOutcome> ordered = listener.await();
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).agentId()).isEqualTo("a");
        assertThat(ordered.get(1).agentId()).isEqualTo("b");
        assertThat(ordered.get(0).output()).isEqualTo("slow-out");
    }

    @Test(timeOut = 30_000)
    void reportsNotConnectedAndUnsupportedShellWithoutExec() throws Exception {
        FakeRunner disconnected = new FakeRunner(AgentCommandRunner.ShellKind.POSIX, false, null, null, null, null);
        FakeRunner powershell = new FakeRunner(AgentCommandRunner.ShellKind.WINDOWS_POWERSHELL, true, null, null, null, null);
        CollectingListener listener = new CollectingListener();

        new SwarmSnippetExecutor().run(
            List.of(target("a", disconnected), target("b", powershell), target("c", null)),
            "cmd", new SwarmRunControl(), listener);

        List<SwarmSnippetExecutor.TargetOutcome> ordered = listener.await();
        assertThat(ordered.get(0).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.NOT_CONNECTED);
        assertThat(ordered.get(1).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.UNSUPPORTED_SHELL);
        assertThat(ordered.get(2).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.NOT_CONNECTED);
        assertThat(listener.started).isEmpty();
    }

    @Test(timeOut = 30_000)
    void mapsCancelledTimedOutErrorAndExitCodes() throws Exception {
        FakeRunner cancelled = new FakeRunner(AgentCommandRunner.ShellKind.POSIX, true,
            new AgentCommandRunner.ExecResult("", "", -1, true, false), null, null, null);
        FakeRunner timedOut = new FakeRunner(AgentCommandRunner.ShellKind.POSIX, true,
            new AgentCommandRunner.ExecResult("partial", "", -1, false, true), null, null, null);
        FakeRunner failing = new FakeRunner(AgentCommandRunner.ShellKind.POSIX, true,
            null, new IllegalStateException("boom"), null, null);
        FakeRunner nonZero = FakeRunner.completing(3, "", "err text");
        CollectingListener listener = new CollectingListener();

        new SwarmSnippetExecutor().run(
            List.of(target("a", cancelled), target("b", timedOut), target("c", failing), target("d", nonZero)),
            "cmd", new SwarmRunControl(), listener);

        List<SwarmSnippetExecutor.TargetOutcome> ordered = listener.await();
        assertThat(ordered.get(0).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.CANCELLED);
        assertThat(ordered.get(1).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.TIMED_OUT);
        assertThat(ordered.get(1).output()).isEqualTo("partial");
        assertThat(ordered.get(2).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.ERROR);
        assertThat(ordered.get(2).errorDetail()).isEqualTo("boom");
        assertThat(ordered.get(3).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.COMPLETED);
        assertThat(ordered.get(3).exitCode()).isEqualTo(3);
        assertThat(ordered.get(3).output()).isEqualTo("err text");
    }

    @Test(timeOut = 30_000)
    void cancelledControlShortCircuitsTargetsThatHaveNotStarted() throws Exception {
        SwarmRunControl control = new SwarmRunControl();
        control.cancelAll();
        FakeRunner runner = FakeRunner.completing(0, "never", "");
        CollectingListener listener = new CollectingListener();

        new SwarmSnippetExecutor().run(List.of(target("a", runner)), "cmd", control, listener);

        List<SwarmSnippetExecutor.TargetOutcome> ordered = listener.await();
        assertThat(ordered.get(0).kind()).isEqualTo(SwarmSnippetExecutor.OutcomeKind.CANCELLED);
        assertThat(listener.started).isEmpty();
    }

    @Test(timeOut = 30_000)
    void forwardsOutputChunksWhileRunning() throws Exception {
        FakeRunner chunky = new FakeRunner(AgentCommandRunner.ShellKind.POSIX, true,
            new AgentCommandRunner.ExecResult("done", "", 0, false, false), null, null,
            List.of("chunk-1", "chunk-2"));
        CollectingListener listener = new CollectingListener();

        new SwarmSnippetExecutor().run(List.of(target("a", chunky)), "cmd", new SwarmRunControl(), listener);

        listener.await();
        assertThat(listener.chunks).containsExactly("a:chunk-1", "a:chunk-2").inOrder();
        assertThat(listener.started).containsExactly("a");
    }

    @Test(timeOut = 30_000)
    void mergesStderrIntoOutput() throws Exception {
        FakeRunner both = FakeRunner.completing(0, "out", "err");
        CollectingListener listener = new CollectingListener();

        new SwarmSnippetExecutor().run(List.of(target("a", both)), "cmd", new SwarmRunControl(), listener);

        List<SwarmSnippetExecutor.TargetOutcome> ordered = listener.await();
        assertThat(ordered.get(0).output()).isEqualTo("out\nerr");
    }

    @Test(timeOut = 30_000)
    void emptyTargetsFinishImmediately() throws Exception {
        CollectingListener listener = new CollectingListener();
        new SwarmSnippetExecutor().run(List.of(), "cmd", new SwarmRunControl(), listener);
        assertThat(listener.await()).isEmpty();
    }
}
