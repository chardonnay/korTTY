package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SshTtyConnectorAgentOscTest {

    @Test
    void extractsWorkingDirectoryFromAgentOscPayload() {
        String cwd = Base64.getEncoder().encodeToString("/home/daniel/Dokumente".getBytes(StandardCharsets.UTF_8));
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertEquals(
            "/home/daniel/Dokumente",
            SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + cwd + ";" + prompt));
    }

    @Test
    void ignoresOldAgentOscPayloadWithoutWorkingDirectory() {
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertNull(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + prompt));
    }

    @Test
    void ignoresRelativeWorkingDirectoryPayload() {
        String cwd = Base64.getEncoder().encodeToString("Dokumente".getBytes(StandardCharsets.UTF_8));
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertNull(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + cwd + ";" + prompt));
    }
}
