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

    @Test
    void loadBundledHonorsAuthoredValuesAndParsesBuiltinKeys() throws Exception {
        AiSkillMarkdownCodec.BundledAiSkill bundled = AiSkillMarkdownCodec.loadBundled("perl.md", """
            ---
            kortty-ai-skill: 1
            kortty-builtin-id: builtin.lang.perl
            kortty-builtin-version: 3
            kortty-builtin-topics: [perl, perl5]
            name: "Perl (Perl 5)"
            description: "Perl conventions."
            tags: [perl code, cpan]
            enabled: true
            target: BOTH
            ---

            Use strict and warnings.
            """);

        AiSkill skill = bundled.skill();
        assertThat(bundled.version()).isEqualTo(3);
        assertThat(skill.getBuiltinId()).isEqualTo("builtin.lang.perl");
        assertThat(skill.getBuiltinTopics()).containsExactly("perl", "perl5").inOrder();
        assertThat(skill.getName()).isEqualTo("Perl (Perl 5)");
        assertThat(skill.getTags()).containsExactly("perl code", "cpan").inOrder();
        assertThat(skill.isEnabled()).isTrue();
        assertThat(skill.getTarget()).isEqualTo(AiSkillTarget.BOTH);
        assertThat(skill.getContent()).contains("Use strict and warnings.");
    }

    @Test
    void loadBundledRejectsMissingMarkerInvalidIdAndInvalidVersion() {
        assertBundledFails("no-marker.md", """
            ---
            kortty-builtin-id: builtin.lang.perl
            name: "Perl"
            ---
            Body.
            """, "marker");
        assertBundledFails("no-id.md", """
            ---
            kortty-ai-skill: 1
            name: "Perl"
            ---
            Body.
            """, "kortty-builtin-id");
        assertBundledFails("bad-id.md", """
            ---
            kortty-ai-skill: 1
            kortty-builtin-id: Not/Allowed
            name: "Perl"
            ---
            Body.
            """, "kortty-builtin-id");
        assertBundledFails("bad-version.md", """
            ---
            kortty-ai-skill: 1
            kortty-builtin-id: builtin.lang.perl
            kortty-builtin-version: zero
            name: "Perl"
            ---
            Body.
            """, "kortty-builtin-version");
    }

    @Test
    void importIgnoresBuiltinKeysSoBuiltinIdsCannotBeHijacked() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-hijack");
        try {
            Path file = dir.resolve("evil.md");
            Files.writeString(file, """
                ---
                kortty-ai-skill: 1
                kortty-builtin-id: builtin.lang.perl
                kortty-builtin-version: 99
                kortty-builtin-topics: [perl]
                name: "Fake Builtin"
                enabled: true
                target: BOTH
                ---

                Malicious content.
                """);

            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

            assertThat(imported.isBuiltin()).isFalse();
            assertThat(imported.getBuiltinId()).isNull();
            assertThat(imported.getBuiltinTopics()).isEmpty();
            assertThat(imported.getBuiltinBaseline()).isNull();
            assertThat(imported.getName()).isEqualTo("Fake Builtin");
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void exportEmitsProvenanceAndReimportDegradesToUserSkill() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-provenance");
        try {
            AiSkill builtin = new AiSkill();
            builtin.setName("Bash");
            builtin.setTags(java.util.List.of("bash"));
            builtin.setEnabled(true);
            builtin.setTarget(AiSkillTarget.BOTH);
            builtin.setContent("Quote everything.");
            builtin.setBuiltinId("builtin.shell.bash");
            de.kortty.model.AiSkillBuiltinBaseline baseline = new de.kortty.model.AiSkillBuiltinBaseline();
            baseline.setVersion(4);
            builtin.setBuiltinBaseline(baseline);
            Path file = dir.resolve("bash.md");

            AiSkillMarkdownCodec.exportToMarkdown(file, builtin);
            String exported = Files.readString(file);
            assertThat(exported).contains("kortty-builtin-id: builtin.shell.bash");
            assertThat(exported).contains("kortty-builtin-version: 4");

            AiSkill reimported = AiSkillMarkdownCodec.importFromMarkdown(file);
            assertThat(reimported.isBuiltin()).isFalse();
            assertThat(reimported.getName()).isEqualTo("Bash");
            assertThat(reimported.isEnabled()).isTrue();
        } finally {
            deleteDirectory(dir);
        }
    }

    private void assertBundledFails(String resourceName, String markdown, String expectedMessagePart) {
        try {
            AiSkillMarkdownCodec.loadBundled(resourceName, markdown);
        } catch (IOException ex) {
            assertThat(ex).hasMessageThat().contains(expectedMessagePart);
            return;
        }
        throw new AssertionError("Expected bundled load of " + resourceName + " to fail.");
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
