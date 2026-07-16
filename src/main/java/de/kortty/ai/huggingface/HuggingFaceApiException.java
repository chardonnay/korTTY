package de.kortty.ai.huggingface;

import java.io.IOException;

/** HTTP/API failure returned by the Hugging Face Hub. Tokens and response headers are omitted. */
public final class HuggingFaceApiException extends IOException {

    private final int statusCode;

    public HuggingFaceApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
