package de.kortty.core;

import java.util.Base64;

/**
 * One image attached to a vision prompt. {@code bytes} is the encoded image file (PNG, JPEG, …),
 * not raw pixels; {@code mediaType} is the MIME type the transport advertises. Note the array
 * component: equality is identity-based, so instances must not be used as map keys.
 */
public record AiImageInput(byte[] bytes, String mediaType) {

    public AiImageInput {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Image bytes must not be empty");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("Image media type must not be blank");
        }
    }

    public static AiImageInput png(byte[] bytes) {
        return new AiImageInput(bytes, "image/png");
    }

    /** Base64 of the encoded file, computed per call — callers embed it exactly once. */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** OpenAI-style {@code data:} URI carrying the image inline. */
    public String toDataUri() {
        return "data:" + mediaType + ";base64," + toBase64();
    }
}
