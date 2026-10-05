package de.kortty.core.worker;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/** The control channel between korTTY and a session worker: requests, answers, errors and events. */
class WorkerEndpointTest {

    /** Two endpoints wired to each other through pipes. */
    private static WorkerEndpoint[] pair() throws IOException {
        PipedOutputStream aOut = new PipedOutputStream();
        PipedInputStream bIn = new PipedInputStream(aOut, 1 << 16);
        PipedOutputStream bOut = new PipedOutputStream();
        PipedInputStream aIn = new PipedInputStream(bOut, 1 << 16);
        return new WorkerEndpoint[] {new WorkerEndpoint(aIn, aOut, "a"), new WorkerEndpoint(bIn, bOut, "b")};
    }

    @Test
    void aRequestGetsItsAnswer() throws Exception {
        WorkerEndpoint[] ends = pair();
        ends[1].setRequestHandler((method, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("echo", method + ":" + params.get("x").getAsString());
            return result;
        });
        ends[0].start();
        ends[1].start();
        JsonObject params = new JsonObject();
        params.addProperty("x", "1\n2");
        assertThat(ends[0].call("hello", params, 5_000).get("echo").getAsString()).isEqualTo("hello:1\n2");
        ends[0].close();
        ends[1].close();
    }

    @Test
    void aFailingHandlerAnswersWithAnError() throws Exception {
        WorkerEndpoint[] ends = pair();
        ends[1].setRequestHandler((method, params) -> {
            throw new IllegalStateException("refused");
        });
        ends[0].start();
        ends[1].start();
        try {
            ends[0].call("x", new JsonObject(), 5_000);
            throw new AssertionError("expected an error answer");
        } catch (WorkerEndpoint.RemoteException expected) {
            assertThat(expected).hasMessageThat().isEqualTo("refused");
        }
        ends[0].close();
        ends[1].close();
    }

    @Test
    void eventsReachTheListenerAndAClosedPeerFailsPendingCalls() throws Exception {
        WorkerEndpoint[] ends = pair();
        CompletableFuture<String> event = new CompletableFuture<>();
        ends[1].setEventListener(message -> event.complete(message.get("type").getAsString()));
        ends[1].setRequestHandler((method, params) -> {
            Thread.sleep(60_000);
            return null;
        });
        ends[0].start();
        ends[1].start();
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "ready");
        ends[0].send(ready);
        assertThat(event.get(5, TimeUnit.SECONDS)).isEqualTo("ready");

        CompletableFuture<Throwable> failure = CompletableFuture.supplyAsync(() -> {
            try {
                ends[0].call("slow", new JsonObject(), 30_000);
                return null;
            } catch (IOException e) {
                return e;
            }
        });
        Thread.sleep(200);
        ends[1].close();
        assertThat(failure.get(10, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
        ends[0].close();
    }
}
