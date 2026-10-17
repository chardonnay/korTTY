package de.kortty.core;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/** Pause, resume and cancel of a translation run, and that a cancelled UI-string run writes no file. */
public class TranslationRunControlTest {

    /** Counts batches; each call is one "batch" of translated texts. */
    private static final class CountingService implements TranslationService {
        final AtomicInteger batches = new AtomicInteger();

        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return "[" + text + "]";
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            batches.incrementAndGet();
            List<String> out = new ArrayList<>();
            for (String text : texts) {
                out.add("[" + text + "]");
            }
            return out;
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    @Test
    void pauseBlocksThePollUntilResumeAndCancelReleasesIt() throws Exception {
        TranslationRunControl.Switch control = new TranslationRunControl.Switch();
        assertThat(control.awaitResumeOrCancel()).isFalse();
        control.pause();
        assertThat(control.isPaused()).isTrue();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        boolean[] result = new boolean[1];
        Thread worker = new Thread(() -> {
            entered.countDown();
            result[0] = control.awaitResumeOrCancel();
            released.countDown();
        });
        worker.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(released.await(200, TimeUnit.MILLISECONDS)).isFalse();
        control.resume();
        assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result[0]).isFalse();
        assertThat(control.pausedNanos()).isGreaterThan(0L);

        control.pause();
        Thread second = new Thread(control::awaitResumeOrCancel);
        second.start();
        control.cancel();
        second.join(2000);
        assertThat(second.isAlive()).isFalse();
        assertThat(control.isCancelled()).isTrue();
        assertThat(control.isPaused()).isFalse();
        assertThat(control.awaitResumeOrCancel()).isTrue();
        control.reset();
        assertThat(control.isCancelled()).isFalse();
        assertThat(control.pausedNanos()).isEqualTo(0L);
    }

    @Test
    void cancelledUiStringRunWritesNoFileAndStopsAtTheNextBatch() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-translation-cancel");
        try {
            CountingService service = new CountingService();
            DynamicLanguageGenerator generator = new DynamicLanguageGenerator(service, configDir);
            TranslationRunControl.Switch control = new TranslationRunControl.Switch();
            int[] seen = {0};
            IOException failure = null;
            try {
                generator.generate("xx", fraction -> {
                    seen[0]++;
                    if (seen[0] == 3) {
                        control.cancel();
                    }
                }, control.asCancelPoll());
            } catch (IOException e) {
                failure = e;
            }
            assertThat(failure).isInstanceOf(DynamicLanguageGenerator.CancelledException.class);
            assertThat(service.batches.get()).isEqualTo(3);
            assertThat(Files.exists(configDir.resolve("i18n").resolve("messages_xx.properties"))).isFalse();

            // Without a cancel the same generator writes the file.
            Path written = generator.generate("yy", null, TranslationRunControl.of(() -> false).asCancelPoll());
            assertThat(Files.isRegularFile(written)).isTrue();
            assertThat(DynamicLanguageGenerator.keyCount()).isGreaterThan(DynamicLanguageGenerator.batchSize());
        } finally {
            try (var files = Files.walk(configDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
