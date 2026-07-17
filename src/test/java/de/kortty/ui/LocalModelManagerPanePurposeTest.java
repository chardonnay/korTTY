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
import java.time.Duration;
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

    @Test
    void hidesRepositoriesWithoutSizesOrBeyondTheDetectedMemory() {
        long eightGiB = 8L * 1024 * 1024 * 1024;
        HuggingFaceModel unknownSize = hubModel(List.of(
            hubFile("model-Q4_K_M.gguf", -1, null, null),
            hubFile("model-Q8_0.gguf", -1, null, null)));
        HuggingFaceModel fitsWithSmallestQuant = hubModel(List.of(
            hubFile("model-Q4_K_M.gguf", 3L * 1024 * 1024 * 1024, sha('a'), URI.create("https://example.test/q4")),
            hubFile("model-Q8_0.gguf", 60L * 1024 * 1024 * 1024, sha('b'), URI.create("https://example.test/q8"))));
        HuggingFaceModel tooLarge = hubModel(List.of(
            hubFile("model-Q4_K_M.gguf", 60L * 1024 * 1024 * 1024, sha('c'), URI.create("https://example.test/large"))));

        assertThat(LocalModelManagerPane.usableOnThisMachine(unknownSize, eightGiB)).isFalse();
        assertThat(LocalModelManagerPane.usableOnThisMachine(fitsWithSmallestQuant, eightGiB)).isTrue();
        assertThat(LocalModelManagerPane.usableOnThisMachine(tooLarge, eightGiB)).isFalse();
        assertThat(fitsWithSmallestQuant.smallestQuantizationBytes())
            .isEqualTo(3L * 1024 * 1024 * 1024);
        assertThat(unknownSize.smallestQuantizationBytes()).isEqualTo(-1);
    }

    @Test
    void transferRatesUseReadableUnitsInsteadOfRoundingSmallRatesToZero() {
        assertThat(LocalModelManagerPane.formatTransferRate(12L * 1024L))
            .isEqualTo("12.0 KiB/s");
        assertThat(LocalModelManagerPane.formatTransferRate(3L * 1024L * 1024L))
            .isEqualTo("3.0 MiB/s");
    }

    @Test
    void elapsedAndRemainingDurationsRetainHoursForLongDownloads() {
        assertThat(LocalModelManagerPane.formatDuration(Duration.ofMinutes(2).plusSeconds(3)))
            .isEqualTo("02:03");
        assertThat(LocalModelManagerPane.formatDuration(
            Duration.ofHours(1).plusMinutes(2).plusSeconds(3)))
            .isEqualTo("01:02:03");
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
