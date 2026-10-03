package de.kortty.ui;

import com.sithtermfx.ui.TerminalCopyPasteHandler;
import com.sithtermfx.ui.TerminalPanel;
import de.kortty.core.PolicyAwareCopyPasteHandler;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the two overrides that moved from the anonymous panel into {@link KorttyTermWidget.KorttyTerminalPanel}:
 * the policy-aware clipboard handler and the cursor clamp after Clear Buffer. Later PRs add their own
 * hooks to the same class, and a lost override would only show in the running app. Building a panel
 * needs a running JavaFX toolkit, so this checks the declarations; {@code terminalContextMenuActionsSmoke}
 * exercises them.
 */
public class KorttyTerminalPanelTest {

    private static final Class<?> PANEL = KorttyTermWidget.KorttyTerminalPanel.class;

    @Test
    public void thePanelIsAnInnerClassOfTheWidgetBecauseClearBufferNeedsItsTerminal() {
        assertThat(PANEL.getSuperclass()).isEqualTo(TerminalPanel.class);
        assertThat(PANEL.getEnclosingClass()).isEqualTo(KorttyTermWidget.class);
        assertThat(Modifier.isStatic(PANEL.getModifiers())).isFalse();
    }

    @Test
    public void thePanelKeepsThePolicyAwareClipboardHandler() throws Exception {
        Method method = PANEL.getDeclaredMethod("createCopyPasteHandler");
        assertThat(method.getReturnType()).isEqualTo(TerminalCopyPasteHandler.class);
        // The override is only called inside TerminalPanel's constructor, so check the class file.
        assertThat(classFileOf(PANEL)).contains(PolicyAwareCopyPasteHandler.class.getName().replace('.', '/'));
    }

    @Test
    public void thePanelKeepsTheCursorClampAfterClearBuffer() throws Exception {
        Method method = PANEL.getDeclaredMethod("clearBuffer", boolean.class);
        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(classFileOf(PANEL)).contains("scrollY");
    }

    private static String classFileOf(Class<?> type) throws IOException {
        String name = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(name)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
