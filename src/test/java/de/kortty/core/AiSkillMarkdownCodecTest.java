package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;


class AiSkillMarkdownCodecTest {

    @Test
    void markdownRoundTripPreservesFrontMatterAndContent() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-markdown");
        try {
            AiSkill skill = new AiSkill();
            skill.setName("Shell Style");
            skill.setDescription("Shell guidance.");
            skill.setTags(java.util.List.of("shell", "bash"));
            skill.setEnabled(true);
            skill.setTarget(AiSkillTarget.BOTH);
            skill.setContent("Use short shell examples.\n");
            Path file = dir.resolve("shell-style.md");

            AiSkillMarkdownCodec.exportToMarkdown(file, skill);
            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

            assertThat(imported.getName()).isEqualTo("Shell Style");
            assertThat(imported.getDescription()).isEqualTo("Shell guidance.");
            assertThat(imported.getTags()).containsExactly("shell", "bash").inOrder();
            assertThat(imported.isEnabled()).isTrue();
            assertThat(imported.getTarget()).isEqualTo(AiSkillTarget.BOTH);
            assertThat(imported.getContent()).isEqualTo("Use short shell examples.\n");
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void plainMarkdownImportsDisabledWithFileNameAndBothTarget() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-plain-markdown");
        try {
            Path file = dir.resolve("review-style.md");
            Files.writeString(file, "Review carefully.");

            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

            assertThat(imported.getName()).isEqualTo("review-style");
            assertThat(imported.isEnabled()).isFalse();
            assertThat(imported.getTarget()).isEqualTo(AiSkillTarget.BOTH);
            assertThat(imported.getContent()).isEqualTo("Review carefully.");
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void importRejectsInvalidEnabledFrontMatter() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-invalid-enabled");
        try {
            Path file = dir.resolve("invalid.md");
            Files.writeString(file, """
                ---
                kortty-ai-skill: 1
                name: "Invalid"
                enabled: maybe
                target: BOTH
                ---

                Invalid enabled value.
                """);

            try {
                AiSkillMarkdownCodec.importFromMarkdown(file);
            } catch (IOException ex) {
                assertThat(ex).hasMessageThat().contains("Invalid AI skill enabled value");
                return;
            }
            throw new AssertionError("Expected invalid enabled value to fail.");
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void importsExternalSkillMarkdownDisabledWithDescriptionAndTags() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-external-markdown");
        try {
            Path file = dir.resolve("SKILL.md");
            Files.writeString(file, """
                ---
                name: linux-sysadmin
                description: >
                  Provides Linux system administration guidance for Fedora,
                  dnf, systemctl, networking, and troubleshooting.
                tags: [linux, sysadmin, fedora, dnf]
                ---

                # Linux System Administration Skill

                Use safe commands.
                """);

            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

            assertThat(imported.getName()).isEqualTo("linux-sysadmin");
            assertThat(imported.getDescription()).contains("Linux system administration guidance");
            assertThat(imported.getTags()).containsExactly("linux", "sysadmin", "fedora", "dnf").inOrder();
            assertThat(imported.isEnabled()).isFalse();
            assertThat(imported.getTarget()).isEqualTo(AiSkillTarget.BOTH);
            assertThat(imported.getContent()).contains("# Linux System Administration Skill");
        } finally {
            deleteDirectory(dir);
        }
    }

    private void deleteDirectory(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(dir);
    }
}
