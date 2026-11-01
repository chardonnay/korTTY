package de.kortty.core;

import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/** The startup scan and the transient registry: findings are flags only, and nothing is changed. */
class AiSecretScanTest {

    private static final String SECRET_TEXT = "Das Passwort ist Sonnenblume42\n";

    private static AiSkill skill(String id, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(id);
        skill.setContent(content);
        return skill;
    }

    @Test
    void theScanFlagsSkillsAndMemoryFilesThatHoldASecretAndChangesNothing() throws IOException {
        Path dir = Files.createTempDirectory("kortty-secret-scan");
        MemoryStore store = new MemoryStore(dir.resolve("memory"));
        Path global = store.fileOf(MemoryScope.global());
        Path connection = store.fileOf(MemoryScope.connection("conn-b"));
        Files.createDirectories(connection.getParent());
        Files.createDirectories(global.getParent());
        Files.writeString(global, SECRET_TEXT);
        Files.writeString(connection, "Use the staging host.\n");
        AiSkill leaking = skill("leaking", SECRET_TEXT);
        AiSkill clean = skill("clean", "Keep the scripts short.");
        AiSecretScanRegistry registry = new AiSecretScanRegistry();

        AiSecretStartupScan.run(List.of(leaking, clean), List.of("conn-b"), store, registry);

        assertThat(registry.skillIds()).containsExactly("leaking");
        assertThat(registry.hasSkillFinding("clean")).isFalse();
        assertThat(registry.memoryScopeKeys()).containsExactly("global");
        assertThat(registry.hasMemoryFinding("connection-conn-b")).isFalse();
        assertThat(Files.readString(global)).isEqualTo(SECRET_TEXT);
        assertThat(leaking.getContent()).isEqualTo(SECRET_TEXT);
    }

    @Test
    void aSkillIsUnflaggedOnceItsTextIsCleanedAndSaved() {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        AiSkill leaking = skill("leaking", SECRET_TEXT);
        registry.refreshSkills(List.of(leaking));
        assertThat(registry.hasSkillFinding("leaking")).isTrue();

        leaking.setContent("Keep the scripts short.");
        registry.refreshSkills(List.of(leaking));

        assertThat(registry.hasSkillFinding("leaking")).isFalse();
    }

    @Test
    void replacingTheFindingsDropsTheOldOnes() {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        registry.replaceSkills(Set.of("a"));
        registry.replaceMemoryScopes(Set.of("global"));

        registry.replaceSkills(List.of("b"));

        assertThat(registry.hasSkillFinding("a")).isFalse();
        assertThat(registry.hasSkillFinding("b")).isTrue();
        assertThat(registry.hasSkillFinding(null)).isFalse();
        assertThat(registry.hasMemoryFinding("global")).isTrue();
    }

    @Test
    void theScanToleratesMissingInputs() {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();

        AiSecretStartupScan.run(null, null, null, registry);

        assertThat(registry.skillIds()).isEmpty();
        assertThat(registry.memoryScopeKeys()).isEmpty();
    }
}
