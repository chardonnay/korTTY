package de.kortty.ai.llama;

import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class GgufMetadataReaderTest {

    @Test
    void readsArchitectureEmbeddingLengthAndSkipsOtherMetadata() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("GGUF".getBytes(StandardCharsets.US_ASCII));
        little(output, 3, 4);
        little(output, 0, 8);
        little(output, 2, 8);
        string(output, "general.name");
        little(output, 8, 4);
        string(output, "Embedding model");
        string(output, "qwen3.embedding_length");
        little(output, 4, 4);
        little(output, 1024, 4);
        Path file = Files.createTempFile("kortty-gguf-metadata", ".gguf");
        try {
            Files.write(file, output.toByteArray());
            assertThat(GgufMetadataReader.embeddingDimensions(file)).hasValue(1024);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsNonGgufInput() throws Exception {
        Path file = Files.createTempFile("kortty-not-gguf", ".gguf");
        try {
            Files.writeString(file, "not a model");
            expectThrows(java.io.IOException.class, () -> GgufMetadataReader.embeddingDimensions(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void string(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        little(output, bytes.length, 8);
        output.writeBytes(bytes);
    }

    private static void little(ByteArrayOutputStream output, long value, int bytes) {
        for (int index = 0; index < bytes; index++) output.write((int) (value >>> (8 * index)) & 0xff);
    }
}
