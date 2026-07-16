package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceModel;
import de.kortty.ai.huggingface.HuggingFaceModelCatalog;
import de.kortty.ai.llama.LlamaModel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Asynchronous boundary between the beginner wizard and local-model provisioning. */
public interface LocalAiSetupWorkflow {

    CompletableFuture<List<ModelDetails>> inspect(
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections);

    Installation install(
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections,
        List<ModelDetails> details,
        Consumer<Progress> progressListener);

    record ModelDetails(
        HuggingFaceModelCatalog.Recommendation recommendation,
        HuggingFaceModel model,
        long downloadBytes
    ) {
        public ModelDetails {
            if (recommendation == null || model == null) {
                throw new IllegalArgumentException("Recommendation and verified model metadata are required.");
            }
            if (downloadBytes < 0) {
                throw new IllegalArgumentException("Download size must not be negative.");
            }
        }
    }

    record Progress(
        Phase phase,
        String modelId,
        int completedModels,
        int totalModels,
        double fraction,
        String detail
    ) {
        public Progress {
            completedModels = Math.max(0, completedModels);
            totalModels = Math.max(0, totalModels);
            fraction = Math.max(0d, Math.min(1d, fraction));
        }
    }

    enum Phase {
        RUNTIME,
        DOWNLOADING,
        REGISTERING,
        TESTING,
        SAVING_ROLES,
        COMPLETE
    }

    interface Installation {
        CompletableFuture<List<LlamaModel>> completion();

        void cancel();
    }
}
