package de.kortty.ai.catalog;

import de.kortty.ai.catalog.AiModelPromptCatalog.PromptFamily;
import de.kortty.ai.catalog.AiModelPromptCatalog.Recommendation;
import de.kortty.ai.catalog.AiModelPromptCatalog.Role;
import de.kortty.model.AiPromptPreset;

import java.util.EnumSet;
import java.util.List;

/** Built-in safe fallback used when no trusted signed catalog is available. */
public final class AiCatalogBootstrap {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final AiModelPromptCatalog CATALOG = new AiModelPromptCatalog(
        AiModelPromptCatalog.SCHEMA_VERSION,
        1,
        "bootstrap-v1",
        List.of(
            new Recommendation("qwen3-1.7b-q4", "unsloth/Qwen3-1.7B-GGUF",
                "d7f544eead698dbd1f15126ef60b45a1e1933222", "Q4_K_M",
                EnumSet.of(Role.TEXT, Role.CODING), 0, 10),
            new Recommendation("qwen3-4b-q4", "Qwen/Qwen3-4B-GGUF",
                "bc640142c66e1fdd12af0bd68f40445458f3869b", "Q4_K_M",
                EnumSet.of(Role.TEXT), 16 * GIB, 20),
            new Recommendation("qwen3-8b-q4", "Qwen/Qwen3-8B-GGUF",
                "7c41481f57cb95916b40956ab2f0b139b296d974", "Q4_K_M",
                EnumSet.of(Role.TEXT), 24 * GIB, 30),
            new Recommendation("qwen2.5-coder-7b-q4", "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF",
                "13fb94bfda8c8cf22497dc57b78f391a9acb426a", "Q4_K_M",
                EnumSet.of(Role.CODING), 16 * GIB, 30),
            new Recommendation("qwen3-embedding-0.6b-q8", "Qwen/Qwen3-Embedding-0.6B-GGUF",
                "370f27d7550e0def9b39c1f16d3fbaa13aa67728", "Q8_0",
                EnumSet.of(Role.EMBEDDING), 0, 100)),
        List.of(
            new PromptFamily("qwen", AiPromptPreset.QWEN, List.of("qwen"), 100),
            new PromptFamily("deepseek", AiPromptPreset.DEEPSEEK, List.of("deepseek"), 90),
            new PromptFamily("mistral", AiPromptPreset.MISTRAL, List.of("mistral", "mixtral"), 80),
            new PromptFamily("gemma", AiPromptPreset.GEMMA, List.of("gemma"), 70),
            new PromptFamily("phi", AiPromptPreset.PHI, List.of("phi-3", "phi3", "phi-4", "phi4"), 60),
            new PromptFamily("gpt-oss", AiPromptPreset.GPT_OSS, List.of("gpt-oss", "gpt_oss"), 50),
            new PromptFamily("llama", AiPromptPreset.LLAMA, List.of("llama"), 40)));

    private AiCatalogBootstrap() {
    }

    public static AiModelPromptCatalog catalog() {
        return CATALOG;
    }
}
