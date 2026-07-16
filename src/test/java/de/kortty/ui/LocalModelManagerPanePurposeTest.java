package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceModel;
import de.kortty.ai.huggingface.HuggingFaceModelCatalog;
import de.kortty.ai.huggingface.HuggingFaceModelFile;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelPurpose;
import org.testng.annotations.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class LocalModelManagerPanePurposeTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void embeddingWizardNeverReusesChatRegistrationForSameWeightFile() {
        Path weights = Path.of("/tmp/shared-model.gguf");
        LlamaModel chat = model("chat", weights, LlamaModelPurpose.CHAT);

        assertThat(LocalModelManagerPane.purposeForRoles(
            Set.of(HuggingFaceModelCatalog.Role.EMBEDDING)))
            .isEqualTo(LlamaModelPurpose.EMBEDDING);
        assertThat(LocalModelManagerPane.reusableModelForPurpose(
            List.of(chat), weights, LlamaModelPurpose.EMBEDDING)).isEmpty();
        assertThat(LocalModelManagerPane.reusableModelForPurpose(
            List.of(chat), weights, LlamaModelPurpose.CHAT)).hasValue(chat);
    }

    @Test
    void detailedMetadataKeepsTheUsersSelectedQuantization() {
        HuggingFaceModel summary = hubModel(List.of(
            hubFile("model-Q4_K_M.gguf", -1, null, null),
            hubFile("model-Q4_K_S.gguf", -1, null, null)));
        HuggingFaceModel detailed = hubModel(List.of(
            hubFile("model-Q4_K_M.gguf", 20, sha('a'), URI.create("https://example.test/q4km")),
            hubFile("model-Q4_K_S.gguf", 15, sha('b'), URI.create("https://example.test/q4ks"))));

        assertThat(LocalModelManagerPane.sameHubModel(summary, detailed)).isTrue();
        assertThat(LocalModelManagerPane.preferredQuantization(detailed, "Q4_K_S"))
            .isEqualTo("Q4_K_S");
        assertThat(LocalModelManagerPane.hasVerifiedDownloadMetadata(summary, "Q4_K_S")).isFalse();
        assertThat(LocalModelManagerPane.hasVerifiedDownloadMetadata(detailed, "Q4_K_S")).isTrue();
        assertThat(detailed.bytesForQuantization("Q4_K_S")).isEqualTo(15);
    }

    @Test
    void differentSearchRevisionCannotBeAppliedAsTheSameModel() {
        HuggingFaceModel first = hubModel(List.of(hubFile("model-Q4_K_M.gguf", -1, null, null)));
        HuggingFaceModel newer = new HuggingFaceModel(
            first.id(), first.author(), "fedcba9876543210fedcba9876543210fedcba98",
            first.license(), first.architecture(), first.contextLength(), first.ggufBytes(),
            first.quantizations(), first.files(), first.tags(), first.gated(), first.privateRepository(),
            first.downloads(), first.likes(), first.lastModified());

        assertThat(LocalModelManagerPane.sameHubModel(first, newer)).isFalse();
    }

    private static LlamaModel model(String id, Path weights, LlamaModelPurpose purpose) {
        return new LlamaModel(
            id, id, weights, Path.of("/tmp/llama-server"), LlamaBackend.CPU, purpose,
            4096, 1, 0, 10);
    }

    private static HuggingFaceModel hubModel(List<HuggingFaceModelFile> files) {
        return new HuggingFaceModel(
            "owner/model", "owner", REVISION, "apache-2.0", "test", 4096, -1,
            null, files, Set.of("gguf"), false, false, 0, 0, null);
    }

    private static HuggingFaceModelFile hubFile(
        String path,
        long size,
        String sha256,
        URI downloadUri
    ) {
        return new HuggingFaceModelFile(path, size, sha256, downloadUri, null, 1, 1);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(64);
    }
}
