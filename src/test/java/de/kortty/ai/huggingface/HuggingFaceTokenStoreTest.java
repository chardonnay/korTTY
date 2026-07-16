package de.kortty.ai.huggingface;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class HuggingFaceTokenStoreTest {

    @Test
    void storesOnlyEncryptedTokenAndCanClearIt() throws Exception {
        Path directory = Files.createTempDirectory("kortty-hf-token");
        char[] password = "master-password".toCharArray();
        try {
            HuggingFaceTokenStore store = new HuggingFaceTokenStore(directory);
            store.store("hf_secret_token", password);

            byte[] persisted = Files.readAllBytes(store.tokenFile());
            assertThat(new String(persisted, StandardCharsets.UTF_8)).doesNotContain("hf_secret_token");
            assertThat(store.load(password)).hasValue("hf_secret_token");
            assertThat(store.isConfigured()).isTrue();

            store.clear();
            assertThat(store.isConfigured()).isFalse();
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
