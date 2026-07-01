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
    void leavesUndecodableImageMarkdownInText() {
        String text = "Broken: ![x](data:image/png;base64,@@@) end";

        List<AiRasterImageSupport.Segment> segments = AiRasterImageSupport.splitTextWithImages(text);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).contains("Broken:");
        assertThat(segments.get(0).text()).contains("end");
    }
}
