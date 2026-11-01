package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void importStripsCharactersTheSettingsFileCannotStore() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-control-chars");
        try {
            Path file = dir.resolve("bell.md");
            Files.writeString(file, "---\nkortty-ai-skill: 1\nname: Bell\u0007 skill\n"
                + "description: Rings\uFFFF\ntags: bash, \u001b[1m\n---\n\nEcho\u0000 the bell.\n\tIndented.\n");

            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

            assertThat(imported.getName()).isEqualTo("Bell skill");
            assertThat(imported.getDescription()).isEqualTo("Rings");
            assertThat(imported.getTags()).containsExactly("bash", "[1m").inOrder();
            assertThat(imported.getContent()).isEqualTo("Echo the bell.\n\tIndented.\n");
            assertThat(imported.firstUnstorableText()).isNull();

            Path plain = dir.resolve("plain.md");
            Files.writeString(plain, "No front matter\u0008.");
            assertThat(AiSkillMarkdownCodec.importFromMarkdown(plain).getContent()).isEqualTo("No front matter.");
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

    @Test
    void learnedRulesAndKortTyProvenanceRoundTripWithoutChangingTheContent() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-learned");
        try {
            AiSkill skill = new AiSkill();
            skill.setName("Perl (korTTY)");
            skill.setTags(java.util.List.of("perl"));
            skill.setEnabled(true);
            skill.setTarget(AiSkillTarget.BOTH);
            skill.setContent("Header line.\n");
            skill.setOrigin(de.kortty.model.AiSkillOrigin.KORTTY);
            skill.setCreatedAt("2026-11-01");
            skill.getLearnedRules().add(de.kortty.model.AiSkillLearnedRule.create(
                "Use Moose for classes.", de.kortty.model.AiSkillLearnedRule.SOURCE_CHAT));
            skill.getLearnedRules().add(de.kortty.model.AiSkillLearnedRule.create(
                "Avoid tabs\nin indentation.", de.kortty.model.AiSkillLearnedRule.SOURCE_AGENT));
            Path file = dir.resolve("perl-korTTY.md");

            AiSkillMarkdownCodec.exportToMarkdown(file, skill);
            String exported = Files.readString(file);
            assertThat(exported).contains("kortty-origin: KORTTY");
            assertThat(exported).contains("kortty-created-at: 2026-11-01");
            assertThat(exported).contains("<!-- kortty:learned-rules -->\n- Use Moose for classes.\n- Avoid tabs in indentation.\n<!-- /kortty:learned-rules -->");

            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);
            assertThat(imported.getContent()).isEqualTo("Header line.\n");
            assertThat(imported.getOrigin()).isEqualTo(de.kortty.model.AiSkillOrigin.KORTTY);
            assertThat(imported.getCreatedAt()).isEqualTo("2026-11-01");
            assertThat(imported.isOriginAcknowledged()).isTrue();
            assertThat(imported.getLearnedRules()).hasSize(2);
            assertThat(imported.getLearnedRules().get(0).getText()).isEqualTo("Use Moose for classes.");
            assertThat(imported.getLearnedRules().get(1).getText()).isEqualTo("Avoid tabs in indentation.");
            assertThat(imported.getLearnedRules().get(0).isAcknowledged()).isTrue();
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void contentWithoutRulesIsExportedAndImportedExactly() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-exact");
        try {
            for (String content : List.of("No trailing newline", "Ends with one\n", "Ends with two\n\n", "")) {
                AiSkill skill = new AiSkill();
                skill.setName("Exact");
                skill.setContent(content);
                Path file = dir.resolve("exact.md");

                AiSkillMarkdownCodec.exportToMarkdown(file, skill);

                assertThat(Files.readString(file)).doesNotContain("kortty:learned-rules");
                assertThat(AiSkillMarkdownCodec.importFromMarkdown(file).getContent()).isEqualTo(content);
            }
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void rulesComeBackExactlyWhenTheContentEndsWithANewline() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-rules-newline");
        try {
            for (String content : List.of("Body without newline", "Body with newline\n", "")) {
                AiSkill skill = new AiSkill();
                skill.setName("Rules");
                skill.setContent(content);
                skill.getLearnedRules().add(de.kortty.model.AiSkillLearnedRule.create(
                    "One rule.", de.kortty.model.AiSkillLearnedRule.SOURCE_CHAT));
                Path file = dir.resolve("rules.md");

                AiSkillMarkdownCodec.exportToMarkdown(file, skill);
                AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

                assertThat(imported.getContent()).isEqualTo(content);
                assertThat(imported.getLearnedRules()).hasSize(1);
            }
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void plainMarkdownWithoutProvenanceImportsNoOriginAndNoRules() throws Exception {
        Path dir = Files.createTempDirectory("kortty-ai-skill-plain-rules");
        try {
            Path file = dir.resolve("plain.md");
            Files.writeString(file, """
                ---
                kortty-ai-skill: 1
                name: "Plain"
                enabled: true
                target: BOTH
                ---

                Text with a <!-- kortty:learned-rules --> marker but no end.
                """);

            AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file);

            assertThat(imported.getOrigin()).isNull();
            assertThat(imported.getLearnedRules()).isEmpty();
            assertThat(imported.getContent()).contains("marker but no end.");
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
