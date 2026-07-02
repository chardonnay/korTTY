package de.kortty.core;

import org.testng.annotations.Test;

import java.util.Base64;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiRasterImageSupportTest {

    // 1x1 transparent PNG.
    private static final String TINY_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
    private static final String TINY_PNG_URI = "data:image/png;base64," + TINY_PNG_BASE64;

    @Test
    void detectsBareDataUriCodeBlocks() {
        assertThat(AiRasterImageSupport.isImageDataUri(TINY_PNG_URI)).isTrue();
        assertThat(AiRasterImageSupport.isImageDataUri("  " + TINY_PNG_URI + "\n")).isTrue();
        assertThat(AiRasterImageSupport.isImageDataUri("data:image/webp;base64,AAAA")).isFalse();
        assertThat(AiRasterImageSupport.isImageDataUri("echo " + TINY_PNG_URI)).isFalse();
        assertThat(AiRasterImageSupport.isImageDataUri(null)).isFalse();
    }

    @Test
    void decodesValidPayloadAndRejectsGarbage() {
        byte[] decoded = AiRasterImageSupport.decodeImageDataUri(TINY_PNG_URI);
        assertThat(decoded).isEqualTo(Base64.getDecoder().decode(TINY_PNG_BASE64));

        assertThat(AiRasterImageSupport.decodeImageDataUri("data:image/png;base64,@@not-base64@@")).isNull();
        assertThat(AiRasterImageSupport.decodeImageDataUri(null)).isNull();
    }

    @Test
    void decodeToleratesWrappedBase64Lines() {
        String wrapped = "data:image/png;base64," + TINY_PNG_BASE64.substring(0, 20) + "\n"
            + TINY_PNG_BASE64.substring(20);
        assertThat(AiRasterImageSupport.decodeImageDataUri(wrapped))
            .isEqualTo(Base64.getDecoder().decode(TINY_PNG_BASE64));
    }

    @Test
    void splitsMarkdownImageOutOfSurroundingText() {
        String text = "Here is the chart:\n![chart](" + TINY_PNG_URI + ")\nDone.";

        List<AiRasterImageSupport.Segment> segments = AiRasterImageSupport.splitTextWithImages(text);

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).text()).isEqualTo("Here is the chart:");
        assertThat(segments.get(1).imageBytes()).isNotNull();
        assertThat(segments.get(2).text()).isEqualTo("Done.");
    }

    @Test
    void keepsPlainTextIntactWithoutImages() {
        List<AiRasterImageSupport.Segment> segments =
            AiRasterImageSupport.splitTextWithImages("No images here.");

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).isEqualTo("No images here.");
        assertThat(segments.get(0).imageBytes()).isNull();
    }

    @Test
    void acceptsSaneDimensionsAndRejectsDecompressionBombs() {
        byte[] tiny = Base64.getDecoder().decode(TINY_PNG_BASE64);
        assertThat(AiRasterImageSupport.hasSaneDimensions(tiny)).isTrue();

        // A valid PNG header declaring 100000x100000 (10 gigapixels) must be rejected
        // before any decode is attempted.
        assertThat(AiRasterImageSupport.hasSaneDimensions(pngHeaderWithDimensions(100_000, 100_000))).isFalse();

        assertThat(AiRasterImageSupport.hasSaneDimensions(new byte[] {1, 2, 3})).isFalse();
        assertThat(AiRasterImageSupport.hasSaneDimensions(null)).isFalse();
    }

    /** Builds a minimal PNG (signature + IHDR with valid CRC) declaring the given dimensions. */
    private static byte[] pngHeaderWithDimensions(int width, int height) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] ihdrType = {'I', 'H', 'D', 'R'};
        java.nio.ByteBuffer ihdrData = java.nio.ByteBuffer.allocate(13);
        ihdrData.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        out.writeBytes(new byte[] {0, 0, 0, 13});
        out.writeBytes(ihdrType);
        out.writeBytes(ihdrData.array());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(ihdrType);
        crc.update(ihdrData.array());
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }

    @Test
    void leavesUndecodableImageMarkdownInText() {
        String text = "Broken: ![x](data:image/png;base64,@@@) end";

        List<AiRasterImageSupport.Segment> segments = AiRasterImageSupport.splitTextWithImages(text);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).contains("Broken:");
        assertThat(segments.get(0).text()).contains("end");
    }
}
