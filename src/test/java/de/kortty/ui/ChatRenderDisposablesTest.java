package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the lifecycle contract of {@link ChatRenderDisposables}, the registry that lets
 * {@code AiResultTab} release its rendered Monaco/WebView engines on rebuild (font zoom) and
 * tab close: every registered disposable runs exactly once per generation, stale epoch tokens
 * stop async render callbacks (Mermaid future and MathJax poll chain) from attaching
 * WebViews to a rebuilt or closed tab, and late registrations after close are disposed
 * immediately instead of leaking.
 */
public class ChatRenderDisposablesTest {

    @Test
    public void disposeAllRunsEveryRegisteredDisposableOnce() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        registry.register(first::incrementAndGet);
        registry.register(second::incrementAndGet);

        registry.disposeAll();
        registry.disposeAll();

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    public void disposeAllInvalidatesOutstandingEpochTokens() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        int token = registry.epoch();
        assertThat(registry.isLive(token)).isTrue();

        registry.disposeAll();

        assertThat(registry.isLive(token)).isFalse();
        assertThat(registry.isLive(registry.epoch())).isTrue();
    }

    @Test
    public void closeDisposesAndRefusesFutureEpochs() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        AtomicInteger disposed = new AtomicInteger();
        registry.register(disposed::incrementAndGet);

        registry.close();

        assertThat(disposed.get()).isEqualTo(1);
        assertThat(registry.isClosed()).isTrue();
        assertThat(registry.isLive(registry.epoch())).isFalse();
    }

    @Test
    public void registrationAfterCloseDisposesImmediately() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        registry.close();

        AtomicInteger disposed = new AtomicInteger();
        registry.register(disposed::incrementAndGet);

        assertThat(disposed.get()).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    public void registrationsInNewEpochDisposeOnNextRebuild() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        registry.register(() -> { });
        registry.disposeAll();

        AtomicInteger rerendered = new AtomicInteger();
        registry.register(rerendered::incrementAndGet);
        registry.disposeAll();

        assertThat(rerendered.get()).isEqualTo(1);
    }

    @Test
    public void oneFailingDisposableDoesNotStopTheRest() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        List<String> order = new ArrayList<>();
        registry.register(() -> order.add("first"));
        registry.register(() -> {
            throw new IllegalStateException("engine already torn down");
        });
        registry.register(() -> order.add("last"));

        registry.disposeAll();

        assertThat(order).containsExactly("first", "last").inOrder();
    }

    @Test
    public void closeIsIdempotent() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        AtomicInteger disposed = new AtomicInteger();
        registry.register(disposed::incrementAndGet);

        registry.close();
        registry.close();

        assertThat(disposed.get()).isEqualTo(1);
    }

    @Test
    public void nullRegistrationIsIgnored() {
        ChatRenderDisposables registry = new ChatRenderDisposables();
        registry.register(null);

        registry.disposeAll();

        assertThat(registry.size()).isEqualTo(0);
    }
}
