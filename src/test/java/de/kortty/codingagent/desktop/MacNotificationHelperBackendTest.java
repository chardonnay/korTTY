package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

class MacNotificationHelperBackendTest {
    private static final String TITLE = "needs a decision \"YES\"";
    private static final String BODY = "ümlaut\nline two \\ path";

    @Test(timeOut = 15_000)
    void privatePipesCarryEscapedTextAndClicksRunOnce() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
            TestNotificationHelper.class.getName());
        AtomicInteger clicks = new AtomicInteger();
        CountDownLatch clicked = new CountDownLatch(1);
        try (MacNotificationHelperBackend backend = new MacNotificationHelperBackend(command)) {
            assertThat(backend.supportsActivation()).isTrue();
            backend.notify(TITLE, BODY, () -> {
                clicks.incrementAndGet();
                clicked.countDown();
            });
            assertThat(clicked.await(10, TimeUnit.SECONDS)).isTrue();
            // A second notification is an ordered barrier after the duplicate click on the first.
            CountDownLatch second = new CountDownLatch(1);
            backend.notify(TITLE, BODY, second::countDown);
            assertThat(second.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(clicks.get()).isEqualTo(1);
            backend.close();
            assertThat(backend.isSupported()).isFalse();
            expectThrows(IOException.class, () -> backend.notify(TITLE, BODY));
        }
    }

    @Test
    void missingExecutableFailsInsteadOfOpeningScriptEditor() {
        try (MacNotificationHelperBackend backend = new MacNotificationHelperBackend(
                Path.of("nonexistent-kortty-test-helper"))) {
            expectThrows(IOException.class, () -> backend.notify(TITLE, BODY));
        }
    }

    /** Test double for the native app: validates JSON and reports duplicate clicks over stdout. */
    public static final class TestNotificationHelper {
        public static void main(String[] args) throws IOException {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    if (!TITLE.equals(request.get("title").getAsString())
                            || !BODY.equals(request.get("body").getAsString())) {
                        System.exit(7);
                    }
                    String click = "activate:" + request.get("identifier").getAsString();
                    System.out.println(click);
                    System.out.println(click);
                    System.out.flush();
                }
            }
        }
    }
}
