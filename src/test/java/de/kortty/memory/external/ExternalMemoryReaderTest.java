package de.kortty.memory.external;

import java.nio.file.attribute.FileTime;

import de.kortty.core.AiSecretGuard;
import de.kortty.memory.MemoryFormat;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class ExternalMemoryReaderTest {

    private Path directory;

    @BeforeMethod
    void createDirectory() throws IOException {
        directory = ExternalMemoryFixtures.tempDirectory("kortty-external-reader-");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDirectory() throws IOException {
        ExternalMemoryFixtures.deleteTree(directory);
    }

    @Test
    void invisibleCharactersAreRemovedAndCounted() {
        ExternalMemoryReader.Sanitized sanitized = ExternalMemoryReader.sanitize(
            "a​b⁦c\u0007d\tkeep\nline\r\n﻿end‮");

        assertThat(sanitized.text()).isEqualTo("abcd\tkeep\nline\r\nend");
        assertThat(sanitized.removed()).isEqualTo(5);
    }

    @Test
    void tagCharactersVariationSelectorsAndSeparatorsAreRemoved() {
        String hidden = new String(Character.toChars(0xE0041)) + "\uFE0F" + "\u061C" + "\u180E" + "\u200E" + "\u2028";

        ExternalMemoryReader.Sanitized sanitized = ExternalMemoryReader.sanitize("a" + hidden + "b");

        assertThat(sanitized.text()).isEqualTo("ab");
        assertThat(sanitized.removed()).isEqualTo(6);
    }

    @Test
    void ordinaryTextAndWhitespaceAreKept() {
        ExternalMemoryReader.Sanitized sanitized = ExternalMemoryReader.sanitize("Grüße, 日本語 \t\n\r\n");

        assertThat(sanitized.text()).isEqualTo("Grüße, 日本語 \t\n\r\n");
        assertThat(sanitized.removed()).isEqualTo(0);
    }

    @Test
    void theFrontMatterOfAMdcRuleIsRemoved() {
        String body = "---\ndescription: Java style\nglobs: *.java\n---\nUse four spaces.\n";

        ExternalMemoryReader.Document rule = ExternalMemoryReader.parse(bytes(body), "java.mdc");
        ExternalMemoryReader.Document note = ExternalMemoryReader.parse(bytes(body), "java.md");

        assertThat(rule.promptText()).isEqualTo("Use four spaces.\n");
        assertThat(note.promptText()).startsWith("---\ndescription");
    }

    @Test
    void anUnclosedFrontMatterIsKept() {
        String body = "---\nno closing fence\nText.\n";

        assertThat(ExternalMemoryReader.parse(bytes(body), "rule.mdc").promptText()).isEqualTo(body);
    }

    @Test
    void theTextSentIsCutToSixteenKilobytesAtAParagraphBoundary() {
        StringBuilder text = new StringBuilder();
        for (int paragraph = 0; paragraph < 3000; paragraph++) {
            text.append("paragraph ").append(paragraph).append(" text\n\n");
        }

        ExternalMemoryReader.Document document = ExternalMemoryReader.parse(bytes(text.toString()), "big.md");

        assertThat(document.truncated()).isTrue();
        assertThat(document.promptText().getBytes(StandardCharsets.UTF_8).length)
            .isAtMost(ExternalMemoryReader.MAX_PROMPT_BYTES);
        assertThat(document.promptText()).startsWith("paragraph 0 text");
        assertThat(document.promptText()).endsWith(" text");
        assertThat(document.sha256()).isEqualTo(MemoryFormat.sha256(bytes(text.toString())));
    }

    @Test
    void aShortFileIsNotTruncated() {
        ExternalMemoryReader.Document document = ExternalMemoryReader.parse(bytes("Short.\n"), "short.md");

        assertThat(document.truncated()).isFalse();
        assertThat(document.promptText()).isEqualTo("Short.\n");
    }

    @Test
    void aPossibleSecretIsReportedWithItsLineAndCategory() {
        ExternalMemoryReader.Document document = ExternalMemoryReader.parse(
            bytes("Notes\npassword = hunter2hunter2\n"), "notes.md");

        assertThat(document.secret()).isPresent();
        AiSecretGuard.Finding finding = document.secret().orElseThrow();
        assertThat(finding.line()).isEqualTo(2);
        assertThat(finding.maskedExcerpt()).doesNotContain("hunter2hunter2");
    }

    @Test
    void aFileWithNoSecretHasNoFinding() {
        assertThat(ExternalMemoryReader.parse(bytes("Use tabs in Perl.\n"), "notes.md").secret()).isEmpty();
    }

    @Test
    void aBinaryFileIsNotReadable() {
        byte[] bytes = {'a', 0, 'b'};

        ExternalMemoryReader.Document document = ExternalMemoryReader.parse(bytes, "data.md");

        assertThat(document.binary()).isTrue();
        assertThat(document.readable()).isFalse();
        assertThat(document.promptText()).isEmpty();
    }

    @Test
    void aFileOverTheSizeLimitHasNoDigest() throws IOException {
        Path file = directory.resolve("huge.md");
        Files.write(file, new byte[(int) ExternalMemoryReader.MAX_FILE_BYTES + 1]);

        ExternalMemoryReader.Document document = new ExternalMemoryReader().read(file);

        assertThat(document.tooLarge()).isTrue();
        assertThat(document.sha256()).isNull();
        assertThat(document.readable()).isFalse();
    }

    @Test
    void anUnchangedFileIsReadFromTheCache() throws IOException {
        Path file = ExternalMemoryFixtures.write(directory.resolve("cached.md"), "Stable text.\n");
        ExternalMemoryReader reader = new ExternalMemoryReader();

        ExternalMemoryReader.Document first = reader.read(file);

        assertThat(reader.read(file)).isSameInstanceAs(first);
        assertThat(first.promptText()).isEqualTo("Stable text.\n");
    }

    @Test
    void aChangedFileIsReadAgain() throws IOException {
        Path file = ExternalMemoryFixtures.write(directory.resolve("changed.md"), "First.\n");
        ExternalMemoryReader reader = new ExternalMemoryReader();
        reader.read(file);

        ExternalMemoryFixtures.write(file, "Second, and longer.\n");

        assertThat(reader.read(file).promptText()).isEqualTo("Second, and longer.\n");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }


    @Test
    void aRewriteThatKeepsTheSizeAndTheModificationTimeIsReadAgain() throws IOException {
        Path file = directory.resolve("AGENTS.md");
        Files.writeString(file, "Rule: answer briefly\n");
        ExternalMemoryReader reader = new ExternalMemoryReader();
        ExternalMemoryReader.Document first = reader.read(file);
        FileTime modified = Files.getLastModifiedTime(file);

        Files.writeString(file, "Rule: answer quietly\n");
        Files.setLastModifiedTime(file, modified);
        ExternalMemoryReader.Document second = reader.read(file);

        assertThat(second.sha256()).isNotEqualTo(first.sha256());
        assertThat(second.sha256()).isEqualTo(MemoryFormat.sha256(Files.readAllBytes(file)));
        assertThat(second.promptText()).contains("quietly");
    }
}
