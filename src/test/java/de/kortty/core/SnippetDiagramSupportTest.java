package de.kortty.core;

import de.kortty.model.SnippetDiagram;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetDiagramSupportTest {

    @Test
    void contentHashMarksDiagramStaleAfterCodeChange() {
        SnippetDiagram diagram = new SnippetDiagram();
        diagram.setSourceContentSha256(SnippetDiagramSupport.contentHash("echo old"));

        assertThat(SnippetDiagramSupport.isStale(diagram, "echo old")).isFalse();
        assertThat(SnippetDiagramSupport.isStale(diagram, "echo new")).isTrue();
    }

    @Test
    void normalizePlantUmlAddsBoundaries() {
        String normalized = SnippetDiagramSupport.normalizePlantUml("start\nstop");

        assertThat(normalized).startsWith("@startuml");
        assertThat(normalized).endsWith("@enduml");
    }

    @Test
    void fallbackLogicalStructureUsesRenderableActivitySyntax() {
        String plantUml = SnippetDiagramSupport.buildFallbackLogicalStructurePlantUml("""
            BACKUP_DIR="/backup"
            tar -czf "$BACKUP_FILE" "${SOURCE_DIRS[@]}"
            if [ $? -eq 0 ]; then
              echo ok | mail -s Success admin@example.com
            else
              echo failed | mail -s Failed admin@example.com
            fi
            """, "bash");

        assertThat(plantUml).startsWith("@startuml");
        assertThat(plantUml).contains(":Read configured values; <<#EAF7EF>>");
        assertThat(plantUml).contains(":Run main snippet logic; <<#EAF4FF>>");
        assertThat(plantUml).contains("if (Main command succeeds?) then (yes)");
        assertThat(plantUml).contains(":Send success notification; <<#EAF7EF>>");
        assertThat(plantUml).contains(":Send failure notification; <<#FDECEC>>");
        assertThat(plantUml).doesNotContain("component");
        assertThat(plantUml).doesNotContain("package");
        assertThat(plantUml).doesNotContain("class");
        assertThat(plantUml).doesNotContain("actor");
        assertThat(plantUml).endsWith("@enduml");
    }

    @Test
    void ensureReadableActivityColorsColorizesPlainActivityDiagram() {
        String plantUml = SnippetDiagramSupport.ensureReadableActivityColors("""
            @startuml
            start
            :Load default configuration;
            :Scan directory for files;
            if (output format is CSV?) then (yes)
              :Format and print CSV output;
            else (no)
              :Format and print table output;
            endif
            stop
            @enduml
            """);

        assertThat(plantUml).contains(":Load default configuration; <<#EAF7EF>>");
        assertThat(plantUml).contains(":Scan directory for files; <<#EAF4FF>>");
        assertThat(plantUml).contains(":Format and print CSV output; <<#EAF7EF>>");
        assertThat(plantUml).contains(":Format and print table output; <<#FDECEC>>");
        assertThat(plantUml).doesNotContain("#EAF7EF:Load default configuration;");
    }

    @Test
    void ensureReadableActivityColorsMigratesDeprecatedPrefixColorSyntax() {
        String plantUml = SnippetDiagramSupport.ensureReadableActivityColors("""
            @startuml
            start
            #EAF7EF:Load default configuration;
            #FDECEC:Handle failure;
            stop
            @enduml
            """);

        assertThat(plantUml).contains(":Load default configuration; <<#EAF7EF>>");
        assertThat(plantUml).contains(":Handle failure; <<#FDECEC>>");
        assertThat(plantUml).doesNotContain("#EAF7EF:Load default configuration;");
        assertThat(plantUml).doesNotContain("#FDECEC:Handle failure;");
    }

    @Test
    void ensureReadableActivityColorsKeepsNonActivityDiagramUnchanged() {
        String plantUml = """
            @startuml
            Alice -> Bob : hello
            @enduml
            """.trim();

        assertThat(SnippetDiagramSupport.ensureReadableActivityColors(plantUml)).isEqualTo(plantUml);
    }

    @Test
    void applyBackgroundColorAddsSkinparamAfterStart() {
        String plantUml = SnippetDiagramSupport.applyBackgroundColor("""
            @startuml
            start
            stop
            @enduml
            """, "#f4f8ff");

        assertThat(plantUml).contains("@startuml\nskinparam backgroundColor #F4F8FF\nstart");
        assertThat(plantUml).endsWith("@enduml");
    }

    @Test
    void applyBackgroundColorReplacesExistingSkinparam() {
        String plantUml = SnippetDiagramSupport.applyBackgroundColor("""
            @startuml
            skinparam backgroundColor #FFFFFF
            start
            stop
            @enduml
            """, "#202020");

        assertThat(plantUml).contains("skinparam backgroundColor #202020");
        assertThat(plantUml).doesNotContain("skinparam backgroundColor #FFFFFF");
    }

    @Test
    void codeReferenceLabelsIncludeColoredActivitiesAndDecisions() {
        List<String> labels = SnippetDiagramSupport.extractCodeReferenceLabels("""
            @startuml
            start
            :Load default configuration; <<#EAF7EF>>
            if (output format is CSV?) then (yes)
              :Format and print CSV output; <<#EAF4FF>>
            endif
            stop
            @enduml
            """);

        assertThat(labels).containsExactly(
            "Load default configuration",
            "output format is CSV?",
            "Format and print CSV output").inOrder();
    }

    @Test
    void buildCodeReferencesMapsOnlyDistinctLocalMatches() {
        List<SnippetDiagramSupport.CodeReference> references = SnippetDiagramSupport.buildCodeReferences("""
            @startuml
            start
            :Scan directory for files; <<#EAF4FF>>
            :Sort files by selected criterion; <<#EAF4FF>>
            stop
            @enduml
            """, """
            SOURCE_DIR="/tmp"
            find "$SOURCE_DIR" -type f -name '*.log'
            sort "$file_list"
            """);

        SnippetDiagramSupport.CodeReference scanReference = findReference(references, "Scan directory for files");
        SnippetDiagramSupport.CodeReference sortReference = findReference(references, "Sort files by selected criterion");

        assertThat(scanReference.startLine()).isEqualTo(2);
        assertThat(scanReference.endLine()).isEqualTo(2);
        assertThat(scanReference.excerpt()).contains("2 | find \"$SOURCE_DIR\" -type f -name '*.log'");
        assertThat(sortReference.startLine()).isEqualTo(3);
        assertThat(sortReference.excerpt()).contains("3 | sort \"$file_list\"");
    }

    @Test
    void buildCodeReferencesSkipsAmbiguousMatches() {
        List<SnippetDiagramSupport.CodeReference> references = SnippetDiagramSupport.buildCodeReferences("""
            @startuml
            start
            :Print output;
            stop
            @enduml
            """, """
            echo output
            echo output
            """);

        assertThat(references).isEmpty();
    }

    @Test
    void fallbackLogicalStructureBuildsCodeReferencesForKnownBlocks() {
        String content = """
            BACKUP_DIR="/backup"
            tar -czf "$BACKUP_FILE" "${SOURCE_DIRS[@]}"
            if [ $? -eq 0 ]; then
              echo ok | mail -s Success admin@example.com
            else
              echo failed | mail -s Failed admin@example.com
            fi
            """;
        String plantUml = SnippetDiagramSupport.buildFallbackLogicalStructurePlantUml(content, "bash");

        List<SnippetDiagramSupport.CodeReference> references =
            SnippetDiagramSupport.buildCodeReferences(plantUml, content);

        assertThat(findReference(references, "Read configured values").startLine()).isEqualTo(1);
        assertThat(findReference(references, "Run main snippet logic").startLine()).isEqualTo(2);
        assertThat(findReference(references, "Main command succeeds?").startLine()).isEqualTo(3);
        assertThat(findReference(references, "Send success notification").startLine()).isEqualTo(4);
        assertThat(findReference(references, "Send failure notification").startLine()).isEqualTo(6);
    }

    @Test
    void buildValidatedCodeReferencesKeepsAiMapAndRejectsInvalidEntries() {
        List<SnippetDiagramSupport.CodeReference> references =
            SnippetDiagramSupport.buildValidatedCodeReferences("""
                @startuml
                start
                :Load default configuration; <<#EAF7EF>>
                :Run main snippet logic; <<#EAF4FF>>
                stop
                @enduml
                """, """
                CONFIG=/etc/tool.conf
                tool --config "$CONFIG"
                """, List.of(
                new SnippetDiagramSupport.SourceCodeReference("Load default configuration", 1, 1),
                new SnippetDiagramSupport.SourceCodeReference("Run main snippet logic", 2, 2),
                new SnippetDiagramSupport.SourceCodeReference("Missing diagram label", 1, 1),
                new SnippetDiagramSupport.SourceCodeReference("Run main snippet logic", 4, 4)));

        assertThat(references).hasSize(2);
        assertThat(findReference(references, "Load default configuration").excerpt()).contains("1 | CONFIG=/etc/tool.conf");
        assertThat(findReference(references, "Run main snippet logic").excerpt()).contains("2 | tool --config \"$CONFIG\"");
    }

    @Test
    void buildExpandedCodeReferencesUsesAiMapAndAddsLocalMatchesForMissingLabels() {
        List<SnippetDiagramSupport.CodeReference> references =
            SnippetDiagramSupport.buildExpandedCodeReferences("""
                @startuml
                start
                :Read configured values; <<#EAF7EF>>
                :Scan directory for files; <<#EAF4FF>>
                :Sort files by selected criterion; <<#EAF4FF>>
                stop
                @enduml
                """, """
                SOURCE_DIR="/tmp"
                find "$SOURCE_DIR" -type f -name '*.log'
                sort "$file_list"
                """, List.of(
                new SnippetDiagramSupport.SourceCodeReference("Read configured values", 1, 1)));

        assertThat(references).hasSize(3);
        assertThat(findReference(references, "Read configured values").startLine()).isEqualTo(1);
        assertThat(findReference(references, "Scan directory for files").startLine()).isEqualTo(2);
        assertThat(findReference(references, "Sort files by selected criterion").startLine()).isEqualTo(3);
    }

    private SnippetDiagramSupport.CodeReference findReference(
        List<SnippetDiagramSupport.CodeReference> references,
        String label) {

        return references.stream()
            .filter(reference -> label.equals(reference.label()))
            .findFirst()
            .orElseThrow();
    }
}
