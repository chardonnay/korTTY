package de.kortty.ui;

import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.model.SnippetDiagram;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class WorkflowScriptGeneratorTest {

    @Test
    void diagramGenerationFallsBackLocallyWhenNoAiProfileIsAvailable() {
        WorkflowScriptGenerator generator = new WorkflowScriptGenerator(null);
        WorkflowScriptGenerator.RunExportData run =
            new WorkflowScriptGenerator.RunExportData(null, null, "deploy", null, "Linux");
        SnippetDiagram existing = new SnippetDiagram();
        existing.setTitle("Existing flow");

        SnippetDiagram diagram = generator.generateDiagram(
            run,
            "CONFIG=/etc/tool.conf\nif tool; then echo success; else echo failure; fi\n",
            WorkflowScriptSupport.ScriptLanguage.BASH,
            "",
            existing);

        assertThat(diagram.getId()).isEqualTo(existing.getId());
        assertThat(SnippetDiagramSupport.isRenderableMermaid(diagram.getMermaidSource())).isTrue();
        assertThat(diagram.getMermaidSource()).contains("start_1([\"Start\"])");
        assertThat(diagram.getMermaidSource()).contains("stop_1([\"Stop\"])");
        assertThat(diagram.getCodeReferences()).isNotEmpty();
    }
}
