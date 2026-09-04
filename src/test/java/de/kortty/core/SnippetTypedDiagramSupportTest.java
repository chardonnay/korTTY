package de.kortty.core;

import de.kortty.model.SnippetDiagramType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetTypedDiagramSupportTest {

    private static final String VALID_SEQUENCE = """
        sequenceDiagram
        participant script as Backup script
        participant server as Remote server
        script ->> server: Upload archive
        alt upload succeeds
        server -->> script: Confirmation
        else upload fails
        server -->> script: Error code
        end
        note over script: Retries three times
        """;

    private static final String VALID_STATE = """
        stateDiagram-v2
        state "Waiting for input" as idle
        [*] --> idle
        idle --> connecting : start requested
        connecting --> connected : handshake done
        connecting --> failed : timeout
        failed --> connecting : retry
        connected --> [*]
        connected : Session is active
        """;

    private static final String VALID_CLASS = """
        classDiagram
        class BackupJob {
        +String name
        -List~Path~ sources
        +run() int
        }
        class Archive
        BackupJob --> Archive : creates
        Archive <|-- EncryptedArchive
        """;

    private static final String VALID_ER = """
        erDiagram
        CUSTOMER ||--o{ ORDER : places
        ORDER ||--|{ ORDER_ITEM : contains
        CUSTOMER {
        int id PK
        varchar(80) name
        varchar(120) email UK "unique login"
        }
        """;

    @DataProvider(name = "validDiagrams")
    Object[][] validDiagrams() {
        return new Object[][] {
            {SnippetDiagramType.SEQUENCE, VALID_SEQUENCE},
            {SnippetDiagramType.STATE, VALID_STATE},
            {SnippetDiagramType.CLASS, VALID_CLASS},
            {SnippetDiagramType.ER, VALID_ER},
        };
    }

    @Test(dataProvider = "validDiagrams")
    void acceptsMinimalValidDiagramOfEachFamily(SnippetDiagramType type, String source) {
        assertThat(SnippetTypedDiagramSupport.validate(type, source).valid()).isTrue();
        assertThat(SnippetTypedDiagramSupport.validateGenerated(type, source).valid()).isTrue();
    }

    @Test(dataProvider = "validDiagrams")
    void rejectsEveryFamilyUnderTheWrongDeclaredType(SnippetDiagramType type, String source) {
        for (SnippetDiagramType other : SnippetDiagramType.values()) {
            if (other == type) {
                continue;
            }
            assertThat(SnippetTypedDiagramSupport.validate(other, source).valid()).isFalse();
        }
    }

    @Test(dataProvider = "validDiagrams")
    void rejectsForbiddenSecuritySyntaxInEveryFamily(SnippetDiagramType type, String source) {
        assertThat(SnippetTypedDiagramSupport.validate(type, "---\ntitle: x\n---\n" + source).valid())
            .isFalse();
        assertThat(SnippetTypedDiagramSupport.validate(type, "%%{init: {}}%%\n" + source).valid())
            .isFalse();
        assertThat(SnippetTypedDiagramSupport.validate(
            type, source + "\nclick something https://example.com").valid()).isFalse();
        assertThat(SnippetTypedDiagramSupport.validate(
            type, source.replaceFirst("\\z", "") + "\n<script>alert(1)</script>").valid()).isFalse();
        assertThat(SnippetTypedDiagramSupport.validate(
            type, source + "\nstyle x fill:#f00").valid()).isFalse();
    }

    @Test
    void diagramSourceIsRecoveredFromAnAnswerWhoseJsonEscapingBroke() {
        // The grammar demands quoted labels, so every quote must survive as \" inside the JSON
        // string. A model that forgets produces an object that parses in no mode at all — the
        // diagram itself is still perfectly good.
        String brokenJson = """
            {
              "title": "Ablauf",
              "mermaid": "flowchart TD
                start_1(["Start"])
                work_1["Read config"]
                stop_1(["Stop"])
                start_1 --> work_1
                work_1 --> stop_1
                class start_1,stop_1 setup
                class work_1 work
            ",
              "codeReferences": []
            }
            """;

        String recovered = SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.LOGICAL_STRUCTURE, brokenJson);

        assertThat(recovered).startsWith("flowchart TD");
        assertThat(recovered).contains("work_1[\"Read config\"]");
        assertThat(recovered).doesNotContain("codeReferences");
        assertThat(SnippetDiagramSupport.validateMermaid(recovered).valid()).isTrue();
    }

    @Test
    void diagramSourceIsRecoveredFromEscapedJsonAndFromAFencedBlock() {
        String escaped = "{\"mermaid\": \"flowchart TD\\n    start_1([\\\"Start\\\"])\\n    "
            + "work_1[\\\"Run\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    "
            + "work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work\"}";
        String fenced = """
            Here is the diagram:
            ```mermaid
            sequenceDiagram
            participant script as Script
            participant server as Server
            script ->> server: Upload
            ```
            Let me know if you need changes.
            """;

        String fromEscaped = SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.LOGICAL_STRUCTURE, escaped);
        String fromFence = SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.SEQUENCE, fenced);

        assertThat(SnippetDiagramSupport.validateMermaid(fromEscaped).valid()).isTrue();
        assertThat(fromEscaped).contains("work_1[\"Run\"]");
        assertThat(fromFence).startsWith("sequenceDiagram");
        assertThat(fromFence).contains("script ->> server: Upload");
        assertThat(fromFence).doesNotContain("Let me know");
    }

    @Test
    void recoverySkipsInlineReasoningThatMentionsTheHeader() {
        // MiniMax-M3 thinks inline: the header appears in the reasoning before the diagram.
        String answer = """
            <think>Let me plan the flowchart TD carefully: start, read config, stop.</think>
            ```mermaid
            flowchart TD
                start_1(["Start"])
                work_1["Read config"]
                stop_1(["Stop"])
                start_1 --> work_1
                work_1 --> stop_1
                class start_1,stop_1 setup
                class work_1 work
            ```
            """;

        String recovered = SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.LOGICAL_STRUCTURE, answer);

        assertThat(recovered).startsWith("flowchart TD");
        assertThat(recovered).doesNotContain("carefully");
        assertThat(SnippetDiagramSupport.validateMermaid(recovered).valid()).isTrue();
    }

    @Test
    void recoveryReturnsNothingForAnAnswerWithoutADiagram() {
        assertThat(SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.LOGICAL_STRUCTURE, "I cannot draw this script.")).isEmpty();
        assertThat(SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.LOGICAL_STRUCTURE, null)).isEmpty();
        // A different family's diagram is not silently accepted for the requested one.
        assertThat(SnippetTypedDiagramSupport.extractDiagramSource(
            SnippetDiagramType.STATE, "flowchart TD\n    start_1([\"Start\"])")).isEmpty();
    }

    @Test
    void logicalStructureDelegatesToTheFlowchartDialect() {
        String flowchart = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid("echo ok", "bash");
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.LOGICAL_STRUCTURE, flowchart).valid())
            .isTrue();
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.LOGICAL_STRUCTURE, VALID_SEQUENCE)
            .message()).contains("flowchart TD");
        assertThat(SnippetTypedDiagramSupport.validate(null, flowchart).valid()).isTrue();
    }

    @Test
    void sequenceRequiresDeclaredParticipantsAndBalancedBlocks() {
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.SEQUENCE, """
            sequenceDiagram
            participant a as Script
            a ->> ghost: Undeclared target
            """).message()).contains("declared participant");
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.SEQUENCE, """
            sequenceDiagram
            participant a as Script
            participant b as Server
            alt success
            a ->> b: Send
            """).message()).contains("closed with 'end'");
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.SEQUENCE, """
            sequenceDiagram
            participant a as Script
            participant b as Server
            a ->> b: Send
            autonumber
            """).message()).contains("Unsupported sequence");
    }

    @Test
    void sequenceGenerationCapsParticipantsAndMessages() {
        StringBuilder manyParticipants = new StringBuilder("sequenceDiagram\n");
        for (int index = 0; index < 13; index++) {
            manyParticipants.append("participant p").append(index).append('\n');
        }
        manyParticipants.append("p0 ->> p1: ping\n");
        assertThat(SnippetTypedDiagramSupport.validate(
            SnippetDiagramType.SEQUENCE, manyParticipants.toString()).valid()).isTrue();
        assertThat(SnippetTypedDiagramSupport.validateGenerated(
            SnippetDiagramType.SEQUENCE, manyParticipants.toString()).message())
            .contains("at most 12 participants");

        StringBuilder manyMessages = new StringBuilder("sequenceDiagram\nparticipant a\nparticipant b\n");
        for (int index = 0; index < 61; index++) {
            manyMessages.append("a ->> b: message ").append(index).append('\n');
        }
        assertThat(SnippetTypedDiagramSupport.validateGenerated(
            SnippetDiagramType.SEQUENCE, manyMessages.toString()).message()).contains("at most 60 messages");
    }

    @Test
    void stateRequiresInitialTransitionAndStaysFlat() {
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.STATE, """
            stateDiagram-v2
            idle --> running
            """).message()).contains("initial [*]");
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.STATE, """
            stateDiagram-v2
            [*] --> outer
            state outer {
            inner --> done
            }
            """).message()).contains("Unsupported state");
        StringBuilder manyStates = new StringBuilder("stateDiagram-v2\n[*] --> s0\n");
        for (int index = 0; index < 13; index++) {
            manyStates.append("s").append(index).append(" --> s").append(index + 1).append('\n');
        }
        assertThat(SnippetTypedDiagramSupport.validateGenerated(
            SnippetDiagramType.STATE, manyStates.toString()).message()).contains("at most 12 states");
    }

    @Test
    void classRejectsStereotypesAndCapsMembers() {
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.CLASS, """
            classDiagram
            class Job {
            <<abstract>>
            +run() int
            }
            """).valid()).isFalse();
        StringBuilder manyMembers = new StringBuilder("classDiagram\nclass Big {\n");
        for (int index = 0; index < 21; index++) {
            manyMembers.append("+field").append(index).append(" int\n");
        }
        manyMembers.append("}\n");
        assertThat(SnippetTypedDiagramSupport.validate(
            SnippetDiagramType.CLASS, manyMembers.toString()).message()).contains("at most 20 members");
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.CLASS, """
            classDiagram
            class Open {
            +run() int
            """).message()).contains("closed with '}'");
    }

    @Test
    void erRequiresLabeledRelationsAndCapsAttributes() {
        assertThat(SnippetTypedDiagramSupport.validate(SnippetDiagramType.ER, """
            erDiagram
            CUSTOMER ||--o{ ORDER
            """).valid()).isFalse();
        StringBuilder manyAttributes = new StringBuilder("erDiagram\nBIG {\n");
        for (int index = 0; index < 41; index++) {
            manyAttributes.append("int field").append(index).append('\n');
        }
        manyAttributes.append("}\n");
        assertThat(SnippetTypedDiagramSupport.validateGenerated(
            SnippetDiagramType.ER, manyAttributes.toString()).message()).contains("at most 40 attributes");
    }

    @Test
    void declaredElementIdsMatchEachFamily() {
        assertThat(SnippetTypedDiagramSupport.declaredElementIds(SnippetDiagramType.SEQUENCE, VALID_SEQUENCE))
            .containsExactly("script", "server");
        assertThat(SnippetTypedDiagramSupport.declaredElementIds(SnippetDiagramType.STATE, VALID_STATE))
            .containsExactly("idle", "connecting", "connected", "failed");
        assertThat(SnippetTypedDiagramSupport.declaredElementIds(SnippetDiagramType.CLASS, VALID_CLASS))
            .containsExactly("BackupJob", "Archive", "EncryptedArchive");
        assertThat(SnippetTypedDiagramSupport.declaredElementIds(SnippetDiagramType.ER, VALID_ER))
            .containsExactly("CUSTOMER", "ORDER", "ORDER_ITEM");
    }

    @Test
    void relaxedReferenceFilterKeepsOnlyDeclaredElementsWithValidRanges() {
        List<SnippetDiagramSupport.SourceCodeReference> references = List.of(
            new SnippetDiagramSupport.SourceCodeReference("script", "Backup script", 1, 3),
            new SnippetDiagramSupport.SourceCodeReference("script", "Duplicate", 4, 5),
            new SnippetDiagramSupport.SourceCodeReference("ghost", "Unknown element", 1, 1),
            new SnippetDiagramSupport.SourceCodeReference("server", "", 1, 1),
            new SnippetDiagramSupport.SourceCodeReference("server", "Remote server", 5, 2));

        List<SnippetDiagramSupport.SourceCodeReference> filtered =
            SnippetTypedDiagramSupport.filterValidSourceReferences(
                SnippetDiagramType.SEQUENCE, VALID_SEQUENCE, references);

        assertThat(filtered).containsExactly(
            new SnippetDiagramSupport.SourceCodeReference("script", "Backup script", 1, 3));
    }

    @Test
    void relaxedReferenceFilterNeverFailsOnEmptyOrUnparseableInput() {
        assertThat(SnippetTypedDiagramSupport.filterValidSourceReferences(
            SnippetDiagramType.SEQUENCE, VALID_SEQUENCE, List.of())).isEmpty();
        assertThat(SnippetTypedDiagramSupport.filterValidSourceReferences(
            SnippetDiagramType.SEQUENCE, "not mermaid at all",
            List.of(new SnippetDiagramSupport.SourceCodeReference("script", "x", 1, 1)))).isEmpty();
    }

    @Test
    void headerAndFallbackMetadataStayInSync() {
        assertThat(SnippetTypedDiagramSupport.header(SnippetDiagramType.LOGICAL_STRUCTURE)).isEqualTo("flowchart TD");
        assertThat(SnippetTypedDiagramSupport.header(SnippetDiagramType.SEQUENCE)).isEqualTo("sequenceDiagram");
        assertThat(SnippetTypedDiagramSupport.header(SnippetDiagramType.STATE)).isEqualTo("stateDiagram-v2");
        assertThat(SnippetTypedDiagramSupport.header(SnippetDiagramType.CLASS)).isEqualTo("classDiagram");
        assertThat(SnippetTypedDiagramSupport.header(SnippetDiagramType.ER)).isEqualTo("erDiagram");
        assertThat(SnippetTypedDiagramSupport.hasDeterministicFallback(SnippetDiagramType.LOGICAL_STRUCTURE)).isTrue();
        assertThat(SnippetTypedDiagramSupport.hasDeterministicFallback(SnippetDiagramType.SEQUENCE)).isFalse();
        assertThat(SnippetTypedDiagramSupport.hasDeterministicFallback(SnippetDiagramType.ER)).isFalse();
    }
}
