package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;


/** Shipping gate for the bundled skill catalog: every release must pass this. */
class BuiltinAiSkillCatalogTest {

    private static final int EXPECTED_SKILL_COUNT = 39;
    private static final Pattern VALID_BUILTIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    @Test
    void catalogShipsExpectedValidSkills() {
        BuiltinAiSkillCatalog catalog = BuiltinAiSkillCatalog.load();

        assertThat(catalog.entries()).hasSize(EXPECTED_SKILL_COUNT);

        Set<String> ids = new HashSet<>();
        for (AiSkillMarkdownCodec.BundledAiSkill entry : catalog.entries()) {
            AiSkill skill = entry.skill();
            String id = skill.getBuiltinId();
            assertThat(id).isNotNull();
            assertThat(VALID_BUILTIN_ID.matcher(id).matches()).isTrue();
            assertThat(ids.add(id)).isTrue();
            assertThat(entry.version()).isAtLeast(1);
            assertThat(skill.getName()).isNotEmpty();
            assertThat(skill.getDescription()).isNotEmpty();
            assertThat(skill.getTags()).isNotEmpty();
            assertThat(skill.getBuiltinTopics()).isNotEmpty();
            assertThat(skill.isEnabled()).isTrue();
            assertThat(skill.getTarget()).isEqualTo(AiSkillTarget.BOTH);
            String content = skill.getContent();
            assertThat(content).isNotNull();
            assertThat(content.isBlank()).isFalse();
            assertThat(content).contains("Best Practices");
        }
    }

    @Test
    void indexMatchesResourceDirectory() throws Exception {
        var indexUrl = getClass().getClassLoader().getResource(BuiltinAiSkillCatalog.INDEX_RESOURCE);
        assertThat(indexUrl).isNotNull();
        Path indexFile = Path.of(indexUrl.toURI());
        Path resourceDir = indexFile.getParent();

        Set<String> indexed = new HashSet<>();
        for (String line : Files.readAllLines(indexFile)) {
            String fileName = line.strip();
            if (!fileName.isEmpty() && !fileName.startsWith("#")) {
                assertThat(indexed.add(fileName)).isTrue();
            }
        }

        Set<String> onDisk = new HashSet<>();
        try (var stream = Files.list(resourceDir)) {
            for (Path path : stream.toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".md")) {
                    onDisk.add(fileName);
                }
            }
        }

        // Files added but not listed (or listed but missing) must fail the build.
        assertThat(indexed).isEqualTo(onDisk);
    }

    @Test
    void everyIndexedFileParsesThroughTheTrustedLoader() {
        // The catalog logs-and-skips broken entries at runtime; here a single skip is a failure.
        List<AiSkillMarkdownCodec.BundledAiSkill> entries = BuiltinAiSkillCatalog.load().entries();
        assertThat(entries).hasSize(EXPECTED_SKILL_COUNT);
    }
}
