package de.kortty.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class RagSourceScannerTest {
    @Test
    void reportsUnreadableFilesWithoutAbortingOtherDocuments() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-permissions");
        Path unreadable = Files.writeString(root.resolve("private.md"), "private");
        try {
            try {
                Files.setPosixFilePermissions(unreadable, java.util.Set.of());
            } catch (UnsupportedOperationException error) {
                throw new SkipException("POSIX permissions unavailable", error);
            }
            if (Files.isReadable(unreadable)) {
                throw new SkipException("Current test user can bypass file permissions");
            }
            Files.writeString(root.resolve("public.md"), "public");

            RagScanPreview preview = new RagSourceScanner().preview(
                RagSource.directory(root), CancellationToken.NONE);

            assertThat(preview.documents().stream().map(RagDocument::relativePath).toList())
                .containsExactly("public.md");
            assertThat(preview.problems().stream().map(RagScanPreview.Problem::code).toList())
                .contains(RagScanPreview.ProblemCode.NOT_READABLE);
        } finally {
            try {
                Files.setPosixFilePermissions(unreadable, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (Exception ignored) { }
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void recursivelyAppliesAllowlistFiltersAndStandardExcludes() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-scan");
        try {
            Files.writeString(root.resolve("root.md"), "root document");
            Files.writeString(root.resolve("ignored.docx"), "not really docx");
            Path nested = Files.createDirectories(root.resolve("nested"));
            Files.writeString(nested.resolve("code.java"), "class Example {}");
            Files.writeString(nested.resolve("skip.java"), "class Skip {}");
            Path modules = Files.createDirectories(root.resolve("node_modules/pkg"));
            Files.writeString(modules.resolve("dependency.md"), "must not be indexed");
            RagSource source = new RagSource("source", "Source", root, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of("**/*.md", "**/*.java"), List.of("**/skip.java"));

            RagScanPreview preview = new RagSourceScanner().preview(source, CancellationToken.NONE);

            assertThat(preview.documents().stream().map(RagDocument::relativePath).toList())
                .containsExactly("root.md", "nested/code.java");
            assertThat(preview.problems().stream().map(RagScanPreview.Problem::code).toList())
                .contains(RagScanPreview.ProblemCode.EXCLUDED);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void rejectsBinaryInvalidUtf8EmptyAndOversizedText() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-content");
        try {
            Files.write(root.resolve("binary.txt"), new byte[] {'a', 0, 'b'});
            Files.write(root.resolve("invalid.md"), new byte[] {(byte) 0xc3, 0x28});
            Files.writeString(root.resolve("empty.txt"), "  \n\t");
            Files.writeString(root.resolve("large.txt"), "1234567890");
            RagSourceScanner scanner = new RagSourceScanner(new RagSourceFormatRegistry(),
                new RagTextExtractor(), 100, 100, 100);

            RagScanPreview preview = scanner.preview(RagSource.directory(root), CancellationToken.NONE);

            assertThat(preview.documents()).hasSize(1);
            assertThat(preview.problems().stream().map(RagScanPreview.Problem::code).toList())
                .containsAtLeast(RagScanPreview.ProblemCode.BINARY_OR_NON_UTF8,
                    RagScanPreview.ProblemCode.EMPTY_CONTENT);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void stopsWithExplicitProblemWhenAggregateLimitIsExceeded() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-limit");
        try {
            Files.writeString(root.resolve("large.txt"), "1234567890");
            RagSourceScanner scanner = new RagSourceScanner(new RagSourceFormatRegistry(),
                new RagTextExtractor(), 100, 5, 100);
            RagScanPreview preview = scanner.preview(RagSource.directory(root), CancellationToken.NONE);
            assertThat(preview.documents()).isEmpty();
            assertThat(preview.problems().stream().map(RagScanPreview.Problem::code).toList())
                .contains(RagScanPreview.ProblemCode.LIMIT_EXCEEDED);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void extractsPdfTextByPageAndRejectsBlankAndProtectedPdf() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-pdf");
        try {
            createTextPdf(root.resolve("text.pdf"), "Hello PDF");
            createBlankPdf(root.resolve("blank.pdf"));
            createProtectedPdf(root.resolve("protected.pdf"));
            createOwnerProtectedPdf(root.resolve("owner-protected.pdf"), "Must not be indexed");

            RagScanPreview preview = new RagSourceScanner().preview(RagSource.directory(root), CancellationToken.NONE);

            assertThat(preview.documents()).hasSize(1);
            assertThat(preview.documents().get(0).text()).contains("Hello PDF");
            assertThat(preview.problems().stream().map(RagScanPreview.Problem::code).toList())
                .containsAtLeast(RagScanPreview.ProblemCode.PDF_WITHOUT_TEXT,
                    RagScanPreview.ProblemCode.PROTECTED_PDF);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void skipsSymbolicLinksWithoutFollowingThem() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-link");
        try {
            Path outside = Files.createTempFile("kortty-rag-outside", ".txt");
            Files.writeString(outside, "secret");
            try {
                Files.createSymbolicLink(root.resolve("linked.txt"), outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
                throw new SkipException("Symbolic links unavailable", error);
            }
            RagScanPreview preview = new RagSourceScanner().preview(RagSource.directory(root), CancellationToken.NONE);
            assertThat(preview.documents()).isEmpty();
            assertThat(preview.problems().stream().map(RagScanPreview.Problem::code).toList())
                .contains(RagScanPreview.ProblemCode.SYMBOLIC_LINK);
            Files.deleteIfExists(outside);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    @Test
    void detectsDuplicateNestedAndContainingSources() throws Exception {
        Path root = Files.createTempDirectory("kortty-rag-overlap");
        try {
            Path file = Files.writeString(root.resolve("one.md"), "one");
            RagSource existing = new RagSource("existing", "Existing", root, RagSourceType.DIRECTORY,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            RagSource candidate = new RagSource("candidate", "Candidate", file, RagSourceType.FILE,
                RagSyncMode.AUTOMATIC, true, List.of(), List.of());
            assertThat(new RagSourceScanner().findOverlap(candidate, List.of(existing))).hasValue(existing);
        } finally {
            RagTestSupport.deleteTree(root);
        }
    }

    private static void createTextPdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
    }

    private static void createBlankPdf(Path path) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
    }

    private static void createProtectedPdf(Path path) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret",
                new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(path.toFile());
        }
    }

    private static void createOwnerProtectedPdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                "owner-secret", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(path.toFile());
        }
    }
}
