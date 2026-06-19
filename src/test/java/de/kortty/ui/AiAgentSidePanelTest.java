package de.kortty.ui;

import de.kortty.core.LanguageManager;
import org.testng.annotations.Test;

import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class AiAgentSidePanelTest {

    @Test
    void formatTabTitleUsesNameWhenPresentOtherwiseIndexedTerminal() {
        // Restore the global locale afterwards so this test can't leak state into other tests.
        Locale previous = LanguageManager.getInstance().getCurrentLocale();
        try {
            LanguageManager.getInstance().setLocale(Locale.ENGLISH);
            assertThat(AiAgentSidePanel.formatTabTitle(2, null)).isEqualTo("Terminal 2");
            assertThat(AiAgentSidePanel.formatTabTitle(1, "   ")).isEqualTo("Terminal 1");
            assertThat(AiAgentSidePanel.formatTabTitle(3, "prod-web")).isEqualTo("prod-web");
        } finally {
            if (previous != null) {
                LanguageManager.getInstance().setLocale(previous);
            }
        }
    }
}
