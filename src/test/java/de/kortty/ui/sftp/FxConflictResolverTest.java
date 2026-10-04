package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.ConflictAction;
import de.kortty.core.sftp.transfer.ConflictInfo;
import de.kortty.core.sftp.transfer.ConflictPolicy;
import de.kortty.core.sftp.transfer.ConflictResolver.Resolution;
import de.kortty.core.sftp.transfer.TransferDirection;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/** Single-flight prompting with a fake presenter; no JavaFX toolkit. */
class FxConflictResolverTest {

    private static final long TIMEOUT_SECONDS = 20;

    private static ConflictInfo file(String name) {
        return ConflictInfo.files(TransferDirection.UPLOAD, "/local/" + name, "/srv", name, 1, 2, null, null);
    }

    /** Collects prompts so the test can answer them; counts the concurrently open ones. */
    private static final class FakePresenter implements FxConflictResolver.Presenter {
        final BlockingQueue<FxConflictResolver.Prompt> prompts = new LinkedBlockingQueue<>();
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();
        final AtomicInteger total = new AtomicInteger();

        @Override
        public void present(FxConflictResolver.Prompt prompt) {
            int now = open.incrementAndGet();
            maxOpen.accumulateAndGet(now, Math::max);
            total.incrementAndGet();
            prompt.whenDone(open::decrementAndGet);
            prompts.add(prompt);
        }
    }

    private static List<Future<ConflictAction>> startWorkers(ExecutorService pool, ConflictPolicy policy,
                                                             FxConflictResolver resolver, int count,
                                                             CountDownLatch started) {
        List<Future<ConflictAction>> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ConflictInfo info = file("f" + i);
            results.add(pool.submit(() -> {
                started.countDown();
                return policy.resolve(info, resolver);
            }));
        }
        return results;
    }

    @Test
    void threeConcurrentConflictsGiveOnePromptWithApplyToAll() throws Exception {
        FakePresenter presenter = new FakePresenter();
        FxConflictResolver resolver = new FxConflictResolver(presenter);
        ConflictPolicy policy = new ConflictPolicy();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch started = new CountDownLatch(3);
            List<Future<ConflictAction>> results = startWorkers(pool, policy, resolver, 3, started);
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            FxConflictResolver.Prompt prompt = presenter.prompts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(prompt).isNotNull();
            assertThat(prompt.allowedActions()).contains(ConflictAction.RENAME);
            // Give the other two workers time to queue up behind the open prompt.
            Thread.sleep(200);
            assertThat(presenter.total.get()).isEqualTo(1);
            prompt.answer(new Resolution(ConflictAction.SKIP, true));

            for (Future<ConflictAction> result : results) {
                assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ConflictAction.SKIP);
            }
            assertThat(presenter.total.get()).isEqualTo(1);
            assertThat(presenter.maxOpen.get()).isEqualTo(1);
            assertThat(resolver.isPrompting()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withoutApplyToAllThePromptsComeOneAtATime() throws Exception {
        FakePresenter presenter = new FakePresenter();
        FxConflictResolver resolver = new FxConflictResolver(presenter);
        ConflictPolicy policy = new ConflictPolicy();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch started = new CountDownLatch(3);
            List<Future<ConflictAction>> results = startWorkers(pool, policy, resolver, 3, started);
            for (int i = 0; i < 3; i++) {
                FxConflictResolver.Prompt prompt = presenter.prompts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(prompt).isNotNull();
                assertThat(presenter.open.get()).isEqualTo(1);
                prompt.answer(new Resolution(ConflictAction.OVERWRITE, false));
            }
            for (Future<ConflictAction> result : results) {
                assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ConflictAction.OVERWRITE);
            }
            assertThat(presenter.total.get()).isEqualTo(3);
            assertThat(presenter.maxOpen.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellingTheBatchWhileWaitingReleasesEveryWaiter() throws Exception {
        FakePresenter presenter = new FakePresenter();
        FxConflictResolver resolver = new FxConflictResolver(presenter);
        ConflictPolicy policy = new ConflictPolicy();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch started = new CountDownLatch(3);
            List<Future<ConflictAction>> results = startWorkers(pool, policy, resolver, 3, started);
            FxConflictResolver.Prompt prompt = presenter.prompts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(prompt).isNotNull();
            Thread.sleep(200);

            resolver.cancelBatch(policy);

            assertThat(prompt.isDone()).isTrue();
            for (Future<ConflictAction> result : results) {
                assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ConflictAction.CANCEL_ALL);
            }
            assertThat(presenter.total.get()).isEqualTo(1);
            assertThat(policy.isCancelled()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellingOneBatchLeavesAnotherBatchsPromptOpen() throws Exception {
        FakePresenter presenter = new FakePresenter();
        FxConflictResolver resolver = new FxConflictResolver(presenter);
        ConflictPolicy first = new ConflictPolicy();
        ConflictPolicy second = new ConflictPolicy();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConflictAction> a = pool.submit(() -> first.resolve(file("a"), resolver));
            FxConflictResolver.Prompt prompt = presenter.prompts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(prompt).isNotNull();
            Future<ConflictAction> b = pool.submit(() -> second.resolve(file("b"), resolver));
            Thread.sleep(200);

            resolver.cancelBatch(second);
            assertThat(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ConflictAction.CANCEL_ALL);
            assertThat(prompt.isDone()).isFalse();

            prompt.answer(new Resolution(ConflictAction.RENAME, false));
            assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ConflictAction.RENAME);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closingTheTabCompletesThePromptAndEveryLaterCall() throws Exception {
        FakePresenter presenter = new FakePresenter();
        FxConflictResolver resolver = new FxConflictResolver(presenter);
        ConflictPolicy policy = new ConflictPolicy();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch started = new CountDownLatch(2);
            List<Future<ConflictAction>> results = startWorkers(pool, policy, resolver, 2, started);
            FxConflictResolver.Prompt prompt = presenter.prompts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(prompt).isNotNull();

            resolver.close();

            for (Future<ConflictAction> result : results) {
                assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(ConflictAction.CANCEL_ALL);
            }
            assertThat(new ConflictPolicy().resolve(file("later"), resolver)).isEqualTo(ConflictAction.CANCEL_ALL);
            assertThat(presenter.total.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailingPresenterCancelsInsteadOfBlocking() throws Exception {
        FxConflictResolver resolver = new FxConflictResolver(prompt -> {
            throw new IllegalStateException("no window");
        });
        ConflictPolicy policy = new ConflictPolicy();
        assertThat(policy.resolve(file("a"), resolver)).isEqualTo(ConflictAction.CANCEL_ALL);
        assertThat(resolver.isPrompting()).isFalse();
    }

    @Test
    void anInterruptedWaiterGivesUpAndClosesItsPrompt() throws Exception {
        FakePresenter presenter = new FakePresenter();
        FxConflictResolver resolver = new FxConflictResolver(presenter);
        ConflictPolicy policy = new ConflictPolicy();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ConflictAction> result = pool.submit(() -> policy.resolve(file("a"), resolver));
            FxConflictResolver.Prompt prompt = presenter.prompts.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(prompt).isNotNull();
            result.cancel(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (resolver.isPrompting() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(resolver.isPrompting()).isFalse();
            assertThat(prompt.isDone()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
