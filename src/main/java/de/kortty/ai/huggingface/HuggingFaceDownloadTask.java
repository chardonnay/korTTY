package de.kortty.ai.huggingface;

import java.util.concurrent.CompletableFuture;

/** Handle returned by an asynchronous GGUF download. */
public final class HuggingFaceDownloadTask {

    private final HuggingFaceDownloadController controller;
    private final CompletableFuture<HuggingFaceDownloadResult> completion;

    HuggingFaceDownloadTask(
        HuggingFaceDownloadController controller,
        CompletableFuture<HuggingFaceDownloadResult> completion
    ) {
        this.controller = controller;
        this.completion = completion;
    }

    public void pause() {
        controller.pause();
    }

    public void resume() {
        controller.resume();
    }

    public void cancel() {
        controller.cancel();
    }

    public CompletableFuture<HuggingFaceDownloadResult> completion() {
        return completion;
    }
}
