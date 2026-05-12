package de.kortty.core;

import de.kortty.model.SnippetDiagram;
import org.testng.annotations.Test;

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
        assertThat(plantUml).contains(":Read configured values;");
        assertThat(plantUml).contains("if (Main command succeeds?) then (yes)");
        assertThat(plantUml).contains(":Send success notification;");
        assertThat(plantUml).contains(":Send failure notification;");
        assertThat(plantUml).doesNotContain("component");
        assertThat(plantUml).doesNotContain("package");
        assertThat(plantUml).endsWith("@enduml");
    }
}
