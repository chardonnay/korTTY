package de.kortty.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Extracts exactly the plaintext that is chunked and displayed in source previews. */
public class RagTextExtractor {
    public ExtractedText extract(Path path, RagSourceFormatRegistry.Format format) throws IOException, ExtractionException {
        if (format.pdf()) {
            return extractPdf(path);
        }
        return extractUtf8(path);
    }

    private ExtractedText extractUtf8(Path path) throws IOException, ExtractionException {
        byte[] bytes = Files.readAllBytes(path);
        if (containsNul(bytes)) {
            throw new ExtractionException(RagScanPreview.ProblemCode.BINARY_OR_NON_UTF8,
                "File contains binary NUL bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new ExtractionException(RagScanPreview.ProblemCode.BINARY_OR_NON_UTF8,
                "File is not valid UTF-8 text", error);
        }
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        if (containsBinaryControls(text)) {
            throw new ExtractionException(RagScanPreview.ProblemCode.BINARY_OR_NON_UTF8,
                "File contains binary control characters");
        }
        text = normalize(text);
        if (text.isBlank()) {
            throw new ExtractionException(RagScanPreview.ProblemCode.EMPTY_CONTENT, "File contains no text");
        }
        return new ExtractedText(text, 0);
    }

    private ExtractedText extractPdf(Path path) throws IOException, ExtractionException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            // PDFBox can open documents whose user password is empty even though they are still
            // encrypted. v1 intentionally rejects every encrypted PDF instead of silently
            // indexing content governed by an owner-password policy.
            if (document.isEncrypted()) {
                throw new ExtractionException(RagScanPreview.ProblemCode.PROTECTED_PDF,
                    "Encrypted PDF is not supported");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            int nonEmptyPages = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalize(stripper.getText(document));
                if (!pageText.isBlank()) {
                    nonEmptyPages++;
                }
                if (page > 1) {
                    text.append('\f');
                }
                text.append(pageText);
            }
            if (text.toString().replace("\f", "").isBlank()) {
                throw new ExtractionException(RagScanPreview.ProblemCode.PDF_WITHOUT_TEXT,
                    "PDF contains no extractable text; OCR is not supported");
            }
            return new ExtractedText(text.toString(), nonEmptyPages);
        } catch (InvalidPasswordException error) {
            throw new ExtractionException(RagScanPreview.ProblemCode.PROTECTED_PDF,
                "Password-protected PDF is not supported", error);
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBinaryControls(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isISOControl(value)
                && value != '\n' && value != '\r' && value != '\t' && value != '\f' && value != '\b') {
                return true;
            }
        }
        return false;
    }

    public record ExtractedText(String text, int nonEmptyPdfPages) { }

    public static final class ExtractionException extends Exception {
        private final RagScanPreview.ProblemCode code;

        public ExtractionException(RagScanPreview.ProblemCode code, String message) {
            super(message);
            this.code = code;
        }

        public ExtractionException(RagScanPreview.ProblemCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public RagScanPreview.ProblemCode code() {
            return code;
        }
    }
}
