package de.kortty.ai.catalog;

import java.io.IOException;
import java.util.Arrays;

/** Fetches the exact signed JSON bytes and their detached signature from one catalog channel. */
@FunctionalInterface
public interface AiCatalogSource {

    SignedPayload fetch() throws IOException, InterruptedException;

    record SignedPayload(byte[] catalogBytes, String detachedSignature) {
        public SignedPayload {
            if (catalogBytes == null || catalogBytes.length == 0) {
                throw new IllegalArgumentException("Signed AI catalog payload is empty.");
            }
            catalogBytes = Arrays.copyOf(catalogBytes, catalogBytes.length);
            detachedSignature = detachedSignature != null ? detachedSignature.trim() : "";
            if (detachedSignature.isEmpty()) {
                throw new IllegalArgumentException("Signed AI catalog has no detached signature.");
            }
        }

        @Override
        public byte[] catalogBytes() {
            return Arrays.copyOf(catalogBytes, catalogBytes.length);
        }
    }
}
