package de.kortty.ui;

import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetAnalysisPanelTest {

    @Test
    void restoredEnumNamesSkipWhatNoLongerExists() {
        HardeningOption first = HardeningOption.values()[0];
        assertThat(SnippetAnalysisPanel.parseEnums(HardeningOption.class,
                Arrays.asList(first.name(), "NO_SUCH_OPTION", null, " ")))
            .isEqualTo(EnumSet.of(first));
        assertThat(SnippetAnalysisPanel.parseEnums(HardeningOption.class, null)).isEmpty();

        assertThat(SnippetAnalysisPanel.parseEnum(HostFormat.class, HostFormat.NONE.name().toLowerCase()))
            .isEqualTo(HostFormat.NONE);
        assertThat(SnippetAnalysisPanel.parseEnum(HostFormat.class, "Mainframe")).isNull();
        assertThat(SnippetAnalysisPanel.parseEnum(HostFormat.class, null)).isNull();
    }

    @Test
    void findingTokensSplitLikeThePageJoinsThem() {
        assertThat(SnippetAnalysisPanel.splitTokens("imp:SEC-1,dep:D1")).containsExactly("imp:SEC-1", "dep:D1").inOrder();
        assertThat(SnippetAnalysisPanel.splitTokens("imp:SEC-1,,")).containsExactly("imp:SEC-1");
        assertThat(SnippetAnalysisPanel.splitTokens("")).isEmpty();
        assertThat(SnippetAnalysisPanel.splitTokens(null)).isEqualTo(List.of());
    }

    @Test
    void selectionIsHandedToThePageAsAQuotedLiteral() {
        assertThat(SnippetAnalysisPanel.jsString("imp:SEC-1,dep:D1")).isEqualTo("'imp:SEC-1,dep:D1'");
        // A model-supplied id must not be able to close the literal and run script.
        assertThat(SnippetAnalysisPanel.jsString("imp:x');alert(1);//")).isEqualTo("'imp:x\\');alert(1);//'");
        assertThat(SnippetAnalysisPanel.jsString("a\\b\nc")).isEqualTo("'a\\\\b\\nc'");
        assertThat(SnippetAnalysisPanel.jsString(null)).isEqualTo("''");
    }
}
