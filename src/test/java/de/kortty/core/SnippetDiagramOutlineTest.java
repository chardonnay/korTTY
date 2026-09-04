package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetDiagramOutlineTest {

    @Test
    void shortSnippetsAreSentComplete() {
        String script = "#!/bin/bash\nset -e\nrun_backup() {\n  tar -cf out.tar /data\n}\nrun_backup\n";

        SnippetDiagramOutline.Outline outline = SnippetDiagramOutline.of(script);

        assertThat(outline.condensed()).isFalse();
        assertThat(outline.totalLines()).isEqualTo(SnippetDiagramSupport.countLines(script));
        assertThat(outline.shownLines()).isEqualTo(outline.totalLines());
        assertThat(outline.text()).contains("2 | set -e");
        assertThat(outline.text()).contains("4 |   tar -cf out.tar /data");
        assertThat(outline.text()).doesNotContain("omitted");
    }

    @Test
    void longScriptKeepsItsDefinitionsAndCompleteTopLevelFlow() {
        // A script that defines everything before running it: the main flow is at the very end,
        // which a budget that simply truncates would drop — exactly the part a flowchart is about.
        StringBuilder script = new StringBuilder("#!/usr/bin/env bash\n");
        for (int index = 1; index <= 40; index++) {
            script.append("step_").append(index).append("() {\n");
            for (int body = 0; body < 20; body++) {
                script.append("  local value_").append(body).append("=").append(body).append('\n');
            }
            script.append("}\n");
        }
        script.append("main_start=1\n");
        for (int index = 1; index <= 40; index++) {
            script.append("step_").append(index).append('\n');
        }
        script.append("echo done\n");
        String content = script.toString();

        SnippetDiagramOutline.Outline outline = SnippetDiagramOutline.of(content);

        assertThat(outline.condensed()).isTrue();
        assertThat(outline.totalLines()).isGreaterThan(SnippetDiagramOutline.CONDENSE_THRESHOLD_LINES);
        assertThat(outline.shownLines()).isAtMost(SnippetDiagramOutline.MAX_OUTLINE_LINES);
        assertThat(outline.text()).contains("| step_1() {");
        assertThat(outline.text()).contains("| step_40() {");
        assertThat(outline.text()).contains("| main_start=1");
        assertThat(outline.text()).contains("| echo done");
        assertThat(outline.text()).contains("lines omitted …");
        assertThat(outline.text()).doesNotContain("local value_7=7");
        // Original line numbers survive, so a returned code reference still points at the snippet.
        int lastLine = SnippetDiagramSupport.countLines(content) - 1;
        assertThat(outline.text()).contains(lastLine + " | echo done");
    }

    @Test
    void anIndentedFileStillYieldsAnOutline() {
        StringBuilder script = new StringBuilder("class Deployment:\n");
        for (int index = 1; index <= 60; index++) {
            script.append("    def stage_").append(index).append("(self):\n");
            for (int body = 0; body < 8; body++) {
                script.append("        value_").append(body).append(" = ").append(body).append('\n');
            }
        }
        String content = script.toString();

        SnippetDiagramOutline.Outline outline = SnippetDiagramOutline.of(content);

        assertThat(outline.condensed()).isTrue();
        assertThat(outline.text()).contains("| class Deployment:");
        assertThat(outline.text()).contains("|     def stage_1(self):");
        assertThat(outline.text()).contains("|     def stage_60(self):");
        assertThat(outline.shownLines()).isAtLeast(60);
        assertThat(outline.shownLines()).isAtMost(SnippetDiagramOutline.MAX_OUTLINE_LINES);
    }

    @Test
    void licenseHeadersAreDroppedButTheInterpreterLineSurvives() {
        StringBuilder script = new StringBuilder("#!/usr/bin/env bash\n");
        script.append("# Copyright notice line\n".repeat(300));
        script.append("start_service\n");
        script.append("echo ok\n".repeat(200));

        SnippetDiagramOutline.Outline outline = SnippetDiagramOutline.of(script.toString());

        assertThat(outline.text()).startsWith("  1 | #!/usr/bin/env bash");
        assertThat(outline.text()).doesNotContain("Copyright notice line");
        assertThat(outline.text()).contains("| start_service");
    }
}
