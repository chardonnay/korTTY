package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The AI skills bundled with this KorTTY build. Enumerated via a build-time index resource
 * (JAR resources cannot be listed at runtime), each entry parsed through the trusted
 * {@link AiSkillMarkdownCodec#loadBundled} path. Immutable; cached after the first successful load.
 */
public final class BuiltinAiSkillCatalog {

    static final String INDEX_RESOURCE = "builtin-ai-skills/builtin-ai-skills.index";
    static final String RESOURCE_DIR = "builtin-ai-skills/";

    private static final Logger logger = LoggerFactory.getLogger(BuiltinAiSkillCatalog.class);
    private static final Pattern VALID_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.md");

    private static BuiltinAiSkillCatalog cached;

    private final List<AiSkillMarkdownCodec.BundledAiSkill> entries;
    private final Map<String, AiSkillMarkdownCodec.BundledAiSkill> byId;

    private BuiltinAiSkillCatalog(List<AiSkillMarkdownCodec.BundledAiSkill> entries) {
        this.entries = List.copyOf(entries);
        Map<String, AiSkillMarkdownCodec.BundledAiSkill> index = new LinkedHashMap<>();
        for (AiSkillMarkdownCodec.BundledAiSkill entry : entries) {
            AiSkillMarkdownCodec.BundledAiSkill previous =
                index.putIfAbsent(entry.skill().getBuiltinId(), entry);
            if (previous != null) {
                logger.warn("Duplicate builtin AI skill id {} in bundled catalog, keeping first entry",
                    entry.skill().getBuiltinId());
            }
        }
        this.byId = Map.copyOf(index);
    }

    /** Loads and caches the bundled catalog; a failed load is retried on the next call. */
    public static synchronized BuiltinAiSkillCatalog load() {
        if (cached == null || cached.isEmpty()) {
            cached = new BuiltinAiSkillCatalog(loadEntries());
        }
        return cached;
    }

    /** Test seam: builds a catalog from hand-made entries without touching classpath resources. */
    static BuiltinAiSkillCatalog of(List<AiSkillMarkdownCodec.BundledAiSkill> entries) {
        return new BuiltinAiSkillCatalog(entries);
    }

    public List<AiSkillMarkdownCodec.BundledAiSkill> entries() {
        return entries;
    }

    public Optional<AiSkillMarkdownCodec.BundledAiSkill> byId(String builtinId) {
        return Optional.ofNullable(builtinId != null ? byId.get(builtinId) : null);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private static List<AiSkillMarkdownCodec.BundledAiSkill> loadEntries() {
        List<AiSkillMarkdownCodec.BundledAiSkill> entries = new ArrayList<>();
        try (InputStream indexStream =
                 BuiltinAiSkillCatalog.class.getClassLoader().getResourceAsStream(INDEX_RESOURCE)) {
            if (indexStream == null) {
                logger.warn("Builtin AI skill index resource {} not found", INDEX_RESOURCE);
                return entries;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String fileName = line.strip();
                if (fileName.isEmpty() || fileName.startsWith("#")) {
                    continue;
                }
                if (!VALID_FILE_NAME.matcher(fileName).matches()) {
                    logger.warn("Skipping builtin AI skill with invalid file name: {}", fileName);
                    continue;
                }
                loadEntry(fileName).ifPresent(entries::add);
            }
        } catch (IOException e) {
            logger.warn("Failed to read builtin AI skill index {}", INDEX_RESOURCE, e);
        }
        return entries;
    }

    private static Optional<AiSkillMarkdownCodec.BundledAiSkill> loadEntry(String fileName) {
        String resourceName = RESOURCE_DIR + fileName;
        try (InputStream stream =
                 BuiltinAiSkillCatalog.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (stream == null) {
                logger.warn("Builtin AI skill resource {} listed in index but not found", resourceName);
                return Optional.empty();
            }
            String markdown = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(AiSkillMarkdownCodec.loadBundled(fileName, markdown));
        } catch (IOException e) {
            logger.warn("Failed to load builtin AI skill {}", resourceName, e);
            return Optional.empty();
        }
    }
}
