package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaRuntimeIndexVerifierTest {

    @Test
    void verifiesExactBytesBeforeParsingIndex() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] json = indexJson(false).getBytes(StandardCharsets.UTF_8);
        String signature = sign(pair, json);
        LlamaRuntimeIndexVerifier verifier = new LlamaRuntimeIndexVerifier(pair.getPublic());

        LlamaRuntimeIndex index = verifier.verifyAndParse(json, "ed25519:" + signature);

        assertThat(index.packages()).hasSize(1);
        assertThat(index.packages().get(0).backend()).isEqualTo(LlamaBackend.CPU);
        byte[] tampered = indexJson(true).getBytes(StandardCharsets.UTF_8);
        expectThrows(java.io.IOException.class, () -> verifier.verifyAndParse(tampered, signature));
    }

    private static String sign(KeyPair pair, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(bytes);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    static String indexJson(boolean revoked) {
        return """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-07-15T12:00:00Z",
              "revokedRuntimeIds": [],
              "packages": [{
                "runtimeId": "llama-b10025-kortty1",
                "llamaTag": "b10025",
                "commit": "a3e5b96ac5e278c390df429df0b68efcee3ee1b5",
                "apiContractVersion": 1,
                "minimumKorttyVersion": "2.5.2",
                "platform": "linux",
                "architecture": "x86_64",
                "backend": "cpu",
                "size": 123,
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "downloadUrl": "https://downloads.example.test/llama.zip",
                "entrypoint": "bin/llama-server",
                "revoked": %s
              }]
            }
            """.formatted(revoked);
    }
}
