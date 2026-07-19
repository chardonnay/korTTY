package de.kortty.ai.huggingface;

import com.sun.net.httpserver.HttpServer;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.runtimeupdate.LlamaRuntimeInstallation;
import de.kortty.ai.runtimeupdate.LlamaRuntimePackageDescriptor;
import de.kortty.ai.runtimeupdate.LlamaRuntimePlatform;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class HuggingFaceModelInstallationServiceTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void registersModelOnlyAfterVerifiedDownloadCompletes() throws Exception {
        byte[] gguf = "test-gguf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange -> {
            exchange.sendResponseHeaders(200, gguf.length);
            try (var output = exchange.getResponseBody()) {
                output.write(gguf);
            }
        });
        server.start();
        Path root = Files.createTempDirectory("kortty-hf-install-register");
        try {
            Path runtimeDirectory = Files.createDirectories(root.resolve("runtime"));
            Path executable = runtimeDirectory.resolve(
                LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
                    ? "llama-server.exe" : "llama-server");
            Files.writeString(executable, "runtime");
            executable.toFile().setExecutable(true, true);
            LlamaRuntimePackageDescriptor descriptor = new LlamaRuntimePackageDescriptor(
                "llama-b10025-kortty1",
                "b10025",
                "a3e5b96ac5e278c390df429df0b68efcee3ee1b5",
                1,
                "2.5.2",
                LlamaRuntimePlatform.current(),
                LlamaRuntimePackageDescriptor.currentArchitecture(),
                LlamaBackend.CPU,
                1,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                URI.create("https://downloads.example.test/runtime.zip"),
                executable.getFileName().toString(),
                false);
            LlamaRuntimeInstallation runtime = new LlamaRuntimeInstallation(
                descriptor, runtimeDirectory, executable);
            URI downloadUri = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "model-Q4_K_M.gguf", gguf.length, sha256(gguf), downloadUri, "Q4_K_M", 1, 1);
            HuggingFaceModel model = new HuggingFaceModel(
                "owner/model", "owner", REVISION, "apache-2.0", "test", 8192,
                gguf.length, Set.of("Q4_K_M"), List.of(file), Set.of("gguf"),
                false, false, 1, 1, null);
            LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(root.resolve("llm"));
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newHttpClient(), HuggingFaceTokenProvider.anonymous(), Runnable::run);
            HuggingFaceModelInstallationService service = new HuggingFaceModelInstallationService(
                downloader, registry);

            LlamaModel installed = service.installAndRegister(
                model,
                "Q4_K_M",
                root.resolve("models"),
                runtime,
                new HuggingFaceDownloadController(),
                ignored -> { });

            assertThat(registry.find(installed.getId())).isPresent();
            assertThat(installed.getBackend()).isEqualTo(LlamaBackend.CPU);
            // Hub metadata advertises the model's maximum, but the safe local start value stays
            // conservative until the user explicitly raises it in the model settings.
            assertThat(installed.getContextSize()).isEqualTo(LlamaModel.DEFAULT_CONTEXT_SIZE);
            assertThat(Files.readAllBytes(installed.getModelPath())).isEqualTo(gguf);
            assertThat(installed.getServerExecutable()).isEqualTo(executable.toAbsolutePath().normalize());
        } finally {
            server.stop(0);
            deleteTree(root);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
