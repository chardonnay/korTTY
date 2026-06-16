package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class LmStudioCliModelsTest {

    @Test
    void parsesChatModelKeysAndSkipsEmbeddingsAndWakeBanner() {
        // Real `lms ls --json` shape (from empirical probe): two LLMs + one embedding model,
        // preceded by the "waking up" banner lms can print before the JSON.
        String raw = "Waking up LM Studio service...\n"
            + "[{\"type\":\"llm\",\"modelKey\":\"qwen/qwen3.6-35b-a3b\",\"displayName\":\"Qwen3.6\"},"
            + "{\"type\":\"llm\",\"modelKey\":\"openai/gpt-oss-20b\",\"displayName\":\"GPT-OSS 20B\"},"
            + "{\"type\":\"embedding\",\"modelKey\":\"text-embedding-nomic-embed-text-v1.5\"}]\n";

        List<String> keys = LmStudioCliModels.parseModelKeys(raw);

        assertThat(keys).containsExactly("qwen/qwen3.6-35b-a3b", "openai/gpt-oss-20b").inOrder();
    }

    @Test
    void parsesModelKeysWithNestedArraysInObjects() {
        // Real lms entries contain nested arrays (e.g. "variants":[...]); the outer array must still
        // be extracted correctly (last ']' is the outer array's).
        String raw = "[{\"type\":\"llm\",\"modelKey\":\"openai/gpt-oss-20b\","
            + "\"variants\":[\"openai/gpt-oss-20b@mxfp4\"],\"maxContextLength\":131072}]";

        assertThat(LmStudioCliModels.parseModelKeys(raw)).containsExactly("openai/gpt-oss-20b");
    }

    @Test
    void parseModelKeysHandlesEmptyAndGarbage() {
        assertThat(LmStudioCliModels.parseModelKeys("[]")).isEmpty();
        assertThat(LmStudioCliModels.parseModelKeys("no json here")).isEmpty();
        assertThat(LmStudioCliModels.parseModelKeys(null)).isEmpty();
    }
}
