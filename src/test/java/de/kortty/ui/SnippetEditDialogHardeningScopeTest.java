package de.kortty.ui;

import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.core.WorkflowScriptSupport.InputHardeningOption;
import org.testng.annotations.Test;

import java.util.EnumSet;

import static com.google.common.truth.Truth.assertThat;

class SnippetEditDialogHardeningScopeTest {

    @Test
    void anyClassicOrInputHardeningRuleRequiresTheWholeSnippet() {
        assertThat(SnippetEditDialog.requiresWholeSnippetForHardening(
            EnumSet.noneOf(HardeningOption.class), InputHardeningConfig.disabled())).isFalse();
        assertThat(SnippetEditDialog.requiresWholeSnippetForHardening(
            EnumSet.of(HardeningOption.STRICT_MODE), InputHardeningConfig.disabled())).isTrue();
        assertThat(SnippetEditDialog.requiresWholeSnippetForHardening(
            EnumSet.noneOf(HardeningOption.class),
            new InputHardeningConfig(EnumSet.of(InputHardeningOption.PARAM_VALIDATION), 0))).isTrue();
    }

    @Test
    void wholeSnippetHardeningOverridesAPartialSelectionTarget() {
        String fullContent = "#!/usr/bin/env bash\nprintf 'start\\n'\nprintf 'end\\n'\n";
        int start = fullContent.indexOf("start");
        int end = start + "start".length();

        SnippetEditDialog.CodeImprovementTarget partial = SnippetEditDialog.resolveCodeImprovementTarget(
            fullContent, start, end, "start", false);
        assertThat(partial.start()).isEqualTo(start);
        assertThat(partial.end()).isEqualTo(end);
        assertThat(partial.text()).isEqualTo("start");

        SnippetEditDialog.CodeImprovementTarget whole = SnippetEditDialog.resolveCodeImprovementTarget(
            fullContent, start, end, "start", true);
        assertThat(whole.start()).isEqualTo(0);
        assertThat(whole.end()).isEqualTo(fullContent.length());
        assertThat(whole.text()).isEqualTo(fullContent);
    }

}
